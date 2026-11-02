package com.quantfinlib.credit;

import com.quantfinlib.rates.YieldCurve;
import org.junit.jupiter.api.Test;

import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CvaApproximator#cvaBucketAverage} against a fine-grid integral of
 * {@code LGD * int DF(t) EE(t) dPD(t)} (independent recomputation: 200 000
 * midpoint steps on the same curves), and the convergence order of both
 * discretisations.
 */
class CvaBucketAverageTest {

    static final double LGD = 0.6;
    static final double T = 5;

    static YieldCurve flat(double r) {
        return YieldCurve.ofZeroRates(new double[] {1, 2, 3, 5, 7, 10},
                new double[] {r, r, r, r, r, r});
    }

    static CreditCurve curve(YieldCurve discount) {
        return CreditCurve.bootstrap(new int[] {10}, new double[] {0.02}, 0.40, discount);
    }

    /** Swap-like hump: zero at inception and maturity, peak at T/2 (EE = 1mm * t (T - t)). */
    static final DoubleUnaryOperator HUMP = t -> 1e6 * t * (T - t);
    /** Rising exposure, as on a cross-currency swap with a final exchange. */
    static final DoubleUnaryOperator RISING = t -> 1e6 * (1 + t * t);

    static double integral(DoubleUnaryOperator ee, CreditCurve c, YieldCurve d) {
        int n = 200_000;
        double h = T / n, sum = 0, q0 = 1;
        for (int k = 1; k <= n; k++) {
            double b = k * h, m = b - 0.5 * h;
            double q = c.survivalProbability(b);
            sum += ee.applyAsDouble(m) * d.discountFactor(m) * (q0 - q);
            q0 = q;
        }
        return LGD * sum;
    }

    static double[] grid(int buckets) {
        double[] t = new double[buckets];
        for (int i = 0; i < buckets; i++) {
            t[i] = T * (i + 1) / buckets;
        }
        return t;
    }

    static double[] sample(DoubleUnaryOperator ee, double[] t) {
        double[] out = new double[t.length];
        for (int i = 0; i < t.length; i++) {
            out[i] = ee.applyAsDouble(t[i]);
        }
        return out;
    }

    static double endErr(DoubleUnaryOperator ee, int buckets, CreditCurve c, YieldCurve d, double exact) {
        double[] t = grid(buckets);
        return CvaApproximator.cva(sample(ee, t), t, c, d, LGD) - exact;
    }

    static double avgErr(DoubleUnaryOperator ee, int buckets, CreditCurve c, YieldCurve d, double exact) {
        double[] t = grid(buckets);
        return CvaApproximator.cvaBucketAverage(ee.applyAsDouble(0), sample(ee, t), t, c, d, LGD) - exact;
    }

    @Test
    void bucketAverageRemovesTheEndPointBiasOnACoarseGrid() {
        YieldCurve d = flat(0.03);
        CreditCurve c = curve(d);
        double exact = integral(RISING, c, d);
        double end = endErr(RISING, 5, c, d, exact);
        double avg = avgErr(RISING, 5, c, d, exact);
        // Annual buckets on EE = 1mm (1 + t^2): the end-point rule overstates CVA by
        // 27.6 %, the bucket average by 1.8 % (measured; 0.45 % at 10 buckets).
        assertTrue(end > 0.25 * exact, "end err " + end + " of " + exact);
        assertTrue(Math.abs(avg) < 0.02 * exact, "avg err " + avg + " of " + exact);
        assertTrue(Math.abs(avgErr(RISING, 10, c, d, exact)) < 0.005 * exact);
    }

    @Test
    void bucketAverageIsSecondOrderForBothShapes() {
        YieldCurve d = flat(0.03);
        CreditCurve c = curve(d);
        for (DoubleUnaryOperator ee : new DoubleUnaryOperator[] {HUMP, RISING}) {
            double exact = integral(ee, c, d);
            double a1 = avgErr(ee, 10, c, d, exact), a2 = avgErr(ee, 20, c, d, exact);
            double a3 = avgErr(ee, 40, c, d, exact);
            // Halving dt quarters the error.
            assertEquals(4.0, a1 / a2, 0.2);
            assertEquals(4.0, a2 / a3, 0.2);
        }
    }

    @Test
    void endPointIsFirstOrderOnRisingExposure() {
        YieldCurve d = flat(0.03);
        CreditCurve c = curve(d);
        double exact = integral(RISING, c, d);
        double e1 = endErr(RISING, 20, c, d, exact), e2 = endErr(RISING, 40, c, d, exact);
        // Halving dt only halves the end-point error, and it overstates CVA.
        assertEquals(2.0, e1 / e2, 0.15);
        assertTrue(e2 > 0);
    }

    @Test
    void workedExampleFromTheJavadoc() {
        // EE = 100 t on one bucket (0, 1], zero rates, flat hazard: the javadoc's numbers.
        YieldCurve zero = flat(0.0);
        CreditCurve c = curve(zero);
        double q1 = c.survivalProbability(1);
        double end = CvaApproximator.cva(new double[] {100}, new double[] {1}, c, zero, 1.0);
        double avg = CvaApproximator.cvaBucketAverage(0, new double[] {100}, new double[] {1}, c, zero, 1.0);
        assertEquals(100 * (1 - q1), end, 1e-12);
        assertEquals(50 * (1 - q1), avg, 1e-12);
    }

    @Test
    void flatExposureGivesTheSameAnswerBothWaysWithoutDiscounting() {
        YieldCurve zero = flat(0.0);
        CreditCurve c = curve(zero);
        double[] t = grid(8);
        double[] ee = new double[8];
        java.util.Arrays.fill(ee, 250.0);
        assertEquals(CvaApproximator.cva(ee, t, c, zero, LGD),
                CvaApproximator.cvaBucketAverage(250.0, ee, t, c, zero, LGD), 1e-9);
    }

    @Test
    void rejectsBadInputs() {
        YieldCurve d = flat(0.03);
        CreditCurve c = curve(d);
        double[] t = {1, 2};
        double[] ee = {1, 1};
        assertThrows(IllegalArgumentException.class, () -> CvaApproximator.cvaBucketAverage(-1, ee, t, c, d, LGD));
        assertThrows(IllegalArgumentException.class,
                () -> CvaApproximator.cvaBucketAverage(Double.POSITIVE_INFINITY, ee, t, c, d, LGD));
        assertThrows(IllegalArgumentException.class,
                () -> CvaApproximator.cvaBucketAverage(0, ee, new double[] {2, 1}, c, d, LGD));
        assertThrows(IllegalArgumentException.class,
                () -> CvaApproximator.cvaBucketAverage(0, new double[] {1}, t, c, d, LGD));
        assertThrows(IllegalArgumentException.class,
                () -> CvaApproximator.cvaBucketAverage(0, new double[] {-1, 1}, t, c, d, LGD));
        assertThrows(IllegalArgumentException.class, () -> CvaApproximator.cvaBucketAverage(0, ee, t, c, d, 0));
        assertThrows(NullPointerException.class, () -> CvaApproximator.cvaBucketAverage(0, ee, t, null, d, LGD));
        assertThrows(NullPointerException.class, () -> CvaApproximator.cvaBucketAverage(0, ee, t, c, null, LGD));
    }
}
