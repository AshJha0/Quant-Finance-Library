package com.quantfinlib.optimization;

import com.quantfinlib.util.MathUtils;

/**
 * Convex quadratic programming by the primal active-set method (Nocedal and
 * Wright, Numerical Optimization, 2nd ed., Algorithm 16.3):
 *
 * <pre>  minimise   ½·x'Qx + c'x
 *  subject to A·x = b,   G·x ≤ h</pre>
 *
 * with Q symmetric positive semi-definite. The method starts from a feasible
 * point supplied by the caller, keeps a working set of inequalities held as
 * equalities, solves the equality-constrained subproblem exactly through its
 * KKT system, steps to the nearest blocking constraint, and drops the
 * working constraint with the most negative multiplier until every
 * multiplier is non-negative. The result is optimal to rounding, not to a
 * sampling tolerance.
 *
 * <p>A ridge of {@code 1e-13·(1 + tr Q / n)} is added to the diagonal of Q so
 * that the KKT matrix stays nonsingular when Q is only semi-definite (a
 * rank-deficient covariance, or a variable that does not enter the
 * objective). Its effect on the solution is of the order of 1e-13 relative.
 * Dense linear algebra; meant for portfolio-sized problems (hundreds of
 * variables).</p>
 */
public final class QuadraticProgram {

    /** Solution, its objective value, and the active-set iterations used. */
    public static final class Solution {
        private final double[] x;
        private final double objective;
        private final int iterations;

        /**
         * Creates the value; array and collection arguments are copied.
         */
        public Solution(double[] x, double objective, int iterations) {
            this.x = x == null ? null : x.clone();
            this.objective = objective;
            this.iterations = iterations;
        }

        /** Element {@code i} of {@code x}. */
        public double x(int i) {
            return x[i];
        }

        /** Length of {@code x}. */
        public int xLength() {
            return x.length;
        }

        /** A fresh copy of {@code x}. */
        public double[] xCopy() {
            return x == null ? null : x.clone();
        }

        /** {@code objective}. */
        public double objective() {
            return objective;
        }

