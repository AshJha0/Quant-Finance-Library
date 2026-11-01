package com.quantfinlib.alpha;

import com.quantfinlib.backtest.FillPolicy;
import com.quantfinlib.core.BarSeries;
import com.quantfinlib.data.PointInTimeUniverse;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact-arithmetic and canary tests for the alpha backtester and construction. */
class AlphaEngineTest {

    private static final long DAY = 86_400_000L;

    private static BarSeries ohlc(String symbol, double[][] openClose) {
        BarSeries.Builder b = BarSeries.builder(symbol);
        for (int i = 0; i < openClose.length; i++) {
            double o = openClose[i][0];
            double c = openClose[i][1];
            b.add(i * DAY, o, Math.max(o, c), Math.min(o, c), c, 1_000_000);
        }
        return b.build();
    }

    /** Two names, four bars, target books at t=0 and t=2, 10 bp commission, next-open fills. */
    @Test
    void twoAssetTwoRebalanceCaseMatchesHandArithmetic() {
        Map<String, BarSeries> data = new HashMap<>();
        data.put("A", ohlc("A", new double[][]{{100, 100}, {102, 104}, {104, 110}, {108, 99}}));
        data.put("B", ohlc("B", new double[][]{{50, 50}, {49, 48}, {48, 51}, {52, 52}}));
        AlphaContext ctx = AlphaContext.of(data);
        AlphaBacktester.Config cfg = new AlphaBacktester.Config(0, 2, 10, 0, 0, 0, 2, 252);
        AlphaBacktester.Result r = AlphaBacktester.run(ctx, (c, t) -> new double[]{t, -t}, cfg,
                (c, s, t) -> t == 0 ? new double[]{0.5, -0.5} : new double[]{-0.25, 0.25});

        // Bar 0 -> 1: nothing held overnight; buy A / short B at the opens
        // (102, 49), turnover 1.0 -> cost 0.001; then earn open->close:
        //   rA = 104/102 - 1, rB = 48/49 - 1, rp = 0.5 rA - 0.5 rB.
        double rA1 = 104.0 / 102 - 1;
        double rB1 = 48.0 / 49 - 1;
        double rp1 = 0.5 * rA1 - 0.5 * rB1;
        double e1 = 0.999 * (1 + rp1);
        double wA = 0.5 * (1 + rA1) / (1 + rp1);
        double wB = -0.5 * (1 + rB1) / (1 + rp1);
        // Bar 1 -> 2: flat overnight (104 -> 104, 48 -> 48), then 104 -> 110, 48 -> 51.
        double rA2 = 110.0 / 104 - 1;
        double rB2 = 51.0 / 48 - 1;
        double rp2 = wA * rA2 + wB * rB2;
        double e2 = e1 * (1 + rp2);
        wA = wA * (1 + rA2) / (1 + rp2);
        wB = wB * (1 + rB2) / (1 + rp2);
        // Bar 2 -> 3: overnight 110 -> 108, 51 -> 52 on the drifted book,
        // then trade to (-0.25, +0.25) against the DRIFTED weights.
        double rA3 = 108.0 / 110 - 1;
        double rB3 = 52.0 / 51 - 1;
        double rp3 = wA * rA3 + wB * rB3;
        double e3 = e2 * (1 + rp3);
        wA = wA * (1 + rA3) / (1 + rp3);
        wB = wB * (1 + rB3) / (1 + rp3);
        double traded = Math.abs(-0.25 - wA) + Math.abs(0.25 - wB);
        e3 *= 1 - traded * 0.001;
        double rp4 = -0.25 * (99.0 / 108 - 1) + 0.25 * 0;
        e3 *= 1 + rp4;

        assertArrayEquals(new double[]{1, e1, e2, e3}, r.netEquityCopy(), 1e-14);
        // Pinned values (computed independently in a separate script).
        assertEquals(1.0189879951980791, r.netEquity(1), 1e-13);
        assertEquals(1.0177887154861944, r.netEquity(2), 1e-13);
        assertEquals(1.0169979154411763, r.netEquity(3), 1e-13);
        assertEquals((1.0 + traded) / 2 / 2, r.meanTurnover(), 1e-14);
        assertEquals((1.0 + traded) * 0.001, r.commissionDrag(), 1e-15);
    }

