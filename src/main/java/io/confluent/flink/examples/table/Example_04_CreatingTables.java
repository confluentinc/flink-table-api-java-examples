package io.confluent.flink.examples.table;

import io.confluent.flink.plugin.ConfluentSettings;
import io.confluent.flink.plugin.ConfluentTableDescriptor;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.types.DataType;

import java.util.List;

/**
 * A table program example that illustrates how to create a table backed by a Kafka topic.
 *
 * <p>NOTE: This example requires write access to a Kafka cluster. Fill out the given variables
 * below with target catalog/database if this is fine for you.
 */
public class Example_04_CreatingTables {

    // Fill this with an environment you have write access to
    static final String TARGET_CATALOG = "";

    // Fill this with a Kafka cluster you have write access to
    static final String TARGET_DATABASE = "";

    // Fill this with names of the Kafka Topics you want to create
    static final String TARGET_TABLE1 = "MyExampleTable1";
    static final String TARGET_TABLE2 = "MyExampleTable2";

    public static void main(String[] args) {
        EnvironmentSettings settings =
                ConfluentSettings.fromPropertiesResource("/cloud.properties");
        TableEnvironment env = TableEnvironment.create(settings);
        env.useCatalog(TARGET_CATALOG);
        env.useDatabase(TARGET_DATABASE);

        System.out.println("Creating table... " + TARGET_TABLE1);

        // Create a table programmatically:
        // The table...
        //   - is backed by an equally named Kafka topic
        //   - stores its payload in JSON
        //   - will reference two Schema Registry subjects for Kafka message key and value
        //   - is distributed across 4 Kafka partitions based on the Kafka message key "user_id"
        env.createTable(
                TARGET_TABLE1,
                ConfluentTableDescriptor.forManaged()
                        .schema(
                                Schema.newBuilder()
                                        .column("user_id", DataTypes.STRING())
                                        .column("name", DataTypes.STRING())
                                        .column("email", DataTypes.STRING())
                                        .build())
                        .distributedBy(4, "user_id")
                        .option("kafka.retention.time", "0")
                        .option("key.format", "json-registry")
                        .option("value.format", "json-registry")
                        .build());

        // Alternatively, the call above could also be executed with SQL
        env.executeSql(
                "CREATE TABLE IF NOT EXISTS `"
                        + TARGET_TABLE1
                        + "` (\n"
                        + "  `user_id` STRING,\n"
                        + "  `name` STRING,\n"
                        + "  `email` STRING\n"
                        + ")\n"
                        + "DISTRIBUTED BY HASH(`user_id`) INTO 4 BUCKETS\n"
                        + "WITH (\n"
                        + "  'kafka.retention.time' = '0 ms',\n"
                        + "  'key.format' = 'json-registry',\n"
                        + "  'value.format' = 'json-registry'\n"
                        + ")");

        System.out.println("Creating table... " + TARGET_TABLE2);

        // The schema builders can be quite useful to avoid manual schema work. You can adopt schema
        // from other tables, massage the schema, and/or add additional columns
        DataType productsRow =
                env.from("examples.marketplace.products")
                        .getResolvedSchema()
                        .toPhysicalRowDataType();
        List<String> columnNames = DataType.getFieldNames(productsRow);
        List<DataType> columnTypes = DataType.getFieldDataTypes(productsRow);

        // In this example, the table will get all names/data types from the table 'products'
        // plus an 'additionalColumn' column
        env.createTable(
                TARGET_TABLE2,
                ConfluentTableDescriptor.forManaged()
                        .schema(
                                Schema.newBuilder()
                                        .fromFields(columnNames, columnTypes)
                                        .column("additionalColumn", DataTypes.STRING())
                                        .build())
                        .build());
    }
}
