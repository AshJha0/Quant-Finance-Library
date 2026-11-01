package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;

/**
 * Vanna-volga pricing: the FX desk's standard smile-consistent adjustment
 * built from exactly three market pillars (in practice the 25Δ put, ATM and
 * 25Δ call that {@code fx.FxVolSurface} solves from broker RR/BF quotes).
 *
 * <p>The idea: hedge the flat-vol Black-Scholes price's vega, vanna and
 * volga with a portfolio of the three pillars; the market cost of that
 * hedge is the smile adjustment. The classic log-strike weight form used
 * here makes the construction exact <em>at</em> the pillars (pricing a
 * pillar strike returns the pillar's own market vol) and interpolates
 * smoothly between and beyond them:</p>
 *
 * <pre>
 *   price(K) = BS(K; σ_atm) + Σᵢ wᵢ(K) · [BS(Kᵢ; σᵢ) − BS(Kᵢ; σ_atm)]
 *   w₁(K) = vega(K)/vega(K₁) · ln(K₂/K)·ln(K₃/K) / (ln(K₂/K₁)·ln(K₃/K₁))
 * </pre>
 *
 * <p>(cyclic for w₂, w₃). {@link #impliedVol} inverts the adjusted price
 * back through Black-Scholes, giving a full smile from three quotes — the
 * standard method for consistent barrier/touch adjustments and broken-strike
 * marks on FX desks. Conventions match {@link BlackScholes}: {@code carry}
 * is the continuous yield (foreign rate for FX).</p>
 */
public final class VannaVolga {

    private final double[] strikes; // ascending: put wing, ATM, call wing
    private final double[] vols;    // market vols at the pillars
    private final double rate;
    private final double carry;
    private final double timeYears;

    /**
     * @param strikes three ascending pillar strikes (25Δput, ATM, 25Δcall)
     * @param vols    their market vols; vols[1] is the ATM anchor
     */
    public VannaVolga(double[] strikes, double[] vols, double rate, double carry,
                      double timeYears) {
        if (strikes.length != 3 || vols.length != 3) {
            throw new IllegalArgumentException("exactly three pillars required");
        }
        if (!(strikes[0] < strikes[1] && strikes[1] < strikes[2])) {
            throw new IllegalArgumentException("strikes must be strictly ascending");
        }
        for (double v : vols) {
            if (v <= 0) {
                throw new IllegalArgumentException("vols must be > 0");
            }
        }
        if (timeYears <= 0) {
            throw new IllegalArgumentException("timeYears must be > 0");
        }
        this.strikes = strikes.clone();
        this.vols = vols.clone();
        this.rate = rate;
        this.carry = carry;
        this.timeYears = timeYears;
    }

    /** Builds directly from a solved {@code fx.FxVolSurface} pillar (25Δ set). */
    public static VannaVolga ofPillars(double[] strikes, double[] vols, double rate,
                                       double carry, double timeYears) {
        if (strikes.length == 5) {
            // Five-pillar smile (10Δ wings present): vanna-volga uses the
            // classic 25Δ triple — indices 1, 2, 3.
            return new VannaVolga(new double[]{strikes[1], strikes[2], strikes[3]},
                    new double[]{vols[1], vols[2], vols[3]}, rate, carry, timeYears);
        }
        return new VannaVolga(strikes, vols, rate, carry, timeYears);
    }

    /** Smile-consistent price of a vanilla at any strike. */
    public double price(OptionType type, double spot, double strike) {
        if (spot <= 0 || strike <= 0) {
            throw new IllegalArgumentException("spot and strike must be > 0");
        }
        double atm = vols[1];
        double base = BlackScholes.price(type, spot, strike, rate, carry, atm, timeYears);
        double vegaK = BlackScholes.vega(spot, strike, rate, carry, atm, timeYears);
        double adjustment = 0;
        for (int i = 0; i < 3; i++) {
            double vegaI = BlackScholes.vega(spot, strikes[i], rate, carry, atm, timeYears);
            // Pillar hedge cost: market vol vs flat ATM vol at the pillar.
            // OptionType is irrelevant to the vol difference (put-call parity
            // makes call and put vega identical); use calls throughout.
            double marketI = BlackScholes.price(OptionType.CALL, spot, strikes[i], rate, carry,
                    vols[i], timeYears);
            double flatI = BlackScholes.price(OptionType.CALL, spot, strikes[i], rate, carry,
                    atm, timeYears);
            adjustment += weight(i, strike, vegaK, vegaI) * (marketI - flatI);
        }
        return base + adjustment;
    }

