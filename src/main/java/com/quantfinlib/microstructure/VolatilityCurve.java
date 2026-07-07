package com.quantfinlib.microstructure;

import com.quantfinlib.persist.Checkpoint;
import com.quantfinlib.util.MathUtils;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

/**
 * Intraday volatility seasonality — the third leg of the seasonality trio
 * beside {@link VolumeCurve} and {@link SpreadForecaster}: volatility is
 * U-shaped through an equity day (wild open, quiet lunch, busy close) and
 * session-humped through an FX day (London open, NY overlap), so "is the
 * market volatile right now?" is meaningless without "…for this time of
 * day."
 *
 * <p>Each session accumulates a per-bucket mean of the observed volatility
 * (feed {@code SignalEngine.volPerSqrtSecond} at whatever cadence you poll
 * it); {@link #rollDay} folds it into a per-bucket baseline with the
 * day-over-day EWMA, the first session seeding directly.</p>
 *
 * <p>{@link #regime} is the point of the class: the <b>normalized
 * volatility-regime signal that {@code execution.BenchmarkExecutor.MarketState}
 * documents as its {@code volatility} input</b> — current vol against the
 * time-of-day baseline, mapped to ~0 (calm for this hour) … 1 (extreme),
 * so "the open is always wild" doesn't read as an urgency signal but a
 * genuinely wild lunchtime does. Before any baseline is learned the regime
 * is 0 (neutral) — the honest default. Cross-asset, zero allocation,
 * single writer.</p>
 */
public final class VolatilityCurve {

    private final int buckets;
    private final double dayAlpha;

    private final double[] baselineEwma;   // learned vol per bucket (per √sec units)
    private final double[] todaySum;
    private final long[] todayCount;
    private int daysLearned;

    /**
     * @param buckets  time buckets per session (78 equities, 288 for 24h FX)
     * @param dayAlpha baseline EWMA weight across days, e.g. 0.1
     */
    public VolatilityCurve(int buckets, double dayAlpha) {
        if (buckets < 1 || dayAlpha <= 0 || dayAlpha > 1) {
            throw new IllegalArgumentException("need buckets >= 1, dayAlpha in (0,1]");
        }
        this.buckets = buckets;
        this.dayAlpha = dayAlpha;
        this.baselineEwma = new double[buckets];
        this.todaySum = new double[buckets];
        this.todayCount = new long[buckets];
    }

    /** 78 equity buckets, 10% day weight. */
    public VolatilityCurve() {
        this(78, 0.1);
    }

    /** Seeds the baseline from a known shape (same units you will feed) — optional. */
    public VolatilityCurve seedBaseline(double[] volPerBucket) {
        if (volPerBucket.length != buckets) {
            throw new IllegalArgumentException("baseline length must equal bucket count");
        }
        System.arraycopy(volPerBucket, 0, baselineEwma, 0, buckets);
        daysLearned = 1;
        return this;
    }

    // ------------------------------------------------------------------
    // Feed
    // ------------------------------------------------------------------

    /**
     * An observed volatility reading for {@code bucket} (e.g.
     * {@code SignalEngine.volPerSqrtSecond}, polled per interval).
     * Non-finite or negative readings are ignored.
     */
    public void onVol(int bucket, double volPerSqrtSecond) {
        if (!(volPerSqrtSecond >= 0) || volPerSqrtSecond == Double.POSITIVE_INFINITY) {
            return;                        // !(x >= 0) also catches NaN
        }
        todaySum[bucket] += volPerSqrtSecond;
        todayCount[bucket]++;
    }

    /**
     * Closes the session: folds today's per-bucket mean vol into the
     * baseline (buckets without observations keep their learned value).
     * Seeding is PER BUCKET — a bucket first observed on day 5 (feed
     * started mid-session on day 1, a half day skipped the afternoon)
     * seeds from its own first observation rather than EWMA-ramping from
     * 0, which would leave {@link #regime} falsely reading "extreme" at
     * that hour for weeks.
     */
    public void rollDay() {
        for (int b = 0; b < buckets; b++) {
            if (todayCount[b] > 0) {
                double todayMean = todaySum[b] / todayCount[b];
                baselineEwma[b] = baselineEwma[b] == 0
                        ? todayMean
                        : baselineEwma[b] + dayAlpha * (todayMean - baselineEwma[b]);
            }
            todaySum[b] = 0;
            todayCount[b] = 0;
        }
        daysLearned++;
    }

    // ------------------------------------------------------------------
    // Prediction
    // ------------------------------------------------------------------

    /** The learned time-of-day baseline vol for a bucket (0 until learned). */
    public double baseline(int bucket) {
        return baselineEwma[bucket];
    }

    /**
     * The normalized volatility-regime signal for
     * {@code BenchmarkExecutor.MarketState.volatility}: how elevated the
     * current vol is against this hour's baseline,
     * {@code clamp(current/baseline − 1, 0, 1)}. 0 when calm-for-the-hour,
     * unlearned, or fed a non-finite reading — a bad input reads as
     * neutral, never as urgency.
     */
    public double regime(int bucket, double currentVolPerSqrtSecond) {
        double base = baselineEwma[bucket];
        if (base <= 0 || !(currentVolPerSqrtSecond >= 0)
                || currentVolPerSqrtSecond == Double.POSITIVE_INFINITY) {
            return 0;
        }
        return MathUtils.clamp(currentVolPerSqrtSecond / base - 1, 0, 1);
    }

    // ------------------------------------------------------------------
    // Persistence (persist.Checkpoint)
    // ------------------------------------------------------------------

    /** Persists the learned baseline (cross-day state) — see {@code persist.Checkpoint}. */
    public void writeState(DataOutput out) throws IOException {
        out.writeByte(1);
        out.writeInt(daysLearned);
        Checkpoint.writeDoubles(out, baselineEwma);
    }

    /**
     * Restores the learned baseline; today's accumulation resets (restore
     * at session start). Throws on a bucket-count or version mismatch.
     */
    public void readState(DataInput in) throws IOException {
        Checkpoint.requireVersion(in, 1, "VolatilityCurve");
        int days = in.readInt();
        Checkpoint.readDoublesInto(in, baselineEwma);
        daysLearned = days;
        Arrays.fill(todaySum, 0);
        Arrays.fill(todayCount, 0);
    }

    public int buckets() {
        return buckets;
    }

    public int daysLearned() {
        return daysLearned;
    }
}
