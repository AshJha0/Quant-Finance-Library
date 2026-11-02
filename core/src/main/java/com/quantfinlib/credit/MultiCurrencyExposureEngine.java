package com.quantfinlib.credit;

import com.quantfinlib.credit.MultiCurrencyExposureSimulator.BasisFactor;
import com.quantfinlib.credit.MultiCurrencyExposureSimulator.Csa;
import com.quantfinlib.credit.MultiCurrencyExposureSimulator.Curve;
import com.quantfinlib.credit.MultiCurrencyExposureSimulator.Domestic;
import com.quantfinlib.credit.MultiCurrencyExposureSimulator.Foreign;
import com.quantfinlib.credit.MultiCurrencyExposureSimulator.ForeignSwap;
import com.quantfinlib.credit.MultiCurrencyExposureSimulator.FxForward;
import com.quantfinlib.credit.MultiCurrencyExposureSimulator.FxForwardTerms;
import com.quantfinlib.credit.MultiCurrencyExposureSimulator.Market;
import com.quantfinlib.credit.MultiCurrencyExposureSimulator.Swap;
import com.quantfinlib.credit.MultiCurrencyExposureSimulator.SwapTerms;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeSet;

/**
 * The Monte Carlo engine behind {@link MultiCurrencyExposureSimulator}:
 * Hull-White state for each currency, the joint Gaussian step covariance,
 * trade valuation on each path and the collateral lag. Package-private;
 * the public entry points and their argument checks stay on the facade.
 * Split out of the facade in 3.1 with no change in behaviour.
 */
final class MultiCurrencyExposureEngine {

    private MultiCurrencyExposureEngine() {
    }

    /** One Hull-White currency: curve, a, sigma. */
    private record Hw(Curve curve, double a, double sigma) {
        double b(double tau) {
            return -Math.expm1(-a * tau) / a;
        }

        double v(double tau) {
            if (sigma == 0) {
                return 0;
            }
            double b2 = -Math.expm1(-2 * a * tau) / (2 * a);
            return sigma * sigma / (a * a) * (tau - 2 * b(tau) + b2);
        }

        double p0(double t) {
            return t == 0 ? 1 : curve.discountFactor(t);
        }

        double bond(double t, double tMat, double x) {
            if (tMat <= t) {
                return 1;
            }
            double lnA = 0.5 * (v(tMat - t) - v(tMat) + v(t));
            return p0(tMat) / p0(t) * Math.exp(lnA - b(tMat - t) * x);
        }
    }

    /** A Gaussian state component: kernel c0 + c1 e^{-k s} on factor f. */
    private record Kernel(int factor, double c0, double c1, double k) {
    }

    private static double eInt(double k, double dt) {
        return k == 0 ? dt : -Math.expm1(-k * dt) / k;
    }

    static final class Engine {
        private final Hw dom;
        private final Hw[] fgn;
        private final double[] spot;
        private final double[] fxVol;
        private final double[] quanto;       // rho_{c,Sc} sigma_c sigma_Sc
        private final double[][] corr;
        private final SwapTerms[] swaps;
        private final Curve[] proj;
        private final double[][] swapDates;
        private final double[][] basis;       // k(S,T) per swap period
        private final FxForwardTerms[] fwds;
        private final int[] fwdPair;
        private final Csa csa;
        private final double[] times;
        private final double[] grid;
        private final int[] userIndex;
        private final int[] lagIndex;
        // state layout
        private final List<Kernel> kernels = new ArrayList<>();
        private final int[] sIdx;   // per pair, index of W_S, or -1 if not simulated
        private final int[] yIdx;   // per pair, index of y_c (I_c is +1), or -1
        private final int wwPair;
        private final int[] swapCcy;        // -1 domestic, else pair index
        private final boolean[] stochBasis;  // domestic projected swap under the basis factor
        private final int bIdx;             // index of y_b, or -1
        private final double basA;
        private final double basSig;
        private final double basRho;        // corr(W_d, W_b)

