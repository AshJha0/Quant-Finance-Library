package com.quantfinlib.risk;

/**
 * Stress testing and scenario analysis — the risk numbers VaR cannot
 * give you, because VaR is calibrated to the recent past and a stress
 * test deliberately is not. Three modes over one book representation
 * (factor exposures δ, optional gammas Γ):
 *
 * <ul>
 *   <li><b>Named scenarios</b> — a vector of factor shocks (fractions:
 *       −0.20 = down 20%) applied through the delta-gamma P&amp;L
 *       {@code δ'Δx + ½Δx'ΓΔx}. Ship-your-own scenarios, plus
 *       {@link #blackMonday1987}, {@link #lehman2008} and
 *       {@link #covidMarch2020} as STARTING TEMPLATES — stylized
 *       single-day shock magnitudes from the public record for a
 *       [equity, rates(bp/1e4), FX-USD, commodity, vol-points/1e2]
 *       factor ordering, documented as approximations to edit, not
 *       gospel to trust;</li>
 *   <li><b>Sensitivity ladders</b> — one factor swept over a shock
 *       range, everything else flat: the "what does ±X% do" table every
 *       risk report carries;</li>
 *   <li><b>Reverse stress</b> — the question regulators now ask first:
 *       "what move BREAKS us?" For a linear book under covariance Σ,
 *       the most-probable shock producing a target loss L has the
 *       closed form {@code Δx* = −(L/(δ'Σδ))·Σδ}: the worst direction
 *       is along Σδ, and its Mahalanobis distance {@code L/√(δ'Σδ)}
 *       says how implausible the breaking move is (in "sigmas") — a
 *       book broken by a 2σ move has a problem TODAY.</li>
 * </ul>
 *
 * <p>Losses are returned as negative P&amp;L (a scenario that makes
 * money reports positive). Research lane, deterministic, static.</p>
 */
public final class StressTester {

    private StressTester() {
    }

    /** Delta-only scenario P&amp;L: {@code δ'Δx}. */
    public static double scenarioPnl(double[] exposures, double[] shocks) {
        requireSameLength(exposures, shocks);
        requireFinite(exposures, "exposures");
        requireFinite(shocks, "shocks");
        double pnl = 0;
        for (int i = 0; i < exposures.length; i++) {
            pnl += exposures[i] * shocks[i];
        }
        return pnl;
    }

    /** Delta-gamma scenario P&amp;L: {@code δ'Δx + ½Δx'ΓΔx}. */
    public static double scenarioPnl(double[] exposures, double[][] gamma, double[] shocks) {
        double pnl = scenarioPnl(exposures, shocks);
        int n = exposures.length;
        if (gamma.length != n) {
            throw new IllegalArgumentException("gamma must match exposures");
        }
        for (double[] row : gamma) {
            requireFinite(row, "gamma");
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                pnl += 0.5 * shocks[i] * gamma[i][j] * shocks[j];
            }
        }
        return pnl;
    }

    /**
     * One factor swept over {@code [−range, +range]} in {@code steps}
     * increments, everything else flat — the sensitivity ladder.
     * Returns P&amp;L per rung, ascending shock. DELTA-ONLY: curvature is
     * ignored — a book carrying gamma needs the delta-gamma overload,
     * or the down rungs will look symmetric when they are not.
     */
    public static double[] sensitivityLadder(double[] exposures, int factor,
                                             double range, int steps) {
        if (factor < 0 || factor >= exposures.length || steps < 2
                || !(range > 0) || range == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("invalid ladder spec");
        }
        requireFinite(exposures, "exposures");
        double[] pnl = new double[steps + 1];
        for (int s = 0; s <= steps; s++) {
            double shock = -range + 2 * range * s / steps;
            pnl[s] = exposures[factor] * shock;
        }
        return pnl;
    }

    /**
     * The delta-gamma ladder: the same sweep with the swept factor's own
     * curvature {@code ½·Γ_ff·shock²} included — the rung table a
     * short-gamma book actually needs, since its down rungs are WORSE
     * than the linear ladder admits (cross-gammas stay out: the other
     * factors are flat by construction).
     */
    public static double[] sensitivityLadder(double[] exposures, double[][] gamma,
                                             int factor, double range, int steps) {
        if (gamma.length != exposures.length) {
            throw new IllegalArgumentException("gamma must match exposures");
        }
        for (double[] row : gamma) {
            requireFinite(row, "gamma");
        }
        double[] pnl = sensitivityLadder(exposures, factor, range, steps);
        for (int s = 0; s <= steps; s++) {
            double shock = -range + 2 * range * s / steps;
            pnl[s] += 0.5 * gamma[factor][factor] * shock * shock;
        }
        return pnl;
    }

    /** The reverse-stress answer: the most-probable shock vector and its distance. */
    public record ReverseStress(double[] shocks, double mahalanobisSigmas) {
    }

    /**
     * The most-probable factor move (under Gaussian factors with
     * covariance Σ) that loses exactly {@code targetLoss} on a linear
     * book — closed form, no search. The returned Mahalanobis distance
     * is the plausibility verdict: how many "joint sigmas" away the
     * breaking scenario sits.
     *
     * @param targetLoss positive loss to reverse-engineer, currency units
     */
    public static ReverseStress reverseStress(double[] exposures, double[][] covariance,
                                              double targetLoss) {
        if (!(targetLoss > 0) || targetLoss == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("targetLoss must be positive and finite");
        }
        double sigmaP = VarEngine.portfolioStdev(exposures, covariance);
        if (!(sigmaP > 0)) {
            throw new IllegalArgumentException(
                    "the book carries no factor risk — no finite move loses " + targetLoss);
        }
        int n = exposures.length;
        double[] sigmaDelta = new double[n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                sigmaDelta[i] += covariance[i][j] * exposures[j];
            }
        }
        double scale = -targetLoss / (sigmaP * sigmaP);   // δ'Σδ = σ_P²
        double[] shocks = new double[n];
        for (int i = 0; i < n; i++) {
            shocks[i] = scale * sigmaDelta[i];
        }
        return new ReverseStress(shocks, targetLoss / sigmaP);
    }

    // ------------------------------------------------------------------
    // Historical templates — STARTING POINTS, not certified replays.
    // Factor order: [equity, rates(Δr as a fraction, +50bp = +0.005),
    // FX (USD strength), commodity, vol (Δ vol points as a fraction)].
    // ------------------------------------------------------------------

    /** 1987-10-19 stylized: equities −20%, flight-to-quality rates, vol explosion. */
    public static double[] blackMonday1987() {
        return new double[]{-0.20, -0.0050, 0.02, -0.05, 0.20};
    }

    /** 2008-09-15 (Lehman week) stylized: −9% equities, −40bp, USD bid, oil down, vol +16pts. */
    public static double[] lehman2008() {
        return new double[]{-0.09, -0.0040, 0.04, -0.07, 0.16};
    }

    /** 2020-03-16 stylized: −12% equities, −30bp, USD squeeze, oil collapse, VIX ATH. */
    public static double[] covidMarch2020() {
        return new double[]{-0.12, -0.0030, 0.05, -0.15, 0.25};
    }

    private static void requireSameLength(double[] a, double[] b) {
        if (a.length != b.length || a.length < 1) {
            throw new IllegalArgumentException("exposures and shocks must align");
        }
    }

    private static void requireFinite(double[] a, String name) {
        for (double x : a) {
            if (!Double.isFinite(x)) {
                throw new IllegalArgumentException(name + " must be finite (one NaN "
                        + "exposure would print NaN for every scenario in the report)");
            }
        }
    }
}
