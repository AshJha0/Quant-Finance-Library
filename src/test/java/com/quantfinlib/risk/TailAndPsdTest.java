package com.quantfinlib.risk;

import com.quantfinlib.simulation.SimulationResult;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TailAndPsdTest {

    // ------------------------------------------------------------------
    // Acerbi-Tasche ES
    // ------------------------------------------------------------------

    @Test
    void esOfOneToThousandAt975IsTheMeanOfTheTop25() {
        double[] l = new double[1000];
        for (int i = 0; i < 1000; i++) {
            l[i] = 1000 - i;
        }
        TailRisk.Tail t = TailRisk.of(l, 0.975);
        assertEquals(975, t.var(), 0);
        assertEquals(988.0, t.expectedShortfall(), 1e-12);
    }

    @Test
    void fractionalTailWeightAndSmallSamples() {
        double[] l = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        // c = 0.75: w = 2.5 -> (10 + 9 + 0.5*8)/2.5 = 9.2; VaR = L(ceil(7.5)) = 8.
        assertEquals(9.2, TailRisk.of(l, 0.75).expectedShortfall(), 1e-12);
        assertEquals(8, TailRisk.of(l, 0.75).var(), 0);
        // w < 1: worst observation.
        assertEquals(10, TailRisk.of(l, 0.95).expectedShortfall(), 0);
        assertThrows(IllegalArgumentException.class, () -> TailRisk.of(new double[]{1, Double.NaN}, 0.9));
    }

    /** ES converges to the normal closed form phi(z)/(1-c), and is never below VaR. */
    @Test
    void esConvergesToTheGaussianTailMean() {
        SplittableRandom rnd = new SplittableRandom(3);
        int n = 400_000;
        double[] l = new double[n];
        for (int i = 0; i < n; i++) {
            l[i] = rnd.nextGaussian();
        }
        TailRisk.Tail t = TailRisk.of(l, 0.975);
        double z = 1.959963984540054;
        double es = Math.exp(-0.5 * z * z) / Math.sqrt(2 * Math.PI) / 0.025;   // 2.337807
        assertEquals(es, t.expectedShortfall(), 0.02);
        assertEquals(z, t.var(), 0.02);
        assertTrue(t.expectedShortfall() >= t.var());
    }

    @Test
    void simulationResultTailsAreConsistentForSmallSamples() {
        // 20 terminal values: 80, 81, ..., 99 on a start of 100.
        double[] finals = new double[20];
        for (int i = 0; i < 20; i++) {
            finals[i] = 80 + i;
        }
        SimulationResult r = new SimulationResult(100, finals);
        // c = 0.9: w = 2 -> ES = mean(20%, 19%) = 19.5%; VaR = L(18) = 18%.
        assertEquals(0.195, r.conditionalValueAtRisk(0.9), 1e-12);
        assertEquals(0.18, r.valueAtRisk(0.9), 1e-12);
        for (double c = 0.51; c < 0.999; c += 0.01) {
            assertTrue(r.conditionalValueAtRisk(c) >= r.valueAtRisk(c) - 1e-15, "c=" + c);
        }
    }

    @Test
    void riskMetricsConditionalVarUsesTheSameEstimator() {
        double[] returns = new double[40];
        for (int i = 0; i < 40; i++) {
            returns[i] = (i - 20) / 1000.0;     // -2.0% ... +1.9%
        }
        // c = 0.95: w = 2 -> mean(2.0%, 1.9%) = 1.95%.
        assertEquals(0.0195, RiskMetrics.conditionalVar(returns, 0.95), 1e-12);
    }

    // ------------------------------------------------------------------
    // PSD factorisation and repair
    // ------------------------------------------------------------------

    @Test
    void choleskyPsdFactorsARankDeficientCovariance() {
        // Two identical assets plus one independent: rank 2.
        double[][] a = {{0.04, 0.04, 0.0}, {0.04, 0.04, 0.0}, {0.0, 0.0, 0.09}};
        double[][] l = PsdMatrices.choleskyPsd(a);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double s = 0;
                for (int k = 0; k < 3; k++) {
                    s += l[i][k] * l[j][k];
                }
                assertEquals(a[i][j], s, 1e-15);
            }
        }
        assertEquals(0, l[1][1], 0);
        // VarEngine Monte Carlo now accepts the same matrix the simulator does.
        VarEngine.VarResult mc = VarEngine.monteCarloVar(new double[]{1, 1, 1}, a, 0.99, 20_000, 1);
        assertTrue(mc.var() > 0);
    }

    @Test
    void choleskyPsdRejectsAnIndefiniteMatrix() {
        double[][] bad = {{1, 0.9, 0.9}, {0.9, 1, -0.9}, {0.9, -0.9, 1}};
        assertThrows(IllegalArgumentException.class, () -> PsdMatrices.choleskyPsd(bad));
    }

    /** Higham (2002), section 4 example: the nearest correlation matrix to a tridiagonal ones matrix. */
    @Test
    void nearestCorrelationReproducesHighamsExample() {
        double[][] a = {{1, 1, 0}, {1, 1, 1}, {0, 1, 1}};
        double[][] x = PsdMatrices.nearestCorrelation(a);
        assertEquals(0.7607, x[0][1], 5e-5);
        assertEquals(0.1573, x[0][2], 5e-5);
        assertEquals(0.7607, x[1][2], 5e-5);
        for (int i = 0; i < 3; i++) {
            assertEquals(1, x[i][i], 0);
        }
        double[] eig = PsdMatrices.symmetricEigen(x).values();
        assertTrue(eig[2] > -1e-10);
        // The result is factorable.
        PsdMatrices.choleskyPsd(x);
    }

    @Test
    void nearestPsdClipsNegativeEigenvalues() {
        double[][] a = {{2, 0}, {0, -1}};
        double[][] x = PsdMatrices.nearestPsd(a);
        assertEquals(2, x[0][0], 1e-15);
        assertEquals(0, x[1][1], 1e-15);
    }

    @Test
    void eigenDecompositionOfAKnownMatrix() {
        // [[2,1],[1,2]]: eigenvalues 3 and 1.
        PsdMatrices.Eigen e = PsdMatrices.symmetricEigen(new double[][]{{2, 1}, {1, 2}});
        assertEquals(3, e.values()[0], 1e-14);
        assertEquals(1, e.values()[1], 1e-14);
        assertEquals(Math.abs(e.vectors()[0][0]), Math.sqrt(0.5), 1e-14);
    }
}