        Engine(Market mk, List<Swap> domSw, List<ForeignSwap> fSw, List<FxForward> fw, BasisFactor bf,
               Csa csa, double[] times, int wwPair) {
            Domestic d = mk.domestic();
            dom = new Hw(d.discount(), d.meanReversion(), d.rateVol());
            int n = mk.foreign().size();
            fgn = new Hw[n];
            spot = new double[n];
            fxVol = new double[n];
            quanto = new double[n];
            boolean withBasis = bf != null && bf.vol() > 0;
            double[][] mc = mk.correlation();
            if (withBasis) {
                int nf0 = mc.length;
                double[] rb = bf.correlation();
                corr = new double[nf0 + 1][nf0 + 1];
                for (int i = 0; i < nf0; i++) {
                    System.arraycopy(mc[i], 0, corr[i], 0, nf0);
                    corr[i][nf0] = rb[i];
                    corr[nf0][i] = rb[i];
                }
                corr[nf0][nf0] = 1;
                basA = bf.meanReversion();
                basSig = bf.vol();
                basRho = rb[0];
            } else {
                corr = mc;
                basA = 1;
                basSig = 0;
                basRho = 0;
            }
            List<Swap> sw = new ArrayList<>(domSw);
            int nDom = domSw.size();
            for (ForeignSwap f : fSw) {
                sw.add(new Swap(f.terms(), f.projection()));
            }
            swapCcy = new int[sw.size()];
            stochBasis = new boolean[sw.size()];
            for (int k = 0; k < sw.size(); k++) {
                swapCcy[k] = k < nDom ? -1 : fSw.get(k - nDom).pair();
                stochBasis[k] = withBasis && k < nDom && sw.get(k).projection() != null;
            }
            for (int c = 0; c < n; c++) {
                Foreign f = mk.foreign().get(c);
                fgn[c] = new Hw(f.discount(), f.meanReversion(), f.rateVol());
                spot[c] = f.spot();
                fxVol[c] = f.fxVol();
                quanto[c] = corr[1 + 2 * c][2 + 2 * c] * f.rateVol() * f.fxVol();
            }
            this.csa = csa;
            this.times = times;
            this.wwPair = wwPair;
            swaps = new SwapTerms[sw.size()];
            proj = new Curve[sw.size()];
            swapDates = new double[sw.size()][];
            basis = new double[sw.size()][];
            for (int k = 0; k < swaps.length; k++) {
                swaps[k] = sw.get(k).terms();
                proj[k] = sw.get(k).projection();
                swapDates[k] = swaps[k].dates();
                int np = swapDates[k].length - 1;
                basis[k] = new double[np];
                for (int j = 0; j < np; j++) {
                    if (proj[k] == null) {
                        basis[k][j] = 1;
                    } else {
                        double s = swapDates[k][j], e = swapDates[k][j + 1];
                        double pfs = s == 0 ? 1 : proj[k].discountFactor(s);
                        double pfe = proj[k].discountFactor(e);
                        Hw h = hw(k);
                        basis[k][j] = (pfs / pfe) / (h.p0(s) / h.p0(e));
                    }
                }
            }
            fwds = new FxForwardTerms[fw.size()];
            fwdPair = new int[fw.size()];
            boolean[] used = new boolean[n];
            for (int i = 0; i < fwds.length; i++) {
                fwds[i] = fw.get(i).terms();
                fwdPair[i] = fw.get(i).pair();
                used[fwdPair[i]] = true;
            }
            for (int k = 0; k < swapCcy.length; k++) {
                if (swapCcy[k] >= 0) {
                    used[swapCcy[k]] = true;
                }
            }
            // Domestic y, I, W first (the 2.1 draw order), then per used pair W_S, y_c, I_c.
            double a = dom.a(), s = dom.sigma();
            kernels.add(new Kernel(0, 0, s, a));
            kernels.add(new Kernel(0, s / a, -s / a, a));
            kernels.add(new Kernel(0, 1, 0, 0));
            sIdx = new int[n];
            yIdx = new int[n];
            Arrays.fill(sIdx, -1);
            Arrays.fill(yIdx, -1);
            for (int c = 0; c < n; c++) {
                if (!used[c]) {
                    continue;
                }
                sIdx[c] = kernels.size();
                kernels.add(new Kernel(2 + 2 * c, 1, 0, 0));
                if (fgn[c].sigma() > 0) {
                    double ac = fgn[c].a(), sc = fgn[c].sigma();
                    yIdx[c] = kernels.size();
                    kernels.add(new Kernel(1 + 2 * c, 0, sc, ac));
                    kernels.add(new Kernel(1 + 2 * c, sc / ac, -sc / ac, ac));
                }
            }
            if (withBasis) {
                int fb = corr.length - 1;
                bIdx = kernels.size();
                kernels.add(new Kernel(fb, 0, basSig, basA));
                kernels.add(new Kernel(fb, basSig / basA, -basSig / basA, basA));
            } else {
                bIdx = -1;
            }

            TreeSet<Double> set = new TreeSet<>();
            set.add(0.0);
            for (double t : times) {
                set.add(t);
            }
            for (double[] dd : swapDates) {
                for (int j = 0; j < dd.length - 1; j++) {
                    if (dd[j] <= times[times.length - 1]) {
                        set.add(dd[j]);
                    }
                }
            }
            double[] lag = new double[times.length];
            for (int i = 0; i < times.length; i++) {
                lag[i] = csa == null ? times[i] : Math.max(times[i] - csa.marginPeriodYears(), 0);
                set.add(lag[i]);
            }
            grid = new double[set.size()];
            int g = 0;
            for (double t : set) {
                grid[g++] = t;
            }
            userIndex = new int[times.length];
            lagIndex = new int[times.length];
            for (int i = 0; i < times.length; i++) {
                userIndex[i] = Arrays.binarySearch(grid, times[i]);
                lagIndex[i] = Arrays.binarySearch(grid, lag[i]);
            }
        }

