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

package com.hazelcast.jet.beam.transforms.pardo;

import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.schemas.FieldAccessDescriptor;
import org.apache.beam.sdk.schemas.JavaFieldSchema;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.annotations.DefaultSchema;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Filter;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.ParDoSchemaTest;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.apache.beam.vendor.guava.v20_0.com.google.common.collect.Lists;
import org.junit.Ignore;
import org.junit.Test;

import java.io.Serializable;
import java.util.List;

import static junit.framework.TestCase.assertEquals;
import static org.junit.Assert.assertTrue;

/* "Inspired" by org.apache.beam.sdk.transforms.ParDoSchemaTest */
@SuppressWarnings("ALL")
public class SchemaParDoTest extends AbstractParDoTest {

    @Test
    public void testSimpleSchemaPipeline() {
        List<MyPojo> pojoList =
                Lists.newArrayList(new MyPojo("a", 1), new MyPojo("b", 2), new MyPojo("c", 3));

        Schema schema =
                Schema.builder().addStringField("string_field").addInt32Field("integer_field").build();

        PCollection<String> output =
                pipeline
                        .apply(
                                Create.of(pojoList)
                                        .withSchema(
                                                schema,
                                                o ->
                                                        Row.withSchema(schema).addValues(o.stringField, o.integerField).build(),
                                                r -> new MyPojo(r.getString("string_field"), r.getInt32("integer_field"))))
                        .apply(
                                ParDo.of(
                                        new DoFn<MyPojo, String>() {
                                            @ProcessElement
                                            public void process(@Element Row row, OutputReceiver<String> r) {
                                                r.output(row.getString(0) + ":" + row.getInt32(1));
                                            }
                                        }));
        PAssert.that(output).containsInAnyOrder("a:1", "b:2", "c:3");
        PipelineResult.State state = pipeline.run().waitUntilFinish();
        assertEquals(PipelineResult.State.DONE, state);
    }

    @Test
    public void testReadAndWrite() {
        List<MyPojo> pojoList =
                Lists.newArrayList(new MyPojo("a", 1), new MyPojo("b", 2), new MyPojo("c", 3));

        Schema schema1 =
                Schema.builder().addStringField("string_field").addInt32Field("integer_field").build();

        Schema schema2 =
                Schema.builder().addStringField("string2_field").addInt32Field("integer2_field").build();

        PCollection<String> output =
                pipeline
                        .apply(
                                Create.of(pojoList)
                                        .withSchema(
                                                schema1,
                                                o ->
                                                        Row.withSchema(schema1)
                                                                .addValues(o.stringField, o.integerField)
                                                                .build(),
                                                r -> new MyPojo(r.getString("string_field"), r.getInt32("integer_field"))))
                        .apply(
                                "first",
                                ParDo.of(
                                        new DoFn<MyPojo, MyPojo>() {
                                            @ProcessElement
                                            public void process(@Element Row row, OutputReceiver<Row> r) {
                                                r.output(
                                                        Row.withSchema(schema2)
                                                                .addValues(row.getString(0), row.getInt32(1))
                                                                .build());
                                            }
                                        }))
                        .setSchema(
                                schema2,
                                o -> Row.withSchema(schema2).addValues(o.stringField, o.integerField).build(),
                                r -> new MyPojo(r.getString("string2_field"), r.getInt32("integer2_field")))
                        .apply(
                                "second",
                                ParDo.of(
                                        new DoFn<MyPojo, String>() {
                                            @ProcessElement
                                            public void process(@Element Row row, OutputReceiver<String> r) {
                                                r.output(row.getString(0) + ":" + row.getInt32(1));
                                            }
                                        }));
        PAssert.that(output).containsInAnyOrder("a:1", "b:2", "c:3");
        PipelineResult.State state = pipeline.run().waitUntilFinish();
        assertEquals(PipelineResult.State.DONE, state);
    }

