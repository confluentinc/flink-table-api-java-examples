package io.confluent.flink.examples.table;

import io.confluent.flink.plugin.ConfluentSettings;
import io.confluent.flink.plugin.ConfluentTools;

import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.apache.flink.table.api.Expressions.lit;
import static org.apache.flink.table.api.Expressions.withAllColumns;

/**
 * An example that illustrates how to embed a table program into a CI/CD pipeline for continuous
 * testing and rollout.
 *
 * <p>Because we cannot rely on production data in this example, the program sets up some
 * Kafka-backed tables with data during the {@code setup} phase.
 *
 * <p>Afterward, the program can operate in two modes: one for integration testing ({@code test}
 * phase) and one for deployment ({@code deploy} phase).
 *
 * <p>A CI/CD workflow could execute the following:
 *
 * <pre>
 *     export EXAMPLE_JAR=./target/flink-table-api-java-examples-1.0.jar
 *     export EXAMPLE_CLASS=io.confluent.flink.examples.table.Example_08_IntegrationAndDeployment
 *     java -jar $EXAMPLE_JAR $EXAMPLE_CLASS setup
 *     java -jar $EXAMPLE_JAR $EXAMPLE_CLASS test
 *     java -jar $EXAMPLE_JAR $EXAMPLE_CLASS deploy
 * </pre>
 *
 * <p>NOTE: This example requires write access to a Kafka cluster. Fill out the given variables
 * below with target catalog/database if this is fine for you.
 *
 * <p>ALSO NOTE: The example submits an unbounded background statement. Make sure to stop the
 * statement in the Web UI afterward to clean up resources.
 *
 * <p>The complete CI/CD workflow performs the following steps:
 *
 * <ol>
 *   <li>Create Kafka table 'ProductsMock' and 'VendorsPerBrand'.
 *   <li>Fill Kafka table 'ProductsMock' with data from marketplace examples table 'products'.
 *   <li>Test the given SQL on a subset of data in 'ProductsMock' with the help of dynamic options.
 *   <li>Deploy an unbounded version of the tested SQL that write into 'VendorsPerBrand'.
 * </ol>
 */
public class Example_08_IntegrationAndDeployment {

    // Fill this with an environment you have write access to
    static final String TARGET_CATALOG = "";

    // Fill this with a Kafka cluster you have write access to
    static final String TARGET_DATABASE = "";

    // Fill this with names of the Kafka Topics you want to create
    static final String SOURCE_TABLE = "ProductsMock";
    static final String TARGET_TABLE = "VendorsPerBrand";

