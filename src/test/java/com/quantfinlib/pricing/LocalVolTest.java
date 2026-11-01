package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dupire local vol from SSVI: the flat surface returns the Black-Scholes
 * vol and prices, and both the local-vol Monte Carlo and the
 * Crank-Nicolson pricer reprice vanillas off a skewed SSVI surface.
 */
class LocalVolTest {

    private static final double S = 100;
    private static final double R = 0.03;
    private static final double Q = 0.01;
    private static final double[] T = {0.25, 0.5, 1.0, 2.0};

    private static SsviSurface flat(double sigma) {
        double[] theta = new double[T.length];
        for (int i = 0; i < T.length; i++) {
            theta[i] = sigma * sigma * T[i];
        }
        return new SsviSurface(0, 0, 0.3, T, theta, S, R, Q);
    }

    private static SsviSurface skewed() {
        return new SsviSurface(-0.6, 1.0, 0.4, T, new double[]{0.010, 0.022, 0.045, 0.090}, S, R, Q);
    }

    @Test
    void flatSurfaceGivesBlackScholes() {
        DupireLocalVol lv = new DupireLocalVol(flat(0.2));
        for (double t : new double[]{0, 0.1, 0.5, 1.7, 3}) {
            for (double s : new double[]{50, 90, 100, 130, 200}) {
                assertEquals(0.2, lv.localVol(t, s), 1e-8);
            }
        }
        LocalVolPde pde = new LocalVolPde(lv);
        LocalVolMonteCarlo mc = new LocalVolMonteCarlo(lv);
        for (double k : new double[]{80, 100, 120}) {
            double bsCall = BlackScholes.price(OptionType.CALL, S, k, R, Q, 0.2, 1);
            double bsPut = BlackScholes.price(OptionType.PUT, S, k, R, Q, 0.2, 1);
            assertEquals(bsCall, pde.price(OptionType.CALL, k, 1, 401, 200), 2e-3);
            assertEquals(bsPut, pde.price(OptionType.PUT, k, 1, 401, 200), 2e-3);
            // Log-Euler with constant vol is exact in distribution: only sampling error remains.
            LocalVolMonteCarlo.Result r = mc.price(OptionType.CALL, k, 1, 20000, 10, 42);
            assertEquals(bsCall, r.price(), 4 * r.standardError());
        }
    }

    @Test
    void pdeConvergesAtSecondOrderOnFlatSurface() {
        LocalVolPde pde = new LocalVolPde(new DupireLocalVol(flat(0.25)));
        double bs = BlackScholes.price(OptionType.CALL, S, 105, R, Q, 0.25, 0.5);
        double e1 = Math.abs(pde.price(OptionType.CALL, 105, 0.5, 101, 50) - bs);
        double e2 = Math.abs(pde.price(OptionType.CALL, 105, 0.5, 201, 100) - bs);
        assertTrue(e2 < e1 / 3, "errors " + e1 + " -> " + e2);
    }

    @Test
    void pdeRepricesSsviVanillas() {
        SsviSurface surf = skewed();
        LocalVolPde pde = new LocalVolPde(new DupireLocalVol(surf));
        for (double t : new double[]{0.5, 1.0}) {
            for (double k : new double[]{75, 90, 100, 110, 130}) {
                OptionType type = k < surf.forward(t) ? OptionType.PUT : OptionType.CALL;
                double p = pde.price(type, k, t, 601, 400);
                double iv = BlackScholes.impliedVol(type, p, S, k, R, Q, t);
                assertEquals(surf.impliedVol(t, k), iv, 2e-3, "t=" + t + " k=" + k);
            }
        }
    }

    @Test
    void monteCarloRepricesSsviVanillas() {
        SsviSurface surf = skewed();
        LocalVolMonteCarlo mc = new LocalVolMonteCarlo(new DupireLocalVol(surf));
        double t = 1.0;
        for (double k : new double[]{80, 100, 120}) {
            OptionType type = k < surf.forward(t) ? OptionType.PUT : OptionType.CALL;
            LocalVolMonteCarlo.Result r = mc.price(type, k, t, 20000, 100, 7);
            double target = surf.price(type, k, t);
            // Sampling error plus a time-discretisation allowance of 0.5 % of the price.
            assertEquals(target, r.price(), 4 * r.standardError() + 0.005 * target, "k=" + k);
        }
        LocalVolMonteCarlo.Result a = mc.price(OptionType.CALL, 100, t, 500, 20, 99);
        LocalVolMonteCarlo.Result b = mc.price(OptionType.CALL, 100, t, 500, 20, 99);
        assertEquals(a, b);   // seeded
    }

