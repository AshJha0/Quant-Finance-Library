package com.quantfinlib.pricing;

import com.quantfinlib.stats.SpecialFunctions;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static com.quantfinlib.pricing.BlackScholes.OptionType.CALL;
import static com.quantfinlib.pricing.BlackScholes.OptionType.PUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seasoned Turnbull-Wakeman, Curran and Levy against a Monte Carlo with the
 * geometric average as control variate (its price is exact under GBM), plus
 * exact reductions: no seasoning equals the existing pricer, a deep
 * in-the-money reduced strike is the discounted forward, and Levy is the
 * continuous limit of Turnbull-Wakeman.
 */
class AsianSeasonedTest {

    private static final double S = 100, R = 0.04, Q = 0.01, VOL = 0.30, T = 1.0;

    private static double[] equalTimes(int from, int n, double t) {
        double[] out = new double[n - from];
        for (int i = from + 1; i <= n; i++) {
            out[i - from - 1] = i * t / n;
        }
        return out;
    }

    /** {mean, standard error} of the discounted arithmetic payoff, geometric control variate. */
    private static double[] monteCarlo(BlackScholes.OptionType type, double k, double[] times,
                                       double obsSum, int obsCount, int paths, long seed) {
        int m = times.length, n = m + obsCount;
        double kStar = (n * k - obsSum) / m, w = (double) m / n;
        double df = Math.exp(-R * T);
        // Exact geometric price on the remaining fixings with strike K*.
        double meanLog = Math.log(S), varLog = 0;
        for (int i = 0; i < m; i++) {
            meanLog += (R - Q - 0.5 * VOL * VOL) * times[i] / m;
            for (int j = 0; j < m; j++) {
                varLog += VOL * VOL * Math.min(times[i], times[j]) / ((double) m * m);
            }
        }
        double fwdG = Math.exp(meanLog + 0.5 * varLog), sd = Math.sqrt(varLog);
        double d1 = (Math.log(fwdG / kStar) + 0.5 * varLog) / sd;
        int s = type.sign();
        double geo = df * s * (fwdG * SpecialFunctions.normCdf(s * d1) - kStar * SpecialFunctions.normCdf(s * (d1 - sd)));

        SplittableRandom rnd = new SplittableRandom(seed);
        double[] x = new double[paths], y = new double[paths];
        for (int p = 0; p < paths; p++) {
            double logS = Math.log(S), prev = 0, sum = 0, sumLog = 0;
            for (double ti : times) {
                double dt = ti - prev;
                logS += (R - Q - 0.5 * VOL * VOL) * dt + VOL * Math.sqrt(dt) * rnd.nextGaussian();
                sum += Math.exp(logS);
                sumLog += logS;
                prev = ti;
            }
            x[p] = df * Math.max(0, s * (sum / m - kStar));
            y[p] = df * Math.max(0, s * (Math.exp(sumLog / m) - kStar));
        }
        double mx = 0, my = 0;
        for (int p = 0; p < paths; p++) {
            mx += x[p];
            my += y[p];
        }
        mx /= paths;
        my /= paths;
        double cxy = 0, cyy = 0;
        for (int p = 0; p < paths; p++) {
            cxy += (x[p] - mx) * (y[p] - my);
            cyy += (y[p] - my) * (y[p] - my);
        }
        double beta = cxy / cyy;
        double var = 0;
        for (int p = 0; p < paths; p++) {
            double z = x[p] - beta * (y[p] - geo);
            double d = z - (mx - beta * (my - geo));
            var += d * d;
        }
        double est = mx - beta * (my - geo);
        return new double[]{w * est, w * Math.sqrt(var / (paths - 1) / paths)};
    }

    @Test
    void unseasonedEqualSpacingMatchesExistingTurnbullWakeman() {
        double[] times = equalTimes(0, 12, T);
        for (double k : new double[]{85, 100, 115}) {
            assertEquals(AsianOption.arithmeticPrice(CALL, S, k, R, Q, VOL, T, 12),
                    AsianOption.turnbullWakemanSeasoned(CALL, S, k, R, Q, VOL, times, T, 0, 0), 1e-12);
            assertEquals(AsianOption.arithmeticPrice(PUT, S, k, R, Q, VOL, T, 12),
                    AsianOption.turnbullWakemanSeasoned(PUT, S, k, R, Q, VOL, times, T, 0, 0), 1e-12);
        }
    }

    @Test
    void seasonedPricesAgreeWithControlVariateMonteCarlo() {
        // 12 monthly fixings, 4 struck at an average of 104; 8 remaining.
        double[] times = equalTimes(4, 12, T);
        double obsSum = 4 * 104;
        for (double k : new double[]{90, 100, 110}) {
            for (var type : new BlackScholes.OptionType[]{CALL, PUT}) {
                double[] mc = monteCarlo(type, k, times, obsSum, 4, 100_000, 42);
                double curran = AsianOption.curranPrice(type, S, k, R, Q, VOL, times, T, obsSum, 4);
                double tw = AsianOption.turnbullWakemanSeasoned(type, S, k, R, Q, VOL, times, T, obsSum, 4);
                // Curran's conditioning error is far below the MC noise here.
                assertEquals(mc[0], curran, 4 * mc[1] + 2e-3 * mc[0] + 1e-3, type + " K=" + k);
                // Turnbull-Wakeman's lognormal error grows with vol^2 T; 2% at 30% vol.
                assertEquals(mc[0], tw, 4 * mc[1] + 0.02 * mc[0] + 1e-3, type + " K=" + k);
            }
        }
    }

