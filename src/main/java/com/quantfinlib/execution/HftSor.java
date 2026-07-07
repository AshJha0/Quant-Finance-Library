package com.quantfinlib.execution;

import com.quantfinlib.orderbook.Side;

/**
 * Hot-lane smart order router: the zero-allocation sibling of
 * {@link SmartOrderRouter}, for when the routing decision sits on the
 * tick-to-order path. Venue state lives in parallel primitive arrays
 * (updated in place from your per-venue feeds — plug in
 * {@code marketdata.Nbbo} venue quotes directly); a route decision is a
 * greedy sweep by all-in price with no sorting, no lists, no boxing.
 *
 * <p>Fees are expressed in <em>ticks</em> (venue taker fee converted at the
 * instrument's tick size, once, at configuration time), so the comparison
 * stays in integer-friendly arithmetic. Negative fee ticks model rebates.
 * With the handful of venues real equities routing faces, the O(V²)
 * selection sweep is faster than any heap — and allocates nothing.</p>
 *
 * <p>Single-writer; reuse one router per routing thread.</p>
 */
public final class HftSor {

    private static final int NO_BID = Integer.MIN_VALUE;
    private static final int NO_ASK = Integer.MAX_VALUE;

    private final int venues;
    private final int[] bidTick;
    private final long[] bidSize;
    private final int[] askTick;
    private final long[] askSize;
    private final double[] feeTicks;

    private long routeCount;

    public HftSor(int venueCount) {
        if (venueCount < 1) {
            throw new IllegalArgumentException("venueCount must be >= 1");
        }
        this.venues = venueCount;
        this.bidTick = new int[venueCount];
        this.bidSize = new long[venueCount];
        this.askTick = new int[venueCount];
        this.askSize = new long[venueCount];
        this.feeTicks = new double[venueCount];
        java.util.Arrays.fill(bidTick, NO_BID);
        java.util.Arrays.fill(askTick, NO_ASK);
    }

    /** Per-venue taker fee in ticks (negative = rebate). Configure once. */
    public void fee(int venue, double takerFeeTicks) {
        feeTicks[venue] = takerFeeTicks;
    }

    /** One venue's displayed top of book (zero size = side unavailable). */
    public void venueQuote(int venue, int bid, long bidSz, int ask, long askSz) {
        bidTick[venue] = bidSz > 0 ? bid : NO_BID;
        bidSize[venue] = bidSz > 0 ? bidSz : 0;
        askTick[venue] = askSz > 0 ? ask : NO_ASK;
        askSize[venue] = askSz > 0 ? askSz : 0;
    }

    /**
     * Removes a venue from routing (feed loss / venue halt) — the symmetric
     * call to {@code Nbbo.onVenueDown}, so a dead venue's stale quote can
     * never keep receiving child orders.
     */
    public void venueDown(int venue) {
        venueQuote(venue, NO_BID, 0, NO_ASK, 0);
    }

    /**
     * Routes a marketable order across venues by best all-in price
     * (quote ± fee), splitting at displayed size. Child quantities are
     * written into {@code outQty[venue]}: the array must be at least
     * {@code venueCount()} long and indices {@code [0, venueCount)} are
     * fully overwritten (entries beyond that are untouched). The return
     * value is the total routed quantity — anything short of
     * {@code quantity} found no displayed liquidity. Zero allocation.
     *
     * @param limitTick worst acceptable raw price in ticks (before fees);
     *                  pass {@code Integer.MAX_VALUE} (buy) /
     *                  {@code Integer.MIN_VALUE} (sell) for pure market
     */
    public long route(Side side, long quantity, int limitTick, long[] outQty) {
        java.util.Arrays.fill(outQty, 0, venues, 0);
        routeCount++;
        long remaining = quantity;
        boolean buy = side == Side.BUY;
        while (remaining > 0) {
            int best = -1;
            double bestAllIn = 0;
            for (int v = 0; v < venues; v++) {
                if (outQty[v] != 0) {
                    continue;                // already swept this venue
                }
                int px = buy ? askTick[v] : bidTick[v];
                long sz = buy ? askSize[v] : bidSize[v];
                if (sz <= 0 || px == (buy ? NO_ASK : NO_BID)
                        || (buy ? px > limitTick : px < limitTick)) {
                    continue;
                }
                double allIn = buy ? px + feeTicks[v] : px - feeTicks[v];
                if (best == -1 || (buy ? allIn < bestAllIn : allIn > bestAllIn)) {
                    best = v;
                    bestAllIn = allIn;
                }
            }
            if (best == -1) {
                break;
            }
            // A selected venue always takes > 0, so outQty doubles as the
            // "already used" marker — no separate scratch array needed.
            long take = Math.min(remaining, buy ? askSize[best] : bidSize[best]);
            outQty[best] = take;
            remaining -= take;
        }
        return quantity - remaining;
    }

    public int venueCount() {
        return venues;
    }

    public long routeCount() {
        return routeCount;
    }
}
