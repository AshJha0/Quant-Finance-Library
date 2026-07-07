package com.quantfinlib.microstructure;

import com.quantfinlib.persist.Checkpoint;
import com.quantfinlib.util.MathUtils;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

/**
 * Short-term spread prediction. The bid/ask spread an execution algo will
 * pay in a few seconds is well modelled by two components a live feed gives
 * you for free:
 *
 * <ol>
 *   <li><b>A time-of-day baseline</b> — spreads are wide at the open, tight
 *       midday, wide into the close (and, in FX, wide across the rollover /
 *       thin liquidity hours). Each session accumulates a per-bucket mean;
 *       {@link #rollDay} folds it into the baseline with the {@code
 *       dayAlpha} day-over-day EWMA (the first session seeds it directly) —
 *       the spread analogue of {@link VolumeCurve};</li>
 *   <li><b>A fast mean-reverting deviation</b> — the current spread relative
 *       to its time-of-day baseline, blended per observation with
 *       {@link #DEVIATION_ALPHA} and decayed toward 0 with the configured
 *       half-life. Spreads spike on events and revert; blending the live
 *       deviation with the baseline forecasts the near-term spread better
 *       than either alone.</li>
 * </ol>
 *
 * <p>{@link #forecast} returns the predicted spread over the next moment —
 * the {@code BenchmarkExecutor.MarketState.spread} input, projected rather
 * than merely observed, so the algo damps aggression <em>before</em> a
 * known-wide window (the close) instead of reacting after. Before the first
 * {@link #rollDay} there is no learned baseline yet, so the forecast
 * degrades honestly to the last observed spread. Volatility is the
 * correlated cousin — a wide-spread forecast usually coincides with high
 * {@code volPerSqrtSecond} from {@link SignalEngine}, and an algo should
 * treat both as timing-cost signals. Cross-asset, zero allocation, single
 * writer.</p>
 */
public final class SpreadForecaster {

    /**
     * Per-observation blend weight of the live deviation (distinct from
     * {@code dayAlpha}, which is the DAY-over-day baseline weight — the two
     * timescales must not share a knob).
     */
    public static final double DEVIATION_ALPHA = 0.25;

    private final int buckets;
    private final double dayAlpha;         // day-over-day baseline weight
    private final long deviationHalfLifeNanos;

    private final double[] baselineEwma;   // learned spread per bucket
    private final double[] todaySum;       // today's per-bucket accumulation
    private final long[] todayCount;

    private double deviation;              // current spread − baseline, decayed
    private long deviationTime;
    private boolean hasDeviation;
    private double lastSpread = Double.NaN;
    private int daysLearned;

    /**
     * @param buckets                time buckets per session (e.g. 78 for equities,
     *                               288 for a 24h FX day)
     * @param dayAlpha               baseline EWMA weight across days, e.g. 0.1
     * @param deviationHalfLifeNanos how fast a spread shock reverts to baseline
     */
    public SpreadForecaster(int buckets, double dayAlpha, long deviationHalfLifeNanos) {
        if (buckets < 1 || dayAlpha <= 0 || dayAlpha > 1 || deviationHalfLifeNanos <= 0) {
            throw new IllegalArgumentException(
                    "need buckets >= 1, dayAlpha in (0,1], half-life > 0");
        }
        this.buckets = buckets;
        this.dayAlpha = dayAlpha;
        this.deviationHalfLifeNanos = deviationHalfLifeNanos;
        this.baselineEwma = new double[buckets];
        this.todaySum = new double[buckets];
        this.todayCount = new long[buckets];
    }

    /** 78 equity buckets, 10% day weight, 5-second reversion. */
    public SpreadForecaster() {
        this(78, 0.1, 5_000_000_000L);
    }

