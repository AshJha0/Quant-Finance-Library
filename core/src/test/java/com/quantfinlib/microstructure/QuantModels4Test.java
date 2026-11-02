package com.quantfinlib.microstructure;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round 4 of the quant model layer: streaming covariance + optimal quoting. */
class QuantModels4Test {

    // ------------------------------------------------------------------
    // EwmaCovariance — the multi-asset risk picture
    // ------------------------------------------------------------------

    @Test
    void learnsThePlantedCorrelationStructure() {
        EwmaCovariance cov = new EwmaCovariance(3, 0.97);
        Random rnd = new Random(42);
        double[] r = new double[3];
        for (int i = 0; i < 3_000; i++) {
            double g = 1e-4 * rnd.nextGaussian();
            r[0] = g;
            r[1] = g;                                       // perfectly correlated
            r[2] = 1e-4 * rnd.nextGaussian();               // independent
            cov.onReturns(r);
        }
        assertTrue(cov.correlation(0, 1) > 0.99, "planted corr 1 recovered");
        assertTrue(Math.abs(cov.correlation(0, 2)) < 0.2, "independence recovered");
        assertEquals(1e-8, cov.variance(0), 5e-9, "planted variance scale recovered");
        assertEquals(cov.correlation(0, 1), cov.correlation(1, 0), 0.0, "order-free");
    }

    @Test
    void portfolioArithmeticIsExactOnAHandBuiltMatrix() {
        // One sample seeds the matrix exactly: returns [1, 1] make every
        // entry 1, so w'Σw for w=[1,1] is 4 and each leg owns half of it.
        EwmaCovariance cov = new EwmaCovariance(2, 0.94);
        cov.onReturns(new double[]{1, 1});
        assertEquals(4, cov.portfolioVariance(new double[]{1, 1}), 1e-12);
        double[] mrc = new double[2];
        cov.marginalContribution(new double[]{1, 1}, mrc);
        assertEquals(0.5, mrc[0], 1e-12, "contributions split evenly");
        assertEquals(1.0, mrc[0] + mrc[1], 1e-12, "and sum to 1");
        assertEquals(1.0, cov.minVarianceHedgeRatio(0, 1), 1e-12, "beta = cov/var");
    }

    @Test
    void aPerfectHedgeIsADegenerateRiskPictureNotACrash() {
        EwmaCovariance cov = new EwmaCovariance(2, 0.94);
        cov.onReturns(new double[]{1, -1});                 // perfectly anti-correlated
        // Long both = flat book: zero variance, so no marginal signal.
        assertEquals(0, cov.portfolioVariance(new double[]{1, 1}), 1e-12);
        double[] mrc = new double[]{99, 99};
        cov.marginalContribution(new double[]{1, 1}, mrc);
        assertEquals(0, mrc[0], 0.0, "no risk picture -> no signal");
        assertEquals(-1.0, cov.minVarianceHedgeRatio(0, 1), 1e-12);
    }

    @Test
    void aBadPrintDropsTheWholeSampleNeverHalfOfIt() {
        EwmaCovariance cov = new EwmaCovariance(2, 0.94);
        cov.onReturns(new double[]{1e-4, 1e-4});
        double before = cov.covariance(0, 1);
        cov.onReturns(new double[]{Double.NaN, 5e-4});      // one bad symbol
        cov.onReturns(new double[]{1e-4, Double.POSITIVE_INFINITY});
        assertEquals(1, cov.samples(), "gap samples never count");
        assertEquals(before, cov.covariance(0, 1), 0.0,
                "a partial update would break PSD — the matrix is untouched");
    }

    @Test
    void covarianceValidation() {
        assertThrows(IllegalArgumentException.class, () -> new EwmaCovariance(0, 0.94));
        assertThrows(IllegalArgumentException.class, () -> new EwmaCovariance(2, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new EwmaCovariance(2, 0));
        EwmaCovariance cov = new EwmaCovariance(3);
        assertThrows(IllegalArgumentException.class, () -> cov.onReturns(new double[2]));
        assertThrows(IllegalArgumentException.class,
                () -> cov.portfolioVariance(new double[2]));
        // The triangle index leaves int range past 46,340 symbols — the
        // constructor must refuse a size it cannot represent.
        assertThrows(IllegalArgumentException.class, () -> new EwmaCovariance(65_536));
    }
}