    @Test
    void lookAheadCanaryIsCaughtByNextOpenFills() {
        // Overnight and intraday returns are independent noise. A factor that
        // peeks at the NEXT open (one bar of look-ahead) earns a fortune when
        // filled at today's close and nothing once it must wait for the open
        // it peeked at.
        SplittableRandom rng = new SplittableRandom(77);
        Map<String, BarSeries> data = new HashMap<>();
        for (int s = 0; s < 20; s++) {
            BarSeries.Builder b = BarSeries.builder("P" + s);
            double c = 100;
            for (int i = 0; i < 400; i++) {
                double o = c * Math.exp(0.01 * AlphaStatisticsTest.gaussian(rng));
                c = o * Math.exp(0.01 * AlphaStatisticsTest.gaussian(rng));
                b.add(i * DAY, o, Math.max(o, c), Math.min(o, c), c, 1_000_000);
            }
            data.put("P" + s, b.build());
        }
        AlphaContext ctx = AlphaContext.of(data);
        AlphaFactor peek = new AlphaFactor() {
            @Override
            public double[] scores(AlphaContext c, int t) {
                double[] out = new double[c.symbolCount()];
                for (int i = 0; i < out.length; i++) {
                    out[i] = t + 1 < c.bars()
                            ? c.series(i).open(t + 1) / c.series(i).close(t) - 1 : Double.NaN;
                }
                return out;
            }

            @Override
            public String name() {
                return "PEEK";
            }
        };
        AlphaBacktester.Config base = new AlphaBacktester.Config(1, 1, 0, 0, 0, 0, 2, 252);
        AlphaBacktester.Result atClose = AlphaBacktester.run(ctx, peek,
                base.withFillPolicy(FillPolicy.CLOSE),
                (c, s, t) -> PortfolioConstruction.zScoreWeights(s, 1.0));
        AlphaBacktester.Result atOpen = AlphaBacktester.run(ctx, peek, base,
                (c, s, t) -> PortfolioConstruction.zScoreWeights(s, 1.0));
        assertTrue(atClose.grossMetrics().sharpeRatio() > 10,
                "close fill Sharpe " + atClose.grossMetrics().sharpeRatio());
        assertTrue(Math.abs(atOpen.grossMetrics().sharpeRatio()) < 2.5,
                "next-open Sharpe " + atOpen.grossMetrics().sharpeRatio());
    }

    @Test
    void zeroReturnPanelProducesZeroPnlUnderEveryPolicy() {
        Map<String, BarSeries> data = new HashMap<>();
        for (int s = 0; s < 6; s++) {
            data.put("F" + s, AlphaStatisticsTest.flat("F" + s, 60, 10 + s));
        }
        AlphaContext ctx = AlphaContext.of(data);
        SplittableRandom rng = new SplittableRandom(1);
        AlphaFactor random = (c, t) -> {
            double[] s = new double[c.symbolCount()];
            for (int i = 0; i < s.length; i++) {
                s[i] = rng.nextDouble();
            }
            return s;
        };
        for (FillPolicy p : FillPolicy.values()) {
            AlphaBacktester.Result r = AlphaBacktester.run(ctx, random,
                    new AlphaBacktester.Config(25, 3, 0, 0, 0, 0, 20, 252).withFillPolicy(p));
            for (double e : r.grossEquityCopy()) {
                assertEquals(1.0, e, 1e-15);
            }
            for (double e : r.netEquityCopy()) {
                assertEquals(1.0, e, 1e-15);
            }
        }
    }

