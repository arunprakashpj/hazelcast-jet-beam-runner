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

import com.hazelcast.jet.Traverser;
import com.hazelcast.jet.Traversers;
import com.hazelcast.jet.beam.Utils;
import com.hazelcast.jet.core.AbstractProcessor;
import com.hazelcast.jet.core.Processor;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.ProcessorSupplier;
import com.hazelcast.jet.core.Watermark;
import com.hazelcast.nio.Address;
import org.apache.beam.runners.core.construction.SerializablePipelineOptions;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.io.UnboundedSource;
import org.apache.beam.sdk.io.UnboundedSource.UnboundedReader;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.util.WindowedValue;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static com.hazelcast.jet.impl.util.ExceptionUtil.rethrow;

public class UnboundedSourceP<T, CMT extends UnboundedSource.CheckpointMark> extends AbstractProcessor {

    private UnboundedSource.UnboundedReader<T>[] readers;
    private final List<? extends UnboundedSource<T, CMT>> allShards;
    private final PipelineOptions options;
    private final Coder outputCoder;
    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private final String ownerId; //do not remove it, very useful for debugging

    private Traverser<Object> traverser;

    private UnboundedSourceP(List<? extends UnboundedSource<T, CMT>> allShards, PipelineOptions options, Coder outputCoder, String ownerId) {
        this.allShards = allShards;
        this.options = options;
        this.outputCoder = outputCoder;
        this.ownerId = ownerId;
    }

    @Override
    protected void init(@Nonnull Processor.Context context) throws IOException {
        List<? extends UnboundedSource<T, CMT>> myShards =
                Utils.roundRobinSubList(allShards, context.globalProcessorIndex(), context.totalParallelism());
        this.readers = createReaders(myShards, options);

        Function<UnboundedReader<T>, byte[]> mapFn = (reader) -> Utils.encodeWindowedValue(
                WindowedValue.timestampedValueInGlobalWindow(reader.getCurrent(), reader.getCurrentTimestamp()), outputCoder);

        if (myShards.size() == 0) {
            traverser = Traversers.empty();
        } else if (myShards.size() == 1) {
            traverser = new SingleReaderTraverser<>(readers[0], mapFn);
        } else {
            traverser = new CoalescingTraverser<>(readers, mapFn);
        }

        for (UnboundedReader<T> reader : readers) {
            reader.start();
        }
    }

    @Override
    public boolean complete() {
        emitFromTraverser(traverser);
        return readers.length == 0;
    }

    @Override
    public boolean isCooperative() {
        return false;
    }

    @Override
    public void close() {
        Arrays.stream(readers).forEach(UnboundedSourceP::stopReader);
        Arrays.fill(readers, null);
    }

    @SuppressWarnings("unchecked")
    private static <T, CMT extends UnboundedSource.CheckpointMark> UnboundedSource.UnboundedReader<T>[] createReaders(
            List<? extends UnboundedSource<T, CMT>> shards, PipelineOptions options) {
        return shards.stream()
                .map(shard -> createReader(options, shard))
                .toArray(UnboundedSource.UnboundedReader[]::new);
    }

    private static long[] initWatermarks(int size) {
        long[] watermarks = new long[size];
        Arrays.fill(watermarks, Long.MIN_VALUE);
        return watermarks;
    }

    private static <T> UnboundedSource.UnboundedReader<T> createReader(PipelineOptions options, UnboundedSource<T, ?> shard) {
        try {
            return shard.createReader(options, null);
        } catch (IOException e) {
            throw rethrow(e);
        }
    }

    private static void stopReader(UnboundedSource.UnboundedReader<?> reader) {
        try {
            reader.close();
        } catch (IOException e) {
            throw rethrow(e);
        }
    }

    private static long getMin(long[] instants) {
        long min = instants[0];
        for (int i = 1; i < instants.length; i++) {
            if (instants[i] < min) {
                min = instants[i];
            }
        }
        return min;
    }

