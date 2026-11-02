package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BinomialTree.ExerciseStyle;
import org.junit.jupiter.api.Test;

import static com.quantfinlib.pricing.BinomialTree.ExerciseStyle.AMERICAN;
import static com.quantfinlib.pricing.BinomialTree.ExerciseStyle.EUROPEAN;
import static com.quantfinlib.pricing.BlackScholes.OptionType.CALL;
import static com.quantfinlib.pricing.BlackScholes.OptionType.PUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vellekoop-Nieuwenhuis dividend tree against: the plain CRR tree (no
 * dividends), an exact one-dividend reference by numerical integration over
 * the stock price at the ex-date (European and American call), the
 * Bos-Vandermark approximation, and the early-exercise behaviour of a call
 * just before a large ex-date.
 */
class DividendTreeTest {

    private static final double S = 100, R = 0.05, VOL = 0.25;

    /**
     * Exact value of a call in the jump model with one dividend D at tEx and no
     * borrow: integrate over S(tEx) the larger of exercising cum-dividend (American
     * only) and the Black-Scholes call on S(tEx) - D for the remaining time.
     */
    private static double oneDividendCall(double k, double t, double tEx, double div,
                                          boolean american) {
        int n = 4000;
        double zMax = 9, h = 2 * zMax / n, sum = 0;
        double sd = VOL * Math.sqrt(tEx);
        for (int i = 0; i <= n; i++) {
            double z = -zMax + i * h;
            double s = S * Math.exp((R - 0.5 * VOL * VOL) * tEx + sd * z);
            double ex = s - div;
            double hold = ex > 0 ? BlackScholes.price(BlackScholes.OptionType.CALL, ex, k, R, 0, VOL, t - tEx) : 0;
            double val = american ? Math.max(hold, s - k) : hold;
            double w = (i == 0 || i == n) ? 1 : ((i & 1) == 1 ? 4 : 2);
            sum += w * val * Math.exp(-0.5 * z * z) / Math.sqrt(2 * Math.PI);
        }
        return Math.exp(-R * tEx) * sum * h / 3;
    }

    @Test
    void noDividendsEqualsCrrTree() {
        for (var type : new BlackScholes.OptionType[]{CALL, PUT}) {
            for (ExerciseStyle style : ExerciseStyle.values()) {
                for (double k : new double[]{85, 100, 120}) {
                    assertEquals(BinomialTree.price(type, style, S, k, R, 0.02, VOL, 1.5, 300),
                            DividendTree.price(type, style, S, k, R, 0.02, VOL, 1.5, DividendSchedule.NONE, 300),
                            1e-11);
                }
            }
        }
        // Zero-amount and post-expiry dividends change nothing either.
        DividendSchedule zero = DividendSchedule.of(new double[]{0.5, 3.0}, new double[]{0, 4});
        assertEquals(BinomialTree.price(PUT, AMERICAN, S, 100, R, 0, VOL, 1, 200),
                DividendTree.price(PUT, AMERICAN, S, 100, R, 0, VOL, 1, zero, 200), 1e-11);
    }

    @Test
    void europeanMatchesExactOneDividendIntegral() {
        DividendSchedule div = DividendSchedule.of(new double[]{0.5}, new double[]{4});
        for (double k : new double[]{90, 100, 110}) {
            double exact = oneDividendCall(k, 1, 0.5, 4, false);
            double tree = DividendTree.price(CALL, EUROPEAN, S, k, R, 0, VOL, 1, div, 2000);
            assertEquals(exact, tree, 6e-3, "K=" + k);
            // Bos-Vandermark is close to the exact jump model for Europeans; escrowed less so.
            double bv = div.bosVandermarkPrice(CALL, S, k, R, 0, VOL, 1);
            assertEquals(exact, bv, 0.05);
            assertTrue(Math.abs(exact - bv) < Math.abs(exact - div.europeanPrice(CALL, S, k, R, 0, VOL, 1)) + 1e-9);
        }
        // Put by parity against the exact call: C - P = S - PV(D) - K e^{-rT}.
        double exactCall = oneDividendCall(100, 1, 0.5, 4, false);
        double put = DividendTree.price(PUT, EUROPEAN, S, 100, R, 0, VOL, 1, div, 2000);
        assertEquals(exactCall - (S - 4 * Math.exp(-R * 0.5) - 100 * Math.exp(-R)), put, 6e-3);
    }

