package io.confluent.flink.examples.app;

import io.confluent.flink.examples.functions.ClickInactivityMonitor;
import io.confluent.flink.plugin.ConfluentSettings;
import io.confluent.flink.plugin.ConfluentTableDescriptor;
import io.confluent.flink.plugin.ConfluentTools;

import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.types.DataType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.flink.table.api.Expressions.$;
import static org.apache.flink.table.api.Expressions.descriptor;
import static org.apache.flink.table.api.Expressions.lit;

/**
 * An example that illustrates how to deploy a {@link ClickInactivityMonitor} process table function
 * (PTF) as a long-running background statement, following the same deployment pattern as {@code
 * ReferenceApp_01_IntegrationAndDeployment}.
 *
 * <p>{@code Example_10_ProcessTableFunction} shows the same kind of {@link ClickInactivityMonitor}
 * PTF invoked inline for a quick, one-off look at its output; this example instead wires it into a
 * pipeline that owns its target table and is submitted as a named, deployable statement, so it
 * keeps running and can be managed (listed, stopped, resumed, deleted) like any other production
 * statement.
 *
 * <p>As in {@code ReferenceApp_01_IntegrationAndDeployment}, the pipeline logic in {@link
 * #detectInactivity(Table)} takes its input table as a parameter instead of resolving it from a
 * catalog. This lets the exact same logic run against a test fixture in {@code
 * ReferenceApp_02_ProcessTableFunctionIT}, not just the real {@code examples.marketplace.clicks}
 * table.
 *
 * <p>NOTE: The example submits an unbounded background statement. Stop and delete it afterward to
 * clean up resources, using any of the approaches the examples cover:
 *
 * <ul>
 *   <li>the Web UI;
 *   <li>the CLI lifecycle actions -- run a deployable app with a {@code stop}/{@code delete}
 *       action, e.g. {@code ./bin/run.sh ReferenceApp_01_IntegrationAndDeployment stop
 *       --statement-name inactivity-alerts} (see the README's CI/CD section and {@code
 *       .github/workflows-examples/manage.yml}, which wraps these actions);
 *   <li>programmatically with a {@code StatementHandle} (or the {@code
 *       ConfluentTools.stop/deleteStatement} helpers), as demonstrated in {@code
 *       Example_11_StatefulProcessTableFunction}.
 * </ul>
 */
public class ReferenceApp_02_ProcessTableFunction {

    private static final Logger LOG =
            LoggerFactory.getLogger(ReferenceApp_02_ProcessTableFunction.class);

    // Name of the table that stores detected inactivity alerts.
    static final String TARGET_TABLE = "InactivityAlerts";

    // All logic is defined in a main() method. It can run both in an IDE or CI/CD system.
    public static void main(String[] args) {
        // Connection settings come from the environment (a FLINK_PROPERTIES file for safe config
        // plus environment variables for the API key and secret), the same as
        // ReferenceApp_01_IntegrationAndDeployment.
        EnvironmentSettings settings =
                ConfluentSettings.newBuilder()
                        .setApplicationName("process-table-function-app")
                        .applyArgs(args)
                        .build();
        TableEnvironment env = TableEnvironment.create(settings);

        Table alerts = detectInactivity(env.from("`examples`.`marketplace`.`clicks`"));

        LOG.info("Creating table... {}", TARGET_TABLE);
        // The pipeline owns its output table and creates it on the first deployment. Rather than
        // restating the columns by hand, infer the schema from the alerts pipeline itself -- it
        // already carries the user_id partition key and the rowtime the PTF appends. Passing
        // ignoreIfExists=true keeps redeployment idempotent, matching CREATE TABLE IF NOT EXISTS.
        DataType alertRow = alerts.getResolvedSchema().toPhysicalRowDataType();
        env.createTable(
                TARGET_TABLE,
                ConfluentTableDescriptor.forManaged()
                        .schema(
                                Schema.newBuilder()
                                        .fromFields(
                                                DataType.getFieldNames(alertRow),
                                                DataType.getFieldDataTypes(alertRow))
                                        .build())
                        .distributedInto(1)
                        .build(),
                true);

        LOG.info("Deploying statement...");
        // Name the submitted statement in code so lifecycle actions can target it for later
        // management. This runs after applyArgs, so it takes precedence over any --statement-name
        // argument; set it on the builder before applyArgs instead if the argument should win.
        ConfluentTools.setStatementName(env, "inactivity-alerts");
        TableResult result = alerts.insertInto(TARGET_TABLE).execute();

        // Print the final submitted name (application prefix included) for use with the lifecycle
        // actions. If no name was configured, the plugin generates one, which is not addressable
        // for later management; CI/CD deployments should always pass --statement-name.
        LOG.info("Statement has been deployed as: {}", ConfluentTools.getStatementName(result));
    }

    /** Invokes the PTF against a clicks table, partitioned by user_id. */
    static Table detectInactivity(Table clicks) {
        return clicks.partitionBy($("user_id"))
                .process(
                        ClickInactivityMonitor.class,
                        lit(30).asArgument("timeoutSeconds"),
                        descriptor("$rowtime").asArgument("on_time"));
    }
}
