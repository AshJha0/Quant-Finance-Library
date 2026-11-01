package com.quantfinlib.optimization;

import java.util.function.ToDoubleFunction;

/**
 * Derivative-free minimisation by the Nelder-Mead simplex method with the
 * standard coefficients (reflection 1, expansion 2, contraction 1/2, shrink
 * 1/2), following Lagarias, Reeds, Wright and Wright, "Convergence
 * properties of the Nelder-Mead simplex method in low dimensions", SIAM J.
 * Optim. 9 (1998) 112-147.
 *
 * <p>Nelder-Mead can stall on a non-stationary point, so {@link #minimize}
 * restarts from the best vertex with a fresh simplex until a restart no
 * longer improves the objective by more than the tolerance. It is meant for
 * smooth problems in a handful of unconstrained variables (likelihoods after
 * a reparameterisation that maps the admissible region onto R^k). An
 * objective value of NaN or +Infinity is treated as "worse than any finite
 * value", which lets the objective reject inadmissible points.</p>
 */
public final class NelderMead {

    /** Result: argmin, minimum, objective evaluations used. */
    public static final class Result {
        private final double[] x;
        private final double value;
        private final int evaluations;

        /**
         * Creates the value; array and collection arguments are copied.
         */
        public Result(double[] x, double value, int evaluations) {
            this.x = x == null ? null : x.clone();
            this.value = value;
            this.evaluations = evaluations;
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

        /** {@code value}. */
        public double value() {
            return value;
        }

        /** {@code evaluations}. */
        public int evaluations() {
            return evaluations;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Result o)) {
                return false;
            }
            return java.util.Arrays.equals(x, o.x)
                    && Double.compare(value, o.value) == 0
                    && evaluations == o.evaluations;
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Arrays.hashCode(x);
            h = 31 * h + Double.hashCode(value);
            h = 31 * h + Integer.hashCode(evaluations);
            return h;
        }

        @Override
        public String toString() {
            return "Result[" + "x=" + java.util.Arrays.toString(x) + ", " + "value=" + value + ", " + "evaluations=" + evaluations + "]";
        }
    }

    private NelderMead() {
    }

    /**
     * Minimises {@code f} from {@code start}.
     *
     * @param f        objective; NaN or +Infinity marks an inadmissible point
     * @param start    starting point (length k &ge; 1), must give a finite value
     * @param step     initial simplex edge in every coordinate, &gt; 0
     * @param tol      convergence tolerance on the spread of simplex values,
     *                 relative to {@code 1 + |f|}, and on the restart gain
     * @param maxEvals evaluation budget
     * @throws IllegalArgumentException if {@code f(start)} is not finite
     * @throws IllegalStateException    if the budget runs out before convergence
     */
    public static Result minimize(ToDoubleFunction<double[]> f, double[] start, double step,
                                  double tol, int maxEvals) {
        int k = start.length;
        if (k < 1 || !(step > 0) || !(tol > 0) || maxEvals < 1) {
            throw new IllegalArgumentException("need k >= 1, step > 0, tol > 0, maxEvals >= 1");
        }
        int[] evals = {0};
        ToDoubleFunction<double[]> g = x -> {
            evals[0]++;
            double v = f.applyAsDouble(x);
            return Double.isNaN(v) ? Double.POSITIVE_INFINITY : v;
        };
        double[] best = start.clone();
        double bestVal = g.applyAsDouble(best);
        if (!Double.isFinite(bestVal)) {
            throw new IllegalArgumentException("objective is not finite at the start point");
        }
        double s = step;
        for (int restart = 0; restart < 50; restart++) {
            Result r = run(g, best, s, tol, maxEvals, evals);
            double gain = bestVal - r.value();
            if (r.value() <= bestVal) {
                best = r.xCopy();
                bestVal = r.value();
            }
            if (gain <= tol * (1 + Math.abs(bestVal))) {
                return new Result(best, bestVal, evals[0]);
            }
            s = Math.max(step * 1e-3, s * 0.5);
        }
        return new Result(best, bestVal, evals[0]);
    }

    private static Result run(ToDoubleFunction<double[]> g, double[] start, double step,
                              double tol, int maxEvals, int[] evals) {
        int k = start.length;
        double[][] p = new double[k + 1][];
        double[] fv = new double[k + 1];
        p[0] = start.clone();
        fv[0] = g.applyAsDouble(p[0]);
        for (int i = 0; i < k; i++) {
            double[] v = start.clone();
            v[i] += step;
            p[i + 1] = v;
            fv[i + 1] = g.applyAsDouble(v);
        }
        double[] centroid = new double[k];
        double[] xr = new double[k];
        double[] xe = new double[k];
        double[] xc = new double[k];
        while (true) {
            sort(p, fv);
            double spread = fv[k] - fv[0];
            double xSpread = 0;
            for (int i = 1; i <= k; i++) {
                for (int j = 0; j < k; j++) {
                    xSpread = Math.max(xSpread, Math.abs(p[i][j] - p[0][j]));
                }
            }
            if ((spread <= tol * (1 + Math.abs(fv[0])) && Double.isFinite(fv[k]))
                    || xSpread <= 1e-14) {
                return new Result(p[0].clone(), fv[0], evals[0]);
            }
            if (evals[0] >= maxEvals) {
                throw new IllegalStateException("Nelder-Mead did not converge in "
                        + maxEvals + " evaluations");
            }
            java.util.Arrays.fill(centroid, 0);
            for (int i = 0; i < k; i++) {
                for (int j = 0; j < k; j++) {
                    centroid[j] += p[i][j] / k;
                }
            }
            for (int j = 0; j < k; j++) {
                xr[j] = centroid[j] + (centroid[j] - p[k][j]);
            }
            double fr = g.applyAsDouble(xr);
            if (fr < fv[0]) {
                for (int j = 0; j < k; j++) {
                    xe[j] = centroid[j] + 2 * (centroid[j] - p[k][j]);
                }
                double fe = g.applyAsDouble(xe);
                if (fe < fr) {
                    p[k] = xe.clone();
                    fv[k] = fe;
                } else {
                    p[k] = xr.clone();
                    fv[k] = fr;
                }
                continue;
            }
            if (fr < fv[k - 1]) {
                p[k] = xr.clone();
                fv[k] = fr;
                continue;
            }
            boolean outside = fr < fv[k];
            for (int j = 0; j < k; j++) {
                xc[j] = outside
                        ? centroid[j] + 0.5 * (xr[j] - centroid[j])
                        : centroid[j] + 0.5 * (p[k][j] - centroid[j]);
            }
            double fc = g.applyAsDouble(xc);
            if (outside ? fc <= fr : fc < fv[k]) {
                p[k] = xc.clone();
                fv[k] = fc;
                continue;
            }
            for (int i = 1; i <= k; i++) {
                for (int j = 0; j < k; j++) {
                    p[i][j] = p[0][j] + 0.5 * (p[i][j] - p[0][j]);
                }
                fv[i] = g.applyAsDouble(p[i]);
            }
        }
    }

    private static void sort(double[][] p, double[] fv) {
        for (int i = 1; i < fv.length; i++) {
            double f = fv[i];
            double[] x = p[i];
            int j = i - 1;
            while (j >= 0 && fv[j] > f) {
                fv[j + 1] = fv[j];
                p[j + 1] = p[j];
                j--;
            }
            fv[j + 1] = f;
            p[j + 1] = x;
        }
    }
}
