package com.quantfinlib.hedging;

import com.quantfinlib.pricing.BlackScholes;
import com.quantfinlib.pricing.BlackScholes.OptionType;
import com.quantfinlib.stats.SpecialFunctions;

import java.util.ArrayList;
import java.util.List;

/**
 * An options book on one underlying: aggregate Greeks across positions,
 * spot×vol scenario grids, and delta-gamma-vega-theta P&amp;L explain — the
 * risk view a desk runs, not a single option.
 *
 * <p>Positions carry their own implied vols (smile-aware if fed from
 * {@link com.quantfinlib.pricing.VolSurface}); quantities are signed
 * (negative = short). The underlying hedge position is part of the book, so
 * net delta reflects the hedged residual.</p>
 */
public final class OptionsBook {

    /** One option position; {@code quantity} in option units (contract multiplier applied by caller). */
    public record OptionPosition(String label, OptionType type, double strike,
                                 double expiryYears, double quantity, double impliedVol) {
    }

    /** Aggregated book value and Greeks. */
    public record BookGreeks(double value, double delta, double gamma, double vega,
                             double theta, double rho) {
    }

    /**
     * Taylor attribution of a P&amp;L move: delta, gamma, vega, vanna
     * ({@code ∂²V/∂S∂σ · dS·dσ}), volga ({@code ½·∂²V/∂σ² · dσ²}) and theta,
     * with the remainder in {@code unexplained}.
     */
    public record PnlExplain(double actualPnl, double deltaPnl, double gammaPnl,
                             double vegaPnl, double vannaPnl, double volgaPnl,
                             double thetaPnl, double unexplained) {
    }

    private final double spot;
    private final double rate;
    private final double carry;
    private final List<OptionPosition> options = new ArrayList<>();
    private double underlyingQty;

    public OptionsBook(double spot, double rate, double carry) {
        this.spot = spot;
        this.rate = rate;
        this.carry = carry;
    }

    public OptionsBook addOption(OptionPosition position) {
        options.add(position);
        return this;
    }

    public OptionsBook addOption(String label, OptionType type, double strike,
                                 double expiryYears, double quantity, double impliedVol) {
        return addOption(new OptionPosition(label, type, strike, expiryYears, quantity, impliedVol));
    }

    /** Adds (or hedges with) the underlying; negative = short. */
    public OptionsBook addUnderlying(double quantity) {
        this.underlyingQty += quantity;
        return this;
    }

    public double spot() {
        return spot;
    }

    public List<OptionPosition> positions() {
        return List.copyOf(options);
    }

    public double underlyingQuantity() {
        return underlyingQty;
    }

    // ------------------------------------------------------------------
    // Aggregation
    // ------------------------------------------------------------------

    /** Book value at current market (options MTM + underlying). */
    public double value() {
        return valueAt(spot, 0, 0);
    }

    /** Aggregated Greeks: options plus the underlying (delta 1 per unit). */
    public BookGreeks greeks() {
        double value = underlyingQty * spot;
        double delta = underlyingQty;
        double gamma = 0, vega = 0, theta = 0, rho = 0;
        for (OptionPosition p : options) {
            BlackScholes.Greeks g = BlackScholes.greeks(p.type(), spot, p.strike(),
                    rate, carry, p.impliedVol(), p.expiryYears());
            value += p.quantity() * g.price();
            delta += p.quantity() * g.delta();
            gamma += p.quantity() * g.gamma();
            vega += p.quantity() * g.vega();
            theta += p.quantity() * g.theta();
            rho += p.quantity() * g.rho();
        }
        return new BookGreeks(value, delta, gamma, vega, theta, rho);
    }

