/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.connector.pulsar.table;

import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.test.junit5.MiniClusterExtension;
import org.apache.flink.test.util.SuccessException;
import org.apache.flink.types.Row;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.apache.commons.lang3.RandomStringUtils.randomAlphanumeric;
import static org.apache.flink.connector.pulsar.table.testutils.PulsarTableTestUtils.collectRows;
import static org.apache.flink.util.CollectionUtil.entry;
import static org.apache.flink.util.CollectionUtil.map;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT cases for the Pulsar table source and sink. It aims to verify runtime behaviour and certain
 * use cases are correct and can produce/consume the desired records as user specifies.
 */
@ExtendWith(MiniClusterExtension.class)
public class PulsarTableITCase extends PulsarTableTestBase {

    private static final String JSON_FORMAT = "json";
    private static final String AVRO_FORMAT = "avro";
    private static final String CSV_FORMAT = "csv";

    @ParameterizedTest
    @ValueSource(strings = {JSON_FORMAT})
    public void pulsarSourceSink(String format) throws Exception {
        // we always use a different topic name for each parameterized topic,
        // in order to make sure the topic can be created.
        final String topic = "test_topic_" + format + randomAlphanumeric(3);
        createTestTopic(topic, 1);

        // ---------- Produce an event time stream into Pulsar -------------------

        final String createTable =
                String.format(
                        "create table pulsar_source_sink (\n"
                                + "  `computed-price` as price + 1.0,\n"
                                + "  price decimal(38, 18),\n"
                                + "  currency string,\n"
                                + "  log_date date,\n"
                                + "  log_time time(3),\n"
                                + "  log_ts timestamp(3),\n"
                                + "  ts as log_ts + INTERVAL '1' SECOND,\n"
                                + "  watermark for ts as ts\n"
                                + ") with (\n"
                                + "  'connector' = '%s',\n"
                                + "  'topics' = '%s',\n"
                                + "  'service-url' = '%s',\n"
                                + "  'admin-url' = '%s',\n"
                                + "  %s\n"
                                + ")",
                        PulsarTableFactory.IDENTIFIER,
                        topic,
                        pulsar.operator().serviceUrl(),
                        pulsar.operator().adminUrl(),
                        formatOptions(format));

        tableEnv.executeSql(createTable);

        String initialValues =
                "INSERT INTO pulsar_source_sink\n"
                        + "SELECT CAST(price AS DECIMAL(10, 2)), currency, "
                        + " CAST(d AS DATE), CAST(t AS TIME(0)), CAST(ts AS TIMESTAMP(3))\n"
                        + "FROM (VALUES (2.02,'Euro','2019-12-12', '00:00:01', '2019-12-12 00:00:01.001001'), \n"
                        + "  (1.11,'US Dollar','2019-12-12', '00:00:02', '2019-12-12 00:00:02.002001'), \n"
                        + "  (50,'Yen','2019-12-12', '00:00:03', '2019-12-12 00:00:03.004001'), \n"
                        + "  (3.1,'Euro','2019-12-12', '00:00:04', '2019-12-12 00:00:04.005001'), \n"
                        + "  (5.33,'US Dollar','2019-12-12', '00:00:05', '2019-12-12 00:00:05.006001'), \n"
                        + "  (0,'DUMMY','2019-12-12', '00:00:10', '2019-12-12 00:00:10'))\n"
                        + "  AS orders (price, currency, d, t, ts)";
        tableEnv.executeSql(initialValues).await();

        String query =
                "SELECT\n"
                        + "  CAST(TUMBLE_END(ts, INTERVAL '5' SECOND) AS VARCHAR),\n"
                        + "  CAST(MAX(log_date) AS VARCHAR),\n"
                        + "  CAST(MAX(log_time) AS VARCHAR),\n"
                        + "  CAST(MAX(ts) AS VARCHAR),\n"
                        + "  COUNT(*),\n"
                        + "  CAST(MAX(price) AS DECIMAL(10, 2))\n"
                        + "FROM pulsar_source_sink\n"
                        + "GROUP BY TUMBLE(ts, INTERVAL '5' SECOND)";

        DataStream<Row> result = tableEnv.toDataStream(tableEnv.sqlQuery(query));
        TestingSinkFunction sink = new TestingSinkFunction(2);
        result.addSink(sink).setParallelism(1);

        try {
            env.execute("Job_2");
        } catch (Throwable e) {
            if (!isCausedByJobFinished(e)) {
                // re-throw
                throw e;
            }
        }

        List<String> expected =
                Arrays.asList(
                        "+I[2019-12-12 00:00:05.000, 2019-12-12, 00:00:03, 2019-12-12 00:00:04.004, 3, 50.00]",
                        "+I[2019-12-12 00:00:10.000, 2019-12-12, 00:00:05, 2019-12-12 00:00:06.006, 2, 5.33]");

        assertThat(TestingSinkFunction.rows).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {JSON_FORMAT})
    public void pulsarSourceSinkWithKeyAndPartialValue(String format) throws Exception {
        // we always use a different topic name for each parameterized topic,
        // in order to make sure the topic can be created.
        final String topic = "key_partial_value_topic_" + format + randomAlphanumeric(3);
        createTestTopic(topic, 1);

        // ---------- Produce an event time stream into Pulsar -------------------

        // k_user_id and user_id have different data types to verify the correct mapping,
        // fields are reordered on purpose
        final String createTable =
                String.format(
                        "CREATE TABLE pulsar_key_value (\n"
                                + "  `user_id` BIGINT,\n"
                                + "  `name` STRING,\n"
                                + "  `event_id` BIGINT,\n"
                                + "  `payload` STRING\n"
                                + ") WITH (\n"
                                + "  'connector' = '%s',\n"
                                + "  'topics' = '%s',\n"
                                + "  'service-url' = '%s',\n"
                                + "  'admin-url' = '%s',\n"
                                + "  'format' = '%s',\n"
                                + "  'key.format' = '%s',\n"
                                + "  'key.fields' = 'user_id; event_id'\n"
                                + ")",
                        PulsarTableFactory.IDENTIFIER,
                        topic,
                        pulsar.operator().serviceUrl(),
                        pulsar.operator().adminUrl(),
                        format,
                        format);

        tableEnv.executeSql(createTable);

        String initialValues =
                "INSERT INTO pulsar_key_value\n"
                        + "VALUES\n"
                        + " (1, 'name 1', 100, 'payload 1'),\n"
                        + " (2, 'name 2', 101, 'payload 2'),\n"
                        + " (3, 'name 3', 102, 'payload 3')";
        tableEnv.executeSql(initialValues).await();

        final List<Row> result =
                collectRows(tableEnv.sqlQuery("SELECT * FROM pulsar_key_value"), 3);

        assertThat(result)
                .containsExactlyInAnyOrder(
                        Row.of(1L, "name 1", 100L, "payload 1"),
                        Row.of(2L, "name 2", 101L, "payload 2"),
                        Row.of(3L, "name 3", 102L, "payload 3"));
    }

