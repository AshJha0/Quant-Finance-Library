package com.quantfinlib.credit;

import com.quantfinlib.rates.YieldCurve;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bilateral CVA/DVA with first-to-default: checked against a fine-grid
 * integral of the first-to-default densities, the zero-own-hazard limit,
 * and the ordering against the unilateral legs.
 */
@SuppressWarnings("removal") // exercises 2.x API deprecated for removal in 2.4.0
class BilateralCvaTest {

    private static YieldCurve dom() {
        return YieldCurve.ofZeroRates(new double[]{1, 5, 10}, new double[]{0.025, 0.03, 0.033});
    }

    private static final double[] TIMES = {0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4, 4.5};

    private static ExposureProfile profile() {
        ExposureSimulator.Market m = ExposureSimulator.Market.ratesOnly(dom(), 0.05, 0.01);
        ExposureSimulator.Swap s = new ExposureSimulator.Swap(100, 0.03, true, 0, 5, 1);
        return ExposureSimulator.simulate(m, List.of(s), List.of(), null, TIMES, 4_000, 5L);
    }

    private static CreditCurve flat(double spread) {
        // one pillar: hazard flat in time, so the bucket formula is exact
        return CreditCurve.bootstrap(new int[]{10}, new double[]{spread}, 0.4, dom());
    }

    @Test
    void matchesFineGridFirstToDefaultIntegral() {
        ExposureProfile p = profile();
        CreditCurve c = flat(0.03), o = flat(0.012);
        CvaApproximator.Bilateral b = CvaApproximator.bilateral(p, c, 0.6, o, 0.6);
        double cva = 0, dva = 0, t0 = 0;
        for (int i = 0; i < TIMES.length; i++) {
            int n = 2000;
            double h = (TIMES[i] - t0) / n, wc = 0, wo = 0;
            for (int k = 0; k < n; k++) {
                double u0 = t0 + k * h, u1 = u0 + h, um = 0.5 * (u0 + u1);
                double sc = c.survivalProbability(um), so = o.survivalProbability(um);
                wc += (c.survivalProbability(u0) - c.survivalProbability(u1)) * so;
                wo += (o.survivalProbability(u0) - o.survivalProbability(u1)) * sc;
            }
            cva += p.discountedExpectedExposure(i) * wc;
            dva += p.discountedExpectedNegativeExposure(i) * wo;
            t0 = TIMES[i];
        }
        assertEquals(0.6 * cva, b.cva(), 1e-6 * Math.abs(cva));
        assertEquals(0.6 * dva, b.dva(), 1e-6 * Math.abs(dva));
        assertEquals(b.cva() - b.dva(), b.total(), 1e-15);
        // first-to-default can only shrink each leg
        assertTrue(b.cva() < CvaApproximator.cva(p, c, 0.6));
        assertTrue(b.dva() < CvaApproximator.dva(p, o, 0.6));
    }

    @Test
    void vanishingOwnHazardGivesTheUnilateralCva() {
        ExposureProfile p = profile();
        CreditCurve c = flat(0.03), o = flat(1e-9);
        CvaApproximator.Bilateral b = CvaApproximator.bilateral(p, c, 0.6, o, 0.6);
        assertEquals(CvaApproximator.cva(p, c, 0.6), b.cva(), 1e-6);
        assertEquals(0, b.dva(), 1e-6);
    }
}
