package com.quantfinlib.fx;

import com.quantfinlib.persist.Checkpoint;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

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
    private final long[] markoutCount;        // matured markouts per LP (seeding)

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
        this.markoutCount = new long[lpCount];
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
        if (Double.isFinite(eff)) {
            // A NaN/Inf price or mid must not poison the EWMA permanently.
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
        if (!Double.isFinite(midAtRequest)) {
            // The reject still counts against the rate, but a NaN/Inf
            // reference mid can never start a markout: maturing against it
            // would poison the EWMA forever and silently de-route this LP.
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
        if (pendingCount == 0 || !Double.isFinite(mid)) {
            return;
        }
        int n = lpCount * PENDING_RING;
        for (int slot = 0; slot < n; slot++) {
            if (pendingSide[slot] != 0
                    && timestampNanos - pendingTs[slot] >= markoutHorizonNanos) {
                int lp = slot / PENDING_RING;
                double move = pendingSide[slot] * (mid - pendingMid[slot]);
                // Seed from the first matured markout — ramping from 0
                // under-penalized a toxic LP for its first ~1/alpha rejects,
                // exactly during the burst that revealed it (the equities
                // twin, VenueScorecard, seeds the same way).
                markoutEwma[lp] = markoutCount[lp] == 0
                        ? move
                        : markoutEwma[lp] + alpha * (move - markoutEwma[lp]);
                markoutCount[lp]++;
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

    // ------------------------------------------------------------------
    // Persistence (persist.Checkpoint)
    // ------------------------------------------------------------------

    /**
     * Persists the learned LP behavior — reject rates, hold times,
     * effective spreads and post-reject markouts. The pending-markout ring
     * is intraday (a reject awaiting its horizon) and is not persisted.
     * Format version 2 (v1, from before markout seeding, is still read).
     * See {@code persist.Checkpoint}.
     */
    public void writeState(DataOutput out) throws IOException {
        out.writeByte(2);
        Checkpoint.writeLongs(out, attempts);
        Checkpoint.writeLongs(out, fills);
        Checkpoint.writeLongs(out, rejects);
        Checkpoint.writeDoubles(out, rejectRate);
        Checkpoint.writeDoubles(out, holdNanosEwma);
        Checkpoint.writeDoubles(out, effSpreadEwma);
        Checkpoint.writeDoubles(out, markoutEwma);
        Checkpoint.writeLongs(out, markoutCount);
        out.writeLong(maturedMarkouts);
    }

    /**
     * Restores the card; pending markouts reset (restore at session
     * start). Reads both format versions — a v1 checkpoint carries no
     * per-LP markout counts, so a restored nonzero markout EWMA counts as
     * already-seeded (it is). Throws on an LP-count mismatch or an
     * unknown version.
     */
    public void readState(DataInput in) throws IOException {
        int v = in.readByte();
        if (v != 1 && v != 2) {
            throw new IOException("LpScorecard state version " + v
                    + " not supported (this build reads versions 1-2)");
        }
        Checkpoint.readLongsInto(in, attempts);
        Checkpoint.readLongsInto(in, fills);
        Checkpoint.readLongsInto(in, rejects);
        Checkpoint.readDoublesInto(in, rejectRate);
        Checkpoint.readDoublesInto(in, holdNanosEwma);
        Checkpoint.readDoublesInto(in, effSpreadEwma);
        Checkpoint.readDoublesInto(in, markoutEwma);
        if (v >= 2) {
            Checkpoint.readLongsInto(in, markoutCount);
        } else {
            for (int lp = 0; lp < lpCount; lp++) {
                markoutCount[lp] = markoutEwma[lp] != 0 ? 1 : 0;
            }
        }
        maturedMarkouts = in.readLong();
        Arrays.fill(pendingSide, (byte) 0);
        Arrays.fill(pendingCursor, (byte) 0);
        pendingCount = 0;
    }
}
