package com.quantfinlib.microstructure;

import java.util.ArrayList;
import java.util.List;

/**
 * Price-banded tick sizes — the MiFID II / ESMA RTS 11 regime where the
 * minimum price increment depends on the instrument's price (and liquidity
 * band), rather than being one flat number. US equities are the degenerate
 * single-band case ($0.01 above $1).
 *
 * <p>Two ways to build one:</p>
 * <ul>
 *   <li>{@link #builder()} — explicit bands loaded from the venue's
 *       published table (the production path: exchanges publish these);</li>
 *   <li>{@link #esmaStyle(int)} — a generated 1-2-5 progression per price
 *       decade in the shape of the ESMA table, parameterized by liquidity
 *       band (0 = most liquid ⇒ finest ticks). Faithful in structure and
 *       magnitude, but not a certified copy of RTS 11 Annex — load the real
 *       table for compliance work.</li>
 * </ul>
 *
 * <p>Lookups are a binary search over primitive arrays — allocation-free
 * and cheap enough for a quoter to call per quote update. Rounding helpers
 * come in directional flavors because order prices must round <em>toward
 * passivity</em> (buys down, sells up) to stay exchange-valid.</p>
 */
public final class TickSizeSchedule {

    private final double[] bandFloors; // ascending; band i applies to [floor[i], floor[i+1])
    private final double[] ticks;

    private TickSizeSchedule(double[] bandFloors, double[] ticks) {
        this.bandFloors = bandFloors;
        this.ticks = ticks;
    }

    /** Single flat tick for every price (US-equity style). */
    public static TickSizeSchedule flat(double tick) {
        if (tick <= 0) {
            throw new IllegalArgumentException("tick must be > 0: " + tick);
        }
        return new TickSizeSchedule(new double[]{0}, new double[]{tick});
    }

    /**
     * ESMA-style generated schedule: within each price decade the bands
     * [1,2), [2,5), [5,10) carry ticks in a 1-2-5 progression, all scaled up
     * by one decade per {@code liquidityBand} step (0 = most liquid). Covers
     * prices from 0.0001 to 100,000.
     */
    public static TickSizeSchedule esmaStyle(int liquidityBand) {
        if (liquidityBand < 0 || liquidityBand > 5) {
            throw new IllegalArgumentException("liquidityBand must be 0..5");
        }
        Builder b = builder();
        // Price decades 10^-4 .. 10^4, split 1-2-5 like the ESMA table rows.
        for (int decade = -4; decade <= 4; decade++) {
            double base = Math.pow(10, decade);
            // Tick for the [1,2) sub-band of this decade, shifted by liquidity:
            // most-liquid band ticks 4 decades below the price decade.
            double tick = Math.pow(10, decade - 4 + liquidityBand);
            b.addBand(base, tick);
            b.addBand(2 * base, 2 * tick);
            b.addBand(5 * base, 5 * tick);
        }
        return b.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Accumulates (floor price, tick) bands; floors may arrive in any order. */
    public static final class Builder {
        private final List<double[]> bands = new ArrayList<>();

        /** The tick that applies from {@code floorPrice} up to the next band's floor. */
        public Builder addBand(double floorPrice, double tick) {
            if (floorPrice < 0 || tick <= 0) {
                throw new IllegalArgumentException("floor must be >= 0 and tick > 0");
            }
            bands.add(new double[]{floorPrice, tick});
            return this;
        }

        public TickSizeSchedule build() {
            if (bands.isEmpty()) {
                throw new IllegalStateException("at least one band required");
            }
            bands.sort((a, b) -> Double.compare(a[0], b[0]));
            double[] floors = new double[bands.size()];
            double[] ticks = new double[bands.size()];
            for (int i = 0; i < bands.size(); i++) {
                floors[i] = bands.get(i)[0];
                ticks[i] = bands.get(i)[1];
                if (i > 0 && floors[i] == floors[i - 1]) {
                    throw new IllegalArgumentException("duplicate band floor " + floors[i]);
                }
            }
            return new TickSizeSchedule(floors, ticks);
        }
    }

    // ------------------------------------------------------------------
    // Lookups (allocation-free)
    // ------------------------------------------------------------------

    /**
     * Like {@link #tickFor}, but prices below the first band floor take the
     * first band's tick instead of throwing — for engines (backtesters,
     * quoters) that must stay total over any positive price the market or a
     * strategy can produce. Order-entry validation should keep using the
     * strict {@link #tickFor}.
     */
    public double tickForClamped(double price) {
        return price < bandFloors[0] ? ticks[0] : tickFor(price);
    }

    /** The minimum increment in force at a price. */
    public double tickFor(double price) {
        if (price < bandFloors[0]) {
            throw new IllegalArgumentException(
                    "price " + price + " below the first band floor " + bandFloors[0]);
        }
        int lo = 0;
        int hi = bandFloors.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (bandFloors[mid] <= price) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return ticks[lo];
    }

    /** Rounds down to the grid — the passive direction for a buy order. */
    public double roundDown(double price) {
        double tick = tickFor(price);
        // Nudge by epsilon so an on-tick price stays put despite FP noise.
        return Math.floor(price / tick + 1e-9) * tick;
    }

    /** Rounds up to the grid — the passive direction for a sell order. */
    public double roundUp(double price) {
        double tick = tickFor(price);
        return Math.ceil(price / tick - 1e-9) * tick;
    }

    /** Rounds to the nearest grid point (marks, reference prices). */
    public double roundNearest(double price) {
        double tick = tickFor(price);
        return Math.round(price / tick) * tick;
    }

    /** Whether a price already sits on the grid (within FP tolerance). */
    public boolean isOnTick(double price) {
        double tick = tickFor(price);
        double ratio = price / tick;
        return Math.abs(ratio - Math.round(ratio)) < 1e-6;
    }

    public int bandCount() {
        return bandFloors.length;
    }
}
