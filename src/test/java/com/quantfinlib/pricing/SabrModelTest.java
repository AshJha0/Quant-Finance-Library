package com.quantfinlib.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SabrModelTest {

    @Test
    void haganExpansionAwayFromTheMoneyPinned() {
        // Reference values from a separate implementation of Hagan et al.
        // (2002) eq. 2.17a (Python, double precision).
        assertEquals(0.2334265624303236, SabrModel.impliedVol(100, 80, 1, 0.2, 1, -0.3, 0.6), 1e-15);
        assertEquals(0.1979843825210321, SabrModel.impliedVol(100, 125, 1, 0.2, 1, -0.3, 0.6), 1e-15);
        assertEquals(0.36567912181176515,
                SabrModel.impliedVol(0.03, 0.015, 5, 0.04, 0.5, -0.25, 0.45), 1e-14);
        assertEquals(0.22769458396651662,
                SabrModel.impliedVol(0.03, 0.05, 5, 0.04, 0.5, -0.25, 0.45), 1e-14);
        // Continuity through the money (the z/x(z) limit).
        double atm = SabrModel.impliedVol(0.03, 0.03, 5, 0.04, 0.5, -0.25, 0.45);
        // The skew is about -0.1 per unit log-strike, so a 1e-9 move shifts vol by ~1e-10.
        assertEquals(atm, SabrModel.impliedVol(0.03, 0.03 * (1 + 1e-9), 5, 0.04, 0.5, -0.25, 0.45), 1e-9);
    }

    @Test
    void levenbergMarquardtRecoversParametersToHighPrecision() {
        double f = 0.03, t = 5, beta = 0.5, alpha = 0.04, rho = -0.25, nu = 0.45;
        double[] strikes = {0.01, 0.015, 0.02, 0.025, 0.03, 0.035, 0.04, 0.05, 0.06};
        double[] vols = new double[strikes.length];
        for (int i = 0; i < strikes.length; i++) {
            vols[i] = SabrModel.impliedVol(f, strikes[i], t, alpha, beta, rho, nu);
        }
        SabrModel.Params p = SabrModel.calibrate(f, t, beta, strikes, vols);
        assertEquals(alpha, p.alpha(), 1e-8);
        assertEquals(rho, p.rho(), 1e-6);
        assertEquals(nu, p.nu(), 1e-6);
        assertTrue(p.rmse() < 1e-10, "rmse " + p.rmse());
    }

    @Test
    void atmCalibrationRepricesTheAtmVolExactly() {
        double f = 100, t = 1, beta = 1;
        double[] strikes = {70, 80, 90, 110, 120, 130};
        double[] vols = new double[strikes.length];
        for (int i = 0; i < strikes.length; i++) {
            vols[i] = SabrModel.impliedVol(f, strikes[i], t, 0.2, beta, -0.3, 0.6);
        }
        double atmVol = SabrModel.impliedVol(f, f, t, 0.2, beta, -0.3, 0.6);
        SabrModel.Params p = SabrModel.calibrateToAtm(f, t, beta, atmVol, strikes, vols, null);
        assertEquals(atmVol, SabrModel.impliedVol(f, f, t, p.alpha(), beta, p.rho(), p.nu()), 1e-14);
        assertEquals(0.2, p.alpha(), 1e-8);
        assertEquals(-0.3, p.rho(), 1e-6);
        assertEquals(0.6, p.nu(), 1e-6);
        // The cubic's root reproduces the ATM vol for beta < 1 too.
        double a = SabrModel.alphaFromAtmVol(0.03, 10, 0.5, -0.4, 0.5, 0.25);
        assertEquals(0.25, SabrModel.impliedVol(0.03, 0.03, 10, a, 0.5, -0.4, 0.5), 1e-14);
    }

    @Test
    void densityCheckFlagsHaganButterflyArbitrage() {
        // Long expiry, low strikes: the expansion's density goes negative
        // (about -60 near K = 0.4%, from an independent finite-difference scan).
        assertTrue(SabrModel.minimumDensity(0.03, 20, 0.025, 0.5, -0.3, 0.7, 0.001, 0.1, 199) < -10);
        // A one-year equity-style smile is clean.
        assertTrue(SabrModel.minimumDensity(100, 1, 0.2, 1, -0.3, 0.6, 50, 200, 151) > 0);
    }
}
