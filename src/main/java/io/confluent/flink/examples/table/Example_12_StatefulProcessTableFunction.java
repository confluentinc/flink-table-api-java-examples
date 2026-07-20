package io.confluent.flink.examples.table;

import io.confluent.flink.plugin.ConfluentSettings;
import io.confluent.flink.plugin.ConfluentTableDescriptor;
import io.confluent.flink.plugin.ConfluentTools;
import io.confluent.flink.plugin.StatementHandle;

import org.apache.flink.table.annotation.ArgumentHint;
import org.apache.flink.table.annotation.DataTypeHint;
import org.apache.flink.table.annotation.StateHint;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.functions.ProcessTableFunction;
import org.apache.flink.types.Row;

import java.util.List;

import static org.apache.flink.table.annotation.ArgumentTrait.SET_SEMANTIC_TABLE;
import static org.apache.flink.table.api.Expressions.$;
import static org.apache.flink.table.api.Expressions.row;

/**
 * A table program example illustrating the <b>stateful</b> side of a {@link ProcessTableFunction}
 * (PTF) and how its state survives a full stop/resume lifecycle on Confluent Cloud.
 *
 * <p>Where {@link Example_11_ProcessTableFunction} focuses on the general look-and-feel, this
 * example focuses purely on <b>keyed state</b>: a PTF that keeps a running sum per partition key.
 * The point of the example is to show that this accumulated state is durable. When the statement is
 * stopped and later resumed, Flink restores the state and the running sums continue exactly where
 * they left off. There is no data loss (rows produced while the statement was stopped are still
 * processed) and no double counting (the sums are not restarted from zero).
 *
 * <p>Along the way it demonstrates the Confluent lifecycle tooling that makes this observable:
 *
 * <ul>
 *   <li>{@link ConfluentTools#setStatementName} to give the pipeline a stable, addressable name.
 *   <li>{@link ConfluentTools#getStatementHandle} to obtain a {@link StatementHandle}.
 *   <li>{@link StatementHandle#stop()}, {@link StatementHandle#resume()}, and {@link
 *       StatementHandle#delete()} to drive the statement through its lifecycle.
 *   <li>{@link ConfluentTools#collectChangelog} to collect data locally and observe the effect of
 *       state restore.
 * </ul>
 *
 * <h2>Background: state in Process Table Functions</h2>
 *
 * <p>A few concepts from Apache Flink are worth keeping in mind while reading this example:
 *
 * <ul>
 *   <li><b>Only set-semantic PTFs can be stateful.</b> State is declared by adding one or more
 *       mutable parameters annotated with {@link StateHint} to {@code eval()}. They come after an
 *       optional {@code Context} parameter and before the regular call arguments.
 *   <li><b>State is keyed and partition-scoped.</b> The {@code PARTITION BY} clause (here {@code
 *       partitionBy($("name"))}) decides how state is divided. Each partition key sees only its own
 *       state, so {@code Bob} and {@code Alice} accumulate independently. This is the same "keyed
 *       state" concept used throughout Flink.
 *   <li><b>Watch for unbounded state growth.</b> State can grow without bound either from an open
 *       keyspace (an unlimited number of distinct partition keys) or from growth within a single
 *       partition. Mitigate with {@code @StateHint(ttl = "...")} to expire idle state, by calling
 *       {@code Context.clearAllState()} when a partition is done, and by keeping state fields
 *       nullable.
 * </ul>
 *
 * <h2>Prerequisites</h2>
 *
 * <p>Unlike the read-only examples, this program <b>creates tables and submits a long-running
 * statement</b> in your environment. Point {@link #TARGET_CATALOG} and {@link #TARGET_DATABASE} at
 * a catalog (environment) and database (Kafka cluster) you have write access to. The program cleans
 * up after itself: it deletes the statement and drops the tables it created before returning.
 */
public class Example_12_StatefulProcessTableFunction {

    // Fill this with an environment you have write access to
    static final String TARGET_CATALOG = "";

    // Fill this with a Kafka cluster you have write access to
    static final String TARGET_DATABASE = "";

