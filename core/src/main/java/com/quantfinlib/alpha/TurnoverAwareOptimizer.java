package com.quantfinlib.alpha;

import com.quantfinlib.stats.LinearAlgebra;

/**
 * Turnover-penalised, constrained mean-variance optimiser solved by ADMM.
 *
 * <pre>
 *   maximise   αᵀw − λ·wᵀΣw − κ·‖w − w₀‖₁
 *   subject to A w = 0                       (dollar, sector, beta neutrality ...)
 *              −cap_i ≤ w_i ≤ cap_i          (position limits)
 *              |w_i − w₀,i| ≤ tradeLimit_i   (liquidity, for example a %ADV cap)
 * </pre>
 *
 * <p>{@code w₀} is the book currently held (drifted to today's prices), so
 * turnover becomes a controlled input instead of a measured outcome: the L1
 * term charges {@code κ} per unit traded, and a name only moves when its
 * marginal alpha net of risk exceeds {@code κ}. With linear costs in bps,
 * {@code κ} is the one-way cost as a fraction (10 bps = 0.001) times
 * whatever scaling the alpha vector carries.</p>
 *
 * <p>Algorithm (Boyd, Parikh, Chu, Peleato and Eckstein 2011, Foundations
 * and Trends in ML 3(1), §5.2, scaled form): split {@code w = z} with the
 * smooth part and the equality constraints on {@code w} and the L1 term and
 * boxes on {@code z}.</p>
 * <ul>
 *   <li>w-step: the equality-constrained quadratic
 *       {@code [2λΣ + ρI, Aᵀ; A, 0][w; ν] = [α + ρ(z − u); 0]}, whose matrix
 *       is inverted once;</li>
 *   <li>z-step: per coordinate, soft-threshold {@code v = w + u} around
 *       {@code w₀} by {@code κ/ρ}, then clip to the intersection of the
 *       position and trade boxes (the exact minimiser of a one-dimensional
 *       convex function on an interval);</li>
 *   <li>{@code u ← u + w − z}.</li>
 * </ul>
 * <p>Stops when the primal residual {@code ‖w − z‖} and the dual residual
 * {@code ρ‖z − z_prev‖} are both below {@code tolerance·√n}. The returned
 * weights are {@code z}: they satisfy the boxes exactly and the equality
 * constraints to within the reported residual. If the boxes and the
 * equalities have no common point the iteration does not converge, and
 * {@link Result#converged()} is false.</p>
 */
public final class TurnoverAwareOptimizer {

    /**
     * Optimiser settings.
     *
     * @param riskAversion     λ &gt; 0
     * @param turnoverPenalty  κ &ge; 0, per unit of |Δw|
     * @param rho              ADMM penalty parameter &gt; 0 (1 is a reasonable start
     *                         when α and Σ are on comparable scales)
     * @param maxIterations    iteration cap
     * @param tolerance        per-coordinate residual tolerance
     */
    public record Settings(double riskAversion, double turnoverPenalty, double rho,
                           int maxIterations, double tolerance) {

        public Settings {
            if (!(riskAversion > 0) || turnoverPenalty < 0 || !(rho > 0)
                    || maxIterations < 1 || !(tolerance > 0)) {
                throw new IllegalArgumentException("invalid optimiser settings");
            }
        }

        public static Settings of(double riskAversion, double turnoverPenalty) {
            return new Settings(riskAversion, turnoverPenalty, 1.0, 20_000, 1e-9);
        }
    }

    public static final class Result {
        private final double[] weights;
        private final double turnover;
        private final double objective;
        private final double equalityResidual;
        private final int iterations;
        private final boolean converged;

        /**
         * Creates the value; array and collection arguments are copied.
         *
         * @param weights optimal book
         * @param turnover {@code ½‖w − w₀‖₁}
         * @param objective {@code αᵀw − λwᵀΣw − κ‖w − w₀‖₁}
         * @param equalityResidual {@code max_k |(A w)_k|}
         */
        public Result(double[] weights, double turnover, double objective, double equalityResidual,
                int iterations, boolean converged) {
            this.weights = weights == null ? null : weights.clone();
            this.turnover = turnover;
            this.objective = objective;
            this.equalityResidual = equalityResidual;
            this.iterations = iterations;
            this.converged = converged;
        }

        /** Element {@code i} of {@code weights} (optimal book). */
        public double weights(int i) {
            return weights[i];
        }

        /** Length of {@code weights}. */
        public int weightsLength() {
            return weights.length;
        }

        /** A fresh copy of {@code weights}. */
        public double[] weightsCopy() {
            return weights == null ? null : weights.clone();
        }

        /** {@code turnover}. */
        public double turnover() {
            return turnover;
        }

        /** {@code objective}. */
        public double objective() {
            return objective;
        }

        /** {@code equalityResidual}. */
        public double equalityResidual() {
            return equalityResidual;
        }

        /** {@code iterations}. */
        public int iterations() {
            return iterations;
        }

