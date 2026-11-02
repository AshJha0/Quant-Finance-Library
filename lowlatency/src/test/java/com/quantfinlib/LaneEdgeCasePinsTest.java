package com.quantfinlib;

import com.quantfinlib.pricing.TouchOption;
import com.quantfinlib.rates.NelsonSiegel;
import com.quantfinlib.risk.VarEngine;
import com.quantfinlib.trading.LastLookGate;
import com.quantfinlib.trading.OrderThrottle;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edge-case pins for trading-lane types, split out of the main module's
 * EdgeCasePinsTest in 3.0 when {@code trading} moved to the low-latency module.
 */
class LaneEdgeCasePinsTest {

    @Test
    void lastLookAcceptsAMoveExactlyAtTolerance() {
        // The FX Global Code gate is symmetric and inclusive: a move of
        // exactly the tolerance is accepted in both directions; one tick
        // beyond is rejected in both.
        LastLookGate gate = new LastLookGate(0.0005);
        assertTrue(gate.accept(true, 1.0000, 1.0005));
        assertTrue(gate.accept(false, 1.0000, 1.0005));
        assertTrue(gate.accept(true, 1.0005, 1.0000));
        assertFalse(gate.accept(true, 1.0000, 1.00051));
        assertFalse(gate.accept(false, 1.00051, 1.0000));
    }

    @Test
    void backwardClockNeverInflatesThrottleTokens() {
        // 1 token/sec, burst 1. Spend the token, then feed a BACKWARD
        // timestamp: the bucket must not refill (and must not go negative
        // either) — replayed or skewed clocks are a real production input.
        OrderThrottle throttle = new OrderThrottle(1.0, 1);
        assertTrue(throttle.tryAcquire(1_000_000_000L));
        assertFalse(throttle.tryAcquire(1_000_000_000L), "burst spent");
        assertFalse(throttle.tryAcquire(0L), "time going backward mints nothing");
        assertFalse(throttle.tryAcquire(1_500_000_000L), "only half a token accrued");
        assertTrue(throttle.tryAcquire(2_000_000_100L), "a full second refills one");
    }
}