    @Test
    void americanCallExercisesJustBeforeLargeExDate() {
        // A $6 dividend two weeks before expiry: the deep in-the-money holder exercises cum-dividend.
        double tEx = 0.46, t = 0.5;
        DividendSchedule div = DividendSchedule.of(new double[]{tEx}, new double[]{6});
        double exactAm = oneDividendCall(90, t, tEx, 6, true);
        double exactEu = oneDividendCall(90, t, tEx, 6, false);
        double am = DividendTree.price(CALL, AMERICAN, S, 90, R, 0, VOL, t, div, 2500);
        double eu = DividendTree.price(CALL, EUROPEAN, S, 90, R, 0, VOL, t, div, 2500);
        assertEquals(exactAm, am, 6e-3);
        assertEquals(exactEu, eu, 6e-3);
        // A material early-exercise premium, close to that of exercising just before the ex-date.
        assertTrue(am - eu > 1.0, "premium " + (am - eu));
        assertTrue(am >= BlackScholes.price(CALL, S, 90, R, 0, VOL, tEx) - 1e-2);
        // Without the dividend an American call on a non-yielding stock is European.
        assertEquals(BlackScholes.price(CALL, S, 90, R, 0, VOL, t),
                DividendTree.price(CALL, AMERICAN, S, 90, R, 0, VOL, t, DividendSchedule.NONE, 2500), 5e-3);
    }

    @Test
    void americanPutWithDividendsIsOrderedAndConverges() {
        DividendSchedule div = DividendSchedule.of(new double[]{0.25, 0.75}, new double[]{2, 2});
        double am = DividendTree.price(PUT, AMERICAN, S, 100, R, 0, VOL, 1, div, 1000);
        double am2 = DividendTree.price(PUT, AMERICAN, S, 100, R, 0, VOL, 1, div, 2000);
        double eu = DividendTree.price(PUT, EUROPEAN, S, 100, R, 0, VOL, 1, div, 2000);
        double noDiv = BinomialTree.price(PUT, AMERICAN, S, 100, R, 0, VOL, 1, 2000);
        assertEquals(am2, am, 1e-2);
        assertTrue(am2 >= eu - 1e-12);
        assertTrue(am2 > noDiv, "dividends make puts dearer");
        assertTrue(am2 >= 0 && eu >= 0);
    }

    @Test
    void dividendAtExpiryHitsTerminalPayoff() {
        DividendSchedule atExpiry = DividendSchedule.of(new double[]{1.0}, new double[]{3});
        double exact = oneDividendCall(100, 1.0 + 1e-12, 1.0, 3, false);
        assertEquals(exact, DividendTree.price(CALL, EUROPEAN, S, 100, R, 0, VOL, 1, atExpiry, 2000), 6e-3);
    }

    @Test
    void validation() {
        assertThrows(IllegalArgumentException.class,
                () -> DividendTree.price(CALL, EUROPEAN, S, 100, R, 0, VOL, 1, DividendSchedule.NONE, 0));
        assertThrows(IllegalArgumentException.class,
                () -> DividendTree.price(CALL, EUROPEAN, S, 100, R, 0, 0, 1, DividendSchedule.NONE, 10));
        assertThrows(IllegalArgumentException.class,
                () -> DividendTree.price(CALL, EUROPEAN, S, 100, R, 0, VOL, 0, DividendSchedule.NONE, 10));
        assertThrows(IllegalArgumentException.class,
                () -> DividendTree.price(CALL, EUROPEAN, S, 100, 5, 0, 0.01, 1, DividendSchedule.NONE, 2));
        DividendSchedule div = DividendSchedule.of(new double[]{0.5}, new double[]{2});
        assertEquals(0.5, div.exTime(0));
        assertEquals(2, div.amount(0));
    }
}