    @Test
    void driftingBookDoesNotEarnTheDailyRebalancingPremium() {
        // Two names that alternate +10% / -10% out of phase. A fixed 50/50
        // long book rebalanced every bar for free earns the volatility
        // pumping premium; a buy-and-hold book that drifts does not.
        Map<String, BarSeries> data = new HashMap<>();
        double[] a = new double[41];
        double[] b = new double[41];
        a[0] = 100;
        b[0] = 100;
        for (int i = 1; i < 41; i++) {
            a[i] = a[i - 1] * (i % 2 == 1 ? 1.1 : 1 / 1.1);
            b[i] = b[i - 1] * (i % 2 == 1 ? 1 / 1.1 : 1.1);
        }
        data.put("A", BarSeries.of("A", a));
        data.put("B", BarSeries.of("B", b));
        AlphaContext ctx = AlphaContext.of(data);
        AlphaBacktester.Result r = AlphaBacktester.run(ctx, (c, t) -> new double[]{1, 1},
                new AlphaBacktester.Config(0, 1000, 0, 0, 0, 0, 2, 252)
                        .withFillPolicy(FillPolicy.CLOSE),
                (c, s, t) -> new double[]{0.5, 0.5});
        // Buy-and-hold: final value = 0.5 a[40]/a[0] + 0.5 b[40]/b[0] = 1.
        assertEquals(1.0, r.netEquity(40), 1e-12);
        // The old fixed-weight arithmetic compounds (1 + 0.5*0.1 - 0.5*(1-1/1.1))
        // every bar, which is above 1 per pair of bars.
        double perBar = 1 + 0.5 * 0.1 + 0.5 * (1 / 1.1 - 1);
        assertTrue(Math.pow(perBar, 40) > 1.03);
    }

    @Test
    void heldPositionEarnsTheDelistingReturnAndBecomesCash() {
        Map<String, BarSeries> data = new HashMap<>();
        data.put("A", AlphaStatisticsTest.flat("A", 10, 100));
        data.put("B", AlphaStatisticsTest.flat("B", 10, 100));
        PointInTimeUniverse u = new PointInTimeUniverse()
                .addMembership("A", 0).addMembership("B", 0)
                .recordDelisting("B", 5 * DAY, -0.30);
        AlphaContext ctx = AlphaContext.of(data).withUniverse(u);
        int bIdx = ctx.symbols().indexOf("B");
        AlphaBacktester.Result r = AlphaBacktester.run(ctx, (c, t) -> new double[]{1, 1},
                new AlphaBacktester.Config(0, 1000, 0, 0, 0, 0, 2, 252)
                        .withFillPolicy(FillPolicy.CLOSE),
                (c, s, t) -> {
                    double[] w = new double[2];
                    w[bIdx] = 0.5;
                    return w;
                });
        // 50% in B; B delists at bar 5 at -30% of its last close: equity 0.85, then flat.
        assertEquals(1.0, r.netEquity(4), 1e-15);
        assertEquals(0.85, r.netEquity(5), 1e-15);
        assertEquals(0.85, r.netEquity(9), 1e-15);
    }

    @Test
    void financingChargesBorrowAndPaysCashInterest() {
        Map<String, BarSeries> data = new HashMap<>();
        data.put("A", AlphaStatisticsTest.flat("A", 11, 100));
        data.put("B", AlphaStatisticsTest.flat("B", 11, 100));
        AlphaContext ctx = AlphaContext.of(data);
        // Long A 0.5, short B 0.5: cash = 1 - 0 = 1 (short proceeds included).
        // Per bar: +1 * 2.52%/252 - 0.5 * 252bp/252 = 0.0001 - 0.00005.
        AlphaBacktester.Result r = AlphaBacktester.run(ctx, (c, t) -> new double[]{1, 1},
                new AlphaBacktester.Config(0, 1000, 0, 0, 0, 0, 2, 252)
                        .withFillPolicy(FillPolicy.CLOSE).withFinancing(252, 0.0252, 0.05),
                (c, s, t) -> new double[]{0.5, -0.5});
        double perBar = 1 + 0.0001 - 0.00005;
        assertEquals(Math.pow(perBar, 10), r.netEquity(10), 1e-13);
        assertEquals(1.0, r.grossEquity(10), 1e-15);
        assertTrue(r.financingDrag() < 0, "the book earned net interest");
    }