        double[][] stepCov(double dt) {
            int n = kernels.size();
            double[][] cov = new double[n][n];
            for (int i = 0; i < n; i++) {
                Kernel p = kernels.get(i);
                for (int j = 0; j <= i; j++) {
                    Kernel q = kernels.get(j);
                    double r = corr[p.factor()][q.factor()];
                    double v = p.c0() * q.c0() * dt
                            + p.c0() * q.c1() * eInt(q.k(), dt)
                            + p.c1() * q.c0() * eInt(p.k(), dt)
                            + p.c1() * q.c1() * eInt(p.k() + q.k(), dt);
                    cov[i][j] = r * v;
                    cov[j][i] = r * v;
                }
            }
            return cov;
        }

        /** True foreign state x_c(t) and its integral, adding the quanto mean. */
        double fgnX(int c, double t, double y) {
            return y - quanto[c] * fgn[c].b(t);
        }

        double fgnIntX(int c, double t, double iy) {
            return iy - quanto[c] * (t - fgn[c].b(t)) / fgn[c].a();
        }

        Hw hw(int k) {
            return swapCcy[k] < 0 ? dom : fgn[swapCcy[k]];
        }

        /**
         * Log basis multiplier ln m(t) for a period fixing at s, with y = y_b(t), t &lt;= s.
         */
        double logBasis(double t, double s, double y) {
            double w1 = s - t, ad = dom.a();
            double f1 = fInt(basA, w1, s), f2 = fInt(basA + ad, w1, s), f3 = fInt(2 * basA, w1, s);
            double drift = basRho * basSig * dom.sigma() / ad * (f1 - f2) - 0.5 * basSig * basSig * f3;
            return Math.exp(-basA * w1) * y + drift;
        }

        private static double fInt(double k, double w1, double w2) {
            return (Math.exp(-k * w1) - Math.exp(-k * w2)) / k;
        }

        double value(double t, double xd, double[] fx, double[] xf, double[][] resetBond, double yb) {
            double v = 0;
            for (int k = 0; k < swaps.length; k++) {
                SwapTerms s = swaps[k];
                double[] d = swapDates[k];
                int n = d.length - 1;
                if (t >= d[n]) {
                    continue;
                }
                int ccy = swapCcy[k];
                Hw h = hw(k);
                double x = ccy < 0 ? xd : xf[ccy];
                double fixed = 0, flt = 0;
                for (int j = 1; j <= n; j++) {
                    if (d[j] <= t) {
                        continue;
                    }
                    double pe = h.bond(t, d[j], x);
                    fixed += (d[j] - d[j - 1]) * pe;
                    double kb = basis[k][j - 1];
                    if (d[j - 1] >= t) {
                        double mb = stochBasis[k] ? Math.exp(logBasis(t, d[j - 1], yb)) : 1;
                        flt += kb * mb * h.bond(t, d[j - 1], x) - pe;
                    } else {
                        // resetBond holds P(S,T) / m(S) for stochastic-basis periods
                        flt += (kb / resetBond[k][j - 1] - 1) * pe;
                    }
                }
                double pv = s.notional() * (flt - s.fixedRate() * fixed);
                if (ccy >= 0) {
                    pv *= fx[ccy];
                }
                v += s.payFixed() ? pv : -pv;
            }
            for (int i = 0; i < fwds.length; i++) {
                FxForwardTerms f = fwds[i];
                if (t >= f.maturityYears()) {
                    continue;
                }
                int c = fwdPair[i];
                double pfT = fgn[c].bond(t, f.maturityYears(), xf[c]);
                v += f.foreignNotional() * (fx[c] * pfT - f.strike() * dom.bond(t, f.maturityYears(), xd));
            }
            return v;
        }

