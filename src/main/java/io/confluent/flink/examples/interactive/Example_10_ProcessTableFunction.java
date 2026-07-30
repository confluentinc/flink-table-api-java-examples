package io.confluent.flink.examples.interactive;

import io.confluent.flink.examples.functions.ClickInactivityMonitor;
import io.confluent.flink.plugin.ConfluentSettings;

import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;

import static org.apache.flink.table.api.Expressions.$;
import static org.apache.flink.table.api.Expressions.descriptor;
import static org.apache.flink.table.api.Expressions.lit;

/**
 * A table program example illustrating how to use a {@link ClickInactivityMonitor} process table
 * function (PTF) in the Flink Table API.
 *
 * <p>This example detects inactive users on the {@code examples.marketplace.clicks} table. For each
 * user, it counts clicks and registers an event-time timer. When no new click arrives within the
 * configured timeout, the timer fires and emits an alert with the user's accumulated click count.
 *
 * <p>Unlike a windowed aggregation, the PTF emits exactly once per inactivity period, combining
 * running state with the absence of events.
 *
 * <p>The PTF logic can be unit-tested locally without Confluent Cloud using {@code
 * ProcessTableFunctionTestHarness} -- see {@code Example_10_ProcessTableFunctionTest}.
 *
 * <p>NOTE: The inline PTF is uploaded as an artifact bound to the query, so this example requires
 * write access. Configure a target catalog (environment) and database (Kafka cluster) via {@code
 * sql.current-catalog} / {@code sql.current-database} in {@code cloud.properties}; the run fails
 * fast with a clear message if they are not set.
 */
public class Example_10_ProcessTableFunction {

    // All logic is defined in a main() method. It can run both in an IDE or CI/CD system.
    public static void main(String[] args) {
        // Setup connection properties to Confluent Cloud
        EnvironmentSettings settings =
                ConfluentSettings.newBuilderFromResource("/cloud.properties")
                        .setApplicationName("process-table-function")
                        .applyArgs(args)
                        .build();

        // Initialize the session context to get started
        TableEnvironment env = TableEnvironment.create(settings);

        // The inline PTF artifact is uploaded to the current catalog/database, taken from
        // sql.current-catalog / sql.current-database in cloud.properties (see
        // cloud.properties.template).

        // Invoke the PTF inline against the clicks table, partitioned by user_id.
        // The function's lifecycle is bound to this query.
        System.out.println("Executing inline ProcessTableFunction...");
        env.from("`examples`.`marketplace`.`clicks`")
                .partitionBy($("user_id"))
                .process(
                        ClickInactivityMonitor.class,
                        lit(30).asArgument("timeoutSeconds"),
                        descriptor("$rowtime").asArgument("on_time"))
                .execute()
                .print();
    }
}
