package io.confluent.flink.examples.table;

import io.confluent.flink.plugin.ConfluentSettings;

import org.apache.flink.table.annotation.DataTypeHint;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.functions.ScalarFunction;
import org.apache.flink.table.types.DataType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Locale;

import static org.apache.flink.table.api.Expressions.$;
import static org.apache.flink.table.api.Expressions.call;
import static org.apache.flink.table.api.Expressions.objectOf;
import static org.apache.flink.table.api.Expressions.row;
import static org.apache.flink.table.api.Expressions.withAllColumns;

/**
 * A table program example illustrating how to use Structured Objects in the Flink Table API.
 *
 * <p>Flink Table API simplifies managing large data structures by natively supporting Java classes
 * and objects. Objects make it easier to organize information and pass information to and from
 * functions.
 */
public class Example_10_StructuredObjects {

    // All logic is defined in a main() method. It can run both in an IDE or CI/CD system.
    public static void main(String[] args) {
        EnvironmentSettings settings = ConfluentSettings.fromResource("/cloud.properties");
        TableEnvironment env = TableEnvironment.create(settings);

        // Flink SQL and the Table API can use Structured Data Types to represent complex objects.
        // While the engine uses internal data structures for performance,
        // it provides seamless mapping to and from standard Java POJOs.
        // Unlike the 'Row' type, which is purely "structural" and defined only by its fields,
        // Structured types are "nominal." This means they are uniquely identified by a
        // fully qualified class name.
        // This distinction ensures type safety: for instance, 'Visit(amount DOUBLE)' and
        // 'Interaction(amount DOUBLE)' are treated as incompatible types despite having
        // identical schemas, preventing accidental data mixing.

        // Given a table with 3 rows:
        Table valuesTable =
                env.fromValues(
                                row("Alice", 37, LocalDateTime.of(2003, 3, 3, 16, 23, 20)),
                                row("Bob", 44, LocalDateTime.of(2003, 3, 3, 16, 24, 11)),
                                row("Alice", -200, LocalDateTime.of(2003, 3, 3, 16, 24, 59)))
                        .as("name", "score", "updated");

        // Map columns to a Structured type using the objectOf() expression without a backing class.
        // This creates a "Nominal" type identified by the name "CustomScoringEvent".
        System.out.println("Handling structured types without Java class...");
        Table eventTable1 =
                valuesTable
                        .select(
                                objectOf(
                                        "CustomScoringEvent",
                                        "name",
                                        $("name"),
                                        "score",
                                        $("score"),
                                        "updated",
                                        $("updated")))
                        .as("obj");
        eventTable1.execute().print();

        // Alternatively, use the class name of a Java implementation class.
        // If a class is present in the classpath, it allows the result to be converted back into
        // specific POJOs when invoking UDFs or returning results to back to the IDE
        System.out.println("\nHandling structured types with Java class via objectOf()...");
        Table eventTable2 =
                valuesTable
                        .select(
                                objectOf(
                                        ScoringEvent.class,
                                        "name",
                                        $("name"),
                                        "score",
                                        $("score"),
                                        "updated",
                                        $("updated")))
                        .as("obj");

        eventTable2
                .execute()
                .collect()
                .forEachRemaining(
                        r -> {
                            // We can now extract the specific Java Object
                            ScoringEvent scoring = r.getFieldAs("obj");
                            System.out.println(
                                    scoring.name + ": " + scoring.score + " at " + scoring.updated);
                        });

        // Explicit casting is often cleaner. We convert the generic 'Row' of all columns
        // into the specific 'ScoringEvent' type.
        System.out.println("\nHandling structured types with Java class via cast()...");
        Table eventTable3 =
                valuesTable.select(row(withAllColumns()).cast(ScoringEvent.DATA_TYPE)).as("obj");
        eventTable3
                .execute()
                .collect()
                .forEachRemaining(r -> System.out.println(r.getField("obj")));

        // UDFs support Java classes as Structured Types natively.
        // The UDF receives a ScoringEvent and returns a new EnrichedScoringEvent.
        System.out.println("\nHandling structured types in UDFs...");
        eventTable3
                .select(call(ScoringEventEnricher.class, $("obj")).as("enrichedObj"))
                .execute()
                .collect()
                .forEachRemaining(
                        r -> {
                            EnrichedScoringEvent scoring = r.getFieldAs("enrichedObj");
                            System.out.println(
                                    scoring.origin
                                            + " -> "
                                            + (scoring.isValid ? "valid" : "invalid"));
                        });
    }

    /**
     * A Java POJO that uses standard Java data structures.
     *
     * <p>Table API automatically extracts a {@link DataType} in UDFs using Java reflection.
     *
     * <p>Important properties for Flink POJOs:
     *
     * <ul>
     *   <li>Class must be public.
     *   <li>No-arg or all-arg constructor is public.
     *   <li>Fields are public OR have public getters/setters.
     *   <li>All fields must map to {@link DataTypes}. Make sure to use boxed types for NULLs (e.g.
     *       {@link Integer} for INT), {@link LocalDateTime} for TIMESTAMP, {@link Instant} for
     *       TIMESTAMP_LTZ, {@link HashMap} for MAP, etc.
     * </ul>
     *
     * @see Example_06_ValuesAndDataTypes
     */
    public static class ScoringEvent {
        public String name;
        public Integer score;

        /** The {@link DataTypeHint} can help the automatic reflective extraction. */
        public @DataTypeHint("TIMESTAMP(3)") LocalDateTime updated;

        /**
         * Alternatively, we can explicitly define the data type here to ensure Flink maps fields
         * correctly, for example, to enforce specific precisions for TIMESTAMP(3).
         */
        public static final DataType DATA_TYPE =
                DataTypes.STRUCTURED(
                        ScoringEvent.class,
                        DataTypes.FIELD("name", DataTypes.STRING()),
                        DataTypes.FIELD("score", DataTypes.INT()),
                        DataTypes.FIELD("updated", DataTypes.TIMESTAMP(3)));

        @Override
        public String toString() {
            return "ScoringEvent{"
                    + "name='"
                    + name
                    + '\''
                    + ", score="
                    + score
                    + ", updated="
                    + updated
                    + '}';
        }
    }

    /** Structured type can be arbitrarily nested. */
    public static class EnrichedScoringEvent {
        public ScoringEvent origin;
        public Boolean isValid;
        public String normalizedName;

        // Make sure that a public no-arg constructor (implicitly) exists.
        public EnrichedScoringEvent() {}

        // Or an all-arg constructor defines field order.
        public EnrichedScoringEvent(ScoringEvent origin, Boolean isValid, String normalizedName) {
            this.origin = origin;
            this.isValid = isValid;
            this.normalizedName = normalizedName;
        }
    }

    /** A user-defined function that takes a Structured Object and returns a new nested one. */
    public static class ScoringEventEnricher extends ScalarFunction {

        public EnrichedScoringEvent eval(ScoringEvent event) {
            // Always handle null inputs in UDFs
            if (event == null) {
                return null;
            }

            EnrichedScoringEvent enriched = new EnrichedScoringEvent();
            enriched.origin = event;

            // Normalize data
            enriched.normalizedName =
                    (event.name != null) ? event.name.toUpperCase(Locale.ROOT) : "UNKNOWN";

            // Score must be positive to be valid
            enriched.isValid = event.score != null && event.score >= 0;

            return enriched;
        }
    }
}