    @Test
    void participationCapSpreadsTheTradeAndCarriesTheRemainder() {
        // ADV 1,000 shares at 10: 10% participation = 1,000 currency per bar.
        // Capital 10,000 wants 50% (5,000) in A: five bars of 0.1 each.
        Map<String, BarSeries> data = new HashMap<>();
        data.put("A", AlphaStatisticsTest.flat("A", 40, 10));
        data.put("B", AlphaStatisticsTest.flat("B", 40, 10));
        AlphaContext ctx = AlphaContext.of(data);
        AlphaBacktester.Config cfg = new AlphaBacktester.Config(25, 1000, 0, 0, 0, 10_000, 20, 252)
                .withMaxParticipation(0.10);
        AlphaBacktester.Result r = AlphaBacktester.run(ctx, (c, t) -> new double[]{1, 1}, cfg,
                (c, s, t) -> new double[]{0.5, 0});
        // Flat prices: impact is zero (zero vol), equity stays at 1. After each
        // of the first four fills the L1 gap is 0.4, 0.3, 0.2, 0.1; the fifth
        // fill completes the target and ends the tracking period.
        assertEquals(1.0, r.netEquity(r.netEquityLength() - 1), 1e-15);
        int bars = r.netEquityLength() - 1;
        double expectedGap = (0.4 + 0.3 + 0.2 + 0.1) / 2 / 4;
        assertEquals(expectedGap, r.meanTrackingGap(), 1e-12);
        assertTrue(bars > 5);
        assertEquals(0.25, r.meanTurnover(), 1e-12);
    }

    @Test
    void zScoreBookStaysDollarNeutralAfterClampAndCap() {
        // One huge outlier: the +-3 clamp and a 10% cap both bind asymmetrically.
        double[] scores = {8, 1, 0.9, 0.8, 0.7, 0.6, -0.6, -0.7, -0.8, -0.9, -1.0, -1.1};
        PortfolioConstruction.Book book = PortfolioConstruction.zScoreBook(scores, 1.0, 0.10);
        assertEquals(0, book.net(), 1e-12);
        assertEquals(1.0, book.gross(), 1e-12);
        for (double w : book.weightsCopy()) {
            assertTrue(Math.abs(w) <= 0.10 + 1e-12);
        }
        assertFalse(book.grossShortfall());
        // Six names, 5% cap, gross 1.0 cannot be deployed (at most 0.15 per side).
        PortfolioConstruction.Book thin = PortfolioConstruction.zScoreBook(
                new double[]{3, 2, 1, -1, -2, -3}, 1.0, 0.05);
        assertEquals(0.30, thin.gross(), 1e-12);
        assertTrue(thin.grossShortfall());
    }

    @Test
    void waterFillingCapReachesTheGrossTarget() {
        double[] w = PortfolioConstruction.capWeights(new double[]{0.6, 0.2, 0.1, 0.1}, 0.3, 1.0);
        // 0.6 -> 0.3; the remaining 0.7 is spread over 0.2/0.1/0.1 pro rata
        // (0.35, 0.175, 0.175) -> 0.35 breaches -> capped; 0.4 over 0.1/0.1.
        assertArrayEquals(new double[]{0.3, 0.3, 0.2, 0.2}, w, 1e-12);
    }

    @Test
    void jointNeutralizationSatisfiesAllConstraintsAtOnce() {
        double[] w = {0.3, -0.1, 0.2, -0.4, 0.25, -0.05};
        String[] sectors = {"X", "X", "Y", "Y", "Z", "Z"};
        double[] beta = {1.2, 0.8, 1.0, 1.5, 0.6, 1.1};
        double[][] rows = new double[4][];
        double[][] sec = PortfolioConstruction.sectorRows(sectors);
        System.arraycopy(sec, 0, rows, 0, 3);
        rows[3] = beta;
        double[] n = PortfolioConstruction.neutralize(w, rows);
        for (double[] row : rows) {
            assertEquals(0, com.quantfinlib.util.MathUtils.dot(row, n), 1e-12);
        }
        // Sequential neutralisation leaves a sector exposure behind.
        double[] seq = PortfolioConstruction.betaNeutralize(
                PortfolioConstruction.sectorNeutralize(w, sectors), beta);
        assertTrue(Math.abs(com.quantfinlib.util.MathUtils.dot(sec[0], seq)) > 1e-3);
    }

