package io.confluent.flink.examples.table;

import io.confluent.flink.plugin.ConfluentSettings;
import io.confluent.flink.plugin.ConfluentTableDescriptor;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.StatementSet;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TablePipeline;

import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.apache.flink.table.api.Expressions.$;
import static org.apache.flink.table.api.Expressions.concat;
import static org.apache.flink.table.api.Expressions.row;

/**
 * A table program example that demos how to pipe data into a table or multiple tables.
 *
 * <p>NOTE: This example requires write access to a Kafka cluster. Fill out the given variables
 * below with target catalog/database if this is fine for you.
 *
 * <p>ALSO NOTE: The example submits an unbounded background statement. Make sure to stop the
 * statement in the Web UI afterward to clean up resources.
 */
public class Example_05_TablePipelines {

    // Fill this with an environment you have write access to
    static final String TARGET_CATALOG = "";

    // Fill this with a Kafka cluster you have write access to
    static final String TARGET_DATABASE = "";

    // Fill this with names of the Kafka Topics you want to create
    static final String TARGET_TABLE1 = "PricePerProduct";
    static final String TARGET_TABLE2 = "PricePerCustomer";

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        EnvironmentSettings settings = ConfluentSettings.fromResource("/cloud.properties");
        TableEnvironment env = TableEnvironment.create(settings);
        env.useCatalog(TARGET_CATALOG);
        env.useDatabase(TARGET_DATABASE);

        System.out.println("Creating tables... " + List.of(TARGET_TABLE1, TARGET_TABLE2));

        // Create two helper tables that will be filled with data from examples
        env.createTable(
                TARGET_TABLE1,
                ConfluentTableDescriptor.forManaged()
                        .schema(
                                Schema.newBuilder()
                                        .column("product_id", DataTypes.STRING().notNull())
                                        .column("price", DataTypes.DOUBLE().notNull())
                                        .build())
                        .distributedInto(1)
                        .build());
        env.createTable(
                TARGET_TABLE2,
                ConfluentTableDescriptor.forManaged()
                        .schema(
                                Schema.newBuilder()
                                        .column("customer_id", DataTypes.INT().notNull())
                                        .column("price", DataTypes.DOUBLE().notNull())
                                        .build())
                        .distributedInto(1)
                        .build());

        System.out.println("Executing table pipeline synchronous...");

        // A TablePipeline describes a flow of data from source(s) to sink.
        // In this case, from values to a Kafka-backed target table
        TablePipeline pipeline =
                env.fromValues(row("1408", 27.71), row("1062", 94.39), row("42", 80.01))
                        .insertInto(TARGET_TABLE1);

        // One can explain or execute a pipeline
        pipeline.printExplain();

        // Execution happens async by default, use await() to attach to the execution in case all
        // sources are finite (i.e. bounded).
        // For infinite (i.e. unbounded) sources, waiting for completion would not make much sense.
        pipeline.execute().await();

        System.out.println("Executing statement set asynchronous...");

        // The API supports more than a single sink, you can also fan out to different tables while
        // reading from a table once using a StatementSet:
        StatementSet statementSet =
                env.createStatementSet()
                        .add(
                                env.from("`examples`.`marketplace`.`orders`")
                                        .select($("product_id"), $("price"))
                                        .insertInto(TARGET_TABLE1))
                        .add(
                                env.from("`examples`.`marketplace`.`orders`")
                                        .select($("customer_id"), $("price"))
                                        .insertInto(TARGET_TABLE2));

        // One can explain or execute a statement set
        statementSet.printExplain();

        // Executes a statement set that splits the 'orders' table into two tables,
        // a 'product_id | price' table and a 'customer_id | price' one
        statementSet.execute();

        System.out.println("Reading merged data written by background statement...");

        // For this example, we read both target tables in again and union them into one output to
        // verify that the data arrives
        Table targetTable1 =
                env.from(TARGET_TABLE1)
                        .select(concat($("product_id"), " event in ", TARGET_TABLE1));
        Table targetTable2 =
                env.from(TARGET_TABLE2)
                        .select(
                                concat(
                                        $("customer_id").cast(DataTypes.STRING()),
                                        " event in ",
                                        TARGET_TABLE2));
        targetTable1.unionAll(targetTable2).as("status").execute().print();
    }
}
