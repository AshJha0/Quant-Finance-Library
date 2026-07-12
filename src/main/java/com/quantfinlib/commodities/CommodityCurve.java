package com.quantfinlib.commodities;

import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * COMMODITY futures curve — where the P&amp;L of a commodity position
 * mostly does NOT come from being right about the spot price. A futures
 * curve in CONTANGO (upward: deferred contracts above spot) charges a
 * long position negative ROLL YIELD every month — selling the expiring
 * cheap contract to buy the deferred rich one — while BACKWARDATION
 * (downward) pays the long for rolling. Over a decade this roll term has
 * dominated most commodity index returns, which is the single most
 * misunderstood fact about the asset class (the USO oil fund's 2020
 * investors learned it the hard way: spot oil recovered, the contango
 * roll ate the fund anyway).
 *
 * <p>The numbers this class produces:</p>
 * <ul>
 *   <li><b>annualized roll yield</b> between two tenors:
 *       {@code ln(F(near)/F(far)) / (far - near)} — positive in
 *       backwardation (near above far: rolling down the curve pays the
 *       long);</li>
 *   <li><b>implied carry</b> versus spot: from the storage-arbitrage
 *       relation {@code F = S * exp((r + u - y) * t)}, the market-implied
 *       {@code u - y} (storage cost minus convenience yield) is
 *       {@code ln(F(t)/S)/t - r}. A deeply negative value means the
 *       market pays dearly to HOLD the physical (convenience yield —
 *       think heating oil before a cold snap);</li>
 *   <li><b>shape tests</b>: {@code isContango()/isBackwardation()} across
 *       the whole curve, strict at every adjacent pillar pair.</li>
 * </ul>
 *
 * <p>Linear interpolation between pillar prices, no extrapolation
 * (asking for a price beyond the pillars throws — a commodity curve's
 * wings are opinions, not data). Seasonality (natural gas winters) makes
 * whole-curve shape tests false for seasonal commodities by design —
 * use pairwise roll yields there, stated. Research lane.</p>
 */
public final class CommodityCurve {

    private final NavigableMap<Double, Double> futures = new TreeMap<>(); // tenorYears -> price
    private final double spot;

    private CommodityCurve(double spot) {
        this.spot = spot;
    }

    /**
     * @param spot        spot price, &gt; 0
     * @param tenorYears  ascending futures tenors, all &gt; 0
     * @param prices      futures prices per tenor, all &gt; 0
     */
    public static CommodityCurve of(double spot, double[] tenorYears, double[] prices) {
        if (!(spot > 0) || spot == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("spot must be positive and finite, got " + spot);
        }
        int n = tenorYears.length;
        if (n == 0 || prices.length != n) {
            throw new IllegalArgumentException("need aligned, non-empty tenors/prices");
        }
        CommodityCurve c = new CommodityCurve(spot);
        double prev = 0;
        for (int i = 0; i < n; i++) {
            if (!(tenorYears[i] > prev) || tenorYears[i] == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("tenors must be ascending, positive, finite");
            }
            if (!(prices[i] > 0) || prices[i] == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("price must be positive and finite: " + prices[i]);
            }
            c.futures.put(tenorYears[i], prices[i]);
            prev = tenorYears[i];
        }
        return c;
    }

    /** Interpolated futures price; throws beyond the pillars (no extrapolation). */
    public double price(double tenorYears) {
        Double exact = futures.get(tenorYears);
        if (exact != null) {
            return exact;
        }
        var lo = futures.floorEntry(tenorYears);
        var hi = futures.ceilingEntry(tenorYears);
        if (lo == null || hi == null) {
            throw new IllegalArgumentException("tenor " + tenorYears
                    + " outside pillars [" + futures.firstKey() + ", " + futures.lastKey() + "]");
        }
        double w = (tenorYears - lo.getKey()) / (hi.getKey() - lo.getKey());
        return lo.getValue() + w * (hi.getValue() - lo.getValue());
    }

    /**
     * Annualized roll yield earned by a LONG rolling from {@code nearYears}
     * to {@code farYears}: positive in backwardation.
     */
    public double annualizedRollYield(double nearYears, double farYears) {
        if (!(farYears > nearYears)) {
            throw new IllegalArgumentException("farYears must exceed nearYears");
        }
        return Math.log(price(nearYears) / price(farYears)) / (farYears - nearYears);
    }

    /**
     * Market-implied storage-minus-convenience {@code u - y} (cc) at the
     * tenor, from {@code F = S * exp((r + u - y) t)}.
     *
     * @param rate the cc risk-free rate to the tenor
     */
    public double impliedCarry(double tenorYears, double rate) {
        if (!Double.isFinite(rate)) {
            throw new IllegalArgumentException("rate must be finite");
        }
        return Math.log(price(tenorYears) / spot) / tenorYears - rate;
    }

    /** Strictly upward at every adjacent pillar pair (deferred above near). */
    public boolean isContango() {
        double prev = spot;
        for (double px : futures.values()) {
            if (px <= prev) {
                return false;
            }
            prev = px;
        }
        return true;
    }

    /** Strictly downward at every adjacent pillar pair. */
    public boolean isBackwardation() {
        double prev = spot;
        for (double px : futures.values()) {
            if (px >= prev) {
                return false;
            }
            prev = px;
        }
        return true;
    }

    public double spot() {
        return spot;
    }
}
