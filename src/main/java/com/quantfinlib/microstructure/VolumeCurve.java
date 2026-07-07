package com.quantfinlib.microstructure;

import com.quantfinlib.persist.Checkpoint;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

/**
 * Dynamic intraday volume prediction — the model that makes a VWAP schedule
 * live instead of historical. Two parts:
 *
 * <ol>
 *   <li><b>The learned profile</b> — per-bucket volume EWMA across days
 *       (feed each session via {@link #onVolume} and close it with
 *       {@link #rollDay}), giving the classic U-shaped expected curve
 *       without any external data;</li>
 *   <li><b>The intraday rescale</b> — today rarely trades the average day's
 *       volume. The projection scales the remaining curve by today's
 *       realized-vs-expected ratio, shrunk toward 1 early in the day when
 *       the ratio is mostly noise: {@code scale = 1 + w·(ratio − 1)} with
 *       {@code w} = the fraction of the expected day already elapsed. A 2×
 *       morning turns into a confident 2× afternoon only as evidence
 *       accumulates.</li>
 * </ol>
 *
 * <p>{@link #expectedFractionElapsed} is exactly the
 * {@code BenchmarkExecutor.MarketState.expectedVolumeFractionElapsed} input:
 * VWAP tracks this curve, so plugging the two together upgrades VWAP from
 * "yesterday's shape" to "today's shape, updated live." The static
 * historical-profile counterpart is {@code ml.IntradayLiquidityForecaster};
 * this class is its dynamic sibling. Cross-asset (volumes are just sums),
 * zero allocation per event, single writer.</p>
 */
public final class VolumeCurve {

    private final int buckets;
    private final double alpha;

    private final double[] profileEwma;    // learned per-bucket volume
    private final double[] cumProfile;     // prefix sums of profileEwma (O(1) lookups)
    private final double[] today;          // today's realized per-bucket
    private double todayTotal;             // running sum of today (O(1) realized)
    private double profileTotal;
    private int daysLearned;

    /**
     * @param buckets buckets per session (e.g. 78 five-minute buckets for a
     *                6.5h equity day; 288 for a 24h FX day)
     * @param alpha   day-over-day EWMA weight, e.g. 0.1
     */
    public VolumeCurve(int buckets, double alpha) {
        if (buckets < 1 || alpha <= 0 || alpha > 1) {
            throw new IllegalArgumentException("need buckets >= 1, alpha in (0,1]");
        }
        this.buckets = buckets;
        this.alpha = alpha;
        this.profileEwma = new double[buckets];
        this.cumProfile = new double[buckets];
        this.today = new double[buckets];
    }

    /** 78 five-minute equity buckets, 10% day weight. */
    public VolumeCurve() {
        this(78, 0.1);
    }

    /** Seeds the profile from a known shape (any positive scale) — optional. */
    public VolumeCurve seedProfile(double[] volumesPerBucket) {
        if (volumesPerBucket.length != buckets) {
            throw new IllegalArgumentException("profile length must equal bucket count");
        }
        for (int b = 0; b < buckets; b++) {
            if (volumesPerBucket[b] < 0) {
                throw new IllegalArgumentException("negative volume in profile");
            }
            profileEwma[b] = volumesPerBucket[b];
        }
        rebuildPrefixSums();
        daysLearned = 1;
        return this;
    }

    /** Prefix sums over the learned profile, rebuilt whenever it changes. */
    private void rebuildPrefixSums() {
        double cum = 0;
        for (int b = 0; b < buckets; b++) {
            cum += profileEwma[b];
            cumProfile[b] = cum;
        }
        profileTotal = cum;
    }

    // ------------------------------------------------------------------
    // Feed
    // ------------------------------------------------------------------

    /** Market volume observed in {@code bucket} (call as prints arrive). */
    public void onVolume(int bucket, long qty) {
        if (qty > 0) {
            today[bucket] += qty;
            todayTotal += qty;
        }
    }

