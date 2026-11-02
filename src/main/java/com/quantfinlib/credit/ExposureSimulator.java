package com.quantfinlib.credit;

import com.quantfinlib.rates.YieldCurve;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.TreeSet;

/**
 * Monte Carlo exposure generator for one netting set of interest-rate
 * swaps and FX forwards, with an optional collateral agreement. The
 * output {@link ExposureProfile} feeds the profile overloads of
 * {@link CvaApproximator}.
 *
 * <p>Rates: Hull-White one-factor in the shifted form of Brigo and
 * Mercurio (Interest Rate Models, 2nd ed., 2006, section 3.3):
 * {@code r(t) = x(t) + phi(t)}, {@code dx = -a x dt + sigma dW_r},
 * {@code x(0) = 0}, phi fitted to the domestic {@link YieldCurve}. Bond
 * prices are closed form (their eq. 3.39):</p>
 *
 * <pre>
 *   P(t,T) = P(0,T)/P(0,t) * exp( (V(t,T) - V(0,T) + V(0,t)) / 2 - B(t,T) x(t) )
 *   B(t,T) = (1 - e^{-a (T-t)}) / a
 *   V(t,T) = sigma^2/a^2 [ tau - 2 B(tau) + (1 - e^{-2 a tau}) / (2a) ],  tau = T - t
 * </pre>
 *
 * <p>The state (x, I = integral of x, W_r) is simulated exactly on the
 * time grid as a joint Gaussian step, so the stochastic discount factor
 * {@code D(0,t) = P(0,t) exp(-V(0,t)/2 - I(t))} has no discretisation
 * error and {@code E[D(0,t)] = P(0,t)}.</p>
 *
 * <p>FX: geometric Brownian motion under the domestic risk-neutral
 * measure, {@code dS/S = (r_d - r_f) dt + sigma_S dW_S},
 * {@code corr(dW_S, dW_r) = rho}, foreign rates deterministic. Exactly,
 * {@code S(t) = S0 P_f(0,t) / D(0,t) * exp(-sigma_S^2 t / 2 + sigma_S W_S(t))}.</p>
 *
 * <p>Trades: a vanilla swap (fixed against a single-curve floating leg,
 * same schedule, accrual = time between dates) is valued at t from the
 * simulated curve, with the floating coupon in progress fixed at its
 * reset from the simulated {@code P(T_{j-1}, T_j)}; reset dates are added
 * to the internal grid. An FX forward to buy {@code N} foreign at strike
 * K is worth {@code N (S P_f(t,T) - K P_d(t,T))}. A cash flow paid at t
 * is not part of the value at t.</p>
 *
 * <p>Collateral (two-way CSA): the target at a margin call on value V is
 * {@code V - H} if V &gt; H, {@code V + H} if V &lt; -H, else 0, with
 * threshold H; the balance moves to the target only when the change is
 * at least the minimum transfer amount. The call for exposure date t is
 * made on the value at {@code t - MPR} (margin period of risk), so the
 * collateralised exposure at t is {@code max(V(t) - C(t - MPR), 0)}
 * (Gregory, The xVA Challenge, 3rd ed., 2015, ch. 7). The balance at
 * time 0 is set from V(0) with the same rule.</p>
 *
 * <p>Seeded {@link SplittableRandom}; the same seed gives the same
 * profile. Research lane.</p>
 *
 * <p><b>3.0 notice.</b> This class folds into
 * {@link MultiCurrencyExposureSimulator} in 3.0. {@link Market}, its
 * {@code ratesOnly} factory and {@link #simulate} are deprecated for removal
 * in 2.4.0. The trade-term records {@link Swap}, {@link FxForward} and
 * {@link Csa} are not deprecated in 2.x because the multi-currency API takes
 * them as parameters; in 3.0 they become nested records of
 * {@code MultiCurrencyExposureSimulator} with the same components (an import
 * change only). See docs/MIGRATION_3.0.md.</p>
 */
public final class ExposureSimulator {

    private ExposureSimulator() {
    }

