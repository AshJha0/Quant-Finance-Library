package com.quantfinlib.fx;

/**
 * Streaming per-LP execution quality: the taker-side answer to last look.
 * FX liquidity is quotes, not firm orders — a provider may hold your order
 * and reject it — so the practical measure of an LP is not its displayed
 * spread but its <em>all-in</em> behavior: how often it rejects, how long
 * it holds, what the market does right after a reject (the flow it declined
 * was the flow that was about to pay you), and the effective spread it
 * actually fills at. Every live FX desk keeps exactly this scorecard; the
 * {@link LpRouter} consumes it to price rejects into the routing decision.
 *
 * <p>All statistics are exponentially weighted per event (configurable α),
 * so the card tracks current LP behavior, not the session average of a
 * provider that changed its engine at lunch. Post-reject markout is
 * measured one horizon after each reject against the mid you feed via
 * {@link #onMid}: positive markout = the market moved the way you were
 * trying to trade = the reject cost you real money.</p>
 *
 * <p>Zero allocation, single writer (the execution/aggregation thread).
 * Pending markouts live in a small ring per LP ({@value #PENDING_RING}
 * slots): reject bursts — which happen precisely when the market runs and
 * markouts are largest — are sampled rather than overwritten, so the stat
 * cannot be biased low for exactly the LPs it must expose. Only a burst
 * deeper than the ring within one horizon overwrites its oldest entry.</p>
 */
public final class LpScorecard {

    /** Pending-markout slots per LP (bursts deeper than this overwrite oldest). */
    public static final int PENDING_RING = 4;

    private final int lpCount;
    private final double alpha;
    private final long markoutHorizonNanos;

    private final long[] attempts;
    private final long[] fills;
    private final long[] rejects;
    private final double[] rejectRate;        // EWMA of {0, 1} per attempt
    private final double[] holdNanosEwma;
    private final double[] effSpreadEwma;     // price units, fills only
    private final double[] markoutEwma;       // price units, rejects only

    // Pending markout ring [lp * PENDING_RING + k]: side 0 = empty, +1 buy, -1 sell.
    private final byte[] pendingSide;
    private final double[] pendingMid;
    private final long[] pendingTs;
    private final byte[] pendingCursor;       // next write slot per LP
    private int pendingCount;                 // fast path: skip onMid scans at 0
    private long maturedMarkouts;

    /**
     * @param lpCount             number of LPs (dense indices)
     * @param alpha               EWMA weight per event, e.g. 0.05
     * @param markoutHorizonNanos how long after a reject the markout is read,
     *                            e.g. 100ms = {@code 100_000_000L}
     */
    public LpScorecard(int lpCount, double alpha, long markoutHorizonNanos) {
        if (lpCount < 1 || alpha <= 0 || alpha > 1 || markoutHorizonNanos <= 0) {
            throw new IllegalArgumentException(
                    "need lpCount >= 1, alpha in (0,1], horizon > 0");
        }
        this.lpCount = lpCount;
        this.alpha = alpha;
        this.markoutHorizonNanos = markoutHorizonNanos;
        this.attempts = new long[lpCount];
        this.fills = new long[lpCount];
        this.rejects = new long[lpCount];
        this.rejectRate = new double[lpCount];
        this.holdNanosEwma = new double[lpCount];
        this.effSpreadEwma = new double[lpCount];
        this.markoutEwma = new double[lpCount];
        this.pendingSide = new byte[lpCount * PENDING_RING];
        this.pendingMid = new double[lpCount * PENDING_RING];
        this.pendingTs = new long[lpCount * PENDING_RING];
        this.pendingCursor = new byte[lpCount];
    }

    /** 5% event weight, 100 ms markout horizon. */
    public LpScorecard(int lpCount) {
        this(lpCount, 0.05, 100_000_000L);
    }

    // ------------------------------------------------------------------
    // Event feed
    // ------------------------------------------------------------------