    // Names of the objects this example creates. They are dropped again at the end.
    static final String SOURCE_TABLE = "MyExampleSourceTable";
    static final String SINK_TABLE = "MyExampleSinkTable";

    // A stable, human-readable statement name; so we can address the running pipeline. Statement
    // names must be unique within an environment/region for an organization.
    static final String STATEMENT_NAME = "example-12-stateful-ptf";

    // All logic is defined in a main() method. It can run both in an IDE or CI/CD system.
    public static void main(String[] args) throws Exception {
        // Setup connection properties to Confluent Cloud
        EnvironmentSettings settings = ConfluentSettings.fromResource("/cloud.properties");

        // Initialize the session context to get started
        TableEnvironment env = TableEnvironment.create(settings);

        // Set default catalog and database
        env.useCatalog(TARGET_CATALOG);
        env.useDatabase(TARGET_DATABASE);

        // Start from a clean slate so the example is deterministic and re-runnable.
        env.dropTable(SOURCE_TABLE);
        env.dropTable(SINK_TABLE);

        // Create a source and a sink table with the Table Descriptor API.
        // ConfluentTableDescriptor.forManaged() targets Confluent's managed connector.
        // Both tables share the same schema and use a single bucket to keep the output ordering
        // easy to reason about.
        Schema schema =
                Schema.newBuilder()
                        .column("name", DataTypes.STRING().notNull())
                        .column("score", DataTypes.INT().notNull())
                        .build();
        ConfluentTableDescriptor tableDescriptor =
                ConfluentTableDescriptor.forManaged().schema(schema).distributedInto(1).build();
        env.createTable(SOURCE_TABLE, tableDescriptor);
        env.createTable(SINK_TABLE, tableDescriptor);

        try {
            runStatefulPtfLifecycle(env);
        } finally {
            // Clean up the tables regardless of outcome.
            env.dropTable(SOURCE_TABLE);
            env.dropTable(SINK_TABLE);
        }
    }