        /** {@code converged}. */
        public boolean converged() {
            return converged;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Result o)) {
                return false;
            }
            return java.util.Arrays.equals(weights, o.weights)
                    && Double.compare(turnover, o.turnover) == 0
                    && Double.compare(objective, o.objective) == 0
                    && Double.compare(equalityResidual, o.equalityResidual) == 0
                    && iterations == o.iterations
                    && converged == o.converged;
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Arrays.hashCode(weights);
            h = 31 * h + Double.hashCode(turnover);
            h = 31 * h + Double.hashCode(objective);
            h = 31 * h + Double.hashCode(equalityResidual);
            h = 31 * h + Integer.hashCode(iterations);
            h = 31 * h + Boolean.hashCode(converged);
            return h;
        }

        @Override
        public String toString() {
            return "Result[" + "weights=" + java.util.Arrays.toString(weights) + ", " + "turnover=" + turnover + ", " + "objective=" + objective + ", " + "equalityResidual=" + equalityResidual + ", " + "iterations=" + iterations + ", " + "converged=" + converged + "]";
        }
    }

    private TurnoverAwareOptimizer() {
    }

    /**
     * Solves the turnover-penalised mean-variance problem.
     *
     * @param alpha       expected returns per name (no NaN; exclude names with cap 0)
     * @param covariance  per-period covariance, symmetric positive semidefinite
     * @param previous    current book {@code w₀} (zeros for a fresh start)
     * @param equality    rows of A (may be empty), each aligned with alpha
     * @param caps        per-name |w| limit (≥ 0)
     * @param tradeLimits per-name |Δw| limit (≥ 0; {@code Double.POSITIVE_INFINITY} = none)
     */
    public static Result optimize(double[] alpha, double[][] covariance, double[] previous,
                                  double[][] equality, double[] caps, double[] tradeLimits,
                                  Settings settings) {
        int n = alpha.length;
        if (covariance.length != n || previous.length != n || caps.length != n
                || tradeLimits.length != n) {
            throw new IllegalArgumentException("inputs must align");
        }
        double[] lo = new double[n];
        double[] hi = new double[n];
        for (int i = 0; i < n; i++) {
            if (!Double.isFinite(alpha[i]) || !Double.isFinite(previous[i])) {
                throw new IllegalArgumentException("alpha and previous must be finite at " + i);
            }
            if (caps[i] < 0 || tradeLimits[i] < 0 || Double.isNaN(caps[i])
                    || Double.isNaN(tradeLimits[i])) {
                throw new IllegalArgumentException("caps and trade limits must be >= 0 at " + i);
            }
            lo[i] = Math.max(-caps[i], previous[i] - tradeLimits[i]);
            hi[i] = Math.min(caps[i], previous[i] + tradeLimits[i]);
            if (lo[i] > hi[i]) {
                throw new IllegalArgumentException("name " + i + " holds " + previous[i]
                        + ", outside its cap " + caps[i] + " by more than its trade limit "
                        + tradeLimits[i] + "; no feasible weight");
            }
        }
        int k = equality.length;
        for (double[] row : equality) {
            if (row.length != n) {
                throw new IllegalArgumentException("equality row must align with alpha");
            }
        }
        double lambda = settings.riskAversion();
        double rho = settings.rho();
        double kappa = settings.turnoverPenalty();
        int dim = n + k;
        double[][] kkt = new double[dim][dim];
        for (int i = 0; i < n; i++) {
            if (covariance[i].length != n) {
                throw new IllegalArgumentException("covariance must be n x n");
            }
            for (int j = 0; j < n; j++) {
                kkt[i][j] = 2 * lambda * covariance[i][j];
            }
            kkt[i][i] += rho;
        }
        for (int r = 0; r < k; r++) {
            for (int j = 0; j < n; j++) {
                kkt[n + r][j] = equality[r][j];
                kkt[j][n + r] = equality[r][j];
            }
        }
        double[][] inv;
        try {
            inv = LinearAlgebra.inverse(kkt);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("equality rows are linearly dependent; drop the "
                    + "redundant ones (" + e.getMessage() + ")", e);
        }
        double[] z = new double[n];
        for (int i = 0; i < n; i++) {
            z[i] = Math.max(lo[i], Math.min(hi[i], previous[i]));
        }
        double[] u = new double[n];
        double[] w = new double[n];
        double[] rhs = new double[dim];
        double tol = settings.tolerance() * Math.sqrt(n);
        double thresh = kappa / rho;
        int it = 0;
        boolean converged = false;
        while (it < settings.maxIterations()) {
            it++;
            for (int i = 0; i < n; i++) {
                rhs[i] = alpha[i] + rho * (z[i] - u[i]);
            }
            for (int i = 0; i < dim; i++) {
                double s = 0;
                for (int j = 0; j < dim; j++) {
                    s += inv[i][j] * rhs[j];
                }
                if (i < n) {
                    w[i] = s;
                }
            }
            double primal = 0;
            double dual = 0;
            for (int i = 0; i < n; i++) {
                double v = w[i] + u[i] - previous[i];
                double shrunk = Math.signum(v) * Math.max(0, Math.abs(v) - thresh);
                double zNew = Math.max(lo[i], Math.min(hi[i], previous[i] + shrunk));
                dual += (zNew - z[i]) * (zNew - z[i]);
                z[i] = zNew;
                u[i] += w[i] - z[i];
                primal += (w[i] - z[i]) * (w[i] - z[i]);
            }
            if (Math.sqrt(primal) < tol && rho * Math.sqrt(dual) < tol) {
                converged = true;
                break;
            }
        }
        double turnover = 0;
        double obj = 0;
        for (int i = 0; i < n; i++) {
            turnover += Math.abs(z[i] - previous[i]);
            obj += alpha[i] * z[i];
        }
        obj -= lambda * LinearAlgebra.quadraticForm(z, covariance) + kappa * turnover;
        double resid = 0;
        for (double[] row : equality) {
            resid = Math.max(resid, Math.abs(LinearAlgebra.dot(row, z)));
        }
        return new Result(z, turnover / 2, obj, resid, it, converged);
    }
}