        /** {@code iterations}. */
        public int iterations() {
            return iterations;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Solution o)) {
                return false;
            }
            return java.util.Arrays.equals(x, o.x)
                    && Double.compare(objective, o.objective) == 0
                    && iterations == o.iterations;
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Arrays.hashCode(x);
            h = 31 * h + Double.hashCode(objective);
            h = 31 * h + Integer.hashCode(iterations);
            return h;
        }

        @Override
        public String toString() {
            return "Solution[" + "x=" + java.util.Arrays.toString(x) + ", " + "objective=" + objective + ", " + "iterations=" + iterations + "]";
        }
    }

    private QuadraticProgram() {
    }

    /**
     * @param q   n×n symmetric PSD
     * @param c   length n
     * @param a   m×n equality rows (m may be 0), linearly independent
     * @param b   length m
     * @param g   p×n inequality rows (p may be 0)
     * @param h   length p
     * @param x0  a feasible starting point (equalities to 1e-9, inequalities
     *            to 1e-9 relative)
     * @throws IllegalArgumentException if x0 is infeasible or inputs are malformed
     * @throws IllegalStateException    if the iteration limit is reached
     */
    public static Solution solve(double[][] q, double[] c, double[][] a, double[] b,
                                 double[][] g, double[] h, double[] x0) {
        int n = c.length;
        int m = b.length;
        int p = h.length;
        if (q.length != n || x0.length != n || a.length != m || g.length != p) {
            throw new IllegalArgumentException("dimension mismatch");
        }
        // Scale the objective to unit size: the argmin is unchanged and the
        // KKT pivots no longer depend on the units of Q (daily covariances
        // are of order 1e-4).
        double objScale = 0;
        for (int i = 0; i < n; i++) {
            if (q[i].length != n) {
                throw new IllegalArgumentException("Q must be square");
            }
            for (int j = 0; j < n; j++) {
                if (!Double.isFinite(q[i][j])) {
                    throw new IllegalArgumentException("Q must be finite");
                }
                objScale = Math.max(objScale, Math.abs(q[i][j]));
            }
            if (!Double.isFinite(c[i])) {
                throw new IllegalArgumentException("c must be finite");
            }
            objScale = Math.max(objScale, Math.abs(c[i]));
        }
        if (objScale == 0) {
            objScale = 1;
        }
        double[][] qOrig = q;
        double[] cOrig = c;
        q = new double[n][n];
        c = new double[n];
        double trace = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                q[i][j] = qOrig[i][j] / objScale;
            }
            c[i] = cOrig[i] / objScale;
            trace += q[i][i];
        }
        double ridge = 1e-13 * (1 + Math.abs(trace) / n);
        double[] x = x0.clone();
        double scaleX = 1;
        for (double v : x) {
            scaleX = Math.max(scaleX, Math.abs(v));
        }
        for (int i = 0; i < m; i++) {
            double r = dot(a[i], x) - b[i];
            if (Math.abs(r) > 1e-9 * (1 + Math.abs(b[i]) + norm(a[i]) * scaleX)) {
                throw new IllegalArgumentException("x0 violates equality " + i + " by " + r);
            }
        }
        for (int j = 0; j < p; j++) {
            double r = dot(g[j], x) - h[j];
            if (r > 1e-9 * (1 + Math.abs(h[j]) + norm(g[j]) * scaleX)) {
                throw new IllegalArgumentException("x0 violates inequality " + j + " by " + r);
            }
        }
        boolean[] working = new boolean[p];
        int maxIter = 50 * (n + p) + 100;
        for (int iter = 0; iter < maxIter; iter++) {
            int[] w = indices(working);
            int k = m + w.length;
            int dim = n + k;
            double[][] kkt = new double[dim][dim];
            double[] rhs = new double[dim];
            for (int i = 0; i < n; i++) {
                double gi = c[i];
                for (int j = 0; j < n; j++) {
                    kkt[i][j] = q[i][j];
                    gi += q[i][j] * x[j];
                }
                kkt[i][i] += ridge;
                gi += ridge * x[i];
                rhs[i] = -gi;
            }
            for (int r = 0; r < k; r++) {
                double[] row = r < m ? a[r] : g[w[r - m]];
                for (int j = 0; j < n; j++) {
                    kkt[n + r][j] = row[j];
                    kkt[j][n + r] = row[j];
                }
            }
            double[] sol = luSolve(kkt, rhs);
            double stepNorm = 0;
            for (int i = 0; i < n; i++) {
                stepNorm = Math.max(stepNorm, Math.abs(sol[i]));
            }
            if (stepNorm <= 1e-13 * Math.max(1, scaleX)) {
                // Stationary on the working set: check the multipliers.
                int drop = -1;
                double most = 0;
                double lamScale = 1;
                for (int i = 0; i < n; i++) {
                    lamScale = Math.max(lamScale, Math.abs(rhs[i]));
                }
                for (int r = 0; r < w.length; r++) {
                    double mu = sol[n + m + r];
                    if (mu < most - 1e-12 * lamScale) {
                        most = mu;
                        drop = w[r];
                    }
                }
                if (drop < 0) {
                    return new Solution(x, objective(qOrig, cOrig, x), iter);
                }
                working[drop] = false;
                continue;
            }
            // Step along p = sol[0..n) up to the first blocking constraint.
            double alpha = 1;
            int block = -1;
            for (int j = 0; j < p; j++) {
                if (working[j]) {
                    continue;
                }
                double gp = 0;
                for (int i = 0; i < n; i++) {
                    gp += g[j][i] * sol[i];
                }
                if (gp > 1e-15 * norm(g[j]) * stepNorm) {
                    double slack = h[j] - dot(g[j], x);
                    double t = Math.max(0, slack) / gp;
                    if (t < alpha) {
                        alpha = t;
                        block = j;
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                x[i] += alpha * sol[i];
            }
            if (block >= 0) {
                working[block] = true;
            }
            scaleX = 1;
            for (double v : x) {
                scaleX = Math.max(scaleX, Math.abs(v));
            }
        }
        throw new IllegalStateException("active-set QP did not converge in " + maxIter + " iterations");
    }

    /** Gaussian elimination with partial pivoting; fails only on an exactly singular pivot. */
    private static double[] luSolve(double[][] m, double[] rhs) {
        int n = rhs.length;
        double[] b = rhs.clone();
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) {
                    piv = r;
                }
            }
            if (m[piv][col] == 0 || !Double.isFinite(m[piv][col])) {
                throw new IllegalStateException("singular KKT system: constraints are dependent");
            }
            double[] t = m[col];
            m[col] = m[piv];
            m[piv] = t;
            double tb = b[col];
            b[col] = b[piv];
            b[piv] = tb;
            for (int r = col + 1; r < n; r++) {
                double f = m[r][col] / m[col][col];
                if (f == 0) {
                    continue;
                }
                b[r] -= f * b[col];
                for (int k = col; k < n; k++) {
                    m[r][k] -= f * m[col][k];
                }
            }
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double s = b[i];
            for (int j = i + 1; j < n; j++) {
                s -= m[i][j] * x[j];
            }
            x[i] = s / m[i][i];
        }
        return x;
    }

    private static double objective(double[][] q, double[] c, double[] x) {
        return 0.5 * MathUtils.quadraticForm(x, q) + dot(c, x);
    }

    private static int[] indices(boolean[] flags) {
        int count = 0;
        for (boolean f : flags) {
            if (f) {
                count++;
            }
        }
        int[] out = new int[count];
        int k = 0;
        for (int i = 0; i < flags.length; i++) {
            if (flags[i]) {
                out[k++] = i;
            }
        }
        return out;
    }

    private static double dot(double[] u, double[] v) {
        double s = 0;
        for (int i = 0; i < u.length; i++) {
            s += u[i] * v[i];
        }
        return s;
    }

    private static double norm(double[] u) {
        return Math.sqrt(dot(u, u));
    }
}
