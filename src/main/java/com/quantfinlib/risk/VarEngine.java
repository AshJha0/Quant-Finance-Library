package com.quantfinlib.risk;

import com.quantfinlib.util.MathUtils;

import java.util.Random;

/**
 * Portfolio Value-at-Risk, all four classic flavors over one input
 * shape: factor EXPOSURES (currency P&amp;L per unit factor return — a
 * delta vector) against a factor covariance matrix or a factor-return
 * history. {@code RiskMetrics} answers the single-series question; this
 * engine answers the PORTFOLIO one, where the methods genuinely
 * disagree and the disagreement is the point:
 *
 * <ul>
 *   <li><b>Delta-normal (variance-covariance)</b> — σ_P = √(δ'Σδ),
 *       VaR = z·σ_P. Instant, and exactly wrong for optionality;</li>
 *   <li><b>Monte Carlo</b> — Cholesky-correlated Gaussian factor draws
 *       through the linear map; converges to delta-normal for a linear
 *       book (the tests pin that agreement), and exists so the same
 *       harness can price non-linear books by full revaluation;</li>
 *   <li><b>Delta-gamma (Cornish-Fisher)</b> — second-order P&amp;L
 *       {@code δ'Δx + ½Δx'ΓΔx} whose skew tilts the quantile via the
 *       Cornish-Fisher expansion: a short-gamma book's VaR is WORSE
 *       than delta-normal says, a long-gamma book's better — the
 *       asymmetry delta-normal cannot see;</li>
 *   <li><b>Historical</b> — replay actual factor-return rows through
 *       the exposures; no distributional assumption, no correlation
 *       matrix, exactly as fat-tailed as the sample was.</li>
 * </ul>
 *
 * <p>Conventions: VaR and ES are returned as POSITIVE losses in
 * currency units; confidence is the one-sided level (0.99 = 99%);
 * factor returns and Σ are per-horizon (scale √t outside). Expected
 * shortfall accompanies each method — post-FRTB, ES is the primary
 * number and VaR the diagnostic. Research lane, deterministic (MC per
 * seed).</p>
 */
public final class VarEngine {

    private VarEngine() {
    }

    // ------------------------------------------------------------------
    // Delta-normal
    // ------------------------------------------------------------------

