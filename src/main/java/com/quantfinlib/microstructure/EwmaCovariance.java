package com.quantfinlib.microstructure;

import com.quantfinlib.persist.Checkpoint;
import com.quantfinlib.util.MathUtils;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Streaming EWMA covariance matrix — the multi-asset risk picture that
 * single-symbol volatility cannot see. RiskMetrics-style: one return
 * vector per sampling interval, {@code cov ← λ·cov + (1−λ)·rᵢrⱼ}, with
 * the classic zero-mean convention (intraday returns have negligible mean
 * at these horizons; carrying decayed means would double the state for a
 * correction smaller than the estimation noise — a documented choice, not
 * an oversight).
 *
 * <p><b>Who consumes it.</b> {@code execution.PortfolioExecutor}
 * documents its capacity allocation as the <em>diagonal</em> approximation
 * of multi-asset Almgren-Chriss; {@link #marginalContribution} is the
 * missing off-diagonal piece — feed the matrix via
 * {@code PortfolioExecutor.useRiskModel} and scarce liquidity flows to the
 * symbols whose remaining position contributes most to BASKET risk, not
 * just to their own. {@link #minVarianceHedgeRatio} is the live hedge
 * beta (cov/var) for cross hedging.</p>
 *
 * <p><b>Discipline.</b> The matrix stays positive-semidefinite because
 * every update is a full-vector rank-1 outer product: a sample containing
 * ANY non-finite return is dropped whole (updating only the clean pairs
 * would break PSD and silently skew correlations). Each pair seeds from
 * its first observation rather than ramping from 0. The lower triangle
 * lives in one flat array — zero allocation per sample, O(n²) work, which
 * at basket sizes (tens of symbols) on an interval cadence is
 * microseconds. Single writer.</p>
 */
public final class EwmaCovariance {

    private final int symbols;
    private final double lambda;

    // Lower triangle, flat: cov(i,j) with i >= j at i*(i+1)/2 + j.
    private final double[] tri;
    private long samples;

    /**
     * @param symbols basket size (dense indices, fixed at construction)
     * @param lambda  decay per sample, e.g. 0.94 (RiskMetrics daily
     *                convention; intraday intervals often want 0.97–0.99)
     */
    public EwmaCovariance(int symbols, double lambda) {
        // 46,340 is where the triangle index i*(i+1)/2 leaves int range —
        // far beyond the basket sizes this streaming design is for, but a
        // constructor must never accept a size it cannot represent.
        if (symbols < 1 || symbols > 46_340 || lambda <= 0 || lambda >= 1) {
            throw new IllegalArgumentException(
                    "need symbols in [1, 46340], lambda in (0,1)");
        }
        this.symbols = symbols;
        this.lambda = lambda;
        this.tri = new double[symbols * (symbols + 1) / 2];
    }

    /** RiskMetrics λ = 0.94. */
    public EwmaCovariance(int symbols) {
        this(symbols, 0.94);
    }

    // ------------------------------------------------------------------
    // Feed
    // ------------------------------------------------------------------

    /**
     * One sampling interval: every symbol's return over the interval that
     * just closed (0 for a symbol that did not move — that IS its return).
     * A vector containing any non-finite entry is dropped entirely: a
     * partial update would break positive-semidefiniteness, so a bad print
     * on one symbol must not corrupt the whole matrix.
     *
     * @param returns length {@code >= symbols}; entries beyond the basket
     *                are ignored
     */
    public void onReturns(double[] returns) {
        if (returns.length < symbols) {
            throw new IllegalArgumentException(
                    "returns has " + returns.length + " entries, basket needs " + symbols);
        }
        for (int i = 0; i < symbols; i++) {
            if (!Double.isFinite(returns[i])) {
                return;                    // gap: drop the whole sample
            }
        }
        boolean seed = samples == 0;
        int k = 0;
        for (int i = 0; i < symbols; i++) {
            double ri = returns[i];
            for (int j = 0; j <= i; j++, k++) {
                double prod = ri * returns[j];
                tri[k] = seed ? prod : tri[k] + (1 - lambda) * (prod - tri[k]);
            }
        }
        samples++;
    }

    // ------------------------------------------------------------------
    // The matrix
    // ------------------------------------------------------------------

    /** Decayed covariance between two symbols (order-free). */
    public double covariance(int i, int j) {
        return i >= j ? tri[i * (i + 1) / 2 + j] : tri[j * (j + 1) / 2 + i];
    }

    /** Decayed variance of one symbol. */
    public double variance(int i) {
        return tri[i * (i + 1) / 2 + i];
    }

    /** Decayed volatility (per √interval), 0 until learned. */
    public double volatility(int i) {
        return Math.sqrt(Math.max(variance(i), 0));
    }

    /** Decayed correlation in [-1, 1]; 0 while either variance is 0. */
    public double correlation(int i, int j) {
        double denom = Math.sqrt(variance(i) * variance(j));
        return denom > 0 ? MathUtils.clamp(covariance(i, j) / denom, -1, 1) : 0;
    }

    // ------------------------------------------------------------------
    // Portfolio arithmetic (all zero-alloc, caller-owned arrays)
    // ------------------------------------------------------------------

    /** {@code w'Σw}: portfolio variance of the (signed) weight vector. */
    public double portfolioVariance(double[] weights) {
        requireLength(weights);
        double total = 0;
        int k = 0;
        for (int i = 0; i < symbols; i++) {
            for (int j = 0; j <= i; j++, k++) {
                double term = weights[i] * weights[j] * tri[k];
                total += i == j ? term : 2 * term;
            }
        }
        return total;
    }

    /**
     * Marginal contribution to portfolio risk:
     * {@code out[i] = wᵢ·(Σw)ᵢ / (w'Σw)} — the fraction of total basket
     * variance symbol i's position is responsible for (contributions sum
     * to 1; a natural hedge contributes negatively). All zeros while the
     * portfolio variance is not positive — no risk picture, no signal.
     *
     * @return the portfolio variance {@code w'Σw}, so a caller gating on
     *         "is there a risk picture?" needs exactly one call — a
     *         separate {@link #portfolioVariance} probe would repeat the
     *         O(n²) pass this method already makes
     */
    public double marginalContribution(double[] weights, double[] out) {
        requireLength(weights);
        requireLength(out);
        double total = portfolioVariance(weights);
        if (total <= 0) {
            for (int i = 0; i < symbols; i++) {
                out[i] = 0;
            }
            return total;
        }
        for (int i = 0; i < symbols; i++) {
            double sigmaW = 0;
            for (int j = 0; j < symbols; j++) {
                sigmaW += covariance(i, j) * weights[j];
            }
            out[i] = weights[i] * sigmaW / total;
        }
        return total;
    }

    /**
     * The live minimum-variance hedge ratio: hedge {@code target} with
     * {@code cov(target,hedge)/var(hedge)} units of {@code hedge} — the
     * streaming sibling of {@code hedging.MinimumVarianceHedge}. 0 while
     * the hedge instrument's variance is unlearned.
     */
    public double minVarianceHedgeRatio(int target, int hedge) {
        double v = variance(hedge);
        return v > 0 ? covariance(target, hedge) / v : 0;
    }

    public int symbols() {
        return symbols;
    }

    public long samples() {
        return samples;
    }

    private void requireLength(double[] a) {
        if (a.length < symbols) {
            throw new IllegalArgumentException(
                    "array has " + a.length + " entries, basket needs " + symbols);
        }
    }

    // ------------------------------------------------------------------
    // Persistence (persist.Checkpoint)
    // ------------------------------------------------------------------

    /** Persists the learned matrix — see {@code persist.Checkpoint}. */
    public void writeState(DataOutput out) throws IOException {
        out.writeByte(1);
        out.writeLong(samples);
        Checkpoint.writeDoubles(out, tri);
    }

    /** Restores the matrix. Throws on a basket-size or version mismatch. */
    public void readState(DataInput in) throws IOException {
        Checkpoint.requireVersion(in, 1, "EwmaCovariance");
        long persisted = in.readLong();
        Checkpoint.readDoublesInto(in, tri);
        samples = persisted;
    }
}
