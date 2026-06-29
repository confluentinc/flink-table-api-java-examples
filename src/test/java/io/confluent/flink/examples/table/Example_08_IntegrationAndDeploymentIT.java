package io.confluent.flink.examples.table;

import io.confluent.flink.plugin.ConfluentPluginOptions;
import io.confluent.flink.plugin.ConfluentSettings;
import io.confluent.flink.plugin.ConfluentTools;

import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.apache.flink.table.api.Expressions.lit;
import static org.apache.flink.table.api.Expressions.withAllColumns;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration tests for the pipeline logic of {@link Example_08_IntegrationAndDeployment}, executed
 * against Confluent Cloud.
 *
 * <p>While the unit tests (see {@code Example_08_IntegrationAndDeploymentTest}) verify the logic
 * locally, these tests verify it on the real service: the exact Confluent SQL semantics, the
 * Confluent catalog, and Kafka-backed tables.
 *
 * <p>The tests run during {@code ./mvnw verify} and fail fast when the required environment
 * variables are not set, so a CI pipeline cannot silently skip its verification step and still
 * report success. Builds without Confluent Cloud credentials skip them with {@code ./mvnw verify
 * -DskipITs}. They require the standard connection variables (see the README's "Via Environment
 * Variables" section) plus TARGET_CATALOG and TARGET_DATABASE pointing to an environment and Kafka
 * cluster with write access.
 *
 * <p>Because we cannot rely on production data in this example, the test fixture creates a mock
 * Kafka-backed table and fills it with data from the marketplace examples table. Dynamic options
 * then make the table bounded, so the pipeline terminates and its result can be asserted.
 *
 * <p>NOTE: Running from the IDE needs the opposite classpath exclusion from the unit tests (the
 * {@code flink-table-planner-loader} JAR) plus the environment variables in the run configuration;
 * see the README's testing section.
 */
class Example_08_IntegrationAndDeploymentIT {

    // Name of the mock Kafka topic that emulates the production input
    static final String SOURCE_TABLE = "ProductsMock";

    static TableEnvironment env;

    @BeforeAll
    // The timeout runs the setup in a separate thread so that it can be interrupted even while
    // blocked on statement results, e.g. when the compute pool has no capacity for the fill
    // statement. Without it, a stuck setup would hang until the CI job timeout.
    @Timeout(value = 15, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    static void setUpMockTable() throws Exception {
        requireEnvironment();
        env = TableEnvironment.create(ConfluentSettings.fromGlobalVariables());
        env.useCatalog(System.getenv("TARGET_CATALOG"));
        env.useDatabase(System.getenv("TARGET_DATABASE"));

        System.out.println("Creating table... " + SOURCE_TABLE);
        // Create a mock table that has exactly the same schema as the example `products` table.
        // The LIKE clause is very convenient for this task which is why we use SQL here.
        // Since we use little data, a bucket of 1 is important to satisfy the
        // `scan.bounded.mode` during testing.
        env.executeSql(
                String.format(
                        "CREATE TABLE IF NOT EXISTS `%s`\n"
                                + "DISTRIBUTED INTO 1 BUCKETS\n"
                                + "LIKE `examples`.`marketplace`.`products` (EXCLUDING OPTIONS)",
                        SOURCE_TABLE));

        System.out.println("Start filling table...");
        // Let Flink copy generated data into the mock table. Note that the statement is
        // unbounded and submitted as a background statement by default.
        TableResult pipelineResult =
                env.from("`examples`.`marketplace`.`products`")
                        .select(withAllColumns())
                        .insertInto(SOURCE_TABLE)
                        .execute();

        long count = 0;
        try {
            System.out.println("Waiting for at least 200 elements in table...");
            // A second Flink statement monitors how the copying progresses. The foreground
            // statement is stopped automatically when its iterator is closed.
            TableResult countResult =
                    env.from(SOURCE_TABLE).select(lit(1).count()).as("c").execute();
            try (CloseableIterator<Row> iterator = countResult.collect()) {
                while (count < 200L && iterator.hasNext()) {
                    count = iterator.next().getFieldAs("c");
                }
            }
        } finally {
            // The fill statement is unbounded and must always be cleaned up, even when the wait
            // above fails, as it would otherwise keep running and consuming CFUs. It is also
            // deleted rather than just stopped: it gets a random name on every run, and stopped
            // statements would accumulate in the environment.
            String fillStatement = ConfluentTools.getStatementName(pipelineResult);
            ConfluentTools.stopStatement(env, fillStatement);
            ConfluentTools.deleteStatement(env, fillStatement);
        }
        if (count < 200L) {
            fail(
                    "The mock table only reached "
                            + count
                            + " elements before the monitoring statement terminated.");
        }
        System.out.println("200 elements reached.");
    }

    // Fails fast with a clear message instead of skipping, so that a CI pipeline with missing
    // secrets cannot report a successful verification that never ran. The connection variable
    // names come from ConfluentPluginOptions, so the list cannot drift from the plugin contract.
    private static void requireEnvironment() {
        List<String> required = new ArrayList<>();
        // A properties file referenced via FLINK_PROPERTIES is a valid alternative to the
        // discrete connection variables (see the README's "Configuration" section).
        if (isBlank(System.getenv(ConfluentPluginOptions.VAR_FLINK_PROPERTIES))) {
            required.add(ConfluentPluginOptions.VAR_CLOUD_PROVIDER);
            required.add(ConfluentPluginOptions.VAR_CLOUD_REGION);
            required.add(ConfluentPluginOptions.VAR_FLINK_API_KEY);
            required.add(ConfluentPluginOptions.VAR_FLINK_API_SECRET);
            required.add(ConfluentPluginOptions.VAR_ORG_ID);
            required.add(ConfluentPluginOptions.VAR_ENV_ID);
            required.add(ConfluentPluginOptions.VAR_COMPUTE_POOL_ID);
        }
        required.add("TARGET_CATALOG");
        required.add("TARGET_DATABASE");
        List<String> missing =
                required.stream()
                        .filter(name -> isBlank(System.getenv(name)))
                        .collect(Collectors.toList());
        if (!missing.isEmpty()) {
            fail(
                    "Integration tests verify the pipeline against Confluent Cloud and require the"
                            + " environment variables "
                            + missing
                            + ". Set them (see the README section 'Via Environment Variables') or"
                            + " skip the integration tests explicitly with -DskipITs.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Test
    void countsVendorsPerBrandOnBoundedData() {
        // Dynamic options allow influencing parts of a table scan. In this case, they define a
        // range (from start offset '0' to end offset '100') how to read from Kafka. Effectively,
        // they make the table bounded. If all tables are finite, the statement can terminate.
        // This allows us to run checks on the result.
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
                Example_08_IntegrationAndDeployment.VendorsPerBrand.buildPipeline(boundedProducts);

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
    }
}