    /**
     * Smile-consistent implied vol at any strike: the vanna-volga price
     * inverted through Black-Scholes. Pillar strikes recover their market
     * vols exactly.
     *
     * <p>The inversion uses the out-of-the-money option (put below the
     * forward, call above), whose price is all time value; inverting a deep
     * in-the-money call loses the smile adjustment in the intrinsic value.</p>
     *
     * <p>Wings. Beyond the {@link #WING_DELTA} strikes (forward delta at the
     * ATM vol, never inside the outer pillars) the quadratic log-strike
     * weights grow without bound and produce negative butterflies, so the
     * raw formula is not used there. Where the vol at the cutoff falls going
     * outward it is held flat (a convex join); where it rises outward it is
     * continued linearly in log-strike with the cutoff slope, which joins
     * smoothly instead of leaving the concave kink a flat cap would. The
     * continuation does not enforce Lee's (2004) moment bound and should not
     * be pushed many standard deviations out.</p>
     */
    public double impliedVol(double spot, double strike) {
        if (!(spot > 0) || !(strike > 0)) {
            throw new IllegalArgumentException("spot and strike must be > 0");
        }
        double forward = spot * Math.exp((rate - carry) * timeYears);
        double sv = vols[1] * Math.sqrt(timeYears);
        double kLo = Math.min(strikes[0], forward
                * Math.exp(-com.quantfinlib.util.MathUtils.normInv(1 - WING_DELTA) * sv + 0.5 * sv * sv));
        double kHi = Math.max(strikes[2], forward
                * Math.exp(-com.quantfinlib.util.MathUtils.normInv(WING_DELTA) * sv + 0.5 * sv * sv));
        if (strike >= kLo && strike <= kHi) {
            return rawVol(spot, strike, forward);
        }
        double kc = strike < kLo ? kLo : kHi;
        double outward = strike < kLo ? -1 : 1;           // direction of increasing |x|
        double vc = rawVol(spot, kc, forward);
        double dx = 1e-4;
        double vIn = rawVol(spot, kc * Math.exp(-outward * dx), forward);
        double slopeOut = (vc - vIn) / dx;                 // dσ per unit log-strike, outward
        if (slopeOut <= 0) {
            return vc;
        }
        return vc + slopeOut * Math.abs(Math.log(strike / kc));
    }

    private double rawVol(double spot, double k, double forward) {
        OptionType otm = k < forward ? OptionType.PUT : OptionType.CALL;
        double p = price(otm, spot, k);
        return BlackScholes.impliedVol(otm, p, spot, k, rate, carry, timeYears);
    }

    /** Forward delta of the strikes beyond which {@link #impliedVol} stops using the raw formula. */
    public static final double WING_DELTA = 0.10;

    /** The classic log-strike interpolation weights (exact at the pillars). */
    private double weight(int i, double k, double vegaK, double vegaI) {
        double x = Math.log(k);
        double x1 = Math.log(strikes[0]);
        double x2 = Math.log(strikes[1]);
        double x3 = Math.log(strikes[2]);
        double num;
        double den;
        switch (i) {
            case 0 -> {
                num = (x2 - x) * (x3 - x);
                den = (x2 - x1) * (x3 - x1);
            }
            case 1 -> {
                num = (x - x1) * (x3 - x);
                den = (x2 - x1) * (x3 - x2);
            }
            default -> {
                num = (x - x1) * (x - x2);
                den = (x3 - x1) * (x3 - x2);
            }
        }
        return (vegaK / vegaI) * (num / den);
    }
}
