package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;

import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;

/**
 * Implied volatility surface built from (expiry, strike, vol) pillar quotes —
 * or directly from market option prices via implied-vol inversion.
 *
 * <p>Interpolation:</p>
 * <ul>
 *   <li><b>Within a smile</b> — linear in vol across strikes, flat
 *       extrapolation beyond the quoted wings.</li>
 *   <li><b>Across expiries</b> — linear in total variance w = σ²T. When a
 *       forward is supplied for every expiry ({@link Builder#forward}), the
 *       interpolation is at fixed log-moneyness ln(K/F(t)), with F(t)
 *       log-linear in t; this is the convention under which a smile that
 *       rolls with the forward stays calendar-arbitrage-free (Gatheral 2006,
 *       <i>The Volatility Surface</i>, chapter 3). Without forwards it falls
 *       back to fixed strike. Flat vol outside the quoted expiry range.</li>
 * </ul>
 *
 * <p>Nothing in the construction enforces absence of arbitrage. Two checks
 * are provided: {@link #calendarViolations} (total variance must not fall
 * with expiry at fixed moneyness) and {@link #minimumDensity} (the
 * butterfly condition ∂²C/∂K² &gt;= 0 on one smile). Linear interpolation
 * with flat wings puts a concave kink at the outermost pillar of any skewed
 * smile, and the density check reports it there; a smooth parametric smile
 * (SABR, {@link SabrModel}) avoids that. For an arbitrage-free parametric
 * surface use {@link SsviSurface} or {@link SviSurface}; Dupire local vol
 * is {@link DupireLocalVol}.</p>
 *
 * Immutable and thread-safe once built.
 */
public final class VolSurface {

    /** Total variance falls between two expiries at this log-moneyness. */
    public record CalendarViolation(double expiry1, double expiry2, double logMoneyness,
                                    double totalVariance1, double totalVariance2) {
    }

    private final TreeMap<Double, TreeMap<Double, Double>> smiles;   // expiry -> (strike -> vol)
    private final TreeMap<Double, Double> forwards;                  // expiry -> forward, or empty

    private VolSurface(TreeMap<Double, TreeMap<Double, Double>> smiles,
                       TreeMap<Double, Double> forwards) {
        this.smiles = smiles;
        this.forwards = forwards;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final TreeMap<Double, TreeMap<Double, Double>> smiles = new TreeMap<>();
        private final TreeMap<Double, Double> forwards = new TreeMap<>();

        /**
         * Forward for an expiry. Either give one for every quoted expiry
         * (time interpolation at fixed log-moneyness) or none (fixed strike).
         */
        public Builder forward(double expiryYears, double forward) {
            if (!(expiryYears > 0) || !(forward > 0) || !Double.isFinite(forward)) {
                throw new IllegalArgumentException("expiry and forward must be positive");
            }
            forwards.put(expiryYears, forward);
            return this;
        }

        /** Adds one pillar quote. */
        public Builder add(double expiryYears, double strike, double vol) {
            // !(x > 0) rejects NaN too: impliedVol returns NaN for
            // unattainable prices, and a NaN pillar poisons every
            // neighboring strike through interpolation.
            if (!(expiryYears > 0) || !(strike > 0) || !(vol > 0) || vol == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException(
                        "expiry, strike and vol must be positive: " + expiryYears + "/" + strike + "/" + vol);
            }
            smiles.computeIfAbsent(expiryYears, k -> new TreeMap<>()).put(strike, vol);
            return this;
        }

        /** Adds a pillar from a market option price via implied-vol inversion. */
        public Builder addFromPrice(OptionType type, double marketPrice, double spot,
                                    double strike, double rate, double carry, double expiryYears) {
            return add(expiryYears, strike,
                    BlackScholes.impliedVol(type, marketPrice, spot, strike, rate, carry, expiryYears));
        }

        public VolSurface build() {
            if (smiles.isEmpty()) {
                throw new IllegalStateException("no pillar quotes");
            }
            // Deep-copy so the builder can be reused safely.
            TreeMap<Double, TreeMap<Double, Double>> copy = new TreeMap<>();
            smiles.forEach((t, smile) -> copy.put(t, new TreeMap<>(smile)));
            if (!forwards.isEmpty() && !forwards.keySet().equals(smiles.keySet())) {
                throw new IllegalStateException(
                        "forwards must be given for exactly the quoted expiries " + smiles.keySet());
            }
            return new VolSurface(copy, new TreeMap<>(forwards));
        }
    }

