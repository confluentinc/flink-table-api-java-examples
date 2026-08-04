package io.confluent.flink.examples.app;

import io.confluent.flink.plugin.ConfluentSettings;
import io.confluent.flink.plugin.ConfluentTools;
import io.confluent.flink.plugin.StatementHandle;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.types.Row;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.apache.flink.table.api.Expressions.row;
import static org.apache.flink.table.api.Expressions.withAllColumns;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the pipeline logic of {@link ReferenceApp_01_IntegrationAndDeployment},
 * executed against Confluent Cloud.
 *
 * <p>While the unit tests (see {@code ReferenceApp_01_IntegrationAndDeploymentTest}) verify the
 * logic locally, these tests verify it on the real service: the exact Confluent SQL semantics, the
 * Confluent catalog, and Kafka-backed tables.
 *
 * <p>The tests run during {@code ./mvnw verify} and fail fast when the required environment
 * variables are not set, so a CI pipeline cannot silently skip its verification step and still
 * report success. Builds without Confluent Cloud credentials skip them with {@code ./mvnw verify
 * -DskipITs}. They require the standard connection variables (see the README's "Via Environment
 * Variables" section); {@link #countsVendorsPerBrandOnBoundedData()} additionally needs a current
 * catalog and database ({@code sql.current-catalog} / {@code sql.current-database}) pointing to an
 * environment and Kafka cluster with write access, while {@link
 * #countsVendorsPerBrandOnSampleRows()} runs on inline {@code fromValues} data and needs neither.
 *
 * <p>Because we cannot rely on production data in this example, {@code
 * countsVendorsPerBrandOnBoundedData} creates a sample Kafka-backed table and fills it with data
 * from the marketplace examples table. Dynamic options then make the table bounded, so the pipeline
 * terminates and its result can be asserted.
 *
 * <p>NOTE: Running from the IDE needs the opposite classpath exclusion from the unit tests (the
 * {@code flink-table-planner-loader} JAR) plus the environment variables in the run configuration;
 * see the README's testing section.
 */
class ReferenceApp_01_IntegrationAndDeploymentIT {

    // Name of the sample Kafka topic that emulates the production input
    static final String SOURCE_TABLE = "ProductsSample";

    static TableEnvironment env;

    @BeforeAll
    static void setUpEnv() {
        // Only the shared connection is set up here. It needs no current catalog, so the
        // fromValues-based test can run without a configured catalog/database. The Kafka-backed
        // fixture (which does need them) is provisioned by the test that uses it.
        env = TableEnvironment.create(ConfluentSettings.fromGlobalVariables());
    }

    @Test
    // Provisioning and filling the sample table can block on statement results (e.g. when the
    // compute pool has no capacity), so run in a separate thread that the timeout can interrupt
    // instead of hanging until the CI job timeout.
    @Timeout(value = 15, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void countsVendorsPerBrandOnBoundedData() throws Exception {
        // This test needs a real Kafka-backed source in the configured current catalog/database and
        // provisions a sample table filled from the marketplace generator. The fromValues test
        // needs none of this, which is why the fixture lives here rather than in @BeforeAll.
        fillSampleTable();
        try {
            // Dynamic options allow influencing parts of a table scan. In this case, they define a
            // range (from start offset '0' to end offset '100') how to read from Kafka.
            // Effectively, they make the table bounded. If all tables are finite, the statement can
            // terminate. This allows us to run checks on the result.
            Table boundedProducts =
                    env.sqlQuery(
                            String.format(
                                    "SELECT * FROM `%s` /*+ OPTIONS(\n"
                                            + "'scan.startup.mode' = 'specific-offsets',\n"
                                            + "'scan.startup.specific-offsets' = 'partition: 0, offset: 0',\n"
                                            + "'scan.bounded.mode' = 'specific-offsets',\n"
                                            + "'scan.bounded.specific-offsets' = 'partition: 0, offset: 100'\n"
                                            + ") */",
                                    SOURCE_TABLE));

            // The exact same pipeline logic that the unit tests run locally
            Table result =
                    ReferenceApp_01_IntegrationAndDeployment.VendorsPerBrand.buildPipeline(
                            boundedProducts);

            List<Row> rows = ConfluentTools.collectMaterialized(result.execute());

            assertThat(rows).isNotEmpty();
            assertThat(rows)
                    .allSatisfy(
                            row -> {
                                assertThat(row.<String>getFieldAs("brand")).isNotBlank();
                                assertThat(row.<Long>getFieldAs("vendors")).isPositive();
                            });
            // The examples data generator produces a fixed set of brands. Checking for a known one
            // guards against reading the wrong data, not just producing plausible-looking rows.
            assertThat(rows).extracting(row -> row.<String>getFieldAs("brand")).contains("Apple");
        } finally {
            // Drop the sample table so the run does not leak a topic into the environment, even
            // when an assertion above fails.
            env.dropTable(SOURCE_TABLE, true);
        }
    }

    @Test
    // Runs in a separate thread so a stuck statement (e.g. no compute pool capacity) can be
    // interrupted instead of hanging until the CI job timeout.
    @Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void countsVendorsPerBrandOnSampleRows() {
        // The Confluent Cloud counterpart of the unit test's fromValues() case: a small, fully
        // controlled input so the result can be asserted exactly, but exercising real Confluent SQL
        // semantics on the service. Unlike countsVendorsPerBrandOnBoundedData (which reads
        // unpredictable marketplace data and can only check general properties), the known rows
        // here let us assert the exact per-brand counts. fromValues is bounded, so the statement
        // terminates on its own -- no Kafka table to provision or clean up.
        Table sampleProducts =
                env.fromValues(
                        DataTypes.ROW(
                                DataTypes.FIELD("name", DataTypes.STRING()),
                                DataTypes.FIELD("brand", DataTypes.STRING())),
                        row("MacBook", "Apple"),
                        row("iPhone", "Apple"),
                        row("Galaxy", "Samsung"));

        // The exact same pipeline logic that the unit tests run locally.
        Table result =
                ReferenceApp_01_IntegrationAndDeployment.VendorsPerBrand.buildPipeline(
                        sampleProducts);

        List<Row> rows = ConfluentTools.collectMaterialized(result.execute());

        assertThat(rows).containsExactlyInAnyOrder(Row.of("Apple", 2L), Row.of("Samsung", 1L));
    }

    // Creates the sample Kafka-backed table and fills it with marketplace data. Requires a current
    // catalog/database to be set on env. Fails if the table does not reach enough rows.
    private static void fillSampleTable() throws Exception {
        System.out.println("Creating table... " + SOURCE_TABLE);
        // Create a sample table that has exactly the same schema as the example `products` table.
        // The LIKE clause is very convenient for this task which is why we use SQL here.
        // A single bucket is required so the little data we write all lands on one partition,
        // satisfying `scan.bounded.mode` when the table is later read as bounded. read-uncommitted
        // makes the freshly filled rows visible immediately, rather than waiting for the
        // exactly-once checkpoint commit (~1 min on Confluent Cloud).
        env.executeSql(
                String.format(
                        "CREATE TABLE IF NOT EXISTS `%s`\n"
                                + "DISTRIBUTED INTO 1 BUCKETS\n"
                                + "WITH ('kafka.consumer.isolation-level' = 'read-uncommitted')\n"
                                + "LIKE `examples`.`marketplace`.`products` (EXCLUDING OPTIONS)",
                        SOURCE_TABLE));

        System.out.println("Start filling table...");
        // Let Flink copy generated data into the sample table. Note that the statement is
        // unbounded and submitted as a background statement by default.
        TableResult pipelineResult =
                env.from("`examples`.`marketplace`.`products`")
                        .select(withAllColumns())
                        .insertInto(SOURCE_TABLE)
                        .execute();

        try {
            System.out.println("Waiting for at least 200 elements in table...");
            // Read 200 rows back to confirm the fill has produced enough data to read as bounded.
            // collectChangelog blocks until that many rows have arrived; the test's @Timeout guards
            // against the fill never getting there (e.g. no compute pool capacity).
            ConfluentTools.collectChangelog(env.from(SOURCE_TABLE), 200);
        } finally {
            // The fill statement is unbounded and must always be cleaned up, even when the wait
            // above fails, as it would otherwise keep running and consuming CFUs. It is also
            // deleted rather than just stopped: it gets a random name on every run, and stopped
            // statements would accumulate in the environment.
            StatementHandle fillStatement = ConfluentTools.getStatementHandle(pipelineResult);
            fillStatement.stop();
            fillStatement.delete();
        }
        System.out.println("200 elements reached.");
    }
}