    @Test
    public void testReadAndWriteMultiOutput() {
        List<MyPojo> pojoList =
                Lists.newArrayList(new MyPojo("a", 1), new MyPojo("b", 2), new MyPojo("c", 3));

        Schema schema1 =
                Schema.builder().addStringField("string_field").addInt32Field("integer_field").build();

        Schema schema2 =
                Schema.builder().addStringField("string2_field").addInt32Field("integer2_field").build();

        Schema schema3 =
                Schema.builder().addStringField("string3_field").addInt32Field("integer3_field").build();

        TupleTag<MyPojo> firstOutput = new TupleTag<>("first");
        TupleTag<MyPojo> secondOutput = new TupleTag<>("second");

        PCollectionTuple tuple =
                pipeline
                        .apply(
                                Create.of(pojoList)
                                        .withSchema(
                                                schema1,
                                                o ->
                                                        Row.withSchema(schema1)
                                                                .addValues(o.stringField, o.integerField)
                                                                .build(),
                                                r -> new MyPojo(r.getString("string_field"), r.getInt32("integer_field"))))
                        .apply(
                                "first",
                                ParDo.of(
                                        new DoFn<MyPojo, MyPojo>() {
                                            @ProcessElement
                                            public void process(@Element Row row, MultiOutputReceiver r) {
                                                r.getRowReceiver(firstOutput)
                                                        .output(
                                                                Row.withSchema(schema2)
                                                                        .addValues(row.getString(0), row.getInt32(1))
                                                                        .build());
                                                r.getRowReceiver(secondOutput)
                                                        .output(
                                                                Row.withSchema(schema3)
                                                                        .addValues(row.getString(0), row.getInt32(1))
                                                                        .build());
                                            }
                                        })
                                        .withOutputTags(firstOutput, TupleTagList.of(secondOutput)));
        tuple
                .get(firstOutput)
                .setSchema(
                        schema2,
                        o -> Row.withSchema(schema2).addValues(o.stringField, o.integerField).build(),
                        r -> new MyPojo(r.getString("string2_field"), r.getInt32("integer2_field")));
        tuple
                .get(secondOutput)
                .setSchema(
                        schema3,
                        o -> Row.withSchema(schema3).addValues(o.stringField, o.integerField).build(),
                        r -> new MyPojo(r.getString("string3_field"), r.getInt32("integer3_field")));

        PCollection<String> output1 =
                tuple
                        .get(firstOutput)
                        .apply(
                                "second",
                                ParDo.of(
                                        new DoFn<MyPojo, String>() {
                                            @ProcessElement
                                            public void process(@Element Row row, OutputReceiver<String> r) {
                                                r.output(
                                                        row.getString("string2_field") + ":" + row.getInt32("integer2_field"));
                                            }
                                        }));

        PCollection<String> output2 =
                tuple
                        .get(secondOutput)
                        .apply(
                                "third",
                                ParDo.of(
                                        new DoFn<MyPojo, String>() {
                                            @ProcessElement
                                            public void process(@Element Row row, OutputReceiver<String> r) {
                                                r.output(
                                                        row.getString("string3_field") + ":" + row.getInt32("integer3_field"));
                                            }
                                        }));

        PAssert.that(output1).containsInAnyOrder("a:1", "b:2", "c:3");
        PAssert.that(output2).containsInAnyOrder("a:1", "b:2", "c:3");
        PipelineResult.State state = pipeline.run().waitUntilFinish();
        assertEquals(PipelineResult.State.DONE, state);
    }

