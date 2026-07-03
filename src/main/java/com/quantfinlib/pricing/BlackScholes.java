package com.quantfinlib.pricing;

import com.quantfinlib.util.MathUtils;

/**
 * Black-Scholes-Merton option pricing and Greeks with a continuous carry
 * yield {@code q}: set {@code q} to the dividend yield for equities or the
 * foreign interest rate for FX (Garman-Kohlhagen). All rates and volatility
 * are annualized; theta is per year; vega/rho are per 1.00 change.
 */
public final class BlackScholes {

    public enum OptionType {
        CALL, PUT;

        public int sign() {
            return this == CALL ? 1 : -1;
        }
    }

    /** Full Greek set for one option. */
    public record Greeks(double price, double delta, double gamma, double vega,
                         double theta, double rho) {
    }

    private BlackScholes() {
    }

    public static double price(OptionType type, double spot, double strike,
                               double rate, double carry, double vol, double timeYears) {
        if (timeYears <= 0) {
            return intrinsic(type, spot, strike);
        }
        double d1 = d1(spot, strike, rate, carry, vol, timeYears);
        double d2 = d1 - vol * Math.sqrt(timeYears);
        double df = Math.exp(-rate * timeYears);
        double cf = Math.exp(-carry * timeYears);
        if (type == OptionType.CALL) {
            return spot * cf * MathUtils.normCdf(d1) - strike * df * MathUtils.normCdf(d2);
        }
        return strike * df * MathUtils.normCdf(-d2) - spot * cf * MathUtils.normCdf(-d1);
    }

    public static double delta(OptionType type, double spot, double strike,
                               double rate, double carry, double vol, double timeYears) {
        if (timeYears <= 0) {
            return intrinsicDelta(type, spot, strike);
        }
        double d1 = d1(spot, strike, rate, carry, vol, timeYears);
        double cf = Math.exp(-carry * timeYears);
        return type == OptionType.CALL
                ? cf * MathUtils.normCdf(d1)
                : cf * (MathUtils.normCdf(d1) - 1);
    }

    public static double gamma(double spot, double strike, double rate, double carry,
                               double vol, double timeYears) {
        if (timeYears <= 0) {
            return 0;
        }
        double d1 = d1(spot, strike, rate, carry, vol, timeYears);
        return Math.exp(-carry * timeYears) * MathUtils.normPdf(d1)
                / (spot * vol * Math.sqrt(timeYears));
    }

    /** Per 1.00 change in volatility (divide by 100 for per-vol-point). */
    public static double vega(double spot, double strike, double rate, double carry,
                              double vol, double timeYears) {
        if (timeYears <= 0) {
            return 0;
        }
        double d1 = d1(spot, strike, rate, carry, vol, timeYears);
        return spot * Math.exp(-carry * timeYears) * MathUtils.normPdf(d1) * Math.sqrt(timeYears);
    }

    /** Per year (divide by 365 for per-calendar-day). */
    public static double theta(OptionType type, double spot, double strike,
                               double rate, double carry, double vol, double timeYears) {
        if (timeYears <= 0) {
            return 0;
        }
        double sqrtT = Math.sqrt(timeYears);
        double d1 = d1(spot, strike, rate, carry, vol, timeYears);
        double d2 = d1 - vol * sqrtT;
        double cf = Math.exp(-carry * timeYears);
        double df = Math.exp(-rate * timeYears);
        double common = -spot * cf * MathUtils.normPdf(d1) * vol / (2 * sqrtT);
        if (type == OptionType.CALL) {
            return common - rate * strike * df * MathUtils.normCdf(d2)
                    + carry * spot * cf * MathUtils.normCdf(d1);
        }
        return common + rate * strike * df * MathUtils.normCdf(-d2)
                - carry * spot * cf * MathUtils.normCdf(-d1);
    }

    /** Per 1.00 change in the domestic rate. */
    public static double rho(OptionType type, double spot, double strike,
                             double rate, double carry, double vol, double timeYears) {
        if (timeYears <= 0) {
            return 0;
        }
        double d2 = d1(spot, strike, rate, carry, vol, timeYears) - vol * Math.sqrt(timeYears);
        double df = Math.exp(-rate * timeYears);
        return type == OptionType.CALL
                ? strike * timeYears * df * MathUtils.normCdf(d2)
                : -strike * timeYears * df * MathUtils.normCdf(-d2);
    }

    public static Greeks greeks(OptionType type, double spot, double strike,
                                double rate, double carry, double vol, double timeYears) {
        return new Greeks(
                price(type, spot, strike, rate, carry, vol, timeYears),
                delta(type, spot, strike, rate, carry, vol, timeYears),
                gamma(spot, strike, rate, carry, vol, timeYears),
                vega(spot, strike, rate, carry, vol, timeYears),
                theta(type, spot, strike, rate, carry, vol, timeYears),
                rho(type, spot, strike, rate, carry, vol, timeYears));
    }

    /** Implied volatility by bisection (price must be arbitrage-consistent). */
    public static double impliedVol(OptionType type, double marketPrice, double spot,
                                    double strike, double rate, double carry, double timeYears) {
        double lo = 1e-4, hi = 5.0;
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            if (price(type, spot, strike, rate, carry, mid, timeYears) < marketPrice) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    public static double intrinsic(OptionType type, double spot, double strike) {
        return Math.max(0, type.sign() * (spot - strike));
    }

    private static double intrinsicDelta(OptionType type, double spot, double strike) {
        if (type == OptionType.CALL) {
            return spot > strike ? 1 : 0;
        }
        return spot < strike ? -1 : 0;
    }

    private static double d1(double spot, double strike, double rate, double carry,
                             double vol, double timeYears) {
        return (Math.log(spot / strike) + (rate - carry + 0.5 * vol * vol) * timeYears)
                / (vol * Math.sqrt(timeYears));
    }
}
