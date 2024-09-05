package io.confluent.flink.examples.table;

import io.confluent.flink.plugin.ConfluentSettings;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.expressions.Expression;
import org.apache.flink.types.Row;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

import static org.apache.flink.table.api.Expressions.array;
import static org.apache.flink.table.api.Expressions.lit;
import static org.apache.flink.table.api.Expressions.map;
import static org.apache.flink.table.api.Expressions.nullOf;
import static org.apache.flink.table.api.Expressions.row;

/** A table program example to create mock data. */
public class Example_06_ValuesAndDataTypes {

    public static void main(String[] args) {
        EnvironmentSettings settings =
                ConfluentSettings.fromPropertiesResource("/cloud.properties");
        TableEnvironment env = TableEnvironment.create(settings);

        // Values for each data type can be created...

        // (1) with Java objects
        Row row = new Row(17);
        // BOOLEAN
        row.setField(0, true);
        // STRING / CHAR / VARCHAR
        row.setField(1, "Alice");
        // DATE
        row.setField(2, LocalDate.of(2024, 12, 23));
        // TIME
        row.setField(3, LocalTime.of(13, 45, 59));
        // TIMESTAMP
        row.setField(4, LocalDateTime.of(2024, 12, 23, 13, 45, 59));
        // TIMESTAMP_LTZ
        row.setField(5, Instant.ofEpochMilli(1734957959000L));
        // BIGINT
        row.setField(6, 42L);
        // INT
        row.setField(7, 42);
        // SMALLINT
        row.setField(8, (short) 42);
        // TINYINT
        row.setField(9, (byte) 42);
        // DOUBLE
        row.setField(10, 42.0);
        // FLOAT
        row.setField(11, 42.0f);
        // DECIMAL
        row.setField(12, new BigDecimal("123.4567"));
        // BYTES / BINARY / VARBINARY
        row.setField(13, new byte[] {1, 2, 3});
        // ARRAY
        row.setField(14, new Integer[] {1, 2, 3});
        // MAP
        row.setField(15, Map.ofEntries(Map.entry("k1", "v1"), Map.entry("k2", "v2")));
        // ROW
        row.setField(16, Row.of("Bob", true));
        Table fromObjects = env.fromValues(row);

        // (2) with Table API expressions
        Expression rowExpr =
                row(
                        // VARCHAR(200)
                        lit("Alice").cast(DataTypes.VARCHAR(200)),
                        // ARRAY
                        array(1, 2, 3),
                        // MAP
                        map("k1", "v1", "k2", "v2"),
                        // ROW
                        row("Bob", true),
                        // NULL
                        nullOf(DataTypes.INT()));
        Table fromExpressions = env.fromValues(rowExpr);

        // (3) with SQL expressions
        Table fromSql =
                env.sqlQuery(
                        "VALUES ("
                                // VARCHAR(200)
                                + "CAST('Alice' AS VARCHAR(200)), "
                                // BYTES
                                + "x'010203', "
                                // ARRAY
                                + "ARRAY[1, 2, 3], "
                                // MAP
                                + "MAP['k1', 'v1', 'k2', 'v2', 'k3', 'v3'], "
                                // ROW
                                + "('Bob', true), "
                                // NULL
                                + "CAST(NULL AS INT), "
                                // DATE
                                + "DATE '2024-12-23', "
                                // TIME
                                + "TIME '13:45:59.000', "
                                // TIMESTAMP
                                + "TIMESTAMP '2024-12-23 13:45:59.000', "
                                // TIMESTAMP_LTZ
                                + "TO_TIMESTAMP_LTZ(1734957959000, 3)"
                                + ")");

        // Verify the derived data types and values

        System.out.println("Table from objects:");
        fromObjects.printSchema();
        fromObjects.execute().print();

        System.out.println("Table from Table API expressions:");
        fromExpressions.printSchema();
        fromExpressions.execute().print();

        System.out.println("Table from SQL expressions:");
        fromSql.printSchema();
        fromSql.execute().print();
    }
}
