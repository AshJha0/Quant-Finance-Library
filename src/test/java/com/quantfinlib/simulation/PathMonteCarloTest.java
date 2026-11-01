package com.quantfinlib.simulation;

import com.quantfinlib.pricing.BlackScholes;
import com.quantfinlib.pricing.BlackScholes.OptionType;
import com.quantfinlib.util.MathUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathMonteCarloTest {

    private static final double[][] ONE = {{1}};

    @Test
    void europeanCallMatchesBlackScholesAndAntitheticHelps() {
        double s = 100, k = 105, r = 0.03, q = 0.01, vol = 0.25, t = 1;
        double bs = BlackScholes.price(OptionType.CALL, s, k, r, q, vol, t);
        MonteCarloSimulator mc = new MonteCarloSimulator(17);
        MonteCarloSimulator.PathPayoff call = p -> Math.max(p[0][p[0].length - 1] - k, 0);
        MonteCarloSimulator.Estimate plain = mc.price(new double[]{s}, new double[]{r - q},
                new double[]{vol}, ONE, t, 12, 100_000, false, r, call);
        MonteCarloSimulator.Estimate anti = mc.price(new double[]{s}, new double[]{r - q},
                new double[]{vol}, ONE, t, 12, 100_000, true, r, call);
        assertEquals(bs, plain.mean(), 3 * plain.standardError());
        assertEquals(bs, anti.mean(), 3 * anti.standardError());
        // Same number of payoff evaluations: antithetic must not be worse.
        assertTrue(anti.standardError() < plain.standardError(),
                anti.standardError() + " vs " + plain.standardError());
    }

    /** Discrete geometric-average Asian call: exact lognormal closed form. */
    @Test
    void geometricAsianMatchesItsClosedForm() {
        double s = 100, k = 100, r = 0.05, q = 0.0, vol = 0.3, t = 1;
        int n = 12;
        double muG = Math.log(s) + (r - q - 0.5 * vol * vol) * t * (n + 1) / (2.0 * n);
        double varG = vol * vol * t * (n + 1) * (2.0 * n + 1) / (6.0 * n * n);
        double d1 = (muG - Math.log(k) + varG) / Math.sqrt(varG);
        double d2 = d1 - Math.sqrt(varG);
        double exact = Math.exp(-r * t) * (Math.exp(muG + 0.5 * varG) * MathUtils.normCdf(d1)
                - k * MathUtils.normCdf(d2));
        MonteCarloSimulator.Estimate e = new MonteCarloSimulator(3).price(new double[]{s},
                new double[]{r - q}, new double[]{vol}, ONE, t, n, 200_000, true, r, p -> {
                    double logSum = 0;
                    for (int j = 1; j <= n; j++) {
                        logSum += Math.log(p[0][j]);
                    }
                    return Math.max(Math.exp(logSum / n) - k, 0);
                });
        assertEquals(exact, e.mean(), 3 * e.standardError());
        assertTrue(e.standardError() < 0.05);
    }

    /** Margrabe exchange option prices the correlation. */
    @Test
    void exchangeOptionMatchesMargrabe() {
        double s1 = 100, s2 = 95, v1 = 0.3, v2 = 0.2, rho = 0.4, t = 0.75, q1 = 0.02, q2 = 0.01;
        double sig = Math.sqrt(v1 * v1 + v2 * v2 - 2 * rho * v1 * v2);
        double d1 = (Math.log(s1 / s2) + (q2 - q1 + 0.5 * sig * sig) * t) / (sig * Math.sqrt(t));
        double d2 = d1 - sig * Math.sqrt(t);
        double margrabe = s1 * Math.exp(-q1 * t) * MathUtils.normCdf(d1)
                - s2 * Math.exp(-q2 * t) * MathUtils.normCdf(d2);
        double r = 0.04;
        MonteCarloSimulator.Estimate e = new MonteCarloSimulator(5).price(
                new double[]{s1, s2}, new double[]{r - q1, r - q2}, new double[]{v1, v2},
                new double[][]{{1, rho}, {rho, 1}}, t, 1, 200_000, true, r,
                p -> Math.max(p[0][1] - p[1][1], 0));
        assertEquals(margrabe, e.mean(), 3 * e.standardError());
    }

    @Test
    void portfolioTerminalMeanIsLognormal() {
        // One asset, daily log mean m and variance v: E[V_T] = V0·exp(d(m + v/2)).
        double m = 2e-4, v = 1e-4;
        int d = 252;
        SimulationResult r = new MonteCarloSimulator(11).simulatePortfolio(1.0, new double[]{1},
                new double[]{m}, new double[][]{{v}}, d, 200_000);
        double expected = Math.exp(d * (m + 0.5 * v));
        double sd = expected * Math.sqrt(Math.expm1(d * v)) / Math.sqrt(200_000);
        assertEquals(expected, r.expectedValue(), 4 * sd);
    }
}
