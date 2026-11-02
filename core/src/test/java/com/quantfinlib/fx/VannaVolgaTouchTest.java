package com.quantfinlib.fx;

import com.quantfinlib.pricing.TouchOption;
import com.quantfinlib.pricing.VannaVolga;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vanna-volga one-touch: flat-smile limit, survival weighting, bounds. */
class VannaVolgaTouchTest {

    private static final double S = 1.0850;
    private static final double R = 0.045;
    private static final double Q = 0.030;
    private static final double T = 0.5;
    private static final double[] STRIKES = {1.04, 1.09, 1.14};

    @Test
    void flatSmileGivesBlackScholesOneTouch() {
        VannaVolga flat = new VannaVolga(STRIKES, new double[]{0.09, 0.09, 0.09}, R, Q, T);
        for (double b : new double[]{1.00, 1.15, 1.20}) {
            assertEquals(TouchOption.oneTouch(S, b, R, Q, 0.09, T, 1), VannaVolgaTouch.oneTouch(flat, S, b, 1), 1e-15);
        }
    }

    @Test
    void adjustmentIsSurvivalWeightedSmileCost() {
        VannaVolga vv = new VannaVolga(STRIKES, new double[]{0.098, 0.090, 0.094}, R, Q, T);
        double b = 1.15;
        double tv = TouchOption.oneTouch(S, b, R, Q, 0.09, T, 1);
        double p = 1 - TouchOption.hitProbability(S, b, R, Q, 0.09, T);
        double cost = VannaVolgaTouch.smileCost(vv, S, b, 1);
        assertNotEquals(0, cost);
        double price = VannaVolgaTouch.oneTouch(vv, S, b, 1);
        assertEquals(tv + p * cost, price, 1e-15);
        assertTrue(price > 0 && price < Math.exp(-R * T));
        // Linear in payout, up to finite-difference rounding in the Greeks.
        assertEquals(1e6 * price, VannaVolgaTouch.oneTouch(vv, S, b, 1e6), 1e-8 * 1e6);
        // At the barrier the touch is certain and the adjustment is dropped.
        assertEquals(Math.exp(-R * T), VannaVolgaTouch.oneTouch(vv, b, b, 1), 1e-15);
    }

    @Test
    void adjustmentShrinksAsBarrierApproachesSpot() {
        VannaVolga vv = new VannaVolga(STRIKES, new double[]{0.098, 0.090, 0.094}, R, Q, T);
        double far = Math.abs(VannaVolgaTouch.oneTouch(vv, S, 1.16, 1) - TouchOption.oneTouch(S, 1.16, R, Q, 0.09, T, 1));
        double near = Math.abs(VannaVolgaTouch.oneTouch(vv, S, 1.0880, 1) - TouchOption.oneTouch(S, 1.0880, R, Q, 0.09, T, 1));
        assertTrue(near < far, near + " vs " + far);
    }

    @Test
    void rejectsBadInput() {
        VannaVolga vv = new VannaVolga(STRIKES, new double[]{0.098, 0.090, 0.094}, R, Q, T);
        assertThrows(IllegalArgumentException.class, () -> VannaVolgaTouch.oneTouch(null, S, 1.1, 1));
        assertThrows(IllegalArgumentException.class, () -> VannaVolgaTouch.oneTouch(vv, S, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> VannaVolgaTouch.smileCost(vv, S, 1.1, -1));
    }
}