    @Test
    void localVolMatchesAnalyticSsviDupire() {
        // Independent analytic form: sigma^2 = theta'(t) dw/dtheta / g(k), with
        // w' and w'' in k from the SSVI formula and phi'(theta) from the power law.
        SsviSurface surf = skewed();
        DupireLocalVol lv = new DupireLocalVol(surf);
        double rho = -0.6;
        double eta = 1.0;
        double gamma = 0.4;
        double t = 0.75;
        double thetaSlope = (0.045 - 0.022) / 0.5;
        double theta = surf.thetaAt(t);
        double phi = eta / (Math.pow(theta, gamma) * Math.pow(1 + theta, 1 - gamma));
        double dphi = -phi * (gamma / theta + (1 - gamma) / (1 + theta));
        for (double k : new double[]{-0.4, -0.1, 0, 0.2, 0.5}) {
            double x = phi * k + rho;
            double rr = Math.sqrt(x * x + 1 - rho * rho);
            double w = 0.5 * theta * (1 + rho * phi * k + rr);
            double w1 = 0.5 * theta * phi * (rho + x / rr);
            double w2 = 0.5 * theta * phi * phi * (1 - rho * rho) / (rr * rr * rr);
            double dwdTheta = w / theta + 0.5 * theta * k * dphi * (rho + x / rr);
            double g = Math.pow(1 - k * w1 / (2 * w), 2) - 0.25 * w1 * w1 * (1 / w + 0.25) + 0.5 * w2;
            assertEquals(Math.sqrt(thetaSlope * dwdTheta / g), lv.localVolAtMoneyness(t, k), 1e-6, "k=" + k);
        }
        // Local skew is steeper than implied skew (about twice near the money).
        double t2 = 0.3;
        assertTrue(lv.localVolAtMoneyness(t2, 0) > 0);
        t = t2;
        double h = 0.02;
        double implSkew = (Math.sqrt(surf.totalVariance(t, h) / t) - Math.sqrt(surf.totalVariance(t, -h) / t)) / (2 * h);
        double locSkew = (lv.localVolAtMoneyness(t, h) - lv.localVolAtMoneyness(t, -h)) / (2 * h);
        assertTrue(locSkew < implSkew && implSkew < 0, implSkew + " vs " + locSkew);
    }

    @Test
    void rejectsBadInputsAndArbitrage() {
        DupireLocalVol lv = new DupireLocalVol(flat(0.2));
        assertThrows(IllegalArgumentException.class, () -> new DupireLocalVol(null));
        assertThrows(IllegalArgumentException.class, () -> lv.localVol(1, 0));
        assertThrows(IllegalArgumentException.class, () -> lv.localVolAtMoneyness(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new LocalVolPde(lv).price(OptionType.CALL, 100, 1, 5, 10));
        assertThrows(IllegalArgumentException.class, () -> new LocalVolMonteCarlo(lv).price(OptionType.CALL, 100, 0, 5, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> new LocalVolPde(null));
        assertThrows(IllegalArgumentException.class, () -> new LocalVolMonteCarlo(null));
        // Vogt's SVI slice has negative g: Dupire must refuse, not return NaN.
        SviSurface vogt = new SviSurface(new double[]{1},
                new SviSlice[]{new SviSlice(-0.0410, 0.1331, 0.3060, 0.3586, 0.4153)}, S, 0, 0);
        DupireLocalVol bad = new DupireLocalVol(vogt);
        int refused = 0;
        for (double y = -1.5; y <= 1.5; y += 0.05) {
            try {
                bad.localVolAtMoneyness(1, y);
            } catch (IllegalStateException e) {
                refused++;
            }
        }
        assertTrue(refused > 0);
    }
}
