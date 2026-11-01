package com.quantfinlib.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quantfinlib.pricing.BlackScholes.OptionType;
import org.junit.jupiter.api.Test;

class BachelierTest {

    @Test
    void closedFormValues() {
        // F = 1.2%, K = 2%, sigma_N = 85bp, T = 3: values from an independent
        // evaluation of the Bachelier formula (scipy).
        assertEquals(0.002719800528426808,
                Bachelier.price(OptionType.CALL, 0.012, 0.02, 0, 0.0085, 3), 1e-17);
        assertEquals(0.010719800528426807,
                Bachelier.price(OptionType.PUT, 0.012, 0.02, 0, 0.0085, 3), 1e-17);
        // At the money: sigma sqrt(T) / sqrt(2 pi), here with discounting.
        assertEquals(Math.exp(-0.03 * 2) * 0.01 * Math.sqrt(2) / Math.sqrt(2 * Math.PI),
                Bachelier.price(OptionType.CALL, -0.004, -0.004, 0.03, 0.01, 2), 1e-17);
    }

    @Test
    void parityAndNegativeRates() {
        double f = -0.0035, k = -0.001, t = 1.5, r = 0.01;
        double c = Bachelier.price(OptionType.CALL, f, k, r, 0.006, t);
        double p = Bachelier.price(OptionType.PUT, f, k, r, 0.006, t);
        assertEquals(Math.exp(-r * t) * (f - k), c - p, 1e-17);
        assertTrue(c > 0 && p > 0);
        // Delta and vega against central differences.
        double h = 1e-7;
        double fd = (Bachelier.price(OptionType.CALL, f + h, k, r, 0.006, t)
                - Bachelier.price(OptionType.CALL, f - h, k, r, 0.006, t)) / (2 * h);
        assertEquals(fd, Bachelier.delta(OptionType.CALL, f, k, r, 0.006, t), 1e-7);
        double fv = (Bachelier.price(OptionType.CALL, f, k, r, 0.006 + h, t)
                - Bachelier.price(OptionType.CALL, f, k, r, 0.006 - h, t)) / (2 * h);
        assertEquals(fv, Bachelier.vega(f, k, r, 0.006, t), 1e-7);
    }

    @Test
    void impliedNormalVolRoundTripSweep() {
        double f = 0.01;
        int checked = 0;
        for (double t : new double[] {1.0 / 52, 0.25, 1, 5, 30}) {
            for (double vol : new double[] {0.0005, 0.002, 0.008, 0.02}) {
                for (double m : new double[] {-8, -5, -3, -1, -0.2, 0, 0.2, 1, 3, 5, 8}) {
                    double k = f + m * vol * Math.sqrt(t);
                    for (OptionType type : OptionType.values()) {
                        double p = Bachelier.price(type, f, k, 0.02, vol, t);
                        double vega = Bachelier.vega(f, k, 0.02, vol, t);
                        double intrinsic = Math.exp(-0.02 * t) * Math.max(type.sign() * (f - k), 0);
                        if (!(p - intrinsic > 1e-300) || !(vega > 0)) {
                            continue;
                        }
                        double iv = Bachelier.impliedVol(type, p, f, k, 0.02, t);
                        double tol = 1e-11 * vol + 50 * Math.ulp(Math.max(Math.abs(f), Math.abs(k))) / vega;
                        assertEquals(vol, iv, tol, type + " t=" + t + " vol=" + vol + " m=" + m);
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 300, "checked " + checked);
        assertTrue(Double.isNaN(Bachelier.impliedVol(OptionType.CALL, 0.001, 0.02, 0.01, 0, 1)));
        assertEquals(0.0, Bachelier.impliedVol(OptionType.CALL, 0.01, 0.02, 0.01, 0, 1), 0.0);
    }
}
