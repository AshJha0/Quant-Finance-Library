package com.quantfinlib.alpha;

import com.quantfinlib.util.MathUtils;

/**
 * Turns raw factor scores into tradeable weight vectors — deliberately a
 * chain of small, composable, pure functions so a construction pipeline
 * reads as what it does:
 *
 * <pre>
 *   double[] w = PortfolioConstruction.zScoreWeights(scores, 1.0, 0.05);
 *   w = PortfolioConstruction.sectorNeutralize(w, sectors);
 *   w = PortfolioConstruction.betaNeutralize(w, betas);
 *   w = PortfolioConstruction.inverseVolBudget(w, vols, 1.0);
 * </pre>
 *
 * <p>All functions take and return weight arrays aligned with the
 * {@link AlphaContext} symbol order; NaN scores become zero weight. Inputs
 * are never mutated. Weights are fractions of equity (0.05 = 5%), signed
 * (short = negative), and each step documents what it preserves and what
 * it re-normalizes — neutralization steps change gross exposure, so gross
 * targeting is a <em>final</em> step or a re-application.</p>
 */
public final class PortfolioConstruction {

    private PortfolioConstruction() {
    }

    // ------------------------------------------------------------------
    // Position sizing
    // ------------------------------------------------------------------

    /** {@link #zScoreWeights(double[], double, double)} without a per-name cap. */
    public static double[] zScoreWeights(double[] scores, double grossTarget) {
        return zScoreWeights(scores, grossTarget, Double.MAX_VALUE);
    }

