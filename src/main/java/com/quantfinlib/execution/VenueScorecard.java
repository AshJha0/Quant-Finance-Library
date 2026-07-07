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
 *       only honest way: by probing and remembering.</li>
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

    private final int venueCount;
    private final double alpha;
    private final double fillRatePrior;

    private final long[] sent;
    private final long[] filled;
    private final double[] fillRateEwma;
    private final double[] latencyNanosEwma;
    private final double[] hiddenFillEwma;      // dark venues: shares found per probe
    private final long[] probes;

    /**
     * @param venueCount    number of venues (dense indices)
     * @param alpha         EWMA weight per event, e.g. 0.05
     * @param fillRatePrior fill rate assumed before any data, e.g. 0.95
     */
    public VenueScorecard(int venueCount, double alpha, double fillRatePrior) {
        if (venueCount < 1 || alpha <= 0 || alpha > 1
                || fillRatePrior <= 0 || fillRatePrior > 1) {
            throw new IllegalArgumentException(
                    "need venueCount >= 1, alpha in (0,1], prior in (0,1]");
        }
        this.venueCount = venueCount;
        this.alpha = alpha;
        this.fillRatePrior = fillRatePrior;
        this.sent = new long[venueCount];
        this.filled = new long[venueCount];
        this.fillRateEwma = new double[venueCount];
        this.latencyNanosEwma = new double[venueCount];
        this.hiddenFillEwma = new double[venueCount];
        this.probes = new long[venueCount];
    }

    /** 5% event weight, 0.95 prior. */
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
     * Persists the learned venue quality — fill rates, measured latencies
     * and dark-probe estimates are exactly what a router should not have
     * to relearn every morning. See {@code persist.Checkpoint}.
     */
    public void writeState(DataOutput out) throws IOException {
        out.writeByte(1);
        Checkpoint.writeLongs(out, sent);
        Checkpoint.writeLongs(out, filled);
        Checkpoint.writeLongs(out, probes);
        Checkpoint.writeDoubles(out, fillRateEwma);
        Checkpoint.writeDoubles(out, latencyNanosEwma);
        Checkpoint.writeDoubles(out, hiddenFillEwma);
    }

    /** Restores the card. Throws on a venue-count or version mismatch. */
    public void readState(DataInput in) throws IOException {
        Checkpoint.requireVersion(in, 1, "VenueScorecard");
        Checkpoint.readLongsInto(in, sent);
        Checkpoint.readLongsInto(in, filled);
        Checkpoint.readLongsInto(in, probes);
        Checkpoint.readDoublesInto(in, fillRateEwma);
        Checkpoint.readDoublesInto(in, latencyNanosEwma);
        Checkpoint.readDoublesInto(in, hiddenFillEwma);
    }
}
