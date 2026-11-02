package com.quantfinlib.risk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * FRTB SA, DRC, RRAO and NMRF against hand computations (own test; each
 * expected value is worked in the comment beside it).
 */
class FrtbSaTest {

    private static final double TOL = 1e-9;
    private static final FrtbSa.Scenario M = FrtbSa.Scenario.MEDIUM;

    private static FrtbSa.Bucket girr() {
        double[] t = {1, 2};
        return FrtbSa.Bucket.of(new double[] {100, -50},
                new double[] {FrtbSa.Girr.RISK_WEIGHTS[2], FrtbSa.Girr.RISK_WEIGHTS[3]},
                FrtbSa.Girr.tenorMatrix(t));
    }

    @Test
    void scenarioScaling() {
        assertEquals(0.5, M.apply(0.5), TOL);
        assertEquals(0.625, FrtbSa.Scenario.HIGH.apply(0.5), TOL);
        assertEquals(1.0, FrtbSa.Scenario.HIGH.apply(0.9), TOL);
        assertEquals(0.375, FrtbSa.Scenario.LOW.apply(0.5), TOL);  // max(0, 0.375)
        assertEquals(0.8, FrtbSa.Scenario.LOW.apply(0.9), TOL);    // max(0.8, 0.675)
    }

    @Test
    void girrDeltaBucketAllScenarios() {
        double rho = Math.exp(-0.03);
        assertEquals(rho, FrtbSa.Girr.tenorCorrelation(1, 2), TOL);
        assertEquals(0.4, FrtbSa.Girr.tenorCorrelation(0.25, 30), TOL);
        // WS = 1.6, -0.65; K^2 = 2.56 + 0.4225 + 2 rho (1.6)(-0.65)
        assertEquals(Math.sqrt(2.9825 - 2.08 * rho), FrtbSa.bucket(girr(), M).k(), TOL);
        assertEquals(0.95, FrtbSa.bucket(girr(), FrtbSa.Scenario.HIGH).k(), TOL);
        double low = 2 * rho - 1;
        assertEquals(Math.sqrt(2.9825 - 2.08 * low),
                FrtbSa.bucket(girr(), FrtbSa.Scenario.LOW).k(), TOL);
        assertEquals(0.95, FrtbSa.bucket(girr(), M).s(), TOL);  // sum 0.95 < K
    }

    private static List<FrtbSa.Bucket> fx() {
        double rw = FrtbSa.Fx.RISK_WEIGHT;
        return List.of(FrtbSa.Bucket.of(new double[] {100}, new double[] {rw}, FrtbSa.constant(1, 0)),
                FrtbSa.Bucket.of(new double[] {200}, new double[] {rw}, FrtbSa.constant(1, 0)));
    }

    @Test
    void fxDeltaAcrossBuckets() {
        double[][] g = FrtbSa.constant(2, FrtbSa.Fx.GAMMA);
        // WS 15, 30: 225 + 900 + 2 gamma 450
        assertEquals(Math.sqrt(1665), FrtbSa.deltaVega(fx(), g, M), TOL);
        assertEquals(Math.sqrt(1800), FrtbSa.deltaVega(fx(), g, FrtbSa.Scenario.HIGH), TOL);
        assertEquals(Math.sqrt(1530), FrtbSa.deltaVega(fx(), g, FrtbSa.Scenario.LOW), TOL);
    }

    @Test
    void equityHedgedBucket() {
        double rw = FrtbSa.Equity.SPOT_RW[4];
        FrtbSa.Bucket b = FrtbSa.Bucket.of(new double[] {100, -100}, new double[] {rw, rw},
                FrtbSa.constant(2, FrtbSa.Equity.intraBucket(5)));
        // WS 30, -30: 1800 - 2(0.25)(900) = 1350; S = 0
        assertEquals(Math.sqrt(1350), FrtbSa.bucket(b, M).k(), TOL);
        assertEquals(0, FrtbSa.bucket(b, M).s(), TOL);
        assertEquals(0.45, FrtbSa.Equity.gamma(3, 12), TOL);
        assertEquals(0.75, FrtbSa.Equity.gamma(12, 13), TOL);
        assertEquals(0, FrtbSa.Equity.gamma(11, 2), TOL);
        assertThrows(IllegalArgumentException.class, () -> FrtbSa.Equity.intraBucket(11));
    }

    @Test
    void commodityBucket() {
        double rw = FrtbSa.Commodity.RW[1];
        FrtbSa.Bucket b = FrtbSa.Bucket.of(new double[] {100, 100}, new double[] {rw, rw},
                FrtbSa.constant(2, FrtbSa.Commodity.RHO_CTY[1]));
        // WS 35, 35: 2450 + 2(0.95)(1225) = 4777.5
        assertEquals(Math.sqrt(4777.5), FrtbSa.bucket(b, M).k(), TOL);
    }

