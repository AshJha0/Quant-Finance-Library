package com.quantfinlib.risk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Supervisory parameters and aggregation rules checked against BCBS d457
 * (January 2019) paragraph by paragraph (published reference; the
 * worked examples are own hand computations shown beside each assert).
 */
class FrtbSaParametersTest {

    private static final double TOL = 1e-12;
    private static final FrtbSa.Scenario M = FrtbSa.Scenario.MEDIUM;

    @Test
    void girrTable1AndCorrelations() {
        // MAR21.42 Table 1, MAR21.43
        assertArrayEquals(new double[] {0.017, 0.017, 0.016, 0.013, 0.012, 0.011, 0.011, 0.011,
            0.011, 0.011}, FrtbSa.Girr.RISK_WEIGHTS, TOL);
        assertEquals(0.016, FrtbSa.Girr.INFLATION_RW, TOL);
        assertEquals(0.016, FrtbSa.Girr.XCCY_BASIS_RW, TOL);
        // MAR21.46 footnote 13: 1y vs 5y = 88.69%; Table 2 0.25y vs 1y = 91.4%
        assertEquals(0.8869, FrtbSa.Girr.tenorCorrelation(1, 5), 5e-5);
        assertEquals(0.914, FrtbSa.Girr.tenorCorrelation(0.25, 1), 5e-4);
        assertEquals(0.419, FrtbSa.Girr.tenorCorrelation(0.5, 15), 5e-4);
        // MAR21.47 footnote 14: 88.60%; MAR21.45 same tenor 99.9%
        assertEquals(0.8860, FrtbSa.Girr.differentCurveCorrelation(1, 5), 5e-5);
        assertEquals(0.999, FrtbSa.Girr.differentCurveCorrelation(2, 2), TOL);
        assertEquals(0.4, FrtbSa.Girr.RHO_INFLATION, TOL);    // MAR21.48
        assertEquals(0.0, FrtbSa.Girr.RHO_XCCY_BASIS, TOL);   // MAR21.49
        assertEquals(0.5, FrtbSa.Girr.GAMMA, TOL);            // MAR21.50
    }

    @Test
    void csrTable4Table5AndIndexBuckets() {
        // MAR21.53 Table 4
        assertArrayEquals(new double[] {0.005, 0.01, 0.05, 0.03, 0.03, 0.02, 0.015, 0.025, 0.02,
            0.04, 0.12, 0.07, 0.085, 0.055, 0.05, 0.12, 0.015, 0.05}, FrtbSa.Csr.RW, TOL);
        // MAR21.54 footnote 18: 35% x 65% x 99.9% = 22.73%
        assertEquals(0.2273, FrtbSa.Csr.intraBucket(false, false, false), 5e-5);
        // MAR21.55: index buckets use 80% for different names
        assertEquals(0.8 * 0.65, FrtbSa.Csr.intraBucketIndex(false, false, true), TOL);
        // MAR21.57: same sector, different rating (1 vs 9) = 50%
        assertEquals(0.5, FrtbSa.Csr.gamma(1, 9), TOL);
        // 1 vs 2 (both IG) = Table 5 75%; 1 vs 10 = 50% x 75%
        assertEquals(0.75, FrtbSa.Csr.gamma(1, 2), TOL);
        assertEquals(0.375, FrtbSa.Csr.gamma(1, 10), TOL);
    }

    @Test
    void csrTable5Spots() {
        assertEquals(0.5 * 0.05, FrtbSa.Csr.gamma(12, 8), TOL); // HY 12 vs IG 8: 50% x 5%
        assertEquals(0.05, FrtbSa.Csr.gamma(4, 8), TOL);      // both IG
        assertEquals(0.25, FrtbSa.Csr.gamma(5, 6), TOL);      // 5/13 vs 6/14
        assertEquals(0.0, FrtbSa.Csr.gamma(16, 3), TOL);      // other sector
        assertEquals(0.45, FrtbSa.Csr.gamma(17, 11), TOL);    // index vs sector, rating factor 1
        assertEquals(0.75, FrtbSa.Csr.gamma(17, 18), TOL);
        assertEquals(FrtbSa.Csr.gamma(7, 14), FrtbSa.Csr.gamma(14, 7), TOL);
    }

