package com.quantfinlib.data;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.data.CorporateEventPositions.Holding;
import com.quantfinlib.data.CorporateEventPositions.Outcome;
import com.quantfinlib.data.CorporateEvents.CashStockMerger;
import com.quantfinlib.data.CorporateEvents.Dividend;
import com.quantfinlib.data.CorporateEvents.DividendKind;
import com.quantfinlib.data.CorporateEvents.DividendPolicy;
import com.quantfinlib.data.CorporateEvents.Event;
import com.quantfinlib.data.CorporateEvents.ReverseSplit;
import com.quantfinlib.data.CorporateEvents.RightsIssue;
import com.quantfinlib.data.CorporateEvents.SpinOff;
import com.quantfinlib.data.CorporateEvents.StockDividend;
import com.quantfinlib.data.CorporateEvents.SymbolChange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Worked numeric examples; every expected value is hand computed in the comment beside it. */
class CorporateEventsTest {

    private static final double EPS = 1e-12;

    /** Closes 100 for ts 0..4, then {@code exClose} for ts 5..9; volume 1000. */
    private static BarSeries tape(double exClose) {
        BarSeries.Builder b = BarSeries.builder("EQ");
        for (int i = 0; i < 10; i++) {
            double c = i < 5 ? 100 : exClose;
            b.add(i, c, c, c, c, 1_000);
        }
        return b.build();
    }

    private static void assertNoJump(BarSeries adj) {
        assertEquals(adj.close(5), adj.close(4), 1e-9, "no return across a value-neutral ex-date");
    }

    @Test
    void stockDividendFivePercent() {
        // 5 new per 100: f = 1/1.05; ex price 100/1.05 = 95.238...
        StockDividend e = new StockDividend(5, 0.05);
        BarSeries adj = CorporateEvents.adjust(tape(100 / 1.05), List.of(e));
        assertEquals(95.23809523809524, adj.close(0), 1e-9);
        assertEquals(1_050, adj.volume(0), 1e-9);
        assertNoJump(adj);
        Outcome o = CorporateEventPositions.apply(new Holding("EQ", "X1", 200, 10_000), e, 95.24);
        assertEquals(210, o.holdings().get(0).shares(), EPS);       // 200 * 1.05
        assertEquals(10_000, o.holdings().get(0).costBasis(), EPS);  // basis unchanged
    }

    @Test
    void reverseSplitOneForTenWithCashInLieu() {
        // 10 old -> 1 new, cum 100 -> ex 1000: f = 10, volume / 10
        ReverseSplit e = new ReverseSplit(5, 10);
        BarSeries adj = CorporateEvents.adjust(tape(1_000), List.of(e));
        assertEquals(1_000, adj.close(0), EPS);
        assertEquals(100, adj.volume(0), EPS);
        assertNoJump(adj);
        // 1,005 old shares -> 100.5 new: 100 kept, 0.5 * 1000 = 500 cash in lieu
        // basis 20,100 * 100 / 100.5 = 20,000
        Outcome o = CorporateEventPositions.apply(new Holding("EQ", "X1", 1_005, 20_100), e, 1_000);
        assertEquals(100, o.holdings().get(0).shares(), EPS);
        assertEquals(500, o.cash(), 1e-9);
        assertEquals(20_000, o.holdings().get(0).costBasis(), 1e-9);
    }

    @Test
    void specialVersusRegularDividendPolicy() {
        // regular 2 then special 10 on a 100 tape; ex closes 98 then 88
        BarSeries.Builder b = BarSeries.builder("EQ");
        double[] c = {100, 100, 98, 98, 88, 88};
        for (int i = 0; i < c.length; i++) {
            b.add(i, c[i], c[i], c[i], c[i], 1);
        }
        BarSeries raw = b.build();
        List<Event> evs = List.of(new Dividend(2, 2, DividendKind.REGULAR),
                new Dividend(4, 10, DividendKind.SPECIAL));
        // ALL: bar 0 factor = (98/100) * (88/98) = 0.88 -> 88
        assertEquals(88, CorporateEvents.adjust(raw, evs, DividendPolicy.ALL).close(0), 1e-9);
        // SPECIAL_ONLY: only 88/98 -> 100 * 0.897959... = 89.7959...
        assertEquals(100 * 88.0 / 98, CorporateEvents.adjust(raw, evs, DividendPolicy.SPECIAL_ONLY).close(0), 1e-9);
        assertEquals(100, CorporateEvents.adjust(raw, evs, DividendPolicy.NONE).close(0), EPS);
        assertTrue(CorporateEvents.totalReturnError(raw,
                CorporateEvents.adjust(raw, evs, DividendPolicy.ALL), evs) < 1e-12);
        // NONE is a price-return series: it misses the special drop 10/98 = 0.10204
        assertEquals(10.0 / 98, CorporateEvents.totalReturnError(raw,
                CorporateEvents.adjust(raw, evs, DividendPolicy.NONE), evs), 1e-12);
    }

