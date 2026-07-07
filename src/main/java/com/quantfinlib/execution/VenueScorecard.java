package com.quantfinlib.execution;

import com.quantfinlib.persist.Checkpoint;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Streaming per-venue execution quality — the equities counterpart of
 * {@code fx.LpScorecard}: displayed prices tell you where a venue CLAIMS
 * you'll trade; the scorecard tells you what actually happens when you send
 * there. Everything the {@link AdaptiveSor} needs beyond the quote:
 *
 * <ul>
 *   <li><b>Fill rate</b> — EWMA of {1 fill, 0 miss} per marketable child:
 *       the venue's reliability. Quotes fade, systems reject, sessions
 *       hiccup — a venue that fills 80% of what you send is worth less
 *       than its displayed price says;</li>
 *   <li><b>Response latency</b> — EWMA of send→ack/fill time as YOU
 *       measure it, which routinely disagrees with the venue's advertised
 *       number;</li>
 *   <li><b>Hidden liquidity</b> — for dark venues, an EWMA of the shares
 *       each probe actually found. "Unknown" hardens into an estimate the
 *       only honest way: by probing and remembering;</li>
 *   <li><b>Post-fill markout (adverse selection)</b> — what the mid does
 *       one horizon after your fill, signed in your trading direction:
 *       positive = the price kept going your way (a clean fill), negative
 *       = it reverted (you paid the spread to trade against informed or
 *       stale flow — the "fade" venues). Arm it via the extended
 *       {@link #onFill(int, long, boolean, double, long)} and feed mids
 *       via {@link #onMid}; the equities counterpart of
 *       {@code fx.LpScorecard}'s post-REJECT markout. <b>The markout leg
 *       makes the card single-symbol</b>: {@link #onMid} matures every
 *       pending fill against the one mid it is given, and the markout is
 *       in absolute price units — one card per symbol once you use it
 *       (the fill-rate/latency stats alone were symbol-agnostic).</li>
 * </ul>
 *
 * <p>All statistics are exponentially weighted per event so the card tracks
 * current behavior (each EWMA seeds from the prior / first observation, so a
 * venue is never scored below "never tried" for its first fill). Before any
 * data, {@link #fillRate} returns a configurable optimistic prior (a new
 * venue deserves flow until it proves otherwise). Zero allocation per event,
 * single writer (the execution-events thread).</p>
 *
 * <p>This is the <em>streaming</em> venue-quality tool for the routing hot
 * path; {@link VenueBenchmark} is the <em>batch</em> counterpart (fill rate,
 * effective spread and markout over a sample list) for post-trade venue
 * analysis.</p>
 */
public final class VenueScorecard {

    /** Pending fill-markout slots per venue (bursts deeper overwrite oldest). */
    public static final int PENDING_RING = 4;

    private final int venueCount;
    private final double alpha;
    private final double fillRatePrior;
    private final long markoutHorizonNanos;

    private final long[] sent;
    private final long[] filled;
    private final double[] fillRateEwma;
    private final double[] latencyNanosEwma;
    private final double[] hiddenFillEwma;      // dark venues: shares found per probe
    private final long[] probes;

    // Post-fill markout: EWMA per venue + a small pending ring, exactly the
    // fx.LpScorecard mechanism pointed at fills instead of rejects.
    private final double[] markoutEwma;
    private final long[] markoutCount;
    private final byte[] pendingSide;           // 0 empty, +1 buy, -1 sell
    private final double[] pendingMid;
    private final long[] pendingTs;
    private final byte[] pendingCursor;
    private int pendingCount;
    private long maturedFillMarkouts;

    /**
     * @param venueCount          number of venues (dense indices)
     * @param alpha               EWMA weight per event, e.g. 0.05
     * @param fillRatePrior       fill rate assumed before any data, e.g. 0.95
     * @param markoutHorizonNanos how long after a fill the markout is read,
     *                            e.g. 100ms = {@code 100_000_000L}
     */
    public VenueScorecard(int venueCount, double alpha, double fillRatePrior,
                          long markoutHorizonNanos) {
        if (venueCount < 1 || alpha <= 0 || alpha > 1
                || fillRatePrior <= 0 || fillRatePrior > 1 || markoutHorizonNanos <= 0) {
            throw new IllegalArgumentException(
                    "need venueCount >= 1, alpha in (0,1], prior in (0,1], horizon > 0");
        }
        this.venueCount = venueCount;
        this.alpha = alpha;
        this.fillRatePrior = fillRatePrior;
        this.markoutHorizonNanos = markoutHorizonNanos;
        this.sent = new long[venueCount];
        this.filled = new long[venueCount];
        this.fillRateEwma = new double[venueCount];
        this.latencyNanosEwma = new double[venueCount];
        this.hiddenFillEwma = new double[venueCount];
        this.probes = new long[venueCount];
        this.markoutEwma = new double[venueCount];
        this.markoutCount = new long[venueCount];
        this.pendingSide = new byte[venueCount * PENDING_RING];
        this.pendingMid = new double[venueCount * PENDING_RING];
        this.pendingTs = new long[venueCount * PENDING_RING];
        this.pendingCursor = new byte[venueCount];
    }

    /** 100 ms markout horizon. */
    public VenueScorecard(int venueCount, double alpha, double fillRatePrior) {
        this(venueCount, alpha, fillRatePrior, 100_000_000L);
    }

    /** 5% event weight, 0.95 prior, 100 ms markout horizon. */
    public VenueScorecard(int venueCount) {
        this(venueCount, 0.05, 0.95);
    }

    // ------------------------------------------------------------------
    // Event feed
    // ------------------------------------------------------------------

    /** A marketable child filled (fully or partially counts as a fill). */
    public void onFill(int venue, long responseNanos) {
        // EWMAs seed from the prior / first observation — ramping from 0
        // would record a venue's FIRST successful fill as fillRate 0.05 and
        // get it vetoed by the router: success must never score below
        // "never tried".
        seedOnFirstEvent(venue, responseNanos);
        sent[venue]++;
        filled[venue]++;
        fillRateEwma[venue] += alpha * (1 - fillRateEwma[venue]);
        latencyNanosEwma[venue] += alpha * (responseNanos - latencyNanosEwma[venue]);
    }

    /** A marketable child that came back unfilled (faded, rejected, expired). */
    public void onMiss(int venue, long responseNanos) {
        seedOnFirstEvent(venue, responseNanos);
        sent[venue]++;
        fillRateEwma[venue] += alpha * (0 - fillRateEwma[venue]);
        latencyNanosEwma[venue] += alpha * (responseNanos - latencyNanosEwma[venue]);
    }

    private void seedOnFirstEvent(int venue, long responseNanos) {
        if (sent[venue] == 0) {
            fillRateEwma[venue] = fillRatePrior;
            latencyNanosEwma[venue] = responseNanos;
        }
    }

    /**
     * A fill WITH the context that arms its markout: direction, the mid at
     * fill time, and the fill timestamp. Feed mids via {@link #onMid} and
     * the move one horizon later is attributed to this venue. A NaN mid
     * still counts the fill but can never start a markout (maturing
     * against NaN would poison the EWMA and silently distort routing).
     */
    public void onFill(int venue, long responseNanos, boolean buy, double midAtFill,
                       long timestampNanos) {
        onFill(venue, responseNanos);
        if (!Double.isFinite(midAtFill)) {
            return;                        // an Inf sentinel must not arm either
        }
        int slot = venue * PENDING_RING + pendingCursor[venue];
        if (pendingSide[slot] == 0) {
            pendingCount++;                // fresh slot; overwrite keeps count
        }
        pendingSide[slot] = (byte) (buy ? 1 : -1);
        pendingMid[slot] = midAtFill;
        pendingTs[slot] = timestampNanos;
        pendingCursor[venue] = (byte) ((pendingCursor[venue] + 1) % PENDING_RING);
    }

    /**
     * Mid update FOR THE CARD'S ONE SYMBOL (see the class doc): matures
     * every pending fill markout whose horizon has elapsed. Non-finite
     * mids are ignored — one Inf sentinel maturing a slot would seed the
     * EWMA at ±Inf and the next blend would NaN it forever, silently
     * disabling the router's adverse-selection term. The common
     * no-pending case is a single compare.
     */
    public void onMid(double mid, long timestampNanos) {
        if (pendingCount == 0 || !Double.isFinite(mid)) {
            return;
        }
        int n = venueCount * PENDING_RING;
        for (int slot = 0; slot < n; slot++) {
            if (pendingSide[slot] != 0
                    && timestampNanos - pendingTs[slot] >= markoutHorizonNanos) {
                int venue = slot / PENDING_RING;
                double move = pendingSide[slot] * (mid - pendingMid[slot]);
                // Seed from the first matured markout — ramping from 0
                // would read a venue's first adverse fills at ~5% strength.
                markoutEwma[venue] = markoutCount[venue] == 0
                        ? move
                        : markoutEwma[venue] + alpha * (move - markoutEwma[venue]);
                markoutCount[venue]++;
                pendingSide[slot] = 0;
                pendingCount--;
                maturedFillMarkouts++;
            }
        }
    }

    /**
     * A dark probe's outcome: how many shares it actually found (0 is a
     * real observation — an empty pool teaches as much as a full one).
     * The estimate seeds from the first probe rather than ramping from 0,
     * so one good probe doesn't collapse subsequent probe sizes.
     */
    public void onDarkProbe(int venue, long sharesFilled) {
        hiddenFillEwma[venue] = probes[venue] == 0
                ? sharesFilled
                : hiddenFillEwma[venue] + alpha * (sharesFilled - hiddenFillEwma[venue]);
        probes[venue]++;
    }

    // ------------------------------------------------------------------
    // The card
    // ------------------------------------------------------------------

    /** EWMA fill probability; the optimistic prior before any data. */
    public double fillRate(int venue) {
        return sent[venue] == 0 ? fillRatePrior : fillRateEwma[venue];
    }

    /** The before-any-data prior (also what unregistered venues score as). */
    public double fillRatePrior() {
        return fillRatePrior;
    }

    /** EWMA measured response latency in nanos (0 before any data). */
    public double measuredLatencyNanos(int venue) {
        return latencyNanosEwma[venue];
    }

    /** EWMA shares found per dark probe (0 before any probe). */
    public double expectedHiddenShares(int venue) {
        return hiddenFillEwma[venue];
    }

    /**
     * EWMA post-fill markout in price units — positive means the mid kept
     * moving your way after fills at this venue; negative means it
     * reverted: you crossed the spread into informed or stale flow, the
     * per-share cost {@code AdaptiveSor} prices as adverse selection.
     * 0 before any matured markout.
     */
    public double postFillMarkout(int venue) {
        return markoutEwma[venue];
    }

    /**
     * Fill markouts matured across all venues — the wiring canary: zero
     * while fills accrue means {@link #onMid} is not being fed and the
     * router's adverse-selection term is silently disabled.
     */
    public long maturedFillMarkouts() {
        return maturedFillMarkouts;
    }

    public long sent(int venue) {
        return sent[venue];
    }

    public long filled(int venue) {
        return filled[venue];
    }

    public long probes(int venue) {
        return probes[venue];
    }

    public int venueCount() {
        return venueCount;
    }

    // ------------------------------------------------------------------
    // Persistence (persist.Checkpoint)
    // ------------------------------------------------------------------

    /**
     * Persists the learned venue quality — fill rates, measured latencies,
     * dark-probe estimates and fill markouts are exactly what a router
     * should not have to relearn every morning. Format version 2 (version
     * 1, from before the markout existed, is still readable). See
     * {@code persist.Checkpoint}.
     */
    public void writeState(DataOutput out) throws IOException {
        out.writeByte(2);
        Checkpoint.writeLongs(out, sent);
        Checkpoint.writeLongs(out, filled);
        Checkpoint.writeLongs(out, probes);
        Checkpoint.writeDoubles(out, fillRateEwma);
        Checkpoint.writeDoubles(out, latencyNanosEwma);
        Checkpoint.writeDoubles(out, hiddenFillEwma);
        Checkpoint.writeDoubles(out, markoutEwma);
        Checkpoint.writeLongs(out, markoutCount);
        out.writeLong(maturedFillMarkouts);
    }

    /**
     * Restores the card; pending fill markouts (intraday) reset. Reads
     * both format versions: a v1 checkpoint restores everything it has and
     * leaves the markout state cold. Throws on a venue-count mismatch or
     * an unknown version.
     */
    public void readState(DataInput in) throws IOException {
        int v = in.readByte();
        if (v != 1 && v != 2) {
            throw new IOException("VenueScorecard state version " + v
                    + " not supported (this build reads versions 1-2)");
        }
        Checkpoint.readLongsInto(in, sent);
        Checkpoint.readLongsInto(in, filled);
        Checkpoint.readLongsInto(in, probes);
        Checkpoint.readDoublesInto(in, fillRateEwma);
        Checkpoint.readDoublesInto(in, latencyNanosEwma);
        Checkpoint.readDoublesInto(in, hiddenFillEwma);
        if (v >= 2) {
            Checkpoint.readDoublesInto(in, markoutEwma);
            Checkpoint.readLongsInto(in, markoutCount);
            maturedFillMarkouts = in.readLong();
        } else {
            java.util.Arrays.fill(markoutEwma, 0);
            java.util.Arrays.fill(markoutCount, 0);
            maturedFillMarkouts = 0;
        }
        java.util.Arrays.fill(pendingSide, (byte) 0);
        java.util.Arrays.fill(pendingCursor, (byte) 0);
        pendingCount = 0;
    }
}