    @Test
    void ctpAndNonCtpSecuritisation() {
        // MAR21.59 Table 6, MAR21.60
        assertEquals(0.16, FrtbSa.Csr.CTP_RW[10], TOL);
        assertEquals(0.13, FrtbSa.Csr.CTP_RW[15], TOL);
        assertEquals(0.35 * 0.99, FrtbSa.Csr.intraBucketCtp(false, true, false), TOL);
        // MAR21.64 Table 8 and MAR21.65-67 scaling
        assertEquals(0.009, FrtbSa.SecNonCtp.riskWeight(1), TOL);
        assertEquals(0.015 * 1.25, FrtbSa.SecNonCtp.riskWeight(10), TOL);
        assertEquals(0.014 * 1.75, FrtbSa.SecNonCtp.riskWeight(24), TOL);
        assertEquals(0.035, FrtbSa.SecNonCtp.riskWeight(25), TOL);
        // MAR21.68: 40% x 80% x 99.9%
        assertEquals(0.4 * 0.8 * 0.999, FrtbSa.SecNonCtp.intraBucket(false, false, false), TOL);
    }

    @Test
    void equityRepoAndCommodity() {
        // MAR21.77 Table 10: repo = 1/100 of spot for every bucket
        for (int i = 0; i < 13; i++) {
            assertEquals(FrtbSa.Equity.SPOT_RW[i] / 100, FrtbSa.Equity.REPO_RW[i], TOL);
        }
        assertEquals(0.70, FrtbSa.Equity.SPOT_RW[10], TOL);   // bucket 11
        assertEquals(0.25 * 0.999, FrtbSa.Equity.spotRepoDifferentIssuer(6), TOL);
        // MAR21.84 footnote 21: 95% x 99% x 99.9% = 93.96%
        assertEquals(0.9396, FrtbSa.Commodity.RHO_CTY[1] * FrtbSa.Commodity.RHO_TENOR
                * FrtbSa.Commodity.RHO_BASIS, 5e-5);
        assertEquals(0.50, FrtbSa.Commodity.RW[10], TOL);     // bucket 11
        assertEquals(0.0, FrtbSa.Commodity.gamma(11, 3), TOL); // MAR21.85(2)
        assertEquals(0.2, FrtbSa.Commodity.gamma(2, 3), TOL);
    }

    @Test
    void deltaUsesUnclampedSumFirst() {
        // MAR21.4(5)(a). Bucket A: WS {10, 10} rho 0 -> K = sqrt(200), sum 20.
        // Bucket B: WS {-1} -> K = 1, sum -1. gamma 0.5.
        // Unclamped: 200 + 1 + 2(0.5)(20)(-1) = 181. Clamped would give
        // S_A = 14.142: 200 + 1 - 14.142 = 186.86.
        FrtbSa.Bucket a = new FrtbSa.Bucket(new double[] {10, 10}, FrtbSa.constant(2, 0));
        FrtbSa.Bucket b = new FrtbSa.Bucket(new double[] {-1}, FrtbSa.constant(1, 0));
        double[][] g = FrtbSa.constant(2, 0.5);
        assertEquals(Math.sqrt(181), FrtbSa.deltaVega(List.of(a, b), g, M), 1e-9);
        // Alternative only when negative: A {10,10} rho 0, B {-10,-10} rho 0, gamma 1
        // unclamped: 200 + 200 - 2(20)(20) < 0; clamped: 400 - 2(200) = 0.
        FrtbSa.Bucket c = new FrtbSa.Bucket(new double[] {-10, -10}, FrtbSa.constant(2, 0));
        assertEquals(0, FrtbSa.deltaVega(List.of(a, c), FrtbSa.constant(2, 1), M), 1e-6);
    }

