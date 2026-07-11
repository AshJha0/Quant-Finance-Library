package com.quantfinlib.backtest;

import com.quantfinlib.TestData;
import com.quantfinlib.backtest.strategies.SmaCrossStrategy;
import com.quantfinlib.core.BarSeries;
import com.quantfinlib.microstructure.Execution;
import com.quantfinlib.microstructure.TransactionCostAnalyzer;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionAwareBacktesterTest {

    /** Signals BUY once at a fixed bar, HOLD otherwise. */
    private static TradingStrategy buyOnceAt(int bar) {
        return new TradingStrategy() {
            @Override
            public String name() {
                return "BUY_ONCE";
            }

            @Override
            public void init(BarSeries series) {
            }

            @Override
            public Signal onBar(int index) {
                return index == bar ? Signal.BUY : Signal.HOLD;
            }
        };
    }

    /** Flat market: close 100, volume 1000 per bar. */
    private static BarSeries flatSeries(int bars) {
        BarSeries.Builder b = BarSeries.builder("FLAT");
        for (int i = 0; i < bars; i++) {
            b.add(i, 100, 100.5, 99.5, 100, 1_000);
        }
        return b.build();
    }

    private static final List<SorExecution.VenueConfig> VENUES = List.of(
            new SorExecution.VenueConfig("LIT_A", 1.0, 0.2, false),
            new SorExecution.VenueConfig("LIT_B", 0.5, 0.2, false),
            new SorExecution.VenueConfig("DARK_X", 0.2, 0.1, true));

    // ------------------------------------------------------------------

    @Test
    void instantExecutionMatchesClassicBacktester() {
        BarSeries s = BarSeries.of("CYCLE", TestData.sineTrend(500, 100, 0.02, 10, 60));
        BacktestConfig cfg = BacktestConfig.defaults();

        BacktestResult classic = Backtester.run(new SmaCrossStrategy(5, 20), s, cfg);
        ExecutionAwareResult aware = ExecutionAwareBacktester.run(
                new SmaCrossStrategy(5, 20), s, cfg, InstantExecution.from(cfg));

        assertEquals(classic.trades().size(), aware.backtest().trades().size());
        // Integer share sizes and all-in pricing vs separate fees: small drift allowed.
        double classicEnd = classic.metrics().finalEquity();
        double awareEnd = aware.backtest().metrics().finalEquity();
        assertEquals(classicEnd, awareEnd, classicEnd * 0.02,
                "classic=" + classicEnd + " aware=" + awareEnd);
        assertEquals(s.size(), aware.backtest().equityCurve().length);
    }

    @Test
    void sorFillsAcrossVenuesAndAcrossBars() {
        BarSeries s = flatSeries(20);
        // Per-bar liquidity = (200 + 200 + 100) = 500; target ≈ 990 -> needs 2 bars.
        ExecutionAwareResult r = ExecutionAwareBacktester.run(
                buyOnceAt(2), s, BacktestConfig.defaults(),
                new SorExecution(VENUES, 5, true));

        ParentOrder entryParent = r.parentOrders().getFirst();
        assertEquals(ParentOrder.REASON_ENTRY, entryParent.reason());
        assertTrue(entryParent.fillDurationBars() >= 1, "entry should span multiple bars");

        Set<String> venuesHit = new HashSet<>();
        for (Execution f : entryParent.fills()) {
            venuesHit.add(f.venue());
        }
        assertTrue(venuesHit.size() >= 3, "should split across venues: " + venuesHit);
        // Dark-first routing: first fill of the first bar is the dark venue.
        assertEquals("DARK_X", entryParent.fills().getFirst().venue());
        // Buy fills cost more than mid (spread + fees).
        assertTrue(entryParent.avgFillPrice() > 100.0);
        // Fully filled: ~999 shares — sizing uses the model's EXACT declared
        // worst-case cost (half-spread + max fee, a few bps), not a flat 1%
        // buffer. The invariant that matters is cash conservation.
        assertTrue(entryParent.filledQty() >= 950 && entryParent.filledQty() <= 1000,
                "filled=" + entryParent.filledQty());
        double entrySpend = 0;
        for (Execution f : entryParent.fills()) {
            entrySpend += f.notional();
        }
        assertTrue(entrySpend <= 100_000, "entry must never overdraw cash: " + entrySpend);
        // End of data closes the position; equity stays sane.
        assertEquals(Trade.REASON_END_OF_DATA,
                r.backtest().trades().getFirst().exitReason());
    }

    @Test
    void icebergSlicesEntryAcrossBars() {
        BarSeries s = flatSeries(30);
        BacktestConfig cfg = BacktestConfig.defaults().withInitialCapital(50_000);
        // ~495 target with 100-share display: 5 bars of work.
        ExecutionAwareResult r = ExecutionAwareBacktester.run(
                buyOnceAt(1), s, cfg,
                new IcebergExecution(new InstantExecution(0.0005, 0), 100));

        ParentOrder entryParent = r.parentOrders().getFirst();
        assertTrue(entryParent.fills().size() >= 4, "fills=" + entryParent.fills().size());
        for (Execution f : entryParent.fills()) {
            assertTrue(f.quantity() <= 100, "tranche " + f.quantity() + " exceeds display");
        }
        assertTrue(entryParent.fillDurationBars() >= 3);
        // Consecutive bars — the iceberg reloads every bar in a liquid market.
        for (int k = 1; k < entryParent.fillBarIndices().length; k++) {
            assertEquals(entryParent.fillBarIndices()[k - 1] + 1, entryParent.fillBarIndices()[k]);
        }
        assertTrue(entryParent.filledQty() >= 490);
    }

    @Test
    void stopLossExitsThroughTheModel() {
        BarSeries.Builder b = BarSeries.builder("CRASH");
        for (int i = 0; i < 10; i++) {
            b.add(i, 100, 101, 99, 100, 10_000);
        }
        b.add(10, 100, 100, 65, 68, 10_000);   // crash bar
        for (int i = 11; i < 15; i++) {
            b.add(i, 68, 69, 67, 68, 10_000);
        }
        BarSeries s = b.build();

        BacktestConfig cfg = BacktestConfig.defaults().withStopLoss(0.10);
        ExecutionAwareResult r = ExecutionAwareBacktester.run(
                buyOnceAt(2), s, cfg, InstantExecution.from(cfg));

        assertEquals(1, r.backtest().trades().size());
        Trade t = r.backtest().trades().getFirst();
        assertEquals(Trade.REASON_STOP_LOSS, t.exitReason());
        assertEquals(10, t.exitIndex());
        assertTrue(t.pnl() < 0);
        // Exit parent carries the same reason.
        ParentOrder exitParent = r.parentOrders().get(1);
        assertEquals(Trade.REASON_STOP_LOSS, exitParent.reason());
        assertEquals(exitParent.filledQty(), t.quantity(), 1e-9);
    }

    @Test
    void sellSignalCancelsUnfilledEntryRemainder() {
        BarSeries s = flatSeries(30);
        // Iceberg shows 50/bar; SELL arrives at bar 4 after ~150 filled.
        TradingStrategy strat = new TradingStrategy() {
            @Override
            public String name() {
                return "ENTER_THEN_ABORT";
            }

            @Override
            public void init(BarSeries series) {
            }

            @Override
            public Signal onBar(int index) {
                if (index == 1) {
                    return Signal.BUY;
                }
                if (index == 4) {
                    return Signal.SELL;
                }
                return Signal.HOLD;
            }
        };
        ExecutionAwareResult r = ExecutionAwareBacktester.run(
                strat, s, BacktestConfig.defaults(),
                new IcebergExecution(new InstantExecution(0, 0), 50));

        assertEquals(1, r.backtest().trades().size());
        Trade t = r.backtest().trades().getFirst();
        assertEquals(Trade.REASON_SIGNAL, t.exitReason());
        // Only the tranches filled by bar 4 (bars 1-4 = 200 shares) round-tripped.
        assertEquals(200, t.quantity(), 1e-9);
        // Position is flat afterwards and stays flat.
        double[] equity = r.backtest().equityCurve();
        assertEquals(equity[10], equity[29], 1e-9);
    }

    @Test
    void tcaQuantifiesExecutionCostPerParent() {
        BarSeries s = flatSeries(20);
        ExecutionAwareResult r = ExecutionAwareBacktester.run(
                buyOnceAt(2), s, BacktestConfig.defaults(),
                new SorExecution(VENUES, 5, false));

        ParentOrder entryParent = r.parentOrders().getFirst();
        TransactionCostAnalyzer.TcaReport tca = r.tca(entryParent);

        // Flat market at 100: all cost comes from spread + fees.
        assertEquals(100.0, tca.arrivalMid(), 1e-9);
        assertEquals(100.0, tca.marketVwap(), 1e-9);
        assertTrue(tca.implementationShortfallBps() > 0,
                "buy in a spread market must cost: " + tca.implementationShortfallBps());
        assertTrue(tca.implementationShortfallBps() < 10);           // half-spread 5bps + ≤1bps fees
        assertEquals(tca.implementationShortfallBps(), tca.slippageVsVwapBps(), 1e-9);
        assertTrue(tca.avgEffectiveSpreadBps() > 0);
        assertEquals(entryParent.filledQty(), tca.totalQuantity());
    }

    @Test
    void patientExecutionUnderperformsInstantInATrendingMarket() {
        // Rising market: slow (iceberg) entry buys later at higher prices.
        BarSeries s = BarSeries.of("UP", TestData.sineTrend(60, 100, 0.5, 0, 10));
        BarSeries.Builder withVol = BarSeries.builder("UP");
        for (int i = 0; i < s.size(); i++) {
            withVol.add(i, s.open(i), s.high(i), s.low(i), s.close(i), 1_000);
        }
        BarSeries up = withVol.build();
        BacktestConfig cfg = BacktestConfig.defaults();

        ExecutionAwareResult instant = ExecutionAwareBacktester.run(
                buyOnceAt(1), up, cfg, new InstantExecution(0, 0));
        ExecutionAwareResult patient = ExecutionAwareBacktester.run(
                buyOnceAt(1), up, cfg, new IcebergExecution(new InstantExecution(0, 0), 100));

        double instantAvg = instant.parentOrders().getFirst().avgFillPrice();
        double patientAvg = patient.parentOrders().getFirst().avgFillPrice();
        assertTrue(patientAvg > instantAvg,
                "patient=" + patientAvg + " instant=" + instantAvg);
        assertNotEquals(0, patient.parentOrders().getFirst().fillDurationBars());
        // The implementation shortfall of the patient entry is visibly positive.
        assertTrue(patient.tca(patient.parentOrders().getFirst()).implementationShortfallBps() > 0);
    }
}
