package io.confluent.flink.examples.functions;

import org.apache.flink.table.annotation.ArgumentHint;
import org.apache.flink.table.annotation.StateHint;
import org.apache.flink.table.functions.ProcessTableFunction;
import org.apache.flink.types.Row;

import java.time.Duration;
import java.time.Instant;

import static org.apache.flink.table.annotation.ArgumentTrait.REQUIRE_ON_TIME;
import static org.apache.flink.table.annotation.ArgumentTrait.SET_SEMANTIC_TABLE;

/**
 * A reusable {@link ProcessTableFunction} (PTF) that detects user inactivity based on click events.
 *
 * <p>For each user (partitioned by {@code user_id}), it counts incoming clicks and registers a
 * named event-time timer. Each new click replaces the previous timer, resetting the inactivity
 * clock. When the timer fires (no new clicks within the timeout), an alert is emitted and the
 * partition's state is cleared so a returning user starts a fresh inactivity window.
 *
 * <p>Unlike a windowed aggregation, the PTF emits exactly once per inactivity period, combining
 * running state with the absence of events.
 *
 * <p>Defined once here and shared by two examples: {@code Example_10_ProcessTableFunction} invokes
 * it inline for a quick, one-off look at its output, while {@code
 * ReferenceApp_02_ProcessTableFunction} wires it into a deployable, long-running statement.
 */
public class ClickInactivityMonitor
        extends ProcessTableFunction<ClickInactivityMonitor.InactivityAlert> {

    /** Output POJO (the partition key and rowtime are added by the framework). */
    public static class InactivityAlert {
        public int clickCount;
    }

    /** Per-user state. */
    public static class ClickState {
        public int clickCount = 0;
    }

    // The eval() method defines the function's signature and is called for every input row.
    public void eval(
            Context ctx,
            @StateHint ClickState state,
            @ArgumentHint({SET_SEMANTIC_TABLE, REQUIRE_ON_TIME}) Row input,
            int timeoutSeconds) {

        state.clickCount++;

        // Each new click pushes the timeout forward; the timer fires only after true inactivity.
        TimeContext<Instant> timeCtx = ctx.timeContext(Instant.class);
        timeCtx.registerOnTime(
                "inactivity", timeCtx.time().plus(Duration.ofSeconds(timeoutSeconds)));
    }

    // The onTimer() method is called when a timer fires.
    public void onTimer(OnTimerContext ctx, ClickState state) {
        InactivityAlert alert = new InactivityAlert();
        alert.clickCount = state.clickCount;
        collect(alert);

        // Reset the partition so a returning user starts a new inactivity window instead of
        // retaining the click counter indefinitely. The fired timer is already consumed, so only
        // state needs clearing.
        ctx.clearAllState();
    }
}
