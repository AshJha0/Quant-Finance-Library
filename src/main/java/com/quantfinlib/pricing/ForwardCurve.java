package com.quantfinlib.pricing;

import java.util.Map;
import java.util.TreeMap;

/**
 * Implied FX forward curve construction from market outright forwards, with
 * interpolation, implied rate differentials, and covered-interest-parity
 * arbitrage checks against deposit rates.
 */
public final class ForwardCurve {

    private final double spot;
    private final TreeMap<Double, Double> outrights = new TreeMap<>();   // tenorYears -> outright forward

    public ForwardCurve(double spot) {
        if (spot <= 0) {
            throw new IllegalArgumentException("spot must be positive");
        }
        this.spot = spot;
        outrights.put(0.0, spot);
    }

    public ForwardCurve addPoint(double tenorYears, double outrightForward) {
        if (tenorYears <= 0) {
            throw new IllegalArgumentException("tenor must be positive");
        }
        outrights.put(tenorYears, outrightForward);
        return this;
    }

    public double spot() {
        return spot;
    }

    /**
     * Interpolated outright forward at the tenor (linear in forward points
     * between pillars; flat-slope extrapolation beyond the last pillar).
     */
    public double forward(double tenorYears) {
        if (tenorYears <= 0) {
            return spot;
        }
        Map.Entry<Double, Double> lo = outrights.floorEntry(tenorYears);
        Map.Entry<Double, Double> hi = outrights.ceilingEntry(tenorYears);
        if (hi == null) {
            // Extrapolate using the slope of the last two pillars.
            Map.Entry<Double, Double> last = outrights.lastEntry();
            Map.Entry<Double, Double> prev = outrights.lowerEntry(last.getKey());
            double slope = (last.getValue() - prev.getValue()) / (last.getKey() - prev.getKey());
            return last.getValue() + slope * (tenorYears - last.getKey());
        }
        if (lo.getKey().equals(hi.getKey())) {
            return lo.getValue();
        }
        double w = (tenorYears - lo.getKey()) / (hi.getKey() - lo.getKey());
        return lo.getValue() + w * (hi.getValue() - lo.getValue());
    }

    /** Forward points at the tenor (outright minus spot). */
    public double forwardPoints(double tenorYears) {
        return forward(tenorYears) - spot;
    }

    /**
     * Implied continuously-compounded rate differential (domestic minus
     * foreign) from covered interest parity: {@code F = S * e^((rd-rf)*t)}.
     */
    public double impliedRateDifferential(double tenorYears) {
        return Math.log(forward(tenorYears) / spot) / tenorYears;
    }

    /** CIP-theoretical forward from simple deposit rates. */
    public static double theoreticalForward(double spot, double domesticRate, double foreignRate, double tenorYears) {
        return spot * (1 + domesticRate * tenorYears) / (1 + foreignRate * tenorYears);
    }

    /**
     * Covered-interest-parity arbitrage check: market forward versus the
     * deposit-implied forward, in basis points (positive = market forward rich).
     */
    public double mispricingBps(double tenorYears, double domesticRate, double foreignRate) {
        double theoretical = theoreticalForward(spot, domesticRate, foreignRate, tenorYears);
        return (forward(tenorYears) - theoretical) / theoretical * 1e4;
    }
}