    /** Seeds the time-of-day baseline from a known shape — optional. */
    public SpreadForecaster seedBaseline(double[] spreadPerBucket) {
        if (spreadPerBucket.length != buckets) {
            throw new IllegalArgumentException("baseline length must equal bucket count");
        }
        System.arraycopy(spreadPerBucket, 0, baselineEwma, 0, buckets);
        daysLearned = 1;
        return this;
    }

    // ------------------------------------------------------------------
    // Feed
    // ------------------------------------------------------------------

    /**
     * Observed spread at {@code bucket}. Accumulates today's per-bucket
     * mean (folded into the baseline at {@link #rollDay}) and updates the
     * mean-reverting deviation from the learned baseline. Non-finite or
     * negative spreads are ignored — one +∞ must not poison a bucket
     * forever.
     */
    public void onSpread(int bucket, double spread, long timestampNanos) {
        if (!(spread >= 0) || spread == Double.POSITIVE_INFINITY) {
            return;                        // !(x >= 0) also catches NaN
        }
        lastSpread = spread;
        todaySum[bucket] += spread;
        todayCount[bucket]++;
        if (daysLearned == 0 || baselineEwma[bucket] == 0) {
            // No baseline yet — for the session OR for this bucket (never
            // observed on a prior day): deviating from 0 would inject the
            // whole spread as a "shock" into the shared deviation and
            // contaminate forecasts at every bucket.
            return;
        }
        double dev = spread - baselineEwma[bucket];
        double prior = decayedDeviation(timestampNanos);
        deviation = hasDeviation ? prior + DEVIATION_ALPHA * (dev - prior) : dev;
        hasDeviation = true;
        deviationTime = timestampNanos;
    }

    /**
     * Closes the session: folds today's per-bucket mean spreads into the
     * baseline with the day-over-day EWMA (buckets with no observations
     * keep their learned value) and resets the intraday state. Seeding is
     * PER BUCKET — a bucket first observed on a later day (feed started
     * mid-session, half day) seeds from its own first observation rather
     * than EWMA-ramping from 0 and forecasting too-tight spreads at that
     * hour for weeks.
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
        deviation = 0;
        hasDeviation = false;
    }

    // ------------------------------------------------------------------
    // Prediction
    // ------------------------------------------------------------------

    /**
     * Forecast spread at {@code bucket} as of {@code nowNanos}: the learned
     * time-of-day baseline plus the mean-reverting live deviation. Before
     * the first {@link #rollDay}/seed there is no baseline, so it returns
     * the last observed spread (the honest live estimate), or NaN before
     * any observation at all.
     */
    public double forecast(int bucket, long nowNanos) {
        if (daysLearned == 0) {
            return lastSpread;
        }
        return Math.max(0, baselineEwma[bucket] + decayedDeviation(nowNanos));
    }

    /** The learned time-of-day baseline spread for a bucket (0 until learned). */
    public double baseline(int bucket) {
        return baselineEwma[bucket];
    }

    /** Current deviation from baseline (decayed to now). */
    public double currentDeviation(long nowNanos) {
        return decayedDeviation(nowNanos);
    }

    private double decayedDeviation(long now) {
        if (!hasDeviation) {
            return 0;
        }
        return deviation * MathUtils.decayFactor(now - deviationTime, deviationHalfLifeNanos);
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
     * Restores the learned baseline; the intraday deviation, last observed
     * spread and today's accumulation reset (restore at session start).
     * Throws on a bucket-count or version mismatch.
     */
    public void readState(DataInput in) throws IOException {
        Checkpoint.requireVersion(in, 1, "SpreadForecaster");
        int days = in.readInt();
        Checkpoint.readDoublesInto(in, baselineEwma);
        daysLearned = days;
        Arrays.fill(todaySum, 0);
        Arrays.fill(todayCount, 0);
        deviation = 0;
        deviationTime = 0;
        hasDeviation = false;
        lastSpread = Double.NaN;
    }

    public int buckets() {
        return buckets;
    }

    public int daysLearned() {
        return daysLearned;
    }
}
