package io.confluent.flink.examples.advanced.changelogs;

import io.confluent.flink.plugin.ConfluentSettings;
import io.confluent.flink.plugin.ConfluentTableDescriptor;
import io.confluent.flink.plugin.ConfluentTools;

import org.apache.flink.table.annotation.ArgumentHint;
import org.apache.flink.table.annotation.DataTypeHint;
import org.apache.flink.table.annotation.StateHint;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.functions.ProcessTableFunction;
import org.apache.flink.types.Row;

import static org.apache.flink.table.annotation.ArgumentTrait.SET_SEMANTIC_TABLE;
import static org.apache.flink.table.api.Expressions.$;
import static org.apache.flink.table.api.Expressions.lit;
import static org.apache.flink.table.api.Expressions.map;
import static org.apache.flink.table.api.Expressions.row;

/**
 * A table program example that showcases a pipeline that ingests a Debezium-style Change Data
 * Capture (CDC) feed, reconstructs it into an upsert table, writes it into a primary-keyed sink,
 * derives an aggregate, and runs a ProcessTableFunction over the raw change events.
 *
 * <p>A Debezium feed is append-only and tags each row with a short operation code ({@code c}
 * create, {@code r} read/snapshot, {@code u} update, {@code d} delete). Updates carry only a single
 * {@code u} after-image (no before-image), which describes an <b>upsert</b> changelog and therefore
 * needs a key. {@code PARTITION BY id} supplies that key, and the built-in {@code FROM_CHANGELOG}
 * process table function is invoked by name via {@code process(...)}. {@code error_handling =>
 * 'SKIP'} drops rows whose op code is unknown instead of failing the pipeline.
 *
 * <p>NOTE: This example requires write access to a Kafka cluster. Configure a target catalog
 * (environment) and database (Kafka cluster) via {@code sql.current-catalog} / {@code
 * sql.current-database} in {@code cloud.properties}.
 *
 * <p>NOTE: The {@code op_mapping}, {@code error_handling}, and partitioned {@code FROM_CHANGELOG}
 * features are provided by the Confluent plugin and executed by the Confluent Cloud planner. They
 * cannot be validated by the local Apache Flink planner used in unit tests.
 */
public class Example_00_CdcIngestion {

    // A table this example creates and drops again at the end.
    static final String SINK_TABLE = "CdcCustomersUpsert";

    // All logic is defined in a main() method. It can run both in an IDE or CI/CD system.
    public static void main(String[] args) throws Exception {
        EnvironmentSettings settings =
                ConfluentSettings.newBuilderFromResource("/cloud.properties")
                        .setApplicationName("changelog-cdc-ingestion")
                        .applyArgs(args)
                        .build();
        TableEnvironment env = TableEnvironment.create(settings);

        // 1. A raw append-only CDC feed, where each row is tagged with the source system's op code.
        //    Updates carry only a single 'u' after-image (no before-image), i.e. an upsert. The 'x'
        //    row carries an unknown code to exercise error handling.
        Table cdcFeed =
                env.fromValues(
                        DataTypes.ROW(
                                DataTypes.FIELD("op", DataTypes.STRING()),
                                DataTypes.FIELD("id", DataTypes.INT()),
                                DataTypes.FIELD("region", DataTypes.STRING()),
                                DataTypes.FIELD("name", DataTypes.STRING())),
                        row("c", 1, "EU", "Alice"),
                        row("c", 2, "US", "Bob"),
                        row("r", 3, "EU", "Carol"),
                        row("u", 1, "EU", "Alice Cooper"),
                        row("x", 9, "??", "corrupt"),
                        row("d", 2, "US", "Bob"));

        // 2. Reconstruct the upsert table. PARTITION BY id defines the upsert key, op_mapping
        //    translates the Debezium codes, error_handling => 'SKIP' drops the corrupt 'x' row. The
        //    op column is consumed and the id key is preserved.
        Table customers =
                cdcFeed.partitionBy($("id"))
                        .process(
                                "FROM_CHANGELOG",
                                map("c, r", "INSERT", "u", "UPDATE_AFTER", "d", "DELETE")
                                        .asArgument("op_mapping"),
                                lit("SKIP").asArgument("error_handling"));

        // 3. An upsert changelog needs a sink with a primary key. Create an upsert-mode table
        //    keyed by id.
        env.createTable(
                SINK_TABLE,
                ConfluentTableDescriptor.forManaged()
                        .schema(
                                Schema.newBuilder()
                                        .column("id", DataTypes.INT().notNull())
                                        .column("region", DataTypes.STRING())
                                        .column("name", DataTypes.STRING())
                                        .primaryKey("id")
                                        .build())
                        .option("changelog.mode", "upsert")
                        .build(),
                true);

        try {
            System.out.println(
                    "Writing the reconstructed upsert changelog into the keyed table...");
            ConfluentTools.setStatementName(env, "cdc-upsert-changelog");
            customers.insertInto(SINK_TABLE).execute().await();

            // The materialized table holds the latest row per id.
            System.out.println("Materialized upsert result...");
            ConfluentTools.printMaterialized(env.from(SINK_TABLE), 6);

            // 4. Derive an aggregate from the reconstructed table. Counting customers per region is
            //    an updating query, so the CDC updates and deletes flow through it.
            System.out.println("Customers per region derived from the CDC stream...");
            ConfluentTools.printMaterialized(
                    env.from(SINK_TABLE)
                            .groupBy($("region"))
                            .select($("region"), $("id").count().as("customer_count")),
                    2);

            // 5. Run a ProcessTableFunction over the raw append-only feed, partitioned by customer
            //    id, to count how many change events each customer produced.
            System.out.println(
                    "Per-customer change-event counts via partitionBy().process(PTF)...");
            cdcFeed.partitionBy($("id")).process(ChangeEventCounter.class).execute().print();
        } finally {
            // Clean up the table.
            env.dropTable(SINK_TABLE, true);
        }
    }

    /**
     * A stateful {@link ProcessTableFunction} that counts the change events seen per partition key.
     */
    @DataTypeHint("ROW<`events` BIGINT NOT NULL>")
    public static class ChangeEventCounter extends ProcessTableFunction<Row> {

        public static class Counter {
            public long events = 0;
        }

        public void eval(@StateHint Counter state, @ArgumentHint(SET_SEMANTIC_TABLE) Row input) {
            state.events++;
            collect(Row.of(state.events));
        }
    }
}