    @Test
    void curranUnseasonedAgreesWithMonteCarloAtHighVol() {
        double[] times = equalTimes(0, 52, T);
        double[] mc = monteCarlo(CALL, 105, times, 0, 0, 100_000, 9);
        assertEquals(mc[0], AsianOption.curranPrice(CALL, S, 105, R, Q, VOL, times, T, 0, 0),
                4 * mc[1] + 2e-3 * mc[0]);
        assertTrue(mc[1] < 0.01, "control variate should give a tight estimate: " + mc[1]);
    }

    @Test
    void deepInTheMoneyReducedStrikeIsTheForward() {
        // Fixings so far average 300 with 6 of 12 struck: K* = (12*100 - 1800)/6 < 0.
        double[] times = equalTimes(6, 12, T);
        double fwd = 0;
        for (double t : times) {
            fwd += S * Math.exp((R - Q) * t);
        }
        double expected = Math.exp(-R * T) * ((1800 + fwd) / 12 - 100);
        assertEquals(expected, AsianOption.turnbullWakemanSeasoned(CALL, S, 100, R, Q, VOL, times, T, 1800, 6), 1e-12);
        assertEquals(expected, AsianOption.curranPrice(CALL, S, 100, R, Q, VOL, times, T, 1800, 6), 1e-12);
        assertEquals(0, AsianOption.curranPrice(PUT, S, 100, R, Q, VOL, times, T, 1800, 6));
        assertEquals(0, AsianOption.turnbullWakemanSeasoned(PUT, S, 100, R, Q, VOL, times, T, 1800, 6));
        // Everything fixed: discounted intrinsic.
        assertEquals(Math.exp(-R * T) * 5, AsianOption.curranPrice(CALL, S, 100, R, Q, VOL, new double[0], T, 1260, 12), 1e-12);
        assertEquals(Math.exp(-R * T) * 5, AsianOption.turnbullWakemanSeasoned(PUT, S, 100, R, Q, VOL, new double[0], T, 1140, 12), 1e-12);
    }

    @Test
    void levyIsTheContinuousLimitOfTurnbullWakeman() {
        int n = 20_000;
        for (double k : new double[]{90, 100, 112}) {
            for (var type : new BlackScholes.OptionType[]{CALL, PUT}) {
                assertEquals(AsianOption.arithmeticPrice(type, S, k, R, Q, VOL, T, n),
                        AsianOption.levyPrice(type, S, k, R, Q, VOL, T, T, 0), 2e-3);
            }
        }
        // Zero carry and b + vol^2 = 0 hit the removable singularities.
        assertEquals(AsianOption.arithmeticPrice(CALL, S, 100, 0.03, 0.03, VOL, T, n),
                AsianOption.levyPrice(CALL, S, 100, 0.03, 0.03, VOL, T, T, 0), 2e-3);
        assertEquals(AsianOption.arithmeticPrice(CALL, S, 100, 0.01, 0.1, VOL, T, n),
                AsianOption.levyPrice(CALL, S, 100, 0.01, 0.1, VOL, T, T, 0), 2e-3);
    }

    @Test
    void seasonedLevyAgreesWithMonteCarlo() {
        // Averaging over one year, 0.4y already elapsed at an average of 97.
        double tLeft = 0.6;
        int fixings = 600;
        int done = 240;
        double[] times = new double[fixings - done];
        for (int i = 0; i < times.length; i++) {
            times[i] = (i + 1) * tLeft / times.length;
        }
        double levy = AsianOption.levyPrice(CALL, S, 100, R, Q, VOL, tLeft, 1.0, 97);
        // The MC helper discounts over T = 1; rescale to the 0.6y payment.
        double[] mc = monteCarlo(CALL, 100, times, 97.0 * done, done, 60_000, 5);
        double scale = Math.exp(-R * tLeft) / Math.exp(-R * T);
        assertEquals(mc[0] * scale, levy, 4 * mc[1] * scale + 0.01 * levy);
        double put = AsianOption.levyPrice(PUT, S, 100, R, Q, VOL, tLeft, 1.0, 97);
        assertTrue(put > 0);
        assertEquals(0, AsianOption.levyPrice(PUT, S, 10, R, Q, VOL, tLeft, 1.0, 97));
    }

    @Test
    void validation() {
        double[] times = {0.5, 1.0};
        assertThrows(IllegalArgumentException.class,
                () -> AsianOption.curranPrice(CALL, S, 100, R, Q, VOL, new double[]{1.0, 0.5}, T, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> AsianOption.curranPrice(CALL, S, 100, R, Q, VOL, new double[]{0.5, 1.5}, T, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> AsianOption.turnbullWakemanSeasoned(CALL, S, 100, R, Q, VOL, times, T, 5, 0));
        assertThrows(IllegalArgumentException.class,
                () -> AsianOption.turnbullWakemanSeasoned(CALL, S, 100, R, Q, VOL, new double[0], T, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> AsianOption.levyPrice(CALL, S, 100, R, Q, VOL, T, 0.5, 0));
        assertThrows(IllegalArgumentException.class,
                () -> AsianOption.levyPrice(CALL, S, 100, R, Q, VOL, 0.5, 1, Double.NaN));
        assertEquals(Math.exp(-R * T) * Math.max(0, (S * Math.exp((R - Q) * 0.5) + S * Math.exp((R - Q) * T)) / 2 - 100),
                AsianOption.curranPrice(CALL, S, 100, R, Q, 0, times, T, 0, 0), 1e-12);
    }
}
