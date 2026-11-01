package com.quantfinlib.alpha;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.data.PointInTimeUniverse;
import com.quantfinlib.screener.Fundamentals;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Statistical tests on noisy panels: distributions under the null, not
 * single deterministic paths where IC is identically 1.
 */
class AlphaStatisticsTest {

    /** iid normal log returns, 1% daily vol, unit volume; open = previous close. */
    static AlphaContext noisyPanel(long seed, int names, int bars) {
        SplittableRandom rng = new SplittableRandom(seed);
        Map<String, BarSeries> data = new HashMap<>();
        for (int s = 0; s < names; s++) {
            BarSeries.Builder b = BarSeries.builder(String.format("N%03d", s));
            double close = 100;
            for (int i = 0; i < bars; i++) {
                double open = close;
                close = open * Math.exp(0.01 * gaussian(rng));
                b.add(i * 86_400_000L, open, Math.max(open, close), Math.min(open, close),
                        close, 1_000_000);
            }
            data.put(String.format("N%03d", s), b.build());
        }
        return AlphaContext.of(data);
    }

    static double gaussian(SplittableRandom rng) {
        // Box-Muller: SplittableRandom has no nextGaussian in older JDKs' contract.
        double u = 1 - rng.nextDouble();
        double v = rng.nextDouble();
        return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
    }

    /** Scores that are pure noise, redrawn every {@code persistence} bars. */
    static AlphaFactor persistentNoise(long seed, int names, int bars, int persistence) {
        SplittableRandom rng = new SplittableRandom(seed ^ 0x5DEECE66DL);
        double[][] s = new double[bars][names];
        for (int t = 0; t < bars; t++) {
            for (int i = 0; i < names; i++) {
                s[t][i] = t % persistence == 0 ? gaussian(rng) : s[t - 1][i];
            }
        }
        return (ctx, index) -> s[index].clone();
    }

    @Test
    void nullFactorHacTStatIsStandardNormalAcrossSeedsWhileNaiveIsInflated() {
        // A signal with no predictive power that persists for 20 bars,
        // evaluated at a 10-bar horizon: overlapping windows plus
        // persistence make the IC series strongly autocorrelated.
        int seeds = 150;
        int hacRejects = 0;
        int naiveRejects = 0;
        double sum = 0;
        double sq = 0;
        for (int seed = 0; seed < seeds; seed++) {
            AlphaContext ctx = noisyPanel(seed, 25, 500);
            SignalEvaluator.Report r = SignalEvaluator.evaluate(ctx,
                    persistentNoise(seed, 25, 500, 20), 0, 10);
            sum += r.tStat();
            sq += r.tStat() * r.tStat();
            if (Math.abs(r.tStat()) > 1.96) {
                hacRejects++;
            }
            if (Math.abs(r.naiveTStat()) > 1.96) {
                naiveRejects++;
            }
        }
        double mean = sum / seeds;
        double sd = Math.sqrt(sq / seeds - mean * mean);
        // HAC t: centred, near unit spread, size near 5%. Newey-West is
        // mildly oversized in finite samples, hence the upper slack.
        assertEquals(0, mean, 0.3, "mean t");
        assertTrue(sd > 0.75 && sd < 1.45, "sd of HAC t " + sd);
        assertTrue(hacRejects <= 0.15 * seeds, "HAC size " + hacRejects + "/" + seeds);
        // The iid t treats ~490 overlapping ICs as independent: grossly oversized.
        assertTrue(naiveRejects >= 0.4 * seeds, "naive size " + naiveRejects + "/" + seeds);
    }