    /**
     * Market model parameters.
     *
     * @param domestic       domestic discount curve (also the swap projection curve)
     * @param meanReversion  Hull-White a, &gt; 0
     * @param rateVol        Hull-White sigma (absolute, per year), &ge; 0
     * @param foreign        foreign discount curve, null when no FX forwards
     * @param fxSpot         spot, domestic per foreign, &gt; 0 when FX is used
     * @param fxVol          FX lognormal volatility, &ge; 0
     * @param fxRateCorrelation correlation of the FX and rate Brownians, in [-1, 1]
     * @deprecated since 2.4.0, removed in 3.0 when this class folds into
     *     {@link MultiCurrencyExposureSimulator}. Use
     *     {@link MultiCurrencyExposureSimulator.Market} with a
     *     {@link MultiCurrencyExposureSimulator.Domestic} and zero or one
     *     {@link MultiCurrencyExposureSimulator.Foreign} (foreign rate vol 0);
     *     docs/MIGRATION_3.0.md shows the one-pair equivalent.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public record Market(YieldCurve domestic, double meanReversion, double rateVol,
                         YieldCurve foreign, double fxSpot, double fxVol,
                         double fxRateCorrelation) {
        /** Validates the parameters. */
        public Market {
            Objects.requireNonNull(domestic, "domestic");
            if (!(meanReversion > 0) || meanReversion == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("meanReversion must be positive and finite");
            }
            if (!(rateVol >= 0) || rateVol == Double.POSITIVE_INFINITY
                    || !(fxVol >= 0) || fxVol == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("volatilities must be >= 0 and finite");
            }
            if (!(fxRateCorrelation >= -1) || !(fxRateCorrelation <= 1)) {
                throw new IllegalArgumentException("correlation must be in [-1, 1]");
            }
            if (foreign != null && (!(fxSpot > 0) || fxSpot == Double.POSITIVE_INFINITY)) {
                throw new IllegalArgumentException("fxSpot must be positive and finite");
            }
        }

