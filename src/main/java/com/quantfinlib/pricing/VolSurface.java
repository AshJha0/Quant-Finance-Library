package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;

import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;

/**
 * Implied volatility surface built from (expiry, strike, vol) pillar quotes —
 * or directly from market option prices via implied-vol inversion.
 *
 * <p>Interpolation follows market practice:</p>
 * <ul>
 *   <li><b>Within a smile</b> — linear in vol across strikes, flat
 *       extrapolation beyond the quoted wings.</li>
 *   <li><b>Across expiries</b> — linear in <i>total variance</i>
 *       ({@code w = σ²·T}) at fixed strike, which keeps the interpolated
 *       term structure calendar-consistent; flat vol extrapolation outside
 *       the quoted expiry range.</li>
 * </ul>
 *
 * Immutable and thread-safe once built.
 */
public final class VolSurface {

    private final TreeMap<Double, TreeMap<Double, Double>> smiles;   // expiry -> (strike -> vol)

    private VolSurface(TreeMap<Double, TreeMap<Double, Double>> smiles) {
        this.smiles = smiles;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final TreeMap<Double, TreeMap<Double, Double>> smiles = new TreeMap<>();

        /** Adds one pillar quote. */
        public Builder add(double expiryYears, double strike, double vol) {
            if (expiryYears <= 0 || strike <= 0 || vol <= 0) {
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
            return new VolSurface(copy);
        }
    }

    /** Interpolated implied volatility at any (expiry, strike). */
    public double vol(double expiryYears, double strike) {
        Map.Entry<Double, TreeMap<Double, Double>> lo = smiles.floorEntry(expiryYears);
        Map.Entry<Double, TreeMap<Double, Double>> hi = smiles.ceilingEntry(expiryYears);
        if (lo == null) {
            return smileVol(hi.getValue(), strike);      // before first expiry: flat
        }
        if (hi == null) {
            return smileVol(lo.getValue(), strike);      // beyond last expiry: flat
        }
        if (lo.getKey().equals(hi.getKey())) {
            return smileVol(lo.getValue(), strike);
        }
        double t1 = lo.getKey(), t2 = hi.getKey();
        double v1 = smileVol(lo.getValue(), strike);
        double v2 = smileVol(hi.getValue(), strike);
        double w1 = v1 * v1 * t1;
        double w2 = v2 * v2 * t2;
        double w = w1 + (w2 - w1) * (expiryYears - t1) / (t2 - t1);
        return Math.sqrt(w / expiryYears);
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

    /** Smile slope between two strikes, in vol points per unit of strike. */
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
