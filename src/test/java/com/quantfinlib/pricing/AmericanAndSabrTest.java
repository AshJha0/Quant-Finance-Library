package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BinomialTree.ExerciseStyle;
import com.quantfinlib.pricing.BlackScholes.OptionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmericanAndSabrTest {

    // ---- CRR binomial tree ------------------------------------------------

    @Test
    void europeanTreeConvergesToBlackScholes() {
        double bs = BlackScholes.price(OptionType.CALL, 100, 100, 0.05, 0, 0.2, 1);
        double tree = BinomialTree.price(OptionType.CALL, ExerciseStyle.EUROPEAN,
                100, 100, 0.05, 0, 0.2, 1, 500);
        assertEquals(bs, tree, 0.05);

        double bsPut = BlackScholes.price(OptionType.PUT, 100, 95, 0.03, 0.01, 0.25, 0.5);
        double treePut = BinomialTree.price(OptionType.PUT, ExerciseStyle.EUROPEAN,
                100, 95, 0.03, 0.01, 0.25, 0.5, 500);
        assertEquals(bsPut, treePut, 0.05);
    }

    @Test
    void americanPutCarriesEarlyExercisePremium() {
        // Deep ITM American put on a high-rate asset: early exercise is valuable.
        double premium = BinomialTree.earlyExercisePremium(
                OptionType.PUT, 80, 100, 0.08, 0, 0.2, 1, 400);
        assertTrue(premium > 0.1, "premium=" + premium);
        // American never below intrinsic.
        double american = BinomialTree.price(OptionType.PUT, ExerciseStyle.AMERICAN,
                80, 100, 0.08, 0, 0.2, 1, 400);
        assertTrue(american >= 20 - 1e-9);
    }

    @Test
    void americanCallWithoutCarryEqualsEuropean() {
        // No dividends: never optimal to exercise a call early.
        double premium = BinomialTree.earlyExercisePremium(
                OptionType.CALL, 100, 95, 0.05, 0, 0.25, 1, 400);
        assertEquals(0, premium, 1e-9);
    }

    @Test
    void treeDeltaMatchesBlackScholesForEuropean() {
        double bsDelta = BlackScholes.delta(OptionType.CALL, 100, 100, 0.05, 0, 0.2, 1);
        double treeDelta = BinomialTree.delta(OptionType.CALL, ExerciseStyle.EUROPEAN,
                100, 100, 0.05, 0, 0.2, 1, 500);
        assertEquals(bsDelta, treeDelta, 0.01);
    }

    // ---- SABR ---------------------------------------------------------------

    private static final double F = 100, T = 1, BETA = 1.0;
    private static final double ALPHA = 0.20, RHO = -0.30, NU = 0.60;

    @Test
    void haganAtmFormulaConsistentWithSmile() {
        double atm = SabrModel.impliedVol(F, F, T, ALPHA, BETA, RHO, NU);
        double nearAtm = SabrModel.impliedVol(F, F + 1e-6, T, ALPHA, BETA, RHO, NU);
        assertEquals(atm, nearAtm, 1e-6);
        // Negative rho: downside strikes carry higher vol.
        assertTrue(SabrModel.impliedVol(F, 80, T, ALPHA, BETA, RHO, NU)
                > SabrModel.impliedVol(F, 120, T, ALPHA, BETA, RHO, NU));
    }

    @Test
    void calibrationRecoversGeneratedSmile() {
        double[] strikes = {70, 80, 90, 100, 110, 120, 130};
        double[] vols = new double[strikes.length];
        for (int i = 0; i < strikes.length; i++) {
            vols[i] = SabrModel.impliedVol(F, strikes[i], T, ALPHA, BETA, RHO, NU);
        }
        SabrModel.Params fit = SabrModel.calibrate(F, T, BETA, strikes, vols);

        assertTrue(fit.rmse() < 5e-4, "rmse=" + fit.rmse());
        assertEquals(ALPHA, fit.alpha(), 0.02);
        assertEquals(RHO, fit.rho(), 0.10);
        assertEquals(NU, fit.nu(), 0.15);
        // Fitted smile reproduces an unquoted strike.
        double interp = SabrModel.impliedVol(F, 85, T, fit.alpha(), fit.beta(), fit.rho(), fit.nu());
        double truth = SabrModel.impliedVol(F, 85, T, ALPHA, BETA, RHO, NU);
        assertEquals(truth, interp, 0.003);
    }
}
