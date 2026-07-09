package com.quantfinlib.microstructure;

import com.quantfinlib.persist.Checkpoint;
import com.quantfinlib.util.MathUtils;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * IC-weighted alpha ensemble — the layer above the individual signals.
 * A desk rarely trades one alpha; it blends several ({@code
 * SignalEngine.alpha}, {@code OnlineAlphaLearner.normalizedPrediction},
 * a lead-lag echo, a bespoke signal), and the blending question is the
 * same honesty problem the learner solves for its weights: <b>how much
 * should each component be trusted, on evidence it could not have
 * memorized?</b>
 *
 * <p>The ensemble runs one prequential IC per component: each interval,
 * {@link #onObservation} scores the component values snapshotted at the
 * PREVIOUS call against the return just realized (values now would
 * contain the move — the same nowcast trap {@code OnlineAlphaLearner}
 * closes), then snapshots the current values for the next round.
 * {@link #combined} weights each component by {@code max(0, IC)} and the
 * weights ARE the sizing — deliberately NOT renormalized to sum to 1,
 * because renormalizing would let a lone component with IC 0.01 emit at
 * full strength: a barely-trusted blend must be a barely-sized signal.
 * A component that has not demonstrated live predictive power gets zero
 * weight, and while the track record spans less than one IC memory the
 * ensemble emits 0 outright. Output is clamped to [-1, 1]: drop-in for
 * {@code BenchmarkExecutor.MarketState.alpha}.</p>
 *
 * <p>Same caveat as every learned signal here: the live IC is a
 * tripwire, not a validation — run any blend you intend to trade through
 * the {@code alpha} package's walk-forward machinery. Components must be
 * dimensionless (~[-1, 1]); non-finite inputs are handled per component
 * (see {@link #onObservation}). Zero allocation per event (caller-owned
 * arrays), single writer, one instance per symbol. The IC evidence persists via
 * {@code persist.Checkpoint} — restored trust is earned trust.</p>
 */
public final class AlphaEnsemble {

    private final int components;
    private final double icAlpha;

    // Per-component prequential IC moments.
    private final double[] meanSig;
    private final double[] meanRet;
    private final double[] varSig;
    private final double[] varRet;
    private final double[] covar;

    // The snapshot: component values as of the PREVIOUS observation.
    private final double[] snapshot;
    private boolean hasSnapshot;
    private long samples;

    /**
     * @param components number of component signals (fixed order — the
     *                   caller owns the mapping)
     * @param icAlpha    EWMA weight of the IC statistics, e.g. 0.01
     */
    public AlphaEnsemble(int components, double icAlpha) {
        if (components < 1 || icAlpha <= 0 || icAlpha > 1) {
            throw new IllegalArgumentException("need components >= 1, icAlpha in (0,1]");
        }
        this.components = components;
        this.icAlpha = icAlpha;
        this.meanSig = new double[components];
        this.meanRet = new double[components];
        this.varSig = new double[components];
        this.varRet = new double[components];
        this.covar = new double[components];
        this.snapshot = new double[components];
    }

    /** 1% IC weight (≈ a few-hundred-observation memory). */
    public AlphaEnsemble(int components) {
        this(components, 0.01);
    }

    /**
     * One interval: the current component values and the return realized
     * since the previous call. Scores the PREVIOUS snapshot against the
     * return (honest alignment), then snapshots {@code values}. The first
     * call only snapshots. Non-finite handling is per-component: a NaN
     * component skips ITS scoring (each component's moments stay
     * conditioned on exactly the returns its covariance saw) while finite
     * siblings still score — but an observation where NOTHING scored (NaN
     * return, or every snapshot value non-finite) does not count toward
     * the track record: the gate must never open on evidence that scored
     * nothing.
     */
    public void onObservation(double[] values, double realizedReturn) {
        requireLength(values);
        if (hasSnapshot && Double.isFinite(realizedReturn)) {
            boolean scored = false;
            for (int c = 0; c < components; c++) {
                double s = snapshot[c];
                if (!Double.isFinite(s)) {
                    continue;
                }
                meanSig[c] += icAlpha * (s - meanSig[c]);
                meanRet[c] += icAlpha * (realizedReturn - meanRet[c]);
                double ds = s - meanSig[c];
                double dr = realizedReturn - meanRet[c];
                varSig[c] += icAlpha * (ds * ds - varSig[c]);
                varRet[c] += icAlpha * (dr * dr - varRet[c]);
                covar[c] += icAlpha * (ds * dr - covar[c]);
                scored = true;
            }
            if (scored) {
                samples++;
            }
        }
        System.arraycopy(values, 0, snapshot, 0, components);
        hasSnapshot = true;
    }

    /**
     * The blended alpha in [-1, 1]: {@code clamp(Σ max(0, ICᶜ) × valueᶜ)}.
     * The IC weights are the SIZE of the signal, not just its mix (see the
     * class doc for why they are not renormalized). 0 while the track
     * record spans less than one IC memory or no component has a positive
     * IC — an unproven blend is silent, exactly like the learner it sits
     * above.
     */
    public double combined(double[] values) {
        requireLength(values);
        if (samples * icAlpha < 1) {
            return 0;
        }
        double weighted = 0;
        for (int c = 0; c < components; c++) {
            double ic = componentIC(c);
            if (ic > 0 && Double.isFinite(values[c])) {
                weighted += ic * values[c];
            }
        }
        return MathUtils.clamp(weighted, -1, 1);
    }

    /**
     * The prequential (out-of-sample) IC of one component — the trust
     * diagnostic per signal. 0 before enough variance exists.
     */
    public double componentIC(int c) {
        double denom = Math.sqrt(varSig[c] * varRet[c]);
        return denom > 0 ? covar[c] / denom : 0;
    }

    public int components() {
        return components;
    }

    public long samples() {
        return samples;
    }

    // ------------------------------------------------------------------
    // Persistence (persist.Checkpoint)
    // ------------------------------------------------------------------

    /** Persists the per-component IC evidence — see {@code persist.Checkpoint}. */
    public void writeState(DataOutput out) throws IOException {
        out.writeByte(1);
        out.writeLong(samples);
        Checkpoint.writeDoubles(out, meanSig);
        Checkpoint.writeDoubles(out, meanRet);
        Checkpoint.writeDoubles(out, varSig);
        Checkpoint.writeDoubles(out, varRet);
        Checkpoint.writeDoubles(out, covar);
    }

    /**
     * Restores the IC evidence; the snapshot (intraday alignment state)
     * resets. Throws on a component-count or version mismatch.
     */
    public void readState(DataInput in) throws IOException {
        Checkpoint.requireVersion(in, 1, "AlphaEnsemble");
        long persisted = in.readLong();
        Checkpoint.readDoublesInto(in, meanSig);
        Checkpoint.readDoublesInto(in, meanRet);
        Checkpoint.readDoublesInto(in, varSig);
        Checkpoint.readDoublesInto(in, varRet);
        Checkpoint.readDoublesInto(in, covar);
        samples = persisted;
        hasSnapshot = false;
    }

    private void requireLength(double[] a) {
        if (a.length < components) {
            throw new IllegalArgumentException(
                    "array has " + a.length + " entries, ensemble has " + components);
        }
    }
}
