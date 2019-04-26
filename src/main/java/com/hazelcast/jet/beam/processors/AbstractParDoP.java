/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.beam.processors;

import com.hazelcast.jet.beam.DAGBuilder;
import com.hazelcast.jet.beam.Utils;
import com.hazelcast.jet.beam.metrics.JetMetricsContainer;
import com.hazelcast.jet.core.Edge;
import com.hazelcast.jet.core.Inbox;
import com.hazelcast.jet.core.Outbox;
import com.hazelcast.jet.core.Processor;
import com.hazelcast.jet.core.Watermark;
import com.hazelcast.jet.function.SupplierEx;
import org.apache.beam.runners.core.DoFnRunner;
import org.apache.beam.runners.core.DoFnRunners;
import org.apache.beam.runners.core.InMemoryStateInternals;
import org.apache.beam.runners.core.NullSideInputReader;
import org.apache.beam.runners.core.SideInputHandler;
import org.apache.beam.runners.core.SideInputReader;
import org.apache.beam.runners.core.construction.SerializablePipelineOptions;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.metrics.MetricsEnvironment;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.reflect.DoFnInvoker;
import org.apache.beam.sdk.transforms.reflect.DoFnInvokers;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.WindowingStrategy;
import org.apache.beam.vendor.guava.v20_0.com.google.common.collect.Lists;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.hazelcast.jet.beam.Utils.serde;
import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableMap;

abstract class AbstractParDoP<InputT, OutputT> implements Processor {

    private final SerializablePipelineOptions pipelineOptions;
    private final DoFn<InputT, OutputT> doFn;
    private final WindowingStrategy<?, ?> windowingStrategy;
    private final Map<TupleTag<?>, int[]> outputCollToOrdinals;
    private final TupleTag<OutputT> mainOutputTag;
    private final Coder<InputT> inputCoder;
    private final Map<PCollectionView<?>, Coder<?>> sideInputCoders;
    private final Map<TupleTag<?>, Coder<?>> outputCoders;
    private final Coder<InputT> inputValueCoder;
    private final Map<TupleTag<?>, Coder<?>> outputValueCoders;
    private final Map<Integer, PCollectionView<?>> ordinalToSideInput;
    private final String ownerId; //do not remove, useful for debugging
    private final String stepId;

    DoFnRunner<InputT, OutputT> doFnRunner;

    private DoFnInvoker<InputT, OutputT> doFnInvoker;
    private SideInputHandler sideInputHandler;
    JetOutputManager outputManager;
    private JetMetricsContainer metricsContainer;
    private SimpleInbox bufferedItems;
    private Set<Integer> completedSideInputs = new HashSet<>();
    private SideInputReader sideInputReader;
    private Outbox outbox;

    AbstractParDoP(
            DoFn<InputT, OutputT> doFn,
            WindowingStrategy<?, ?> windowingStrategy,
            Map<TupleTag<?>, int[]> outputCollToOrdinals,
            SerializablePipelineOptions pipelineOptions,
            TupleTag<OutputT> mainOutputTag,
            Coder<InputT> inputCoder,
            Map<PCollectionView<?>, Coder<?>> sideInputCoders,
            Map<TupleTag<?>, Coder<?>> outputCoders,
            Coder<InputT> inputValueCoder,
            Map<TupleTag<?>, Coder<?>> outputValueCoders,
            Map<Integer, PCollectionView<?>> ordinalToSideInput,
            String ownerId,
            String stepId
    ) {
        this.pipelineOptions = pipelineOptions;
        this.doFn = serde(doFn);
        this.windowingStrategy = windowingStrategy;
        this.outputCollToOrdinals = outputCollToOrdinals;
        this.mainOutputTag = mainOutputTag;
        this.inputCoder = inputCoder;
        this.sideInputCoders = sideInputCoders.entrySet().stream()
                .collect(
                        Collectors.toMap(
                                e -> e.getKey(),
                                e -> Utils.deriveIterableValueCoder((WindowedValue.FullWindowedValueCoder) e.getValue())
                        )
                );
        this.outputCoders = outputCoders;
        this.inputValueCoder = inputValueCoder;
        this.outputValueCoders = outputValueCoders;
        this.ordinalToSideInput = ordinalToSideInput;
        this.ownerId = ownerId;
        this.stepId = stepId;
    }