    public static <T, CMT extends UnboundedSource.CheckpointMark> ProcessorMetaSupplier supplier(
            UnboundedSource<T, CMT> unboundedSource,
            SerializablePipelineOptions options,
            Coder outputCoder,
            String ownerId
    ) {
        return new UnboundedSourceMetaProcessorSupplier<>(unboundedSource, options, outputCoder, ownerId);
    }

    private static class UnboundedSourceMetaProcessorSupplier<T, CMT extends UnboundedSource.CheckpointMark> implements ProcessorMetaSupplier {

        private final UnboundedSource<T, CMT> unboundedSource;
        private final SerializablePipelineOptions options;
        private final Coder outputCoder;
        private final String ownerId;

        private List<? extends UnboundedSource<T, CMT>> shards;

        private UnboundedSourceMetaProcessorSupplier(
                UnboundedSource<T, CMT> unboundedSource,
                SerializablePipelineOptions options,
                Coder outputCoder,
                String ownerId
        ) {
            this.unboundedSource = unboundedSource;
            this.options = options;
            this.outputCoder = outputCoder;
            this.ownerId = ownerId;
        }

        @Override
        public void init(@Nonnull ProcessorMetaSupplier.Context context) throws Exception {
            shards = unboundedSource.split(context.totalParallelism(), options.get());
        }

        @Nonnull
        @Override
        public Function<? super Address, ? extends ProcessorSupplier> get(@Nonnull List<Address> addresses) {
            return address -> ProcessorSupplier.of(() -> new UnboundedSourceP<>(shards, options.get(), outputCoder, ownerId));
        }
    }

    private static class SingleReaderTraverser<InputT> implements Traverser<Object> {
        private final UnboundedReader<InputT> reader;
        private final Function<UnboundedReader<InputT>, byte[]> mapFn;
        private long lastWatermark = Long.MIN_VALUE;

        SingleReaderTraverser(UnboundedReader<InputT> reader, Function<UnboundedReader<InputT>, byte[]> mapFn) {
            this.reader = reader;
            this.mapFn = mapFn;
        }

        @Override
        public Object next() {
            long wm = reader.getWatermark().getMillis();
            if (wm > lastWatermark) {
                lastWatermark = wm;
                return new Watermark(wm);
            }
            try {
                return reader.advance() ? mapFn.apply(reader) : null;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class CoalescingTraverser<InputT> implements Traverser<Object> {
        private final UnboundedReader<InputT>[] readers;
        private final Function<UnboundedReader<InputT>, byte[]> mapFn;

        private int currentReaderIndex;
        private long minWatermark = Long.MIN_VALUE;
        private long lastSentWatermark = Long.MIN_VALUE;
        private long[] watermarks;

        CoalescingTraverser(UnboundedReader<InputT>[] readers, Function<UnboundedReader<InputT>, byte[]> mapFn) {
            this.readers = readers;
            watermarks = initWatermarks(readers.length);
            this.mapFn = mapFn;
        }

        @Override
        public Object next() {
            if (minWatermark > lastSentWatermark) {
                lastSentWatermark = minWatermark;
                return new Watermark(lastSentWatermark);
            }

            try {
                //trying to fetch a value from the next reader
                for (int i = 0; i < readers.length; i++) {
                    currentReaderIndex++;
                    if (currentReaderIndex >= readers.length) {
                        currentReaderIndex = 0;
                    }
                    UnboundedSource.UnboundedReader<InputT> currentReader = readers[currentReaderIndex];
                    if (currentReader.advance()) {
                        long currentWatermark = currentReader.getWatermark().getMillis();
                        long origWatermark = watermarks[currentReaderIndex];
                        if (currentWatermark > origWatermark) {
                            watermarks[currentReaderIndex] = currentWatermark; //todo: we should probably do this only on a timer...
                            if (origWatermark == minWatermark) {
                                minWatermark = getMin(watermarks);
                            }
                        }
                        return mapFn.apply(currentReader);
                    }
                }

                //all advances have failed
                return null;
            } catch (IOException e) {
                throw rethrow(e);
            }
        }
    }
}
