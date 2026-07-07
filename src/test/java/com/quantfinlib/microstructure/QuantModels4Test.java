package com.quantfinlib.microstructure;

import com.quantfinlib.trading.AvellanedaStoikov;
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

    // ------------------------------------------------------------------
    // AvellanedaStoikov — optimal quoting
    // ------------------------------------------------------------------

    @Test
    void quotesMatchTheClosedForm() {
        double gamma = 0.1;
        double kappa = 1.5;
        AvellanedaStoikov as = new AvellanedaStoikov(gamma, kappa);
        double var = 1e-4;                                  // price variance / sec
        double tau = 60;
        double mid = 100;
        double inv = 100;

        double expectedR = mid - inv * gamma * var * tau;
        assertEquals(expectedR, as.reservationPrice(mid, inv, var, tau), 1e-12);

        double expectedHalf = 0.5 * gamma * var * tau + Math.log(1 + gamma / kappa) / gamma;
        assertEquals(expectedHalf, as.optimalHalfSpread(var, tau), 1e-12);

        assertEquals(expectedR - expectedHalf, as.bidQuote(mid, inv, var, tau), 1e-12);
        assertEquals(expectedR + expectedHalf, as.askQuote(mid, inv, var, tau), 1e-12);
    }

    @Test
    void longInventoryShadesBothQuotesDown() {
        AvellanedaStoikov as = new AvellanedaStoikov(0.1, 1.5);
        double flatBid = as.bidQuote(100, 0, 1e-4, 60);
        double flatAsk = as.askQuote(100, 0, 1e-4, 60);
        assertTrue(as.bidQuote(100, 500, 1e-4, 60) < flatBid,
                "long: make buying less attractive");
        assertTrue(as.askQuote(100, 500, 1e-4, 60) < flatAsk,
                "long: make selling more attractive");
        assertTrue(as.askQuote(100, -500, 1e-4, 60) > flatAsk, "short mirrors");
    }

    @Test
    void spreadWidensWithVolatilityAndHorizon() {
        AvellanedaStoikov as = new AvellanedaStoikov(0.1, 1.5);
        assertTrue(as.optimalHalfSpread(4e-4, 60) > as.optimalHalfSpread(1e-4, 60),
                "wilder market -> wider quotes");
        assertTrue(as.optimalHalfSpread(1e-4, 600) > as.optimalHalfSpread(1e-4, 60),
                "longer inventory horizon -> wider quotes");
    }

    @Test
    void riskNeutralLimitIsThePureLiquiditySpread() {
        // gamma -> 0: (1/gamma)·ln(1 + gamma/kappa) -> 1/kappa.
        AvellanedaStoikov nearNeutral = new AvellanedaStoikov(1e-9, 1.5);
        assertEquals(1 / 1.5, nearNeutral.optimalHalfSpread(0, 0), 1e-6,
                "even a risk-neutral maker charges for immediacy");
        // Regression: plain log(1+x) rounds to 0 below x ~ 1e-16 and quoted
        // a ZERO-width spread; log1p keeps the floor exact.
        AvellanedaStoikov tiny = new AvellanedaStoikov(1e-16, 1.5);
        assertEquals(1 / 1.5, tiny.optimalHalfSpread(0, 0), 1e-9,
                "the liquidity floor survives arbitrarily small gamma");
    }

    @Test
    void garbageVarianceIsNeutralNeverPoisonous() {
        AvellanedaStoikov as = new AvellanedaStoikov(0.1, 1.5);
        double floor = as.optimalHalfSpread(0, 60);
        assertEquals(100, as.reservationPrice(100, 500, Double.NaN, 60), 1e-12,
                "NaN variance: no shade, reservation = mid");
        assertEquals(floor, as.optimalHalfSpread(Double.NaN, 60), 1e-12);
        assertEquals(floor, as.optimalHalfSpread(-1, 60), 1e-12);
        assertEquals(floor, as.optimalHalfSpread(Double.POSITIVE_INFINITY, 60), 1e-12,
                "an infinite variance print must not quote an infinite spread");
        assertThrows(IllegalArgumentException.class, () -> new AvellanedaStoikov(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new AvellanedaStoikov(1, 0));
    }

    // ------------------------------------------------------------------
    // Allocation
    // ------------------------------------------------------------------

    @Test
    void streamingUpdatesAreAllocationFree() {
        EwmaCovariance cov = new EwmaCovariance(8, 0.97);
        AvellanedaStoikov as = new AvellanedaStoikov(0.1, 1.5);
        double[] r = new double[8];
        double[] w = new double[8];
        double[] mrc = new double[8];
        java.util.Arrays.fill(w, 1_000);
        double blackhole = 0;
        for (int i = 0; i < 200_000; i++) {                 // warm-up
            blackhole += step(cov, as, r, w, mrc, i);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            blackhole += step(cov, as, r, w, mrc, i);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "round-4 models allocated " + allocated + " bytes");
        assertTrue(blackhole != 0);
    }

    private static double step(EwmaCovariance cov, AvellanedaStoikov as,
                               double[] r, double[] w, double[] mrc, int i) {
        for (int s = 0; s < r.length; s++) {
            r[s] = ((i * 31 + s * 17) % 200 - 100) * 1e-6;
        }
        cov.onReturns(r);
        cov.marginalContribution(w, mrc);
        return cov.portfolioVariance(w) + mrc[3] + cov.correlation(0, 5)
                + as.bidQuote(100, (i % 200) - 100, 1e-4, 60)
                + as.optimalHalfSpread(1e-4, 60);
    }
}