    @Override
    public void init(@Nonnull Outbox outbox, @Nonnull Context context) {
        this.outbox = outbox;
        metricsContainer = new JetMetricsContainer(stepId, context);
        MetricsEnvironment.setCurrentContainer(metricsContainer); //todo: this is correct only as long as the processor is non-cooperative

        doFnInvoker = DoFnInvokers.invokerFor(doFn);
        doFnInvoker.invokeSetup();

        if (ordinalToSideInput.isEmpty()) {
            sideInputReader = NullSideInputReader.of(emptyList());
        } else {
            bufferedItems = new SimpleInbox();
            sideInputHandler = new SideInputHandler(ordinalToSideInput.values(), InMemoryStateInternals.forKey(null));
            sideInputReader = sideInputHandler;
        }

        outputManager = new JetOutputManager(outbox, outputCoders, outputCollToOrdinals);

        doFnRunner = getDoFnRunner(
                pipelineOptions.get(),
                doFn,
                sideInputReader,
                outputManager,
                mainOutputTag,
                Lists.newArrayList(outputCollToOrdinals.keySet()),
                inputValueCoder,
                outputValueCoders,
                windowingStrategy
        );
    }

    protected abstract DoFnRunner<InputT, OutputT> getDoFnRunner(
            PipelineOptions pipelineOptions,
            DoFn<InputT, OutputT> doFn,
            SideInputReader sideInputReader,
            JetOutputManager outputManager,
            TupleTag<OutputT> mainOutputTag,
            List<TupleTag<?>> additionalOutputTags,
            Coder<InputT> inputValueCoder,
            Map<TupleTag<?>, Coder<?>> outputValueCoders,
            WindowingStrategy<?, ?> windowingStrategy
    );


    @Override
    public boolean isCooperative() {
        return false; //todo: re-examine later, we should be non-cooperative for doFns that do I/O, can be cooperative for others
    }

    @Override
    public void close() {
        doFnInvoker.invokeTeardown();
    }

    @Override
    public void process(int ordinal, @Nonnull Inbox inbox) {
        if (!outputManager.tryFlush()) {
            // don't process more items until outputManager is empty
            return;
        }
        PCollectionView<?> sideInputView = ordinalToSideInput.get(ordinal);
        if (sideInputView != null) {
            processSideInput(sideInputView, inbox);
        } else {
            if (bufferedItems != null) {
                processBufferedRegularItems(inbox);
            } else {
                processNonBufferedRegularItems(inbox);
            }
        }
    }

    private void processSideInput(PCollectionView<?> sideInputView, Inbox inbox) {
        for (byte[] value; (value = (byte[]) inbox.poll()) != null; ) {
            Coder<?> sideInputCoder = sideInputCoders.get(sideInputView);
            WindowedValue<Iterable<?>> windowedValue = Utils.decodeWindowedValue(value, sideInputCoder);
            sideInputHandler.addSideInputValue(sideInputView, windowedValue);
        }
    }

    private void processNonBufferedRegularItems(Inbox inbox) {
        startRunnerBundle(doFnRunner);
        for (byte[] value; (value = (byte[]) inbox.poll()) != null; ) {
            WindowedValue<InputT> windowedValue = Utils.decodeWindowedValue(value, inputCoder);
            processElementWithRunner(doFnRunner, windowedValue);
            if (!outputManager.tryFlush()) {
                break;
            }
        }
        finishRunnerBundle(doFnRunner);
        // finishBundle can also add items to outputManager, they will be flushed in tryProcess() or complete()
    }

    protected void startRunnerBundle(DoFnRunner<InputT, OutputT> runner) {
        //System.out.println(AbstractParDoP.class.getSimpleName() + " UPDATE ownerId = " + ownerId); //useful for debugging
        runner.startBundle();
    }

    protected void processElementWithRunner(DoFnRunner<InputT, OutputT> runner, WindowedValue<InputT> windowedValue) {
        //System.out.println(AbstractParDoP.class.getSimpleName() + " UPDATE ownerId = " + ownerId + ", windowedValue = " + windowedValue); //useful for debugging
        runner.processElement(windowedValue);
    }

    protected void finishRunnerBundle(DoFnRunner<InputT, OutputT> runner) {
        runner.finishBundle();
    }

    @SuppressWarnings("unchecked")
    private void processBufferedRegularItems(Inbox inbox) {
        for (byte[] value; (value = (byte[]) inbox.poll()) != null; ) {
            bufferedItems.add(value);
        }
    }

