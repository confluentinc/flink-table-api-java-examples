package io.confluent.flink.examples.advanced.changelogs;

import io.confluent.flink.plugin.ConfluentSettings;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableEnvironment;

import static org.apache.flink.table.api.Expressions.$;
import static org.apache.flink.table.api.Expressions.descriptor;
import static org.apache.flink.table.api.Expressions.lit;
import static org.apache.flink.table.api.Expressions.map;
import static org.apache.flink.table.api.Expressions.row;

/**
 * A table program example that showcases a pipeline that reconstructs an updating {@code orders}
 * table from a change feed, enriches it, and exports it as an append-only "soft delete" stream for
 * a destination that cannot apply physical deletes (e.g. a data warehouse, an audit log, a plain
 * Kafka topic).
 *
 * <p>{@code toChangelog} makes the updating table append-only. {@code op_mapping} collapses the
 * change operations onto a {@code deleted} flag instead of keeping Flink's operation names. {@code
 * produces_full_deletes => false} emits key-only deletes: non-key columns are null on a delete,
 * keeping tombstones compact.
 *
 * <p>NOTE: the {@code op_mapping} and {@code produces_full_deletes} features are provided by the
 * Confluent and executed by the Confluent Cloud planner. They cannot be validated by the local
 * Apache Flink planner used in unit tests.
 */
public class Example_01_SoftDeleteExport {

    // All logic is defined in a main() method. It can run both in an IDE or CI/CD system.
    public static void main(String[] args) {
        EnvironmentSettings settings =
                ConfluentSettings.newBuilderFromResource("/cloud.properties")
                        .setApplicationName("changelog-soft-delete-export")
                        .applyArgs(args)
                        .build();
        TableEnvironment env = TableEnvironment.create(settings);

        // 1. Reconstruct an updating 'orders' table from a change feed that inserts two orders,
        //    updates one, and deletes another.
        Table orders =
                env.fromValues(
                                DataTypes.ROW(
                                        DataTypes.FIELD("op", DataTypes.STRING()),
                                        DataTypes.FIELD("order_id", DataTypes.INT()),
                                        DataTypes.FIELD("customer", DataTypes.STRING()),
                                        DataTypes.FIELD("amount", DataTypes.INT())),
                                row("INSERT", 1, "Alice", 100),
                                row("INSERT", 2, "Bob", 50),
                                row("UPDATE_BEFORE", 1, "Alice", 100),
                                row("UPDATE_AFTER", 1, "Alice", 130),
                                row("DELETE", 2, "Bob", 50))
                        .fromChangelog();

        // 2. Enrich the updating table with a derived column before exporting.
        Table enriched =
                orders.select(
                        $("order_id"),
                        $("customer"),
                        $("amount"),
                        $("amount").isGreaterOrEqual(100).as("is_large_order"));

        System.out.println("Enriched updating orders table...");
        enriched.execute().print();

        // 3. Export as an append-only soft-delete stream. The 'deleted' column replaces Flink's
        //    operation names. produces_full_deletes => false emits key-only deletes. Key-only
        //    deletes need identifying columns, so the call must define a key via PARTITION BY.
        //    Downstream reads an insert-only stream where deleted='true' marks a tombstone.
        Table exportStream =
                enriched.partitionBy($("order_id"))
                        .process(
                                "TO_CHANGELOG",
                                descriptor("deleted").asArgument("op"),
                                map(
                                                "INSERT, UPDATE_AFTER",
                                                "false",
                                                "UPDATE_BEFORE, DELETE",
                                                "true")
                                        .asArgument("op_mapping"),
                                lit(false).asArgument("produces_full_deletes"));

        System.out.println("Append-only soft-delete export with key-only tombstones...");
        exportStream.execute().print();
    }
}