    /**
     * Closes the session: folds today into the learned profile and resets
     * the intraday state. Call once per trading day. Unlike the vol/spread
     * curves, a zero bucket IS a real observation here (no prints = no
     * volume), so partial-coverage sessions (feed started mid-day) bias
     * the profile — exclude them from rollDay or seed the shape via
     * {@link #seedProfile} instead.
     */
    public void rollDay() {
        for (int b = 0; b < buckets; b++) {
            profileEwma[b] = daysLearned == 0
                    ? today[b]
                    : profileEwma[b] + alpha * (today[b] - profileEwma[b]);
            today[b] = 0;
        }
        rebuildPrefixSums();
        todayTotal = 0;
        daysLearned++;
    }

    // ------------------------------------------------------------------
    // Prediction
    // ------------------------------------------------------------------

    /**
     * Expected fraction of TODAY's total volume already traded, at
     * {@code fracWithinBucket} through {@code bucket} — the live VWAP
     * curve input. Falls back to linear time when no profile is learned
     * yet (VWAP degrades to TWAP, the honest default).
     */
    public double expectedFractionElapsed(int bucket, double fracWithinBucket) {
        double f = Math.max(0, Math.min(1, fracWithinBucket));
        if (profileTotal <= 0) {
            return (bucket + f) / buckets;
        }
        // O(1) via the prefix sums: volume strictly before this bucket plus
        // the fraction consumed within it.
        double cum = (bucket > 0 ? cumProfile[bucket - 1] : 0) + f * profileEwma[bucket];
        return Math.min(1.0, cum / profileTotal);
    }

    /**
     * Projected total volume for today: the learned day total scaled by
     * today's realized-vs-expected ratio, shrunk toward 1 by how much of
     * the expected day has elapsed. Returns the learned total before any
     * intraday evidence, 0 when nothing is learned or realized.
     */
    public double projectedDayVolume(int bucket, double fracWithinBucket) {
        if (profileTotal <= 0) {
            return realizedToday();
        }
        double expectedSoFar = expectedFractionElapsed(bucket, fracWithinBucket) * profileTotal;
        double realized = realizedToday();
        if (expectedSoFar <= 0) {
            return profileTotal;
        }
        double ratio = realized / expectedSoFar;
        double w = Math.min(1.0, expectedSoFar / profileTotal);
        double scale = 1 + w * (ratio - 1);
        return profileTotal * Math.max(0, scale);
    }

    /** Volume still expected between now and the close, under the projection. */
    public double expectedVolumeRemaining(int bucket, double fracWithinBucket) {
        return Math.max(0,
                projectedDayVolume(bucket, fracWithinBucket) - realizedToday());
    }

    /** Today's realized volume so far (O(1) running total). */
    public double realizedToday() {
        return todayTotal;
    }

    /** The learned average volume for one bucket. */
    public double profileVolume(int bucket) {
        return profileEwma[bucket];
    }

    // ------------------------------------------------------------------
    // Persistence (persist.Checkpoint)
    // ------------------------------------------------------------------

    /** Persists the learned profile (cross-day state) — see {@code persist.Checkpoint}. */
    public void writeState(DataOutput out) throws IOException {
        out.writeByte(1);
        out.writeInt(daysLearned);
        Checkpoint.writeDoubles(out, profileEwma);
    }

    /**
     * Restores the learned profile; intraday state resets (restore at
     * session start). Throws if the checkpoint was written with a
     * different bucket count or an unknown state version.
     */
    public void readState(DataInput in) throws IOException {
        Checkpoint.requireVersion(in, 1, "VolumeCurve");
        int days = in.readInt();
        Checkpoint.readDoublesInto(in, profileEwma);
        daysLearned = days;
        rebuildPrefixSums();
        Arrays.fill(today, 0);
        todayTotal = 0;
    }

    public int buckets() {
        return buckets;
    }

    public int daysLearned() {
        return daysLearned;
    }
}
