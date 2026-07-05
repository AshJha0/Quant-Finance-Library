package com.quantfinlib.backtest;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.microstructure.Execution;
import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Last-look execution: the LP rejects exactly when the intra-bar move runs
 * in the taker's favor beyond the threshold, fills otherwise at open plus
 * half-spread, and the asymmetry (LP-favorable moves always fill) holds.
 */
class LastLookExecutionTest {

    /** One bar with a controlled open→close move (bps). */
    private static BarSeries barWithMove(double moveBps) {
        BarSeries.Builder b = BarSeries.builder("EURUSD");
        double open = 1.0850;
        double close = open * (1 + moveBps / 1e4);
        b.add(0, open, Math.max(open, close) * 1.0001, Math.min(open, close) * 0.9999,
                close, 1_000_000);
        return b.build();
    }

    @Test
    void adverseMoveToTheLpIsRejected() {
        LastLookExecution ll = new LastLookExecution(0.5, 2.0);
        // Price rises 5 bps while the LP holds our BUY: reject and chase.
        List<Execution> fills = ll.execute(Side.BUY, 1_000_000, barWithMove(5), 0);
        assertTrue(fills.isEmpty());
        assertEquals(1, ll.rejectCount());
        assertEquals(0, ll.fillCount());
        assertEquals(1.0, ll.rejectRate());
        // A falling market rejects the SELL symmetrically.
        assertTrue(ll.execute(Side.SELL, 1_000_000, barWithMove(-5), 0).isEmpty());
        assertEquals(2, ll.rejectCount());
    }

    @Test
    void benignAndLpFavorableMovesFillAtOpenPlusSpread() {
        LastLookExecution ll = new LastLookExecution(0.5, 2.0);
        // Tiny move inside the threshold: fill at open + 0.5 bps for a buy.
        List<Execution> fills = ll.execute(Side.BUY, 500_000, barWithMove(1), 0);
        assertEquals(1, fills.size());
        assertEquals(500_000, fills.get(0).quantity());
        assertEquals(1.0850 * (1 + 0.5 / 1e4), fills.get(0).price(), 1e-12);
        assertEquals("LASTLOOK", fills.get(0).venue());
        // The asymmetry of last look: a move AGAINST the taker (favorable to
        // the LP) always fills, however large.
        assertEquals(1, ll.execute(Side.BUY, 500_000, barWithMove(-25), 0).size());
        assertEquals(0, ll.rejectCount());
        assertEquals(2, ll.fillCount());
        assertEquals(0.0, ll.rejectRate());
    }

    @Test
    void zeroQuantityAndValidation() {
        LastLookExecution ll = new LastLookExecution(0.5, 2.0);
        assertTrue(ll.execute(Side.BUY, 0, barWithMove(0), 0).isEmpty());
        assertEquals(0, ll.rejectRate()); // no attempts yet
        assertThrows(IllegalArgumentException.class, () -> new LastLookExecution(-1, 2));
        assertThrows(IllegalArgumentException.class, () -> new LastLookExecution(0.5, 0));
    }

    @Test
    void integratesWithTheExecutionAwareEngine() {
        // A rising series where every bar runs 8 bps: the LP rejects every
        // attempt and the parent order chases without ever filling — the
        // pathology last-look modeling exists to expose.
        BarSeries.Builder b = BarSeries.builder("EURUSD");
        double p = 1.0850;
        for (int i = 0; i < 30; i++) {
            double open = p;
            p = open * (1 + 8.0 / 1e4);
            b.add(i, open, p * 1.0001, open * 0.9999, p, 1_000_000);
        }
        BarSeries series = b.build();
        LastLookExecution ll = new LastLookExecution(0.5, 2.0);
        long unfilled = 1_000_000;
        for (int i = 5; i < 30 && unfilled > 0; i++) {
            List<Execution> fills = ll.execute(Side.BUY, unfilled, series, i);
            for (Execution f : fills) {
                unfilled -= f.quantity();
            }
        }
        assertEquals(1_000_000, unfilled);       // never filled
        assertEquals(25, ll.rejectCount());      // rejected every bar
        assertEquals(1.0, ll.rejectRate());
    }
}