    /**
     * An accepted fill.
     *
     * @param buy          our direction
     * @param price        the all-in fill price
     * @param midAtRequest composite mid when the order was sent
     * @param holdNanos    time the LP held the order before accepting
     */
    public void onFill(int lp, boolean buy, double price, double midAtRequest,
                       long holdNanos) {
        attempts[lp]++;
        fills[lp]++;
        rejectRate[lp] += alpha * (0 - rejectRate[lp]);
        holdNanosEwma[lp] += alpha * (holdNanos - holdNanosEwma[lp]);
        double eff = buy ? price - midAtRequest : midAtRequest - price;
        if (!Double.isNaN(eff)) {
            // A NaN price/mid must not poison the EWMA permanently.
            effSpreadEwma[lp] += alpha * (eff - effSpreadEwma[lp]);
        }
    }

    /**
     * A last-look reject. The markout clock starts here: feed mids via
     * {@link #onMid} and the move one horizon later is attributed to this
     * reject.
     */
    public void onReject(int lp, boolean buy, double midAtRequest, long timestampNanos,
                         long holdNanos) {
        attempts[lp]++;
        rejects[lp]++;
        rejectRate[lp] += alpha * (1 - rejectRate[lp]);
        holdNanosEwma[lp] += alpha * (holdNanos - holdNanosEwma[lp]);
        if (Double.isNaN(midAtRequest)) {
            // The reject still counts against the rate, but a NaN reference
            // mid can never start a markout: maturing against it would set
            // the EWMA to NaN forever and silently de-route this LP.
            return;
        }
        int slot = lp * PENDING_RING + pendingCursor[lp];
        if (pendingSide[slot] == 0) {
            pendingCount++;                    // fresh slot; overwrite keeps count
        }
        pendingSide[slot] = (byte) (buy ? 1 : -1);
        pendingMid[slot] = midAtRequest;
        pendingTs[slot] = timestampNanos;
        pendingCursor[lp] = (byte) ((pendingCursor[lp] + 1) % PENDING_RING);
    }

    /**
     * Composite mid update: matures every pending reject markout whose
     * horizon has elapsed. NaN mids (one-sided composite, feed gap) are
     * ignored — a non-price must never poison the EWMA, which would
     * otherwise stay NaN forever and disable the router's penalty. The
     * common no-pending case is a single compare.
     */
    public void onMid(double mid, long timestampNanos) {
        if (pendingCount == 0 || Double.isNaN(mid)) {
            return;
        }
        int n = lpCount * PENDING_RING;
        for (int slot = 0; slot < n; slot++) {
            if (pendingSide[slot] != 0
                    && timestampNanos - pendingTs[slot] >= markoutHorizonNanos) {
                int lp = slot / PENDING_RING;
                double move = pendingSide[slot] * (mid - pendingMid[slot]);
                markoutEwma[lp] += alpha * (move - markoutEwma[lp]);
                pendingSide[slot] = 0;
                pendingCount--;
                maturedMarkouts++;
            }
        }
    }

    // ------------------------------------------------------------------
    // The card
    // ------------------------------------------------------------------

    /** EWMA reject probability in [0, 1]; 0 before any events. */
    public double rejectRate(int lp) {
        return rejectRate[lp];
    }

    /** EWMA hold time across fills and rejects, in nanos. */
    public double avgHoldNanos(int lp) {
        return holdNanosEwma[lp];
    }

    /** EWMA effective half-spread paid on fills, in price units. */
    public double effectiveSpread(int lp) {
        return effSpreadEwma[lp];
    }

    /**
     * EWMA post-reject markout in price units — positive means the market
     * moved the way you were trying to trade after the LP declined: the
     * realized cost of that LP's last look.
     */
    public double postRejectMarkout(int lp) {
        return markoutEwma[lp];
    }

    /**
     * Markouts matured across all LPs — the router-degradation canary:
     * zero while rejects accrue means {@link #onMid} is not wired and the
     * routing penalty is silently zero.
     */
    public long maturedMarkouts() {
        return maturedMarkouts;
    }

    /** Invariant: attempts == fills + rejects (kept explicit for future outcomes). */
    public long attempts(int lp) {
        return attempts[lp];
    }

    public long fills(int lp) {
        return fills[lp];
    }

    public long rejects(int lp) {
        return rejects[lp];
    }

    public int lpCount() {
        return lpCount;
    }
}
