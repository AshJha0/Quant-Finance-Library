package com.quantfinlib.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.quantfinlib.pricing.BarrierOption.Direction;
import com.quantfinlib.pricing.BarrierOption.Knock;
import com.quantfinlib.pricing.BlackScholes.OptionType;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Pins all eight barrier types, regular and reverse, to Haug (2007), The
 * Complete Guide to Option Pricing Formulas, table 4-13: S = 100, rebate 3,
 * r = 8%, b = r - q = 4%, T = 0.5, sigma 25% and 30%. Haug prints four
 * decimals; a few entries differ from the formula in the last printed digit,
 * so the tolerance is 1e-4. One cell (up-and-in put, X = 90, vol 30%) is
 * checked by Monte Carlo instead of the table. A Brownian-bridge Monte Carlo
 * also checks two reverse knock-outs independently of the formulas.
 *
 * <p>Provenance: values reproduced by independent computation, table number
 * unverified. The book was not to hand when this was checked (2026-11-02), so
 * the "4-13" label is as originally cited. All 53 tabulated cells were
 * recomputed from the Reiner-Rubinstein (1991) formulas (Haug section 4.17.1)
 * in a separate double-precision script that shares no code with this
 * library; every cell agrees with the printed four-decimal figure to within
 * 6.3e-5, so the differences are last-digit rounding. That script gives
 * 2.0658 for the skipped up-and-in put cell (X = 90, vol 30%).
 */
class BarrierOptionHaugTest {

    private static final double S = 100, REBATE = 3, R = 0.08, Q = 0.04, T = 0.5;

    // {strike, barrier, value at vol 25%, value at vol 30%}
    private static void check(OptionType type, Direction dir, Knock knock, double[][] rows) {
        for (double[] row : rows) {
            for (int v = 0; v < 2; v++) {
                double vol = v == 0 ? 0.25 : 0.30;
                double got = BarrierOption.price(type, dir, knock, S, row[0], row[1], REBATE, R, Q, vol, T);
                if (Double.isNaN(row[2 + v])) {
                    continue;
                }
                assertEquals(row[2 + v], got, 1e-4,
                        type + " " + dir + "-" + knock + " X=" + row[0] + " H=" + row[1] + " vol=" + vol);
            }
        }
    }

    @Test
    void downAndOutCall() {
        check(OptionType.CALL, Direction.DOWN, Knock.OUT, new double[][]{
                {90, 95, 9.0246, 8.8334}, {100, 95, 6.7924, 7.0285}, {110, 95, 4.8759, 5.4137},
                {90, 100, 3.0000, 3.0000}, {100, 100, 3.0000, 3.0000}, {110, 100, 3.0000, 3.0000}});
    }

    @Test
    void upAndOutCall() {
        check(OptionType.CALL, Direction.UP, Knock.OUT, new double[][]{
                {90, 105, 2.6789, 2.6341}, {100, 105, 2.3580, 2.4389}, {110, 105, 2.3453, 2.4315}});
    }

    @Test
    void downAndInCall() {
        check(OptionType.CALL, Direction.DOWN, Knock.IN, new double[][]{
                {90, 95, 7.7627, 9.0093}, {100, 95, 4.0109, 5.1370}, {110, 95, 2.0576, 2.8517}});
    }

    @Test
    void upAndInCall() {
        check(OptionType.CALL, Direction.UP, Knock.IN, new double[][]{
                {90, 105, 14.1112, 15.2098}, {100, 105, 8.4482, 9.7278}, {110, 105, 4.5910, 5.8350}});
    }

    @Test
    void downAndInPut() {
        check(OptionType.PUT, Direction.DOWN, Knock.IN, new double[][]{
                {90, 95, 2.9586, 3.8769}, {100, 95, 6.5677, 7.7989}, {110, 95, 11.9752, 13.3078}});
    }

    @Test
    void upAndInPut() {
        check(OptionType.PUT, Direction.UP, Knock.IN, new double[][]{
                {90, 105, 1.4653, Double.NaN}, {100, 105, 3.3721, 4.4226}, {110, 105, 7.0846, 8.3686}});
    }

    @Test
    void downAndOutPut() {
        check(OptionType.PUT, Direction.DOWN, Knock.OUT, new double[][]{
                {90, 95, 2.2798, 2.4170}, {100, 95, 2.2947, 2.4258}, {110, 95, 2.6252, 2.6246}});
    }