        ExposureProfile run(int paths, long seed) {
            int m = times.length;
            int ng = grid.length;
            int nk = kernels.size();
            int nf = fgn.length;
            double[][][] chol = new double[ng][][];
            for (int g = 1; g < ng; g++) {
                chol[g] = cholesky(stepCov(grid[g] - grid[g - 1]));
            }
            // per-step decay / B for each OU component (y at index i, I at i + 1)
            double[][] decay = new double[ng][nk];
            double[][] bStep = new double[ng][nk];
            for (int g = 1; g < ng; g++) {
                double dt = grid[g] - grid[g - 1];
                for (int i = 0; i < nk; i++) {
                    double k = kernels.get(i).k();
                    decay[g][i] = Math.exp(-k * dt);
                    bStep[g][i] = eInt(k, dt);
                }
            }
            boolean[] isY = new boolean[nk];
            isY[0] = true;
            for (int c = 0; c < nf; c++) {
                if (yIdx[c] >= 0) {
                    isY[yIdx[c]] = true;
                }
            }
            if (bIdx >= 0) {
                isY[bIdx] = true;
            }

            double[][] discPos = new double[paths][m];
            double[][] discNeg = new double[paths][m];
            double[][] expo = new double[paths][m];
            double[][] driverRates = new double[paths][m];
            double[][] driverFx = new double[paths][m];
            double[] wrOnGrid = new double[ng];
            double[] wfxOnGrid = new double[ng];
            int wwIdx = wwPair < nf ? sIdx[wwPair] : -1;

            double[] fx0 = spot.clone();
            double[] xf = new double[nf];
            double v0 = value(0, 0, fx0, xf, initialResets(), 0);
            SplittableRandom rng = new SplittableRandom(seed);
            double[] vOnGrid = new double[ng];
            double[] dOnGrid = new double[ng];
            double[][] resets = new double[swaps.length][];
            for (int k = 0; k < swaps.length; k++) {
                resets[k] = new double[swapDates[k].length - 1];
            }
            int[][] resetAt = resetLookup();
            boolean[] needV = new boolean[ng];
            for (int i = 0; i < m; i++) {
                needV[userIndex[i]] = true;
                needV[lagIndex[i]] = true;
            }
            double[] st = new double[nk];
            double[] z = new double[nk];
            double[] fx = new double[nf];
            for (int p = 0; p < paths; p++) {
                Arrays.fill(st, 0);
                vOnGrid[0] = v0;
                dOnGrid[0] = 1;
                Arrays.fill(xf, 0);
                fillResets(resets, resetAt, 0, 0, xf, 0);
                for (int g = 1; g < ng; g++) {
                    for (int i = 0; i < nk; i++) {
                        z[i] = rng.nextGaussian();
                    }
                    double[][] c = chol[g];
                    for (int i = 0; i < nk; i++) {
                        if (isY[i]) {
                            // integral first (uses the old y), then y
                            double e1 = 0, e2 = 0;
                            for (int j = 0; j <= i; j++) {
                                e1 += c[i][j] * z[j];
                            }
                            for (int j = 0; j <= i + 1; j++) {
                                e2 += c[i + 1][j] * z[j];
                            }
                            st[i + 1] += st[i] * bStep[g][i] + e2;
                            st[i] = st[i] * decay[g][i] + e1;
                            i++;
                        } else {
                            double e = 0;
                            for (int j = 0; j <= i; j++) {
                                e += c[i][j] * z[j];
                            }
                            st[i] += e;
                        }
                    }
                    double t = grid[g];
                    wrOnGrid[g] = st[2];
                    wfxOnGrid[g] = wwIdx >= 0 ? st[wwIdx] : 0;
                    double disc = dom.p0(t) * Math.exp(-0.5 * dom.v(t) - st[1]);
                    dOnGrid[g] = disc;
                    double xd = st[0];
                    double yb = bIdx >= 0 ? st[bIdx] : 0;
                    for (int cc = 0; cc < nf; cc++) {
                        xf[cc] = yIdx[cc] >= 0 ? fgnX(cc, t, st[yIdx[cc]]) : 0;
                    }
                    fillResets(resets, resetAt, g, xd, xf, yb);
                    if (needV[g]) {
                        for (int cc = 0; cc < nf; cc++) {
                            if (sIdx[cc] < 0) {
                                continue;
                            }
                            double iy = yIdx[cc] >= 0 ? st[yIdx[cc] + 1] : 0;
                            Hw h = fgn[cc];
                            double ix = yIdx[cc] >= 0 ? fgnIntX(cc, t, iy) : 0;
                            double accF = h.p0(t) * Math.exp(-0.5 * h.v(t) - ix);
                            double sv = fxVol[cc];
                            fx[cc] = spot[cc] * accF / disc * Math.exp(-0.5 * sv * sv * t + sv * st[sIdx[cc]]);
                        }
                        vOnGrid[g] = value(t, xd, fx, xf, resets, yb);
                    }
                }
                double balance = 0;
                if (csa != null) {
                    double tgt = csa.target(v0);
                    balance = Math.abs(tgt) >= csa.minimumTransfer() ? tgt : 0;
                }
                for (int i = 0; i < m; i++) {
                    if (csa != null) {
                        double tgt = csa.target(vOnGrid[lagIndex[i]]);
                        if (Math.abs(tgt - balance) >= csa.minimumTransfer()) {
                            balance = tgt;
                        }
                    }
                    double net = vOnGrid[userIndex[i]] - balance;
                    double d = dOnGrid[userIndex[i]];
                    expo[p][i] = Math.max(net, 0);
                    discPos[p][i] = d * Math.max(net, 0);
                    discNeg[p][i] = d * Math.max(-net, 0);
                    int u = userIndex[i];
                    int l = csa == null || lagIndex[i] == u ? 0 : lagIndex[i];
                    double norm = 1 / Math.sqrt(times[i] - grid[l]);
                    driverRates[p][i] = (wrOnGrid[u] - wrOnGrid[l]) * norm;
                    driverFx[p][i] = wwIdx >= 0 ? (wfxOnGrid[u] - wfxOnGrid[l]) * norm : 0;
                }
            }
            double[] p0s = new double[m];
            for (int i = 0; i < m; i++) {
                p0s[i] = dom.p0(times[i]);
            }
            return new ExposureProfile(times, p0s, discPos, discNeg, expo, driverRates, driverFx);
        }

