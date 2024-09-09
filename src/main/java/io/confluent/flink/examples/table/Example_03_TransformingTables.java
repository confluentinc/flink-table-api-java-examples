package io.confluent.flink.examples.table;

import io.confluent.flink.plugin.ConfluentSettings;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableEnvironment;

import static org.apache.flink.table.api.Expressions.$;
import static org.apache.flink.table.api.Expressions.row;
import static org.apache.flink.table.api.Expressions.withAllColumns;

/** A table program example that demos how to transform data with the {@link Table} object. */
public class Example_03_TransformingTables {

    public static void main(String[] args) {
        EnvironmentSettings settings = ConfluentSettings.fromResource("/cloud.properties");
        TableEnvironment env = TableEnvironment.create(settings);
        env.useCatalog("examples");
        env.useDatabase("marketplace");

        // The Table API is centered around 'Table' objects. These objects behave similar to SQL
        // views. In other words: You don't mutate data or store it in any way, you only define the
        // pipeline. No execution happens until execute() is called!

        // Read from tables like 'orders'
        Table orders = env.from("orders").select(withAllColumns());

        // Or mock tables with values
        Table customers =
                env.fromValues(
                        DataTypes.ROW(
                                DataTypes.FIELD("customer_id", DataTypes.INT()),
                                DataTypes.FIELD("name", DataTypes.STRING()),
                                DataTypes.FIELD("email", DataTypes.STRING())),
                        row(3160, "Bob", "bob@corp.com"),
                        row(3107, "Alice", "alice.smith@example.org"),
                        row(3248, "Robert", "robert@someinc.com"));

        // Use built-in expressions and functions in transformations such as filter().
        // All top-level expressions can be found in the Expressions.* class;
        // such as $() / col() for selecting columns or lit() for literals.
        // Further expressions can be accessed fluently after the top-level expression;
        // such as in(), round(), cast(), or as().
        Table filteredOrders = orders.filter($("customer_id").in(3160, 3107, 3107));

        // addColumns()/renameColumns()/dropColumns() modify some columns while
        // others stay untouched
        Table transformedOrders =
                filteredOrders
                        .addColumns($("price").round(0).cast(DataTypes.INT()).as("price_rounded"))
                        .renameColumns($("customer_id").as("o_customer_id"));

        // Use unbounded joins if the key space is small, otherwise take a look at interval joins
        Table joinedTable =
                customers
                        .join(transformedOrders, $("customer_id").isEqual($("o_customer_id")))
                        .dropColumns($("o_customer_id"));

        // The result shows a joined table with the following columns:
        // customer_id | name | email | order_id | product_id | price | price_rounded
        joinedTable.execute().print();
    }
}
