package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;
import com.quantfinlib.util.MathUtils;

/**
 * Continuously monitored single-barrier options: all eight in/out, up/down,
 * call/put combinations, regular and reverse, with a cash rebate.
 *
 * <p>Closed forms of Reiner and Rubinstein (1991), "Breaking down the
 * barriers", Risk 4(8), in the notation of Haug (2007), <i>The Complete
 * Guide to Option Pricing Formulas</i>, 2nd ed., section 4.17.1. With cost of
 * carry b = r − q, μ = (b − σ²/2)/σ², λ = √(μ² + 2r/σ²) and</p>
 *
 * <pre>
 *   x1 = ln(S/X)/(σ√T) + (1+μ)σ√T     x2 = ln(S/H)/(σ√T) + (1+μ)σ√T
 *   y1 = ln(H²/(SX))/(σ√T) + (1+μ)σ√T y2 = ln(H/S)/(σ√T) + (1+μ)σ√T
 *   z  = ln(H/S)/(σ√T) + λσ√T
 * </pre>
 *
 * <p>the building blocks A-F (φ = ±1 call/put, η = +1 down, −1 up) combine as
 * in Haug's table: for instance an up-and-out call with X &lt; H is
 * A − B + C − D + F. A knock-in rebate is paid at expiry if the barrier was
 * never touched; a knock-out rebate is paid at the moment of the hit.</p>
 *
 * <p>Conventions match {@link BlackScholes}: {@code carry} is the continuous
 * yield q (foreign rate for FX), so b = rate − carry. If the barrier is
 * already breached at {@code spot}, a knock-in is the vanilla and a knock-out
 * pays its rebate now. Discrete monitoring is not modelled; the
 * Broadie-Glasserman-Kou (1997) shift H·e^{±0.5826σ√Δt} is the usual
 * correction and can be applied to {@code barrier} by the caller.</p>
 */
public final class BarrierOption {

    /** Barrier direction relative to spot. */
    public enum Direction {
        DOWN, UP
    }

    /** Whether the barrier activates or extinguishes the option. */
    public enum Knock {
        IN, OUT
    }

    private BarrierOption() {
    }

