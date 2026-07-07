package com.quantfinlib.microstructure;

import com.quantfinlib.persist.Checkpoint;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

/**
 * Streaming cross-asset lead-lag estimation: does instrument A's return
 * <em>now</em> predict instrument B's return a few intervals from now?
 * The classic pairs — EURUSD leads EURJPY, index futures lead the cash
 * basket, the liquid large-cap leads its sector peers — are exactly this
 * structure, and it is the basis of cross hedging and cross pricing.
 *
 * <p>Feed one {@link #onSample} per fixed sampling interval with the two
 * instruments' returns over that interval (mid-to-mid log or simple
 * returns; the caller owns the sampling clock, e.g. 100&nbsp;ms for FX
 * majors, 1&nbsp;s for equities). The estimator keeps a small ring of the
 * leader's recent returns and, for each candidate lag
 * {@code k = 0..maxLag}, a time-decayed correlation between the leader's
 * return {@code k} intervals ago and the follower's return now.
 * O(maxLag) work per sample, zero allocation, single writer.</p>
 *
 * <p>Reading the output: {@link #bestLag} is the {@code k > 0} with the
 * largest |correlation| — a genuine lead, because the leader's return was
 * observable before the follower's. Compare it against
 * {@link #correlationAtLag correlationAtLag(0)}: if the contemporaneous
 * correlation dominates every lagged one, the pair co-moves but neither
 * side is tradeably ahead. {@link #expectedFollowerReturn} turns the best
 * lag into a prediction via the regression beta at that lag. Like every
 * streaming estimate here, treat a lead that appears and disappears with
 * the decay window as noise; only a persistent best lag with stable sign
 * is structure.</p>
 */
public final class LeadLagEstimator {

    private final int maxLag;
    private final double alpha;

    // Ring of the leader's most recent maxLag+1 returns; head = newest slot.
    private final double[] leaderRing;
    private int head;
    private int ringFill;                  // valid entries since the last ring reset
    private long samples;

    // Per-lag time-decayed moments of (leader[t-k], follower[t]).
    private final double[] meanLead;
    private final double[] meanFollow;
    private final double[] varLead;
    private final double[] varFollow;
    private final double[] covar;

    /**
     * @param maxLag largest lead to test, in sampling intervals (e.g. 10)
     * @param alpha  EWMA weight of the correlation statistics, e.g. 0.01
     *               (≈ a few-hundred-sample memory)
     */
    public LeadLagEstimator(int maxLag, double alpha) {
        if (maxLag < 1 || alpha <= 0 || alpha > 1) {
            throw new IllegalArgumentException("need maxLag >= 1, alpha in (0,1]");
        }
        this.maxLag = maxLag;
        this.alpha = alpha;
        this.leaderRing = new double[maxLag + 1];
        int n = maxLag + 1;
        this.meanLead = new double[n];
        this.meanFollow = new double[n];
        this.varLead = new double[n];
        this.varFollow = new double[n];
        this.covar = new double[n];
    }

    /** maxLag 10 intervals, EWMA memory ~200 samples. */
    public LeadLagEstimator() {
        this(10, 0.01);
    }

    /**
     * One sampling interval: the leader's and follower's returns over the
     * interval that just closed. Non-finite inputs are a gap — the sample
     * is dropped entirely (no moment updates, ring untouched) so a bad
     * print can't poison the correlations. <b>Gap caveat:</b> after a
     * dropped interval the ring holds the last valid samples, so lag
     * {@code k} means "k valid samples ago", which is MORE than k
     * wall-clock intervals across the gap. On a feed that gaps often,
     * read {@link #bestLag} as a lead in valid samples, not wall-clock
     * time.
     */
    public void onSample(double leaderReturn, double followerReturn) {
        if (!Double.isFinite(leaderReturn) || !Double.isFinite(followerReturn)) {
            return;
        }
        head = head + 1 == leaderRing.length ? 0 : head + 1;
        leaderRing[head] = leaderReturn;
        if (ringFill <= maxLag) {
            ringFill++;
        }
        samples++;

        // Gate on the ring's actual fill (not the lifetime sample count):
        // after a restore the ring is empty, and pairing followers with the
        // zeroed slots would dilute the persisted correlations with
        // fabricated (0, follower) observations.
        int lags = Math.min(ringFill - 1, maxLag);
        for (int k = 0; k <= lags; k++) {
            double lead = leaderRing[index(k)];
            meanLead[k] += alpha * (lead - meanLead[k]);
            meanFollow[k] += alpha * (followerReturn - meanFollow[k]);
            double dl = lead - meanLead[k];
            double df = followerReturn - meanFollow[k];
            varLead[k] += alpha * (dl * dl - varLead[k]);
            varFollow[k] += alpha * (df * df - varFollow[k]);
            covar[k] += alpha * (dl * df - covar[k]);
        }
    }

