package com.quantfinlib.rates;

/**
 * How a {@link DiscountCurve} fills in between its pillars. Both schemes
 * work on {@code y(t) = -ln P(t)}, the integrated instantaneous forward, so
 * every pillar discount factor is reproduced exactly.
 */
public enum CurveInterpolation {

    /**
     * Linear in {@code ln P(t)}: the instantaneous forward is constant on each
     * pillar interval and jumps at the pillars. Local (moving one pillar
     * changes only its two neighbouring intervals), which makes a
     * pillar-by-pillar bootstrap exact. Extrapolates with the last interval's
     * forward.
     */
    LOG_LINEAR_DISCOUNT,

    /**
     * Monotone-convex (Hagan and West 2006, "Interpolation Methods for Curve
     * Construction", Applied Mathematical Finance 13(2), sections 4-5): a
     * continuous instantaneous forward curve built from the discrete
     * pillar-interval forwards, keeping each interval's average forward and
     * preserving monotonicity of the discrete forwards. The positivity
     * amendment of the paper is not applied, so negative rates are allowed.
     * Extrapolates with the forward at the last pillar.
     */
    MONOTONE_CONVEX
}