    @Test
    void shuffledSignalHasNoIcAndNullHitRateIsHalf() {
        // A real signal: scores are the next 5-bar return plus noise of equal size.
        AlphaContext ctx = noisyPanel(3, 40, 400);
        AlphaFactor real = (c, t) -> {
            double[] s = new double[c.symbolCount()];
            for (int i = 0; i < s.length; i++) {
                s[i] = t + 5 < c.bars() ? c.returnOver(i, t, t + 5) : Double.NaN;
            }
            return s;
        };
        SignalEvaluator.Report good = SignalEvaluator.evaluate(ctx, real, 0, 5);
        assertTrue(good.meanIc() > 0.95, "oracle IC " + good.meanIc());
        // Shuffle the scores across names every date: IC must vanish.
        SplittableRandom rng = new SplittableRandom(9);
        AlphaFactor shuffled = (c, t) -> {
            double[] s = real.scores(c, t);
            for (int i = s.length - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                double tmp = s[i];
                s[i] = s[j];
                s[j] = tmp;
            }
            return s;
        };
        SignalEvaluator.Report bad = SignalEvaluator.evaluate(ctx, shuffled, 0, 5);
        assertEquals(0, bad.meanIc(), 0.02);
        assertTrue(Math.abs(bad.tStat()) < 3, "t " + bad.tStat());
        assertEquals(0.5, bad.hitRate(), 0.03);
    }

    @Test
    void hitRateOfOneSignedScoresIsNotTheUpMoveFraction() {
        // Strong up-drift panel and a useless score that is always negative
        // (like -sigma for low volatility). Old raw-sign hit rate = fraction
        // of negative forward returns, about 0. Demeaned, it is about 0.5.
        SplittableRandom rng = new SplittableRandom(4);
        Map<String, BarSeries> data = new HashMap<>();
        for (int s = 0; s < 30; s++) {
            BarSeries.Builder b = BarSeries.builder("U" + s);
            double c = 100;
            for (int i = 0; i < 300; i++) {
                c *= Math.exp(0.02 + 0.005 * gaussian(rng));
                b.add(i, c, c, c, c, 1);
            }
            data.put("U" + s, b.build());
        }
        AlphaContext ctx = AlphaContext.of(data);
        SplittableRandom noise = new SplittableRandom(5);
        AlphaFactor negative = (c, t) -> {
            double[] s = new double[c.symbolCount()];
            for (int i = 0; i < s.length; i++) {
                s[i] = -1 - noise.nextDouble();
            }
            return s;
        };
        SignalEvaluator.Report r = SignalEvaluator.evaluate(ctx, negative, 0, 1);
        assertEquals(0.5, r.hitRate(), 0.03);
    }

    @Test
    void neweyWestMatchesHandComputation() {
        double[] x = {1, 2, 3, 4};
        // mean 2.5; deviations -1.5,-0.5,0.5,1.5
        // g0 = 5/4 = 1.25; g1 = (0.75 - 0.25 + 0.75)/4 = 0.3125
        // lag 1: omega = 1.25 + 2*(1/2)*0.3125 = 1.5625; se = sqrt(1.5625/4) = 0.625
        assertEquals(1.25, NeweyWest.longRunVariance(x, 0), 1e-15);
        assertEquals(1.5625, NeweyWest.longRunVariance(x, 1), 1e-15);
        assertEquals(0.625, NeweyWest.standardErrorOfMean(x, 1), 1e-15);
        assertEquals(2.5 / 0.625, NeweyWest.tStat(x, 1), 1e-12);
        assertEquals(0.25, NeweyWest.autocorrelation(x, 1), 1e-15);
    }

    @Test
    void neweyWestRecoversAr1LongRunVariance() {
        // AR(1) phi=0.6, unit innovations: long-run variance 1/(1-phi)^2 = 6.25.
        SplittableRandom rng = new SplittableRandom(17);
        int n = 200_000;
        double[] x = new double[n];
        for (int t = 1; t < n; t++) {
            x[t] = 0.6 * x[t - 1] + gaussian(rng);
        }
        double lrv = NeweyWest.longRunVariance(x, NeweyWest.automaticLag(x));
        assertEquals(6.25, lrv, 0.35);
    }

