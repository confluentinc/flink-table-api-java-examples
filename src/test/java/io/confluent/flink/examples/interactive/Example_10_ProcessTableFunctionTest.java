package io.confluent.flink.examples.interactive;

import io.confluent.flink.examples.functions.ClickInactivityMonitor;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.runtime.functions.ProcessTableFunctionTestHarness;
import org.apache.flink.types.Row;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ClickInactivityMonitor} using the {@link ProcessTableFunctionTestHarness}.
 *
 * <p>Like {@code Example_08_FunctionsTest}, this runs entirely locally: the harness invokes the
 * PTF's {@code eval()} and {@code onTimer()} methods directly against mock rows and a simulated
 * watermark clock, so no Apache Flink planner, mini-cluster, or Confluent Cloud connection is
 * required.
 */
class Example_10_ProcessTableFunctionTest {

    private static final String INPUT = "input";

    private static ProcessTableFunctionTestHarness<ClickInactivityMonitor.InactivityAlert>
            newHarness() throws Exception {
        return ProcessTableFunctionTestHarness.ofClass(ClickInactivityMonitor.class)
                .withTableArgument(
                        INPUT, DataTypes.of("ROW<user_id STRING, click_time TIMESTAMP(3)>"))
                .withPartitionBy(INPUT, "user_id")
                .withOnTimeColumn("click_time")
                .withScalarArgument("timeoutSeconds", 30)
                .build();
    }

    @Test
    void registersAnInactivityTimerAfterTheFirstClick() throws Exception {
        try (ProcessTableFunctionTestHarness<ClickInactivityMonitor.InactivityAlert> harness =
                newHarness()) {

            harness.processElement(Row.of("alice", LocalDateTime.of(2025, 1, 1, 0, 0, 0)));

            ClickInactivityMonitor.ClickState state =
                    harness.getStateForKey("state", Row.of("alice"));
            assertThat(state.clickCount).isEqualTo(1);

            assertThat(harness.getPendingTimers()).hasSize(1);
            assertThat(harness.getPendingTimers().get(0).getName()).isEqualTo("inactivity");
            assertThat(harness.getPendingTimers().get(0).getTimestampAs(LocalDateTime.class))
                    .isEqualTo(LocalDateTime.of(2025, 1, 1, 0, 0, 30));
        }
    }

    @Test
    void eachNewClickPushesTheInactivityTimerForward() throws Exception {
        try (ProcessTableFunctionTestHarness<ClickInactivityMonitor.InactivityAlert> harness =
                newHarness()) {

            // First click at T+0s registers a timer for T+30s.
            harness.processElement(Row.of("alice", LocalDateTime.of(2025, 1, 1, 0, 0, 0)));
            // A second click before the timer fires replaces it, pushing inactivity out to T+40s.
            harness.processElement(Row.of("alice", LocalDateTime.of(2025, 1, 1, 0, 0, 10)));

            ClickInactivityMonitor.ClickState state =
                    harness.getStateForKey("state", Row.of("alice"));
            assertThat(state.clickCount).isEqualTo(2);

            // Registering a timer under the same name replaces the earlier one instead of
            // stacking, so exactly one timer remains, now scheduled from the latest click.
            assertThat(harness.getPendingTimers()).hasSize(1);
            assertThat(harness.getPendingTimers().get(0).getTimestampAs(LocalDateTime.class))
                    .isEqualTo(LocalDateTime.of(2025, 1, 1, 0, 0, 40));
        }
    }

    @Test
    void tracksInactivityIndependentlyPerUser() throws Exception {
        try (ProcessTableFunctionTestHarness<ClickInactivityMonitor.InactivityAlert> harness =
                newHarness()) {

            harness.processElement(Row.of("alice", LocalDateTime.of(2025, 1, 1, 0, 0, 0)));
            harness.processElement(Row.of("bob", LocalDateTime.of(2025, 1, 1, 0, 0, 5)));
            harness.processElement(Row.of("alice", LocalDateTime.of(2025, 1, 1, 0, 0, 10)));

            ClickInactivityMonitor.ClickState aliceState =
                    harness.getStateForKey("state", Row.of("alice"));
            ClickInactivityMonitor.ClickState bobState =
                    harness.getStateForKey("state", Row.of("bob"));
            assertThat(aliceState.clickCount).isEqualTo(2);
            assertThat(bobState.clickCount).isEqualTo(1);

            assertThat(harness.getPendingTimers(Row.of("alice"))).hasSize(1);
            assertThat(harness.getPendingTimers(Row.of("bob"))).hasSize(1);
        }
    }

    /**
     * When the inactivity timer fires, {@code onTimer()} emits an {@link
     * ClickInactivityMonitor.InactivityAlert} carrying the user's accumulated click count and then
     * clears the partition's state. Advancing the watermark past the registered timer triggers
     * this. {@link ProcessTableFunctionTestHarness#getFunctionOutput()} exposes the raw POJOs the
     * PTF emitted, so the alert can be asserted directly.
     */
    @Test
    void firingTheTimerEmitsAnAlertAndClearsState() throws Exception {
        try (ProcessTableFunctionTestHarness<ClickInactivityMonitor.InactivityAlert> harness =
                newHarness()) {

            // Two clicks for alice, whose second click registers the inactivity timer at T+40s.
            harness.processElement(Row.of("alice", LocalDateTime.of(2025, 1, 1, 0, 0, 0)));
            harness.processElement(Row.of("alice", LocalDateTime.of(2025, 1, 1, 0, 0, 10)));

            // Advancing the watermark past T+40s fires the timer, invoking onTimer().
            harness.setWatermark(Instant.parse("2025-01-01T00:00:41Z"));

            // onTimer() emitted exactly one alert with alice's accumulated click count.
            assertThat(harness.getFunctionOutput())
                    .singleElement()
                    .satisfies(alert -> assertThat(alert.clickCount).isEqualTo(2));

            // The timer fired once and none remain pending.
            assertThat(harness.getFiredTimers()).hasSize(1);
            assertThat(harness.getPendingTimers()).isEmpty();

            // onTimer() cleared the partition, so a returning click starts a fresh inactivity
            // window: the count restarts at 1 rather than resuming from the pre-alert total.
            harness.processElement(Row.of("alice", LocalDateTime.of(2025, 1, 1, 0, 1, 0)));
            ClickInactivityMonitor.ClickState reopened =
                    harness.getStateForKey("state", Row.of("alice"));
            assertThat(reopened.clickCount).isEqualTo(1);
        }
    }
}