        private double[][] initialResets() {
            double[][] r = new double[swaps.length][];
            for (int k = 0; k < swaps.length; k++) {
                r[k] = new double[swapDates[k].length - 1];
                Arrays.fill(r[k], 1);
            }
            return r;
        }

        private int[][] resetLookup() {
            List<List<Integer>> lists = new ArrayList<>();
            for (int g = 0; g < grid.length; g++) {
                lists.add(new ArrayList<>());
            }
            for (int k = 0; k < swaps.length; k++) {
                for (int j = 0; j < swapDates[k].length - 1; j++) {
                    int g = Arrays.binarySearch(grid, swapDates[k][j]);
                    if (g < 0) {
                        continue;
                    }
                    lists.get(g).add(k);
                    lists.get(g).add(j);
                }
            }
            int[][] out = new int[grid.length][];
            for (int g = 0; g < grid.length; g++) {
                List<Integer> l = lists.get(g);
                out[g] = new int[l.size()];
                for (int i = 0; i < l.size(); i++) {
                    out[g][i] = l.get(i);
                }
            }
            return out;
        }

        private void fillResets(double[][] resets, int[][] resetAt, int g, double x, double[] xf, double yb) {
            int[] r = resetAt[g];
            for (int i = 0; i < r.length; i += 2) {
                int k = r[i], j = r[i + 1];
                double xs = swapCcy[k] < 0 ? x : xf[swapCcy[k]];
                double pb = hw(k).bond(grid[g], swapDates[k][j + 1], xs);
                if (stochBasis[k]) {
                    pb /= Math.exp(logBasis(grid[g], grid[g], yb));
                }
                resets[k][j] = pb;
            }
        }
    }

    /** Cholesky of a positive semi-definite matrix; zero pivots give zero columns. */
    static double[][] cholesky(double[][] c) {
        int n = c.length;
        double[][] l = new double[n][n];
        for (int j = 0; j < n; j++) {
            double d = c[j][j];
            for (int k = 0; k < j; k++) {
                d -= l[j][k] * l[j][k];
            }
            double scale = Math.max(Math.abs(c[j][j]), 1e-300);
            if (d <= 1e-13 * scale) {
                continue;
            }
            l[j][j] = Math.sqrt(d);
            for (int i = j + 1; i < n; i++) {
                double s = c[i][j];
                for (int k = 0; k < j; k++) {
                    s -= l[i][k] * l[j][k];
                }
                l[i][j] = s / l[j][j];
            }
        }
        return l;
    }
}
