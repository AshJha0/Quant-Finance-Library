package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;

/**
 * Tick-frequency Greek estimation without tick-frequency repricing: a full
 * Black-Scholes evaluation anchors the position, and every tick updates
 * price/delta by the delta-gamma Taylor expansion — a handful of multiplies,
 * zero allocation — while the anchor is refreshed off the hot path.
 *
 * <pre>
 *   price(S) ≈ price₀ + Δ₀·(S−S₀) + ½·Γ₀·(S−S₀)²
 *   delta(S) ≈ Δ₀ + Γ₀·(S−S₀)
 * </pre>
 *
 * <p>This is how live options risk actually stays tick-fresh: the quadratic
 * error term is O((S−S₀)³·speed), negligible for the sub-0.5% moves between
 * anchor refreshes, and {@link #needsReprice} tells the slow path when the
 * spot has drifted far enough to re-anchor (a pricing-thread job, not the
 * tick thread's).</p>
 *
 * <p>Single-threaded by design — one instance per position per risk thread.
 * {@link #reprice} allocates (it calls {@link BlackScholes#greeks}); it is
 * the <em>anchor</em> operation. {@link #onTick} never allocates.</p>
 */
public final class IncrementalGreeks {

    // Anchor state from the last full reprice.
    private double baseSpot = Double.NaN;
    private double basePrice;
    private double baseDelta;
    private double baseGamma;
    private double baseVega;
    private double baseTheta;

    // Tick-fresh estimates.
    private double lastSpot = Double.NaN;
    private double estimatedPrice;
    private double estimatedDelta;

    /**
     * Full reprice: re-anchors the expansion. Call from the pricing/slow
     * thread — at start-up, on {@link #needsReprice}, on vol or rate marks.
     */
    public void reprice(OptionType type, double spot, double strike, double rate,
                        double carry, double vol, double timeYears) {
        BlackScholes.Greeks g = BlackScholes.greeks(type, spot, strike, rate, carry, vol, timeYears);
        baseSpot = spot;
        basePrice = g.price();
        baseDelta = g.delta();
        baseGamma = g.gamma();
        baseVega = g.vega();
        baseTheta = g.theta();
        lastSpot = spot;
        estimatedPrice = g.price();
        estimatedDelta = g.delta();
    }

    /**
     * The hot path: delta-gamma update from the anchor. No allocation, no
     * transcendental math — two multiplies and three adds.
     */
    public void onTick(double spot) {
        double dS = spot - baseSpot;
        estimatedPrice = basePrice + baseDelta * dS + 0.5 * baseGamma * dS * dS;
        estimatedDelta = baseDelta + baseGamma * dS;
        lastSpot = spot;
    }

    /**
     * Whether spot has drifted beyond {@code maxSpotDrift} from the anchor —
     * the signal for the slow path to {@link #reprice}. The tick thread only
     * reads a flag-style comparison; it never re-anchors itself.
     */
    public boolean needsReprice(double maxSpotDrift) {
        return Math.abs(lastSpot - baseSpot) > maxSpotDrift;
    }

    /** Tick-fresh price estimate (per unit; scale by position externally). */
    public double estimatedPrice() {
        return estimatedPrice;
    }

    /** Tick-fresh delta estimate. */
    public double estimatedDelta() {
        return estimatedDelta;
    }

    /** Anchor gamma (constant between reprices — second order is the anchor's). */
    public double gamma() {
        return baseGamma;
    }

    /** Anchor vega — vol risk only changes on reprice, not per tick. */
    public double vega() {
        return baseVega;
    }

    /** Anchor theta. */
    public double theta() {
        return baseTheta;
    }

    /** The spot the expansion is anchored at (NaN before the first reprice). */
    public double anchorSpot() {
        return baseSpot;
    }
}