    @Test
    void otherSectorBucketIsAbsoluteSum() {
        // MAR21.79(1): equity bucket 11 K = sum |WS| = 30 + 20, gamma 0 to others
        FrtbSa.Bucket other = new FrtbSa.Bucket(new double[] {30, -20}, FrtbSa.constant(2, 0.9));
        FrtbSa.Bucket b1 = new FrtbSa.Bucket(new double[] {40}, FrtbSa.constant(1, 0));
        double[][] g = {{1, 0}, {0, 1}};
        assertEquals(Math.sqrt(2500 + 1600),
                FrtbSa.deltaVega(List.of(other, b1), g, M, Set.of(0)), 1e-9);
        // MAR21.79(2) curvature: max(5 + 0, 4) = 5
        assertEquals(5, FrtbSa.otherSectorCurvatureK(new double[] {5, -1}, new double[] {4}), TOL);
    }

    @Test
    void curvatureScenarioAppliedToSquaredCorrelation() {
        // MAR21.100-101. rho = 0.5 -> squared 0.25. LOW on squared:
        // max(2(0.25) - 1, 0.75(0.25)) = 0.1875 (old order gave 0.375^2 = 0.140625).
        FrtbSa.Bucket b = new FrtbSa.Bucket(new double[] {4, 3}, FrtbSa.constant(2, 0.5));
        double expected = Math.sqrt(16 + 9 + 2 * 0.1875 * 12);
        assertEquals(expected, FrtbSa.curvature(List.of(b), FrtbSa.constant(1, 0),
                FrtbSa.Scenario.LOW), 1e-9);
        // HIGH on squared: min(1.25 x 0.25, 1) = 0.3125
        assertEquals(Math.sqrt(25 + 24 * 0.3125), FrtbSa.curvature(List.of(b),
                FrtbSa.constant(1, 0), FrtbSa.Scenario.HIGH), 1e-9);
    }

    @Test
    void curvatureUpDownSelection() {
        // MAR21.5(3)(a): bucket 1 up {4} K=4, down {3} K=3 -> up, S = 4.
        // bucket 2 up {-2} K=0, down {1} K=1 -> down, S = 1. gamma 0.5 -> 0.25.
        // 16 + 1 + 2(0.25)(4)(1) = 19
        FrtbSa.Bucket u1 = new FrtbSa.Bucket(new double[] {4}, FrtbSa.constant(1, 0));
        FrtbSa.Bucket d1 = new FrtbSa.Bucket(new double[] {3}, FrtbSa.constant(1, 0));
        FrtbSa.Bucket u2 = new FrtbSa.Bucket(new double[] {-2}, FrtbSa.constant(1, 0));
        FrtbSa.Bucket d2 = new FrtbSa.Bucket(new double[] {1}, FrtbSa.constant(1, 0));
        assertEquals(Math.sqrt(19), FrtbSa.curvature(List.of(u1, u2), List.of(d1, d2),
                FrtbSa.constant(2, 0.5), M), 1e-9);
        // CSR curvature uses rho_name only (MAR21.100(1))
        assertEquals(0.35, FrtbSa.Csr.curvatureRho(false, false), TOL);
    }

    @Test
    void vegaTable13() {
        // MAR21.92 Table 13: 100% except equity large cap 77.78%
        assertEquals(1.0, FrtbSa.vegaRiskWeight(FrtbSa.VEGA_LH_CSR), TOL);
        assertEquals(1.0, FrtbSa.vegaRiskWeight(FrtbSa.VEGA_LH_FX), TOL);
        assertEquals(1.0, FrtbSa.vegaRiskWeight(FrtbSa.VEGA_LH_EQUITY_SMALL_CAP), TOL);
        assertEquals(0.7778, FrtbSa.vegaRiskWeight(FrtbSa.VEGA_LH_EQUITY_LARGE_CAP), 5e-5);
    }

    @Test
    void drcObligorNetting() {
        // MAR22.19(3)(b): 3-month short of 100 against a 2y long of 100 nets to 75
        assertEquals(75, FrtbDrc.netObligor(new double[] {100, -100}, new double[] {2, 0.25}),
                TOL);
        // MAR22.12: covered bond LGD 25%
        assertEquals(25, FrtbDrc.jtd(100, FrtbDrc.LGD_COVERED_BOND, 0), TOL);
    }
}
