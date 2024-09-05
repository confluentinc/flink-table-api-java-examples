package io.confluent.flink.examples.table;

import io.confluent.flink.plugin.ConfluentSettings;
import io.confluent.flink.plugin.ConfluentTools;

import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.types.Row;

import java.util.List;

/**
 * A table program example to get started.
 *
 * <p>It executes two foreground statements in Confluent Cloud. The results of both statements are
 * printed to the console.
 */
public class Example_00_HelloWorld {

    // All logic is defined in a main() method. It can run both in an IDE or CI/CD system.
    public static void main(String[] args) {
        // Setup connection properties to Confluent Cloud
        EnvironmentSettings settings =
                ConfluentSettings.fromPropertiesResource("/cloud.properties");

        // Initialize the session context to get started
        TableEnvironment env = TableEnvironment.create(settings);

        System.out.println("Running with printing...");

        // The Table API is centered around 'Table' objects which help in defining data pipelines
        // fluently. Pipelines can be defined fully programmatic...
        Table table = env.fromValues("Hello world!");
        // ... or with embedded Flink SQL
        // Table table = env.sqlQuery("SELECT 'Hello world!'");

        // Once the pipeline is defined, execute it on Confluent Cloud.
        // If no target table has been defined, results are streamed back and can be printed
        // locally. This can be useful for development and debugging.
        table.execute().print();

        System.out.println("Running with collecting...");

        // Results can not only be printed but also collected locally and accessed individually.
        // This can be useful for testing.
        Table moreHellos = env.fromValues("Hello Bob", "Hello Alice", "Hello Peter").as("greeting");
        List<Row> rows = ConfluentTools.collectChangelog(moreHellos, 10);
        rows.forEach(
                r -> {
                    String column = r.getFieldAs("greeting");
                    System.out.println("Greeting: " + column);
                });
    }
}