    @ParameterizedTest
    @ValueSource(strings = {JSON_FORMAT})
    public void pulsarSourceSinkWithMetadata(String format) throws Exception {
        // we always use a different topic name for each parameterized topic,
        // in order to make sure the topic can be created.
        final String topic = "metadata_topic_" + format + randomAlphanumeric(3);
        createTestTopic(topic, 1);

        final String createTable =
                String.format(
                        "CREATE TABLE pulsar_metadata (\n"
                                + "  `physical_1` STRING,\n"
                                + "  `physical_2` INT,\n"
                                + "  `message_size` INT METADATA VIRTUAL,\n"
                                + "  `event_time` TIMESTAMP(3) METADATA,\n"
                                + "  `properties` MAP<STRING, STRING> METADATA,\n"
                                + "  `physical_3` BOOLEAN\n"
                                + ") WITH (\n"
                                + "  'connector' = '%s',\n"
                                + "  'topics' = '%s',\n"
                                + "  'service-url' = '%s',\n"
                                + "  'admin-url' = '%s',\n"
                                + "  'pulsar.producer.producerName' = 'pulsar-table-test',\n"
                                + "  'format' = '%s'\n"
                                + ")",
                        PulsarTableFactory.IDENTIFIER,
                        topic,
                        pulsar.operator().serviceUrl(),
                        pulsar.operator().adminUrl(),
                        format);
        tableEnv.executeSql(createTable);

        String initialValues =
                "INSERT INTO pulsar_metadata\n"
                        + "VALUES\n"
                        + " ('data 1', 1, TIMESTAMP '2022-03-24 13:12:11.123', MAP['k1', 'C0FFEE', 'k2', 'BABE01'], TRUE),\n"
                        + " ('data 2', 2, TIMESTAMP '2022-03-25 13:12:11.123', CAST(NULL AS MAP<STRING, BYTES>), FALSE),\n"
                        + " ('data 3', 3, TIMESTAMP '2022-03-26 13:12:11.123', MAP['k1', 'C0FFEE', 'k2', 'BABE01'], TRUE)";
        tableEnv.executeSql(initialValues).await();

        // ---------- Consume stream from Pulsar -------------------

        final List<Row> result = collectRows(tableEnv.sqlQuery("SELECT * FROM pulsar_metadata"), 3);
        assertThat(result)
                .containsExactlyInAnyOrder(
                        Row.of(
                                "data 1",
                                1,
                                56,
                                LocalDateTime.parse("2022-03-24T13:12:11.123"),
                                map(entry("k1", "C0FFEE"), entry("k2", "BABE01")),
                                true),
                        Row.of(
                                "data 2",
                                2,
                                57,
                                LocalDateTime.parse("2022-03-25T13:12:11.123"),
                                Collections.emptyMap(),
                                false),
                        Row.of(
                                "data 3",
                                3,
                                56,
                                LocalDateTime.parse("2022-03-26T13:12:11.123"),
                                map(entry("k1", "C0FFEE"), entry("k2", "BABE01")),
                                true));
    }

    private static final class TestingSinkFunction implements SinkFunction<Row> {

        private static final long serialVersionUID = 455430015321124493L;
        private static List<String> rows = new ArrayList<>();

        private final int expectedSize;

        private TestingSinkFunction(int expectedSize) {
            this.expectedSize = expectedSize;
            rows.clear();
        }

        @Override
        public void invoke(Row value, Context context) {
            rows.add(value.toString());
            if (rows.size() >= expectedSize) {
                // job finish
                throw new SuccessException();
            }
        }
    }

    private static boolean isCausedByJobFinished(Throwable e) {
        if (e instanceof SuccessException) {
            return true;
        } else if (e.getCause() != null) {
            return isCausedByJobFinished(e.getCause());
        } else {
            return false;
        }
    }

    private String formatOptions(String format) {
        return String.format("'format' = '%s'", format);
    }
}