    /**
     * Price of a single-barrier option with a cash rebate.
     *
     * @param rebate cash amount: paid at expiry for an untouched knock-in,
     *               at the hit for a knock-out; 0 for none
     */
    public static double price(OptionType type, Direction direction, Knock knock, double spot,
                               double strike, double barrier, double rebate, double rate,
                               double carry, double vol, double timeYears) {
        if (!(spot > 0) || !(strike > 0) || !(barrier > 0) || !Double.isFinite(spot)
                || !Double.isFinite(strike) || !Double.isFinite(barrier)) {
            throw new IllegalArgumentException("spot, strike, barrier must be positive and finite");
        }
        if (!(rebate >= 0) || !Double.isFinite(rebate) || !Double.isFinite(rate)
                || !Double.isFinite(carry) || !(vol >= 0) || !Double.isFinite(vol)
                || Double.isNaN(timeYears)) {
            throw new IllegalArgumentException("invalid rebate, rate, carry or vol");
        }
        boolean breached = direction == Direction.DOWN ? spot <= barrier : spot >= barrier;
        if (breached) {
            return knock == Knock.IN
                    ? BlackScholes.price(type, spot, strike, rate, carry, vol, timeYears)
                    : rebate;
        }
        if (timeYears <= 0) {
            return knock == Knock.IN ? rebate : BlackScholes.intrinsic(type, spot, strike);
        }
        if (vol <= 0) {
            throw new IllegalArgumentException("vol must be > 0 before expiry");
        }
        double t = timeYears;
        double b = rate - carry;
        double sig2 = vol * vol;
        double sq = vol * Math.sqrt(t);
        double mu = (b - 0.5 * sig2) / sig2;
        double lam = Math.sqrt(mu * mu + 2 * rate / sig2);
        double phi = type.sign();
        double eta = direction == Direction.DOWN ? 1 : -1;
        double x1 = Math.log(spot / strike) / sq + (1 + mu) * sq;
        double x2 = Math.log(spot / barrier) / sq + (1 + mu) * sq;
        double y1 = Math.log(barrier * barrier / (spot * strike)) / sq + (1 + mu) * sq;
        double y2 = Math.log(barrier / spot) / sq + (1 + mu) * sq;
        double z = Math.log(barrier / spot) / sq + lam * sq;
        double sFwd = spot * Math.exp((b - rate) * t);
        double kDf = strike * Math.exp(-rate * t);
        double hs = barrier / spot;
        double hs2mu1 = Math.pow(hs, 2 * (mu + 1));
        double hs2mu = Math.pow(hs, 2 * mu);
        double a = phi * sFwd * n(phi * x1) - phi * kDf * n(phi * x1 - phi * sq);
        double bb = phi * sFwd * n(phi * x2) - phi * kDf * n(phi * x2 - phi * sq);
        double c = phi * sFwd * hs2mu1 * n(eta * y1) - phi * kDf * hs2mu * n(eta * y1 - eta * sq);
        double d = phi * sFwd * hs2mu1 * n(eta * y2) - phi * kDf * hs2mu * n(eta * y2 - eta * sq);
        double e = rebate == 0 ? 0 : rebate * Math.exp(-rate * t)
                * (n(eta * x2 - eta * sq) - hs2mu * n(eta * y2 - eta * sq));
        double f = rebate == 0 ? 0 : rebate * (Math.pow(hs, mu + lam) * n(eta * z)
                + Math.pow(hs, mu - lam) * n(eta * z - 2 * eta * lam * sq));
        boolean call = type == OptionType.CALL;
        boolean down = direction == Direction.DOWN;
        boolean xAboveH = strike > barrier;
        double v;
        if (knock == Knock.IN) {
            if (call && down) {
                v = xAboveH ? c + e : a - bb + d + e;
            } else if (call) {
                v = xAboveH ? a + e : bb - c + d + e;
            } else if (down) {
                v = xAboveH ? bb - c + d + e : a + e;
            } else {
                v = xAboveH ? a - bb + d + e : c + e;
            }
        } else {
            if (call && down) {
                v = xAboveH ? a - c + f : bb - d + f;
            } else if (call) {
                v = xAboveH ? f : a - bb + c - d + f;
            } else if (down) {
                v = xAboveH ? a - bb + c - d + f : f;
            } else {
                v = xAboveH ? bb - d + f : a - c + f;
            }
        }
        // Cancellation in the reverse cases can leave a few ulps below zero.
        return Math.max(v, 0);
    }

    private static double n(double x) {
        return MathUtils.normCdf(x);
    }

    /** Down-and-in call, no rebate. Throws if the barrier is at or above spot. */
    public static double downAndInCall(double spot, double strike, double barrier,
                                       double rate, double carry, double vol, double timeYears) {
        requireAlive(spot, barrier, true);
        return price(OptionType.CALL, Direction.DOWN, Knock.IN, spot, strike, barrier, 0,
                rate, carry, vol, timeYears);
    }

    /** Down-and-out call, no rebate. Throws if the barrier is at or above spot. */
    public static double downAndOutCall(double spot, double strike, double barrier,
                                        double rate, double carry, double vol, double timeYears) {
        requireAlive(spot, barrier, true);
        return price(OptionType.CALL, Direction.DOWN, Knock.OUT, spot, strike, barrier, 0,
                rate, carry, vol, timeYears);
    }

    /** Up-and-in put, no rebate. Throws if the barrier is at or below spot. */
    public static double upAndInPut(double spot, double strike, double barrier,
                                    double rate, double carry, double vol, double timeYears) {
        requireAlive(spot, barrier, false);
        return price(OptionType.PUT, Direction.UP, Knock.IN, spot, strike, barrier, 0,
                rate, carry, vol, timeYears);
    }

    /** Up-and-out put, no rebate. Throws if the barrier is at or below spot. */
    public static double upAndOutPut(double spot, double strike, double barrier,
                                     double rate, double carry, double vol, double timeYears) {
        requireAlive(spot, barrier, false);
        return price(OptionType.PUT, Direction.UP, Knock.OUT, spot, strike, barrier, 0,
                rate, carry, vol, timeYears);
    }

    private static void requireAlive(double spot, double barrier, boolean down) {
        if (!(spot > 0) || !(barrier > 0)) {
            throw new IllegalArgumentException("spot, strike, barrier must be > 0");
        }
        if (down ? barrier >= spot : barrier <= spot) {
            throw new IllegalArgumentException((down ? "down" : "up") + " barrier " + barrier
                    + " already breached at spot " + spot);
        }
    }
}