    /** Interpolated implied volatility at any (expiry, strike). */
    public double vol(double expiryYears, double strike) {
        Map.Entry<Double, TreeMap<Double, Double>> lo = smiles.floorEntry(expiryYears);
        Map.Entry<Double, TreeMap<Double, Double>> hi = smiles.ceilingEntry(expiryYears);
        double x = forwards.isEmpty() ? 0 : Math.log(strike / forwardAt(expiryYears));
        if (lo == null) {
            return smileVol(hi.getValue(), strikeAt(hi.getKey(), strike, x)); // flat before first
        }
        if (hi == null) {
            return smileVol(lo.getValue(), strikeAt(lo.getKey(), strike, x)); // flat beyond last
        }
        if (lo.getKey().equals(hi.getKey())) {
            return smileVol(lo.getValue(), strike);
        }
        double t1 = lo.getKey(), t2 = hi.getKey();
        double v1 = smileVol(lo.getValue(), strikeAt(t1, strike, x));
        double v2 = smileVol(hi.getValue(), strikeAt(t2, strike, x));
        double w1 = v1 * v1 * t1;
        double w2 = v2 * v2 * t2;
        double w = w1 + (w2 - w1) * (expiryYears - t1) / (t2 - t1);
        return Math.sqrt(w / expiryYears);
    }

    /** Strike on pillar {@code t} with the same log-moneyness (or the same strike). */
    private double strikeAt(double t, double strike, double x) {
        return forwards.isEmpty() ? strike : forwards.get(t) * Math.exp(x);
    }

    /**
     * Forward at an expiry, log-linear between pillars and flat outside.
     *
     * @throws IllegalStateException if the surface was built without forwards
     */
    public double forwardAt(double expiryYears) {
        if (forwards.isEmpty()) {
            throw new IllegalStateException("surface built without forwards");
        }
        Map.Entry<Double, Double> lo = forwards.floorEntry(expiryYears);
        Map.Entry<Double, Double> hi = forwards.ceilingEntry(expiryYears);
        if (lo == null) {
            return hi.getValue();
        }
        if (hi == null || lo.getKey().equals(hi.getKey())) {
            return lo.getValue();
        }
        double w = (expiryYears - lo.getKey()) / (hi.getKey() - lo.getKey());
        return Math.exp(Math.log(lo.getValue()) + w * (Math.log(hi.getValue()) - Math.log(lo.getValue())));
    }

    /**
     * Calendar-arbitrage scan: for each pair of consecutive quoted expiries,
     * total variance at {@code gridPoints} log-moneyness values spanning both
     * smiles' quoted range must not decrease. Returns every violation found
     * (empty when clean). Without forwards, moneyness is ln(K) at fixed strike.
     */
    public java.util.List<CalendarViolation> calendarViolations(int gridPoints) {
        if (gridPoints < 2) {
            throw new IllegalArgumentException("gridPoints must be >= 2");
        }
        java.util.List<CalendarViolation> out = new java.util.ArrayList<>();
        Double prev = null;
        TreeMap<Double, Double> prevSmile = null;
        for (java.util.Map.Entry<Double, TreeMap<Double, Double>> e : smiles.entrySet()) {
            double t = e.getKey();
            TreeMap<Double, Double> smile = e.getValue();
            if (prev != null) {
                double xMin = Math.min(logMoneyness(prev, prevSmile.firstKey()),
                        logMoneyness(t, smile.firstKey()));
                double xMax = Math.max(logMoneyness(prev, prevSmile.lastKey()),
                        logMoneyness(t, smile.lastKey()));
                for (int i = 0; i < gridPoints; i++) {
                    double x = xMin + (xMax - xMin) * i / (gridPoints - 1);
                    double v1 = smileVol(prevSmile, fromMoneyness(prev, x));
                    double v2 = smileVol(smile, fromMoneyness(t, x));
                    double w1 = v1 * v1 * prev;
                    double w2 = v2 * v2 * t;
                    if (w2 < w1 * (1 - 1e-12)) {
                        out.add(new CalendarViolation(prev, t, x, w1, w2));
                    }
                }
            }
            prevSmile = smile;
            prev = t;
        }
        return out;
    }

