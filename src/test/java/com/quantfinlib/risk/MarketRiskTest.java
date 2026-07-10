package com.quantfinlib.risk;

import com.quantfinlib.util.MathUtils;
import com.quantfinlib.volatility.Garch11;
import com.quantfinlib.volatility.GjrGarch11;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Market risk: dependence, PCA, copulas, VaR flavors, EVT, stress, FRTB, PLAT, GJR. */
class MarketRiskTest {

    // ------------------------------------------------------------------
    // Dependence
    // ------------------------------------------------------------------

    @Test
    void rankCorrelationsSurviveWhatWrecksPearson() {
        Random rnd = new Random(42);
        double[] x = new double[500];
        double[] y = new double[500];
        for (int i = 0; i < x.length; i++) {
            x[i] = rnd.nextGaussian();
            y[i] = Math.exp(3 * x[i]);      // monotone but violently nonlinear
        }
        assertEquals(1.0, Dependence.spearman(x, y), 1e-9,
                "a monotone transform is invisible to ranks");
        assertEquals(1.0, Dependence.kendallTau(x, y), 1e-9);
        assertTrue(RiskMetrics.correlation(x, y) < 0.75,
                "while Pearson is dragged around by the convexity");
        // Kendall on a hand-checkable case: one discordant pair among three.
        double tau = Dependence.kendallTau(new double[]{1, 2, 3}, new double[]{1, 3, 2});
        assertEquals(1.0 / 3, tau, 1e-12, "(2 concordant - 1 discordant) / 3 pairs");
        // The elliptical bridge at the corners and center.
        assertEquals(0, Dependence.pearsonFromKendall(0), 1e-12);
        assertEquals(1, Dependence.pearsonFromKendall(1), 1e-12);
        assertEquals(Math.sin(Math.PI * 0.25), Dependence.pearsonFromKendall(0.5), 1e-12);
    }

    // ------------------------------------------------------------------
    // PCA
    // ------------------------------------------------------------------

