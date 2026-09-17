package io.confluent.flink.examples.interactive;

import io.confluent.flink.plugin.ConfluentSettings;
import io.confluent.flink.plugin.ConfluentTools;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableEnvironment;

import static org.apache.flink.table.api.Expressions.$;
import static org.apache.flink.table.api.Expressions.descriptor;
import static org.apache.flink.table.api.Expressions.lit;
import static org.apache.flink.table.api.Expressions.map;
import static org.apache.flink.table.api.Expressions.row;

/** A table program example that illustrates how to deal with changelogs. */
public class Example_07_Changelogs {

    // All logic is defined in a main() method. It can run both in an IDE or CI/CD system.
    public static void main(String[] args) {
        EnvironmentSettings settings =
                ConfluentSettings.newBuilderFromResource("/cloud.properties")
                        .setApplicationName("changelogs")
                        .applyArgs(args)
                        .build();
        TableEnvironment env = TableEnvironment.create(settings);
        env.useCatalog("examples");
        env.useDatabase("marketplace");

        // Table API conceptually views streams as tables. However, every table can also be
        // converted to a stream of changes. This is the so-called 'stream-table duality'.
        // Although you conceptually work with tables, a changelog might be visible when
        // printing to the console for immediate real-time results.

        System.out.println("Print an append-only table...");

        // Queries on append-only tables produce insert-only streams.
        // When defining a table based on values, every row in the debug output contains
        // a +I change flag. The flag represents an insert-only change in the changelog
        env.fromValues(1, 2, 3, 5, 6).execute().print();

        System.out.println("Print an updating table...");

        // Even if the input was insert-only, the output might be updating. Operations such as
        // aggregations or outer joins might produce updating results with every incoming event.
        // Thus, an updating table becomes an updating stream where -U/+U/-D flags can be observed
        // in the debug output
        env.fromValues(1, 2, 3, 5, 6).as("c").select($("c").sum()).execute().print();

        // The 'customers' table in the 'examples' catalog is an updating table. It upserts based on
        // the defined primary key 'customer_id'
        Table customers = env.from("customers");

        // Use ConfluentTools to visualize either the changelog or materialized in-memory table.
        // The 'customers' table is unbounded by default, but the tool allows to stop consuming
        // after 100 events for debugging
        System.out.println("Print a capped changelog...");
        ConfluentTools.printChangelog(customers, 100);
        System.out.println("Print a table of the capped and applied changelog...");
        ConfluentTools.printMaterialized(customers, 100);

        // Usually the planner decides when a query emits inserts, updates, or deletes, and the
        // flags are only visible in debug output. By using Table.toChangelog() and
        // Table.fromChangelog() we can enforce a conversion to a changelog and back into the normal
        // table representation. For example adding a product to an existing brand retracts the old
        // count and emits a new one, so the changelog carries UPDATE_BEFORE/UPDATE_AFTER as well as
        // INSERT.
        Table productsPerBrand =
                env.fromValues(
                                DataTypes.ROW(
                                        DataTypes.FIELD("product", DataTypes.STRING()),
                                        DataTypes.FIELD("brand", DataTypes.STRING())),
                                row("MacBook", "Apple"),
                                row("iPhone", "Apple"),
                                row("Galaxy", "Samsung"))
                        .groupBy($("brand"))
                        .select($("brand"), $("product").count().as("cnt"));

        System.out.println("Print the implicit changelog of an updating table...");
        productsPerBrand.execute().print();

        // When converting to a changelog, each row becomes an INSERT-only row and the original
        // change survives in the prepended 'op' column.
        System.out.println("Capture the changelog as append-only data with toChangelog()...");
        productsPerBrand.toChangelog().execute().print();

        // The operation column is named 'op' by default. Pass a descriptor to choose a different
        // name.
        System.out.println("Rename the operation column with the 'op' descriptor argument...");
        productsPerBrand.toChangelog(descriptor("change_kind").asArgument("op")).execute().print();

        // An append-only change feed as it might arrive from an external system, where each row
        // carries the data plus a STRING 'op' column usingFlink's canonical change-operation names.
        Table changeFeed =
                env.fromValues(
                        DataTypes.ROW(
                                DataTypes.FIELD("op", DataTypes.STRING()),
                                DataTypes.FIELD("customer_id", DataTypes.INT()),
                                DataTypes.FIELD("name", DataTypes.STRING())),
                        row("INSERT", 1, "Alice"),
                        row("INSERT", 2, "Bob"),
                        row("UPDATE_BEFORE", 1, "Alice"),
                        row("UPDATE_AFTER", 1, "Alice Cooper"),
                        row("DELETE", 2, "Bob"));

        // Reconstruct the table to see the real change flags again (+I/-U/+U/-D). It no longer
        // contains the 'op' column.
        System.out.println(
                "Turn the change feed back into an updating table with fromChangelog()...");
        changeFeed.fromChangelog().execute().print();

        // The two operations are inverses. Converting an updating table to a changelog and back
        // yields the same updating table.
        System.out.println(
                "Round-trip: toChangelog() then fromChangelog() reconstructs the input...");
        productsPerBrand.toChangelog().fromChangelog().execute().print();

        // A change feed may contain rows whose op code is unknown or NULL. By default,
        // fromChangelog fails on such a row. Pass error_handling => 'SKIP' to drop the malformed
        // rows and keep going.
        Table feedWithBadRow =
                env.fromValues(
                        DataTypes.ROW(
                                DataTypes.FIELD("op", DataTypes.STRING()),
                                DataTypes.FIELD("customer_id", DataTypes.INT()),
                                DataTypes.FIELD("name", DataTypes.STRING())),
                        row("INSERT", 1, "Alice"),
                        row("BOGUS", 2, "Bob"),
                        row("INSERT", 3, "Carol"));

        System.out.println(
                "fromChangelog() dropping malformed rows with error_handling => 'SKIP'...");
        feedWithBadRow.fromChangelog(lit("SKIP").asArgument("error_handling")).execute().print();

        // produces_full_deletes controls how DELETE rows look when converting to a changelog. The
        // default is 'true', where delete carries the full row image. Pass 'false' to emit
        // key-only deletes. Key-only deletes need identifying columns, so the call must define a
        // key via PARTITION BY.
        Table updatingCustomers =
                env.fromValues(
                                DataTypes.ROW(
                                        DataTypes.FIELD("op", DataTypes.STRING()),
                                        DataTypes.FIELD("customer_id", DataTypes.INT()),
                                        DataTypes.FIELD("name", DataTypes.STRING())),
                                row("INSERT", 1, "Alice"),
                                row("INSERT", 2, "Bob"),
                                row("DELETE", 2, "Bob"))
                        .fromChangelog();

        System.out.println(
                "toChangelog() with key-only deletes via produces_full_deletes => false...");
        updatingCustomers
                .partitionBy($("customer_id"))
                .process("TO_CHANGELOG", lit(false).asArgument("produces_full_deletes"))
                .execute()
                .print();

        // With PARTITION BY, the built-in FROM_CHANGELOG PTF is invoked by name through process(),
        // and the partition key is preserved in the output. This feed carries before- and
        // after-images (UB/UA), so the reconstruction is a retract changelog that can be printed
        // directly.
        Table changeEvents =
                env.fromValues(
                        DataTypes.ROW(
                                DataTypes.FIELD("op", DataTypes.STRING()),
                                DataTypes.FIELD("customer_id", DataTypes.INT()),
                                DataTypes.FIELD("name", DataTypes.STRING())),
                        row("I", 1, "Alice"),
                        row("UB", 1, "Alice"),
                        row("UA", 1, "Alice Cooper"),
                        row("D", 1, "Alice Cooper"));

        System.out.println(
                "Invoke FROM_CHANGELOG with PARTITION BY via partitionBy().process()...");
        changeEvents
                .partitionBy($("customer_id"))
                .process(
                        "FROM_CHANGELOG",
                        map(
                                        "I", "INSERT",
                                        "UB", "UPDATE_BEFORE",
                                        "UA", "UPDATE_AFTER",
                                        "D", "DELETE")
                                .asArgument("op_mapping"))
                .execute()
                .print();
    }
}
