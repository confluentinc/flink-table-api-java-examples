package io.confluent.flink.examples.table;

import io.confluent.flink.plugin.ConfluentSettings;

import org.apache.flink.table.annotation.ArgumentHint;
import org.apache.flink.table.annotation.StateHint;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.functions.ProcessTableFunction;
import org.apache.flink.types.Row;

import java.time.Duration;
import java.time.Instant;

import static org.apache.flink.table.annotation.ArgumentTrait.REQUIRE_ON_TIME;
import static org.apache.flink.table.annotation.ArgumentTrait.SET_SEMANTIC_TABLE;
import static org.apache.flink.table.api.Expressions.$;
import static org.apache.flink.table.api.Expressions.descriptor;
import static org.apache.flink.table.api.Expressions.lit;

/**
 * A table program example illustrating how to use a {@link ProcessTableFunction} (PTF) in the Flink
 * Table API.
 *
 * <p>This example detects inactive users on the {@code examples.marketplace.clicks} table. For each
 * user, it counts clicks and registers an event-time timer. When no new click arrives within the
 * configured timeout, the timer fires and emits an alert with the user's accumulated click count.
 *
 * <p>Unlike a windowed aggregation, the PTF emits exactly once per inactivity period, combining
 * running state with the absence of events.
 */
public class Example_11_ProcessTableFunction {

    // Fill this with an environment you have write access to
    static final String TARGET_CATALOG = "";

    // Fill this with a Kafka cluster you have write access to
    static final String TARGET_DATABASE = "";

    // All logic is defined in a main() method. It can run both in an IDE or CI/CD system.
    public static void main(String[] args) {
        // Setup connection properties to Confluent Cloud
        EnvironmentSettings settings = ConfluentSettings.fromResource("/cloud.properties");

        // Initialize the session context to get started
        TableEnvironment env = TableEnvironment.create(settings);

        // Set default catalog and database
        env.useCatalog(TARGET_CATALOG);
        env.useDatabase(TARGET_DATABASE);

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

    /**
     * A ProcessTableFunction that detects user inactivity based on click events.
     *
     * <p>For each user (partitioned by {@code user_id}), it counts incoming clicks and registers a
     * named event-time timer. Each new click replaces the previous timer, resetting the inactivity
     * clock. When the timer fires (no new clicks within the timeout), an alert is emitted.
     */
    public static class ClickInactivityMonitor
            extends ProcessTableFunction<ClickInactivityMonitor.InactivityAlert> {

        /** Output POJO. The framework adds the user_id partition key and rowtime automatically. */
        public static class InactivityAlert {
            public int clickCount;
        }

        /** Per-user state. */
        public static class ClickState {
            public int clickCount = 0;
        }

        public void eval(
                Context ctx,
                @StateHint ClickState state,
                @ArgumentHint({SET_SEMANTIC_TABLE, REQUIRE_ON_TIME}) Row input,
                Integer timeoutSeconds) {

            state.clickCount++;

            // Each new click pushes the timeout forward; the timer fires only after true
            // inactivity.
            TimeContext<Instant> timeCtx = ctx.timeContext(Instant.class);
            timeCtx.registerOnTime(
                    "inactivity", timeCtx.time().plus(Duration.ofSeconds(timeoutSeconds)));
        }

        public void onTimer(ClickState state) {
            InactivityAlert alert = new InactivityAlert();
            alert.clickCount = state.clickCount;
            collect(alert);
        }
    }
}
