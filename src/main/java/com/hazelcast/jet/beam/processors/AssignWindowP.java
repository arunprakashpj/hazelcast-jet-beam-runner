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

import com.hazelcast.jet.beam.Utils;
import com.hazelcast.jet.core.AbstractProcessor;
import com.hazelcast.jet.core.Processor;
import com.hazelcast.jet.core.ResettableSingletonTraverser;
import com.hazelcast.jet.function.SupplierEx;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.WindowFn;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.values.WindowingStrategy;
import org.apache.beam.vendor.guava.v20_0.com.google.common.collect.Iterables;
import org.joda.time.Instant;

import javax.annotation.Nonnull;
import java.util.Collection;

public class AssignWindowP<InputT> extends AbstractProcessor {

    @SuppressWarnings("FieldCanBeLocal")
    private final String ownerId; //do not remove, useful for debugging

    private final ResettableSingletonTraverser<byte[]> traverser = new ResettableSingletonTraverser<>();
    private final FlatMapper<byte[], byte[]> flatMapper;
    private final WindowAssignContext<InputT> windowAssignContext;

    private AssignWindowP(
            Coder inputCoder,
            Coder outputCoder,
            WindowingStrategy<InputT, BoundedWindow> windowingStrategy,
            String ownerId
    ) {
        this.ownerId = ownerId;

        windowAssignContext = new WindowAssignContext<>(windowingStrategy.getWindowFn());

        flatMapper = flatMapper(item -> {
            Collection<BoundedWindow> windows;
            WindowedValue<InputT> inputValue = Utils.decodeWindowedValue(item, inputCoder);
            windowAssignContext.setValue(inputValue);
            try {
                windows = windowingStrategy.getWindowFn().assignWindows(windowAssignContext);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            WindowedValue<InputT> outputValue = WindowedValue.of(inputValue.getValue(), inputValue.getTimestamp(), windows, inputValue.getPane());
            traverser.accept(Utils.encodeWindowedValue(outputValue, outputCoder));
            return traverser;
        });
    }

    @SuppressWarnings("unchecked")
    @Override
    protected boolean tryProcess(int ordinal, @Nonnull Object item) {
        return flatMapper.tryProcess((byte[]) item);
    }

    public static <T> SupplierEx<Processor> supplier(
            Coder inputCoder,
            Coder outputCoder,
            WindowingStrategy<T, BoundedWindow> windowingStrategy,
            String ownerId
    ) {
        return () -> new AssignWindowP<>(inputCoder, outputCoder, windowingStrategy, ownerId);
    }

    private static class WindowAssignContext<InputT> extends WindowFn<InputT, BoundedWindow>.AssignContext {
        private WindowedValue<InputT> value;

        WindowAssignContext(WindowFn<InputT, BoundedWindow> fn) {
            fn.super();
        }

        public void setValue(WindowedValue<InputT> value) {
            if (Iterables.size(value.getWindows()) != 1) {
                throw new IllegalArgumentException(
                        String.format(
                                "%s passed to window assignment must be in a single window, but it was in %s: %s",
                                WindowedValue.class.getSimpleName(),
                                Iterables.size(value.getWindows()),
                                value.getWindows()));
            }
            this.value = value;
        }

        @Override
        public InputT element() {
            return value.getValue();
        }

        @Override
        public Instant timestamp() {
            return value.getTimestamp();
        }

        @Override
        public BoundedWindow window() {
            return Iterables.getOnlyElement(value.getWindows());
        }
    }
}