    @Test
    public void testReadAndWriteWithSchemaRegistry() {
        Schema schema =
                Schema.builder().addStringField("string_field").addInt32Field("integer_field").build();

        pipeline
                .getSchemaRegistry()
                .registerSchemaForClass(
                        MyPojo.class,
                        schema,
                        o -> Row.withSchema(schema).addValues(o.stringField, o.integerField).build(),
                        r -> new MyPojo(r.getString("string_field"), r.getInt32("integer_field")));

        List<MyPojo> pojoList =
                Lists.newArrayList(new MyPojo("a", 1), new MyPojo("b", 2), new MyPojo("c", 3));

        PCollection<String> output =
                pipeline
                        .apply(Create.of(pojoList))
                        .apply(
                                "first",
                                ParDo.of(
                                        new DoFn<MyPojo, MyPojo>() {
                                            @ProcessElement
                                            public void process(@Element Row row, OutputReceiver<Row> r) {
                                                r.output(
                                                        Row.withSchema(schema)
                                                                .addValues(row.getString(0), row.getInt32(1))
                                                                .build());
                                            }
                                        }))
                        .apply(
                                "second",
                                ParDo.of(
                                        new DoFn<MyPojo, String>() {
                                            @ProcessElement
                                            public void process(@Element Row row, OutputReceiver<String> r) {
                                                r.output(row.getString(0) + ":" + row.getInt32(1));
                                            }
                                        }));
        PAssert.that(output).containsInAnyOrder("a:1", "b:2", "c:3");
        PipelineResult.State state = pipeline.run().waitUntilFinish();
        assertEquals(PipelineResult.State.DONE, state);
    }

    @Test
    public void testFieldAccessSchemaPipeline() {
        List<MyPojo> pojoList =
                Lists.newArrayList(new MyPojo("a", 1), new MyPojo("b", 2), new MyPojo("c", 3));

        Schema schema =
                Schema.builder().addStringField("string_field").addInt32Field("integer_field").build();

        PCollection<String> output =
                pipeline
                        .apply(
                                Create.of(pojoList)
                                        .withSchema(
                                                schema,
                                                o ->
                                                        Row.withSchema(schema).addValues(o.stringField, o.integerField).build(),
                                                r -> new MyPojo(r.getString("string_field"), r.getInt32("integer_field"))))
                        .apply(
                                ParDo.of(
                                        new DoFn<MyPojo, String>() {
                                            @FieldAccess("foo")
                                            final FieldAccessDescriptor fieldAccess =
                                                    FieldAccessDescriptor.withAllFields();

                                            @ProcessElement
                                            public void process(@FieldAccess("foo") @Element Row row, OutputReceiver<String> r) {
                                                r.output(row.getString(0) + ":" + row.getInt32(1));
                                            }
                                        }));
        PAssert.that(output).containsInAnyOrder("a:1", "b:2", "c:3");
        PipelineResult.State state = pipeline.run().waitUntilFinish();
        assertEquals(PipelineResult.State.DONE, state);
    }

    @Test
    public void testInferredSchemaPipeline() {
        List<InferredPojo> pojoList =
                Lists.newArrayList(
                        new InferredPojo("a", 1), new InferredPojo("b", 2), new InferredPojo("c", 3));

        PCollection<String> output =
                pipeline
                        .apply(Create.of(pojoList))
                        .apply(
                                ParDo.of(
                                        new DoFn<InferredPojo, String>() {
                                            @ProcessElement
                                            public void process(@Element Row row, OutputReceiver<String> r) {
                                                r.output(row.getString(0) + ":" + row.getInt32(1));
                                            }
                                        }));
        PAssert.that(output).containsInAnyOrder("a:1", "b:2", "c:3");
        PipelineResult.State state = pipeline.run().waitUntilFinish();
        assertEquals(PipelineResult.State.DONE, state);
    }

    @Test
    public void testSchemasPassedThrough() {
        List<InferredPojo> pojoList =
                Lists.newArrayList(
                        new InferredPojo("a", 1), new InferredPojo("b", 2), new InferredPojo("c", 3));

        PCollection<InferredPojo> out = pipeline.apply(Create.of(pojoList)).apply(Filter.by(e -> true));
        assertTrue(out.hasSchema());

        PipelineResult.State state = pipeline.run().waitUntilFinish();
        assertEquals(PipelineResult.State.DONE, state);
    }

    static class MyPojo implements Serializable {
        MyPojo(String stringField, Integer integerField) {
            this.stringField = stringField;
            this.integerField = integerField;
        }

        String stringField;
        Integer integerField;
    }

    @DefaultSchema(JavaFieldSchema.class)
    public static class InferredPojo {
        public String stringField;
        public Integer integerField;

        public InferredPojo(String stringField, Integer integerField) {
            this.stringField = stringField;
            this.integerField = integerField;
        }

        public InferredPojo() {}
    }

}
