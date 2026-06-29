package io.confluent.flink.examples.table;

import io.confluent.flink.plugin.ConfluentSettings;
import io.confluent.flink.plugin.ConfluentTools;

import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;

import static org.apache.flink.table.api.Expressions.$;
import static org.apache.flink.table.api.Expressions.lit;

/**
 * An example that illustrates how to structure, test, and deploy a table program for production use
 * in a CI/CD pipeline.
 *
 * <p>The example separates two concerns that often end up entangled:
 *
 * <ul>
 *   <li>The pipeline logic in {@link VendorsPerBrand} is plain Table API code without any
 *       Confluent-specific dependencies. It receives its input table as a parameter instead of
 *       resolving it from a catalog. This makes the logic executable on Apache Flink in local unit
 *       tests, where the input is mocked with {@code fromValues()} (see {@code
 *       Example_08_IntegrationAndDeploymentTest}), as well as on Confluent Cloud for Apache Flink,
 *       where the input is a Kafka-backed table (see {@code
 *       Example_08_IntegrationAndDeploymentIT}).
 *   <li>The {@link #main(String[])} method is the deployment entrypoint. It wires the pipeline to
 *       Confluent Cloud and submits it as a long-running background statement.
 * </ul>
 *
 * <p>The program is configured with {@link ConfluentSettings#fromArgs(String[])}, which reads
 * configuration from command-line arguments, with environment variables as a fallback. This also
 * enables the plugin's built-in CI/CD lifecycle actions: when the JAR is run with an action as the
 * first argument ({@code list}, {@code describe}, {@code resume}, {@code stop}, or {@code delete}),
 * the plugin executes the action and exits before the deployment logic runs, so the same JAR both
 * deploys and manages (see {@code .github/workflows-examples/manage.yml}).
 *
 * <p>The statement name and application name are deployment configuration, not source constants:
 * the pipeline provides them via {@code --statement-name} / {@code --application-name}, which keeps
 * a single source of truth for both deploy and management and is required for the lifecycle actions
 * (they read the name at startup, before {@code main()} runs). A program that submits several
 * statements instead names each one in code via {@link
 * ConfluentTools#setStatementName(TableEnvironment, String)}.
 *
 * <p>Re-running the deployment with unchanged code is idempotent. When the pipeline changed, pass
 * {@code --on-conflict replace} to replace the existing statement; see the README's CI/CD section
 * for what that means for stateful pipelines. The README also covers configuration, environment
 * promotion, and the full set of workflow steps.
 *
 * <p>NOTE: This example requires write access to a Kafka cluster, selected with the {@code
 * sql.current-catalog} (environment name) and {@code sql.current-database} (Kafka cluster name)
 * configuration options.
 *
 * <p>ALSO NOTE: The example submits an unbounded background statement. Use the lifecycle actions
 * (see {@code .github/workflows-examples/manage.yml}) or the Web UI to stop and delete the
 * statement afterward to clean up resources.
 */
public class Example_08_IntegrationAndDeployment {

    // Name of the table that stores the results
    static final String TARGET_TABLE = "VendorsPerBrand";

    /**
     * The pipeline logic under test: counts the number of vendors per brand.
     *
     * <p>This class must not reference any {@code io.confluent.flink.plugin} classes so that unit
     * tests can run it on Apache Flink without the plugin on the classpath.
     */
    public static class VendorsPerBrand {
        public static Table buildPipeline(Table products) {
            return products.groupBy($("brand")).select($("brand"), lit(1).count().as("vendors"));
        }
    }

    // The main() method performs the deployment, unless an action argument is present, in which
    // case ConfluentSettings.fromArgs(...) below executes that action and exits before the rest
    // of this method runs.
    public static void main(String[] args) {
        // All configuration comes from the deployment via fromArgs (with environment variables as
        // a fallback): the connection settings, the statement and application names, and the
        // target catalog and database (set with sql.current-catalog / sql.current-database). In
        // GitHub Actions these map to repository or environment secrets; see the README.
        EnvironmentSettings settings = ConfluentSettings.fromArgs(args);
        TableEnvironment env = TableEnvironment.create(settings);

        System.out.println("Creating table... " + TARGET_TABLE);
        // The pipeline owns its output table and creates it on the first deployment.
        env.executeSql(
                String.format(
                        "CREATE TABLE IF NOT EXISTS `%s`\n"
                                + "(brand STRING, vendors BIGINT, PRIMARY KEY(brand) NOT ENFORCED)\n"
                                + "DISTRIBUTED INTO 1 BUCKETS",
                        TARGET_TABLE));

        System.out.println("Deploying statement...");
        // The same pipeline logic that was tested locally and against Confluent Cloud now runs
        // unbounded on the continuously generated rows of the examples catalog.
        Table products = env.from("`examples`.`marketplace`.`products`");
        TableResult result =
                VendorsPerBrand.buildPipeline(products).insertInto(TARGET_TABLE).execute();

        // Print the final submitted name (application prefix included) for use with the lifecycle
        // actions. If no name was configured, the plugin generates one, which is not addressable
        // for later management; CI/CD deployments should always pass --statement-name.
        System.out.println(
                "Statement has been deployed as: " + ConfluentTools.getStatementName(result));
    }
}
