package com.quantfinlib.microstructure;

import com.quantfinlib.persist.Checkpoint;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Kyle's lambda — market impact LEARNED from the tape instead of assumed
 * from a formula. Kyle (1985): price change is linear in signed order
 * flow, {@code Δp = λ·q + noise}, and λ (price per unit of signed volume)
 * is the market's depth read off its own behavior. Where
 * {@link MarketImpactModel} parameterizes impact (square-root law with
 * calibrated constants), this class runs the streaming through-origin
 * regression {@code λ = E[q·Δp] / E[q²]} on time-decayed moments — the
 * regression-through-origin form is standard here because both the signed
 * flow and the mid change are zero-mean at these horizons.
 *
 * <p>Feed one {@link #onSample} per aggregation window (a trade, a bar, a
 * decision interval): the mid change over the window and the signed volume
 * that traded in it (buy-aggressor positive — {@link TradeClassifier}
 * supplies the sign when the feed doesn't). {@link #impactBps} then prices
 * a contemplated child order in the units
 * {@code execution.BenchmarkExecutor.MarketState.impactBps} expects —
 * closing the last MarketState input that lacked a live producer. A noisy
 * negative λ estimate is clamped to zero impact there: "the market pays
 * you to trade" is an estimation artifact, and feeding it to the executor
 * would ACCELERATE the schedule on garbage. {@link #lambda} stays raw for
 * diagnostics.</p>
 *
 * <p>Gap discipline: non-finite inputs are skipped whole; the moments seed
 * from the first valid sample. Zero allocation per event, single writer,
 * one instance per symbol. Persistable via {@code persist.Checkpoint} —
 * depth is a slowly-moving property worth carrying across the
 * overnight.</p>
 */
public final class KylesLambda {

    private final double alpha;

    private double flowSquared;            // decayed E[q²]
    private double flowTimesMove;          // decayed E[q·Δp]
    private long samples;

    /** @param alpha EWMA weight per sample, e.g. 0.02 (≈ 50-sample memory) */
    public KylesLambda(double alpha) {
        if (alpha <= 0 || alpha > 1) {
            throw new IllegalArgumentException("need alpha in (0,1]");
        }
        this.alpha = alpha;
    }

    /** 2% sample weight. */
    public KylesLambda() {
        this(0.02);
    }

    /**
     * One aggregation window: the mid change over the window and the
     * signed volume traded in it (+ = buyer-initiated). Non-finite or
     * volume-free windows are skipped — no flow, no impact information.
     */
    public void onSample(double midChange, double signedVolume) {
        if (!Double.isFinite(midChange) || !Double.isFinite(signedVolume)
                || signedVolume == 0) {
            return;
        }
        double q2 = signedVolume * signedVolume;
        double qp = signedVolume * midChange;
        if (samples == 0) {
            flowSquared = q2;
            flowTimesMove = qp;
        } else {
            flowSquared += alpha * (q2 - flowSquared);
            flowTimesMove += alpha * (qp - flowTimesMove);
        }
        samples++;
    }

    /**
     * The learned λ: price change per unit of signed volume. Raw — can be
     * negative while the estimate is noise. 0 until any flow is observed.
     */
    public double lambda() {
        return flowSquared > 0 ? flowTimesMove / flowSquared : 0;
    }

    /**
     * The estimated impact of trading {@code quantity} now, in basis
     * points of {@code mid} — the live producer for
     * {@code MarketState.impactBps}. The SIGN of the quantity is ignored
     * (impact is a cost in both directions — a signed sell size must not
     * read as free), a negative λ estimate is clamped to 0 (noise, never
     * a subsidy), and non-finite inputs are neutral.
     */
    public double impactBps(double quantity, double mid) {
        double size = Math.abs(quantity);
        if (!(mid > 0) || mid == Double.POSITIVE_INFINITY
                || !(size > 0) || size == Double.POSITIVE_INFINITY) {
            return 0;                      // !(x > 0) also catches NaN
        }
        return Math.max(0, lambda() * size / mid * 1e4);
    }

    public long samples() {
        return samples;
    }

    // ------------------------------------------------------------------
    // Persistence (persist.Checkpoint)
    // ------------------------------------------------------------------

    /** Persists the learned depth moments — see {@code persist.Checkpoint}. */
    public void writeState(DataOutput out) throws IOException {
        out.writeByte(1);
        out.writeDouble(flowSquared);
        out.writeDouble(flowTimesMove);
        out.writeLong(samples);
    }

    /** Restores the moments. Throws on a version mismatch. */
    public void readState(DataInput in) throws IOException {
        Checkpoint.requireVersion(in, 1, "KylesLambda");
        flowSquared = in.readDouble();
        flowTimesMove = in.readDouble();
        samples = in.readLong();
    }
}
