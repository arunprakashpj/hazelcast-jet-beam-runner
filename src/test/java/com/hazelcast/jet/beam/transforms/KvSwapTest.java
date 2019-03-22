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

package com.hazelcast.jet.beam.transforms;

import org.apache.beam.sdk.coders.BigEndianIntegerCoder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.NullableCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.KvSwap;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.junit.Test;

import java.util.Arrays;

/* "Inspired" by org.apache.beam.sdk.transforms.KvSwapTest */
@SuppressWarnings("ALL")
public class KvSwapTest extends AbstractTransformTest {

    private static final KV<String, Integer>[] TABLE =
            new KV[]{
                    KV.of("one", 1),
                    KV.of("two", 2),
                    KV.of("three", 3),
                    KV.of("four", 4),
                    KV.of("dup", 4),
                    KV.of("dup", 5),
                    KV.of("null", null),
            };

    @Test
    public void testKvSwap() {
        PCollection<KV<String, Integer>> input =
                pipeline.apply(
                        Create.of(Arrays.asList(TABLE))
                                .withCoder(
                                        KvCoder.of(
                                                StringUtf8Coder.of(), NullableCoder.of(BigEndianIntegerCoder.of()))));

        PCollection<KV<Integer, String>> output = input.apply(KvSwap.create());

        PAssert.that(output)
                .containsInAnyOrder(
                        KV.of(1, "one"),
                        KV.of(2, "two"),
                        KV.of(3, "three"),
                        KV.of(4, "four"),
                        KV.of(4, "dup"),
                        KV.of(5, "dup"),
                        KV.of(null, "null"));
        pipeline.run();
    }

}