    @Override
    public boolean tryProcess() {
        return outputManager.tryFlush();
    }

    @Override
    public boolean tryProcessWatermark(@Nonnull Watermark watermark) {
        return outbox.offer(watermark);
    }

    @Override
    public boolean completeEdge(int ordinal) {
        if (ordinalToSideInput.get(ordinal) == null) {
            return true; // ignore non-side-input edges
        }
        completedSideInputs.add(ordinal);
        if (completedSideInputs.size() != ordinalToSideInput.size()) {
            // there are more side inputs to complete
            return true;
        }
        processNonBufferedRegularItems(bufferedItems);
        if (bufferedItems.isEmpty()) {
            bufferedItems = null;
            return true;
        }
        return false;
    }

    @Override
    public boolean complete() {
        //System.out.println(ParDoP.class.getSimpleName() + " COMPLETE ownerId = " + ownerId); //useful for debugging
        //if (ownerId.startsWith("8 ")) System.out.println(ParDoP.class.getSimpleName() + " COMPLETE ownerId = " + ownerId); //useful for debugging
        boolean successful = outputManager.tryFlush();
        if (successful) {
            metricsContainer.flush();
            MetricsEnvironment.setCurrentContainer(null);  //todo: this is correct only as long as the processor is non-cooperative
        }
        return successful;
    }

    /**
     * An output manager that stores the output in an ArrayList, one for each
     * output ordinal, and a way to drain to outbox ({@link #tryFlush()}).
     */
    static class JetOutputManager implements DoFnRunners.OutputManager {

        private final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        private final Outbox outbox;
        private final Map<TupleTag<?>, Coder<?>> outputCoders;
        private final Map<TupleTag<?>, int[]> outputCollToOrdinals;
        private final List<Object>[] outputBuckets;

        // the flush position to continue flushing to outbox
        private int currentBucket, currentItem;

        @SuppressWarnings("unchecked")
        JetOutputManager(Outbox outbox, Map<TupleTag<?>, Coder<?>> outputCoders, Map<TupleTag<?>, int[]> outputCollToOrdinals) {
            this.outbox = outbox;
            this.outputCoders = outputCoders;
            this.outputCollToOrdinals = outputCollToOrdinals;
            assert !outputCollToOrdinals.isEmpty();
            int maxOrdinal = outputCollToOrdinals.values().stream().flatMapToInt(IntStream::of).max().orElse(-1);
            outputBuckets = new List[maxOrdinal + 1];
            Arrays.setAll(outputBuckets, i -> new ArrayList<>());
        }

        @Override
        public <T> void output(TupleTag<T> tag, WindowedValue<T> outputValue) {
            assert currentBucket == 0 && currentItem == 0 : "adding output while flushing";
            Coder<?> coder = outputCoders.get(tag);
            byte[] output = Utils.encodeWindowedValue(outputValue, coder, baos);
            for (int ordinal : outputCollToOrdinals.get(tag)) {
                outputBuckets[ordinal].add(output);
            }
        }

        @CheckReturnValue
        boolean tryFlush() {
            for (; currentBucket < outputBuckets.length; currentBucket++) {
                List<Object> bucket = outputBuckets[currentBucket];
                for (; currentItem < bucket.size(); currentItem++) {
                    if (!outbox.offer(currentBucket, bucket.get(currentItem))) {
                        return false;
                    }
                }
                bucket.clear();
                currentItem = 0;
            }
            currentBucket = 0;
            int sum = 0;
            for (List<Object> outputBucket : outputBuckets) {
                sum += outputBucket.size();
            }
            return sum == 0;
        }
    }

    abstract static class AbstractSupplier<InputT, OutputT> implements SupplierEx<Processor>, DAGBuilder.WiringListener {

        protected final String ownerId;
        private final String stepId;

        private final SerializablePipelineOptions pipelineOptions;
        private final DoFn<InputT, OutputT> doFn;
        private final WindowingStrategy<?, ?> windowingStrategy;
        private final TupleTag<OutputT> mainOutputTag;
        private final Map<TupleTag<?>, List<Integer>> outputCollToOrdinals;
        private final Coder<InputT> inputCoder;
        private final Map<PCollectionView<?>, Coder<?>> sideInputCoders;
        private final Map<TupleTag<?>, Coder<?>> outputCoders;
        private final Coder<InputT> inputValueCoder;
        private final Map<TupleTag<?>, Coder<?>> outputValueCoders;
        private final List<PCollectionView<?>> sideInputs;

