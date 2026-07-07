package com.quantfinlib.microstructure;

/**
 * US-equities trading safeguards, styled after the SEC's Limit Up-Limit Down
 * plan and the market-wide circuit-breaker rule (not certified
 * implementations — the regulatory texts govern):
 *
 * <ul>
 *   <li><b>LULD price bands</b> ({@link #luldBandPct}, {@link Luld}) —
 *       per-symbol bands around a reference price; quoting at a band edge
 *       enters a limit state, and a limit state that persists 15 seconds
 *       becomes a 5-minute trading pause;</li>
 *   <li><b>Market-wide circuit breakers</b> ({@link MarketWide}) — S&amp;P 500
 *       declines of 7% / 13% halt the market for 15 minutes (each at most
 *       once per day, and not after 15:25), and 20% halts for the day.</li>
 * </ul>
 *
 * <p>The {@link Luld} state machine is primitives-only and allocation-free:
 * drive it from the NBBO callback.</p>
 */
public final class CircuitBreakers {

    private CircuitBreakers() {
    }

    // ------------------------------------------------------------------
    // LULD bands
    // ------------------------------------------------------------------

    /**
     * LULD band as a fraction of the reference price.
     *
     * <p><b>Units: dollars, not ticks.</b> The tier thresholds ($3.00, $0.75,
     * $0.15) are dollar amounts from the plan, so a price fed in 0.0001-tick
     * ints (the {@code marketdata} convention) lands every symbol in the
     * wrong tier — convert at the seam ({@code tick * 1e-4}) first.</p>
     *
     * @param referencePrice the LULD reference (5-minute average price), in dollars
     * @param tier1          true for Tier 1 symbols (S&amp;P 500 / Russell 1000 /
     *                       designated ETPs), false for Tier 2
     * @param widened        true in the widened-band windows (the plan doubles
     *                       bands near the open and close for most groups)
     */
    public static double luldBandPct(double referencePrice, boolean tier1, boolean widened) {
        if (referencePrice <= 0 || Double.isNaN(referencePrice)) {
            throw new IllegalArgumentException("referencePrice must be positive");
        }
        double pct;
        if (referencePrice > 3.00) {
            pct = tier1 ? 0.05 : 0.10;
        } else if (referencePrice >= 0.75) {
            pct = 0.20;
        } else {
            // Lesser of 75% or $0.15, expressed as a fraction of reference.
            pct = Math.min(0.75, 0.15 / referencePrice);
        }
        return widened ? pct * 2 : pct;
    }

    /** Lower LULD band price. */
    public static double luldLowerBand(double referencePrice, boolean tier1, boolean widened) {
        return referencePrice * (1 - luldBandPct(referencePrice, tier1, widened));
    }

    /** Upper LULD band price. */
    public static double luldUpperBand(double referencePrice, boolean tier1, boolean widened) {
        return referencePrice * (1 + luldBandPct(referencePrice, tier1, widened));
    }

    /** LULD per-symbol state. */
    public enum LuldState {
        /** Trading normally inside the bands. */
        NORMAL,
        /** NBB pinned at the upper band: sellers must arrive or a pause follows. */
        LIMIT_UP,
        /** NBO pinned at the lower band. */
        LIMIT_DOWN,
        /** In the 5-minute trading pause. */
        PAUSED
    }

    /**
     * The LULD limit-state machine for one symbol: enters a limit state when
     * the inside quote pins a band edge, converts to a 5-minute pause when
     * the limit state persists 15 seconds, and exits the pause after it
     * elapses. Single-writer, zero allocation.
     */
    public static final class Luld {

        private static final long LIMIT_STATE_NANOS = 15_000_000_000L;
        private static final long PAUSE_NANOS = 300_000_000_000L;

        private final boolean tier1;
        private double lowerBand = Double.NaN;
        private double upperBand = Double.NaN;
        private LuldState state = LuldState.NORMAL;
        private long stateSince;
        private long pauseCount;

        public Luld(boolean tier1) {
            this.tier1 = tier1;
        }

        /** New reference price (the plan recomputes it as a 5-minute average). */
        public void reference(double referencePrice, boolean widened) {
            this.lowerBand = luldLowerBand(referencePrice, tier1, widened);
            this.upperBand = luldUpperBand(referencePrice, tier1, widened);
        }