    @Test
    void spinOffAllocatesBasisAndCreatesChild() {
        // 1 child per 2 parents, child 40: distribution value 20; f = 80/100
        SpinOff e = new SpinOff(5, "CHLD", "XS000CHILD1", 0.5, 40);
        BarSeries adj = CorporateEvents.adjust(tape(80), List.of(e));
        assertEquals(80, adj.close(0), 1e-9);
        assertNoJump(adj);
        // basis 9,000 on 100 sh; parent 80 vs child 0.5*40 = 20 -> 80 % / 20 %
        Outcome o = CorporateEventPositions.apply(new Holding("EQ", "X1", 100, 9_000), e, 80);
        assertEquals(7_200, o.holdings().get(0).costBasis(), 1e-9);
        Holding child = o.holdings().get(1);
        assertEquals("CHLD", child.symbol());
        assertEquals("XS000CHILD1", child.isin());
        assertEquals(50, child.shares(), EPS);
        assertEquals(1_800, child.costBasis(), 1e-9);
    }

    @Test
    void rightsIssueTerp() {
        // 1 for 4 at 80, cum 100: TERP = (100 + 20) / 1.25 = 96, f = 0.96
        RightsIssue e = new RightsIssue(5, 0.25, 80);
        assertEquals(96, CorporateEvents.terp(100, e), EPS);
        BarSeries adj = CorporateEvents.adjust(tape(96), List.of(e));
        assertEquals(96, adj.close(0), 1e-9);
        assertNoJump(adj);
        // take up on 400 sh: pay 100 * 80 = 8,000 for 100 new
        Outcome o = CorporateEventPositions.takeUpRights(new Holding("EQ", "X1", 400, 36_000), e);
        assertEquals(500, o.holdings().get(0).shares(), EPS);
        assertEquals(-8_000, o.cash(), EPS);
        assertEquals(44_000, o.holdings().get(0).costBasis(), EPS);
    }

    @Test
    void symbolChangeRenamesWithoutAdjusting() {
        SymbolChange e = new SymbolChange(5, "NEW", "US0000NEW01");
        BarSeries adj = CorporateEvents.adjust(tape(100), List.of(e));
        assertEquals("NEW", adj.symbol());
        assertEquals(100, adj.close(0), EPS);
        Holding h = CorporateEventPositions.apply(new Holding("EQ", "X1", 7, 70), e, 100).holdings().get(0);
        assertEquals("NEW", h.symbol());
        assertEquals("US0000NEW01", h.isin());
        assertEquals(7, h.shares(), EPS);
    }

    @Test
    void cashStockMergerWithElectionAndProration() {
        // cash election 50/sh or stock election 0.8 acquirer/sh, acquirer 62.5 (0.8*62.5 = 50)
        // deal pays at most 40 % in cash; 80 % of holders elect cash -> proration 0.5
        CashStockMerger m = new CashStockMerger(5, "ACQ", "US00000ACQ1", 50, 0.8, 0.4, 62.5);
        // holder of 1,001 sh, all elected cash: 500.5 paid cash = 25,025
        // 500.5 sh to stock -> 400.4 ACQ: 400 kept, 0.4 * 62.5 = 25 cash in lieu
        Outcome o = CorporateEventPositions.merger(new Holding("TGT", "T1", 1_001, 40_040), m, 1.0, 0.8);
        assertEquals(0, o.holdings().get(0).shares(), EPS);
        Holding acq = o.holdings().get(1);
        assertEquals(400, acq.shares(), EPS);
        assertEquals(25_050, o.cash(), 1e-9);
        // value 25,000 stock + 25,050 cash = 50,050; basis 40,040 * 25,000 / 50,050 = 20,000
        assertEquals(20_000, acq.costBasis(), 1e-9);
        // no proration when cash elections fit under the cap
        Outcome all = CorporateEventPositions.merger(new Holding("TGT", "T1", 100, 1), m, 1.0, 0.3);
        assertEquals(5_000, all.cash(), EPS);
        assertEquals(0, all.holdings().get(1).shares(), EPS);
        assertThrows(IllegalArgumentException.class,
                () -> CorporateEventPositions.merger(new Holding("TGT", "T1", 1, 1), m, 1.5, 0));
    }

    @Test
    void combinedHistoryPassesTotalReturnCheck() {
        // noisy raw tape with every adjusting event; the independent share-count
        // simulation must match the adjusted returns bar by bar
        double[] c = {50, 51, 52, 26.5, 27, 26, 25.2, 25.5, 24, 24.5, 240, 238, 230, 231, 235};
        BarSeries.Builder b = BarSeries.builder("EQ");
        for (int i = 0; i < c.length; i++) {
            b.add(i, c[i], c[i], c[i], c[i], 1_000);
        }
        BarSeries raw = b.build();
        List<Event> evs = List.of(
                new StockDividend(3, 1.0),                       // 2-for-1 bonus
                new Dividend(5, 0.5, DividendKind.REGULAR),
                new Dividend(6, 1.0, DividendKind.SPECIAL),
                new RightsIssue(8, 0.1, 20),
                new ReverseSplit(10, 10),
                new SpinOff(12, "CH", "C1", 1, 5),
                new SymbolChange(13, "EQ2", "I2"));
        BarSeries adj = CorporateEvents.adjust(raw, evs);
        assertEquals("EQ2", adj.symbol());
        assertTrue(CorporateEvents.totalReturnError(raw, adj, evs) < 1e-12);
        assertEquals(raw.close(14), adj.close(14), EPS);   // latest bar is never adjusted
    }

    @Test
    void rejectsBadInputs() {
        assertThrows(IllegalArgumentException.class, () -> new ReverseSplit(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new StockDividend(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CashStockMerger(0, "A", "I", 0, 0, 0.5, 1));
        assertThrows(IllegalArgumentException.class, () -> CorporateEvents.adjust(tape(100),
                List.of(new SpinOff(5, "C", "I", 1, 100))));
    }
}