        /**
         * Rates-only market (no FX).
         *
         * @deprecated since 2.4.0, removed in 3.0. Use
         *     {@code new MultiCurrencyExposureSimulator.Market(new Domestic(Curve.of(curve), a, sigma), List.of(), new double[][]{{1}})}.
         */
        @Deprecated(since = "2.4.0", forRemoval = true)
        public static Market ratesOnly(YieldCurve domestic, double meanReversion, double rateVol) {
            return new Market(domestic, meanReversion, rateVol, null, 0, 0, 0);
        }
    }

    /**
     * Vanilla fixed-for-floating swap.
     *
     * @param notional      notional, &gt; 0
     * @param fixedRate     fixed rate, decimal
     * @param payFixed      true for a payer swap (receive floating)
     * @param startYears    first reset time, &ge; 0
     * @param maturityYears last payment time, &gt; start
     * @param paymentsPerYear payments per year, &ge; 1
     */
    public record Swap(double notional, double fixedRate, boolean payFixed, double startYears,
                       double maturityYears, int paymentsPerYear) {
        /** Validates the terms. */
        public Swap {
            if (!(notional > 0) || notional == Double.POSITIVE_INFINITY || !Double.isFinite(fixedRate)) {
                throw new IllegalArgumentException("need positive notional and finite rate");
            }
            if (!(startYears >= 0) || !(maturityYears > startYears)
                    || maturityYears == Double.POSITIVE_INFINITY || paymentsPerYear < 1) {
                throw new IllegalArgumentException("need 0 <= start < maturity and frequency >= 1");
            }
        }

        /** Schedule T_0 = start, ..., T_n = maturity (a short last period if needed). */
        double[] dates() {
            double step = 1.0 / paymentsPerYear;
            int n = (int) Math.ceil((maturityYears - startYears) / step - 1e-9);
            double[] d = new double[n + 1];
            for (int j = 0; j < n; j++) {
                d[j] = startYears + j * step;
            }
            d[n] = maturityYears;
            return d;
        }
    }

    /**
     * FX forward: buy {@code foreignNotional} units of foreign currency at
     * {@code strike} domestic per foreign at {@code maturityYears}; a
     * negative notional sells.
     *
     * @param foreignNotional foreign units bought (sold if negative), non-zero
     * @param strike          contract rate, &gt; 0
     * @param maturityYears   settlement time, &gt; 0
     */
    public record FxForward(double foreignNotional, double strike, double maturityYears) {
        /** Validates the terms. */
        public FxForward {
            if (!Double.isFinite(foreignNotional) || foreignNotional == 0
                    || !(strike > 0) || strike == Double.POSITIVE_INFINITY
                    || !(maturityYears > 0) || maturityYears == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("need non-zero notional, positive strike and maturity");
            }
        }
    }

    /**
     * Collateral agreement terms (two-way, symmetric).
     *
     * @param threshold        H, &ge; 0
     * @param minimumTransfer  MTA, &ge; 0
     * @param marginPeriodYears MPR in years, &ge; 0 (10 business days is about 10/250)
     */
    public record Csa(double threshold, double minimumTransfer, double marginPeriodYears) {
        /** Validates the terms. */
        public Csa {
            if (!(threshold >= 0) || !(minimumTransfer >= 0) || !(marginPeriodYears >= 0)
                    || !Double.isFinite(threshold + minimumTransfer + marginPeriodYears)) {
                throw new IllegalArgumentException("CSA terms must be >= 0 and finite");
            }
        }

        double target(double v) {
            if (v > threshold) {
                return v - threshold;
            }
            if (v < -threshold) {
                return v + threshold;
            }
            return 0;
        }
    }

    /**
     * Simulates the netting set.
     *
     * @param market   market model
     * @param swaps    swaps (may be empty)
     * @param forwards FX forwards (may be empty; need {@code market.foreign()})
     * @param csa      collateral terms, or null for an uncollateralised set
     * @param times    exposure dates in years, strictly ascending, &gt; 0
     * @param paths    number of paths, &ge; 2
     * @param seed     random seed
     * @return the exposure profile
     * @deprecated since 2.4.0, removed in 3.0. Use
     *     {@link MultiCurrencyExposureSimulator#simulate(MultiCurrencyExposureSimulator.Market, List, List, Csa, double[], int, long)},
     *     wrapping each swap as {@code new MultiCurrencyExposureSimulator.Swap(swap, null)} and each
     *     forward as {@code new MultiCurrencyExposureSimulator.FxForward(0, fwd)}. The multi-currency
     *     engine agrees with this one to rounding, path for path, from the same seed (own test:
     *     {@code MultiCurrencyExposureSimulatorTest}), not bit for bit.
     */
    @Deprecated(since = "2.4.0", forRemoval = true)
    public static ExposureProfile simulate(Market market, List<Swap> swaps, List<FxForward> forwards,
                                           Csa csa, double[] times, int paths, long seed) {
        Objects.requireNonNull(market, "market");
        Objects.requireNonNull(swaps, "swaps");
        Objects.requireNonNull(forwards, "forwards");
        if (swaps.isEmpty() && forwards.isEmpty()) {
            throw new IllegalArgumentException("netting set has no trades");
        }
        if (!forwards.isEmpty() && market.foreign() == null) {
            throw new IllegalArgumentException("FX forwards need a foreign curve and spot");
        }
        if (paths < 2) {
            throw new IllegalArgumentException("need at least 2 paths, got " + paths);
        }
        int m = times.length;
        if (m == 0) {
            throw new IllegalArgumentException("need at least one exposure date");
        }
        double prev = 0;
        for (double t : times) {
            if (!(t > prev) || t == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("times must be strictly ascending, positive, finite");
            }
            prev = t;
        }
        return new Engine(market, swaps, forwards, csa, times.clone()).run(paths, seed);
    }

    /** One simulation run; all per-path work is in {@link #run}. */
    private static final class Engine {
        private final Market mk;
        private final Swap[] swaps;
        private final double[][] swapDates;
        private final FxForward[] fwds;
        private final Csa csa;
        private final double[] times;
        private final double[] grid;          // internal grid, grid[0] = 0
        private final int[] userIndex;        // grid index of each user date
        private final int[] lagIndex;         // grid index of each margin-call date
        private final double a;
        private final double sigma;

        Engine(Market mk, List<Swap> swaps, List<FxForward> fwds, Csa csa, double[] times) {
            this.mk = mk;
            this.swaps = swaps.toArray(new Swap[0]);
            this.fwds = fwds.toArray(new FxForward[0]);
            this.csa = csa;
            this.times = times;
            this.a = mk.meanReversion();
            this.sigma = mk.rateVol();
            this.swapDates = new double[this.swaps.length][];
            TreeSet<Double> set = new TreeSet<>();
            set.add(0.0);
            for (double t : times) {
                set.add(t);
            }
            for (int k = 0; k < this.swaps.length; k++) {
                swapDates[k] = this.swaps[k].dates();
                for (int j = 0; j < swapDates[k].length - 1; j++) {
                    if (swapDates[k][j] <= times[times.length - 1]) {
                        set.add(swapDates[k][j]);
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

        double bFn(double tau) {
            return -Math.expm1(-a * tau) / a;
        }

        double vFn(double tau) {
            if (sigma == 0) {
                return 0;
            }
            double b = bFn(tau);
            double b2 = -Math.expm1(-2 * a * tau) / (2 * a);
            return sigma * sigma / (a * a) * (tau - 2 * b + b2);
        }

        double p0(double t) {
            return t == 0 ? 1 : mk.domestic().discountFactor(t);
        }

        /** Simulated P(t,T) given x(t). */
        double bond(double t, double tMat, double x) {
            if (tMat <= t) {
                return 1;
            }
            double lnA = 0.5 * (vFn(tMat - t) - vFn(tMat) + vFn(t));
            return p0(tMat) / p0(t) * Math.exp(lnA - bFn(tMat - t) * x);
        }

        double pf(double t) {
            return t == 0 ? 1 : mk.foreign().discountFactor(t);
        }

        double value(double t, double x, double fx, double[][] resetBond) {
            double v = 0;
            for (int k = 0; k < swaps.length; k++) {
                Swap s = swaps[k];
                double[] d = swapDates[k];
                int n = d.length - 1;
                if (t >= d[n]) {
                    continue;
                }
                double fixed = 0;
                int first = -1;
                for (int j = 1; j <= n; j++) {
                    if (d[j] > t) {
                        if (first < 0) {
                            first = j;
                        }
                        fixed += (d[j] - d[j - 1]) * bond(t, d[j], x);
                    }
                }
                double pn = bond(t, d[n], x);
                double flt;
                if (d[first - 1] >= t) {
                    flt = bond(t, d[first - 1], x) - pn;
                } else {
                    flt = bond(t, d[first], x) / resetBond[k][first - 1] - pn;
                }
                double pv = s.notional() * (flt - s.fixedRate() * fixed);
                v += s.payFixed() ? pv : -pv;
            }
            for (FxForward f : fwds) {
                if (t >= f.maturityYears()) {
                    continue;
                }
                double pfT = pf(f.maturityYears()) / pf(t);
                v += f.foreignNotional() * (fx * pfT - f.strike() * bond(t, f.maturityYears(), x));
            }
            return v;
        }

        ExposureProfile run(int paths, long seed) {
            int m = times.length;
            int ng = grid.length;
            // Per-step Cholesky factor of the (x, I, W_r) innovation covariance.
            double[][][] chol = new double[ng][][];
            double[] decay = new double[ng];
            double[] bStep = new double[ng];
            for (int g = 1; g < ng; g++) {
                double dt = grid[g] - grid[g - 1];
                double b = bFn(dt);
                double b2 = -Math.expm1(-2 * a * dt) / (2 * a);
                double s2 = sigma * sigma;
                double[][] cov = {
                        {s2 * b2, s2 / a * (b - b2), sigma * b},
                        {s2 / a * (b - b2), s2 / (a * a) * (dt - 2 * b + b2), sigma / a * (dt - b)},
                        {sigma * b, sigma / a * (dt - b), dt}};
                chol[g] = semiDefiniteCholesky(cov);
                decay[g] = Math.exp(-a * dt);
                bStep[g] = b;
            }
            double rho = mk.fxRateCorrelation();
            double rhoPerp = Math.sqrt(Math.max(1 - rho * rho, 0));
            double fxVol = mk.fxVol();
            boolean hasFx = fwds.length > 0;

            double[][] discPos = new double[paths][m];
            double[][] discNeg = new double[paths][m];
            double[][] expo = new double[paths][m];
            double[][] driverRates = new double[paths][m];
            double[][] driverFx = new double[paths][m];
            double[] wrOnGrid = new double[ng];
            double[] wfxOnGrid = new double[ng];

            double v0 = value(0, 0, hasFx ? mk.fxSpot() : 0, initialResets());
            SplittableRandom rng = new SplittableRandom(seed);
            double[] vOnGrid = new double[ng];
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
            double[] dOnGrid = new double[ng];
            for (int p = 0; p < paths; p++) {
                double x = 0, integ = 0, wr = 0, wfx = 0;
                vOnGrid[0] = v0;
                dOnGrid[0] = 1;
                fillResets(resets, resetAt, 0, 0);
                for (int g = 1; g < ng; g++) {
                    double z1 = rng.nextGaussian(), z2 = rng.nextGaussian(), z3 = rng.nextGaussian();
                    double[][] c = chol[g];
                    double e1 = c[0][0] * z1;
                    double e2 = c[1][0] * z1 + c[1][1] * z2;
                    double ew = c[2][0] * z1 + c[2][1] * z2 + c[2][2] * z3;
                    integ += x * bStep[g] + e2;
                    x = x * decay[g] + e1;
                    wr += ew;
                    wrOnGrid[g] = wr;
                    double t = grid[g];
                    double dt = t - grid[g - 1];
                    if (hasFx) {
                        wfx += rho * ew + rhoPerp * Math.sqrt(dt) * rng.nextGaussian();
                    }
                    wfxOnGrid[g] = wfx;
                    double disc = p0(t) * Math.exp(-0.5 * vFn(t) - integ);
                    dOnGrid[g] = disc;
                    fillResets(resets, resetAt, g, x);
                    if (needV[g]) {
                        double fx = hasFx
                                ? mk.fxSpot() * pf(t) / disc * Math.exp(-0.5 * fxVol * fxVol * t + fxVol * wfx)
                                : 0;
                        vOnGrid[g] = value(t, x, fx, resets);
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
                    // Wrong-way driver for bucket i: the standardised Brownian move that
                    // drives E(t_i). Uncollateralised, the level W(t_i) / sqrt(t_i); under
                    // a CSA, the increment over the margin period of risk ending at t_i
                    // (a zero MPR falls back to the level).
                    int u = userIndex[i];
                    int l = csa == null || lagIndex[i] == u ? 0 : lagIndex[i];
                    double span = times[i] - grid[l];
                    double norm = 1 / Math.sqrt(span);
                    driverRates[p][i] = (wrOnGrid[u] - wrOnGrid[l]) * norm;
                    driverFx[p][i] = hasFx ? (wfxOnGrid[u] - wfxOnGrid[l]) * norm : 0;
                }
            }
            double[] p0s = new double[m];
            for (int i = 0; i < m; i++) {
                p0s[i] = p0(times[i]);
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

        /** For each grid index, flattened pairs (swap, period) resetting there. */
        private int[][] resetLookup() {
            List<List<Integer>> lists = new ArrayList<>();
            for (int g = 0; g < grid.length; g++) {
                lists.add(new ArrayList<>());
            }
            for (int k = 0; k < swaps.length; k++) {
                for (int j = 0; j < swapDates[k].length - 1; j++) {
                    int g = Arrays.binarySearch(grid, swapDates[k][j]);
                    if (g < 0) {
                        continue;   // resets after the last exposure date are never needed
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

        private void fillResets(double[][] resets, int[][] resetAt, int g, double x) {
            int[] r = resetAt[g];
            for (int i = 0; i < r.length; i += 2) {
                int k = r[i], j = r[i + 1];
                resets[k][j] = bond(grid[g], swapDates[k][j + 1], x);
            }
        }
    }

    /** Cholesky of a 3x3 positive semi-definite matrix; zero pivots give zero columns. */
    static double[][] semiDefiniteCholesky(double[][] c) {
        int n = c.length;
        double[][] l = new double[n][n];
        for (int j = 0; j < n; j++) {
            double d = c[j][j];
            for (int k = 0; k < j; k++) {
                d -= l[j][k] * l[j][k];
            }
            double scale = Math.max(Math.abs(c[j][j]), 1e-300);
            if (d <= 1e-13 * scale) {
                continue;   // degenerate direction (zero vol, or perfectly determined)
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