        /**
         * NBBO update; returns the state after applying it. Prices are in
         * the same units as the reference.
         */
        public LuldState onNbbo(double nbb, double nbo, long timestampNanos) {
            if (state(timestampNanos) == LuldState.PAUSED) {
                return LuldState.PAUSED;
            }
            if (Double.isNaN(lowerBand)) {
                return state;                       // no reference yet
            }
            boolean limitUp = !Double.isNaN(nbb) && nbb >= upperBand;
            boolean limitDown = !Double.isNaN(nbo) && nbo <= lowerBand;
            LuldState quoteState = limitUp ? LuldState.LIMIT_UP
                    : limitDown ? LuldState.LIMIT_DOWN : LuldState.NORMAL;
            if (quoteState == LuldState.NORMAL) {
                state = LuldState.NORMAL;
                stateSince = timestampNanos;
                return state;
            }
            if (state != quoteState) {
                state = quoteState;                 // entering the limit state
                stateSince = timestampNanos;
                return state;
            }
            if (timestampNanos - stateSince >= LIMIT_STATE_NANOS) {
                state = LuldState.PAUSED;           // 15 s at the band: pause
                stateSince = timestampNanos;
                pauseCount++;
            }
            return state;
        }

        /**
         * Current state as of {@code nowNanos}, applying pause expiry —
         * paused symbols are exactly the ones that stop quoting, so expiry
         * must be pollable rather than waiting for the next quote.
         */
        public LuldState state(long nowNanos) {
            if (state == LuldState.PAUSED && nowNanos - stateSince >= PAUSE_NANOS) {
                state = LuldState.NORMAL;
                stateSince = nowNanos;
            }
            return state;
        }

        /**
         * Last observed state WITHOUT pause expiry; prefer
         * {@link #state(long)} when a paused symbol may be quote-silent.
         */
        public LuldState state() {
            return state;
        }

        public double lowerBand() {
            return lowerBand;
        }

        public double upperBand() {
            return upperBand;
        }

        public long pauseCount() {
            return pauseCount;
        }
    }

    // ------------------------------------------------------------------
    // Market-wide circuit breakers
    // ------------------------------------------------------------------

    /** MWCB halt decision. */
    public enum Halt {
        NONE,
        /** Level 1 (7%) or Level 2 (13%): 15-minute market-wide halt. */
        HALT_15_MIN,
        /** Level 3 (20%): trading halts for the remainder of the day. */
        HALT_REST_OF_DAY
    }

    /**
     * Market-wide circuit-breaker day state: feed it the S&amp;P 500 decline
     * from the prior close and the time of day; it applies the once-per-day
     * and not-after-15:25 rules for Levels 1 and 2. Reset it each session.
     */
    public static final class MarketWide {

        public static final double LEVEL_1 = 0.07;
        public static final double LEVEL_2 = 0.13;
        public static final double LEVEL_3 = 0.20;
        private static final int CUTOFF_MINUTES = 15 * 60 + 25;   // 15:25

        private boolean level1Used;
        private boolean level2Used;
        private boolean level3Used;

        /**
         * @param declineFromPriorClose e.g. 0.08 = the index is down 8%
         * @param minutesSinceMidnight  local exchange time in MINUTES,
         *                              e.g. 14:30 = 870 (validated to 0..1440
         *                              so a seconds/nanos unit mistake fails
         *                              loudly instead of suppressing halts)
         */
        public Halt onDecline(double declineFromPriorClose, int minutesSinceMidnight) {
            if (minutesSinceMidnight < 0 || minutesSinceMidnight > 24 * 60) {
                throw new IllegalArgumentException(
                        "minutesSinceMidnight out of range (wrong time unit?): "
                                + minutesSinceMidnight);
            }
            if (level3Used) {
                return Halt.NONE;                   // the day is over; nothing re-fires
            }
            if (declineFromPriorClose >= LEVEL_3) {
                level3Used = true;
                level2Used = true;                  // deeper levels consume shallower ones
                level1Used = true;
                return Halt.HALT_REST_OF_DAY;
            }
            boolean beforeCutoff = minutesSinceMidnight < CUTOFF_MINUTES;
            if (declineFromPriorClose >= LEVEL_2 && !level2Used && beforeCutoff) {
                level2Used = true;
                level1Used = true;                  // a deeper level implies the shallower
                return Halt.HALT_15_MIN;
            }
            if (declineFromPriorClose >= LEVEL_1 && !level1Used && beforeCutoff) {
                level1Used = true;
                return Halt.HALT_15_MIN;
            }
            return Halt.NONE;
        }

        public boolean level1Used() {
            return level1Used;
        }

        public boolean level2Used() {
            return level2Used;
        }

        public boolean level3Used() {
            return level3Used;
        }
    }
}
