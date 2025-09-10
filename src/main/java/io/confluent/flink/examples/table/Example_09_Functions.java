package io.confluent.flink.examples.table;

import io.confluent.flink.plugin.ConfluentSettings;

import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.functions.ScalarFunction;
import org.apache.flink.table.functions.TableFunction;

import java.util.List;

import static org.apache.flink.table.api.Expressions.$;
import static org.apache.flink.table.api.Expressions.array;
import static org.apache.flink.table.api.Expressions.call;
import static org.apache.flink.table.api.Expressions.row;

/**
 * A table program example illustrating how to use User-Defined Functions (UDFs) in the Flink Table
 * API.
 *
 * <p>Flink Table API simplifies the process of creating and managing UDFs.
 *
 * <ul>
 *   <li>It helps creating a JAR file containing all required dependencies for a given UDF.
 *   <li>Uploads the JAR to Confluent artifact API.
 *   <li>Creates SQL functions for given artifacts.
 * </ul>
 */
public class Example_09_Functions {

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

        System.out.println("Registering a scalar function...");
        // The Table API underneath creates a temporary JAR file containing all transitive classes
        // required to run the function, uploads it to Confluent Cloud, and registers the function
        // using the previously uploaded artifact.
        env.createFunction("CustomTax", CustomTax.class, true);

        // As of now, Scalar and Table functions are supported.
        System.out.println("Registering a table function...");
        env.createFunction("Explode", Explode.class, true);

        // Once registered, the functions can be used in Table API and SQL queries.
        System.out.println("Executing registered UDFs...");
        env.fromValues(row("Apple", "USA", 2), row("Apple", "EU", 3))
                .select(
                        $("f0").as("product"),
                        $("f1").as("location"),
                        $("f2").times(call("CustomTax", $("f1"))).as("tax"))
                .execute()
                .print();

        env.fromValues(
                        row(1L, "Ann", array("Apples", "Bananas")),
                        row(2L, "Peter", array("Apples", "Pears")))
                .joinLateral(call("Explode", $("f2")).as("fruit"))
                .select($("f0").as("id"), $("f1").as("name"), $("fruit"))
                .execute()
                .print();

        // Instead of registering functions permanently, you can embed UDFs directly into queries
        // without registering them first. This will upload all the functions of the query as a
        // single artifact to Confluent Cloud. Moreover, the functions lifecycle will be bound to
        // the lifecycle of the query.
        System.out.println("Executing inline UDFs...");
        env.fromValues(row("Apple", "USA", 2), row("Apple", "EU", 3))
                .select(
                        $("f0").as("product"),
                        $("f1").as("location"),
                        $("f2").times(call(CustomTax.class, $("f1"))).as("tax"))
                .execute()
                .print();

        env.fromValues(
                        row(1L, "Ann", array("Apples", "Bananas")),
                        row(2L, "Peter", array("Apples", "Pears")))
                .joinLateral(call(Explode.class, $("f2")).as("fruit"))
                .select($("f0").as("id"), $("f1").as("name"), $("fruit"))
                .execute()
                .print();
    }

    /** A scalar function that calculates a custom tax based on the provided location. */
    public static class CustomTax extends ScalarFunction {
        public int eval(String location) {
            if (location.equals("USA")) {
                return 10;
            }
            if (location.equals("EU")) {
                return 5;
            }
            return 0;
        }
    }

    /** A table function that explodes an array of string into multiple rows. */
    public static class Explode extends TableFunction<String> {
        public void eval(List<String> arr) {
            for (String i : arr) {
                collect(i);
            }
        }
    }
}
