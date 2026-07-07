package com.quantfinlib.marketdata;

/**
 * National Best Bid and Offer: aggregates per-venue top-of-book quotes for
 * one symbol into the consolidated best bid/ask, the size available at those
 * prices, and a bitmask of which venues are at the inside — the three inputs
 * a smart order router actually consumes. The equities counterpart of
 * {@code fx.AggregatedBook}, in integer ticks.
 *
 * <p>Zero allocation per update; single-writer (one consolidated-feed
 * thread). Recomputation is a linear scan over venues — with the ≤ 64 venues
 * of a real consolidated tape, a branch-predictable scan over four parallel
 * arrays beats any incremental structure.</p>
 *
 * <p>An optional {@link Listener} fires only when the NBBO actually changes
 * (price or size at the inside), so downstream logic is naturally conflated
 * to inside-quote updates.</p>
 */
public final class Nbbo {

    /** Fired after the NBBO (price or inside size) changes. Primitive-only. */
    @FunctionalInterface
    public interface Listener {
        void onNbbo(int bidTick, long bidSize, int askTick, long askSize,
                    long timestampNanos);
    }

    public static final int NO_BID = Integer.MIN_VALUE;
    public static final int NO_ASK = Integer.MAX_VALUE;

    private final int venues;
    private final int[] bidTick;
    private final long[] bidSize;
    private final int[] askTick;
    private final long[] askSize;

    private int nbb = NO_BID;
    private long nbbSize;
    private long nbbVenueBits;
    private int nbo = NO_ASK;
    private long nboSize;
    private long nboVenueBits;

    private Listener listener;
    private long updateCount;
    private long changeCount;

    /** @param venueCount number of venues (≤ 64, so venue sets fit one bitmask) */
    public Nbbo(int venueCount) {
        if (venueCount < 1 || venueCount > 64) {
            throw new IllegalArgumentException("venueCount must be 1..64");
        }
        this.venues = venueCount;
        this.bidTick = new int[venueCount];
        this.bidSize = new long[venueCount];
        this.askTick = new int[venueCount];
        this.askSize = new long[venueCount];
        java.util.Arrays.fill(bidTick, NO_BID);
        java.util.Arrays.fill(askTick, NO_ASK);
    }

    /** Installs the (single) inside-change callback. */
    public void listener(Listener l) {
        this.listener = l;
    }

    /**
     * One venue's new top of book. Pass {@link #NO_BID}/{@link #NO_ASK} (or
     * zero sizes) for an empty side; use {@link #onVenueDown} when the venue
     * drops entirely. Returns true when the NBBO changed.
     */
    public boolean onVenueQuote(int venue, int bid, long bidSz, int ask, long askSz,
                                long timestampNanos) {
        int newBid = bidSz > 0 ? bid : NO_BID;
        int newAsk = askSz > 0 ? ask : NO_ASK;
        bidTick[venue] = newBid;
        bidSize[venue] = bidSz > 0 ? bidSz : 0;
        askTick[venue] = newAsk;
        askSize[venue] = askSz > 0 ? askSz : 0;
        updateCount++;
        // Fast path: a venue that was not at either inside and stays strictly
        // off it cannot move the NBBO — most consolidated-tape updates are
        // exactly this off-inside flicker, so skip the scan for them.
        // Provably no change iff: not previously at either inside (bit clear
        // covers "its old quote WAS the inside"), and the new bid stays
        // strictly below the inside (or absent), and the new ask strictly
        // above (or absent). Any comparison against an absent inside
        // (NO_BID/NO_ASK sentinel) is false, correctly forcing the scan.
        long bit = 1L << venue;
        if ((nbbVenueBits & bit) == 0 && (nboVenueBits & bit) == 0
                && (newBid == NO_BID || newBid < nbb)
                && (newAsk == NO_ASK || newAsk > nbo)) {
            return false;
        }
        return recompute(timestampNanos);
    }

    /** Removes a venue's quotes (feed loss / venue halt). Returns true on NBBO change. */
    public boolean onVenueDown(int venue, long timestampNanos) {
        // One state-clearing path: an empty quote and a downed venue must
        // never drift apart.
        return onVenueQuote(venue, NO_BID, 0, NO_ASK, 0, timestampNanos);
    }

    private boolean recompute(long ts) {
        int bb = NO_BID;
        long bbSz = 0;
        long bbBits = 0;
        int bo = NO_ASK;
        long boSz = 0;
        long boBits = 0;
        for (int v = 0; v < venues; v++) {
            int b = bidTick[v];
            if (b != NO_BID) {
                if (b > bb) {
                    bb = b;
                    bbSz = bidSize[v];
                    bbBits = 1L << v;
                } else if (b == bb) {
                    bbSz += bidSize[v];
                    bbBits |= 1L << v;
                }
            }
            int a = askTick[v];
            if (a != NO_ASK) {
                if (a < bo) {
                    bo = a;
                    boSz = askSize[v];
                    boBits = 1L << v;
                } else if (a == bo) {
                    boSz += askSize[v];
                    boBits |= 1L << v;
                }
            }
        }
        boolean changed = bb != nbb || bbSz != nbbSize || bo != nbo || boSz != nboSize;
        nbb = bb;
        nbbSize = bbSz;
        nbbVenueBits = bbBits;
        nbo = bo;
        nboSize = boSz;
        nboVenueBits = boBits;
        if (changed) {
            changeCount++;
            if (listener != null) {
                listener.onNbbo(bb, bbSz, bo, boSz, ts);
            }
        }
        return changed;
    }

    /** National best bid in ticks; {@link #NO_BID} when no venue bids. */
    public int bidTick() {
        return nbb;
    }

    /** Total displayed size at the national best bid, across venues. */
    public long bidSize() {
        return nbbSize;
    }

    /** National best offer in ticks; {@link #NO_ASK} when no venue offers. */
    public int askTick() {
        return nbo;
    }

    /** Total displayed size at the national best offer, across venues. */
    public long askSize() {
        return nboSize;
    }

    /** Bitmask of venues quoting at the national best bid (bit v = venue v). */
    public long bidVenues() {
        return nbbVenueBits;
    }

    /** Bitmask of venues quoting at the national best offer. */
    public long askVenues() {
        return nboVenueBits;
    }

    /** Crossed market flag (NBB above NBO — locked/crossed tape condition). */
    public boolean crossed() {
        return nbb != NO_BID && nbo != NO_ASK && nbb > nbo;
    }

    /** Locked market flag (NBB equal to NBO). */
    public boolean locked() {
        return nbb != NO_BID && nbb == nbo;
    }

    /** Mid in tick units, NaN when either side is absent. */
    public double midTick() {
        // Sum in long: two ticks near 2^30 (a ~$107k symbol in 0.0001 ticks)
        // would wrap a 32-bit sum negative.
        return nbb == NO_BID || nbo == NO_ASK ? Double.NaN : ((long) nbb + nbo) / 2.0;
    }

    public long updateCount() {
        return updateCount;
    }

    /** Updates that moved the inside (price or size) — the conflation ratio. */
    public long changeCount() {
        return changeCount;
    }
}
