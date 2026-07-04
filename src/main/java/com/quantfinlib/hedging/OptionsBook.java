package com.quantfinlib.hedging;

import com.quantfinlib.pricing.BlackScholes;
import com.quantfinlib.pricing.BlackScholes.OptionType;

import java.util.ArrayList;
import java.util.List;

/**
 * An options book on one underlying: aggregate Greeks across positions,
 * spot×vol scenario grids, and delta-gamma-vega-theta P&L explain — the
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

    /** First/second-order attribution of a P&L move. */
    public record PnlExplain(double actualPnl, double deltaPnl, double gammaPnl,
                             double vegaPnl, double thetaPnl, double unexplained) {
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
     * Spot×vol scenario P&L grid (full revaluation, no time decay):
     * {@code grid[i][j]} = P&L at spot shifted by {@code spotShiftsPct[i]}
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
     * Delta-gamma-vega-theta P&L explain versus full revaluation: how much of
     * the actual move the Greeks account for, and what is left unexplained
     * (higher-order and cross terms).
     */
    public PnlExplain pnlExplain(double newSpot, double volShift, double timeDecayYears) {
        BookGreeks g = greeks();
        double actual = valueAt(newSpot, volShift, timeDecayYears) - value();
        double dS = newSpot - spot;
        double deltaPnl = g.delta() * dS;
        double gammaPnl = 0.5 * g.gamma() * dS * dS;
        double vegaPnl = g.vega() * volShift;
        double thetaPnl = g.theta() * timeDecayYears;
        double unexplained = actual - deltaPnl - gammaPnl - vegaPnl - thetaPnl;
        return new PnlExplain(actual, deltaPnl, gammaPnl, vegaPnl, thetaPnl, unexplained);
    }
}
