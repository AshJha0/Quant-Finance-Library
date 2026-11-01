package com.quantfinlib.alpha;

import com.quantfinlib.util.MathUtils;

/**
 * Turns raw factor scores into tradeable weight vectors, as a
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

    /**
     * A constructed book with what it actually achieved.
     *
     * @param gross          achieved {@code Σ|w|}
     * @param net            achieved {@code Σw}
     * @param grossShortfall true when gross fell below 90% of the target
     *                       (too few names for the cap, or too few scored names)
     */
    public record Book(double[] weights, double gross, double net, boolean grossShortfall) {
    }

    /** {@link #zScoreWeights(double[], double, double)} without a per-name cap. */
    public static double[] zScoreWeights(double[] scores, double grossTarget) {
        return zScoreWeights(scores, grossTarget, Double.MAX_VALUE);
    }

    /** The weights of {@link #zScoreBook}. */
    public static double[] zScoreWeights(double[] scores, double grossTarget, double maxWeight) {
        return zScoreBook(scores, grossTarget, maxWeight).weights();
    }

    /**
     * Z-score sizing: demean scores cross-sectionally, scale by their
     * dispersion, clamp at ±3σ, then size the long and short sides
     * separately to {@code grossTarget/2} each with an iterative
     * water-filling cap at {@code maxWeight}.
     *
     * <p>Sizing the sides separately keeps the book dollar-neutral after the
     * clamp and the cap, both of which break {@code Σw = 0} when applied to
     * the whole vector. When neither binds the result equals plain
     * proportional scaling of the z-scores. When one side cannot reach
     * {@code grossTarget/2} under the cap (fewer than
     * {@code grossTarget/(2·maxWeight)} names on it), both sides are sized to
     * what the smaller side can carry, and {@link Book#grossShortfall()}
     * says so: with the 5% default cap a book needs at least ten names per
     * side to deploy gross 1.0.</p>
     */
    public static Book zScoreBook(double[] scores, double grossTarget, double maxWeight) {
        if (grossTarget <= 0 || maxWeight <= 0) {
            throw new IllegalArgumentException("grossTarget and maxWeight must be > 0");
        }
        int n = scores.length;
        double mean = 0;
        int scored = 0;
        for (double s : scores) {
            if (!Double.isNaN(s)) {
                mean += s;
                scored++;
            }
        }
        double[] w = new double[n];
        if (scored < 2) {
            return new Book(w, 0, 0, true); // nothing to rank against: hold cash
        }
        mean /= scored;
        double var = 0;
        for (double s : scores) {
            if (!Double.isNaN(s)) {
                var += (s - mean) * (s - mean);
            }
        }
        double std = Math.sqrt(var / scored);
        if (std == 0) {
            return new Book(w, 0, 0, true); // no cross-sectional information
        }
        double[] z = new double[n];
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(scores[i])) {
                z[i] = Math.max(-3, Math.min(3, (scores[i] - mean) / std));
            }
        }
        double half = grossTarget / 2;
        double longAchievable = waterFillSide(z, 1, Double.POSITIVE_INFINITY, maxWeight, null);
        double shortAchievable = waterFillSide(z, -1, Double.POSITIVE_INFINITY, maxWeight, null);
        double side = Math.min(half, Math.min(longAchievable, shortAchievable));
        if (side > 0) {
            waterFillSide(z, 1, side, maxWeight, w);
            waterFillSide(z, -1, side, maxWeight, w);
        }
        double g = gross(w);
        double net = 0;
        for (double x : w) {
            net += x;
        }
        return new Book(w, g, net, g < 0.9 * grossTarget);
    }

    /**
     * Sizes the names with {@code sign·z > 0} to total magnitude
     * {@code target}, proportional to |z| with each capped at {@code cap}
     * (water-filling: names that hit the cap are fixed and the rest
     * rescaled until no new name binds). Writes into {@code out} when
     * non-null; returns the magnitude achieved (the most the side can carry
     * when {@code target} is infinite).
     */
    private static double waterFillSide(double[] z, int sign, double target, double cap,
                                        double[] out) {
        int n = z.length;
        boolean[] capped = new boolean[n];
        int active = 0;
        for (double v : z) {
            if (sign * v > 0) {
                active++;
            }
        }
        if (active == 0) {
            return 0;
        }
        if (Double.isInfinite(target) || target >= active * cap) {
            if (out != null) {
                for (int i = 0; i < n; i++) {
                    if (sign * z[i] > 0) {
                        out[i] = sign * cap;
                    }
                }
            }
            return active * cap;
        }
        double scale = 0;
        for (int iter = 0; iter <= n; iter++) {
            double fixed = 0;
            double free = 0;
            for (int i = 0; i < n; i++) {
                if (sign * z[i] > 0) {
                    if (capped[i]) {
                        fixed += cap;
                    } else {
                        free += Math.abs(z[i]);
                    }
                }
            }
            scale = free == 0 ? 0 : (target - fixed) / free;
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                if (sign * z[i] > 0 && !capped[i] && Math.abs(z[i]) * scale > cap) {
                    capped[i] = true;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        if (out != null) {
            for (int i = 0; i < n; i++) {
                if (sign * z[i] > 0) {
                    out[i] = sign * (capped[i] ? cap : Math.abs(z[i]) * scale);
                }
            }
        }
        return target;
    }

    /**
     * Caps each |weight| at {@code maxWeight} and rescales the uncapped
     * names so that {@code Σ|w|} reaches {@code grossTarget}, repeating until
     * no further name breaches the cap (water-filling). When the cap makes
     * the target unreachable every non-zero name ends at the cap and the
     * shortfall stays in cash. Signs are preserved; the net is not
     * re-balanced (use {@link #zScoreBook} for a dollar-neutral book).
     */
    public static double[] capWeights(double[] weights, double maxWeight, double grossTarget) {
        double[] mags = new double[weights.length];
        for (int i = 0; i < mags.length; i++) {
            mags[i] = Math.abs(weights[i]);
        }
        double[] filled = new double[weights.length];
        waterFillSide(mags, 1, grossTarget, maxWeight, filled);
        for (int i = 0; i < filled.length; i++) {
            filled[i] = Math.copySign(filled[i], weights[i]);
        }
        return filled;
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
     * inverseVolatilityWeights} builds long-only weights from vols alone and
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
     * Trailing return volatilities per symbol at {@code index} (sample sd
     * of the close-to-close returns ending at bars
     * {@code index−lookback+1 .. index}), the standard input to
     * {@link #inverseVolBudget}. Synthetic (forward-filled) bars are skipped;
     * NaN when fewer than two real bars remain.
     */
    public static double[] trailingVols(AlphaContext ctx, int index, int lookback) {
        if (index < lookback) {
            throw new IllegalArgumentException("index " + index + " < lookback " + lookback);
        }
        double[] vols = new double[ctx.symbolCount()];
        for (int i = 0; i < vols.length; i++) {
            com.quantfinlib.core.BarSeries s = ctx.series(i);
            double[] r = new double[lookback];
            int k = 0;
            for (int j = index - lookback + 1; j <= index; j++) {
                if (!s.isSynthetic(j)) {
                    r[k++] = s.close(j) / s.close(j - 1) - 1;
                }
            }
            vols[i] = k < 2 ? Double.NaN : MathUtils.stdDev(java.util.Arrays.copyOf(r, k));
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
            // An unknown sector gets a unique label, so the name demeans against
            // itself (to zero) instead of polluting a real sector's offset.
            sectors[i] = sectorBySymbol.getOrDefault(ctx.symbols().get(i),
                    "\0UNKNOWN:" + ctx.symbols().get(i));
        }
        return sectorNeutralize(weights, sectors);
    }

    /**
     * Sector neutrality: demeans weights within each sector, so every
     * sector's net weight is exactly zero and the book carries stock
     * selection, not sector bets. Names with weight 0 stay 0 (they are not
     * dragged in to fund their sector's offset). Gross exposure changes;
     * re-target gross afterwards if it matters. Followed by
     * {@link #betaNeutralize} it is no longer sector-neutral: use
     * {@link #neutralize} for several constraints at once.
     *
     * @param sectors sector label per symbol, aligned with the weights —
     *                which follow {@code AlphaContext.symbols()} order
     *                (sorted, not your input map's order); prefer the
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
     * weight vector orthogonal to the beta vector,
     * {@code w − β · (w·β)/(β·β)}. After the projection {@code Σ wᵢβᵢ = 0}
     * exactly and the book's P&amp;L stops being a leveraged market bet.
     * Gross changes; re-target afterwards if needed.
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
     * Trailing OLS betas against a leave-one-out equal-weight market: for
     * symbol {@code i} the market return is the mean of the other names'
     * returns. Including the name itself pulls every beta toward 1 on small
     * panels (with N names the self-weight is 1/N).
     */
    public static double[] trailingBetas(AlphaContext ctx, int index, int lookback) {
        if (index < lookback) {
            throw new IllegalArgumentException("index " + index + " < lookback " + lookback);
        }
        int n = ctx.symbolCount();
        if (n < 2) {
            throw new IllegalArgumentException("leave-one-out betas need at least 2 names");
        }
        double[][] returns = new double[n][lookback];
        double[] total = new double[lookback];
        for (int j = 0; j < lookback; j++) {
            for (int i = 0; i < n; i++) {
                returns[i][j] = ctx.returnOver(i, index - lookback + j, index - lookback + j + 1);
                total[j] += returns[i][j];
            }
        }
        double[] betas = new double[n];
        double[] market = new double[lookback];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < lookback; j++) {
                market[j] = (total[j] - returns[i][j]) / (n - 1);
            }
            betas[i] = com.quantfinlib.risk.RiskMetrics.beta(returns[i], market);
        }
        return betas;
    }

    /**
     * Trailing OLS betas against an external market series aligned with the
     * panel (same bar count and timestamps), for example an index.
     */
    public static double[] trailingBetas(AlphaContext ctx, int index, int lookback,
                                         com.quantfinlib.core.BarSeries market) {
        if (index < lookback) {
            throw new IllegalArgumentException("index " + index + " < lookback " + lookback);
        }
        if (market.size() != ctx.bars() || market.timestamp(index) != ctx.timestamp(index)) {
            throw new IllegalArgumentException("market series must be aligned with the panel");
        }
        double[] m = new double[lookback];
        for (int j = 0; j < lookback; j++) {
            int k = index - lookback + j + 1;
            m[j] = market.close(k) / market.close(k - 1) - 1;
        }
        double[] betas = new double[ctx.symbolCount()];
        double[] r = new double[lookback];
        for (int i = 0; i < betas.length; i++) {
            for (int j = 0; j < lookback; j++) {
                r[j] = ctx.returnOver(i, index - lookback + j, index - lookback + j + 1);
            }
            betas[i] = com.quantfinlib.risk.RiskMetrics.beta(r, m);
        }
        return betas;
    }

    /**
     * Joint neutralisation: projects {@code w} onto the null space of all
     * constraint rows at once, so that {@code Σ_i a_ki w_i = 0} for every
     * row {@code k} simultaneously. Applying {@link #sectorNeutralize} and
     * then {@link #betaNeutralize} in sequence does not achieve this: the
     * second projection undoes the first. Constraint rows are restricted to
     * the book's support (names with {@code w = 0} stay at 0), orthonormalised
     * by modified Gram-Schmidt, and rows that are linearly dependent on
     * earlier ones (for example a dollar row next to a full set of sector
     * dummies) are dropped. Gross changes; re-target afterwards.
     *
     * @param constraints constraint rows, each aligned with {@code w}
     *                    (sector dummies, betas, a row of ones for dollar
     *                    neutrality)
     */
    public static double[] neutralize(double[] weights, double[][] constraints) {
        int n = weights.length;
        java.util.List<double[]> basis = new java.util.ArrayList<>();
        for (double[] row : constraints) {
            if (row.length != n) {
                throw new IllegalArgumentException("constraint row must align with the weights");
            }
            double[] q = new double[n];
            double norm0 = 0;
            for (int i = 0; i < n; i++) {
                if (weights[i] != 0) {
                    if (Double.isNaN(row[i])) {
                        throw new IllegalArgumentException("NaN constraint value at position " + i);
                    }
                    q[i] = row[i];
                    norm0 += row[i] * row[i];
                }
            }
            for (double[] b : basis) {
                double d = MathUtils.dot(q, b);
                for (int i = 0; i < n; i++) {
                    q[i] -= d * b[i];
                }
            }
            double norm = Math.sqrt(MathUtils.dot(q, q));
            if (norm <= 1e-10 * Math.max(1, Math.sqrt(norm0))) {
                continue; // dependent on earlier constraints
            }
            for (int i = 0; i < n; i++) {
                q[i] /= norm;
            }
            basis.add(q);
        }
        double[] w = weights.clone();
        for (double[] b : basis) {
            double d = MathUtils.dot(w, b);
            for (int i = 0; i < n; i++) {
                w[i] -= d * b[i];
            }
        }
        return w;
    }

    /** Sector dummy rows (one per distinct label) for {@link #neutralize}. */
    public static double[][] sectorRows(String[] sectors) {
        java.util.Map<String, Integer> idx = new java.util.LinkedHashMap<>();
        for (String s : sectors) {
            idx.putIfAbsent(s, idx.size());
        }
        double[][] rows = new double[idx.size()][sectors.length];
        for (int i = 0; i < sectors.length; i++) {
            rows[idx.get(sectors[i])][i] = 1;
        }
        return rows;
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