    @Test
    void olsRecoversExactCoefficientsAndClassicalErrors() {
        // y = 1 + 2 x exactly except two residuals; closed-form simple regression.
        double[] xs = {0, 1, 2, 3, 4};
        double[] y = {1.1, 2.9, 5.0, 7.1, 8.9};
        double[][] x = new double[5][];
        for (int i = 0; i < 5; i++) {
            x[i] = new double[]{1, xs[i]};
        }
        Ols.Fit fit = Ols.fit(x, y, 0);
        // slope = Sxy/Sxx = (7.8 + 2.1 + 0 + 2.1 + 7.8)/10 = 1.98; intercept = 5 - 1.98*2 = 1.04
        assertEquals(1.98, fit.coefficients()[1], 1e-12);
        assertEquals(1.04, fit.coefficients()[0], 1e-12);
        // residuals: 0.06, -0.12, 0.00, 0.12, -0.06 -> SSR = 0.036; sigma2 = 0.036/3
        double sigma2 = 0.036 / 3;
        assertEquals(Math.sqrt(sigma2 / 10), fit.standardErrors()[1], 1e-12);
        assertEquals(Math.sqrt(sigma2 * (1.0 / 5 + 4.0 / 10)), fit.standardErrors()[0], 1e-12);
        assertThrows(IllegalArgumentException.class,
                () -> Ols.fit(new double[][]{{1, 1}, {1, 1}, {1, 1}}, new double[]{1, 2, 3}, 0));
    }

    @Test
    void crossSectionalNullRejectsAStaticRealSignalThatTheDatePermutationCannot() {
        // A static characteristic that really predicts returns: each name's
        // drift is proportional to its score, plus noise. Date permutation
        // gives p near 1 (the ranking never varies); the cross-sectional
        // null rejects.
        SplittableRandom rng = new SplittableRandom(21);
        int names = 30;
        double[] score = new double[names];
        Map<String, BarSeries> data = new HashMap<>();
        for (int s = 0; s < names; s++) {
            score[s] = gaussian(rng);
        }
        for (int s = 0; s < names; s++) {
            BarSeries.Builder b = BarSeries.builder(String.format("S%02d", s));
            double c = 100;
            for (int i = 0; i < 400; i++) {
                c *= Math.exp(0.002 * score[s] + 0.01 * gaussian(rng));
                b.add(i, c, c, c, c, 1);
            }
            data.put(String.format("S%02d", s), b.build());
        }
        AlphaContext ctx = AlphaContext.of(data);
        AlphaFactor stat = (c, t) -> {
            double[] out = new double[names];
            for (int i = 0; i < names; i++) {
                out[i] = score[Integer.parseInt(c.symbols().get(i).substring(1))];
            }
            return out;
        };
        var xs = AlphaValidation.monteCarloRobustness(ctx, stat, 10, 0, 500, 1);
        var dates = AlphaValidation.monteCarloRobustness(ctx, stat, 10, 0, 500, 1,
                AlphaValidation.NullModel.DATE_PERMUTATION);
        var shift = AlphaValidation.monteCarloRobustness(ctx, stat, 10, 0, 500, 1,
                AlphaValidation.NullModel.CIRCULAR_SHIFT);
        assertTrue(xs.observedMeanIc() > 0.15, "IC " + xs.observedMeanIc());
        assertTrue(xs.pValue() < 0.01, "cross-sectional p " + xs.pValue());
        assertTrue(dates.pValue() > 0.5, "date permutation p " + dates.pValue());
        assertEquals(38, shift.trials()); // exact enumeration over 39 dates
        assertEquals(AlphaValidation.NullModel.CIRCULAR_SHIFT, shift.nullModel());
        // The cross-sectional null is centred on zero.
        assertEquals(0, xs.nullMean(), 0.01);
    }

