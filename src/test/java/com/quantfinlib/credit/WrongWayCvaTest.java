package com.quantfinlib.credit;

import com.quantfinlib.rates.YieldCurve;
import com.quantfinlib.util.MathUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Path-consistent wrong-way CVA: each bucket's default driver is the
 * market factor relevant to that bucket's exposure (level when
 * uncollateralised, increment over the margin period of risk when a CSA
 * is present). Pinned by sign and monotonicity in rho, the rho = 0
 * identity, and a single-bucket FX forward against direct quadrature of
 * E[D E(T) P(default | X)].
 */
class WrongWayCvaTest {

    private static final double A = 0.05;
    private static final double SIG = 0.01;
    private static final double[] RHOS = {-0.75, -0.5, -0.25, 0, 0.25, 0.5, 0.75};

    private static YieldCurve dom() {
        return YieldCurve.ofZeroRates(new double[]{0.5, 1, 2, 3, 5, 7, 10},
                new double[]{0.025, 0.027, 0.029, 0.031, 0.033, 0.034, 0.035});
    }

    private static double[] quarterly() {
        double[] times = new double[20];
        for (int i = 0; i < 20; i++) {
            times[i] = 0.25 * (i + 1);
        }
        return times;
    }

    private static ExposureProfile payer(ExposureSimulator.Csa csa) {
        return ExposureSimulator.simulate(ExposureSimulator.Market.ratesOnly(dom(), A, SIG),
                List.of(new ExposureSimulator.Swap(100, 0.032, true, 0, 5, 1)), List.of(), csa,
                quarterly(), 4_000, 9L);
    }

    private static CreditCurve cpty() {
        return CreditCurve.bootstrap(new int[]{5}, new double[]{0.02}, 0.4, dom());
    }

    private static void checkWrongAndRightWay(ExposureProfile prof) {
        CreditCurve c = cpty();
        double indep = CvaApproximator.cva(prof, c, 0.6);
        assertEquals(indep, CvaApproximator.cvaWrongWay(prof, c, 0.6, 0, ExposureProfile.Factor.RATES),
                1e-12 * indep);
        double prev = Double.NEGATIVE_INFINITY;
        for (double rho : RHOS) {
            double w = CvaApproximator.cvaWrongWay(prof, c, 0.6, rho, ExposureProfile.Factor.RATES);
            assertTrue(w > prev, "not increasing at rho " + rho + ": " + w + " vs " + prev);
            if (rho > 0) {
                assertTrue(w > indep, "rho " + rho + ": " + w + " vs " + indep);
            } else if (rho < 0) {
                assertTrue(w < indep, "rho " + rho + ": " + w + " vs " + indep);
            }
            prev = w;
        }
    }

    @Test
    void collateralisedPayerIsWrongWayForPositiveRho() {
        ExposureProfile prof = payer(new ExposureSimulator.Csa(0, 0, 10.0 / 250));
        double indep = CvaApproximator.cva(prof, cpty(), 0.6);
        double ww = CvaApproximator.cvaWrongWay(prof, cpty(), 0.6, 0.5, ExposureProfile.Factor.RATES);
        assertTrue(ww >= indep, ww + " vs " + indep);
        checkWrongAndRightWay(prof);
    }

    @Test
    void uncollateralisedPayerIsWrongWayForPositiveRho() {
        checkWrongAndRightWay(payer(null));
    }

    /**
     * Single bucket at t, deterministic domestic rates, FX forward to buy
     * foreign: E(t) = N max(S_t P_f(t,T) - K P_d(t,T), 0) with S_t lognormal
     * in X = W_fx(t)/sqrt(t), so
     * CVA = LGD P_d(t) int E(x) Phi((k + rho x)/sqrt(1-rho^2)) phi(x) dx,
     * evaluated by the trapezoid rule on [-9, 9]. Monte Carlo tolerance 2 %
     * (about four standard errors at 400k paths).
     */
    @Test
    void singleBucketMatchesQuadrature() {
        YieldCurve f = YieldCurve.ofZeroRates(new double[]{1, 5, 10}, new double[]{0.01, 0.012, 0.015});
        double spot = 1.1, vol = 0.12, tMat = 2, strike = 1.12, notional = 100, t = 1.5;
        ExposureSimulator.Market mk = new ExposureSimulator.Market(dom(), A, 0, f, spot, vol, 0);
        ExposureProfile prof = ExposureSimulator.simulate(mk, List.of(),
                List.of(new ExposureSimulator.FxForward(notional, strike, tMat)), null,
                new double[]{t}, 400_000, 3L);
        CreditCurve c = cpty();
        double k = MathUtils.normInv(c.defaultProbability(t));
        double pd = dom().discountFactor(t);
        double fwdFactor = f.discountFactor(tMat) / f.discountFactor(t);
        double fwdD = dom().discountFactor(tMat) / pd;
        for (double rho : new double[]{-0.6, 0, 0.3, 0.6}) {
            double s = Math.sqrt(1 - rho * rho);
            double sum = 0;
            double h = 1e-3;
            int nSteps = 18_000;
            for (int j = 0; j <= nSteps; j++) {
                double x = -9 + j * h;
                double st = spot * f.discountFactor(t) / pd
                        * Math.exp(-0.5 * vol * vol * t + vol * Math.sqrt(t) * x);
                double e = notional * Math.max(st * fwdFactor - strike * fwdD, 0);
                double w = (j == 0 || j == nSteps) ? 0.5 : 1;
                sum += w * e * MathUtils.normCdf((k + rho * x) / s) * Math.exp(-0.5 * x * x);
            }
            double exact = 0.6 * pd * sum * h / Math.sqrt(2 * Math.PI);
            double mc = CvaApproximator.cvaWrongWay(prof, c, 0.6, rho, ExposureProfile.Factor.FX);
            assertEquals(exact, mc, 0.02 * exact, "rho " + rho);
        }
    }
}
