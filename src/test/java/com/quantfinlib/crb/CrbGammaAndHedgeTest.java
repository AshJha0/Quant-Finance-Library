package com.quantfinlib.crb;

import com.quantfinlib.pricing.BlackScholes;
import com.quantfinlib.pricing.BlackScholes.OptionType;
import com.quantfinlib.risk.TailRisk;
import com.quantfinlib.stats.SpecialFunctions;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrbGammaAndHedgeTest {

    /**
     * A short option book: the report must treat dollar gamma as curvature on
     * the EQ factor. Reference: Monte Carlo of the exact delta-gamma P&L
     * {@code D·r + 50·G·r²} with r ~ N(0, σ²); Cornish-Fisher should be close.
     */
    @Test
    void shortGammaBookUsesDeltaGammaVar() {
        CentralRiskBook book = new CentralRiskBook();
        book.bookEquityOption("opt", "SPY", OptionType.CALL, -1000, 100, 500, 500, 0.0, 0.0,
                0.20, 0.05);
        int n = book.factors().size();
        double sigma = 0.02;
        double[][] cov = new double[n][n];
        int eq = book.factors().idIfPresent("EQ:SPY");
        cov[eq][eq] = sigma * sigma;
        int vegaId = book.factors().idIfPresent("EQVEGA:SPY");
        cov[vegaId][vegaId] = 1e-12;                 // vol factor nearly still
        CentralRiskBook.CrbReport rep = book.report(cov, 0.99);

        double dollarDelta = book.exposure("EQ:SPY");
        double dollarGamma = book.exposure("EQGAMMA:SPY");
        assertTrue(dollarGamma < 0, "short option = short gamma");

        SplittableRandom rnd = new SplittableRandom(5);
        int sims = 400_000;
        double[] losses = new double[sims];
        for (int i = 0; i < sims; i++) {
            double r = sigma * rnd.nextGaussian();
            losses[i] = -(dollarDelta * r + 50 * dollarGamma * r * r);
        }
        double mcVar = TailRisk.of(losses, 0.99).var();
        double deltaNormal = SpecialFunctions.normInv(0.99) * Math.abs(dollarDelta) * sigma;
        assertTrue(rep.var() > deltaNormal, "short gamma raises VaR above delta-normal");
        assertEquals(mcVar, rep.var(), 0.03 * mcVar, "CF VaR within 3% of exact delta-gamma MC");

        // The gamma row of the covariance is ignored: changing it changes nothing.
        int gId = book.factors().idIfPresent("EQGAMMA:SPY");
        cov[gId][gId] = 1.0;
        assertEquals(rep.var(), book.report(cov, 0.99).var(), 1e-9);

        // Sanity: the dollar gamma booked is Γ·S²/100 per contract unit.
        double gamma = BlackScholes.gamma(500, 500, 0, 0, 0.2, 0.05);
        assertEquals(-1000 * 100 * gamma * 500 * 500 / 100, dollarGamma, 1e-6);
    }

    /** Two identical hedge instruments: unique (split) solution, no exception. */
    @Test
    void collinearHedgeInstrumentsConverge() {
        double[] e = {1_000_000};
        double[][] cov = {{4e-4}};
        double[][] loadings = {{1.0, 1.0}};
        double[] h = HedgeOptimizer.hedge(e, cov, loadings, new double[]{0, 0}, 0);
        assertEquals(-1_000_000, h[0] + h[1], 1e-3);
        double[] near = HedgeOptimizer.hedge(new double[]{1_000_000, 0},
                new double[][]{{4e-4, 3.996e-4}, {3.996e-4, 4e-4}},
                new double[][]{{1, 0}, {0, 1}}, new double[]{0, 0}, 0);
        assertEquals(-1_000_000, near[0], 1e-3);
        assertEquals(0, near[1], 1e-3);
    }
}
