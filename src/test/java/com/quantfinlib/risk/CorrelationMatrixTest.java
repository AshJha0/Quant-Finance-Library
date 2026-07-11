package com.quantfinlib.risk;

import com.quantfinlib.util.MathUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins for the matrix WRAPPER's own invariants — the inner
 * {@code MathUtils.correlation} is tested elsewhere; what can silently
 * break here is placement: diagonal, symmetry, and index order.
 */
class CorrelationMatrixTest {

    private static final double[][] RETURNS = {
            {1, 2, 3, 4},   // asset 0
            {2, 4, 6, 8},   // asset 1 = 2 * asset 0 -> corr +1
            {4, 3, 2, 1},   // asset 2 = mirror       -> corr -1
    };

    @Test
    void correlationHasUnitDiagonalAndExactSignedOffDiagonals() {
        double[][] c = CorrelationMatrix.correlation(RETURNS);
        for (int i = 0; i < 3; i++) {
            assertEquals(1.0, c[i][i], 0.0, "diagonal must be exactly 1");
        }
        assertEquals(1.0, c[0][1], 1e-12);
        assertEquals(-1.0, c[0][2], 1e-12);
        assertEquals(-1.0, c[1][2], 1e-12);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                assertEquals(c[i][j], c[j][i], 0.0, "must be exactly symmetric");
            }
        }
    }

    @Test
    void covarianceMatchesScalarEstimatorCellByCell() {
        double[][] m = CorrelationMatrix.covariance(RETURNS);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                assertEquals(MathUtils.covariance(RETURNS[i], RETURNS[j]), m[i][j], 0.0,
                        "cell (" + i + "," + j + ")");
                assertEquals(m[i][j], m[j][i], 0.0);
            }
        }
        // Scaling asset 1 by 2 quadruples its variance cell.
        assertEquals(4 * m[0][0], m[1][1], 1e-12);
    }
}
