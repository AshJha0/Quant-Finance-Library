package com.quantfinlib.util;

import com.quantfinlib.stats.LinearAlgebra;
import com.quantfinlib.stats.Numerics;
import com.quantfinlib.stats.SpecialFunctions;
import com.quantfinlib.stats.Stats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every deprecated {@link MathUtils} method returns exactly what its named
 * replacement returns, so callers can switch with no change in results.
 */
@SuppressWarnings("removal") // the subject under test is deprecated for removal in 2.4.0
class MathUtilsDelegationTest {

    private static final double[] X = {0.013, -0.021, 0.004, 0.031, -0.009, 0.017, -0.026, 0.008};
    private static final double[] Y = {0.011, -0.015, 0.002, 0.020, -0.012, 0.010, -0.019, 0.012};
    private static final double[][] M = {{4, 1.2, 0.6}, {1.2, 3, 0.4}, {0.6, 0.4, 2}};
    private static final double[] W = {0.5, 0.3, 0.2};

    @Test
    void statistics() {
        assertEquals(Stats.mean(X), MathUtils.mean(X));
        assertEquals(Stats.mean(X, 1, 5), MathUtils.mean(X, 1, 5));
        assertEquals(Stats.sampleVariance(X), MathUtils.variance(X));
        assertEquals(Stats.sampleStdDev(X), MathUtils.stdDev(X));
        assertEquals(Stats.populationStdDev(X, 1, 6), MathUtils.stdDevP(X, 1, 6));
        assertEquals(Stats.sampleStdDev(X, 1, 6), MathUtils.stdDevSample(X, 1, 6));
        assertEquals(Stats.quantile(X, 0.3), MathUtils.percentile(X, 0.3));
        assertEquals(Stats.quantileSorted(new double[]{1, 2, 4}, 0.4),
                MathUtils.percentileSorted(new double[]{1, 2, 4}, 0.4));
        assertEquals(Stats.sampleCovariance(X, Y), MathUtils.covariance(X, Y));
        assertEquals(Stats.pearsonOrZero(X, Y), MathUtils.correlation(X, Y));
        assertEquals(Stats.skewness(X), MathUtils.skewness(X));
        assertEquals(Stats.kurtosis(X), MathUtils.kurtosis(X));
    }

    @Test
    void specialFunctions() {
        assertEquals(SpecialFunctions.normCdf(-1.3), MathUtils.normCdf(-1.3));
        assertEquals(SpecialFunctions.normInv(0.2), MathUtils.normInv(0.2));
        assertEquals(SpecialFunctions.normPdf(0.5), MathUtils.normPdf(0.5));
        assertEquals(SpecialFunctions.logNormPdf(2), MathUtils.logNormPdf(2));
        assertEquals(SpecialFunctions.logGamma(3.3), MathUtils.logGamma(3.3));
        assertEquals(SpecialFunctions.regularizedIncompleteBeta(2, 3, 0.4),
                MathUtils.regularizedIncompleteBeta(2, 3, 0.4));
        assertEquals(SpecialFunctions.tCdf(1.2, 7), MathUtils.tCdf(1.2, 7));
    }

    @Test
    void linearAlgebraAndHelpers() {
        assertEquals(LinearAlgebra.dot(X, Y), MathUtils.dot(X, Y));
        assertArrayEquals(LinearAlgebra.matVec(M, W), MathUtils.matVec(M, W));
        assertEquals(LinearAlgebra.quadraticForm(W, M), MathUtils.quadraticForm(W, M));
        assertArrayEquals(LinearAlgebra.cholesky(M), MathUtils.cholesky(M));
        assertArrayEquals(LinearAlgebra.solveLinear(M, W), MathUtils.solveLinear(M, W));
        assertArrayEquals(LinearAlgebra.inverse(M), MathUtils.inverse(M));
        assertEquals(Numerics.clamp(2, 0, 1), MathUtils.clamp(2, 0, 1));
        assertArrayEquals(Numerics.nanArray(3), MathUtils.nanArray(3));
        assertEquals(Numerics.decayFactor(5, 10), MathUtils.decayFactor(5, 10));
        double[] k = {3, 1, 2};
        int[] v = {0, 1, 2};
        MathUtils.pairSort(k, v);
        assertArrayEquals(new int[]{1, 2, 0}, v);
    }
}