    /**
     * Time-decayed correlation between the leader's return {@code lag}
     * intervals ago and the follower's return now. 0 until enough samples
     * exist at that lag.
     */
    public double correlationAtLag(int lag) {
        double denom = Math.sqrt(varLead[lag] * varFollow[lag]);
        return denom > 0 ? covar[lag] / denom : 0;
    }

    /**
     * The lag {@code k >= 1} with the largest |correlation| — the
     * estimated lead time in sampling intervals. 0 when no lagged
     * correlation has been measured yet (fewer than 2 samples).
     */
    public int bestLag() {
        int best = 0;
        double bestAbs = 0;
        int lags = (int) Math.min(samples - 1, maxLag);
        for (int k = 1; k <= lags; k++) {
            double c = Math.abs(correlationAtLag(k));
            if (c > bestAbs) {
                bestAbs = c;
                best = k;
            }
        }
        return best;
    }

    /** The signed correlation at {@link #bestLag}; 0 when bestLag() is 0. */
    public double bestCorrelation() {
        int k = bestLag();
        return k == 0 ? 0 : correlationAtLag(k);
    }

    /**
     * The regression prediction of the follower's next-interval return
     * from the leader's return at the best lag:
     * {@code beta(k) x leaderReturn[t-k+1]} with
     * {@code beta = cov/varLead}. 0 when no lead has been measured. This
     * is a point estimate for hedging/pricing, subject to the same
     * persistence caveat as {@link #bestLag}.
     */
    public double expectedFollowerReturn() {
        int k = bestLag();
        if (k == 0 || varLead[k] <= 0) {
            return 0;
        }
        // The leader return that is k intervals before the follower's NEXT
        // interval is the one observed k-1 intervals before now.
        return covar[k] / varLead[k] * leaderRing[index(k - 1)];
    }

    public int maxLag() {
        return maxLag;
    }

    public long samples() {
        return samples;
    }

    // ------------------------------------------------------------------
    // Persistence (persist.Checkpoint)
    // ------------------------------------------------------------------

    /** Persists the per-lag correlation moments — see {@code persist.Checkpoint}. */
    public void writeState(DataOutput out) throws IOException {
        out.writeByte(1);
        out.writeLong(samples);
        Checkpoint.writeDoubles(out, meanLead);
        Checkpoint.writeDoubles(out, meanFollow);
        Checkpoint.writeDoubles(out, varLead);
        Checkpoint.writeDoubles(out, varFollow);
        Checkpoint.writeDoubles(out, covar);
    }

    /**
     * Restores the learned correlations; the leader ring resets, so
     * post-restore samples never pair today's follower with yesterday's
     * pre-close leader across the overnight gap — and lag {@code k}
     * resumes updating only once the ring holds k+1 fresh samples, so the
     * restored moments are never diluted by the empty ring either.
     * Throws on a maxLag or version mismatch.
     */
    public void readState(DataInput in) throws IOException {
        Checkpoint.requireVersion(in, 1, "LeadLagEstimator");
        long persisted = in.readLong();
        Checkpoint.readDoublesInto(in, meanLead);
        Checkpoint.readDoublesInto(in, meanFollow);
        Checkpoint.readDoublesInto(in, varLead);
        Checkpoint.readDoublesInto(in, varFollow);
        Checkpoint.readDoublesInto(in, covar);
        samples = persisted;
        Arrays.fill(leaderRing, 0);
        head = 0;
        ringFill = 0;
    }

    /** Ring slot holding the leader's return from {@code k} intervals ago. */
    private int index(int k) {
        int i = head - k;
        return i < 0 ? i + leaderRing.length : i;
    }

}