    @Test
    void csrBucket() {
        double rw = FrtbSa.Csr.RW[2];
        double rho = FrtbSa.Csr.intraBucket(false, true, true);
        assertEquals(0.35, rho, TOL);
        assertEquals(0.35 * 0.65, FrtbSa.Csr.intraBucket(false, false, true), TOL);
        FrtbSa.Bucket b = FrtbSa.Bucket.of(new double[] {100, 100}, new double[] {rw, rw},
                FrtbSa.constant(2, rho));
        // WS 5, 5: 50 + 2(0.35)(25) = 67.5
        assertEquals(Math.sqrt(67.5), FrtbSa.bucket(b, M).k(), TOL);
    }

    @Test
    void curvature() {
        // -min(-5 - 0 - 2, -3 - 0 + 2) = 7
        assertEquals(7, FrtbSa.cvr(-5, 0, -3, 2), TOL);
        FrtbSa.Bucket b = new FrtbSa.Bucket(new double[] {7, -3}, FrtbSa.constant(2, 0.5));
        // 49 + max(-3,0)^2 + 2 (0.5^2)(7)(-3) = 38.5
        assertEquals(Math.sqrt(38.5), FrtbSa.curvature(List.of(b), FrtbSa.constant(1, 0), M), TOL);
        // both negative: psi = 0, K = 0
        FrtbSa.Bucket neg = new FrtbSa.Bucket(new double[] {-2, -3}, FrtbSa.constant(2, 0.5));
        assertEquals(0, FrtbSa.curvature(List.of(neg), FrtbSa.constant(1, 0), M), TOL);
    }

    @Test
    void scenarioMaxAcrossClasses() {
        FrtbSa.RiskClass g = new FrtbSa.RiskClass(List.of(girr()), FrtbSa.constant(1, 0),
                List.of(), new double[0][0], List.of(), new double[0][0]);
        FrtbSa.RiskClass f = new FrtbSa.RiskClass(fx(), FrtbSa.constant(2, 0.6),
                List.of(), new double[0][0], List.of(), new double[0][0]);
        // HIGH: sqrt(1800) + 0.95 beats MEDIUM and LOW
        assertEquals(Math.sqrt(1800) + 0.95, FrtbSa.sbmCapital(List.of(g, f)), TOL);
    }

    @Test
    void vegaParameters() {
        assertEquals(1.0, FrtbSa.vegaRiskWeight(FrtbSa.VEGA_LH_GIRR), TOL);
        assertEquals(0.55 * Math.sqrt(2), FrtbSa.vegaRiskWeight(FrtbSa.VEGA_LH_EQUITY_LARGE_CAP), TOL);
        assertEquals(Math.exp(-0.01), FrtbSa.vegaMaturityCorrelation(1, 2), TOL);
    }

    @Test
    void drc() {
        assertEquals(75, FrtbDrc.jtd(100, FrtbDrc.LGD_SENIOR, 0), TOL);
        assertEquals(-70, FrtbDrc.jtd(-100, FrtbDrc.LGD_SENIOR, 5), TOL);
        assertEquals(0.25, FrtbDrc.maturityScale(0.1), TOL);
        assertEquals(1, FrtbDrc.maturityScale(5), TOL);
        List<FrtbDrc.NetJtd> b = List.of(
                new FrtbDrc.NetJtd(100, FrtbDrc.CreditQuality.BBB.riskWeight()),
                new FrtbDrc.NetJtd(-50, FrtbDrc.CreditQuality.A.riskWeight()));
        // WtS = 2/3; 6 - (2/3)(1.5) = 5
        assertEquals(2.0 / 3, FrtbDrc.hedgeBenefitRatio(b), TOL);
        assertEquals(5, FrtbDrc.bucketCharge(b), TOL);
        // all-short bucket floors at 0; total sums buckets
        List<FrtbDrc.NetJtd> s = List.of(new FrtbDrc.NetJtd(-10, 0.5));
        assertEquals(5, FrtbDrc.total(List.of(b, s)), TOL);
    }

    @Test
    void rrao() {
        assertEquals(15, FrtbRrao.charge(1000, 5000), TOL);
        assertThrows(IllegalArgumentException.class, () -> FrtbRrao.charge(-1, 0));
    }

    @Test
    void nmrf() {
        // 5 + 5 + sqrt(0.36 * 400 + 0.64 * 200)
        assertEquals(10 + Math.sqrt(272),
                FrtbNmrf.ses(new double[] {3, 4}, new double[] {5}, new double[] {10, 10}), TOL);
        assertThrows(IllegalArgumentException.class,
                () -> FrtbNmrf.ses(new double[] {-1}, new double[0], new double[0]));
    }
}