    /** Portfolio stdev √(δ'Σδ) in currency units. */
    public static double portfolioStdev(double[] exposures, double[][] covariance) {
        int n = requireSquare(exposures, covariance);
        double variance = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                variance += exposures[i] * covariance[i][j] * exposures[j];
            }
        }
        return Math.sqrt(Math.max(variance, 0));
    }

    /** Delta-normal VaR: z-quantile of the Gaussian portfolio P&amp;L. */
    public static double deltaNormalVar(double[] exposures, double[][] covariance,
                                        double confidence) {
        return zOf(confidence) * portfolioStdev(exposures, covariance);
    }

    /** Delta-normal ES: the Gaussian tail mean, σ·φ(z)/(1−c). */
    public static double deltaNormalEs(double[] exposures, double[][] covariance,
                                       double confidence) {
        double z = zOf(confidence);
        return portfolioStdev(exposures, covariance)
                * MathUtils.normPdf(z) / (1 - confidence);
    }

    // ------------------------------------------------------------------
    // Monte Carlo (linear book; the harness full revaluation plugs into)
    // ------------------------------------------------------------------

    /** VaR and ES from Gaussian Monte Carlo factor scenarios. */
    public record VarResult(double var, double expectedShortfall) {
    }

    public static VarResult monteCarloVar(double[] exposures, double[][] covariance,
                                          double confidence, int scenarios, long seed) {
        int n = requireSquare(exposures, covariance);
        if (scenarios < 100) {
            throw new IllegalArgumentException("need >= 100 scenarios");
        }
        double[][] chol = GaussianCopula.cholesky(covariance);
        Random rnd = new Random(seed);
        double[] z = new double[n];
        double[] losses = new double[scenarios];
        for (int s = 0; s < scenarios; s++) {
            for (int i = 0; i < n; i++) {
                z[i] = rnd.nextGaussian();
            }
            double pnl = 0;
            for (int i = 0; i < n; i++) {
                double shock = 0;
                for (int j = 0; j <= i; j++) {
                    shock += chol[i][j] * z[j];
                }
                pnl += exposures[i] * shock;
            }
            losses[s] = -pnl;
        }
        return tail(losses, confidence);
    }

    // ------------------------------------------------------------------
    // Delta-gamma (Cornish-Fisher)
    // ------------------------------------------------------------------

    /**
     * Second-order VaR via the Cornish-Fisher quantile of the
     * delta-gamma P&amp;L. Moments of {@code δ'Δx + ½Δx'ΓΔx} under
     * Gaussian factors: mean {@code ½tr(ΓΣ)}, variance
     * {@code δ'Σδ + ½tr((ΓΣ)²)}, and the skew that moves the quantile.
     * Accurate for MODERATE gamma — the expansion degrades when the
     * quadratic term dominates (skew beyond ~1), which is when full
     * revaluation Monte Carlo earns its cost; that boundary is the
     * documented limit, not a hidden one.
     */
    public static double deltaGammaVar(double[] exposures, double[][] gamma,
                                       double[][] covariance, double confidence) {
        Cumulants c = deltaGammaCumulants(exposures, gamma, covariance);
        if (c.variance() <= 0) {
            return 0;
        }
        double z = zOf(confidence);
        // Cornish-Fisher: the LOSS quantile uses -z (left tail of P&L).
        double zCf = -z + (z * z - 1) * c.skew() / 6;
        double pnlQuantile = c.mean() + zCf * Math.sqrt(c.variance());
        return Math.max(0, -pnlQuantile);
    }

    /**
     * Second-order ES: the tail mean of the Cornish-Fisher loss
     * quantile, integrated in CLOSED FORM. With loss quantile
     * {@code q(p) = −μ + σ·(z_p + (z_p²−1)·s/6)} (s = loss skew), the
     * identities {@code E[Z·1{Z>z}] = φ(z)} and
     * {@code E[(Z²−1)·1{Z>z}] = z·φ(z)} give
     * {@code ES = −μ + σ·φ(z)/(1−c)·(1 + z·s/6)} — no numerical
     * integration, and it reduces EXACTLY to {@link #deltaNormalEs}
     * when Γ = 0. Same moderate-gamma validity bound as
     * {@link #deltaGammaVar}.
     */
    public static double deltaGammaEs(double[] exposures, double[][] gamma,
                                      double[][] covariance, double confidence) {
        Cumulants c = deltaGammaCumulants(exposures, gamma, covariance);
        if (c.variance() <= 0) {
            return 0;
        }
        double z = zOf(confidence);
        double lossSkew = -c.skew();
        double es = -c.mean() + Math.sqrt(c.variance())
                * MathUtils.normPdf(z) / (1 - confidence) * (1 + z * lossSkew / 6);
        return Math.max(0, es);
    }

    private record Cumulants(double mean, double variance, double skew) {
    }

    /** Moments of {@code δ'Δx + ½Δx'ΓΔx} under Gaussian factors. */
    private static Cumulants deltaGammaCumulants(double[] exposures, double[][] gamma,
                                                 double[][] covariance) {
        int n = requireSquare(exposures, covariance);
        if (gamma.length != n) {
            throw new IllegalArgumentException("gamma must match exposures");
        }
        double[][] gs = multiply(gamma, covariance, n);
        double mean = 0.5 * trace(gs, n);
        double linVar = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                linVar += exposures[i] * covariance[i][j] * exposures[j];
            }
        }
        double[][] gs2 = multiply(gs, gs, n);
        double variance = linVar + 0.5 * trace(gs2, n);
        if (variance <= 0) {
            return new Cumulants(mean, variance, 0);
        }
        // Third cumulant: 3δ'ΣΓΣδ + tr((ΓΣ)³).
        double[] sd = new double[n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                sd[i] += covariance[i][j] * exposures[j];
            }
        }
        double dgd = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                dgd += sd[i] * gamma[i][j] * sd[j];
            }
        }
        double[][] gs3 = multiply(gs2, gs, n);
        double kappa3 = 3 * dgd + trace(gs3, n);
        return new Cumulants(mean, variance, kappa3 / Math.pow(variance, 1.5));
    }

    // ------------------------------------------------------------------
    // Historical
    // ------------------------------------------------------------------

    /**
     * Historical simulation: each row of {@code factorReturns} is one
     * scenario replayed through the exposures. No assumptions — and no
     * more tail than the window actually contained.
     */
    public static VarResult historicalVar(double[] exposures, double[][] factorReturns,
                                          double confidence) {
        if (factorReturns.length < 20) {
            throw new IllegalArgumentException("need >= 20 scenarios");
        }
        double[] losses = new double[factorReturns.length];
        for (int s = 0; s < factorReturns.length; s++) {
            if (factorReturns[s].length != exposures.length) {
                throw new IllegalArgumentException("scenario " + s + " width mismatch");
            }
            double pnl = 0;
            for (int i = 0; i < exposures.length; i++) {
                pnl += exposures[i] * factorReturns[s][i];
            }
            losses[s] = -pnl;
        }
        return tail(losses, confidence);
    }

    // ------------------------------------------------------------------
    // Full revaluation
    // ------------------------------------------------------------------

    /** Revalues the book under one scenario's factor moves. */
    @FunctionalInterface
    public interface ScenarioReval {
        /** @return the book's P&amp;L under these factor moves (finite) */
        double pnl(double[] factorMoves);
    }

    /**
     * Full-revaluation VaR: every scenario repriced through the
     * CALLER'S pricer — the method that sees what every sensitivity
     * shortcut misses (a knocked-out barrier, a pinned short gamma, an
     * autocall triggered by the scenario itself). Scenarios are rows of
     * factor moves — historical rows for historical full-reval,
     * Cholesky-generated rows for Monte Carlo full-reval. A pricer
     * returning NaN/Infinity throws: a scenario your pricer cannot
     * price is a modelling problem, not a quantile.
     */
    public static VarResult fullRevaluationVar(double[][] scenarios, ScenarioReval pricer,
                                               double confidence) {
        if (scenarios.length < 20) {
            throw new IllegalArgumentException("need >= 20 scenarios");
        }
        double[] losses = new double[scenarios.length];
        for (int s = 0; s < scenarios.length; s++) {
            double pnl = pricer.pnl(scenarios[s]);
            if (!Double.isFinite(pnl)) {
                throw new IllegalArgumentException(
                        "pricer returned " + pnl + " for scenario " + s
                                + " — a scenario the pricer cannot price is a "
                                + "modelling problem, not a quantile");
            }
            losses[s] = -pnl;
        }
        return tail(losses, confidence);
    }

    // ------------------------------------------------------------------
    // Shared tail arithmetic
    // ------------------------------------------------------------------

    /** VaR (quantile) and ES (tail mean) of a loss sample, positive = loss. */
    static VarResult tail(double[] losses, double confidence) {
        if (!(confidence > 0.5 && confidence < 1)) {
            throw new IllegalArgumentException("confidence must be in (0.5, 1)");
        }
        double[] sorted = losses.clone();
        java.util.Arrays.sort(sorted);
        int n = sorted.length;
        int index = Math.min(n - 1, (int) Math.ceil(confidence * n) - 1);
        double var = Math.max(0, sorted[index]);
        double sum = 0;
        int count = 0;
        for (int i = index; i < n; i++) {
            sum += sorted[i];
            count++;
        }
        return new VarResult(var, Math.max(0, sum / count));
    }

    private static double zOf(double confidence) {
        if (!(confidence > 0.5 && confidence < 1)) {
            throw new IllegalArgumentException("confidence must be in (0.5, 1)");
        }
        return MathUtils.normInv(confidence);
    }

    private static int requireSquare(double[] exposures, double[][] covariance) {
        int n = exposures.length;
        if (n < 1 || covariance.length != n) {
            throw new IllegalArgumentException("covariance must match exposures");
        }
        return n;
    }

    private static double[][] multiply(double[][] a, double[][] b, int n) {
        double[][] out = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < n; k++) {
                double aik = a[i][k];
                if (aik == 0) {
                    continue;
                }
                for (int j = 0; j < n; j++) {
                    out[i][j] += aik * b[k][j];
                }
            }
        }
        return out;
    }

    private static double trace(double[][] m, int n) {
        double t = 0;
        for (int i = 0; i < n; i++) {
            t += m[i][i];
        }
        return t;
    }
}
