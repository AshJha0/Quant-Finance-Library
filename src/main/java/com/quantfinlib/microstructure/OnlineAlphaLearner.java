package com.quantfinlib.microstructure;

import com.quantfinlib.persist.Checkpoint;
import com.quantfinlib.util.MathUtils;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Online alpha-weight learning: upgrades {@link SignalEngine#alpha}'s fixed
 * composite weights to weights <em>learned from realized returns</em> — an
 * online ridge regression (SGD with L2 shrinkage) from the four
 * dimensionless signal ingredients (queue imbalance, trade imbalance,
 * normalized OFI, momentum-Z) to the next-interval return.
 *
 * <h2>The honesty mechanism: prequential out-of-sample IC</h2>
 * The trap with any self-updating alpha is grading its own homework. This
 * learner can't: {@link #train} records the prediction made with the
 * CURRENT weights <em>before</em> the realized return updates them
 * (predict-then-train, "prequential" evaluation), and maintains a
 * time-decayed correlation between those genuinely out-of-sample
 * predictions and the outcomes — {@link #outOfSampleIC}. <b>Gate any use
 * of the learned alpha on that number</b>: persistently positive
 * (intraday, ~0.02–0.10 is real) means the weights found signal; an IC
 * hovering at zero means they found noise, and {@link #normalizedPrediction}
 * should be treated as such. This diagnostic is a live tripwire, not a
 * validation — before trading a weighting seriously, run it through the
 * {@code alpha} package's walk-forward and permutation machinery like any
 * other signal.
 *
 * <p>One instance per symbol (or one pooled across a homogeneous group —
 * pooling trades specificity for sample count; the caller chooses).
 * Cross-asset: ingredients are dimensionless and the target is a return.
 * Zero allocation per event, single writer.</p>
 */
public final class OnlineAlphaLearner {

    private static final int FEATURES = 4;

    private final double learningRate;
    private final double ridgeLambda;
    private final double icAlpha;

    private final double[] w = new double[FEATURES];

    // Prequential IC statistics (time-decayed first/second moments).
    private double meanPred;
    private double meanRet;
    private double varPred;
    private double varRet;
    private double covar;
    private double absPredEwma;            // prediction scale, for normalization
    private long samples;

    // trainFrom's snapshot: the engine's ingredients as of the PREVIOUS
    // call, so a return realized over (t, t+1] is fitted against features
    // observed at t — never against the t+1 features that already contain
    // the move (which would be lookahead leakage the IC could not detect).
    private double snapQi;
    private double snapTi;
    private double snapOfi;
    private double snapMz;
    private boolean hasSnapshot;

    /**
     * @param learningRate SGD step, e.g. 0.01 — larger adapts faster,
     *                     overshoots noisier targets
     * @param ridgeLambda  L2 shrinkage toward 0 per step, e.g. 1e-4 —
     *                     keeps weights from chasing one lucky streak
     * @param icAlpha      EWMA weight of the IC statistics, e.g. 0.01
     *                     (≈ a few-hundred-sample memory)
     */
    public OnlineAlphaLearner(double learningRate, double ridgeLambda, double icAlpha) {
        if (learningRate <= 0 || ridgeLambda < 0 || icAlpha <= 0 || icAlpha > 1) {
            throw new IllegalArgumentException(
                    "need learningRate > 0, ridgeLambda >= 0, icAlpha in (0,1]");
        }
        this.learningRate = learningRate;
        this.ridgeLambda = ridgeLambda;
        this.icAlpha = icAlpha;
    }

    /** lr 0.01, ridge 1e-4, IC memory ~200 samples. */
    public OnlineAlphaLearner() {
        this(0.01, 1e-4, 0.01);
    }

    // ------------------------------------------------------------------
    // Predict / train
    // ------------------------------------------------------------------

    /**
     * The learned prediction of the next-interval return from the four
     * SignalEngine ingredients (each expected in ~[-1, 1]). Raw units =
     * whatever return you train against.
     */
    public double predict(double queueImbalance, double tradeImbalance,
                          double normalizedOfi, double momentumZ) {
        return w[0] * queueImbalance + w[1] * tradeImbalance
                + w[2] * normalizedOfi + w[3] * momentumZ;
    }

    /** {@link #predict} pulling the ingredients straight from a SignalEngine. */
    public double predictFrom(SignalEngine engine, int symbolId) {
        return predict(engine.queueImbalance(symbolId), engine.tradeImbalance(symbolId),
                engine.normalizedOfi(symbolId), engine.momentumZ(symbolId));
    }

    /**
     * One learning step: the prediction made with the current weights is
     * scored against {@code realizedReturn} (this is what makes
     * {@link #outOfSampleIC} honest), THEN the weights update by ridge-SGD.
     * Non-finite inputs are skipped entirely — a NaN must neither poison
     * the weights nor sneak into the IC.
     *
     * <p><b>Alignment is the caller's contract here:</b> the four features
     * must have been observed BEFORE the interval {@code realizedReturn}
     * covers. Passing the current features with the return that just ended
     * fits a nowcast — the features already contain the move — and the IC
     * will read high on pure leakage. {@link #trainFrom} handles this
     * alignment automatically; use it unless you keep your own snapshots.</p>
     */
    public void train(double queueImbalance, double tradeImbalance,
                      double normalizedOfi, double momentumZ, double realizedReturn) {
        if (!Double.isFinite(queueImbalance) || !Double.isFinite(tradeImbalance)
                || !Double.isFinite(normalizedOfi) || !Double.isFinite(momentumZ)
                || !Double.isFinite(realizedReturn)) {
            return;
        }
        // 1. Score BEFORE learning: genuinely out-of-sample.
        double pred = predict(queueImbalance, tradeImbalance, normalizedOfi, momentumZ);
        meanPred += icAlpha * (pred - meanPred);
        meanRet += icAlpha * (realizedReturn - meanRet);
        double dp = pred - meanPred;
        double dr = realizedReturn - meanRet;
        varPred += icAlpha * (dp * dp - varPred);
        varRet += icAlpha * (dr * dr - varRet);
        covar += icAlpha * (dp * dr - covar);
        // The scale seeds from the first nonzero |prediction| — ramping
        // from 0 would leave it ~an order of magnitude small early and let
        // normalizedPrediction rail-pin at ±1 on a thin track record.
        double ap = Math.abs(pred);
        absPredEwma = absPredEwma == 0 ? ap : absPredEwma + icAlpha * (ap - absPredEwma);
        samples++;

        // 2. Ridge-SGD step: w += lr·(error·x − λ·w).
        double err = realizedReturn - pred;
        w[0] += learningRate * (err * queueImbalance - ridgeLambda * w[0]);
        w[1] += learningRate * (err * tradeImbalance - ridgeLambda * w[1]);
        w[2] += learningRate * (err * normalizedOfi - ridgeLambda * w[2]);
        w[3] += learningRate * (err * momentumZ - ridgeLambda * w[3]);
    }

    /**
     * The aligned learning step over a SignalEngine: trains on the
     * ingredients snapshotted at the PREVIOUS call (which predate the
     * interval {@code realizedReturn} covers), then snapshots the current
     * ingredients for the next call. The first call only snapshots — there
     * is nothing aligned to train on yet. Call once per interval, with the
     * return realized since the previous call; feeding it the current
     * features directly would let the momentum echo of the return grade
     * itself (see {@link #train}).
     */
    public void trainFrom(SignalEngine engine, int symbolId, double realizedReturn) {
        if (hasSnapshot) {
            train(snapQi, snapTi, snapOfi, snapMz, realizedReturn);
        }
        snapQi = engine.queueImbalance(symbolId);
        snapTi = engine.tradeImbalance(symbolId);
        snapOfi = engine.normalizedOfi(symbolId);
        snapMz = engine.momentumZ(symbolId);
        hasSnapshot = true;
    }

    // ------------------------------------------------------------------
    // Diagnostics and the normalized output
    // ------------------------------------------------------------------

    /**
     * The prequential (out-of-sample) information coefficient: time-decayed
     * correlation between the predictions made BEFORE each outcome and the
     * outcomes themselves. The gate for using the learned alpha; 0 before
     * enough variance exists to measure.
     */
    public double outOfSampleIC() {
        double denom = Math.sqrt(varPred * varRet);
        return denom > 0 ? covar / denom : 0;
    }

    /**
     * The prediction scaled by its own typical magnitude and clamped to
     * [-1, 1] — the {@code BenchmarkExecutor.MarketState.alpha}-ready form.
     * Returns 0 while the out-of-sample IC is not positive OR the track
     * record is shorter than one IC memory (~1/icAlpha samples): a learner
     * that hasn't demonstrated live predictive power over a meaningful
     * window emits no signal — a lucky first hour is not evidence.
     */
    public double normalizedPrediction(double queueImbalance, double tradeImbalance,
                                       double normalizedOfi, double momentumZ) {
        if (samples * icAlpha < 1 || outOfSampleIC() <= 0 || absPredEwma <= 0) {
            return 0;
        }
        double pred = predict(queueImbalance, tradeImbalance, normalizedOfi, momentumZ);
        return MathUtils.clamp(pred / (2 * absPredEwma), -1, 1);
    }

    /** The learned weight for feature {@code i} (0=queueImb, 1=tradeImb, 2=OFI, 3=momZ). */
    public double weight(int i) {
        return w[i];
    }

    public long samples() {
        return samples;
    }

    // ------------------------------------------------------------------
    // Persistence (persist.Checkpoint)
    // ------------------------------------------------------------------

    /**
     * Persists the weights AND the prequential IC evidence — restored
     * trust must be earned trust: a learner reloaded without its IC
     * history would start silent again ({@link #normalizedPrediction}
     * gates on the IC), which is exactly right for weights with no
     * demonstrated track record.
     */
    public void writeState(DataOutput out) throws IOException {
        out.writeByte(1);                  // matches Checkpoint.requireVersion below
        Checkpoint.writeDoubles(out, w);
        out.writeDouble(meanPred);
        out.writeDouble(meanRet);
        out.writeDouble(varPred);
        out.writeDouble(varRet);
        out.writeDouble(covar);
        out.writeDouble(absPredEwma);
        out.writeLong(samples);
    }

    /**
     * Restores weights and IC evidence; the trainFrom feature snapshot is
     * intraday state and resets. Throws on a version mismatch.
     */
    public void readState(DataInput in) throws IOException {
        Checkpoint.requireVersion(in, 1, "OnlineAlphaLearner");
        Checkpoint.readDoublesInto(in, w);
        meanPred = in.readDouble();
        meanRet = in.readDouble();
        varPred = in.readDouble();
        varRet = in.readDouble();
        covar = in.readDouble();
        absPredEwma = in.readDouble();
        samples = in.readLong();
        hasSnapshot = false;
    }

}
