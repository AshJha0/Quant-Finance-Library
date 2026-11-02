package com.quantfinlib.pricing;

import java.util.function.Function;

/**
 * Levenberg-Marquardt nonlinear least squares (Levenberg 1944; Marquardt
 * 1963, with Marquardt's diagonal scaling), forward-difference Jacobian.
 * Package-private helper for the smile calibrations; bounds are handled by
 * the callers through smooth reparametrisation.
 */
final class LeastSquares {

    private LeastSquares() {
    }

    /** Minimiser and its cost (sum of squared residuals). */
    static final class Solution {
        private final double[] point;
        private final double cost;

        Solution(double[] point, double cost) {
            this.point = point.clone();
            this.cost = cost;
        }

        double x(int i) {
            return point[i];
        }

        double cost() {
            return cost;
        }
    }

    static Solution minimise(Function<double[], double[]> residuals, double[] start, int maxIterations) {
        int n = start.length;
        double[] p = start.clone();
        double[] r = checked(residuals.apply(p));
        double cost = sumSq(r);
        double lambda = 1e-3;
        for (int iter = 0; iter < maxIterations; iter++) {
            int m = r.length;
            double[][] jac = new double[m][n];
            for (int j = 0; j < n; j++) {
                double h = 1e-7 * (1 + Math.abs(p[j]));
                double[] q = p.clone();
                q[j] += h;
                double[] rq = tryApply(residuals, q);
                if (rq == null) {
                    q[j] = p[j] - h;
                    rq = checked(residuals.apply(q));
                    h = -h;
                }
                for (int i = 0; i < m; i++) {
                    jac[i][j] = (rq[i] - r[i]) / h;
                }
            }
            double[][] jtj = new double[n][n];
            double[] jtr = new double[n];
            for (int i = 0; i < m; i++) {
                for (int a = 0; a < n; a++) {
                    jtr[a] += jac[i][a] * r[i];
                    for (int b = a; b < n; b++) {
                        jtj[a][b] += jac[i][a] * jac[i][b];
                    }
                }
            }
            for (int a = 0; a < n; a++) {
                for (int b = 0; b < a; b++) {
                    jtj[a][b] = jtj[b][a];
                }
            }
            boolean improved = false;
            while (lambda < 1e12) {
                double[][] lhs = new double[n][n];
                double[] rhs = new double[n];
                for (int a = 0; a < n; a++) {
                    System.arraycopy(jtj[a], 0, lhs[a], 0, n);
                    lhs[a][a] += lambda * Math.max(jtj[a][a], 1e-12);
                    rhs[a] = -jtr[a];
                }
                double[] step = solve(lhs, rhs);
                double[] trial = new double[n];
                for (int a = 0; a < n; a++) {
                    trial[a] = p[a] + step[a];
                }
                double[] rt = tryApply(residuals, trial);
                double ct = rt != null ? sumSq(rt) : Double.POSITIVE_INFINITY;
                if (ct < cost) {
                    double rel = (cost - ct) / Math.max(cost, 1e-300);
                    p = trial;
                    r = rt;
                    cost = ct;
                    lambda = Math.max(lambda / 3, 1e-12);
                    improved = true;
                    if (rel < 1e-14) {
                        return new Solution(p, cost);
                    }
                    break;
                }
                lambda *= 4;
            }
            if (!improved || cost == 0) {
                break;
            }
        }
        return new Solution(p, cost);
    }

    /** Residuals, or null when the point is outside the model's domain. */
    private static double[] tryApply(Function<double[], double[]> f, double[] x) {
        try {
            double[] r = f.apply(x);
            return finite(r) ? r : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static double[] checked(double[] r) {
        if (!finite(r)) {
            throw new IllegalArgumentException("non-finite residual at the starting point");
        }
        return r;
    }

    private static boolean finite(double[] r) {
        for (double v : r) {
            if (!Double.isFinite(v)) {
                return false;
            }
        }
        return true;
    }

    private static double sumSq(double[] r) {
        double s = 0;
        for (double v : r) {
            s += v * v;
        }
        return s;
    }

    /** Gaussian elimination with partial pivoting; lhs is positive definite here. */
    static double[] solve(double[][] a, double[] b) {
        int n = b.length;
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[piv][col])) {
                    piv = r;
                }
            }
            double[] t = a[col]; a[col] = a[piv]; a[piv] = t;
            double tb = b[col]; b[col] = b[piv]; b[piv] = tb;
            if (a[col][col] == 0) {
                throw new IllegalArgumentException("singular system");
            }
            for (int r = col + 1; r < n; r++) {
                double f = a[r][col] / a[col][col];
                b[r] -= f * b[col];
                for (int c = col; c < n; c++) {
                    a[r][c] -= f * a[col][c];
                }
            }
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double s = b[i];
            for (int j = i + 1; j < n; j++) {
                s -= a[i][j] * x[j];
            }
            x[i] = s / a[i][i];
        }
        return x;
    }
}