    @Test
    void optimizerMatchesClosedFormWhenUnconstrained() {
        // kappa = 0, no equality, loose boxes: w = Sigma^-1 alpha / (2 lambda).
        double[] alpha = {0.02, -0.01, 0.005};
        double[][] cov = {{0.04, 0.01, 0.0}, {0.01, 0.09, 0.02}, {0.0, 0.02, 0.0625}};
        double[] expected = com.quantfinlib.util.MathUtils.solveLinear(cov, alpha);
        for (int i = 0; i < 3; i++) {
            expected[i] /= 2 * 2.0;
        }
        double[] inf = {10, 10, 10};
        TurnoverAwareOptimizer.Result r = TurnoverAwareOptimizer.optimize(alpha, cov,
                new double[3], new double[0][], inf,
                new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                        Double.POSITIVE_INFINITY},
                TurnoverAwareOptimizer.Settings.of(2.0, 0));
        assertTrue(r.converged());
        assertArrayEquals(expected, r.weightsCopy(), 1e-7);
    }

    @Test
    void optimizerTurnoverPenaltyCreatesANoTradeBandAndConstraintsHold() {
        double[] alpha = {0.010, 0.008, -0.004, -0.012};
        double[][] cov = new double[4][4];
        for (int i = 0; i < 4; i++) {
            cov[i][i] = 0.04;
        }
        double[] prev = {0.1, 0.1, -0.1, -0.1};
        double[] caps = {0.15, 0.15, 0.15, 0.15};
        double[] trade = {1, 1, 1, 1};
        double[][] dollar = {{1, 1, 1, 1}};
        TurnoverAwareOptimizer.Result free = TurnoverAwareOptimizer.optimize(alpha, cov, prev,
                dollar, caps, trade, TurnoverAwareOptimizer.Settings.of(1.0, 0));
        TurnoverAwareOptimizer.Result sticky = TurnoverAwareOptimizer.optimize(alpha, cov, prev,
                dollar, caps, trade, TurnoverAwareOptimizer.Settings.of(1.0, 0.05));
        assertTrue(free.converged() && sticky.converged());
        assertTrue(free.equalityResidual() < 1e-6 && sticky.equalityResidual() < 1e-6);
        for (double w : free.weightsCopy()) {
            assertTrue(Math.abs(w) <= 0.15 + 1e-12);
        }
        // A penalty larger than any marginal alpha keeps the old book.
        assertArrayEquals(prev, sticky.weightsCopy(), 1e-6);
        assertTrue(free.turnover() > 0.01);
        // Optimality check of the free solution by perturbation along the
        // dollar-neutral directions: no feasible small move improves it.
        double base = free.objective();
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                if (i == j) {
                    continue;
                }
                double[] w = free.weightsCopy().clone();
                w[i] += 1e-4;
                w[j] -= 1e-4;
                if (Math.abs(w[i]) > 0.15 || Math.abs(w[j]) > 0.15) {
                    continue;
                }
                double obj = 0;
                for (int k = 0; k < 4; k++) {
                    obj += alpha[k] * w[k] - 0.04 * w[k] * w[k];
                }
                assertTrue(obj <= base + 1e-10, "move " + i + "->" + j + " improves");
            }
        }
    }

    @Test
    void factorReturnsAreBuyAndHoldLongShort() {
        Map<String, BarSeries> data = new HashMap<>();
        data.put("A", BarSeries.of("A", new double[]{10, 11, 12.1, 12.1}));
        data.put("B", BarSeries.of("B", new double[]{10, 10, 10, 10}));
        data.put("C", BarSeries.of("C", new double[]{10, 9, 9, 9.9}));
        data.put("D", BarSeries.of("D", new double[]{10, 10, 10, 10}));
        AlphaContext ctx = AlphaContext.of(data);
        // Scores rank A > B > D > C at t=0: top half {A? ...}; use 2 quantiles
        // with explicit scores A=4, B=3, D=2, C=1 -> long {B, A}, short {C, D}.
        AlphaFactor f = (c, t) -> new double[]{4, 3, 1, 2};
        double[] r = FactorReturns.longShort(ctx, f, 0, 2, 10);
        // t=0: long leg (A+B) 20 -> 21 = +5%; short leg (C+D) 20 -> 19 = -5%.
        assertEquals(0.10, r[0], 1e-12);
        // t=1: long 21 -> 22.1; short 19 -> 19.
        assertEquals(22.1 / 21 - 1, r[1], 1e-12);
        // t=2: long 22.1 -> 22.1; short 19 -> 19.9.
        assertEquals(0 - (19.9 / 19 - 1), r[2], 1e-12);
    }
}
