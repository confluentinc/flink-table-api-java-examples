package io.confluent.flink.examples.table;

import io.confluent.flink.plugin.ConfluentSettings;
import io.confluent.flink.plugin.ConfluentTools;

import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableEnvironment;

import static org.apache.flink.table.api.Expressions.$;

/** A table program example that illustrates how to deal with changelogs. */
public class Example_07_Changelogs {

    public static void main(String[] args) {
        EnvironmentSettings settings = ConfluentSettings.fromResource("/cloud.properties");
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

        // Use ConfluentTools to visualise either the changelog or materialized in-memory table.
        // The 'customers' table is unbounded by default, but the tool allows to stop consuming
        // after 100 events for debugging
        System.out.println("Print a capped changelog...");
        ConfluentTools.printChangelog(customers, 100);
        System.out.println("Print a table of the capped and applied changelog...");
        ConfluentTools.printMaterialized(customers, 100);
    }
}
