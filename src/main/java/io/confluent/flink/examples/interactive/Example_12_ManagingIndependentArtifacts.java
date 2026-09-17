package io.confluent.flink.examples.interactive;

import io.confluent.flink.plugin.ConfluentArtifact;
import io.confluent.flink.plugin.ConfluentArtifactOptions;
import io.confluent.flink.plugin.ConfluentSettings;
import io.confluent.flink.plugin.ConfluentTools;

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
 * A table program example illustrating how to use a {@link
 * ConfluentTools#createArtifact(TableEnvironment, String, ConfluentArtifactOptions, Class[])} and
 * {@link ConfluentTools#deleteArtifact(TableEnvironment, String)} to manage artifact and function
 * lifecycle independently.
 *
 * <p>This example uploads an artifact, creates functions and deletes them afterward, independent of
 * statement lifecycles.
 *
 * <p>NOTE: This example requires write access. It creates functions and uploads artifacts, so it
 * needs a target catalog (environment) and database (Kafka cluster) via {@code sql.current-catalog}
 * / {@code sql.current-database} in {@code cloud.properties}; the run fails fast with a clear
 * message if they are not set.
 */
public class Example_12_ManagingIndependentArtifacts {

    // All logic is defined in a main() method. It can run both in an IDE or CI/CD system.
    public static void main(String[] args) {
        // Setup connection properties to Confluent Cloud
        EnvironmentSettings settings =
                ConfluentSettings.newBuilderFromResource("/cloud.properties")
                        .setApplicationName("managing-independent-artifacts")
                        .applyArgs(args)
                        .build();

        // Initialize the session context to get started
        TableEnvironment env = TableEnvironment.create(settings);

        // Managing a single artifact and function detached from a statement can be done with the
        // usage of createFunction(...)
        System.out.println("Registering a table function...");
        env.createFunction("CustomTax", CustomTax.class, true);

        // Once registered, the function can be used in Table API and SQL queries.
        System.out.println("Executing registered UDF...");
        env.fromValues(row("Apple", "USA", 2), row("Apple", "EU", 3))
                .select(
                        $("f0").as("product"),
                        $("f1").as("location"),
                        $("f2").times(call("CustomTax", $("f1"))).as("tax"))
                .execute()
                .print();

        // A single created function can be cleaned up independently of the statements
        // you want to use them in.
        System.out.println("Clean up function...");
        env.dropFunction("CustomTax");

        // Artifacts can be uploaded separately independent of a statement submission and function
        // creation. You can provide ConfluentArtifactOptions to override or add options, like a
        // custom description or a documentation link. Multiple classes can be bundled into one
        // artifact as well. Just call the createArtifact function with additional classes you want
        // to bundle into one jar,e.g. ConfluentTools.createArtifact(..., ..., Class1.class,
        // Class2.class, Class3.class). For a single class the default path is generated from the
        // name of the given class. For multiple classes it is set to 'default'. You can customize
        // the path by overriding it in the ConfluentArtifactOptions:
        // ConfluentArtifactOptions.newBuilder().defaultPath("custom.path.to.Function$Udf").build().
        System.out.println("Creating artifact...");
        ConfluentArtifactOptions options =
                ConfluentArtifactOptions.newBuilder()
                        .description("A custom managed artifact.")
                        .documentationLink("https://link-to-docs.com")
                        .build();
        ConfluentArtifact artifact =
                ConfluentTools.createArtifact(
                        env, "external-jar", options, CustomTax.class, Explode.class);

        // Use the reference of the created artifact to create functions using your jar.
        System.out.println("Creating functions using the artifact...");
        env.executeSql(
                "CREATE FUNCTION IF NOT EXISTS External_CustomTax AS '"
                        + CustomTax.class.getName()
                        + "' "
                        + "USING JAR '"
                        + artifact.getReference()
                        + "'");
        env.executeSql(
                "CREATE FUNCTION IF NOT EXISTS External_Explode AS '"
                        + Explode.class.getName()
                        + "' "
                        + "USING JAR '"
                        + artifact.getReference()
                        + "'");

        System.out.println("Executing registered UDFs...");
        env.fromValues(row("Apple", "USA", 2), row("Apple", "EU", 3))
                .select(
                        $("f0").as("product"),
                        $("f1").as("location"),
                        $("f2").times(call("External_CustomTax", $("f1"))).as("tax"))
                .execute()
                .print();

        env.fromValues(
                        row(1L, "Ann", array("Apples", "Bananas")),
                        row(2L, "Peter", array("Apples", "Pears")))
                .joinLateral(call("External_Explode", $("f2")).as("fruit"))
                .select($("f0").as("id"), $("f1").as("name"), $("fruit"))
                .execute()
                .print();

        // Custom created functions and artifacts can be cleaned up independently of the statements
        // you want to use them in. For deleting an artifact just use the artifact id returned from
        // the creation request.
        System.out.println("Clean up functions and artifacts...");
        env.dropFunction("External_CustomTax");
        env.dropFunction("External_Explode");
        ConfluentTools.deleteArtifact(env, artifact.getId());
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
