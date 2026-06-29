package io.confluent.flink.examples.table;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.types.RowKind;
import org.apache.flink.util.CloseableIterator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.apache.flink.table.api.Expressions.row;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pipeline logic of {@link Example_08_IntegrationAndDeployment}.
 *
 * <p>These tests run entirely locally on Apache Flink with mock data from {@code fromValues()}. No
 * Confluent Cloud connectivity, credentials, or compute pool are required, which makes them
 * suitable for fast feedback during development and for CI runs on pull requests.
 *
 * <p>NOTE: The Confluent plugin and the Apache Flink planner cannot share a runtime classpath (both
 * register Executor and Planner factories under the identifier 'default'), so these tests must be
 * executed via {@code ./mvnw test}, where the surefire configuration excludes the plugin. Running
 * them directly from the IDE fails with "Multiple factories for identifier 'default'"; see the
 * README's testing section for the IDE run-configuration setup.
 *
 * <p>ALSO NOTE: Running locally on Apache Flink is not identical to Confluent Cloud.
 * Confluent-specific features such as the {@code $rowtime} system column, the Confluent catalog,
 * and Confluent SQL extensions are not available locally. Use the integration tests (see {@code
 * Example_08_IntegrationAndDeploymentIT}) to verify behavior against the real service.
 */
class Example_08_IntegrationAndDeploymentTest {

    private static Table mockProducts(TableEnvironment env) {
        return env.fromValues(
                DataTypes.ROW(
                        DataTypes.FIELD("name", DataTypes.STRING()),
                        DataTypes.FIELD("brand", DataTypes.STRING())),
                row("MacBook", "Apple"),
                row("iPhone", "Apple"),
                row("Galaxy", "Samsung"));
    }

    @Test
    void countsVendorsPerBrandInBatchMode() throws Exception {
        // Batch mode computes the final result over the finite mock data, which makes
        // assertions straightforward.
        TableEnvironment env = TableEnvironment.create(EnvironmentSettings.inBatchMode());

        Table result =
                Example_08_IntegrationAndDeployment.VendorsPerBrand.buildPipeline(
                        mockProducts(env));

        assertThat(collectRows(result))
                .containsExactlyInAnyOrder(Row.of("Apple", 2L), Row.of("Samsung", 1L));
    }

    @Test
    void countsVendorsPerBrandInStreamingMode() throws Exception {
        // Streaming mode emits a changelog: an insert for the first product of a brand,
        // followed by update_before/update_after pairs as more products arrive. This mirrors
        // how the statement behaves on Confluent Cloud.
        TableEnvironment env = TableEnvironment.create(EnvironmentSettings.inStreamingMode());

        Table result =
                Example_08_IntegrationAndDeployment.VendorsPerBrand.buildPipeline(
                        mockProducts(env));

        List<Row> changelog = collectRows(result);
        assertThat(materialize(changelog))
                .containsExactlyInAnyOrder(Row.of("Apple", 2L), Row.of("Samsung", 1L));
    }

    private static List<Row> collectRows(Table table) throws Exception {
        List<Row> rows = new ArrayList<>();
        try (CloseableIterator<Row> iterator = table.execute().collect()) {
            iterator.forEachRemaining(rows::add);
        }
        return rows;
    }

    // Applies the changelog to derive the final result, similar to what
    // ConfluentTools.collectMaterialized() does for statements running on Confluent Cloud.
    // Rows are copied so that the caller's changelog is left untouched.
    private static List<Row> materialize(List<Row> changelog) {
        List<Row> state = new ArrayList<>();
        for (Row row : changelog) {
            Row copy = Row.copy(row);
            copy.setKind(RowKind.INSERT);
            switch (row.getKind()) {
                case INSERT:
                case UPDATE_AFTER:
                    state.add(copy);
                    break;
                case UPDATE_BEFORE:
                case DELETE:
                    state.remove(copy);
                    break;
            }
        }
        return state;
    }
}
