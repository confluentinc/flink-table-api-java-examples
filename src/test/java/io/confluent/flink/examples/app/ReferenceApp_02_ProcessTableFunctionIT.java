package io.confluent.flink.examples.app;

import io.confluent.flink.plugin.ConfluentSettings;
import io.confluent.flink.plugin.ConfluentTableDescriptor;
import io.confluent.flink.plugin.ConfluentTools;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.types.Row;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.apache.flink.table.api.Expressions.row;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the pipeline logic of {@link
 * ReferenceApp_02_ProcessTableFunction#detectInactivity(Table)}, which applies the shared {@code
 * ClickInactivityMonitor} PTF, executed against Confluent Cloud.
 *
 * <p>Unlike the local {@code Example_10_ProcessTableFunctionTest} (which drives the identical PTF
 * logic directly via {@code ProcessTableFunctionTestHarness}), this test exercises the real {@code
 * $rowtime} system column, watermarks, and timers on the service, running the deployable pipeline
 * from {@link ReferenceApp_02_ProcessTableFunction#detectInactivity(Table)}.
 *
 * <p>{@code $rowtime} is only available on a real, managed (Kafka-backed) catalog table -- a plain
 * {@code fromValues()} table has no watermark, so it cannot satisfy the PTF's {@code
 * REQUIRE_ON_TIME} argument (Confluent Cloud does not support temporary catalog objects, so there
 * is no other way to attach a watermark to ad-hoc data). This test therefore provisions a small
 * managed mock table, fills it from {@code fromValues()}, and reads it back with a bounded offset
 * range so the statement terminates and its output can be asserted -- the same technique {@code
 * ReferenceApp_01_IntegrationAndDeploymentIT} uses for its Kafka-backed case, just with a handful
 * of controlled rows instead of production data.
 *
 * <p>Because the source is bounded, Flink advances the watermark to its maximum once all rows are
 * read, which fires every pending inactivity timer immediately -- there is no need to wait out the
 * real 30-second timeout.
 *
 * <p>Like {@code ReferenceApp_01_IntegrationAndDeploymentIT}, this runs during {@code ./mvnw
 * verify} and requires the standard connection configuration (see the README's "Via Environment
 * Variables" section) plus a current catalog and database ({@code sql.current-catalog} / {@code
 * sql.current-database}) pointing to an environment and Kafka cluster with write access, since it
 * provisions a managed mock table. Builds without Confluent Cloud credentials skip it with {@code
 * ./mvnw verify -DskipITs}.
 */
class ReferenceApp_02_ProcessTableFunctionIT {

    private static final Logger LOG =
            LoggerFactory.getLogger(ReferenceApp_02_ProcessTableFunctionIT.class);

    // Name of the mock Kafka topic that stands in for `examples.marketplace.clicks`
    static final String SOURCE_TABLE = "ClicksMock";

    static TableEnvironment env;

    @BeforeAll
    // The timeout runs the setup in a separate thread so that it can be interrupted even while
    // blocked on statement results, e.g. when the compute pool has no capacity for the fill
    // statement. Without it, a stuck setup would hang until the CI job timeout.
    @Timeout(value = 15, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    static void setUpMockTable() throws Exception {
        env = TableEnvironment.create(ConfluentSettings.fromGlobalVariables());

        LOG.info("Creating table... {}", SOURCE_TABLE);
        // DISTRIBUTED INTO 1 BUCKETS keeps every row in a single Kafka partition, which the bounded
        // offset range in the test relies on. read-uncommitted avoids waiting out the ~1 min
        // checkpoint interval for the fill statement's exactly-once records to become visible.
        env.createTable(
                SOURCE_TABLE,
                ConfluentTableDescriptor.forManaged()
                        .schema(
                                Schema.newBuilder()
                                        .column("user_id", DataTypes.STRING().notNull())
                                        .build())
                        .option("kafka.consumer.isolation-level", "read-uncommitted")
                        .distributedInto(1)
                        .build());

        LOG.info("Filling table with known clicks...");
        // Two clicks for alice, one for bob. fromValues() is bounded, so await() waits for the
        // write to finish instead of returning once it is merely submitted.
        env.fromValues(row("alice"), row("alice"), row("bob"))
                .insertInto(SOURCE_TABLE)
                .execute()
                .await();
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void emitsOneAlertPerUserOnBoundedClicks() {
        TableResult result = null;
        try {
            // Dynamic options make the table bounded (read exactly the 3 rows setUpMockTable
            // wrote),
            // so the statement terminates and the result can be asserted, just like
            // ReferenceApp_01_IntegrationAndDeploymentIT's bounded-data test. $rowtime is a virtual
            // metadata column, so SELECT * would silently drop it -- it must be selected by name
            // for the PTF's on_time argument to find it.
            Table boundedClicks =
                    env.sqlQuery(
                            String.format(
                                    "SELECT `user_id`, `$rowtime` FROM `%s` /*+ OPTIONS(\n"
                                            + "'scan.startup.mode' = 'specific-offsets',\n"
                                            + "'scan.startup.specific-offsets' = 'partition: 0, offset: 0',\n"
                                            + "'scan.bounded.mode' = 'specific-offsets',\n"
                                            + "'scan.bounded.specific-offsets' = 'partition: 0, offset: 3'\n"
                                            + ") */",
                                    SOURCE_TABLE));

            // The exact same pipeline logic that main() deploys.
            Table alerts = ReferenceApp_02_ProcessTableFunction.detectInactivity(boundedClicks);

            result = alerts.execute();
            List<Row> rows = ConfluentTools.collectMaterialized(result);

            // The bounded source's end-of-stream watermark fires each partition's timer exactly
            // once, so every user gets a single alert with their total click count. The rowtime the
            // PTF appends is not asserted here since its exact value is not controlled by the test.
            Map<String, Integer> clickCountsByUser = new HashMap<>();
            for (Row alert : rows) {
                clickCountsByUser.put(alert.getFieldAs("user_id"), alert.getFieldAs("clickCount"));
            }
            assertThat(clickCountsByUser)
                    .containsExactlyInAnyOrderEntriesOf(Map.of("alice", 2, "bob", 1));
        } finally {
            // The PTF is submitted as an inline function backed by an uploaded artifact (the class
            // file). deleteStatement() discovers and deletes both, in addition to the statement
            // itself; without it, the artifact and function registration would leak in the
            // environment on every run.
            if (result != null) {
                ConfluentTools.deleteStatement(env, ConfluentTools.getStatementName(result));
            }
        }
    }

    @AfterAll
    // Drop the mock table so the run does not leak a topic into the environment. Runs even when a
    // test fails, so a failed run cleans up after itself too.
    static void dropMockTable() {
        if (env != null) {
            env.dropTable(SOURCE_TABLE, true);
        }
    }
}