    @Test
    void lagIcProfileRecoversTheDecayConstant() {
        // Returns r(t+h) = 0.01 * phi^(h-1) * s(t) + noise, with s an AR(1)
        // signal of coefficient phi: the IC against the one-bar return at lag
        // h decays like phi^(h-1). phi = 0.7 -> tau = -1/ln(0.7) = 2.80 bars.
        double phi = 0.7;
        int names = 60;
        int bars = 800;
        SplittableRandom rng = new SplittableRandom(33);
        double[][] s = new double[bars][names];
        for (int i = 0; i < names; i++) {
            s[0][i] = gaussian(rng);
        }
        for (int t = 1; t < bars; t++) {
            for (int i = 0; i < names; i++) {
                s[t][i] = phi * s[t - 1][i] + Math.sqrt(1 - phi * phi) * gaussian(rng);
            }
        }
        Map<String, BarSeries> data = new HashMap<>();
        for (int i = 0; i < names; i++) {
            BarSeries.Builder b = BarSeries.builder(String.format("D%02d", i));
            double c = 100;
            b.add(0, c, c, c, c, 1);
            for (int t = 1; t < bars; t++) {
                c *= 1 + 0.01 * s[t - 1][i] + 0.01 * gaussian(rng);
                b.add(t, c, c, c, c, 1);
            }
            data.put(String.format("D%02d", i), b.build());
        }
        AlphaContext ctx = AlphaContext.of(data);
        AlphaFactor f = (c, t) -> {
            double[] out = new double[names];
            for (int i = 0; i < names; i++) {
                out[i] = s[t][Integer.parseInt(c.symbols().get(i).substring(1))];
            }
            return out;
        };
        AlphaReport.LagDecay d = AlphaReport.lagIcProfile(ctx, f, 0, 8);
        assertTrue(d.meanIcs()[0] > d.meanIcs()[3], "IC must decay with lag");
        assertEquals(-1 / Math.log(phi), d.tauBars(), 0.6);
        assertTrue(d.format().contains("half-life"));
        // Signal autocorrelation at lag 1 is about phi (rank version of it).
        assertEquals(phi, d.signalAutocorrelation()[0], 0.05);
    }

    @Test
    void exponentialFitIsExactOnAnExactCurve() {
        double[] ic = new double[6];
        for (int h = 0; h < ic.length; h++) {
            ic[h] = 0.08 * Math.exp(-h / 3.0);
        }
        double[] fit = AlphaReport.fitExponential(ic);
        assertEquals(0.08, fit[0], 1e-6);
        assertEquals(3.0, fit[1], 1e-4);
    }

    @Test
    void forwardReturnIncludesTheDelistingReturn() {
        // Two names on a flat 100 price; B delists at bar 5 with -100%.
        Map<String, BarSeries> data = new HashMap<>();
        data.put("A", flat("A", 10, 100));
        data.put("B", flat("B", 10, 100));
        PointInTimeUniverse u = new PointInTimeUniverse()
                .addMembership("A", 0).addMembership("B", 0)
                .recordDelisting("B", 5L * 86_400_000L, -1.0);
        AlphaContext ctx = AlphaContext.of(data).withUniverse(u);
        int b = ctx.symbols().indexOf("B");
        // Alive at bar 3, dead inside (3, 8]: the holder lost everything.
        assertEquals(-1.0, ctx.returnOver(b, 3, 8), 1e-15);
        // Old behaviour read the forward-filled ghost bars: 100/100 - 1 = 0.
        assertEquals(0.0, ctx.series(b).close(8) / ctx.series(b).close(3) - 1, 0);
        // Dead at the start: no return at all.
        assertTrue(Double.isNaN(ctx.returnOver(b, 5, 8)));
        assertEquals(5, ctx.terminalIndex(b));
        // Forward returns used by IC carry it.
        assertEquals(-1.0, SignalEvaluator.forwardReturns(ctx, 4, 2)[b], 1e-15);
    }

    @Test
    void mergerReturnFollowsTheAcquirer() {
        Map<String, BarSeries> data = new HashMap<>();
        data.put("T", flat("T", 10, 40));
        double[] acq = {100, 100, 100, 100, 100, 110, 110, 120, 120, 120};
        data.put("ACQ", BarSeries.of("ACQ", acq));
        PointInTimeUniverse u = new PointInTimeUniverse()
                .addMembership("T", 0).addMembership("ACQ", 0)
                .recordMerger("T", 5L * 86_400_000L, 5.0, 0.5, "ACQ");
        AlphaContext ctx = AlphaContext.of(data).withUniverse(u);
        int t = ctx.symbols().indexOf("T");
        // At bar 7: 5 cash + 0.5 * 120 = 65 per share, from 40.
        assertEquals(65.0 / 40 - 1, ctx.returnOver(t, 2, 7), 1e-12);
    }

