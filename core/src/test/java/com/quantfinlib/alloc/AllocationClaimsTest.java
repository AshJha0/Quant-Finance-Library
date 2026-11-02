package com.quantfinlib.alloc;

import com.quantfinlib.backtest.TradingStrategy;
import com.quantfinlib.backtest.strategies.SmaCrossStrategy;
import com.quantfinlib.core.BarSeries;
import com.quantfinlib.core.Side;
import com.quantfinlib.crb.CentralRiskBook;
import com.quantfinlib.crb.CrbAutoHedger;
import com.quantfinlib.crb.InternalizationEngine;
import com.quantfinlib.execution.HftSor;
import com.quantfinlib.execution.Ucb1Selector;
import com.quantfinlib.fx.AggregatedBook;
import com.quantfinlib.fx.CurrencyPair;
import com.quantfinlib.fx.FixingRisk;
import com.quantfinlib.fx.FxVolSurface;
import com.quantfinlib.fx.SwapPointsCurve;
import com.quantfinlib.marketdata.tick.TickFileReader;
import com.quantfinlib.marketdata.tick.TickFileWriter;
import com.quantfinlib.microstructure.CircuitBreakers;
import com.quantfinlib.microstructure.DayTypeProfiles;
import com.quantfinlib.microstructure.EwmaCovariance;
import com.quantfinlib.microstructure.KalmanBeta;
import com.quantfinlib.microstructure.TickSizeSchedule;
import com.quantfinlib.pricing.Black76;
import com.quantfinlib.pricing.BlackScholes.OptionType;
import com.quantfinlib.pricing.DividendSchedule;
import com.quantfinlib.pricing.HigherOrderGreeks;
import com.quantfinlib.pricing.IncrementalGreeks;
import com.quantfinlib.rates.ShortRateModels;
import com.quantfinlib.rates.YieldCurve;
import com.quantfinlib.risk.CovarianceShrinkage;
import com.quantfinlib.risk.GaussianCopula;
import com.quantfinlib.stats.Numerics;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.SplittableRandom;

import static com.quantfinlib.alloc.AllocationProbe.assertAllocates;
import static com.quantfinlib.alloc.AllocationProbe.assertAllocationFree;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One test per "allocation-free" / "no allocation" / "allocates nothing" /
 * "zero-alloc" claim in the core javadoc. Inputs are built outside the
 * measured operation; each operation runs the claimed call in steady state.
 */
@Tag("allocation")
class AllocationClaimsTest {

    private static final int N = 1024;
    private static final int MASK = N - 1;

