package com.quantfinlib.risk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactorRiskModelTest {

    static final int N = 400;
    static final int T = 600;
    static final int S = 4;
    static final int K = 2;
    static final double[] FACTOR_VOL = {0.010, 0.006, 0.005, 0.004, 0.003, 0.004, 0.003};

    /** A panel generated from a known factor model. */
    static final class Panel {
        double[][] returns = new double[T][N];
        double[][][] styles = new double[T][K][N];
        double[][] caps = new double[T][N];
        int[] sectors = new int[N];
        double[][] trueF = new double[T][1 + S + K];
        double[] specVol = new double[N];
        double[][] z = new double[K][];
    }

    static Panel panel(long seed, double volScaleLast60) {
        return panel(seed, volScaleLast60, 1);
    }

    static Panel panel(long seed, double volScaleLast60, double specScale) {
        SplittableRandom rnd = new SplittableRandom(seed);
        Panel p = new Panel();
        double[] cap = new double[N];
        double[][] raw = new double[K][N];
        boolean[] live = new boolean[N];
        for (int i = 0; i < N; i++) {
            cap[i] = Math.exp(22 + 1.5 * gauss(rnd));
            p.sectors[i] = i % S;
            p.specVol[i] = specScale * (0.01 + 0.02 * rnd.nextDouble());
            live[i] = true;
            for (int k = 0; k < K; k++) {
                raw[k][i] = gauss(rnd);
            }
        }
        for (int k = 0; k < K; k++) {
            p.z[k] = FactorRiskModel.standardize(raw[k], cap, live, 3.0);
        }
        double[] cs = new double[S];
        double total = 0;
        for (int i = 0; i < N; i++) {
            cs[p.sectors[i]] += cap[i];
            total += cap[i];
        }
        for (int t = 0; t < T; t++) {
            double scale = t >= T - 60 ? volScaleLast60 : 1;
            double[] f = p.trueF[t];
            for (int a = 0; a < f.length; a++) {
                f[a] = scale * FACTOR_VOL[a] * gauss(rnd);
            }
            double m = 0;
            for (int s = 0; s < S; s++) {
                m += cs[s] / total * f[1 + s];
            }
            for (int s = 0; s < S; s++) {
                f[1 + s] -= m; // impose the cap-weighted constraint on the truth
            }
            for (int i = 0; i < N; i++) {
                double r = f[0] + f[1 + p.sectors[i]];
                for (int k = 0; k < K; k++) {
                    r += p.z[k][i] * f[1 + S + k];
                }
                p.returns[t][i] = r + scale * p.specVol[i] * gauss(rnd);
                p.caps[t][i] = cap[i];
                for (int k = 0; k < K; k++) {
                    p.styles[t][k][i] = raw[k][i];
                }
            }
        }
        return p;
    }

    static double gauss(SplittableRandom r) {
        double u = r.nextDouble();
        double v = r.nextDouble();
        return Math.sqrt(-2 * Math.log(1 - u)) * Math.cos(2 * Math.PI * v);
    }

    static FactorRiskModel fit(Panel p, FactorRiskModel.Settings s) {
        return FactorRiskModel.estimate(p.returns, p.styles, List.of("SIZE", "MOM"), p.sectors,
                List.of("A", "B", "C", "D"), p.caps, s);
    }

    static final FactorRiskModel.Settings FLAT =
            new FactorRiskModel.Settings(100_000, 0, 0, 100_000, 0, 5, 3.0);

    @Test
    void recoversFactorReturnsExactlyWithoutSpecificNoise() {
        Panel p = panel(1, 1, 0);
        FactorRiskModel m = fit(p, FLAT);
        assertEquals(List.of("MARKET", "A", "B", "C", "D", "SIZE", "MOM"), m.factorNames());
        for (int t = 0; t < T; t += 13) {
            for (int a = 0; a < m.factorCount(); a++) {
                assertEquals(p.trueF[t][a], m.factorReturn(t, a), 1e-12, "t=" + t + " a=" + a);
            }
        }
        // the covariance is then the EWMA (here flat) second moment of the true returns
        for (int a = 0; a < 7; a++) {
            double ss = 0;
            double w = 1;
            double ws = 0;
            double d = Math.pow(0.5, 1.0 / 100_000);
            for (int t = T - 1; t >= 0; t--, w *= d) {
                ss += w * p.trueF[t][a] * p.trueF[t][a];
                ws += w;
            }
            assertEquals(ss / ws, m.factorCovariance(a, a), 1e-12 * ss / ws + 1e-20);
        }
    }

    @Test
    void recoversStyleReturnsWithinTheWlsStandardError() {
        Panel p = panel(1, 1);
        FactorRiskModel m = fit(p, FLAT);
        // Style returns: WLS standard error ~ specific vol (~2%) / sqrt(effective N ~ 230)
        // ~ 0.0014 per period. Require the RMSE to be below 0.002 and the correlation with
        // the truth above 0.8 for both styles (vols 0.4% and 0.3%).
        for (int a = 5; a < 7; a++) {
            double se = 0;
            double sxy = 0;
            double sxx = 0;
            double syy = 0;
            for (int t = 0; t < T; t++) {
                double e = m.factorReturn(t, a) - p.trueF[t][a];
                se += e * e;
                sxy += m.factorReturn(t, a) * p.trueF[t][a];
                sxx += m.factorReturn(t, a) * m.factorReturn(t, a);
                syy += p.trueF[t][a] * p.trueF[t][a];
            }
            assertTrue(Math.sqrt(se / T) < 0.002, "style " + a + " RMSE " + Math.sqrt(se / T));
            // expected corr = vol / sqrt(vol^2 + se^2) ~ 0.9 for the 0.3% style
            assertTrue(sxy / Math.sqrt(sxx * syy) > 0.8, "corr " + sxy / Math.sqrt(sxx * syy));
        }
        // market factor: under the constraint it is close to the cap-weighted mean, whose
        // effective N is N / exp(1.5^2) ~ 42, so noise ~ 0.021 / sqrt(42) ~ 0.0033
        double se = 0;
        for (int t = 0; t < T; t++) {
            double e = m.factorReturn(t, 0) - p.trueF[t][0];
            se += e * e;
        }
        assertTrue(Math.sqrt(se / T) < 0.005, "market RMSE " + Math.sqrt(se / T));
    }

    @Test
    void sectorReturnsSatisfyTheCapWeightedConstraint() {
        Panel p = panel(2, 1);
        FactorRiskModel m = fit(p, FLAT);
        double[] cs = new double[S];
        for (int i = 0; i < N; i++) {
            cs[p.sectors[i]] += p.caps[0][i];
        }
        for (int t = 0; t < T; t += 37) {
            double c = 0;
            for (int s = 0; s < S; s++) {
                c += cs[s] * m.factorReturn(t, 1 + s);
            }
            assertEquals(0, c / 1e12, 1e-12);
        }
    }

    @Test
    void recoversFactorAndSpecificVariances() {
        Panel p = panel(3, 1);
        FactorRiskModel m = fit(p, FLAT);
        // Estimated factor variance = true variance + WLS estimation-error variance; the
        // latter is measured directly from the estimated-minus-true series. Sampling sd of a
        // variance with T = 600 is sqrt(2/600) ~ 6%; allow 15%.
        for (int a = 0; a < 7; a++) {
            double err = 0;
            for (int t = 0; t < T; t++) {
                double e = m.factorReturn(t, a) - p.trueF[t][a];
                err += e * e / T;
            }
            double tru = FACTOR_VOL[a] * FACTOR_VOL[a];
            if (a >= 1 && a <= S) {
                continue; // sector truths are shifted by the constraint; covered above
            }
            assertEquals(1, m.factorCovariance(a, a) / (tru + err), 0.15, "factor " + a);
        }
        // Specific variance without shrinkage: median ratio near 1 (dof loss is P/N ~ 2%).
        double[] ratio = new double[N];
        for (int i = 0; i < N; i++) {
            ratio[i] = m.specificVariance(i) / (p.specVol[i] * p.specVol[i]);
        }
        java.util.Arrays.sort(ratio);
        assertEquals(1, ratio[N / 2], 0.05);
        assertTrue(ratio[N / 20] > 0.8 && ratio[N - N / 20] < 1.2);
    }

    @Test
    void covarianceIsPsdAndDecompositionSumsToTotal() {
        Panel p = panel(4, 1);
        FactorRiskModel m = fit(p, FactorRiskModel.Settings.defaults());
        double[][] f = m.factorCovarianceCopy();
        assertTrue(PsdMatrices.symmetricEigen(f).values(f.length - 1) >= -1e-15);
        double[][] v = m.covariance();
        SplittableRandom rnd = new SplittableRandom(9);
        double[] w = new double[N];
        for (int i = 0; i < N; i++) {
            w[i] = (rnd.nextDouble() - 0.4) / N;
        }
        FactorRiskModel.Decomposition d = m.decompose(w);
        double wvw = 0;
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                wvw += w[i] * v[i][j] * w[j];
            }
        }
        assertEquals(wvw, d.totalVariance(), 1e-12 * wvw);
        double sp = 0;
        for (double x : d.percentCopy()) {
            sp += x;
        }
        assertEquals(1, sp, 1e-10);
        double sf = d.specificContribution();
        for (double x : d.factorContributionCopy()) {
            sf += x;
        }
        assertEquals(1, sf, 1e-10);
        assertEquals(d.factorVariance() + d.specificVariance(), d.totalRisk() * d.totalRisk(), 1e-15);
        // marginal contribution against a central finite difference
        int i = 17;
        double h = 1e-6;
        double[] up = w.clone();
        double[] dn = w.clone();
        up[i] += h;
        dn[i] -= h;
        double fd = (m.decompose(up).totalRisk() - m.decompose(dn).totalRisk()) / (2 * h);
        assertEquals(fd, d.marginal(i), 1e-6 * Math.abs(fd) + 1e-12);
        // Euler: sum of w_i * marginal_i = sigma
        double euler = 0;
        for (int j = 0; j < N; j++) {
            euler += w[j] * d.marginal(j);
        }
        assertEquals(d.totalRisk(), euler, 1e-12);
        assertEquals(d, m.decompose(w));
        assertEquals(d.hashCode(), m.decompose(w).hashCode());
        assertTrue(d.toString().startsWith("Decomposition["));
        assertEquals(m.factorCount(), d.exposureLength());
        assertEquals(m.factorCount(), d.factorNames().size());
    }

    @Test
    void neweyWestMatchesBartlettLongRunVarianceOfAnAr1() {
        double phi = 0.5;
        int n = 200_000;
        SplittableRandom rnd = new SplittableRandom(5);
        double[][] f = new double[n][1];
        double x = 0;
        for (int t = 0; t < n; t++) {
            x = phi * x + gauss(rnd);
            f[t][0] = x;
        }
        double g0 = 1 / (1 - phi * phi);
        int lags = 3;
        double expected = g0;
        for (int l = 1; l <= lags; l++) {
            expected += 2 * (1 - l / (lags + 1.0)) * g0 * Math.pow(phi, l);
        }
        double got = FactorRiskModel.factorCovariance(f, 10_000_000, lags)[0][0];
        assertEquals(expected, got, 0.03 * expected);
        assertEquals(g0, FactorRiskModel.factorCovariance(f, 10_000_000, 0)[0][0], 0.03 * g0);
    }

    @Test
    void regimeAdjustmentIsNeutralForStableVolAndScalesUpAfterAShock() {
        FactorRiskModel calm = fit(panel(6, 1), FactorRiskModel.Settings.defaults());
        assertEquals(1, calm.regimeMultiplier(), 0.15);
        FactorRiskModel shocked = fit(panel(6, 3), FactorRiskModel.Settings.defaults());
        assertTrue(shocked.regimeMultiplier() > 1.5, "lambda " + shocked.regimeMultiplier());
        assertEquals(1, fit(panel(6, 3), FLAT).regimeMultiplier());
    }

    @Test
    void bayesianShrinkageFollowsTheUse4Formula() {
        // two names, one bucket, equal caps: mean = (s1+s2)/2, delta = |s1-s2|/2
        double[][] u = new double[50][2];
        for (int t = 0; t < 50; t++) {
            u[t][0] = t % 2 == 0 ? 0.01 : -0.01;
            u[t][1] = t % 2 == 0 ? 0.03 : -0.03;
        }
        double[] caps = {1, 1};
        double[][] z = {{0, 0}};
        FactorRiskModel.Settings s = new FactorRiskModel.Settings(10, 0, 0, 10, 0.5, 1, 3);
        double[] v = FactorRiskModel.specificVariances(u, caps, z, s);
        double mean = 0.02;
        double delta = 0.01;
        double gap = 0.5 * 0.01;
        double w = gap / (delta + gap);
        assertEquals(Math.pow(w * mean + (1 - w) * 0.01, 2), v[0], 1e-15);
        assertEquals(Math.pow(w * mean + (1 - w) * 0.03, 2), v[1], 1e-15);
        // q = 0 keeps the raw estimate
        double[] raw = FactorRiskModel.specificVariances(u, caps, z,
                new FactorRiskModel.Settings(10, 0, 0, 10, 0, 1, 3));
        assertEquals(1e-4, raw[0], 1e-15);
    }

    @Test
    void standardizeUsesCapWeightedMeanAndEqualWeightedSd() {
        double[] raw = {1, 2, 3, Double.NaN, 100};
        double[] caps = {3, 1, 1, 1, 1};
        boolean[] live = {true, true, true, true, false};
        double[] z = FactorRiskModel.standardize(raw, caps, live, 10);
        // cap mean of {1,2,3} with caps {3,1,1} = 8/5 = 1.6; ew sd = sqrt(2/3)
        double sd = Math.sqrt(2.0 / 3);
        assertEquals((1 - 1.6) / sd, z[0], 1e-12);
        assertEquals((3 - 1.6) / sd, z[2], 1e-12);
        assertEquals(0, z[3]);
        assertTrue(Double.isNaN(z[4]));
        // clipping: a far outlier is clipped then the set re-standardised
        double[] big = {0, 0, 0, 0, 0, 0, 0, 0, 0, 100};
        double[] ones = new double[10];
        boolean[] all = new boolean[10];
        java.util.Arrays.fill(ones, 1);
        java.util.Arrays.fill(all, true);
        double[] zc = FactorRiskModel.standardize(big, ones, all, 2);
        double mean = 0;
        for (double x : zc) {
            mean += x / 10;
        }
        assertEquals(0, mean, 1e-12);
        assertTrue(zc[9] < 3.01);
        assertThrows(IllegalArgumentException.class,
                () -> FactorRiskModel.standardize(raw, caps, live, 0));
    }

    @Test
    void forecastRowAndInactiveNames() {
        Panel p = panel(7, 1);
        double[][][] styles = java.util.Arrays.copyOf(p.styles, T + 1);
        double[][] caps = java.util.Arrays.copyOf(p.caps, T + 1);
        styles[T] = p.styles[T - 1];
        caps[T] = p.caps[T - 1].clone();
        caps[T][3] = Double.NaN; // name 3 delisted before the forecast
        p.returns[10][5] = Double.NaN;
        FactorRiskModel m = FactorRiskModel.estimate(p.returns, styles, List.of("SIZE", "MOM"),
                p.sectors, List.of("A", "B", "C", "D"), caps, FactorRiskModel.Settings.defaults());
        assertFalse(m.covers(3));
        assertTrue(Double.isNaN(m.exposure(3, 0)));
        assertTrue(Double.isNaN(m.residual(10, 5)));
        assertEquals(1.0, m.exposure(5, 0));
        assertEquals(1.0, m.exposure(5, 1 + p.sectors[5]));
        assertEquals(T, m.periods());
        assertEquals(N, m.nameCount());
        assertEquals(S, m.sectorCount());
        assertEquals(K, m.styleCount());
        assertEquals(p.sectors[9], m.sector(9));
        assertEquals(m.factorReturn(4, 2), m.factorReturnsCopy(4)[2]);
        assertFalse(m.repaired());
        double[] w = new double[N];
        w[3] = 0.1;
        assertThrows(IllegalArgumentException.class, () -> m.decompose(w));
        double[][] v = m.covariance();
        assertEquals(0, v[3][3]);
    }

    @Test
    void rejectsBadInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> new FactorRiskModel.Settings(0, 0, 0, 1, 0, 1, 1));
        Panel p = panel(8, 1);
        int[] bad = p.sectors.clone();
        bad[0] = 9;
        assertThrows(IllegalArgumentException.class, () -> FactorRiskModel.estimate(p.returns,
                p.styles, List.of("SIZE", "MOM"), bad, List.of("A", "B", "C", "D"), p.caps, FLAT));
        assertThrows(IllegalArgumentException.class, () -> FactorRiskModel.estimate(
                new double[1][N], p.styles, List.of("SIZE", "MOM"), p.sectors,
                List.of("A", "B", "C", "D"), p.caps, FLAT));
        assertEquals(0, FLAT.plain().neweyWestLags());
    }
}
