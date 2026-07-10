package com.quantfinlib.execution;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spread execution, post-or-cross policy, anti-gaming jitter, futures roll. */
class AdvancedExecutionAlgosTest {

    // ------------------------------------------------------------------
    // Spread execution — legging risk is THE risk
    // ------------------------------------------------------------------

    @Test
    void hedgeLegChasesAndTheLeggingCapIsHard() {
        // 10k lead units, 2 hedge units per lead, cap 3k hedge units,
        // patient 1k lead children.
        SpreadExecutionAlgo spread = new SpreadExecutionAlgo(10_000, 2.0, 3_000, 1_000);

        SpreadExecutionAlgo.Children c = spread.decide();
        assertEquals(1_000, c.leadQty(), "fresh: work the lead patiently");
        assertEquals(0, c.hedgeQty(), "nothing to chase yet");
        assertFalse(c.atRiskCap());

        // Lead fills, hedge does not: the imbalance is 2,000 hedge units.
        spread.onLeadFill(1_000);
        c = spread.decide();
        assertEquals(2_000, c.hedgeQty(), "the hedge chases the imbalance");
        assertEquals(500, c.leadQty(),
                "lead throttled: even a full fill stays under the cap ((3000-2000)/2)");

        // The hedge keeps not filling; lead fills again: cap reached.
        spread.onLeadFill(500);
        c = spread.decide();
        assertTrue(c.atRiskCap(), "3,000 hedge units of naked leg = the cap");
        assertEquals(0, c.leadQty(), "STOP adding lead risk at the cap");
        assertEquals(3_000, c.hedgeQty(), "cross the whole imbalance, get flat");

        // Hedge catches up: lead resumes.
        spread.onHedgeFill(3_000);
        assertEquals(0, spread.imbalanceHedgeUnits());
        c = spread.decide();
        assertEquals(1_000, c.leadQty(), "flat again: full patience restored");
        assertFalse(spread.done());

        // Run to completion: done only when the hedge has fully caught up.
        spread.onLeadFill(8_500);
        assertFalse(spread.done(), "lead complete but hedge behind is NOT done");
        spread.onHedgeFill(spread.imbalanceHedgeUnits());
        assertTrue(spread.done());
        assertEquals(20_000, spread.hedgeExecuted(), "2 hedge units per lead unit, exactly");

        assertThrows(IllegalArgumentException.class, () -> spread.onLeadFill(1),
                "overfilling the parent is a bug upstream, not a rounding matter");
        assertThrows(IllegalArgumentException.class,
                () -> new SpreadExecutionAlgo(0, 2, 3_000, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new SpreadExecutionAlgo(100, Double.NaN, 3_000, 100));
    }

    // ------------------------------------------------------------------
    // Order placement — the arithmetic of post vs cross
    // ------------------------------------------------------------------

    @Test
    void postOrCrossFollowsExpectedCostExactly() {
        // Friendly conditions: 5c half spread, 60% fill odds, mild 2c
        // adverse selection, 1c drift, 0.2c rebate.
        OrderPlacementPolicy.Placement friendly =
                OrderPlacementPolicy.decide(0.05, 0.6, 0.02, 0.01, 0.002);
        assertEquals(0.0048, friendly.expectedPostCost(), 1e-12,
                "0.6*(0.02-0.05-0.002) + 0.4*(0.05+0.01), by hand");
        assertEquals(0.05, friendly.crossCost(), 1e-12);
        assertTrue(friendly.post(), "posting is 10x cheaper here");

        // Toxic tape: 15c adverse selection swamps everything — a passive
        // fill here means the market just ran you over.
        OrderPlacementPolicy.Placement toxic =
                OrderPlacementPolicy.decide(0.05, 0.6, 0.15, 0.01, 0.002);
        assertEquals(0.0828, toxic.expectedPostCost(), 1e-12);
        assertFalse(toxic.post(), "cross and be done");

        // The breakeven threshold, verified from both sides.
        double pStar = OrderPlacementPolicy.breakevenFillProbability(0.05, 0.02, 0.01, 0.002);
        assertEquals(0.01 / 0.092, pStar, 1e-12, "d / (2h + r + d - a)");
        assertTrue(OrderPlacementPolicy.decide(0.05, pStar + 0.01, 0.02, 0.01, 0.002).post());
        assertFalse(OrderPlacementPolicy.decide(0.05, pStar - 0.01, 0.02, 0.01, 0.002).post());

        // Favorable drift (the market is expected to come to you) makes
        // waiting cheaper, exactly as it should.
        assertTrue(OrderPlacementPolicy.decide(0.05, 0.1, 0.02, -0.03, 0).post(),
                "why cross when the market is coming to you?");

        assertThrows(IllegalArgumentException.class,
                () -> OrderPlacementPolicy.decide(0, 0.5, 0.02, 0.01, 0));
        assertThrows(IllegalArgumentException.class,
                () -> OrderPlacementPolicy.decide(0.05, 1.5, 0.02, 0.01, 0));
        assertThrows(IllegalArgumentException.class,
                () -> OrderPlacementPolicy.decide(0.05, 0.5, 0.02, Double.NaN, 0));
    }

    // ------------------------------------------------------------------
    // Anti-gaming jitter — kill the pattern, keep the schedule
    // ------------------------------------------------------------------

    @Test
    void jitterKillsThePatternButNeverTheTotal() {
        long[] clockwork = new long[12];
        Arrays.fill(clockwork, 1_000);

        AntiGamingJitter jitter = new AntiGamingJitter(42, 0.3, 0.4);
        long[] jittered = jitter.jitterSizes(clockwork);

        long total = 0;
        boolean anyDifferent = false;
        for (int i = 0; i < jittered.length; i++) {
            total += jittered[i];
            assertTrue(jittered[i] >= 0, "no negative children, ever");
            anyDifferent |= jittered[i] != 1_000;
        }
        assertEquals(12_000, total, "anti-gaming NEVER changes what gets done");
        assertTrue(anyDifferent, "but the metronome is gone");

        // Deterministic per seed: replayable in backtests, auditable live.
        assertArrayEquals(jittered, new AntiGamingJitter(42, 0.3, 0.4)
                .jitterSizes(clockwork), "same seed, same schedule");
        assertFalse(Arrays.equals(jittered, new AntiGamingJitter(43, 0.3, 0.4)
                .jitterSizes(clockwork)), "different seed, different pattern");

        // Times: monotone, inside the window, never past the original end.
        long[] times = new long[10];
        for (int i = 0; i < 10; i++) {
            times[i] = (i + 1) * 60_000_000_000L;      // every minute
        }
        long[] jt = new AntiGamingJitter(7, 0, 0.4).jitterTimes(times, 0);
        for (int i = 0; i < jt.length; i++) {
            if (i > 0) {
                assertTrue(jt[i] > jt[i - 1], "children never reorder");
            }
        }
        assertTrue(jt[0] >= 0);
        assertTrue(jt[9] <= times[9], "the schedule never runs long");
        boolean timesMoved = !Arrays.equals(jt, times);
        assertTrue(timesMoved, "the clock is no longer a metronome");

        assertThrows(IllegalArgumentException.class,
                () -> new AntiGamingJitter(1, 0.6, 0.1));
        assertThrows(IllegalArgumentException.class,
                () -> jitter.jitterTimes(new long[]{5, 5}, 0));
    }

    // ------------------------------------------------------------------
    // Futures roll — follow the migration, always complete
    // ------------------------------------------------------------------

    @Test
    void rollFollowsTheMigrationCurveAndAlwaysCompletes() {
        double[] curve = FuturesRollAlgo.defaultMigration(5);
        assertEquals(1.0, curve[4], 0.0, "the roll ENDS complete, exactly");
        for (int d = 1; d < 5; d++) {
            assertTrue(curve[d] > curve[d - 1], "migration only goes forward");
        }
        // The S-shape: the middle day carries the most.
        assertTrue(curve[2] - curve[1] > curve[0], "concentrated middle");

        FuturesRollAlgo roll = new FuturesRollAlgo(10_000, curve);
        long day0 = roll.dueOnDay(0);
        assertEquals(Math.round(10_000 * curve[0]), day0);
        roll.onRolled(day0);

        // The desk gets pulled away on day 1: day 2 CATCHES UP to the curve.
        assertEquals(Math.round(10_000 * curve[2]) - day0, roll.dueOnDay(2),
                "falling behind just makes later days bigger");
        roll.onRolled(roll.dueOnDay(2));
        roll.onRolled(roll.dueOnDay(3));
        roll.onRolled(roll.dueOnDay(4));
        assertTrue(roll.done(), "the position is fully rolled, no delivery notice");
        assertEquals(10_000, roll.rolled());

        // Each day's due executes as a calendar spread: ratio 1, tight cap.
        SpreadExecutionAlgo calendar = new SpreadExecutionAlgo(day0, 1.0, 200, 300);
        while (!calendar.done()) {
            SpreadExecutionAlgo.Children c = calendar.decide();
            calendar.onLeadFill(c.leadQty());              // sell front
            calendar.onHedgeFill(c.hedgeQty());            // buy back
        }
        assertEquals(calendar.leadExecuted(), calendar.hedgeExecuted(),
                "a completed calendar spread is flat by definition");

        assertThrows(IllegalArgumentException.class,
                () -> new FuturesRollAlgo(1_000, new double[]{0.5, 0.9}),
                "a curve that does not end at 1 is a delivery risk");
        assertThrows(IllegalArgumentException.class,
                () -> new FuturesRollAlgo(1_000, new double[]{0.5, 0.4, 1.0}));
        assertThrows(IllegalArgumentException.class, () -> roll.onRolled(1));
    }
}