    @Test
    void contextRejectsMisalignedTimestamps() {
        Map<String, BarSeries> data = new HashMap<>();
        data.put("A", flat("A", 5, 10));
        BarSeries.Builder b = BarSeries.builder("B");
        for (int i = 0; i < 5; i++) {
            b.add((i + 1) * 86_400_000L, 10, 10, 10, 10, 1); // one bar late
        }
        data.put("B", b.build());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AlphaContext.of(data));
        assertTrue(e.getMessage().contains("timeline"));
    }

    @Test
    void fundamentalsHistoryIsPointInTimeWithAReportingLag() {
        long day = 86_400_000L;
        Fundamentals q1 = new Fundamentals(1e9, 10, 1, 1.0, 0.1, 0, 0.5);
        Fundamentals q2 = new Fundamentals(1e9, 20, 2, 2.0, 0.2, 0, 0.5);
        FundamentalsHistory h = new FundamentalsHistory()
                .addWithLag("A", 10 * day, 45 * day, q1)    // public at day 55
                .addWithLag("A", 100 * day, 45 * day, q2);  // public at day 145
        assertNull(h.asOf("A", 54 * day));
        assertSame(q1, h.asOf("A", 55 * day));
        assertSame(q1, h.asOf("A", 144 * day));
        assertSame(q2, h.asOf("A", 145 * day));
        assertNull(h.asOf("B", 145 * day));

        Map<String, BarSeries> data = new HashMap<>();
        data.put("A", flat("A", 200, 50));
        data.put("B", flat("B", 200, 50));
        AlphaContext ctx = AlphaContext.of(data).withFundamentalsHistory(h);
        int a = ctx.symbols().indexOf("A");
        assertTrue(Double.isNaN(Factors.value().scores(ctx, 54)[a]));
        // Earnings yield from eps over today's price, book yield 1/PB as recorded.
        assertEquals((1.0 / 50 + 1.0) / 2, Factors.value().scores(ctx, 60)[a], 1e-12);
        assertEquals((2.0 / 50 + 0.5) / 2, Factors.value().scores(ctx, 150)[a], 1e-12);
    }

    @Test
    void famaMacBethNeweyWestIsWiderThanIidForOverlappingReturns() {
        // Premium 0.01 per unit exposure, 10-bar overlapping forward returns.
        SplittableRandom rng = new SplittableRandom(8);
        int periods = 400;
        int assets = 40;
        int h = 10;
        double[][] eps = new double[periods + h][assets];
        for (double[] row : eps) {
            for (int a = 0; a < assets; a++) {
                row[a] = 0.02 * gaussian(rng);
            }
        }
        double[][][] x = new double[periods][assets][1];
        double[][] y = new double[periods][assets];
        double[] beta = new double[assets];
        for (int a = 0; a < assets; a++) {
            beta[a] = gaussian(rng);
        }
        double[] common = new double[periods + h];
        for (int t = 0; t < common.length; t++) {
            common[t] = 0.01 * gaussian(rng);
        }
        for (int t = 0; t < periods; t++) {
            for (int a = 0; a < assets; a++) {
                x[t][a][0] = beta[a];
                double sum = 0;
                for (int k = 1; k <= h; k++) {
                    sum += beta[a] * common[t + k] + eps[t + k][a];
                }
                y[t][a] = sum;
            }
        }
        FamaMacBeth.Result r = FamaMacBeth.fit(x, y, h, false);
        assertTrue(Math.abs(r.tStats()[0]) < Math.abs(r.naiveTStats()[0]) / 1.8,
                "HAC t " + r.tStats()[0] + " vs iid " + r.naiveTStats()[0]);
        assertTrue(r.hacLags()[0] >= h - 1);
    }

    @Test
    void calendarHolmFlagsRequireFamilyWiseSignificance() {
        double[] p = {0.01, 0.04, 0.03, 0.2, 0.012};
        boolean[] rej = CalendarAnomalies.holm(p, 0.05);
        // sorted: 0.01 (<= .05/5=.01 yes), 0.012 (<= .05/4=.0125 yes),
        // 0.03 (<= .05/3=.0167 no) -> stop.
        assertTrue(rej[0]);
        assertTrue(rej[4]);
        assertTrue(!rej[2] && !rej[1] && !rej[3]);
    }

    @Test
    void walkForwardWeightsFoldsAndReportsSelectionBias() {
        AlphaContext ctx = noisyPanel(12, 20, 400);
        List<AlphaFactor> noise = List.of(persistentNoise(1, 20, 400, 5),
                persistentNoise(2, 20, 400, 5), persistentNoise(3, 20, 400, 5),
                persistentNoise(4, 20, 400, 5));
        AlphaValidation.WalkForwardResult r = AlphaValidation.walkForward(ctx, noise, 5, 0, 100, 50);
        // Best-of-4 on noise looks positive in sample; the expected-max haircut
        // removes most of it, and OOS is centred on zero.
        assertTrue(r.meanInSampleIc() > 0, "IS " + r.meanInSampleIc());
        assertTrue(r.meanSelectionBias() > 0);
        assertTrue(Math.abs(r.outOfSampleTStat()) < 3);
        int oos = 0;
        for (AlphaValidation.Fold f : r.folds()) {
            oos += f.outOfSampleObservations();
        }
        assertEquals(oos, r.outOfSampleIcSeries().length);
    }

    @Test
    void sensitivityDropIsMeasuredFromTheBestParameter() {
        // ICs 0.10, 0.30, 0.25, 0.00: the worst adjacent jump is 0.25 (between
        // 0.25 and 0.00), but the best (0.30) only drops 0.20 to its worse neighbour.
        double[] ics = {0.10, 0.30, 0.25, 0.00};
        AlphaContext ctx = noisyPanel(1, 10, 60);
        List<AlphaFactor> sweep = new java.util.ArrayList<>();
        for (double ic : ics) {
            sweep.add(new AlphaFactor() {
                @Override
                public double[] scores(AlphaContext c, int t) {
                    // Score = forward return mixed so that the rank IC is the
                    // same at every date; exact values are checked below.
                    return blend(c, t, ic);
                }

                @Override
                public String name() {
                    return "ic" + ic;
                }
            });
        }
        AlphaValidation.SensitivityResult r = AlphaValidation.parameterSensitivity(ctx, sweep, 5, 0);
        double[] m = r.meanIcs();
        int best = 0;
        for (int i = 1; i < m.length; i++) {
            if (m[i] > m[best]) {
                best = i;
            }
        }
        assertEquals(sweep.get(best).name(), r.best());
        double worse = Double.POSITIVE_INFINITY;
        if (best > 0) {
            worse = Math.min(worse, m[best - 1]);
        }
        if (best < m.length - 1) {
            worse = Math.min(worse, m[best + 1]);
        }
        double expectedDrop = m[best] - worse;
        assertEquals(expectedDrop, r.worstNeighborDrop(), 1e-12);
        assertTrue(r.largestAdjacentJump() >= r.worstNeighborDrop());
    }

    /** Scores equal to the forward return for a fraction of names and reversed for the rest. */
    private static double[] blend(AlphaContext c, int t, double fraction) {
        double[] s = new double[c.symbolCount()];
        int keep = (int) Math.round(fraction * 10);
        for (int i = 0; i < s.length; i++) {
            double fwd = t + 5 < c.bars() ? c.returnOver(i, t, t + 5) : Double.NaN;
            s[i] = i < keep ? fwd : -fwd;
        }
        return s;
    }

    static BarSeries flat(String symbol, int bars, double price) {
        BarSeries.Builder b = BarSeries.builder(symbol);
        for (int i = 0; i < bars; i++) {
            b.add(i * 86_400_000L, price, price, price, price, 1_000);
        }
        return b.build();
    }
}