    private double logMoneyness(double t, double strike) {
        return forwards.isEmpty() ? Math.log(strike) : Math.log(strike / forwards.get(t));
    }

    private double fromMoneyness(double t, double x) {
        return forwards.isEmpty() ? Math.exp(x) : forwards.get(t) * Math.exp(x);
    }

    /**
     * Smallest butterfly density ∂²C/∂K² (undiscounted Black call on the
     * interpolated smile, central second difference) over {@code n} strikes
     * across the quoted range of pillar expiry {@code expiryYears}. Negative
     * means the smile admits a butterfly arbitrage. Kinks of the linear
     * interpolation show up as spikes, not as negative density, unless the
     * slope change itself is arbitrageable.
     */
    public double minimumDensity(double expiryYears, double forward, int n) {
        TreeMap<Double, Double> smile = smiles.get(expiryYears);
        if (smile == null) {
            throw new IllegalArgumentException("no pillar expiry " + expiryYears);
        }
        if (!(forward > 0) || n < 2) {
            throw new IllegalArgumentException("forward must be > 0 and n >= 2");
        }
        double kMin = smile.firstKey(), kMax = smile.lastKey();
        if (kMax == kMin) {
            kMin *= 0.9;
            kMax *= 1.1;
        }
        double min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            double k = kMin + (kMax - kMin) * i / (n - 1);
            double h = 1e-4 * k;
            double c = Black76.price(OptionType.CALL, forward, k, 0, smileVol(smile, k), expiryYears);
            double cUp = Black76.price(OptionType.CALL, forward, k + h, 0, smileVol(smile, k + h), expiryYears);
            double cDn = Black76.price(OptionType.CALL, forward, k - h, 0, smileVol(smile, k - h), expiryYears);
            min = Math.min(min, (cUp - 2 * c + cDn) / (h * h));
        }
        return min;
    }

    /** ATM vol, taking the forward (or spot) as the at-the-money strike. */
    public double atmVol(double expiryYears, double forward) {
        return vol(expiryYears, forward);
    }

    /** Option price using the surface vol at (expiry, strike). */
    public double price(OptionType type, double spot, double strike,
                        double rate, double carry, double expiryYears) {
        return BlackScholes.price(type, spot, strike, rate, carry,
                vol(expiryYears, strike), expiryYears);
    }

    /** Chord slope of the smile between two strikes, in decimal vol per unit of strike. */
    public double skew(double expiryYears, double strikeLow, double strikeHigh) {
        return (vol(expiryYears, strikeHigh) - vol(expiryYears, strikeLow))
                / (strikeHigh - strikeLow);
    }

    public NavigableSet<Double> expiries() {
        return smiles.navigableKeySet();
    }

    public NavigableSet<Double> strikes(double expiryYears) {
        TreeMap<Double, Double> smile = smiles.get(expiryYears);
        if (smile == null) {
            throw new IllegalArgumentException("no pillar expiry " + expiryYears);
        }
        return smile.navigableKeySet();
    }

    /** Linear in strike inside the smile; flat beyond the quoted wings. */
    private static double smileVol(TreeMap<Double, Double> smile, double strike) {
        Map.Entry<Double, Double> lo = smile.floorEntry(strike);
        Map.Entry<Double, Double> hi = smile.ceilingEntry(strike);
        if (lo == null) {
            return hi.getValue();
        }
        if (hi == null) {
            return lo.getValue();
        }
        if (lo.getKey().equals(hi.getKey())) {
            return lo.getValue();
        }
        double w = (strike - lo.getKey()) / (hi.getKey() - lo.getKey());
        return lo.getValue() + w * (hi.getValue() - lo.getValue());
    }
}
