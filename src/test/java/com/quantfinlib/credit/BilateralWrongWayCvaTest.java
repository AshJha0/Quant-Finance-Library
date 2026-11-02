package com.quantfinlib.credit;

import com.quantfinlib.rates.YieldCurve;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Joint wrong-way bilateral CVA/DVA: reduces to the independent bilateral
 * formula, matches both marginal default curves on the paths, agrees with a
 * brute-force simulation of the two default times, and positive wrong-way
 * loading raises the CVA (own tests).
 */
@SuppressWarnings("removal") // exercises 2.x API deprecated for removal in 2.4.0
class BilateralWrongWayCvaTest {

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
        return CreditCurve.bootstrap(new int[]{10}, new double[]{spread}, 0.4, dom());
    }

    @Test
    void zeroLoadingsReproduceIndependentBilateral() {
        ExposureProfile p = profile();
        CreditCurve c = flat(0.03), o = flat(0.012);
        CvaApproximator.Bilateral ind = CvaApproximator.bilateral(p, c, 0.6, o, 0.6);
        CvaApproximator.Bilateral ww = CvaApproximator.bilateralWrongWay(p, c, 0.6, o, 0.6, 0, 0,
                ExposureProfile.Factor.RATES);
        assertEquals(ind.cva(), ww.cva(), 1e-12 * ind.cva());
        assertEquals(ind.dva(), ww.dva(), 1e-12 * ind.dva());
    }

    @Test
    void drivenHazardsMatchMarginalSurvival() {
        ExposureProfile p = profile();
        CreditCurve c = flat(0.03);
        double[][] l = CvaApproximator.drivenHazards(p, c, 0.8, ExposureProfile.Factor.RATES);
        int n = p.paths();
        double[] surv = new double[n];
        java.util.Arrays.fill(surv, 1);
        for (int i = 0; i < p.timesLength(); i++) {
            double s = 0;
            for (int k = 0; k < n; k++) {
                surv[k] *= Math.exp(-l[k][i]);
                s += surv[k];
            }
            double q = c.survivalProbability(p.time(i));
            assertEquals(q, s / n, 1e-12, "bucket " + i);
        }
    }

    @Test
    void positiveLoadingRaisesCvaOnPayerSwap() {
        ExposureProfile p = profile();
        CreditCurve c = flat(0.03), o = flat(0.012);
        double base = CvaApproximator.bilateral(p, c, 0.6, o, 0.6).cva();
        double wrong = CvaApproximator.bilateralWrongWay(p, c, 0.6, o, 0.6, 0.5, 0,
                ExposureProfile.Factor.RATES).cva();
        double right = CvaApproximator.bilateralWrongWay(p, c, 0.6, o, 0.6, -0.5, 0,
                ExposureProfile.Factor.RATES).cva();
        assertTrue(wrong > 1.2 * base, "wrong-way " + wrong + " vs " + base);
        assertTrue(right < base, "right-way " + right + " vs " + base);
    }

    @Test
    void agreesWithBruteForceFirstToDefault() {
        ExposureProfile p = profile();
        CreditCurve c = flat(0.04), o = flat(0.03);
        double bc = 0.7, bo = -0.4;
        CvaApproximator.Bilateral ww = CvaApproximator.bilateralWrongWay(p, c, 0.6, o, 0.5, bc, bo,
                ExposureProfile.Factor.RATES);
        double[][] lc = CvaApproximator.drivenHazards(p, c, bc, ExposureProfile.Factor.RATES);
        double[][] lo = CvaApproximator.drivenHazards(p, o, bo, ExposureProfile.Factor.RATES);
        // Independent computation: draw two default times per path (flat hazard in each bucket),
        // keep whichever comes first, many draws per path.
        SplittableRandom rng = new SplittableRandom(99);
        int n = p.paths(), m = p.timesLength(), reps = 50;
        double sc = 0, sc2 = 0, sd = 0, sd2 = 0;
        for (int k = 0; k < n; k++) {
            for (int r = 0; r < reps; r++) {
                double tc = defaultTime(rng, lc[k]), to = defaultTime(rng, lo[k]);
                double lossC = 0, gainD = 0;
                if (tc < to && tc < m) {
                    lossC = 0.6 * p.discountedPositive(k, (int) tc);
                } else if (to < tc && to < m) {
                    gainD = 0.5 * p.discountedNegative(k, (int) to);
                }
                sc += lossC;
                sc2 += lossC * lossC;
                sd += gainD;
                sd2 += gainD * gainD;
            }
        }
        int tot = n * reps;
        double mc = sc / tot, md = sd / tot;
        double sec = Math.sqrt((sc2 / tot - mc * mc) / tot), sed = Math.sqrt((sd2 / tot - md * md) / tot);
        assertEquals(mc, ww.cva(), 4 * sec, "cva");
        assertEquals(md, ww.dva(), 4 * sed, "dva");
    }

    /** Default time in "bucket units": bucket index plus fraction, or +infinity. */
    private static double defaultTime(SplittableRandom rng, double[] l) {
        double e = -Math.log(1 - rng.nextDouble());
        for (int i = 0; i < l.length; i++) {
            if (e <= l[i]) {
                return i + e / l[i] * 0.999;
            }
            e -= l[i];
        }
        return Double.POSITIVE_INFINITY;
    }

    @Test
    void rejectsBadInputs() {
        ExposureProfile p = profile();
        CreditCurve c = flat(0.03);
        assertThrows(IllegalArgumentException.class, () -> CvaApproximator.bilateralWrongWay(p, c, 0.6, c, 0.6,
                Double.NaN, 0, ExposureProfile.Factor.RATES));
        assertThrows(IllegalArgumentException.class, () -> CvaApproximator.bilateralWrongWay(p, c, 0, c, 0.6,
                0, 0, ExposureProfile.Factor.RATES));
    }
}
