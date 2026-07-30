package io.confluent.flink.examples.interactive;

import io.confluent.flink.plugin.ConfluentSettings;

import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;

/** A table program example to interact with catalogs and databases. */
public class Example_01_CatalogsAndDatabases {

    // All logic is defined in a main() method. It can run both in an IDE or CI/CD system.
    public static void main(String[] args) {
        EnvironmentSettings settings =
                ConfluentSettings.newBuilderFromResource("/cloud.properties")
                        .setApplicationName("catalogs-and-databases")
                        .applyArgs(args)
                        .build();
        TableEnvironment env = TableEnvironment.create(settings);

        // Each catalog object is located in a catalog and database
        env.executeSql("SHOW TABLES IN `examples`.`marketplace`").print();

        // Catalog objects (e.g. tables) can be referenced by 3-part identifiers:
        // catalog.database.object
        env.from("`examples`.`marketplace`.`customers`").printSchema();

        // Select a current catalog and database to work with 1-part identifiers.
        // Both Name or ID can be used for this.
        env.executeSql("SHOW CATALOGS").print();
        env.useCatalog("examples");
        env.executeSql("SHOW DATABASES").print();
        env.useDatabase("marketplace");

        // Print the current catalog/database
        System.out.println(env.getCurrentCatalog());
        System.out.println(env.getCurrentDatabase());

        // Once current catalog/database are set, work with
        // 1-part identifiers (i.e. object names) to read tables
        env.from("`customers`").printSchema();
    }
}