    private static double[] wave(int n, double base, double amp) {
        double[] a = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = base + amp * Math.sin(i * 0.05) + 0.1 * amp * Math.cos(i * 0.31);
        }
        return a;
    }

    // ------------------------------------------------------------------
    // Probe sanity
    // ------------------------------------------------------------------

    @Test
    void probeSeesAllocation() throws Exception {
        assertAllocates(i -> {
            double[] a = new double[4];
            a[i & 3] = i;
            return a[0] + a.length;
        });
    }

    // ------------------------------------------------------------------
    // backtest
    // ------------------------------------------------------------------

    @Test
    void shippedStrategyOnBarIsAllocationFree() throws Exception {
        BarSeries s = BarSeries.of("X", wave(N, 100, 5));
        TradingStrategy[] strategies = {new SmaCrossStrategy(5, 20), new SmaCrossStrategy(10, 50)};
        for (TradingStrategy st : strategies) {
            st.init(s);
            assertAllocationFree("strategies/package-info + TradingStrategy: " + st.name() + " onBar",
                    i -> st.onBar(i & MASK).ordinal() + st.onBar(i & MASK, 1.0).ordinal());
        }
    }

    // ------------------------------------------------------------------
    // core / internal value types
    // ------------------------------------------------------------------

    @Test
    void barSeriesIndexedAccessorsAreAllocationFree() throws Exception {
        BarSeries s = BarSeries.of("X", wave(N, 100, 5));
        double[] dst = new double[16];
        assertAllocationFree("BarSeries indexed accessors", i -> {
            int k = i & MASK;
            s.copyClosesInto(dst, k & 511, 16);
            return s.open(k) + s.high(k) + s.low(k) + s.close(k) + s.volume(k)
                    + s.timestamp(k) + s.lastClose() + s.size() + (s.isSynthetic(k) ? 1 : 0) + dst[3];
        });
    }

    @Test
    void valueArraysBackedIndexedAccessorsAreAllocationFree() throws Exception {
        double[][] m = {{1, 0.2, 0.1}, {0.2, 1, 0.3}, {0.1, 0.3, 1}};
        CovarianceShrinkage.Result r = new CovarianceShrinkage.Result(m, 0.4, 1.0);
        assertAllocationFree("ValueArrays: indexed accessors of a value type",
                i -> r.matrix(i % 3, (i / 3) % 3) + r.matrixRows() + r.intensity());
    }

    // ------------------------------------------------------------------
    // crb
    // ------------------------------------------------------------------

    @Test
    void centralRiskBookHandleBookingIsAllocationFree() throws Exception {
        CentralRiskBook crb = new CentralRiskBook();
        int desk = crb.deskId("EQ-DESK");
        int fxDesk = crb.deskId("FX-DESK");
        int eq = crb.equityHandle("AAPL");
        int pair = crb.fxPairHandle("EURUSD");
        // first booking on each handle resolves the factors (allowed to allocate)
        crb.bookCashEquity(desk, eq, 1, 100);
        crb.bookEquityOption(desk, eq, OptionType.CALL, 1, 100, 100, 100, 0.03, 0.01, 0.2, 0.5);
        crb.bookFxSpot(fxDesk, pair, 1e6, 1.08);
        crb.bookFxSwap(fxDesk, pair, 1e6, 1.08, 1.085);
        crb.bookNdf(fxDesk, pair, 1e6, 1.09);
        crb.bookFxOption(fxDesk, pair, OptionType.PUT, 1e6, 1.08, 1.07, 0.04, 0.03, 0.08, 0.25);

        assertAllocationFree("CentralRiskBook.bookCashEquity(int..)",
                i -> { crb.bookCashEquity(desk, eq, (i & 7) - 3.5, 100 + (i & 15)); return crb.flowsBooked(); });
        assertAllocationFree("CentralRiskBook.bookEquityOption(int..)",
                i -> { crb.bookEquityOption(desk, eq, (i & 1) == 0 ? OptionType.CALL : OptionType.PUT,
                        (i & 3) - 1.5, 100, 100 + (i & 7), 100, 0.03, 0.01, 0.2, 0.5); return crb.flowsBooked(); });
        assertAllocationFree("CentralRiskBook.bookFxSpot(int..)",
                i -> { crb.bookFxSpot(fxDesk, pair, 1e6 * ((i & 3) - 1.5), 1.08 + (i & 7) * 1e-4); return crb.flowsBooked(); });
        assertAllocationFree("CentralRiskBook.bookFxSwap(int..)",
                i -> { crb.bookFxSwap(fxDesk, pair, 1e6 * ((i & 3) - 1.5), 1.08, 1.081 + (i & 7) * 1e-4); return crb.flowsBooked(); });
        assertAllocationFree("CentralRiskBook.bookNdf(int..)",
                i -> { crb.bookNdf(fxDesk, pair, 1e6 * ((i & 3) - 1.5), 1.09 + (i & 7) * 1e-4); return crb.flowsBooked(); });
        assertAllocationFree("CentralRiskBook.bookFxOption(int..)",
                i -> { crb.bookFxOption(fxDesk, pair, OptionType.PUT, 1e6 * ((i & 3) - 1.5), 1.08 + (i & 7) * 1e-4,
                        1.07, 0.04, 0.03, 0.08, 0.25); return crb.flowsBooked(); });
    }

    @Test
    void autoHedgerIdlePathIsAllocationFree() throws Exception {
        CrbAutoHedger hedger = new CrbAutoHedger(new double[]{10_000_000}, 0.5, 2);
        double[][] cov = {{4e-4}};
        double[][] loadings = {{1}};
        double[] costs = {1};
        double[] exposure = new double[1];
        assertAllocationFree("CrbAutoHedger.check idle (inside the band)", i -> {
            exposure[0] = 1_000_000 + (i & 1023) * 1000;
            return hedger.check(exposure, cov, loadings, costs, 0, i).length;
        });
    }

    @Test
    void internalizeAllocatesNothing() throws Exception {
        InternalizationEngine engine = new InternalizationEngine(10_000_000, 0.5);
        assertAllocationFree("InternalizationEngine.internalize",
                i -> engine.internalize(((i & 15) - 7.5) * 500_000, ((i & 7) - 3.5) * 250_000, 1.5)
                        + engine.lastRouted() + engine.lastImprovementBps());
    }

    // ------------------------------------------------------------------
    // execution
    // ------------------------------------------------------------------

    @Test
    void hftSorRouteIsAllocationFree() throws Exception {
        HftSor sor = new HftSor(3);
        sor.fee(0, 0.3);
        sor.fee(1, 0.2);
        sor.fee(2, 0.25);
        long[] out = new long[3];
        assertAllocationFree("HftSor (zero-allocation router)", i -> {
            int k = i & 7;
            sor.venueQuote(0, 100 + (k & 1), 200, 101 + (k & 1), 300);
            sor.venueQuote(1, 100, 150 + k, 101, 250 + k);
            sor.venueQuote(2, 99 + (k & 3), 400, 102, 100);
            long r = (i & 1) == 0
                    ? sor.route(Side.BUY, 400 + k, Integer.MAX_VALUE, out)
                    : sor.route(Side.SELL, 300 + k, Integer.MIN_VALUE, out);
            return r + out[0];
        });
    }

    @Test
    void ucb1AfterConstructionIsAllocationFree() throws Exception {
        Ucb1Selector sel = new Ucb1Selector(4);
        assertAllocationFree("Ucb1Selector after construction", i -> {
            int arm = sel.select();
            sel.record(arm, ((i * 31 + arm) & 15) / 15.0);
            return arm + sel.meanReward(arm) + sel.pulls(arm);
        });
    }

    // ------------------------------------------------------------------
    // fx
    // ------------------------------------------------------------------

    @Test
    void fixingRiskStaticsAreAllocationFree() throws Exception {
        CurrencyPair eurusd = CurrencyPair.of("EURUSD");
        double[] prices = wave(64, 1.08, 0.001);
        double[] sizes = wave(64, 5, 1);
        assertAllocationFree("FixingRisk statics", i -> FixingRisk.windowTwap(prices)
                + FixingRisk.windowVwap(prices, sizes)
                + FixingRisk.slippageVsFix(eurusd, 1.08 + (i & 15) * 1e-5, 1.0801)
                + FixingRisk.trackingErrorStd(0.0002, 5 + (i & 7))
                + FixingRisk.participationRate(1e6 + i, 5e8));
    }

    @Test
    void fxVolSurfaceLookupsAreAllocationFree() throws Exception {
        FxVolSurface s = FxVolSurface.builder()
                .add(0.25, 1.0850, 0.075, -0.010, 0.0025)
                .add(1.0, 1.0920, 0.083, -0.012, 0.0030, -0.020, 0.0095)
                .build();
        double[] strikes = wave(N, 1.09, 0.05);
        assertAllocationFree("FxVolSurface lookups after build", i -> {
            double t = 0.1 + (i & 63) / 50.0;
            return s.vol(t, strikes[i & MASK]) + s.atmVol(t) + s.forwardAt(t);
        });
    }

    @Test
    @SuppressWarnings("removal")
    void aggregatedBookIsAllocationFree() throws Exception {
        AggregatedBook book = new AggregatedBook(4);
        assertAllocationFree("AggregatedBook (zero-alloc multi-venue BBO)", i -> {
            int v = i & 3;
            double px = 1.0800 + (i & 15) * 1e-5;
            book.onQuote(v, px, 1e6, px + 2e-5, 2e6);
            return book.bestBid() + book.bestAsk() + book.mid() + book.spread() + book.bestBidVenue()
                    + book.totalBidSizeAtBest(1e-9) + book.totalAskSizeAtBest(1e-9) + (book.isCrossed() ? 1 : 0);
        });
    }

    @Test
    void swapPointsCurveLookupsAreAllocationFree() throws Exception {
        LocalDate trade = LocalDate.of(2026, 3, 2);
        SwapPointsCurve c = SwapPointsCurve.builder(CurrencyPair.of("EURUSD"), trade, 1.0850)
                .add("1M", 11.0)
                .add("3M", 33.0)
                .add("6M", 70.0)
                .build();
        LocalDate[] dates = new LocalDate[128];
        for (int k = 0; k < dates.length; k++) {
            dates[k] = c.spotDate().plusDays(k + 1);
        }
        assertAllocationFree("SwapPointsCurve lookups", i -> {
            LocalDate d = dates[i & 127];
            return c.forwardPoints(d) + c.outright(d) + c.impliedCarry(d);
        });
    }

    // ------------------------------------------------------------------
    // marketdata.tick
    // ------------------------------------------------------------------

    private static long threadBytes() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean())
                .getCurrentThreadAllocatedBytes();
    }

    private static void writeV1(Path f, int ticks) throws Exception {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(f)))) {
            out.writeInt(0x51464C54 /* "QFLT", TickFileWriter.MAGIC */);
            out.writeByte(1);
            out.writeByte(1 /* TYPE_SYMBOL */);
            out.writeInt(0);
            byte[] name = "BTC".getBytes(StandardCharsets.UTF_8);
            out.writeShort(name.length);
            out.write(name);
            for (int i = 0; i < ticks; i++) {
                out.writeByte(0 /* TYPE_TICK */);
                out.writeInt(0);
                out.writeDouble(100 + (i & 63));
                out.writeDouble(1);
                out.writeLong(i);
            }
        }
    }

    /**
     * "Allocation-free per tick" is a per-tick claim on a call that opens a
     * stream (a fixed cost), so the test replays a small and a large v1 file
     * and requires the extra ticks to add essentially nothing.
     */
    @Test
    void tickFileV1ReplayIsAllocationFreePerTick(@TempDir Path dir) throws Exception {
        AllocationProbe.bestBatchBytes(i -> 0);   // enables thread allocation counting (or skips)
        Path small = dir.resolve("small.qflt");
        Path large = dir.resolve("large.qflt");
        int extra = 200_000;
        writeV1(small, 1_000);
        writeV1(large, 1_000 + extra);
        double[] acc = new double[1];
        TickFileReader.ReplayHandler h = (id, price, size, ts) -> acc[0] += price;
        for (int w = 0; w < 20; w++) {
            TickFileReader.replay(small, h);
            TickFileReader.replay(large, h);
        }
        long best = Long.MAX_VALUE;
        for (int a = 0; a < 5; a++) {
            long b0 = threadBytes();
            TickFileReader.replay(small, h);
            long b1 = threadBytes();
            long ticks = TickFileReader.replay(large, h);
            long b2 = threadBytes();
            assertEquals(1_000 + extra, ticks);
            best = Math.min(best, (b2 - b1) - (b1 - b0));
        }
        assertTrue(best <= AllocationProbe.TOLERANCE_BYTES,
                "TickFileReader v1 replay per tick: " + extra + " extra ticks allocated " + best + " bytes");
    }

    @Test
    void tickFileWriterSteadyStreamIsAllocationFree(@TempDir Path dir) throws Exception {
        try (TickFileWriter w = new TickFileWriter(dir.resolve("w.qflt"))) {
            w.defineSymbol(0, "BTC");
            // 29 bytes per tick: tens of thousands of calls cross many block flushes
            assertAllocationFree("TickFileWriter steady stream (per block)", i -> {
                w.write(0, 100 + (i & 63), 1, i);
                return w.tickCount();
            });
        }
    }

    // ------------------------------------------------------------------
    // microstructure
    // ------------------------------------------------------------------

    @Test
    void luldStateMachineIsAllocationFree() throws Exception {
        CircuitBreakers.Luld luld = new CircuitBreakers.Luld(true);
        luld.reference(100, false);
        assertAllocationFree("CircuitBreakers.Luld", i -> {
            double px = 100 + ((i & 31) - 16) * 0.5;
            CircuitBreakers.LuldState st = luld.onNbbo(px - 0.01, px + 0.01, i * 1_000_000_000L);
            return st.ordinal() + luld.state(i * 1_000_000_000L).ordinal() + luld.lowerBand()
                    + luld.upperBand() + CircuitBreakers.luldBandPct(100 + (i & 7), true, false);
        });
    }

    @Test
    void dayTypeProfileLookupIsAllocationFree() throws Exception {
        DayTypeProfiles<KalmanBeta> p = new DayTypeProfiles<>(3, () -> new KalmanBeta(1, 1, 1e-5, 1e-3));
        assertAllocationFree("DayTypeProfiles.profile", i -> p.profile(i % 3).beta() + p.dayTypes());
    }

    @Test
    void ewmaPortfolioArithmeticIsAllocationFree() throws Exception {
        EwmaCovariance ewma = new EwmaCovariance(3, 0.94);
        SplittableRandom rnd = new SplittableRandom(7);
        double[] r = new double[3];
        for (int k = 0; k < 200; k++) {
            for (int j = 0; j < 3; j++) {
                r[j] = rnd.nextGaussian() * 0.01;
            }
            ewma.onReturns(r);
        }
        double[] w = {0.5, 0.3, 0.2};
        double[] out = new double[3];
        assertAllocationFree("EwmaCovariance portfolio arithmetic", i -> {
            w[0] = 0.5 + (i & 7) * 0.01;
            return ewma.portfolioVariance(w) + ewma.marginalContribution(w, out)
                    + ewma.minVarianceHedgeRatio(0, 1 + (i & 1)) + ewma.correlation(0, 2);
        });
    }

    @Test
    void kalmanBetaObservationIsAllocationFree() throws Exception {
        KalmanBeta k = new KalmanBeta(1, 1, 1e-5, 1e-3);
        double[] x = wave(N, 0, 0.01);
        assertAllocationFree("KalmanBeta after construction",
                i -> k.onObservation(x[i & MASK], 1.2 * x[i & MASK] + 1e-4 * (i & 3)) + k.alpha());
    }

    @Test
    void tickSizeLookupsAreAllocationFree() throws Exception {
        TickSizeSchedule t = TickSizeSchedule.esmaStyle(3);
        double[] px = wave(N, 50, 40);
        assertAllocationFree("TickSizeSchedule lookups", i -> {
            double p = px[i & MASK];
            return t.tickFor(p) + t.tickForClamped(p) + t.roundDown(p) + t.roundUp(p)
                    + t.roundDownClamped(p) + t.roundUpClamped(p) + t.roundNearest(p)
                    + (t.isOnTick(p) ? 1 : 0) + t.bandCount();
        });
    }

    // ------------------------------------------------------------------
    // pricing / rates / risk / stats
    // ------------------------------------------------------------------

    @Test
    void black76IsAllocationFree() throws Exception {
        assertAllocationFree("Black76 statics", i -> {
            double k = 95 + (i & 15);
            double p = Black76.price(OptionType.CALL, 100, k, 0.03, 0.2, 0.5);
            return p + Black76.delta(OptionType.PUT, 100, k, 0.03, 0.2, 0.5)
                    + Black76.vega(100, k, 0.03, 0.2, 0.5)
                    + Black76.impliedVol(OptionType.CALL, p, 100, k, 0.03, 0.5);
        });
    }

    @Test
    void dividendScheduleQueriesAreAllocationFree() throws Exception {
        DividendSchedule d = DividendSchedule.of(new double[]{0.1, 0.35, 0.6}, new double[]{0.5, 0.5, 0.6});
        assertAllocationFree("DividendSchedule queries", i -> {
            double k = 95 + (i & 15);
            return d.presentValue(0.03, 0.5) + d.adjustedSpot(100, 0.03, 0.5)
                    + d.forward(100, 0.03, 0.01, 0.5)
                    + d.europeanPrice(OptionType.CALL, 100, k, 0.03, 0.01, 0.2, 0.5)
                    + d.bosVandermarkPrice(OptionType.PUT, 100, k, 0.03, 0.01, 0.2, 0.5)
                    + d.exTime(i % 3) + d.amount(i % 3) + d.count();
        });
    }

    @Test
    void higherOrderGreeksAreAllocationFree() throws Exception {
        assertAllocationFree("HigherOrderGreeks statics", i -> {
            double k = 95 + (i & 15);
            return HigherOrderGreeks.vanna(100, k, 0.03, 0.01, 0.2, 0.5)
                    + HigherOrderGreeks.volga(100, k, 0.03, 0.01, 0.2, 0.5)
                    + HigherOrderGreeks.exchangeCrossGamma(100, k, 0.2, 0.25, 0.5, 0.5);
        });
    }

    @Test
    void incrementalGreeksTickIsAllocationFree() throws Exception {
        IncrementalGreeks g = new IncrementalGreeks();
        g.reprice(OptionType.CALL, 100, 100, 0.03, 0.01, 0.2, 0.5);
        assertAllocationFree("IncrementalGreeks.onTick", i -> {
            g.onTick(100 + ((i & 15) - 8) * 0.05, (i & 7) * 1e-5);
            g.onTick(100 + (i & 3) * 0.01);
            return g.estimatedPrice() + g.estimatedDelta() + (g.needsReprice(1.0) ? 1 : 0);
        });
    }

    @Test
    void shortRateModelsAreAllocationFree() throws Exception {
        assertAllocationFree("ShortRateModels Vasicek and CIR statics", i -> {
            double r = 0.02 + (i & 15) * 1e-4;
            double g = ((i & 31) - 16) / 16.0;
            return ShortRateModels.vasicekBond(r, 0.5, 0.03, 0.01, 5)
                    + ShortRateModels.vasicekYield(r, 0.5, 0.03, 0.01, 5)
                    + ShortRateModels.vasicekStep(r, 0.5, 0.03, 0.01, 1.0 / 252, g)
                    + ShortRateModels.cirBond(r, 0.5, 0.03, 0.05, 5)
                    + ShortRateModels.cirFeller(0.5, 0.03, 0.05)
                    + ShortRateModels.cirStep(r, 0.5, 0.03, 0.05, 1.0 / 252, g);
        });
    }

    /**
     * The Hull-White functions read a TreeMap-backed YieldCurve, which boxes the
     * tenor. Without coverage instrumentation C2 often removes the boxes, with it
     * (the verify build) it does not, so the javadoc no longer claims these are
     * allocation-free; this test only keeps them in the claim inventory.
     */
    @Test
    void hullWhiteOnYieldCurveIsNotClaimedAllocationFree() throws Exception {
        YieldCurve curve = YieldCurve.ofZeroRates(new double[]{1, 5, 10}, new double[]{0.025, 0.03, 0.033});
        double r = 0.02;
        double v = ShortRateModels.hullWhiteBond(curve, 1, 5, r, 0.1, 0.01)
                + ShortRateModels.instantaneousForward(curve, 2.3);
        org.junit.jupiter.api.Assertions.assertTrue(Double.isFinite(v));
    }

    @Test
    void gaussianCopulaDrawIsAllocationFree() throws Exception {
        GaussianCopula c = new GaussianCopula(new double[][]{{1, 0.5, 0.2}, {0.5, 1, 0.3}, {0.2, 0.3, 1}});
        SplittableRandom rnd = new SplittableRandom(11);
        double[] out = new double[3];
        double[] scratch = new double[3];
        assertAllocationFree("GaussianCopula.sample per draw", i -> {
            c.sample(rnd, out, scratch);
            return out[0] + out[2];
        });
    }

    @Test
    void pairSortAllocatesNothingBeyondCallerArrays() throws Exception {
        double[] src = wave(256, 0, 1);
        double[] keys = new double[256];
        int[] values = new int[256];
        assertAllocationFree("Numerics.pairSort", i -> {
            for (int k = 0; k < 256; k++) {
                keys[k] = src[(k * 37 + i) & 255];
                values[k] = k;
            }
            Numerics.pairSort(keys, values);
            return keys[0] + values[255];
        });
    }
}