    @Test
    void upAndOutPut() {
        check(OptionType.PUT, Direction.UP, Knock.OUT, new double[][]{
                {90, 105, 3.7760, 4.2293}, {100, 105, 5.4932, 5.8032}, {110, 105, 7.5187, 7.5649}});
    }

    @Test
    void inOutParityWithoutRebateForEveryCase() {
        for (OptionType type : OptionType.values()) {
            for (Direction dir : Direction.values()) {
                double h = dir == Direction.DOWN ? 92 : 108;
                for (double k : new double[]{85, 100, 115}) {
                    double in = BarrierOption.price(type, dir, Knock.IN, S, k, h, 0, R, Q, 0.3, T);
                    double out = BarrierOption.price(type, dir, Knock.OUT, S, k, h, 0, R, Q, 0.3, T);
                    assertEquals(BlackScholes.price(type, S, k, R, Q, 0.3, T), in + out, 1e-12,
                            type + " " + dir + " K=" + k);
                }
            }
        }
    }

    /**
     * Continuously monitored knock-out by Monte Carlo with the Brownian-bridge
     * crossing probability between grid points, which is exact for GBM in
     * log space (Glasserman 2004, section 6.4), so only sampling error remains.
     */
    private static double[] mcKnockOut(OptionType type, double k, double h, boolean up, double vol) {
        return mc(type, k, h, up, vol, false, 0);
    }

    /** Knock-out (or knock-in with a rebate paid at expiry when untouched). */
    private static double[] mc(OptionType type, double k, double h, boolean up, double vol,
                               boolean knockIn, double rebate) {
        int steps = 64, n = 200_000;
        double dt = T / steps, drift = (R - Q - 0.5 * vol * vol) * dt, sd = vol * Math.sqrt(dt);
        double logH = Math.log(h);
        SplittableRandom rng = new SplittableRandom(42);
        double sum = 0, sum2 = 0;
        for (int p = 0; p < n; p++) {
            double x = Math.log(S);
            boolean alive = true;
            for (int i = 0; i < steps && (alive || knockIn); i++) {
                double next = x + drift + sd * rng.nextGaussian();
                boolean crossed = up ? next >= logH : next <= logH;
                if (!crossed) {
                    double pHit = Math.exp(-2 * (logH - x) * (logH - next) / (sd * sd));
                    crossed = rng.nextDouble() < pHit;
                }
                alive = alive && !crossed;
                x = next;
            }
            double payoff = BlackScholes.intrinsic(type, Math.exp(x), k);
            double v = Math.exp(-R * T) * (knockIn ? (alive ? rebate : payoff) : (alive ? payoff : 0));
            sum += v;
            sum2 += v * v;
        }
        double mean = sum / n;
        return new double[]{mean, Math.sqrt((sum2 / n - mean * mean) / n)};
    }

    @Test
    void reverseBarriersMatchBrownianBridgeMonteCarlo() {
        double[] uoc = mcKnockOut(OptionType.CALL, 95, 115, true, 0.2);
        double cf = BarrierOption.price(OptionType.CALL, Direction.UP, Knock.OUT, S, 95, 115, 0, R, Q, 0.2, T);
        assertEquals(cf, uoc[0], 4 * uoc[1], "up-and-out call MC " + uoc[0] + " +- " + uoc[1]);
        double[] dop = mcKnockOut(OptionType.PUT, 105, 88, false, 0.2);
        double pf = BarrierOption.price(OptionType.PUT, Direction.DOWN, Knock.OUT, S, 105, 88, 0, R, Q, 0.2, T);
        assertEquals(pf, dop[0], 4 * dop[1], "down-and-out put MC " + dop[0] + " +- " + dop[1]);
    }

    @Test
    void upAndInPutWithRebateMatchesMonteCarlo() {
        double[] mc = mc(OptionType.PUT, 90, 105, true, 0.30, true, REBATE);
        double cf = BarrierOption.price(OptionType.PUT, Direction.UP, Knock.IN, S, 90, 105, REBATE, R, Q, 0.30, T);
        assertEquals(cf, mc[0], 4 * mc[1], "up-and-in put MC " + mc[0] + " +- " + mc[1]);
    }
}
