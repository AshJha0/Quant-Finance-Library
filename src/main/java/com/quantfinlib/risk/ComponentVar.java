package com.quantfinlib.risk;

import com.quantfinlib.util.MathUtils;

/**
 * COMPONENT VaR — the answer to the risk committee's actual question. A
 * portfolio VaR of $10m is a fact; "which desk owns how much of it?" is
 * the decision. Under the delta-normal model the Euler allocation makes
 * the split exact and additive:
 *
 * <pre>
 *   sigma_p     = sqrt(w' &Sigma; w)
 *   marginal_i  = (&Sigma;w)_i / sigma_p          d sigma_p / d w_i
 *   component_i = w_i · marginal_i            and &Sigma;_i component_i = sigma_p  (exact)
 * </pre>
 *
 * <p>Scaled by the same z-score, component VaRs SUM EXACTLY to portfolio
 * VaR — no "diversification residual" bucket to argue over. The three
 * numbers answer three different questions:</p>
 * <ul>
 *   <li><b>component VaR</b> — how much of today's risk this position
 *       owns (the risk-budget number);</li>
 *   <li><b>marginal VaR</b> — how fast VaR moves per unit added (the
 *       "should the next dollar go here?" number);</li>
 *   <li><b>incremental VaR</b> — how much VaR disappears if the position
 *       is CLOSED entirely (a full re-computation without it — NOT
 *       component VaR, and the difference is the whole point: a large
 *       position that hedges the book has POSITIVE size, NEGATIVE
 *       component, and closing it RAISES VaR).</li>
 * </ul>
 *
 * <p>Delta-normal only, stated: allocations inherit the model's
 * assumptions (linear positions, normal returns). For the portfolio-level
 * numbers under fatter models, see {@link VarEngine}; the Euler split of
 * a Monte Carlo ES is a different (kernel-estimation) exercise this class
 * does not pretend to do. Sign convention: VaR is reported positive;
 * components carry sign (a hedge's component is negative). Research
 * lane, deterministic.</p>
 */
public final class ComponentVar {

    /**
     * @param portfolioVar total delta-normal VaR (positive)
     * @param components   per-position component VaR; sums exactly to
     *                     {@code portfolioVar}; hedges are negative
     * @param marginals    per-position marginal VaR (z · (Σw)_i / σ_p)
     */
    public record Allocation(double portfolioVar, double[] components, double[] marginals) {
    }

    private ComponentVar() {
    }

    /**
     * Euler allocation of delta-normal VaR.
     *
     * @param weights    position exposures (currency units), signed
     * @param covariance return covariance matrix, symmetric, n×n
     * @param confidence e.g. 0.99; z = &Phi;⁻¹(confidence)
     */
    public static Allocation allocate(double[] weights, double[][] covariance, double confidence) {
        int n = weights.length;
        if (n == 0 || covariance.length != n) {
            throw new IllegalArgumentException(
                    "weights (" + n + ") and covariance (" + covariance.length + ") must align");
        }
        if (!(confidence > 0.5) || !(confidence < 1)) {
            throw new IllegalArgumentException("confidence must be in (0.5, 1), got " + confidence);
        }
        for (int i = 0; i < n; i++) {
            if (!Double.isFinite(weights[i])) {
                throw new IllegalArgumentException("non-finite weight at " + i);
            }
            if (covariance[i].length != n) {
                throw new IllegalArgumentException("covariance row " + i + " is not length " + n);
            }
            for (int j = 0; j < n; j++) {
                if (!Double.isFinite(covariance[i][j])) {
                    throw new IllegalArgumentException("non-finite covariance at " + i + "," + j);
                }
            }
        }
        double[] sw = MathUtils.matVec(covariance, weights);
        double variance = MathUtils.dot(weights, sw);
        if (!(variance > 0)) {
            throw new IllegalArgumentException(
                    "portfolio variance must be > 0 (flat or perfectly hedged book), got " + variance);
        }
        double sigma = Math.sqrt(variance);
        double z = MathUtils.normInv(confidence);
        double[] components = new double[n];
        double[] marginals = new double[n];
        for (int i = 0; i < n; i++) {
            marginals[i] = z * sw[i] / sigma;
            components[i] = weights[i] * marginals[i];
        }
        return new Allocation(z * sigma, components, marginals);
    }

    /**
     * Incremental VaR of position {@code i}: portfolio VaR now minus VaR
     * with the position closed (weight zeroed). Positive means closing it
     * REDUCES risk; negative means the position is a hedge and closing it
     * raises VaR.
     */
    public static double incremental(double[] weights, double[][] covariance,
                                     double confidence, int i) {
        if (i < 0 || i >= weights.length) {
            throw new IllegalArgumentException("position index " + i + " out of range");
        }
        double full = allocate(weights, covariance, confidence).portfolioVar();
        double[] without = weights.clone();
        without[i] = 0;
        double sw = MathUtils.quadraticForm(without, covariance);
        // A book that is flat without this position has zero remaining VaR.
        double rest = sw > 0 ? MathUtils.normInv(confidence) * Math.sqrt(sw) : 0;
        return full - rest;
    }
}
