package io.confluent.flink.examples.table;

import org.apache.flink.api.common.functions.util.ListCollector;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the User-Defined Functions of {@link Example_09_Functions}.
 *
 * <p>UDFs are plain Java classes, so their logic can be tested with JUnit alone: no Apache Flink,
 * no Confluent Cloud connectivity, and no artifact upload are required. This is the fastest test
 * tier and should cover the bulk of a UDF's business logic before it is registered and exercised on
 * Confluent Cloud.
 */
class Example_09_FunctionsTest {

    @Test
    void customTaxReturnsRatePerLocation() {
        Example_09_Functions.CustomTax tax = new Example_09_Functions.CustomTax();

        assertThat(tax.eval("USA")).isEqualTo(10);
        assertThat(tax.eval("EU")).isEqualTo(5);
        assertThat(tax.eval("Mars")).isEqualTo(0);
    }

    @Test
    void explodeEmitsOneRowPerElement() {
        Example_09_Functions.Explode explode = new Example_09_Functions.Explode();

        // Table functions emit rows via a collector, which tests can replace with a list
        List<String> collected = new ArrayList<>();
        explode.setCollector(new ListCollector<>(collected));

        explode.eval(List.of("Apples", "Bananas"));

        assertThat(collected).containsExactly("Apples", "Bananas");
    }
}
