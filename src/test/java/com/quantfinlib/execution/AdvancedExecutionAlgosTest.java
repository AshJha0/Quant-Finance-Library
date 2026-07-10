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

    @Test
    void fractionalRatiosOverfillsAndImpossibleCapsAreHandled() {
        // Ratio 1.5, lead 3: the hedge target is round(4.5) = 5 —
        // half-up over-hedging by design, certified here.
        SpreadExecutionAlgo frac = new SpreadExecutionAlgo(3, 1.5, 4, 3);
        while (!frac.done()) {
            SpreadExecutionAlgo.Children c = frac.decide();
            frac.onLeadFill(c.leadQty());
            frac.onHedgeFill(c.hedgeQty());
        }
        assertEquals(3, frac.leadExecuted());
        assertEquals(5, frac.hedgeExecuted(), "round(3 x 1.5) = 5, half-up");

        // A legging cap smaller than one lead unit's hedge would make
        // execution IMPOSSIBLE (decide() could never emit a lead child):
        // rejected at construction, not discovered as a silent livelock.
        assertThrows(IllegalArgumentException.class,
                () -> new SpreadExecutionAlgo(100, 2.0, 1, 10),
                "a cap that cannot cover one lead unit is not a spread");

        // Hedge overfill is the same upstream bug the lead guard catches:
        // a duplicate fill report must throw, not silently absorb.
        SpreadExecutionAlgo over = new SpreadExecutionAlgo(10, 1.0, 5, 10);
        over.onLeadFill(10);
        assertThrows(IllegalArgumentException.class, () -> over.onHedgeFill(11),
                "beyond the spread's hedge target");
        over.onHedgeFill(10);
        assertTrue(over.done());
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

        // The post REGION, verified from both sides. Normal regime
        // (2h+r+d > a): posting pays ABOVE the threshold.
        OrderPlacementPolicy.PostRegion normal =
                OrderPlacementPolicy.postRegion(0.05, 0.02, 0.01, 0.002);
        assertEquals(0.01 / 0.092, normal.from(), 1e-12, "d / (2h + r + d - a)");
        assertEquals(1, normal.to(), 1e-12);
        assertTrue(OrderPlacementPolicy.decide(0.05, normal.from() + 0.01,
                0.02, 0.01, 0.002).post());
        assertFalse(OrderPlacementPolicy.decide(0.05, normal.from() - 0.01,
                0.02, 0.01, 0.002).post());

        // TOXIC regime (a > 2h+r+d): the slope flips and posting never
        // pays — the region is EMPTY, where a scalar threshold would
        // have reported a meaningless negative number.
        assertTrue(OrderPlacementPolicy.postRegion(0.05, 0.15, 0.01, 0.002).isEmpty(),
                "adverse selection swamps everything: never post");

        // FLIPPED regime with favorable drift: posting pays only BELOW
        // the threshold (waiting is good, but a fill means being run over).
        OrderPlacementPolicy.PostRegion flipped =
                OrderPlacementPolicy.postRegion(0.05, 0.3, -0.05, 0);
        assertEquals(0, flipped.from(), 1e-12);
        assertEquals(0.2, flipped.to(), 1e-12, "d/coef = -0.05/-0.25");
        assertTrue(OrderPlacementPolicy.decide(0.05, 0.1, 0.3, -0.05, 0).post(),
                "below the flipped threshold: post");
        assertFalse(OrderPlacementPolicy.decide(0.05, 0.3, 0.3, -0.05, 0).post(),
                "above it: a fill here means being run over — cross");

        // Flat-in-p regime (coef == 0): the drift sign decides for all p.
        assertFalse(OrderPlacementPolicy.postRegion(1, 1, -1, 0).isEmpty(),
                "postCost = h+d-0 = 0 < 1 for every p");
        assertTrue(OrderPlacementPolicy.postRegion(1, 2.1, 0.1, 0).isEmpty());

        // Exact probability boundaries.
        assertFalse(OrderPlacementPolicy.decide(0.05, 0, 0.02, 0, 0).post(),
                "p = 0, d = 0: a tie, resolved to CROSS");
        assertEquals(0.02 - 0.05 - 0.002,
                OrderPlacementPolicy.decide(0.05, 1, 0.02, 0.01, 0.002)
                        .expectedPostCost(), 1e-12, "p = 1: cost is a - h - r exactly");

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

        // Zero fractions = IDENTITY, exactly — "jitter off" must mean
        // the untouched schedule, an auditable property.
        AntiGamingJitter off = new AntiGamingJitter(99, 0, 0);
        assertArrayEquals(clockwork, off.jitterSizes(clockwork));
        assertArrayEquals(times, off.jitterTimes(times, 0));

        // Degenerate schedules survive: one child, no pairs to transfer.
        assertArrayEquals(new long[]{5_000},
                jitter.jitterSizes(new long[]{5_000}));
        assertArrayEquals(new long[]{100},
                new AntiGamingJitter(3, 0.3, 0.4).jitterTimes(new long[]{100}, 0));
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

        // Degenerate windows: a single-day roll and a one-lot position.
        FuturesRollAlgo oneDay = new FuturesRollAlgo(500,
                FuturesRollAlgo.defaultMigration(1));
        assertEquals(500, oneDay.dueOnDay(0), "everything, today");
        oneDay.onRolled(500);
        assertTrue(oneDay.done());
        FuturesRollAlgo oneLot = new FuturesRollAlgo(1, FuturesRollAlgo.defaultMigration(5));
        long total = 0;
        for (int d = 0; d < 5; d++) {
            long due = oneLot.dueOnDay(d);
            oneLot.onRolled(due);
            total += due;
        }
        assertEquals(1, total, "rounding never loses or duplicates the lot");
        assertTrue(oneLot.done());
        // Zero early entries are valid: nothing due yet, contract [0, 1].
        FuturesRollAlgo lateStart = new FuturesRollAlgo(1_000, new double[]{0, 0, 1});
        assertEquals(0, lateStart.dueOnDay(0));
        assertEquals(1_000, lateStart.dueOnDay(2));
    }
}