    private static void runStatefulPtfLifecycle(TableEnvironment env) throws Exception {
        // 1. Seed the source with an initial batch of scores
        // Two rows for Bob, one each for Alice and Charley.
        // Because "name" partitions the PTF, each name accumulates its scores independently.
        System.out.println("Inserting the initial batch of scores...");
        env.fromValues(row("Bob", 1), row("Bob", 2), row("Alice", 3), row("Charley", 4))
                .insertInto(SOURCE_TABLE)
                .execute()
                .await();

        // 2. Submit the main stateful PTF pipeline
        // Name the statement before submission so we can address it later.
        ConfluentTools.setStatementName(env, STATEMENT_NAME);

        // The PTF reads the source as a set-semantic table, partitioned by name, and writes a
        // running sum per name into the sink. This statement runs continuously on Confluent Cloud.
        System.out.println("Submitting the stateful ProcessTableFunction pipeline...");
        TableResult result =
                env.from(SOURCE_TABLE)
                        .partitionBy($("name"))
                        .process(ScoreAccumulator.class)
                        .insertInto(SINK_TABLE)
                        .execute();

        // Get a handle to control the statement lifecycle.
        // The handle is the primary way to stop, resume, and delete
        // a running statement from Table API code.
        StatementHandle handle = ConfluentTools.getStatementHandle(result);
        System.out.println("Submitted statement: " + handle.getName());

        // 3. Observe the accumulated state before stopping
        // Read the first four sink rows.
        // collectChangelog() starts a separate bounded read against
        // the sink and returns once the requested number of rows has arrived.
        // The running sums are:
        //   Bob:     1 -> 1, then +2 -> 3
        //   Alice:   3 -> 3
        //   Charley: 4 -> 4
        System.out.println("Collecting output before stop...");
        List<Row> beforeStop =
                ConfluentTools.collectChangelog(
                        env.from(SINK_TABLE).select($("name"), $("score")), 4);
        beforeStop.forEach(System.out::println);
        // Expected (in any order):
        //   +I[Bob, 1]
        //   +I[Bob, 3]
        //   +I[Alice, 3]
        //   +I[Charley, 4]

        // 4. Stop the statement
        // stop() blocks until the statement reaches a stopped state.
        // Flink takes a snapshot of the PTF state on the way down,
        // so the accumulated sums are preserved.
        System.out.println("Stopping the statement (state is snapshotted)...");
        handle.stop();

        // 5. Produce more data while the statement is stopped
        // These rows land in the source topic but are not processed yet. They demonstrate that a
        // stopped statement loses no input: the data waits until the statement resumes.
        System.out.println("Inserting more scores while stopped...");
        env.fromValues(row("Bob", 5), row("Alice", 6)).insertInto(SOURCE_TABLE).execute().await();

        // 6. Resume the statement
        // resume() restores the previously snapshotted state and continues from where it left off.
        // The accumulators are NOT reset: Bob resumes from 3, Alice from 3.
        System.out.println("Resuming the statement (state is restored)...");
        handle.resume();

        // Add one more row after the resume to show processing continues normally.
        System.out.println("Inserting a final score after resume...");
        env.fromValues(row("Charley", 1)).insertInto(SOURCE_TABLE).execute().await();

        // 7. Observe the accumulated state after restore
        // Read all seven sink rows produced across the statement's lifetime.
        // The post-resume rows continue the running sums rather than restarting them:
        //   Bob:     3 (restored) + 5 -> 8
        //   Alice:   3 (restored) + 6 -> 9
        //   Charley: 4 (restored) + 1 -> 5
        System.out.println("Collecting output after restore...");
        List<Row> afterRestore =
                ConfluentTools.collectChangelog(
                        env.from(SINK_TABLE).select($("name"), $("score")), 7);
        afterRestore.forEach(System.out::println);
        // Expected (in any order):
        //   +I[Bob, 1]
        //   +I[Bob, 3]
        //   +I[Bob, 8]     <- continued from the restored sum of 3, not from 5
        //   +I[Alice, 3]
        //   +I[Alice, 9]   <- continued from the restored sum of 3, not from 6
        //   +I[Charley, 4]
        //   +I[Charley, 5] <- continued from the restored sum of 4, not from 1

        // 8. Inspect and delete the statement
        // The handle also exposes diagnostics: any warnings attached to the statement
        // and the raw OpenAPI representation for deeper inspection.
        handle.getWarnings()
                .forEach(
                        w ->
                                System.out.println(
                                        "Warning: " + w.getSeverity() + " " + w.getMessage()));
        System.out.println("Statement phase: " + handle.getSqlV1Statement().getStatus().getPhase());

        // delete() removes the statement entirely from the system, discarding its state. This is
        // different from stop(): a deleted statement cannot be resumed. It blocks until the
        // deletion completes.
        System.out.println("Deleting the statement...");
        handle.delete();
    }

    /**
     * A stateful {@link ProcessTableFunction} that accumulates a running sum of scores per
     * partition key.
     *
     * <p>The output type is declared with a {@link DataTypeHint} so it maps onto the sink's {@code
     * score} column. The framework automatically prepends the {@code name} partition key to the
     * emitted row, producing the {@code (name, score)} shape the sink expects.
     */
    @DataTypeHint("ROW<`score` INT NOT NULL>")
    public static class ScoreAccumulator extends ProcessTableFunction<Row> {

        /**
         * Per-key state. This POJO is the single value-state entry for each partition. It is small
         * and fixed-size, so plain value state (read on entry, written back on exit) is the right
         * choice; large or unbounded state would call for a TTL instead.
         */
        public static class SumState {
            public int sum = 0;
        }

        /**
         * Called for every input row. State parameters annotated with {@link StateHint} come first
         * (after an optional {@code Context}), then the set-semantic table argument. Flink hands us
         * the current state for the row's partition key; we mutate it and it is persisted
         * transparently.
         *
         * @param state per-key running sum, scoped to the table's partition key
         * @param input the current input row from the set-semantic table
         */
        public void eval(@StateHint SumState state, @ArgumentHint(SET_SEMANTIC_TABLE) Row input) {
            Integer score = input.getFieldAs("score");
            state.sum += score;
            collect(Row.of(state.sum));
        }
    }
}