    /** Full revaluation at a shifted market (parallel vol shift, time decay). */
    public double valueAt(double newSpot, double volShift, double timeDecayYears) {
        double value = underlyingQty * newSpot;
        for (OptionPosition p : options) {
            double t = Math.max(0, p.expiryYears() - timeDecayYears);
            double vol = Math.max(1e-4, p.impliedVol() + volShift);
            value += p.quantity() * BlackScholes.price(p.type(), newSpot, p.strike(),
                    rate, carry, vol, t);
        }
        return value;
    }

    /**
     * Spot×vol scenario P&amp;L grid (full revaluation, no time decay):
     * {@code grid[i][j]} = P&amp;L at spot shifted by {@code spotShiftsPct[i]}
     * (fraction, 0.05 = +5%) and vol shifted by {@code volShiftsAbs[j]}
     * (absolute, 0.02 = +2 vol points).
     */
    public double[][] scenarioGrid(double[] spotShiftsPct, double[] volShiftsAbs) {
        double base = value();
        double[][] grid = new double[spotShiftsPct.length][volShiftsAbs.length];
        for (int i = 0; i < spotShiftsPct.length; i++) {
            for (int j = 0; j < volShiftsAbs.length; j++) {
                grid[i][j] = valueAt(spot * (1 + spotShiftsPct[i]), volShiftsAbs[j], 0) - base;
            }
        }
        return grid;
    }

    /**
     * Second-order P&amp;L explain versus full revaluation:
     *
     * <pre>  dV ≈ Δ·dS + ½Γ·dS² + ν·dσ + vanna·dS·dσ + ½volga·dσ² + Θ·dt</pre>
     *
     * with Black-Scholes-Merton vanna {@code −e^{−qT}·φ(d1)·d2/σ} and volga
     * {@code ν·d1·d2/σ} per position (Haug, The Complete Guide to Option
     * Pricing Formulas, 2nd ed., 2.2). {@code volShift} is a parallel
     * absolute shift of every position's implied vol (no smile dynamics);
     * theta is per year, so {@code timeDecayYears} multiplies it directly.
     * Rho and the time cross terms are left in {@code unexplained}.
     */
    public PnlExplain pnlExplain(double newSpot, double volShift, double timeDecayYears) {
        BookGreeks g = greeks();
        double vanna = 0;
        double volga = 0;
        for (OptionPosition p : options) {
            double[] vv = vannaVolga(spot, p.strike(), rate, carry, p.impliedVol(), p.expiryYears());
            vanna += p.quantity() * vv[0];
            volga += p.quantity() * vv[1];
        }
        double actual = valueAt(newSpot, volShift, timeDecayYears) - value();
        double dS = newSpot - spot;
        double deltaPnl = g.delta() * dS;
        double gammaPnl = 0.5 * g.gamma() * dS * dS;
        double vegaPnl = g.vega() * volShift;
        double vannaPnl = vanna * dS * volShift;
        double volgaPnl = 0.5 * volga * volShift * volShift;
        double thetaPnl = g.theta() * timeDecayYears;
        double unexplained = actual - deltaPnl - gammaPnl - vegaPnl - vannaPnl - volgaPnl - thetaPnl;
        return new PnlExplain(actual, deltaPnl, gammaPnl, vegaPnl, vannaPnl, volgaPnl,
                thetaPnl, unexplained);
    }

    /** {vanna, volga} of one Black-Scholes-Merton option (same for calls and puts). */
    static double[] vannaVolga(double s, double k, double r, double q, double vol, double t) {
        if (!(t > 0) || !(vol > 0)) {
            return new double[]{0, 0};
        }
        double sqrtT = Math.sqrt(t);
        double d1 = (Math.log(s / k) + (r - q + 0.5 * vol * vol) * t) / (vol * sqrtT);
        double d2 = d1 - vol * sqrtT;
        double pdf = SpecialFunctions.normPdf(d1);
        double vega = s * Math.exp(-q * t) * pdf * sqrtT;
        return new double[]{-Math.exp(-q * t) * pdf * d2 / vol, vega * d1 * d2 / vol};
    }
}
