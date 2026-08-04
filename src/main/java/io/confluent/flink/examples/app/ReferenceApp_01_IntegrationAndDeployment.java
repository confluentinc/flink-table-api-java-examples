package io.confluent.flink.examples.app;

import io.confluent.flink.plugin.ConfluentSettings;
import io.confluent.flink.plugin.ConfluentTableDescriptor;
import io.confluent.flink.plugin.ConfluentTools;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *       ReferenceApp_01_IntegrationAndDeploymentTest}), as well as on Confluent Cloud for Apache
 *       Flink, where the input is a Kafka-backed table (see {@code
 *       ReferenceApp_01_IntegrationAndDeploymentIT}).
 *   <li>The {@link #main(String[])} method is the deployment entrypoint. It wires the pipeline to
 *       Confluent Cloud and submits it as a long-running background statement.
 * </ul>
 *
 * <p>The program is configured with {@code ConfluentSettings.newBuilder().applyArgs(args)}: the
 * builder starts from the environment (a {@code FLINK_PROPERTIES} properties file plus environment
 * variables for secrets), and {@link ConfluentSettings.Builder#applyArgs(String[])} layers the
 * per-deployment command-line arguments on top. This also enables the plugin's built-in CI/CD
 * lifecycle actions: when the JAR is run with an action as the first argument ({@code list}, {@code
 * describe}, {@code resume}, {@code stop}, or {@code delete}), the plugin executes the action and
 * exits before the deployment logic runs, so the same JAR both deploys and manages (see {@code
 * .github/workflows-examples/manage.yml}).
 *
 * <p>The application name is set in code as a default and can be overridden per deployment with
 * {@code --application-name}. The statement name is deployment configuration passed via {@code
 * --statement-name}, which the lifecycle actions use to target the right statement for both deploy
 * and management. A program that submits several statements instead names each one in code via
 * {@link ConfluentTools#setStatementName(TableEnvironment, String)}.
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
public class ReferenceApp_01_IntegrationAndDeployment {

    private static final Logger LOG =
            LoggerFactory.getLogger(ReferenceApp_01_IntegrationAndDeployment.class);

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
    // case applyArgs(...) records it and build() below executes that action and exits before the
    // rest of this method runs.
    public static void main(String[] args) {
        // Connection settings come from the environment (a FLINK_PROPERTIES file for safe config
        // plus environment variables for the API key and secret). The application name is defaulted
        // in code here. The per-deployment arguments -- the statement name, the target catalog and
        // database (sql.current-catalog / sql.current-database), any lifecycle action, and an
        // optional --application-name override -- are layered on top with applyArgs. In GitHub
        // Actions the secrets map to repository or environment secrets; see the README.
        EnvironmentSettings settings =
                ConfluentSettings.newBuilder()
                        .setApplicationName("vendors-per-brand")
                        .applyArgs(args)
                        .build();
        TableEnvironment env = TableEnvironment.create(settings);

        LOG.info("Creating table... {}", TARGET_TABLE);
        // The pipeline owns its output table and creates it on the first deployment.
        env.createTable(
                TARGET_TABLE,
                ConfluentTableDescriptor.forManaged()
                        .schema(
                                Schema.newBuilder()
                                        .column("brand", DataTypes.STRING().notNull())
                                        .column("vendors", DataTypes.BIGINT())
                                        .primaryKey("brand")
                                        .build())
                        .distributedInto(1)
                        .build(),
                true);

        LOG.info("Deploying statement...");
        // The same pipeline logic that was tested locally and against Confluent Cloud now runs
        // unbounded on the continuously generated rows of the examples catalog.
        ConfluentTools.setStatementName(env, "pipeline");
        Table products = env.from("`examples`.`marketplace`.`products`");
        TableResult result =
                VendorsPerBrand.buildPipeline(products).insertInto(TARGET_TABLE).execute();

        // Print the final submitted name (application prefix included) for use with the lifecycle
        // actions. If no name was configured, the plugin generates one, which is not addressable
        // for later management; CI/CD deployments should always pass --statement-name.
        LOG.info("Statement has been deployed as: {}", ConfluentTools.getStatementName(result));
    }
}
