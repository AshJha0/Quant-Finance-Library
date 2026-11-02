package com.quantfinlib.pricing;

import org.junit.jupiter.api.Test;

import static com.quantfinlib.pricing.BlackScholes.OptionType.CALL;
import static com.quantfinlib.pricing.BlackScholes.OptionType.PUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * COS pricer pinned by the Fang-Oosterlee (2008) published Heston value, by the
 * independent Simpson pricer in {@link Heston}, by the Black-Scholes limit, and
 * its analytic Greeks and gradient by central differences of the full pricer.
 */
class HestonCosTest {

    // Fang and Oosterlee (2008), section 5.2 / Table 4: S=K=100, r=q=0, T=1.
    private static final Heston.Params FO = new Heston.Params(1.5768, 0.0398, 0.5751, -0.5711, 0.0175);

    @Test
    void reproducesFangOosterleePublishedValue() {
        // The paper quotes 5.785155450. The 1.6e-8 gap to this pricer is in the
        // reference, not here. An independent computation (Lewis 2001 single
        // integral with the Albrecher et al. 2007 characteristic function,
        // 40-digit mpmath adaptive quadrature, no shared code) gives
        // 5.7851554343761890; the paper's figure is 1.56e-8 above that, so its
        // last two printed digits are not significant (the paper's own COS
        // reference run is double precision). The published value is therefore
        // pinned at 2e-8 and the high-precision value at 1e-10.
        double cos = HestonCos.price(CALL, 100, 100, 0, 0, 1, FO);
        assertEquals(5.785155450, cos, 2e-8);
        assertEquals(5.7851554343761890, cos, 1e-10);
        assertEquals(Heston.call(100, 100, 0, 0, 1, FO), cos, 2e-9);
        // Converged: more terms and a wider interval change nothing at this tolerance.
        assertEquals(cos, HestonCos.price(CALL, 100, 100, 0, 0, 1, FO, 2048, 40), 1e-10);
        // The paper's L = 12 truncation leaves an error near 4.5e-5 on this case.
        assertEquals(4.5e-5, cos - HestonCos.price(CALL, 100, 100, 0, 0, 1, FO, 1024, 12), 1e-5);
    }

    @Test
    void agreesWithSimpsonPricerAcrossStrikesAndMaturities() {
        Heston.Params p = new Heston.Params(2.0, 0.05, 0.4, -0.7, 0.04);
        for (double t : new double[]{0.1, 0.5, 1, 3}) {
            for (double k : new double[]{70, 90, 100, 110, 140}) {
                double simpson = Heston.call(100, k, 0.03, 0.01, t, p);
                assertEquals(simpson, HestonCos.price(CALL, 100, k, 0.03, 0.01, t, p), 2e-6,
                        "T=" + t + " K=" + k);
                assertEquals(Heston.put(100, k, 0.03, 0.01, t, p),
                        HestonCos.price(PUT, 100, k, 0.03, 0.01, t, p), 2e-6);
            }
        }
    }

    @Test
    void tinyVolOfVolRecoversBlackScholes() {
        // sigmaV -> 0 with v0 = theta: deterministic variance theta, BS with vol sqrt(theta).
        Heston.Params p = new Heston.Params(1.0, 0.04, 1e-4, 0.0, 0.04);
        for (double k : new double[]{80, 100, 125}) {
            assertEquals(BlackScholes.price(CALL, 100, k, 0.02, 0.01, 0.2, 2),
                    HestonCos.price(CALL, 100, k, 0.02, 0.01, 2, p), 1e-6);
        }
    }

    @Test
    void analyticGreeksMatchCentralDifferences() {
        Heston.Params p = new Heston.Params(1.2, 0.06, 0.6, -0.6, 0.05);
        double s = 100, k = 95, r = 0.02, q = 0.005, t = 0.8;
        double h = 1e-2;
        for (var type : new BlackScholes.OptionType[]{CALL, PUT}) {
            double up = HestonCos.price(type, s + h, k, r, q, t, p);
            double mid = HestonCos.price(type, s, k, r, q, t, p);
            double dn = HestonCos.price(type, s - h, k, r, q, t, p);
            assertEquals((up - dn) / (2 * h), HestonCos.delta(type, s, k, r, q, t, p), 1e-7);
            assertEquals((up - 2 * mid + dn) / (h * h), HestonCos.gamma(s, k, r, q, t, p), 1e-5);
        }
        double[] g = HestonCos.parameterGradient(s, k, r, q, t, p);
        double[] base = {p.kappa(), p.theta(), p.sigmaV(), p.rho(), p.v0()};
        for (int j = 0; j < 5; j++) {
            double e = 1e-5;
            double[] a = base.clone();
            double[] b = base.clone();
            a[j] += e;
            b[j] -= e;
            double fd = (HestonCos.price(CALL, s, k, r, q, t, params(a))
                    - HestonCos.price(CALL, s, k, r, q, t, params(b))) / (2 * e);
            assertEquals(fd, g[j], 1e-5 * Math.max(1, Math.abs(fd)), "param " + j);
        }
        assertEquals(g[4], HestonCos.vegaV0(s, k, r, q, t, p), 0);
    }

    @Test
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> HestonCos.price(CALL, -1, 100, 0, 0, 1, FO));
        assertThrows(IllegalArgumentException.class, () -> HestonCos.price(CALL, 100, 100, 0, 0, 0, FO));
        assertThrows(IllegalArgumentException.class,
                () -> HestonCos.price(CALL, 100, 100, 0, 0, 1, FO, 4, 12));
        assertThrows(IllegalArgumentException.class,
                () -> HestonCos.price(CALL, 100, 100, 0, 0, 1, FO, 64, 0));
    }

    static Heston.Params params(double[] a) {
        return new Heston.Params(a[0], a[1], a[2], a[3], a[4]);
    }
}