    // The following SQL will be tested on a finite subset of data before
    // it gets deployed to production.
    // In production, it will run on unbounded input.
    // The '%s' parameterizes the SQL for testing.
    static final String SQL =
            "SELECT brand, COUNT(*) AS vendors FROM ProductsMock %s GROUP BY brand";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException(
                    "No mode specified. Possible values are 'setup', 'test', or 'deploy'.");
        }

        EnvironmentSettings settings = ConfluentSettings.fromResource("/cloud.properties");
        TableEnvironment env = TableEnvironment.create(settings);
        env.useCatalog(TARGET_CATALOG);
        env.useDatabase(TARGET_DATABASE);

        String mode = args[0];
        switch (mode) {
            case "setup":
                setupProgram(env);
                break;
            case "test":
                testProgram(env);
                break;
            case "deploy":
                deployProgram(env);
                break;
            default:
                throw new IllegalArgumentException("Unknown mode: " + mode);
        }
    }

    // --------------------------------------------------------------------------------------------
    // Setup Phase
    // --------------------------------------------------------------------------------------------

    private static void setupProgram(TableEnvironment env) throws Exception {
        System.out.println("Running setup...");

        System.out.println("Creating table..." + SOURCE_TABLE);
        // Create a mock table that has exactly the same schema as the example `products` table.
        // The LIKE clause is very convenient for this task which is why we use SQL here.
        // Since we use little data, a bucket of 1 is important to satisfy the `scan.bounded.mode`
        // during testing.
        env.executeSql(
                String.format(
                        "CREATE TABLE IF NOT EXISTS `%s`\n"
                                + "DISTRIBUTED INTO 1 BUCKETS\n"
                                + "LIKE `examples`.`marketplace`.`products` (EXCLUDING OPTIONS)",
                        SOURCE_TABLE));

        System.out.println("Start filling table...");
        // Let Flink copy generated data into the mock table. Note that the statement is unbounded
        // and submitted as a background statement by default.
        TableResult pipelineResult =
                env.from("`examples`.`marketplace`.`products`")
                        .select(withAllColumns())
                        .insertInto(SOURCE_TABLE)
                        .execute();

        System.out.println("Waiting for at least 200 elements in table...");
        // We start a second Flink statement for monitoring how the copying progresses
        TableResult countResult = env.from(SOURCE_TABLE).select(lit(1).count()).as("c").execute();
        // This waits for the condition to be met:
        try (CloseableIterator<Row> iterator = countResult.collect()) {
            while (iterator.hasNext()) {
                Row row = iterator.next();
                long count = row.getFieldAs("c");
                if (count >= 200L) {
                    System.out.println("200 elements reached. Stopping...");
                    break;
                }
            }
        }

        // By using a closable iterator, the foreground statement will be stopped automatically when
        // the iterator is closed. But the background statement still needs a manual stop.
        ConfluentTools.stopStatement(pipelineResult);

        System.out.println("Creating table..." + TARGET_TABLE);
        // Create a table for storing the results after deployment.
        env.executeSql(
                String.format(
                        "CREATE TABLE IF NOT EXISTS `%s` \n"
                                + "(brand STRING, vendors BIGINT, PRIMARY KEY(brand) NOT ENFORCED)\n"
                                + "DISTRIBUTED INTO 1 BUCKETS",
                        TARGET_TABLE));
    }

    // --------------------------------------------------------------------------------------------
    // Test Phase
    // --------------------------------------------------------------------------------------------

    private static void testProgram(TableEnvironment env) {
        System.out.println("Running test...");

        // Dynamic options allow influencing parts of a table scan. In this case, they define a
        // range (from start offset '0' to end offset '100') how to read from Kafka. Effectively,
        // they make the table bounded. If all tables are finite, the statement can terminate.
        // This allows us to run checks on the result.
        String dynamicOptions =
                "/*+ OPTIONS(\n"
                        + "'scan.startup.mode' = 'specific-offsets',\n"
                        + "'scan.startup.specific-offsets' = 'partition: 0, offset: 0',\n"
                        + "'scan.bounded.mode' = 'specific-offsets',\n"
                        + "'scan.bounded.specific-offsets' = 'partition: 0, offset: 100'\n"
                        + ") */";

        System.out.println("Requesting test data...");
        TableResult result = env.executeSql(String.format(SQL, dynamicOptions));
        List<Row> rows = ConfluentTools.collectMaterialized(result);

        System.out.println(
                "Test data:\n"
                        + rows.stream().map(Row::toString).collect(Collectors.joining("\n")));

        // Use the testing framework of your choice and add checks to verify the
        // correctness of the test data
        boolean testSuccessful =
                rows.stream()
                        .map(r -> r.<String>getFieldAs("brand"))
                        .anyMatch(brand -> brand.equals("Apple"));
        if (testSuccessful) {
            System.out.println("Success. Ready for deployment.");
        } else {
            throw new IllegalStateException("Test was not successful");
        }
    }

    // --------------------------------------------------------------------------------------------
    // Deploy Phase
    // --------------------------------------------------------------------------------------------

    private static void deployProgram(TableEnvironment env) {
        System.out.println("Running deploy...");

        // It is possible to give a better statement name for deployment but make sure that the name
        // is unique across environment and region.
        String statementName = "vendors-per-brand-" + UUID.randomUUID();
        env.getConfig().set("client.statement-name", statementName);

        // Execute the SQL without dynamic options.
        // The result is unbounded and piped into the target table.
        TableResult insertIntoResult =
                env.sqlQuery(String.format(SQL, "")).insertInto(TARGET_TABLE).execute();

        // The API might add suffixes to manual statement names such as '-sql' or '-api'.
        // For the final submitted name, use the provided tools.
        String finalName = ConfluentTools.getStatementName(insertIntoResult);

        System.out.println("Statement has been deployed as: " + finalName);
    }
}
