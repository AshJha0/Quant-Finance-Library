package com.quantfinlib.trading;

import com.quantfinlib.microstructure.EwmaCovariance;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Avellaneda-Stoikov optimal quoting, moved from the main module's
 * QuantModels4Test in 3.0 when {@code trading} moved to the low-latency module.
 */
class AvellanedaStoikovTest {

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
