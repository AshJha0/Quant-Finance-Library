package com.quantfinlib.trading;

import com.quantfinlib.core.Side;
import com.quantfinlib.trading.DropCopyReconciler.BreakType;
import com.quantfinlib.trading.DropCopyReconciler.Fill;
import com.quantfinlib.trading.PositionLedger.CostMethod;
import com.quantfinlib.util.AllocationProbe;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static com.quantfinlib.trading.PositionLedger.ANY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionLedgerTest {

    private static final double EPS = 1e-9;

    @Test
    void handWorkedAverageCostAndFifo() {
        PositionLedger l = new PositionLedger(1, 1, 1, 8, 16);
        assertTrue(l.onFill(1, 0, 0, 0, Side.BUY, 100, 10.0));
        assertTrue(l.onFill(2, 0, 0, 0, Side.BUY, 100, 12.0));
        assertTrue(l.onFill(3, 0, 0, 0, Side.SELL, 150, 11.0));
        assertEquals(50, l.position(0, 0, 0));
        assertEquals(11.0, l.averageCost(0, 0, 0), EPS);
        assertEquals(0.0, l.realised(CostMethod.AVERAGE_COST, 0, 0, 0), EPS);
        assertEquals(50.0, l.realised(CostMethod.FIFO, 0, 0, 0), EPS);   // 100*1 + 50*(-1)
        assertEquals(1, l.openLots(0, 0, 0));
        assertEquals(50, l.lotQuantity(0, 0, 0, 0));
        assertEquals(12.0, l.lotPrice(0, 0, 0, 0), EPS);
        PositionLedger.PriceSource at13 = s -> 13.0;
        assertEquals(100.0, l.unrealised(CostMethod.AVERAGE_COST, 0, 0, 0, at13), EPS);
        assertEquals(50.0, l.unrealised(CostMethod.FIFO, 0, 0, 0, at13), EPS);
        assertEquals(100.0, l.totalPnl(CostMethod.FIFO, 0, 0, 0, at13), EPS);

        // Sell through flat: close 50, open 50 short at 14.
        assertTrue(l.onFill(4, 0, 0, 0, Side.SELL, 100, 14.0));
        assertEquals(-50, l.position(0, 0, 0));
        assertEquals(14.0, l.averageCost(0, 0, 0), EPS);
        assertEquals(150.0, l.realised(CostMethod.AVERAGE_COST, 0, 0, 0), EPS);  // 50*(14-11)
        assertEquals(150.0, l.realised(CostMethod.FIFO, 0, 0, 0), EPS);          // 50 + 50*(14-12)
        assertEquals(50.0, l.unrealised(CostMethod.FIFO, 0, 0, 0, at13), EPS);   // -50*(13-14)
        assertEquals(50.0, l.unrealised(CostMethod.AVERAGE_COST, 0, 0, 0, at13), EPS);

        // Buy back exactly flat.
        assertTrue(l.onFill(5, 0, 0, 0, Side.BUY, 50, 13.5));
        assertEquals(0, l.position(0, 0, 0));
        assertEquals(0.0, l.averageCost(0, 0, 0));
        assertEquals(175.0, l.realised(CostMethod.FIFO, 0, 0, 0), EPS);
        assertEquals(175.0, l.realised(CostMethod.AVERAGE_COST, 0, 0, 0), EPS);
        assertEquals(0.0, l.unrealised(CostMethod.FIFO, 0, 0, 0, s -> Double.NaN), "flat needs no mark");
        assertEquals(250, l.boughtQuantity(0, 0, 0));
        assertEquals(250, l.soldQuantity(0, 0, 0));
        assertEquals(5, l.fillCount());
    }

    @Test
    void multiplierScalesPnl() {
        PositionLedger l = new PositionLedger(2, 1, 1, 4, 0);
        l.setMultiplier(1, 50.0);                      // e.g. an index future, 50 per point
        assertEquals(50.0, l.multiplier(1));
        l.onFill(1, 0, 0, 1, Side.BUY, 2, 4000.0, 4.0);
        l.onFill(2, 0, 0, 1, Side.SELL, 1, 4010.0, 2.0);
        assertEquals(500.0, l.realised(CostMethod.FIFO, 0, 0, 1), EPS);
        assertEquals(1000.0, l.unrealised(CostMethod.AVERAGE_COST, 0, 0, 1, s -> 4020.0), EPS);
        assertEquals(6.0, l.fees(0, 0, 1), EPS);
        assertEquals(2, l.fillLogOverflow());
        assertThrows(IllegalArgumentException.class, () -> l.setMultiplier(0, 0.0));
    }

    /**
     * Independent reference: total P&amp;L must equal the cash-flow identity
     * m (n M - sum s_i q_i p_i) for both conventions, over random fill paths.
     */
    @Test
    void bothConventionsMatchCashFlowIdentityOnRandomPaths() {
        SplittableRandom rng = new SplittableRandom(20261101L);
        for (int path = 0; path < 50; path++) {
            PositionLedger l = new PositionLedger(1, 1, 1, 512, 0);
            double m = 1 + rng.nextInt(10);
            l.setMultiplier(0, m);
            double cash = 0;
            long pos = 0;
            double px = 100;
            for (int i = 0; i < 300; i++) {
                px += rng.nextGaussian();
                Side side = rng.nextBoolean() ? Side.BUY : Side.SELL;
                long q = 1L + rng.nextInt(40);
                assertTrue(l.onFill(i, 0, 0, 0, side, q, px));
                cash -= side.sign() * q * px;
                pos += side.sign() * q;
            }
            double mark = px + 0.37;
            double reference = m * (pos * mark + cash);
            assertEquals(pos, l.position(0, 0, 0));
            for (CostMethod method : CostMethod.values()) {
                assertEquals(reference, l.totalPnl(method, 0, 0, 0, s -> mark),
                        1e-7 * Math.max(1, Math.abs(reference)), method.name());
            }
            long lots = 0;
            for (int k = 0; k < l.openLots(0, 0, 0); k++) {
                lots += l.lotQuantity(0, 0, 0, k);
            }
            assertEquals(Math.abs(pos), lots, "open lots add up to the position");
        }
    }

    @Test
    void aggregatesAcrossAccountsStrategiesAndSymbols() {
        PositionLedger l = new PositionLedger(2, 2, 2, 4, 64);
        l.onFill(1, 0, 0, 0, Side.BUY, 10, 5.0);
        l.onFill(2, 0, 1, 0, Side.BUY, 20, 6.0);
        l.onFill(3, 1, 0, 0, Side.SELL, 5, 7.0);
        l.onFill(4, 1, 1, 1, Side.BUY, 3, 100.0);
        l.onFill(5, 1, 1, 1, Side.SELL, 3, 101.0);
        assertEquals(25, l.netPosition(ANY, ANY, 0));
        assertEquals(30, l.netPosition(0, ANY, 0));
        assertEquals(5, l.netPosition(ANY, 0, 0));
        assertEquals(3.0, l.realised(CostMethod.FIFO, ANY, ANY, ANY), EPS);
        assertEquals(3.0, l.realised(CostMethod.AVERAGE_COST, 1, ANY, ANY), EPS);
        assertEquals(0.0, l.realised(CostMethod.FIFO, 0, ANY, ANY), EPS);
        PositionLedger.PriceSource marks = s -> s == 0 ? 6.5 : Double.NaN;
        // account 0: 10*(1.5) + 20*(0.5) = 25; account 1: -5*(6.5-7) = 2.5; symbol 1 flat.
        assertEquals(27.5, l.unrealised(CostMethod.FIFO, ANY, ANY, ANY, marks), EPS);
        assertEquals(0, l.unpricedBooks(ANY, ANY, ANY, marks));
        l.onFill(6, 0, 0, 1, Side.BUY, 1, 100.0);
        assertTrue(Double.isNaN(l.unrealised(CostMethod.FIFO, ANY, ANY, ANY, marks)));
        assertEquals(1, l.unpricedBooks(ANY, ANY, ANY, marks));
        assertThrows(IndexOutOfBoundsException.class, () -> l.netPosition(ANY, ANY, ANY));
        assertEquals(2, l.symbols());
        assertEquals(2, l.strategies());
        assertEquals(2, l.accounts());
    }

    @Test
    void invalidFillsAndLotOverflowChangeNothing() {
        PositionLedger l = new PositionLedger(1, 1, 1, 2, 8);
        assertFalse(l.onFill(1, 1, 0, 0, Side.BUY, 1, 1.0));
        assertFalse(l.onFill(1, 0, 0, 0, Side.BUY, 0, 1.0));
        assertFalse(l.onFill(1, 0, 0, 0, Side.BUY, 1, Double.NaN));
        assertFalse(l.onFill(1, 0, 0, 0, null, 1, 1.0));
        assertFalse(l.onFill(1, 0, 0, 0, Side.BUY, 1, 1.0, Double.POSITIVE_INFINITY));
        assertEquals(5, l.invalidFillCount());
        assertTrue(l.onFill(1, 0, 0, 0, Side.BUY, 1, 1.0));
        assertTrue(l.onFill(2, 0, 0, 0, Side.BUY, 1, 2.0));
        assertFalse(l.onFill(3, 0, 0, 0, Side.BUY, 1, 3.0), "third lot does not fit");
        assertEquals(1, l.lotOverflowCount());
        assertEquals(2, l.position(0, 0, 0));
        assertTrue(l.onFill(4, 0, 0, 0, Side.SELL, 1, 3.0), "closing needs no lot");
        assertTrue(l.onFill(5, 0, 0, 0, Side.BUY, 1, 3.0), "ring wraps");
        assertEquals(2.0, l.lotPrice(0, 0, 0, 0));
        assertEquals(3.0, l.lotPrice(0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new PositionLedger(0, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new PositionLedger(1, 1, 1, 0, 1));
    }

    @Test
    void ingestIsAllocationFree() {
        PositionLedger l = new PositionLedger(4, 2, 2, 64, 1 << 16);
        long[] id = {0};
        Runnable burst = () -> {
            for (int i = 0; i < 1000; i++) {
                long e = id[0]++;
                l.onFill(e, (int) (e & 1), (int) ((e >> 1) & 1), (int) (e & 3),
                        (e & 4) == 0 ? Side.BUY : Side.SELL, 1 + (e % 7), 100.0 + (e % 13), 0.01);
            }
        };
        AllocationProbe.assertZeroAllocation(burst, burst, 5);
    }

    // ---- drop copy -----------------------------------------------------------

    @Test
    void reconciliationFindsEveryBreakType() {
        PositionLedger l = new PositionLedger(3, 1, 1, 8, 16);
        l.onFill(101, 0, 0, 0, Side.BUY, 100, 10.00);   // matches
        l.onFill(102, 0, 0, 0, Side.BUY, 50, 10.01);    // price within tolerance
        l.onFill(103, 0, 0, 1, Side.SELL, 20, 5.0);     // qty mismatch (drop copy 25)
        l.onFill(104, 0, 0, 2, Side.BUY, 7, 3.0);       // extra
        l.onFill(104, 0, 0, 2, Side.BUY, 7, 3.0);       // booked twice
        List<Fill> dc = new ArrayList<>(List.of(
                Fill.dropCopy(101, 0, Side.BUY, 100, 10.00),
                Fill.dropCopy(102, 0, Side.BUY, 50, 10.015),
                Fill.dropCopy(103, 1, Side.SELL, 25, 5.2),
                Fill.dropCopy(105, 0, Side.SELL, 30, 10.5),   // missing here
                Fill.dropCopy(105, 0, Side.SELL, 30, 10.5)));  // replayed record
        DropCopyReconciler.Report r = DropCopyReconciler.reconcile(l, dc, 0.01);
        assertEquals(2, r.matched());
        assertEquals(1, r.breaks(BreakType.MISMATCH).size());
        String detail = r.breaks(BreakType.MISMATCH).getFirst().detail();
        assertTrue(detail.contains("qty 20 vs 25") && detail.contains("price"), detail);
        assertEquals(104, r.breaks(BreakType.EXTRA).getFirst().execId());
        assertEquals(105, r.breaks(BreakType.MISSING).getFirst().execId());
        assertEquals(2, r.breaks(BreakType.DUPLICATE).size());
        assertFalse(r.isClean());
        assertTrue(r.ownLogComplete());
        // Symbol 0: drop copy +150 - 30 = 120 vs own 150 -> -30. Symbol 1: -25 vs -20 -> -5.
        // Symbol 2: 0 vs +14 -> -14.
        assertEquals(-30L, r.quantityDifference().get(0));
        assertEquals(-5L, r.quantityDifference().get(1));
        assertEquals(-14L, r.quantityDifference().get(2));
        String text = r.format();
        assertTrue(text.startsWith("matched=2 breaks=5"), text);
        assertTrue(text.contains("MISSING execId=105"));
    }

    @Test
    void cleanReconciliationAndSideOrSymbolMismatch() {
        List<Fill> own = List.of(new Fill(1, 0, 0, 0, Side.BUY, 5, 1.0));
        assertTrue(DropCopyReconciler.reconcile(own, List.of(Fill.dropCopy(1, 0, Side.BUY, 5, 1.0)), 0)
                .isClean());
        var r = DropCopyReconciler.reconcile(own, List.of(Fill.dropCopy(1, 1, Side.SELL, 5, 1.0)), 0);
        String d = r.breaks().getFirst().detail();
        assertTrue(d.contains("symbol 0 vs 1") && d.contains("side BUY vs SELL"), d);
        assertThrows(IllegalArgumentException.class,
                () -> DropCopyReconciler.reconcile(own, List.of(), -1));
        assertThrows(IllegalArgumentException.class, () -> new Fill(1, 0, 0, 0, Side.BUY, 0, 1.0));

        PositionLedger small = new PositionLedger(1, 1, 1, 4, 1);
        small.onFill(1, 0, 0, 0, Side.BUY, 1, 1.0);
        small.onFill(2, 0, 0, 0, Side.BUY, 1, 1.0);
        var partial = DropCopyReconciler.reconcile(small, List.of(Fill.dropCopy(1, 0, Side.BUY, 1, 1.0),
                Fill.dropCopy(2, 0, Side.BUY, 1, 1.0)), 0);
        assertFalse(partial.ownLogComplete());
        assertFalse(partial.isClean());
        assertTrue(partial.format().contains("incomplete"));
    }
}
