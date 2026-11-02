package com.quantfinlib.risk;

import com.quantfinlib.stats.LinearAlgebra;
import com.quantfinlib.stats.Numerics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Fundamental equity factor risk model in the Barra USE4 style: a market
 * factor, one dummy factor per sector and standardised style factors,
 * estimated by weighted cross-sectional regression each period.
 *
 * <p><b>Model.</b> For period {@code t} and name {@code i},
 * {@code r_it = f_mkt,t + f_sec(i),t + Σ_k x_ikt f_kt + u_it}. The forecast
 * covariance of the names is {@code V = X F Xᵀ + D}, with {@code X} the
 * exposures at the forecast date, {@code F} the factor covariance and
 * {@code D} the diagonal of specific variances.</p>
 *
 * <p><b>Inputs and timing.</b> {@code returns[t][i]} is the simple return of
 * name {@code i} over period {@code t}. {@code styles[t][k][i]} and
 * {@code caps[t][i]} are known at the start of period {@code t}. A name
 * takes part in period {@code t} when its return is finite and its
 * capitalisation is finite and positive. When {@code styles} and
 * {@code caps} carry one more row than {@code returns}, that last row is
 * the forecast date (exposures known today, for the period ahead);
 * otherwise the forecast uses the last estimation row.</p>
 *
 * <p><b>Style standardisation</b> (Menchero, Orr and Wang 2011, "The Barra
 * US Equity Model (USE4), Methodology Notes", §3): per period, subtract the
 * capitalisation-weighted mean and divide by the equal-weighted standard
 * deviation, clip at {@code ±winsorLimit}, then re-centre and re-scale the
 * clipped values once. A missing raw exposure of a participating name is
 * set to 0, the cap-weighted mean.</p>
 *
 * <p><b>Factor returns.</b> Weighted least squares with regression weights
 * {@code √cap_i} (heteroskedasticity of specific returns falls with size).
 * The sector dummies sum to the market column, so the system is singular;
 * following Menchero (2010, "The Characteristics of Factor Portfolios", J.
 * Performance Measurement 15(1)) the constraint
 * {@code Σ_s c_s f_s = 0} with {@code c_s} the sector's capitalisation share
 * is imposed. It is solved exactly by substituting
 * {@code f_S = −Σ_{s<S} (c_s/c_S) f_s} (the restriction matrix {@code R} of
 * Menchero's paper) and running unconstrained WLS on {@code X R}. The
 * market factor then is the cap-weighted market return net of style
 * contributions. Sectors with no participating names in a period get a
 * return of 0 and drop out of that period's constraint. Periods with fewer
 * names than free parameters plus one are skipped (factor returns NaN).</p>
 *
 * <p><b>Factor covariance</b> (USE4 §4): exponentially weighted with
 * half-life {@code factorHalfLife} periods and zero mean, then a Newey and
 * West (1987, Econometrica 55(3)) correction for serial correlation,
 * {@code F = C₀ + Σ_{l=1..L} (1 − l/(L+1))(C_l + C_lᵀ)}, with the lagged
 * cross-covariances {@code C_l = Σ w_t f_t f_{t−l}ᵀ / Σ w_t}. A result that
 * is not positive semi-definite to rounding is repaired with
 * {@link PsdMatrices#nearestPsd}.</p>
 *
 * <p><b>Volatility regime adjustment</b> (USE4 §4.3): the cross-sectional
 * bias statistic {@code B_t² = (1/K) Σ_k (f_kt / σ_k,t−1)²}, with
 * {@code σ_k,t−1} the one-step-ahead EWMA volatility of factor {@code k},
 * is averaged with half-life {@code volatilityRegimeHalfLife}; {@code F}
 * is multiplied by that average {@code λ_F²}. The statistic starts after a
 * burn-in of 20 valid periods; 0 switches the adjustment off.</p>
 *
 * <p><b>Specific risk.</b> Zero-mean EWMA of the regression residuals with
 * half-life {@code specificHalfLife}, then Bayesian shrinkage toward the
 * capitalisation-weighted mean {@code σ̄_n} of the name's size bucket
 * (USE4 §5.3): {@code σ_SH = v σ̄_n + (1 − v) σ},
 * {@code v = q|σ − σ̄_n| / (Δ_n + q|σ − σ̄_n|)},
 * {@code Δ_n = √(mean_n (σ − σ̄_n)²)}. Names with fewer than two residuals
 * take the bucket mean.</p>
 *
 * <p>All variances are per period of the input returns.</p>
 */
public final class FactorRiskModel {

    /** Burn-in of valid periods before the bias statistic is used. */
    static final int VRA_BURN_IN = 20;

    /**
     * Estimation settings. Half-lives are in periods.
     *
     * @param factorHalfLife           EWMA half-life of the factor covariance (&gt; 0)
     * @param neweyWestLags            Newey-West lags {@code L} (&ge; 0)
     * @param volatilityRegimeHalfLife half-life of the bias statistic average (0 = no adjustment)
     * @param specificHalfLife         EWMA half-life of specific variance (&gt; 0)
     * @param shrinkage                Bayesian shrinkage intensity {@code q} (&ge; 0, 0 = none)
     * @param sizeBuckets              number of capitalisation buckets for shrinkage (&ge; 1)
     * @param winsorLimit              clip of standardised styles, in standard deviations (&gt; 0)
     */
    public record Settings(int factorHalfLife, int neweyWestLags, int volatilityRegimeHalfLife,
                           int specificHalfLife, double shrinkage, int sizeBuckets,
                           double winsorLimit) {

        public Settings {
            if (factorHalfLife <= 0 || neweyWestLags < 0 || volatilityRegimeHalfLife < 0
                    || specificHalfLife <= 0 || !(shrinkage >= 0) || Double.isInfinite(shrinkage)
                    || sizeBuckets < 1 || !(winsorLimit > 0)) {
                throw new IllegalArgumentException("invalid factor risk model settings");
            }
        }

        /**
         * Daily defaults close to the USE4 short-horizon choices: factor
         * half-life 90, two Newey-West lags, regime half-life 42, specific
         * half-life 90, {@code q = 0.1}, ten size buckets, clip at 3.
         */
        public static Settings defaults() {
            return new Settings(90, 2, 42, 90, 0.1, 10, 3.0);
        }

        /** The same settings without Newey-West, regime adjustment or shrinkage. */
        public Settings plain() {
            return new Settings(factorHalfLife, 0, 0, specificHalfLife, 0, sizeBuckets,
                    winsorLimit);
        }
    }

    private final List<String> factorNames;
    private final int sectorCount;
    private final int styleCount;
    private final int[] sectors;
    private final double[][] factorReturns;   // [T][P], NaN for skipped periods
    private final double[][] residuals;       // [T][N], NaN when not in the regression
    private final double[][][] zStyles;       // [T or T+1][K][N], NaN when not participating
    private final double[][] factorCov;       // [P][P]
    private final double[] specificVar;       // [N], NaN for names not live at the forecast
    private final double regimeMultiplier;
    private final boolean repaired;

    private FactorRiskModel(List<String> factorNames, int sectorCount, int styleCount, int[] sectors,
                            double[][] factorReturns, double[][] residuals, double[][][] zStyles,
                            double[][] factorCov, double[] specificVar, double regimeMultiplier,
                            boolean repaired) {
        this.factorNames = List.copyOf(factorNames);
        this.sectorCount = sectorCount;
        this.styleCount = styleCount;
        this.sectors = sectors;
        this.factorReturns = factorReturns;
        this.residuals = residuals;
        this.zStyles = zStyles;
        this.factorCov = factorCov;
        this.specificVar = specificVar;
        this.regimeMultiplier = regimeMultiplier;
        this.repaired = repaired;
    }

    /**
     * Estimates the model.
     *
     * @param returns     {@code [T][N]} period returns (NaN = not trading)
     * @param styles      {@code [T or T+1][K][N]} raw style exposures
     * @param styleNames  K names
     * @param sectors     sector index of each name, in {@code [0, sectorNames.size())}
     * @param sectorNames S &ge; 1 sector names
     * @param caps        {@code [T or T+1][N]} capitalisations (any currency)
     * @param settings    estimation settings
     */
    public static FactorRiskModel estimate(double[][] returns, double[][][] styles,
                                           List<String> styleNames, int[] sectors,
                                           List<String> sectorNames, double[][] caps,
                                           Settings settings) {
        int t = returns.length;
        if (t < 2) {
            throw new IllegalArgumentException("need at least two periods");
        }
        int n = returns[0].length;
        int k = styleNames.size();
        int s = sectorNames.size();
        if (s < 1 || sectors.length != n || caps.length != styles.length
                || (styles.length != t && styles.length != t + 1)) {
            throw new IllegalArgumentException("inputs must align: styles and caps need T or T+1"
                    + " rows, sectors one entry per name, and at least one sector");
        }
        for (int i = 0; i < n; i++) {
            if (sectors[i] < 0 || sectors[i] >= s) {
                throw new IllegalArgumentException("sector index out of range for name " + i);
            }
        }
        for (int r = 0; r < styles.length; r++) {
            if (caps[r].length != n || styles[r].length != k
                    || (r < t && returns[r].length != n)) {
                throw new IllegalArgumentException("row " + r + " is misaligned");
            }
            for (int j = 0; j < k; j++) {
                if (styles[r][j].length != n) {
                    throw new IllegalArgumentException("style row " + r + "," + j + " is misaligned");
                }
            }
        }
        int p = 1 + s + k;
        List<String> names = new ArrayList<>(p);
        names.add("MARKET");
        names.addAll(sectorNames);
        names.addAll(styleNames);

        double[][][] z = new double[styles.length][k][];
        for (int r = 0; r < styles.length; r++) {
            boolean[] live = new boolean[n];
            for (int i = 0; i < n; i++) {
                live[i] = liveCap(caps[r][i]) && (r >= t || Double.isFinite(returns[r][i]));
            }
            for (int j = 0; j < k; j++) {
                z[r][j] = standardize(styles[r][j], caps[r], live, settings.winsorLimit());
            }
        }

        double[][] f = new double[t][];
        double[][] u = new double[t][];
        for (int r = 0; r < t; r++) {
            u[r] = Numerics.nanArray(n);
            f[r] = regress(returns[r], z[r], sectors, s, caps[r], u[r]);
        }

        int last = styles.length - 1;
        double[][] cov = factorCovariance(f, settings.factorHalfLife(), settings.neweyWestLags());
        double lambda = settings.volatilityRegimeHalfLife() == 0 ? 1
                : Math.sqrt(regimeMultiplierSquared(f, settings.factorHalfLife(),
                        settings.volatilityRegimeHalfLife()));
        double l2 = lambda * lambda;
        for (double[] row : cov) {
            for (int j = 0; j < p; j++) {
                row[j] *= l2;
            }
        }
        boolean fixed = false;
        double minEig = PsdMatrices.symmetricEigen(cov).values(p - 1);
        double maxDiag = 0;
        for (int j = 0; j < p; j++) {
            maxDiag = Math.max(maxDiag, cov[j][j]);
        }
        if (minEig < -1e-12 * Math.max(maxDiag, Double.MIN_NORMAL)) {
            cov = PsdMatrices.nearestPsd(cov);
            fixed = true;
        }

        double[] spec = specificVariances(u, caps[last], z[last], settings);
        return new FactorRiskModel(Collections.unmodifiableList(names), s, k, sectors.clone(), f,
                u, z, cov, spec, lambda, fixed);
    }

    // ------------------------------------------------------------------
    // Estimation steps (package-private for tests)
    // ------------------------------------------------------------------

    private static boolean liveCap(double c) {
        return Double.isFinite(c) && c > 0;
    }

    /**
     * Cap-weighted-mean-zero, equal-weighted-unit-sd z-scores over the
     * {@code live} names, clipped at {@code ±limit} and re-standardised once.
     * Live names with a non-finite raw value get 0; names not live get NaN.
     * All-zero when the live values have no dispersion.
     */
    public static double[] standardize(double[] raw, double[] caps, boolean[] live, double limit) {
        int n = raw.length;
        if (caps.length != n || live.length != n || !(limit > 0)) {
            throw new IllegalArgumentException("inputs must align and limit must be > 0");
        }
        double[] out = Numerics.nanArray(n);
        boolean[] has = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (live[i]) {
                if (!liveCap(caps[i])) {
                    throw new IllegalArgumentException("live name " + i + " needs a positive cap");
                }
                has[i] = Double.isFinite(raw[i]);
                out[i] = has[i] ? raw[i] : 0;
            }
        }
        rescale(out, caps, has);
        boolean clipped = false;
        for (int i = 0; i < n; i++) {
            if (has[i] && Math.abs(out[i]) > limit) {
                out[i] = Math.copySign(limit, out[i]);
                clipped = true;
            }
        }
        if (clipped) {
            rescale(out, caps, has);
        }
        for (int i = 0; i < n; i++) {
            if (live[i] && !has[i]) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static void rescale(double[] x, double[] caps, boolean[] has) {
        double cw = 0;
        double cs = 0;
        int m = 0;
        for (int i = 0; i < x.length; i++) {
            if (has[i]) {
                cw += caps[i] * x[i];
                cs += caps[i];
                m++;
            }
        }
        if (m == 0) {
            return;
        }
        double mu = cw / cs;
        double em = 0;
        for (int i = 0; i < x.length; i++) {
            if (has[i]) {
                em += x[i];
            }
        }
        em /= m;
        double var = 0;
        for (int i = 0; i < x.length; i++) {
            if (has[i]) {
                var += (x[i] - em) * (x[i] - em);
            }
        }
        double sd = Math.sqrt(var / m);
        for (int i = 0; i < x.length; i++) {
            if (has[i]) {
                x[i] = sd > 0 ? (x[i] - mu) / sd : 0;
            }
        }
    }

    /**
     * One constrained WLS cross-section. Fills {@code resid} for the
     * participating names and returns the P factor returns (all NaN when
     * the period has too few names).
     */
    static double[] regress(double[] r, double[][] z, int[] sectors, int s, double[] caps,
                            double[] resid) {
        int n = r.length;
        int k = z.length;
        int p = 1 + s + k;
        double[] capBySector = new double[s];
        int live = 0;
        for (int i = 0; i < n; i++) {
            if (Double.isFinite(r[i]) && liveCap(caps[i])) {
                capBySector[sectors[i]] += caps[i];
                live++;
            }
        }
        int[] present = new int[s];
        int ps = 0;
        for (int j = 0; j < s; j++) {
            if (capBySector[j] > 0) {
                present[ps++] = j;
            }
        }
        double[] fr = Numerics.nanArray(p);
        // free parameters: market, ps-1 sectors, k styles
        int q = 1 + Math.max(ps - 1, 0) + k;
        if (ps == 0 || live < q + 1) {
            return fr;
        }
        int lastSec = present[ps - 1];
        double cLast = capBySector[lastSec];
        // map sector -> free column (or -1 for the dependent last one)
        int[] col = new int[s];
        Arrays.fill(col, -2);
        for (int a = 0; a < ps - 1; a++) {
            col[present[a]] = 1 + a;
        }
        col[lastSec] = -1;
        double wsum = 0;
        for (int i = 0; i < n; i++) {
            if (Double.isFinite(r[i]) && liveCap(caps[i])) {
                wsum += Math.sqrt(caps[i]);
            }
        }
        double[][] xtx = new double[q][q];
        double[] xty = new double[q];
        double[] row = new double[q];
        for (int i = 0; i < n; i++) {
            if (!(Double.isFinite(r[i]) && liveCap(caps[i]))) {
                continue;
            }
            fillRow(row, i, z, sectors, col, present, ps, capBySector, cLast);
            double w = Math.sqrt(caps[i]) * live / wsum;
            for (int a = 0; a < q; a++) {
                xty[a] += w * row[a] * r[i];
                for (int b = 0; b < q; b++) {
                    xtx[a][b] += w * row[a] * row[b];
                }
            }
        }
        double[] g;
        try {
            g = LinearAlgebra.solveLinear(xtx, xty);
        } catch (IllegalArgumentException singular) {
            return fr;
        }
        fr[0] = g[0];
        for (int j = 0; j < s; j++) {
            fr[1 + j] = 0;
        }
        double dep = 0;
        for (int a = 0; a < ps - 1; a++) {
            fr[1 + present[a]] = g[1 + a];
            dep -= capBySector[present[a]] / cLast * g[1 + a];
        }
        fr[1 + lastSec] = dep;
        for (int j = 0; j < k; j++) {
            fr[1 + s + j] = g[q - k + j];
        }
        for (int i = 0; i < n; i++) {
            if (Double.isFinite(r[i]) && liveCap(caps[i])) {
                double fit = fr[0] + fr[1 + sectors[i]];
                for (int j = 0; j < k; j++) {
                    fit += z[j][i] * fr[1 + s + j];
                }
                resid[i] = r[i] - fit;
            }
        }
        return fr;
    }

    private static void fillRow(double[] row, int i, double[][] z, int[] sectors, int[] col,
                                int[] present, int ps, double[] capBySector, double cLast) {
        Arrays.fill(row, 0);
        row[0] = 1;
        int c = col[sectors[i]];
        if (c > 0) {
            row[c] = 1;
        } else if (c == -1) {
            for (int a = 0; a < ps - 1; a++) {
                row[1 + a] = -capBySector[present[a]] / cLast;
            }
        }
        int k = z.length;
        int q = row.length;
        for (int j = 0; j < k; j++) {
            row[q - k + j] = z[j][i];
        }
    }

    /** Zero-mean EWMA covariance with Bartlett-weighted Newey-West lags over the valid rows. */
    static double[][] factorCovariance(double[][] f, int halfLife, int lags) {
        int t = f.length;
        int p = f[0].length;
        double decay = Math.pow(0.5, 1.0 / halfLife);
        double[][] cov = new double[p][p];
        double[][][] lagged = new double[lags][p][p];
        double wsum = 0;
        double w = 1;
        int used = 0;
        for (int r = t - 1; r >= 0; r--, w *= decay) {
            if (!valid(f[r])) {
                continue;
            }
            used++;
            wsum += w;
            for (int a = 0; a < p; a++) {
                for (int b = 0; b < p; b++) {
                    cov[a][b] += w * f[r][a] * f[r][b];
                }
            }
            for (int l = 1; l <= lags; l++) {
                if (r - l >= 0 && valid(f[r - l])) {
                    for (int a = 0; a < p; a++) {
                        for (int b = 0; b < p; b++) {
                            lagged[l - 1][a][b] += w * f[r][a] * f[r - l][b];
                        }
                    }
                }
            }
        }
        if (used < 2) {
            throw new IllegalArgumentException("fewer than two periods with factor returns");
        }
        for (int a = 0; a < p; a++) {
            for (int b = 0; b < p; b++) {
                double v = cov[a][b];
                for (int l = 1; l <= lags; l++) {
                    double bw = 1 - l / (lags + 1.0);
                    v += bw * (lagged[l - 1][a][b] + lagged[l - 1][b][a]);
                }
                cov[a][b] = v / wsum;
            }
        }
        for (int a = 0; a < p; a++) {
            for (int b = a + 1; b < p; b++) {
                double m = 0.5 * (cov[a][b] + cov[b][a]);
                cov[a][b] = m;
                cov[b][a] = m;
            }
        }
        return cov;
    }

    /** EWMA (half-life {@code vraHalfLife}) of the cross-sectional bias statistic squared. */
    static double regimeMultiplierSquared(double[][] f, int halfLife, int vraHalfLife) {
        int p = f[0].length;
        double d = Math.pow(0.5, 1.0 / halfLife);
        double dv = Math.pow(0.5, 1.0 / vraHalfLife);
        double[] s = new double[p];
        double ws = 0;
        double num = 0;
        double den = 0;
        int seen = 0;
        for (double[] row : f) {
            if (!valid(row)) {
                continue;
            }
            if (seen >= VRA_BURN_IN) {
                double b2 = 0;
                int kk = 0;
                for (int a = 0; a < p; a++) {
                    double var = s[a] / ws;
                    if (var > 0) {
                        b2 += row[a] * row[a] / var;
                        kk++;
                    }
                }
                if (kk > 0) {
                    num = dv * num + b2 / kk;
                    den = dv * den + 1;
                }
            }
            for (int a = 0; a < p; a++) {
                s[a] = d * s[a] + row[a] * row[a];
            }
            ws = d * ws + 1;
            seen++;
        }
        return den == 0 ? 1 : num / den;
    }

    private static boolean valid(double[] row) {
        for (double v : row) {
            if (!Double.isFinite(v)) {
                return false;
            }
        }
        return true;
    }

    /** EWMA specific variances with size-bucket Bayesian shrinkage, for names live at the forecast row. */
    static double[] specificVariances(double[][] u, double[] capsNow, double[][] zNow,
                                      Settings settings) {
        int n = capsNow.length;
        int t = u.length;
        double d = Math.pow(0.5, 1.0 / settings.specificHalfLife());
        double[] sigma = Numerics.nanArray(n);
        int[] live = new int[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            boolean isLive = liveCap(capsNow[i]);
            for (double[] zz : zNow) {
                isLive &= !Double.isNaN(zz[i]);
            }
            if (!isLive) {
                continue;
            }
            live[m++] = i;
            double num = 0;
            double den = 0;
            int obs = 0;
            double w = 1;
            for (int r = t - 1; r >= 0; r--, w *= d) {
                if (!Double.isNaN(u[r][i])) {
                    num += w * u[r][i] * u[r][i];
                    den += w;
                    obs++;
                }
            }
            if (obs >= 2) {
                sigma[i] = Math.sqrt(num / den);
            }
        }
        if (m == 0) {
            return sigma;
        }
        // size buckets by capitalisation rank
        double[] keys = new double[m];
        int[] idx = Arrays.copyOf(live, m);
        for (int a = 0; a < m; a++) {
            keys[a] = capsNow[idx[a]];
        }
        Numerics.pairSort(keys, idx);
        int buckets = Math.min(settings.sizeBuckets(), m);
        double q = settings.shrinkage();
        double[] out = Numerics.nanArray(n);
        for (int b = 0; b < buckets; b++) {
            int lo = (int) ((long) b * m / buckets);
            int hi = (int) ((long) (b + 1) * m / buckets);
            double cw = 0;
            double cs = 0;
            int cnt = 0;
            for (int a = lo; a < hi; a++) {
                int i = idx[a];
                if (!Double.isNaN(sigma[i])) {
                    cw += capsNow[i] * sigma[i];
                    cs += capsNow[i];
                    cnt++;
                }
            }
            if (cnt == 0) {
                throw new IllegalArgumentException("size bucket " + b
                        + " has no name with two or more residuals");
            }
            double mean = cw / cs;
            double dev = 0;
            for (int a = lo; a < hi; a++) {
                int i = idx[a];
                if (!Double.isNaN(sigma[i])) {
                    dev += (sigma[i] - mean) * (sigma[i] - mean);
                }
            }
            double delta = Math.sqrt(dev / cnt);
            for (int a = lo; a < hi; a++) {
                int i = idx[a];
                double sh;
                if (Double.isNaN(sigma[i])) {
                    sh = mean;
                } else {
                    double gap = q * Math.abs(sigma[i] - mean);
                    double v = gap == 0 ? 0 : gap / (delta + gap);
                    sh = v * mean + (1 - v) * sigma[i];
                }
                out[i] = sh * sh;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    /** Factor names: {@code MARKET}, then the sectors, then the styles. */
    public List<String> factorNames() {
        return factorNames;
    }

    public int factorCount() {
        return factorNames.size();
    }

    public int sectorCount() {
        return sectorCount;
    }

    public int styleCount() {
        return styleCount;
    }

    public int nameCount() {
        return sectors.length;
    }

    /** Number of estimation periods. */
    public int periods() {
        return factorReturns.length;
    }

    /** Sector index of name {@code i}. */
    public int sector(int i) {
        return sectors[i];
    }

    /** Factor return {@code p} in period {@code t} (NaN when the period was skipped). */
    public double factorReturn(int t, int p) {
        return factorReturns[t][p];
    }

    /** Factor returns of period {@code t}, a copy. */
    public double[] factorReturnsCopy(int t) {
        return factorReturns[t].clone();
    }

    /** Specific return of name {@code i} in period {@code t} (NaN when it took no part). */
    public double residual(int t, int i) {
        return residuals[t][i];
    }

    /**
     * Exposure of name {@code i} to factor {@code p} at the start of
     * period {@code t} (the forecast row when {@code t == periods()} and a
     * forecast row was supplied). NaN for a name that did not take part.
     */
    public double exposureAt(int t, int i, int p) {
        double[] any = zStyles[t].length > 0 ? zStyles[t][0] : null;
        if (p == 0) {
            return any == null || !Double.isNaN(any[i]) ? 1 : Double.NaN;
        }
        if (p <= sectorCount) {
            if (any != null && Double.isNaN(any[i])) {
                return Double.NaN;
            }
            return sectors[i] == p - 1 ? 1 : 0;
        }
        return zStyles[t][p - 1 - sectorCount][i];
    }

    /** Exposure of name {@code i} to factor {@code p} at the forecast date. */
    public double exposure(int i, int p) {
        return exposureAt(zStyles.length - 1, i, p);
    }

    /** Whether name {@code i} has a forecast (live at the forecast date). */
    public boolean covers(int i) {
        return !Double.isNaN(specificVar[i]);
    }

    /** Element {@code (a, b)} of the factor covariance, per period. */
    public double factorCovariance(int a, int b) {
        return factorCov[a][b];
    }

    /** The factor covariance, a copy. */
    public double[][] factorCovarianceCopy() {
        double[][] c = new double[factorCov.length][];
        for (int a = 0; a < c.length; a++) {
            c[a] = factorCov[a].clone();
        }
        return c;
    }

    /** Specific variance of name {@code i}, per period (NaN when not covered). */
    public double specificVariance(int i) {
        return specificVar[i];
    }

    /** The volatility regime multiplier {@code λ_F} applied to factor volatilities (1 when off). */
    public double regimeMultiplier() {
        return regimeMultiplier;
    }

    /** Whether the factor covariance needed a nearest-PSD repair. */
    public boolean repaired() {
        return repaired;
    }

    /**
     * The full {@code N × N} name covariance {@code X F Xᵀ + D}. Rows and
     * columns of names that are not covered are zero.
     */
    public double[][] covariance() {
        int n = sectors.length;
        int p = factorCount();
        double[][] x = exposureMatrix();
        double[][] xf = new double[n][p];
        for (int i = 0; i < n; i++) {
            if (!covers(i)) {
                continue;
            }
            for (int a = 0; a < p; a++) {
                double v = 0;
                for (int b = 0; b < p; b++) {
                    v += x[i][b] * factorCov[b][a];
                }
                xf[i][a] = v;
            }
        }
        double[][] v = new double[n][n];
        for (int i = 0; i < n; i++) {
            if (!covers(i)) {
                continue;
            }
            for (int j = i; j < n; j++) {
                if (!covers(j)) {
                    continue;
                }
                double c = 0;
                for (int a = 0; a < p; a++) {
                    c += xf[i][a] * x[j][a];
                }
                if (i == j) {
                    c += specificVar[i];
                }
                v[i][j] = c;
                v[j][i] = c;
            }
        }
        return v;
    }

    private double[][] exposureMatrix() {
        int n = sectors.length;
        int p = factorCount();
        double[][] x = new double[n][p];
        for (int i = 0; i < n; i++) {
            if (covers(i)) {
                for (int a = 0; a < p; a++) {
                    x[i][a] = exposure(i, a);
                }
            }
        }
        return x;
    }

    /**
     * Risk decomposition of a portfolio (weights as fractions of capital,
     * zero on names that are not covered).
     */
    public Decomposition decompose(double[] weights) {
        int n = sectors.length;
        int p = factorCount();
        if (weights.length != n) {
            throw new IllegalArgumentException("weights must have one entry per name");
        }
        double[] xp = new double[p];
        for (int i = 0; i < n; i++) {
            if (!Double.isFinite(weights[i])) {
                throw new IllegalArgumentException("weight " + i + " is not finite");
            }
            if (weights[i] != 0 && !covers(i)) {
                throw new IllegalArgumentException("name " + i + " has weight but no forecast");
            }
            if (weights[i] != 0) {
                for (int a = 0; a < p; a++) {
                    xp[a] += weights[i] * exposure(i, a);
                }
            }
        }
        double[] fx = new double[p];
        for (int a = 0; a < p; a++) {
            for (int b = 0; b < p; b++) {
                fx[a] += factorCov[a][b] * xp[b];
            }
        }
        double factorVar = LinearAlgebra.dot(xp, fx);
        double specVar = 0;
        double[] vw = new double[n];
        for (int i = 0; i < n; i++) {
            if (!covers(i)) {
                continue;
            }
            double v = 0;
            for (int a = 0; a < p; a++) {
                v += exposure(i, a) * fx[a];
            }
            v += specificVar[i] * weights[i];
            vw[i] = v;
            specVar += weights[i] * weights[i] * specificVar[i];
        }
        double total = factorVar + specVar;
        double sd = Math.sqrt(total);
        double[] marginal = new double[n];
        double[] pct = new double[n];
        double[] fc = new double[p];
        for (int i = 0; i < n; i++) {
            marginal[i] = sd > 0 ? vw[i] / sd : 0;
            pct[i] = total > 0 ? weights[i] * vw[i] / total : 0;
        }
        for (int a = 0; a < p; a++) {
            fc[a] = total > 0 ? xp[a] * fx[a] / total : 0;
        }
        return new Decomposition(factorNames, xp, factorVar, specVar, marginal, pct, fc,
                total > 0 ? specVar / total : 0);
    }

    /**
     * Ex-ante risk of one portfolio under the model. Variances are per
     * period. Percent contributions of the names sum to 1, and the factor
     * contributions plus the specific share sum to 1 (Euler decomposition,
     * Litterman 1996, "Hot Spots and Hedges", Goldman Sachs Risk Management
     * Series).
     */
    public static final class Decomposition {
        private final List<String> factorNames;
        private final double[] exposures;
        private final double factorVariance;
        private final double specificVariance;
        private final double[] marginal;
        private final double[] percent;
        private final double[] factorContribution;
        private final double specificContribution;

        /**
         * Creates the value; arrays are copied.
         *
         * @param exposures            portfolio factor exposures {@code Xᵀw}
         * @param marginal             {@code ∂σ/∂w_i = (Vw)_i/σ}
         * @param percent              {@code w_i (Vw)_i / σ²}
         * @param factorContribution   {@code x_k (F x)_k / σ²}
         * @param specificContribution {@code Σ w_i² d_i / σ²}
         */
        public Decomposition(List<String> factorNames, double[] exposures, double factorVariance,
                             double specificVariance, double[] marginal, double[] percent,
                             double[] factorContribution, double specificContribution) {
            this.factorNames = List.copyOf(factorNames);
            this.exposures = exposures.clone();
            this.factorVariance = factorVariance;
            this.specificVariance = specificVariance;
            this.marginal = marginal.clone();
            this.percent = percent.clone();
            this.factorContribution = factorContribution.clone();
            this.specificContribution = specificContribution;
        }

        public List<String> factorNames() {
            return factorNames;
        }

        /** Portfolio exposure to factor {@code p}. */
        public double exposure(int p) {
            return exposures[p];
        }

        public int exposureLength() {
            return exposures.length;
        }

        public double[] exposuresCopy() {
            return exposures.clone();
        }

        public double factorVariance() {
            return factorVariance;
        }

        public double specificVariance() {
            return specificVariance;
        }

        public double totalVariance() {
            return factorVariance + specificVariance;
        }

        /** Total risk {@code σ}, per period. */
        public double totalRisk() {
            return Math.sqrt(totalVariance());
        }

        public double factorRisk() {
            return Math.sqrt(factorVariance);
        }

        public double specificRisk() {
            return Math.sqrt(specificVariance);
        }

        /** Marginal contribution of name {@code i} to total risk. */
        public double marginal(int i) {
            return marginal[i];
        }

        public double[] marginalCopy() {
            return marginal.clone();
        }

        /** Share of total variance from name {@code i}. */
        public double percent(int i) {
            return percent[i];
        }

        public double[] percentCopy() {
            return percent.clone();
        }

        /** Share of total variance from factor {@code p}. */
        public double factorContribution(int p) {
            return factorContribution[p];
        }

        public double[] factorContributionCopy() {
            return factorContribution.clone();
        }

        /** Share of total variance that is specific. */
        public double specificContribution() {
            return specificContribution;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Decomposition d && factorNames.equals(d.factorNames)
                    && Arrays.equals(exposures, d.exposures)
                    && Double.compare(factorVariance, d.factorVariance) == 0
                    && Double.compare(specificVariance, d.specificVariance) == 0
                    && Arrays.equals(marginal, d.marginal) && Arrays.equals(percent, d.percent)
                    && Arrays.equals(factorContribution, d.factorContribution)
                    && Double.compare(specificContribution, d.specificContribution) == 0;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(factorNames, Arrays.hashCode(exposures), factorVariance,
                    specificVariance, Arrays.hashCode(marginal), Arrays.hashCode(percent),
                    Arrays.hashCode(factorContribution), specificContribution);
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT,
                    "Decomposition[risk=%.6g, factor=%.6g, specific=%.6g, specificShare=%.4f]",
                    totalRisk(), factorRisk(), specificRisk(), specificContribution);
        }
    }
}
