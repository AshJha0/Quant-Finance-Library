package com.quantfinlib.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The routines moved out of {@code util.MathUtils} in 2.4.0 return the same
 * bits as 2.3.0. Expected values are the raw IEEE-754 bits printed by the
 * published 2.3.0 jar's {@code MathUtils} on the same inputs (independent
 * computation: a separate program run against the released artifact), so the
 * move cannot have changed a result without this test failing.
 */
class MovedFromMathUtilsTest {

    private static final double[] X = {0.013, -0.021, 0.004, 0.031, -0.009, 0.017, -0.026, 0.008};
    private static final double[] Y = {0.011, -0.015, 0.002, 0.020, -0.012, 0.010, -0.019, 0.012};
    private static final double[][] M = {{4, 1.2, 0.6}, {1.2, 3, 0.4}, {0.6, 0.4, 2}};
    private static final double[] W = {0.5, 0.3, 0.2};

    private static void pin(long expectedBits, double actual) {
        assertEquals(expectedBits, Double.doubleToRawLongBits(actual),
                () -> "got " + actual + ", 2.3.0 gave " + Double.longBitsToDouble(expectedBits));
    }

    @Test
    void sameBitsAsTwoThreeZero() {
        pin(0x3fa6d148ca287905L, SpecialFunctions.normCdf(-1.7));
        pin(0x3fe3c5ee2cc40b79L, SpecialFunctions.normCdf(0.3));
        pin(0x3c00a7afbb1ee67dL, SpecialFunctions.normCdf(-9));
        pin(0xc0029c5c4630ff0fL, SpecialFunctions.normInv(0.01));
        pin(0x3fe0c7e39582c5faL, SpecialFunctions.normInv(0.7));
        pin(0x3fd791bcec2c8cfdL, SpecialFunctions.normPdf(0.4));
        pin(0xc015acfe390c97d7L, SpecialFunctions.logNormPdf(3));
        pin(0x4003a140a3a623cfL, SpecialFunctions.logGamma(4.5));
        pin(0x3fb6c5040e33d645L, SpecialFunctions.regularizedIncompleteBeta(2.5, 1.5, 0.3));
        pin(0x3fa6fa11a8f6cc06L, SpecialFunctions.tCdf(-2.1, 5));
        pin(0xbfc2cdae5bfce57fL, Stats.skewness(X));
        pin(0x3ffe7c639738beecL, Stats.kurtosis(X));
        pin(0x3fef1b745840e896L, Stats.pearsonOrZero(X, Y));
        pin(0x3ffe0c49ba5e3540L, LinearAlgebra.quadraticForm(W, M));
        pin(0x3f6001d5c31593e6L, LinearAlgebra.dot(X, Y));
        pin(0x3fe3333333333333L, LinearAlgebra.cholesky(M)[1][0]);
        pin(0x3ff6018d221865d2L, LinearAlgebra.cholesky(M)[2][2]);
        pin(0x3fb9bbb83bdf9a4cL, LinearAlgebra.solveLinear(M, W)[0]);
        pin(0x3fae7307e4ef156eL, LinearAlgebra.solveLinear(M, W)[2]);
        pin(0xbfbbae64447c4206L, LinearAlgebra.inverse(M)[0][1]);
        pin(0x3fe0ea929ba144cbL, LinearAlgebra.inverse(M)[2][2]);
        pin(0x3fe9fdf8bcce533dL, Numerics.decayFactor(3_000_000_000L, 10_000_000_000L));
        pin(0x3ff0000000000000L, Numerics.clamp(1.7, -1, 1));
    }

    @Test
    void zeroDispersionConventionsKept() {
        double[] flat = {1, 1, 1, 1};
        assertEquals(0.0, Stats.pearsonOrZero(flat, new double[]{1, 2, 3, 4}));
        assertEquals(0.0, Stats.skewness(flat));
        assertEquals(0.0, Stats.kurtosis(flat));
        assertEquals(1.0, Numerics.decayFactor(0, 5));
    }

    @Test
    void pairSortAndNanArray() {
        double[] k = {3, 1, 2};
        int[] v = {0, 1, 2};
        Numerics.pairSort(k, v);
        assertArrayEquals(new double[]{1, 2, 3}, k);
        assertArrayEquals(new int[]{1, 2, 0}, v);
        double[] n = Numerics.nanArray(2);
        assertEquals(2, n.length);
        assertEquals(Double.NaN, n[0]);
    }
}