    @Test
    void pcaRecoversAnalyticEigenstructure() {
        // [[2,1],[1,2]]: eigenvalues 3 and 1, eigenvectors (1,1)/sqrt2, (1,-1)/sqrt2.
        Pca pca = new Pca(new double[][]{{2, 1}, {1, 2}});
        assertEquals(3, pca.eigenvalue(0), 1e-9);
        assertEquals(1, pca.eigenvalue(1), 1e-9);
        assertEquals(0.75, pca.explainedVariance(1), 1e-9, "3 of 4 total variance");
        double ratio = pca.loading(0, 0) / pca.loading(0, 1);
        assertEquals(1, ratio, 1e-6, "first component loads both factors equally");

        // A one-factor 'curve': all tenors driven by one level shock.
        double[][] level = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                level[i][j] = 1e-4;         // rank-1: pure level
            }
        }
        Pca curve = new Pca(level);
        assertEquals(1.0, curve.explainedVariance(1), 1e-9,
                "one real factor -> one component explains everything");
        assertThrows(IllegalArgumentException.class,
                () -> new Pca(new double[][]{{1, 0.5}, {0.2, 1}}));   // asymmetric

        // Currency-unit covariance (~1e8 entries): convergence thresholds
        // are relative to the matrix's scale, so this decomposes exactly
        // like its rate-fraction twin above.
        Pca big = new Pca(new double[][]{{2e8, 1e8}, {1e8, 2e8}});
        assertEquals(3e8, big.eigenvalue(0), 1e-2);
        assertEquals(1e8, big.eigenvalue(1), 1e-2);
    }

    // ------------------------------------------------------------------
    // Copulas
    // ------------------------------------------------------------------

    @Test
    void copulaSamplesCarryTheRequestedDependence() {
        double[][] corr = {{1, 0.7}, {0.7, 1}};
        GaussianCopula copula = new GaussianCopula(corr);
        Random rnd = new Random(7);
        int n = 20_000;
        double[] u1 = new double[n];
        double[] u2 = new double[n];
        double[] out = new double[2];
        double[] scratch = new double[2];
        for (int i = 0; i < n; i++) {
            copula.sample(rnd, out, scratch);
            u1[i] = out[0];
            u2[i] = out[1];
            assertTrue(out[0] > 0 && out[0] < 1 && out[1] > 0 && out[1] < 1);
        }
        // Spearman of a Gaussian copula: (6/pi)·asin(rho/2) ~ 0.683 at rho 0.7.
        double expected = 6 / Math.PI * Math.asin(0.7 / 2);
        assertEquals(expected, Dependence.spearman(u1, u2), 0.03,
                "the requested dependence came out the other side");

        // Tail dependence: joint-extreme frequency, t(3) vs Gaussian.
        int jointGauss = jointTail(copula, rnd, false, 0);
        int jointT = jointTail(copula, rnd, true, 3);
        assertTrue(jointT > jointGauss * 1.3,
                "t-copula extremes cluster: " + jointT + " vs " + jointGauss);

        assertThrows(IllegalArgumentException.class,
                () -> new GaussianCopula(new double[][]{{1, 1.2}, {1.2, 1}}),
                "a fake correlation matrix fails at the Cholesky, loudly");
    }

    @Test
    void tCopulaUniformsAreActuallyUniformInTheTails() {
        // The t-CDF against closed forms: df = 1 is Cauchy, df = 2 is
        // algebraic, df -> infinity is the normal.
        assertEquals(0.5 + Math.atan(0.5) / Math.PI, MathUtils.tCdf(0.5, 1), 1e-12);
        assertEquals(0.5 * (1 + 1 / Math.sqrt(3)), MathUtils.tCdf(1, 2), 1e-12);
        assertEquals(0.5, MathUtils.tCdf(0, 7), 1e-12);
        assertEquals(MathUtils.normCdf(-1.5), MathUtils.tCdf(-1.5, 1_000_000), 1e-4);

        // Marginal tail mass: P(U < 0.05) must BE 5% — the moment-matched
        // normal approximation this replaced put ~3.3% there at df = 3,
        // distorting exactly the quantiles a tail sampler exists for.
        GaussianCopula copula = new GaussianCopula(new double[][]{{1, 0.5}, {0.5, 1}});
        Random rnd = new Random(11);
        double[] out = new double[2];
        double[] scratch = new double[2];
        int below = 0;
        int n = 40_000;
        for (int i = 0; i < n; i++) {
            copula.sampleT(rnd, 3, out, scratch);
            if (out[0] < 0.05) {
                below++;
            }
        }
        assertEquals(0.05, below / (double) n, 0.005,
                "exact t-CDF => uniform marginals, tails included");
    }

    private static int jointTail(GaussianCopula copula, Random rnd, boolean t, int df) {
        double[] out = new double[2];
        double[] scratch = new double[2];
        int joint = 0;
        for (int i = 0; i < 20_000; i++) {
            if (t) {
                copula.sampleT(rnd, df, out, scratch);
            } else {
                copula.sample(rnd, out, scratch);
            }
            if (out[0] < 0.02 && out[1] < 0.02) {
                joint++;
            }
        }
        return joint;
    }

    // ------------------------------------------------------------------
    // VaR engine
    // ------------------------------------------------------------------

    @Test
    void theFourVarFlavorsAgreeWhereTheyShouldAndDivergeWhereTheyMust() {
        double[] exposures = {1_000_000, -500_000};
        double[][] cov = {{4e-4, 1e-4}, {1e-4, 2.25e-4}};   // 2%/1.5% daily, corr ~1/3

        double sigma = VarEngine.portfolioStdev(exposures, cov);
        double parametric = VarEngine.deltaNormalVar(exposures, cov, 0.99);
        assertEquals(MathUtils.normInv(0.99) * sigma, parametric, 1e-9,
                "delta-normal IS z times sigma");
        assertTrue(VarEngine.deltaNormalEs(exposures, cov, 0.99) > parametric,
                "ES sits beyond VaR, always");

        // Monte Carlo on the same linear book converges to delta-normal.
        var mc = VarEngine.monteCarloVar(exposures, cov, 0.99, 200_000, 42);
        assertEquals(parametric, mc.var(), 0.03 * parametric,
                "two routes, one linear book");
        assertTrue(mc.expectedShortfall() > mc.var());

        // Short gamma makes the left tail worse than delta-normal admits.
        double[][] shortGamma = {{-4_000_000, 0}, {0, 0}};
        double dgVar = VarEngine.deltaGammaVar(exposures, shortGamma, cov, 0.99);
        assertTrue(dgVar > parametric,
                "short-gamma tails: " + dgVar + " > " + parametric);
        double[][] longGamma = {{4_000_000, 0}, {0, 0}};
        assertTrue(VarEngine.deltaGammaVar(exposures, longGamma, cov, 0.99) < parametric,
                "long gamma cushions the same tail");

        // Delta-gamma ES (closed-form Cornish-Fisher tail mean): with
        // gamma = 0 it IS delta-normal ES, exactly; with short gamma it
        // exceeds both its own VaR and the Gaussian ES.
        assertEquals(VarEngine.deltaNormalEs(exposures, cov, 0.99),
                VarEngine.deltaGammaEs(exposures, new double[2][2], cov, 0.99), 1e-9,
                "gamma = 0 reduces exactly to delta-normal ES");
        double dgEs = VarEngine.deltaGammaEs(exposures, shortGamma, cov, 0.99);
        assertTrue(dgEs > dgVar, "ES sits beyond VaR for the gamma book too");
        assertTrue(dgEs > VarEngine.deltaNormalEs(exposures, cov, 0.99),
                "short gamma worsens the tail MEAN, not just the quantile");

        // Historical on a hand-built sample: the 95% loss of 100 scenarios
        // is the 95th-worst — exactly scenario row 94 by construction.
        double[][] history = new double[100][2];
        for (int s = 0; s < 100; s++) {
            history[s][0] = -(s + 1) * 1e-5;    // factor 1 loses linearly more
            history[s][1] = 0;
        }
        var hist = VarEngine.historicalVar(new double[]{1_000_000, 0}, history, 0.95);
        assertEquals(950_000 * 1e-3, hist.var(), 1e-6, "the 95th of 100 ordered losses");
        assertTrue(hist.expectedShortfall() > hist.var());
    }

    @Test
    void fullRevaluationSeesWhatSensitivitiesMiss() {
        // 100 one-factor scenarios, monotonically worse: x_s = -(s+1)·5e-4.
        double[][] scenarios = new double[100][1];
        for (int s = 0; s < 100; s++) {
            scenarios[s][0] = -(s + 1) * 5e-4;
        }
        // A LINEAR pricer reproduces historical simulation exactly.
        double delta = 1_000_000;
        var linear = VarEngine.fullRevaluationVar(scenarios,
                moves -> delta * moves[0], 0.99);
        var historical = VarEngine.historicalVar(new double[]{delta}, scenarios, 0.99);
        assertEquals(historical.var(), linear.var(), 1e-9,
                "a linear pricer IS historical simulation");
        assertEquals(historical.expectedShortfall(), linear.expectedShortfall(), 1e-9);

        // A SHORT-GAMMA pricer: full revaluation sees the curvature the
        // linear replay cannot. 99% row is s = 98 (x = -0.0495):
        // linear 49,500 + quadratic 0.5·4e7·0.0495² = 49,005 -> 98,505.
        double gamma = -4e7;
        var quadratic = VarEngine.fullRevaluationVar(scenarios,
                moves -> delta * moves[0] + 0.5 * gamma * moves[0] * moves[0], 0.99);
        assertEquals(98_505, quadratic.var(), 1e-6, "hand-computed, to the dollar");
        assertTrue(quadratic.var() > linear.var(),
                "short gamma makes every down scenario worse than delta admits");
        assertTrue(quadratic.expectedShortfall() > quadratic.var());

        // A pricer that cannot price a scenario is a modelling problem.
        assertThrows(IllegalArgumentException.class,
                () -> VarEngine.fullRevaluationVar(scenarios,
                        moves -> Double.NaN, 0.99));
        assertThrows(IllegalArgumentException.class,
                () -> VarEngine.fullRevaluationVar(new double[10][1],
                        moves -> 0, 0.99));
    }

    // ------------------------------------------------------------------
    // EVT
    // ------------------------------------------------------------------

    @Test
    void evtRecoversAPlantedParetoTailAndRefusesInfiniteMeans() {
        // Pareto(alpha = 4) exceedances: GPD shape = 1/4 exactly.
        Random rnd = new Random(42);
        double[] losses = new double[20_000];
        for (int i = 0; i < losses.length; i++) {
            losses[i] = Math.pow(rnd.nextDouble(), -1.0 / 4);   // Pareto >= 1
        }
        var fit = ExtremeValueTheory.fitPot(losses, 0.90);
        assertEquals(0.25, fit.shape(), 0.06, "the planted tail index: " + fit.shape());
        // The extrapolated 99.9% must exceed the fitted-threshold quantile
        // and the sample's own empirical 99.9% should be in its vicinity.
        double var999 = fit.var(0.999);
        double[] sorted = losses.clone();
        java.util.Arrays.sort(sorted);
        double empirical = sorted[(int) (0.999 * sorted.length)];
        assertEquals(empirical, var999, 0.25 * empirical,
                "tail extrapolation lands where the (large) sample says");
        assertTrue(fit.expectedShortfall(0.999) > var999);

        // A tail with no mean refuses to average itself.
        var infinite = new ExtremeValueTheory.GpdFit(1, 1.2, 0.5, 100, 1000);
        assertThrows(IllegalStateException.class, () -> infinite.expectedShortfall(0.999));
        assertThrows(IllegalArgumentException.class, () -> fit.var(0.5),
                "inside-sample quantiles belong to plain historical VaR");
    }

    @Test
    void evtIgnoresTiesAtTheThreshold() {
        // Discretized losses: ten observations sit EXACTLY at the threshold
        // value (P&L snapped to ticks does this) — counted as y = 0
        // exceedances they would deflate both PWMs and bias the shape.
        double[] losses = new double[100];
        for (int i = 0; i < 45; i++) {
            losses[i] = 1 + i;                       // bulk below the threshold
        }
        for (int i = 45; i < 55; i++) {
            losses[i] = 50.0;                        // tie block spanning the 0.5 quantile
        }
        for (int i = 55; i < 100; i++) {
            double p = (i - 55 + 0.5) / 45;          // GPD(xi=0.25, beta=2) quantiles above
            losses[i] = 50 + 2 / 0.25 * (Math.pow(1 - p, -0.25) - 1);
        }
        var fit = ExtremeValueTheory.fitPot(losses, 0.5);
        assertEquals(45, fit.exceedances(), "ties AT the threshold are not exceedances");
        assertEquals(0.25, fit.shape(), 0.15, "the planted tail survives ties: " + fit.shape());
        assertTrue(fit.var(0.99) > 50);
    }

    // ------------------------------------------------------------------
    // Stress testing
    // ------------------------------------------------------------------

    @Test
    void stressScenariosAndReverseStressCloseTheLoop() {
        double[] exposures = {2_000_000, -1_000_000, 500_000, 0, 300_000};
        double pnl = StressTester.scenarioPnl(exposures, StressTester.lehman2008());
        assertTrue(pnl < 0, "a long-equity short-rates book bleeds in Lehman week");

        // Delta-gamma: short gamma makes the same shock worse.
        double[][] gamma = new double[5][5];
        gamma[0][0] = -1e7;
        assertTrue(StressTester.scenarioPnl(exposures, gamma, StressTester.lehman2008()) < pnl);

        // The ladder is linear in the shock for a delta book.
        double[] ladder = StressTester.sensitivityLadder(exposures, 0, 0.10, 4);
        assertEquals(-200_000, ladder[0], 1e-6, "-10% x 2M");
        assertEquals(0, ladder[2], 1e-6);
        assertEquals(200_000, ladder[4], 1e-6);

        // The delta-gamma ladder shows the curvature the linear one hides:
        // short gamma costs 0.5 * 1e7 * 0.01 = 50k at BOTH +/-10% rungs.
        double[] dgLadder = StressTester.sensitivityLadder(exposures, gamma, 0, 0.10, 4);
        assertEquals(-250_000, dgLadder[0], 1e-6, "the down rung is WORSE short gamma");
        assertEquals(0, dgLadder[2], 1e-6);
        assertEquals(150_000, dgLadder[4], 1e-6);

        // Reverse stress: the returned shock loses EXACTLY the target.
        double[][] cov = {{4e-4, 1e-4}, {1e-4, 2.25e-4}};
        double[] expo2 = {1_000_000, -500_000};
        var reverse = StressTester.reverseStress(expo2, cov, 50_000);
        assertEquals(-50_000, StressTester.scenarioPnl(expo2, reverse.shocks()), 1e-6,
                "the breaking scenario breaks by exactly the asked amount");
        assertEquals(50_000 / VarEngine.portfolioStdev(expo2, cov),
                reverse.mahalanobisSigmas(), 1e-9, "and reports its own implausibility");
        assertThrows(IllegalArgumentException.class,
                () -> StressTester.reverseStress(new double[]{0, 0}, cov, 50_000));
    }

    // ------------------------------------------------------------------
    // FRTB ES, traffic light, PLAT
    // ------------------------------------------------------------------

    @Test
    void frtbArithmeticMatchesTheRegulationsFormulas() {
        // Cascade, hand-computed: ES {10, 8, 5} at horizons {10, 20, 60}:
        // sqrt(10² + (8·1)² + (5·2)²) = sqrt(100 + 64 + 100) = sqrt(264).
        double es = FrtbEs.liquidityHorizonEs(new double[]{10, 8, 5},
                new int[]{10, 20, 60});
        assertEquals(Math.sqrt(264), es, 1e-12);

        // The stressed ratio scales capital up, never down.
        assertEquals(150, FrtbEs.stressCalibratedEs(100, 30, 20), 1e-12, "ratio 1.5");
        assertEquals(100, FrtbEs.stressCalibratedEs(100, 10, 20), 1e-12,
                "a calm stressed period floors at 1, never discounts");

        // ES 97.5 of a known tail: losses 1..1000; VaR = 975th value, ES =
        // mean of 975..1000 (26 values) = 987.5 exactly.
        double[] losses = new double[1000];
        for (int i = 0; i < 1000; i++) {
            losses[i] = i + 1;
        }
        assertEquals(987.5, FrtbEs.es975(losses), 1e-9, "mean of 975..1000");

        assertEquals(FrtbEs.TrafficLight.GREEN, FrtbEs.TrafficLight.of(4));
        assertEquals(FrtbEs.TrafficLight.AMBER, FrtbEs.TrafficLight.of(5));
        assertEquals(FrtbEs.TrafficLight.AMBER, FrtbEs.TrafficLight.of(9));
        assertEquals(FrtbEs.TrafficLight.RED, FrtbEs.TrafficLight.of(10));
        assertThrows(IllegalArgumentException.class,
                () -> FrtbEs.liquidityHorizonEs(new double[]{10}, new int[]{20}));
    }

    @Test
    void platPassesAFaithfulModelAndFlagsAMissingFactor() {
        Random rnd = new Random(9);
        int days = 250;
        double[] hpl = new double[days];
        double[] rtplGood = new double[days];
        double[] rtplBad = new double[days];
        for (int d = 0; d < days; d++) {
            double factor1 = rnd.nextGaussian();
            double factor2 = rnd.nextGaussian();
            hpl[d] = 100 * factor1 + 80 * factor2;
            rtplGood[d] = 100 * factor1 + 80 * factor2 + 3 * rnd.nextGaussian();
            rtplBad[d] = 100 * factor1;                 // the model MISSES factor 2
        }
        var good = PnlAttribution.test(hpl, rtplGood);
        assertEquals(PnlAttribution.Zone.GREEN, good.zone(),
                "a faithful model passes: corr " + good.spearmanCorrelation()
                        + ", ks " + good.ksStatistic());
        var bad = PnlAttribution.test(hpl, rtplBad);
        assertTrue(bad.zone() != PnlAttribution.Zone.GREEN,
                "a missing risk factor cannot pass PLAT: corr "
                        + bad.spearmanCorrelation() + ", ks " + bad.ksStatistic());
        // Identical series: the degenerate perfect score.
        var perfect = PnlAttribution.test(hpl, hpl.clone());
        assertEquals(1.0, perfect.spearmanCorrelation(), 1e-9);
        assertEquals(0.0, perfect.ksStatistic(), 1e-9);
    }

    // ------------------------------------------------------------------
    // GJR-GARCH
    // ------------------------------------------------------------------

    @Test
    void gjrFindsTheLeverageEffectAndItsAbsenceHonestly() {
        // Simulate a GJR process with a strong planted leverage term.
        Random rnd = new Random(42);
        int n = 4_000;
        double omega = 2e-6;
        double alpha = 0.03;
        double gammaTrue = 0.12;
        double beta = 0.85;
        double[] r = new double[n];
        double h = omega / (1 - alpha - gammaTrue / 2 - beta);
        for (int t = 0; t < n; t++) {
            r[t] = Math.sqrt(h) * rnd.nextGaussian();
            double arch = r[t] < 0 ? alpha + gammaTrue : alpha;
            h = omega + arch * r[t] * r[t] + beta * h;
        }
        GjrGarch11.Params fit = GjrGarch11.fit(r);
        assertTrue(fit.gamma() > 0.05,
                "the planted asymmetry is found: gamma = " + fit.gamma());
        assertTrue(fit.persistence() < 1, "stationary fit");
        assertTrue(fit.logLikelihood() > Garch11.fit(r).logLikelihood(),
                "the model that generated the data likelihood-beats the symmetric one");

        // Symmetric GARCH data: the honest answer is gamma ~ 0.
        double[] sym = new double[n];
        double hs = 2e-6 / (1 - 0.08 - 0.9);
        for (int t = 0; t < n; t++) {
            sym[t] = Math.sqrt(hs) * rnd.nextGaussian();
            hs = 2e-6 + 0.08 * sym[t] * sym[t] + 0.9 * hs;
        }
        assertTrue(GjrGarch11.fit(sym).gamma() < 0.06,
                "no asymmetry to find: gamma = " + GjrGarch11.fit(sym).gamma());

        // Forecast mean-reverts toward the unconditional variance.
        double uncond = fit.unconditionalVariance();
        double near = GjrGarch11.forecastVariance(r, fit, 1);
        double far = GjrGarch11.forecastVariance(r, fit, 500);
        assertTrue(Math.abs(far - uncond) < Math.abs(near - uncond) + 1e-15,
                "long horizons forget today");
        assertThrows(IllegalArgumentException.class, () -> GjrGarch11.fit(new double[50]));
    }

    @Test
    void reviewRegressionsStayFixed() {
        // One NaN P&L day used to HANG ksStatistic in an infinite loop
        // (NaN == NaN is false, so neither pointer advanced) — throws now.
        double[] good = new double[20];
        double[] bad = new double[20];
        for (int i = 0; i < 20; i++) {
            good[i] = i;
            bad[i] = i;
        }
        bad[7] = Double.NaN;
        assertThrows(IllegalArgumentException.class,
                () -> PnlAttribution.ksStatistic(good, bad));
        assertThrows(IllegalArgumentException.class,
                () -> PnlAttribution.test(bad, good));

        // Small-unit covariances are valid Monte Carlo inputs: the pivot
        // floor is relative to the diagonal scale, so two 0.5bp-vol rate
        // factors no longer read as "not positive-definite".
        double v = 2.5e-9;
        double[][] tiny = {{v, 0.9 * v}, {0.9 * v, v}};
        double[] expo = {1_000_000, -500_000};
        double dn = VarEngine.deltaNormalVar(expo, tiny, 0.99);
        assertEquals(dn, VarEngine.monteCarloVar(expo, tiny, 0.99, 100_000, 7).var(),
                0.04 * dn, "the linear book agreement holds at any unit scale");

        // PCA at extreme magnitudes: norm accumulators must not overflow.
        Pca huge = new Pca(new double[][]{{2e155, 1e155}, {1e155, 2e155}});
        assertEquals(3e155, huge.eigenvalue(0), 1e144);
        assertEquals(1e155, huge.eigenvalue(1), 1e144);

        // A confidence typo (99.9 instead of 0.999) throws, never NaN.
        var fit = new ExtremeValueTheory.GpdFit(10, 0.3, 2, 100, 1000);
        assertThrows(IllegalArgumentException.class, () -> fit.var(99.9));
        assertTrue(Double.isFinite(fit.var(0.999)));

        // Aliased out/scratch would silently corrupt the dependence.
        GaussianCopula cop = new GaussianCopula(new double[][]{{1, 0.5}, {0.5, 1}});
        double[] u = new double[2];
        assertThrows(IllegalArgumentException.class,
                () -> cop.sample(new Random(1), u, u));

        // A NaN exposure fails at the stress gate, not as NaN per scenario.
        assertThrows(IllegalArgumentException.class, () -> StressTester.scenarioPnl(
                new double[]{1e6, Double.NaN, 0, 0, 0}, StressTester.lehman2008()));

        // An indefinite "covariance" (typo'd correlation 1.3) fails loudly
        // instead of silently simulating a clamped dependence structure.
        assertThrows(IllegalArgumentException.class, () -> MathUtils.cholesky(
                new double[][]{{1e-4, 1.3e-4}, {1.3e-4, 1e-4}}));
    }

    @Test
    void gjrGridIsNotAHiddenParameterCap() {
        // Low-persistence / high-ARCH data (short intraday windows and
        // regime breaks produce it): true alpha = 0.45, beta = 0.15 — a
        // fit box capped at alpha <= 0.30 can only creep to ~0.37 across
        // the refinement passes and pins at the edge silently.
        Random rnd = new Random(5);
        int n = 4_000;
        double[] hot = new double[n];
        double hv = 1e-4;
        for (int t = 0; t < n; t++) {
            hot[t] = Math.sqrt(hv) * rnd.nextGaussian();
            hv = 4e-5 + 0.45 * hot[t] * hot[t] + 0.15 * hv;
        }
        GjrGarch11.Params fit = GjrGarch11.fit(hot);
        assertTrue(fit.alpha() > 0.38,
                "the MLE reaches the true high-ARCH region: alpha = " + fit.alpha());
        assertTrue(fit.beta() < 0.35, "and the true low beta: " + fit.beta());
    }
}
