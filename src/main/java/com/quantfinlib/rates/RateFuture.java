package com.quantfinlib.rates;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A short-term interest rate future (3M SOFR/Euribor style) on the index
 * period {@code [start, end]}, quoted by its futures rate
 * {@code rate = (100 - price)/100}.
 *
 * <p>Futures are margined daily, so the futures rate exceeds the forward
 * rate by a convexity adjustment. Under the Hull-White one-factor model
 * {@code dr = (theta(t) - a r) dt + sigma dW} the adjustment between the
 * continuously compounded futures and forward rates over
 * {@code [t1, t2]} is (Hull, Options, Futures and Other Derivatives,
 * 10th ed. 2018, Technical Note 1; Kirikos and Novak 1997, "Convexity
 * conundrums", Risk 10(3)):</p>
 * <pre>
 *   futures_cc - forward_cc = B(t1,t2)/(t2-t1) * [ B(t1,t2)(1 - e^{-2 a t1}) + 2 a B(0,t1)^2 ] * sigma^2 / (4a)
 *   B(s,u) = (1 - e^{-a(u-s)}) / a
 * </pre>
 * <p>with the Ho-Lee limit {@code sigma^2 t1 t2 / 2} as {@code a -> 0}
 * (Hull 2018, section 6.3), used when {@code meanReversion == 0}. Following Hull, the
 * adjustment is applied to continuously compounded rates over the curve's
 * time axis: the implied futures rate is
 * {@code (exp((f_cc + CA)(t2 - t1)) - 1)/tau}, {@code f_cc} the projection
 * curve's average forward over {@code [t1, t2]} and tau from the day
 * count. Set {@code sigma = 0} for no adjustment.</p>
 *
 * @param meanReversion Hull-White {@code a}, per year, {@code >= 0}
 * @param sigma         Hull-White short-rate volatility (absolute, per sqrt year), {@code >= 0}
 */
public record RateFuture(LocalDate start, LocalDate end, double rate, DayCount dayCount,
                         double meanReversion, double sigma) implements CurveInstrument {

    public RateFuture {
        Legs.requirePeriod(start, end);
        Objects.requireNonNull(dayCount, "dayCount");
        Legs.requireFinite(rate, "rate");
        if (!(meanReversion >= 0) || !Double.isFinite(meanReversion)) {
            throw new IllegalArgumentException("meanReversion must be finite and >= 0");
        }
        if (!(sigma >= 0) || !Double.isFinite(sigma)) {
            throw new IllegalArgumentException("sigma must be finite and >= 0");
        }
    }

    /** From an exchange price, {@code rate = (100 - price)/100}. */
    public static RateFuture ofPrice(LocalDate start, LocalDate end, double price, DayCount dayCount,
                                     double meanReversion, double sigma) {
        return new RateFuture(start, end, (100 - price) / 100, dayCount, meanReversion, sigma);
    }

    /**
     * Hull-White convexity adjustment (continuously compounded futures minus
     * forward rate) for an index period from {@code t1} to {@code t2} years.
     */
    public static double hullWhiteConvexity(double t1, double t2, double meanReversion,
                                            double sigma) {
        if (!(t1 >= 0) || !(t2 > t1) || !Double.isFinite(t2)) {
            throw new IllegalArgumentException("need 0 <= t1 < t2, got " + t1 + ", " + t2);
        }
        double a = meanReversion;
        double s2 = sigma * sigma;
        if (a == 0) {
            return 0.5 * s2 * t1 * t2;
        }
        double b12 = -Math.expm1(-a * (t2 - t1)) / a;
        double b01 = -Math.expm1(-a * t1) / a;
        return b12 / (t2 - t1) * (b12 * -Math.expm1(-2 * a * t1) + 2 * a * b01 * b01) * s2 / (4 * a);
    }

    /** This future's adjustment on the curve's time axis. */
    public double convexityAdjustment(DiscountCurve curve) {
        return hullWhiteConvexity(Math.max(0, curve.time(start)), curve.time(end), meanReversion, sigma);
    }

    @Override
    public LocalDate pillarDate() {
        return end;
    }

    @Override
    public double quote() {
        return rate;
    }

    @Override
    public RateFuture withQuote(double quote) {
        return new RateFuture(start, end, quote, dayCount, meanReversion, sigma);
    }

    @Override
    public double impliedQuote(DiscountCurve discount, DiscountCurve projection) {
        double t1 = projection.time(start);
        double t2 = projection.time(end);
        double forwardCc = (projection.minusLogDf(t2) - projection.minusLogDf(t1)) / (t2 - t1);
        double futuresCc = forwardCc + convexityAdjustment(projection);
        return Math.expm1(futuresCc * (t2 - t1)) / dayCount.yearFraction(start, end);
    }
}
