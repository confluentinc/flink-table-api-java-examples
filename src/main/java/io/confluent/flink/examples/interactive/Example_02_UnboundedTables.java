package io.confluent.flink.examples.interactive;

import io.confluent.flink.plugin.ConfluentSettings;

import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;

import java.util.stream.Stream;

import static org.apache.flink.table.api.Expressions.$;

/** A table program example that illustrates bounded and unbounded statements. */
public class Example_02_UnboundedTables {

    // All logic is defined in a main() method. It can run both in an IDE or CI/CD system.
    public static void main(String[] args) {
        EnvironmentSettings settings =
                ConfluentSettings.newBuilderFromResource("/cloud.properties")
                        .setApplicationName("unbounded-tables")
                        .applyArgs(args)
                        .build();
        TableEnvironment env = TableEnvironment.create(settings);
        env.useCatalog("examples");
        env.useDatabase("marketplace");

        // Statements can be finite (i.e. bounded) or infinite (i.e. unbounded).
        // If one of the accessed input tables is unbounded, the statement is unbounded.

        System.out.println("Running bounded statements for listing...");

        // Catalog operations (such as show/list queries) are always finite
        env.executeSql("SHOW TABLES").print();
        Stream.of(env.listTables()).forEach(System.out::println);

        System.out.println("Running bounded statement from values...");

        // Pipelines derived from finite tables (such as fromValues) are bounded as well
        env.fromValues("Bob", "Alice", "Peter")
                .as("name")
                .filter($("name").like("%e%"))
                .execute()
                .print();

        System.out.println(
                "Running unbounded statement... this does not terminate on its own; press Ctrl+C to"
                        + " stop it.");

        // Confluent's unbounded streaming examples don't terminate and mock real-time data from
        // Kafka. Unlike the bounded statements above, print() keeps streaming results to the
        // console forever, so you must interrupt the program (Ctrl+C) to stop it. To cap the
        // output instead, use ConfluentTools.printMaterialized/printChangelog with a row limit
        // (see Example_07).
        env.from("clicks")
                .groupBy($("user_id"))
                .select($("user_id"), $("view_time").sum())
                .execute()
                .print();
    }
}
