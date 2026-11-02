package com.quantfinlib.util;

import com.quantfinlib.stats.SpecialFunctions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the normal CDF and its inverse to independent reference values.
 *
 * <p>Reference values were computed to 17 significant digits in 900-digit
 * decimal arithmetic (the Taylor series of the CDF for |x| &lt;= 3 and the
 * Laplace continued fraction for the Mills ratio in the tail); they agree
 * with the tables in Marsaglia (2004), "Evaluating the normal distribution",
 * J. Stat. Software 11(4). The old Abramowitz-Stegun 26.2.17 approximation
 * misses every one of the tail values by orders of magnitude in relative
 * terms (its error is 7.5e-8 absolute).</p>
 */
// Reference values keep the digits of their source; each rounds to the nearest double.
@SuppressWarnings("FloatingPointLiteralPrecision")
class MathUtilsNormalTest {

    private static final double[][] CDF = {
            {-37.5, 4.6053530095819552e-308},
            {-35, 1.1249107064724062e-268},
            {-30, 4.9067139271481872e-198},
            {-20, 2.7536241186062337e-89},
            {-10, 7.6198530241605255e-24},
            {-8, 6.2209605742717839e-16},
            {-6, 9.8658764503769809e-10},
            {-5.657, 7.7020881721776015e-09},
            {-5, 2.8665157187919391e-07},
            {-4, 3.1671241833119924e-05},
            {-3, 0.0013498980316300946},
            {-2, 0.022750131948179209},
            {-1.5, 0.066807201268858071},
            {-1, 0.15865525393145705},
            {-0.67449, 0.24999992061817369},
            {-0.5, 0.30853753872598688},
            {-0.1, 0.46017216272297101},
            {0, 0.5},
            {0.3, 0.61791142218895267},
            {1, 0.84134474606854293},
            {2, 0.97724986805182079},
            {3, 0.9986501019683699},
            {5, 0.99999971334842808},
            {8, 0.99999999999999933},
    };

    @Test
    void cdfMatchesHighPrecisionReferenceToDoublePrecision() {
        for (double[] row : CDF) {
            double got = SpecialFunctions.normCdf(row[0]);
            double rel = Math.abs(got - row[1]) / row[1];
            assertTrue(rel < 1e-15 || Math.abs(got - row[1]) <= Math.ulp(row[1]),
                    "x=" + row[0] + " got " + got + " want " + row[1] + " rel " + rel);
        }
    }

    @Test
    void cdfEdgeCases() {
        assertTrue(Double.isNaN(SpecialFunctions.normCdf(Double.NaN)));
        assertEquals(0.0, SpecialFunctions.normCdf(Double.NEGATIVE_INFINITY));
        assertEquals(1.0, SpecialFunctions.normCdf(Double.POSITIVE_INFINITY));
        assertEquals(0.0, SpecialFunctions.normCdf(-41));
        // Never negative and monotone across the region boundaries.
        double prev = 0;
        for (double x = -39; x <= 9; x += 0.03125) {
            double v = SpecialFunctions.normCdf(x);
            assertTrue(v >= prev, "not monotone at " + x);
            prev = v;
        }
        // Symmetry where both sides are representable.
        for (double x = 0; x < 8; x += 0.37) {
            assertEquals(1.0, SpecialFunctions.normCdf(x) + SpecialFunctions.normCdf(-x), 2e-16);
        }
    }

    @Test
    void inverseRoundTripsAndMatchesKnownQuantiles() {
        assertEquals(1.959963984540054, SpecialFunctions.normInv(0.975), 1e-15);
        assertEquals(2.3263478740408408, SpecialFunctions.normInv(0.99), 1e-15);
        assertEquals(-2.3263478740408408, SpecialFunctions.normInv(0.01), 1e-15);
        assertEquals(0.0, SpecialFunctions.normInv(0.5), 1e-16);
        double[] ps = {1e-300, 1e-100, 1e-20, 1e-10, 1e-5, 0.01, 0.02425, 0.3, 0.7, 0.975};
        for (double p : ps) {
            double x = SpecialFunctions.normInv(p);
            assertEquals(p, SpecialFunctions.normCdf(x), p * 1e-13, "p=" + p);
        }
        for (double q : new double[] {1e-10, 1e-6, 0.01}) {
            double x = SpecialFunctions.normInv(1 - q);
            assertEquals(q, SpecialFunctions.normCdf(-x), q * 1e-6, "1-q, q=" + q);
        }
    }
}