        private final Map<Integer, PCollectionView<?>> ordinalToSideInput = new HashMap<>();

        AbstractSupplier(
                String stepId,
                String ownerId,
                DoFn<InputT, OutputT> doFn,
                WindowingStrategy<?, ?> windowingStrategy,
                SerializablePipelineOptions pipelineOptions,
                TupleTag<OutputT> mainOutputTag,
                Set<TupleTag<OutputT>> allOutputTags,
                Coder<InputT> inputCoder,
                Map<PCollectionView<?>, Coder<?>> sideInputCoders,
                Map<TupleTag<?>, Coder<?>> outputCoders,
                Coder<InputT> inputValueCoder,
                Map<TupleTag<?>, Coder<?>> outputValueCoders,
                List<PCollectionView<?>> sideInputs
        ) {
            this.stepId = stepId;
            this.ownerId = ownerId;
            this.pipelineOptions = pipelineOptions;
            this.doFn = doFn;
            this.windowingStrategy = windowingStrategy;
            this.outputCollToOrdinals = allOutputTags.stream().collect(Collectors.toMap(Function.identity(), t -> new ArrayList<>()));
            this.mainOutputTag = mainOutputTag;
            this.inputCoder = inputCoder;
            this.sideInputCoders = sideInputCoders;
            this.outputCoders = outputCoders;
            this.inputValueCoder = inputValueCoder;
            this.outputValueCoders = outputValueCoders;
            this.sideInputs = sideInputs;
        }

        @Override
        public Processor getEx() {
            if (ordinalToSideInput.size() != sideInputs.size()) throw new RuntimeException("Oops");
            return getEx(
                    doFn,
                    windowingStrategy,
                    outputCollToOrdinals.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().stream().mapToInt(i -> i).toArray())),
                    pipelineOptions,
                    mainOutputTag,
                    inputCoder,
                    unmodifiableMap(sideInputCoders),
                    unmodifiableMap(outputCoders),
                    inputValueCoder,
                    unmodifiableMap(outputValueCoders),
                    unmodifiableMap(ordinalToSideInput),
                    ownerId,
                    stepId
            );
        }

        abstract Processor getEx(
                DoFn<InputT, OutputT> doFn,
                WindowingStrategy<?, ?> windowingStrategy,
                Map<TupleTag<?>, int[]> outputCollToOrdinals,
                SerializablePipelineOptions pipelineOptions,
                TupleTag<OutputT> mainOutputTag,
                Coder<InputT> inputCoder,
                Map<PCollectionView<?>, Coder<?>> sideInputCoders,
                Map<TupleTag<?>, Coder<?>> outputCoders,
                Coder<InputT> inputValueCoder,
                Map<TupleTag<?>, Coder<?>> outputValueCoders,
                Map<Integer, PCollectionView<?>> ordinalToSideInput,
                String ownerId,
                String stepId
        );

        @Override
        public void isOutboundEdgeOfVertex(Edge edge, String edgeId, String pCollId, String vertexId) {
            if (ownerId.equals(vertexId)) {
                List<Integer> ordinals = outputCollToOrdinals.get(new TupleTag<>(pCollId));
                if (ordinals == null) throw new RuntimeException("Oops"); //todo

                ordinals.add(edge.getSourceOrdinal());
            }
        }

        @Override
        public void isInboundEdgeOfVertex(Edge edge, String edgeId, String pCollId, String vertexId) {
            if (ownerId.equals(vertexId)) {
                for (PCollectionView<?> pCollectionView : sideInputs) {
                    if (edgeId.equals(Utils.getTupleTagId(pCollectionView))) {
                        ordinalToSideInput.put(edge.getDestOrdinal(), pCollectionView);
                        break;
                    }
                }
            }
        }
    }

    private class SimpleInbox implements Inbox {
        private Deque<Object> items = new ArrayDeque<>();

        public void add(Object item) {
            items.add(item);
        }

        @Override
        public boolean isEmpty() {
            return items.isEmpty();
        }

        @Override
        public Object peek() {
            return items.peek();
        }

        @Override
        public Object poll() {
            return items.poll();
        }

        @Override
        public void remove() {
            items.remove();
        }
    }
}