    /**
     * Z-score sizing, the workhorse: demean scores cross-sectionally,
     * scale by their dispersion, clamp at ±3σ (a single outlier must not
     * own the book), then normalize to {@code Σ|w| = grossTarget} and cap
     * per-name weight at {@code maxWeight}.
     *
     * <p>Demeaning makes the book dollar-neutral by construction whenever
     * scores are symmetric; the clamp bounds concentration before the cap
     * even applies.</p>
     */
    public static double[] zScoreWeights(double[] scores, double grossTarget, double maxWeight) {
        if (grossTarget <= 0 || maxWeight <= 0) {
            throw new IllegalArgumentException("grossTarget and maxWeight must be > 0");
        }
        int n = scores.length;
        // Cross-sectional mean/std over scored names only.
        double mean = 0;
        int scored = 0;
        for (double s : scores) {
            if (!Double.isNaN(s)) {
                mean += s;
                scored++;
            }
        }
        if (scored < 2) {
            return new double[n]; // nothing to rank against: hold cash
        }
        mean /= scored;
        double var = 0;
        for (double s : scores) {
            if (!Double.isNaN(s)) {
                var += (s - mean) * (s - mean);
            }
        }
        double std = Math.sqrt(var / scored);
        double[] w = new double[n];
        if (std == 0) {
            return w; // all scores identical: no cross-sectional information
        }
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(scores[i])) {
                // Winsorized z-score: the raw signal in dispersion units.
                w[i] = Math.max(-3, Math.min(3, (scores[i] - mean) / std));
            }
        }
        normalizeGross(w, grossTarget);
        return capWeights(w, maxWeight, grossTarget);
    }

    /**
     * Caps each |weight| at {@code maxWeight}, then re-normalizes the rest
     * toward {@code grossTarget} without breaching the cap (single pass of
     * redistribution; residual gross shortfall stays in cash — honest,
     * rather than looping until the cap itself binds everywhere).
     */
    public static double[] capWeights(double[] weights, double maxWeight, double grossTarget) {
        double[] w = weights.clone();
        boolean capped = false;
        for (int i = 0; i < w.length; i++) {
            if (Math.abs(w[i]) > maxWeight) {
                w[i] = Math.signum(w[i]) * maxWeight;
                capped = true;
            }
        }
        if (!capped) {
            return w;
        }
        // Redistribute onto uncapped names proportionally, respecting the cap.
        double gross = gross(w);
        if (gross == 0) {
            return w;
        }
        double scale = grossTarget / gross;
        for (int i = 0; i < w.length; i++) {
            w[i] = Math.max(-maxWeight, Math.min(maxWeight, w[i] * scale));
        }
        return w;
    }

    // ------------------------------------------------------------------
    // Risk budgeting
    // ------------------------------------------------------------------

    /**
     * Inverse-volatility risk budgeting: rescales each position by
     * {@code 1/σᵢ} (keeping its sign and relative signal strength), so
     * every name contributes comparably to portfolio risk instead of the
     * volatile names dominating — the first-order version of equal risk
     * contribution, exact when correlations are equal. Re-normalized to
     * {@code grossTarget}.
     *
     * <p>Related but different: {@code backtest.portfolio.PositionSizing.
     * inverseVolatilityWeights} builds long-only weights FROM vols alone and
     * silently equal-weights on a degenerate vol; this method rescales an
     * existing signed book and <b>throws</b> on unusable vols — a flat
     * (σ = 0) name inside a signal-weighted book is a data problem to
     * surface, not to paper over.</p>
     */
    public static double[] inverseVolBudget(double[] weights, double[] vols, double grossTarget) {
        if (weights.length != vols.length) {
            throw new IllegalArgumentException("weights and vols must align");
        }
        double[] w = new double[weights.length];
        for (int i = 0; i < w.length; i++) {
            if (weights[i] != 0) {
                if (Double.isNaN(vols[i]) || vols[i] <= 0) {
                    throw new IllegalArgumentException(
                            "position " + i + " has no usable vol (" + vols[i] + ")");
                }
                w[i] = weights[i] / vols[i];
            }
        }
        normalizeGross(w, grossTarget);
        return w;
    }

    /**
     * Trailing return volatilities per symbol at {@code index} — the
     * standard input to {@link #inverseVolBudget} (per-bar σ; the common
     * scale cancels in the renormalization).
     */
    public static double[] trailingVols(AlphaContext ctx, int index, int lookback) {
        if (index < lookback) {
            throw new IllegalArgumentException("index " + index + " < lookback " + lookback);
        }
        double[] vols = new double[ctx.symbolCount()];
        for (int i = 0; i < vols.length; i++) {
            double[] r = new double[lookback];
            for (int j = 0; j < lookback; j++) {
                r[j] = ctx.returnOver(i, index - lookback + j, index - lookback + j + 1);
            }
            vols[i] = MathUtils.stdDev(r);
        }
        return vols;
    }

    // ------------------------------------------------------------------
    // Neutralization
    // ------------------------------------------------------------------

    /**
     * {@link #sectorNeutralize(double[], String[])} with alignment by
     * construction: sector labels come as a map keyed by symbol and are
     * resolved against the context's frozen (sorted!) symbol order —
     * {@code AlphaContext.of} re-sorts symbols, so a hand-built array in the
     * caller's insertion order would silently demean against permuted
     * labels. Symbols missing from the map keep their own singleton sector
     * (i.e. they demean to zero).
     */
    public static double[] sectorNeutralize(AlphaContext ctx, double[] weights,
                                            java.util.Map<String, String> sectorBySymbol) {
        if (weights.length != ctx.symbolCount()) {
            throw new IllegalArgumentException("weights must align with the context panel");
        }
        String[] sectors = new String[weights.length];
        for (int i = 0; i < sectors.length; i++) {
            // Unknown sector → unique label → the name demeans against
            // itself (to zero) instead of polluting a real sector's offset.
            sectors[i] = sectorBySymbol.getOrDefault(ctx.symbols().get(i),
                    " UNKNOWN:" + ctx.symbols().get(i));
        }
        return sectorNeutralize(weights, sectors);
    }

    /**
     * Sector neutrality: demeans weights within each sector, so every
     * sector's net weight is exactly zero and the book carries stock
     * selection, not sector bets. Names with weight 0 stay 0 (they are not
     * dragged in to fund their sector's offset). Gross exposure changes —
     * re-target gross afterwards if it matters.
     *
     * @param sectors sector label per symbol, aligned with the weights —
     *                which follow {@code AlphaContext.symbols()} order
     *                (SORTED, not your input map's order); prefer the
     *                {@link #sectorNeutralize(AlphaContext, double[], java.util.Map)}
     *                overload, which cannot misalign
     */
    public static double[] sectorNeutralize(double[] weights, String[] sectors) {
        if (weights.length != sectors.length) {
            throw new IllegalArgumentException("weights and sectors must align");
        }
        double[] w = weights.clone();
        java.util.Map<String, double[]> sums = new java.util.HashMap<>(); // [sum, count]
        for (int i = 0; i < w.length; i++) {
            if (w[i] != 0) {
                sums.computeIfAbsent(sectors[i], k -> new double[2]);
                sums.get(sectors[i])[0] += w[i];
                sums.get(sectors[i])[1]++;
            }
        }
        for (int i = 0; i < w.length; i++) {
            if (w[i] != 0) {
                double[] s = sums.get(sectors[i]);
                w[i] -= s[0] / s[1];
            }
        }
        return w;
    }

    /**
     * Beta neutrality: removes the market-beta component by projecting the
     * weight vector orthogonal to the beta vector —
     * {@code w − β · (w·β)/(β·β)} — so {@code Σ wᵢβᵢ = 0} exactly and the
     * book's P&amp;L stops being a leveraged market bet. Gross changes;
     * re-target afterwards if needed.
     */
    public static double[] betaNeutralize(double[] weights, double[] betas) {
        if (weights.length != betas.length) {
            throw new IllegalArgumentException("weights and betas must align");
        }
        double wb = 0;
        double bb = 0;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] != 0) {
                double beta = requireBeta(betas[i], i);
                wb += weights[i] * beta;
                bb += beta * beta;
            }
        }
        double[] w = weights.clone();
        if (bb == 0) {
            return w; // no active betas: nothing to project out
        }
        double lambda = wb / bb;
        for (int i = 0; i < w.length; i++) {
            if (w[i] != 0) {
                w[i] -= lambda * betas[i];
            }
        }
        return w;
    }

    /**
     * Trailing OLS betas of each symbol against the equal-weight universe
     * return — the in-panel market proxy when no index series is supplied.
     */
    public static double[] trailingBetas(AlphaContext ctx, int index, int lookback) {
        if (index < lookback) {
            throw new IllegalArgumentException("index " + index + " < lookback " + lookback);
        }
        int n = ctx.symbolCount();
        double[][] returns = new double[n][lookback];
        double[] market = new double[lookback];
        for (int j = 0; j < lookback; j++) {
            double sum = 0;
            for (int i = 0; i < n; i++) {
                returns[i][j] = ctx.returnOver(i, index - lookback + j, index - lookback + j + 1);
                sum += returns[i][j];
            }
            market[j] = sum / n;
        }
        double[] betas = new double[n];
        for (int i = 0; i < n; i++) {
            // One beta definition library-wide: risk.RiskMetrics owns it
            // (including the zero-variance-benchmark policy).
            betas[i] = com.quantfinlib.risk.RiskMetrics.beta(returns[i], market);
        }
        return betas;
    }

    // ------------------------------------------------------------------
    // Optimization
    // ------------------------------------------------------------------

    /**
     * Unconstrained mean-variance tilt: {@code w ∝ Σ⁻¹ α} (the Markowitz
     * solution up to scale), solved via Gaussian elimination and normalized
     * to {@code grossTarget}. Unlike z-score sizing this <em>uses</em> the
     * correlation structure: two highly correlated names with the same
     * alpha share one bet instead of doubling it. Feed a shrunk/regularized
     * covariance — the raw sample matrix near-singular universe inverts
     * into garbage, which is a data problem no solver fixes.
     *
     * @param alphas     expected-return proxy per symbol (z-scored factor
     *                   scores work); NaN = exclude
     * @param covariance per-bar return covariance, aligned both ways
     */
    public static double[] meanVarianceTilt(double[] alphas, double[][] covariance,
                                            double grossTarget) {
        int n = alphas.length;
        if (covariance.length != n) {
            throw new IllegalArgumentException("alphas and covariance must align");
        }
        // Solve only over scored names; excluded names keep zero weight.
        int m = 0;
        int[] map = new int[n];
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(alphas[i])) {
                map[m++] = i;
            }
        }
        if (m == 0) {
            return new double[n];
        }
        double[][] cov = new double[m][m];
        double[] a = new double[m];
        for (int i = 0; i < m; i++) {
            a[i] = alphas[map[i]];
            for (int j = 0; j < m; j++) {
                cov[i][j] = covariance[map[i]][map[j]];
            }
        }
        double[] solved;
        try {
            solved = MathUtils.solveLinear(cov, a);
        } catch (IllegalArgumentException e) {
            // The solver reports compacted-matrix columns, which mislead when
            // NaN alphas were squeezed out — re-throw in the caller's terms.
            throw new IllegalArgumentException(
                    "covariance is singular (a flat/duplicated/forward-filled series has zero "
                            + "or dependent variance) — shrink or regularize it, e.g. add λI ("
                            + e.getMessage() + ")", e);
        }
        double[] w = new double[n];
        for (int i = 0; i < m; i++) {
            w[map[i]] = solved[i];
        }
        normalizeGross(w, grossTarget);
        return w;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Scales weights in place so {@code Σ|w| = grossTarget} (no-op on a flat book). */
    static void normalizeGross(double[] w, double grossTarget) {
        double gross = gross(w);
        if (gross == 0) {
            return;
        }
        double scale = grossTarget / gross;
        for (int i = 0; i < w.length; i++) {
            w[i] *= scale;
        }
    }

    static double gross(double[] w) {
        double g = 0;
        for (double x : w) {
            g += Math.abs(x);
        }
        return g;
    }

    private static double requireBeta(double beta, int i) {
        if (Double.isNaN(beta)) {
            throw new IllegalArgumentException("position " + i + " has NaN beta");
        }
        return beta;
    }
}
