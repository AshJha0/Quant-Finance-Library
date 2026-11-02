package com.quantfinlib.risk;

import java.util.List;
import java.util.Set;

/**
 * FRTB standardised approach, sensitivities-based method (SBM), styled
 * after BCBS d457 (January 2019) chapter MAR21. Not certified.
 *
 * <p><b>Plain idea.</b> Each risk factor sensitivity is multiplied by a
 * supervisory risk weight. Weighted sensitivities are netted inside a
 * bucket with a correlation, the bucket results are netted across
 * buckets with a second correlation, and the whole calculation is run
 * three times with the correlations scaled up, left alone and scaled
 * down. The capital is the worst of the three totals.</p>
 *
 * <p><b>Formal statement (per MAR21).</b> With weighted sensitivities
 * WS<sub>k</sub> = RW<sub>k</sub>·s<sub>k</sub>:</p>
 * <pre>  K_b = √max(0, Σ WS_k² + Σ_{k≠l} ρ_kl WS_k WS_l)
 *  S_b = max(min(Σ WS_k, K_b), −K_b)
 *  Δ   = √(Σ K_b² + Σ_{b≠c} γ_bc S_b S_c)</pre>
 * For delta and vega the first pass uses S<sub>b</sub> = Σ WS<sub>k</sub>
 * unclamped (MAR21.4(5)(a)); only if that radicand is negative is the
 * calculation repeated with the clamped S<sub>b</sub> above, the
 * alternative specification of MAR21.4(5)(b). If that is still negative
 * this class returns 0. Curvature (MAR21.5) uses S<sub>b</sub> = Σ CVR
 * of the selected scenario, the ψ rule (cross term dropped when both
 * terms are negative) and floors the radicand at 0; the bucket takes
 * the larger of the up and down scenario (MAR21.5(3)(a)), see
 * {@link #curvature(List, List, double[][], Scenario)}. Curvature
 * correlations are the squared delta correlations, and the high and low
 * scenarios are applied to the squared value (MAR21.100, MAR21.101).
 * Correlation scenarios (MAR21.6): high = min(1.25ρ, 1), low =
 * max(2ρ − 1, 0.75ρ), applied to ρ and γ alike.
 *
 * <p><b>Worked example</b> (own test {@code FrtbSaTest}): two GIRR
 * sensitivities of 100 and −50 at tenors 1y and 2y in one currency,
 * RW 1.6% and 1.3%: WS = 1.6, −0.65; ρ = exp(−0.03·1/1) = 0.97045;
 * K = √(2.56 + 0.4225 − 2·0.97045·1.04) = 0.98182.</p>
 *
 * <p>Correlation matrices are supplied by the caller; the supervisory
 * parameter helpers {@link Girr}, {@link Fx}, {@link Equity},
 * {@link Commodity} and {@link Csr} build the ones this library can
 * take from d457 MAR21, each value checked against the text and cited
 * on the constant (published reference, BCBS d457 January 2019).
 * {@link SecNonCtp} covers CSR securitisation (non-CTP). Bucketing
 * decisions (sector, rating, market cap) stay with the caller.</p>
 */
public final class FrtbSa {

    private FrtbSa() {
    }

    /** Correlation scenarios of MAR21. */
    public enum Scenario {
        LOW, MEDIUM, HIGH;

        /** Applies the scenario to a medium correlation. */
        public double apply(double rho) {
            return switch (this) {
                case MEDIUM -> rho;
                case HIGH -> Math.min(1.25 * rho, 1.0);
                case LOW -> Math.max(2.0 * rho - 1.0, 0.75 * rho);
            };
        }
    }

    /**
     * One bucket: weighted sensitivities and the medium-scenario
     * intra-bucket correlation matrix (diagonal ignored).
     */
    public record Bucket(double[] weighted, double[][] rho) {
        public Bucket {
            if (weighted.length != rho.length) {
                throw new IllegalArgumentException("rho must be n x n");
            }
            for (double[] r : rho) {
                if (r.length != weighted.length) {
                    throw new IllegalArgumentException("rho must be n x n");
                }
            }
            weighted = weighted.clone();
            rho = copy(rho);
        }

        @Override
        public double[] weighted() {
            return weighted.clone();
        }

        @Override
        public double[][] rho() {
            return copy(rho);
        }

        /** Bucket with risk weights applied: WS_k = rw[k]·s[k]. */
        public static Bucket of(double[] sens, double[] rw, double[][] rho) {
            if (sens.length != rw.length) {
                throw new IllegalArgumentException("sens and rw must align");
            }
            double[] ws = new double[sens.length];
            for (int i = 0; i < ws.length; i++) {
                ws[i] = sens[i] * rw[i];
            }
            return new Bucket(ws, rho);
        }
    }

    /** Bucket-level result. */
    public record BucketResult(double k, double s) {
    }

    static double bucketK(double[] ws, double[][] rho, Scenario sc, boolean curvature) {
        double sum = 0;
        for (int k = 0; k < ws.length; k++) {
            double a = curvature ? Math.max(ws[k], 0) : ws[k];
            sum += a * a;
            for (int l = 0; l < ws.length; l++) {
                if (l == k) {
                    continue;
                }
                if (curvature && ws[k] < 0 && ws[l] < 0) {
                    continue;
                }
                double r = curvature ? sc.apply(rho[k][l] * rho[k][l]) : sc.apply(rho[k][l]);
                sum += r * ws[k] * ws[l];
            }
        }
        return Math.sqrt(Math.max(0, sum));
    }

    /** K_b and clamped S_b of one bucket (delta or vega). */
    public static BucketResult bucket(Bucket b, Scenario sc) {
        return bucketResult(b, sc, false);
    }

    private static BucketResult bucketResult(Bucket b, Scenario sc, boolean curvature) {
        double k = bucketK(b.weighted(), b.rho(), sc, curvature);
        double sum = 0;
        for (double w : b.weighted()) {
            sum += w;
        }
        return new BucketResult(k, Math.max(-k, Math.min(k, sum)));
    }

    /**
     * Delta or vega capital of one risk class under one scenario.
     *
     * @param gamma medium-scenario inter-bucket correlation, nb x nb
     */
    public static double deltaVega(List<Bucket> buckets, double[][] gamma, Scenario sc) {
        return aggregate(buckets, gamma, sc, false);
    }

    /**
     * Curvature capital of one risk class under one scenario. Each
     * bucket's {@code weighted} entries are CVR_k (see {@link #cvr});
     * ρ and γ are the delta correlations, squared internally.
     */
    public static double curvature(List<Bucket> buckets, double[][] gamma, Scenario sc) {
        return aggregate(buckets, gamma, sc, true);
    }

    private static double aggregate(List<Bucket> buckets, double[][] gamma, Scenario sc,
            boolean curvature) {
        int n = buckets.size();
        if (gamma.length != n) {
            throw new IllegalArgumentException("gamma must be nb x nb");
        }
        double[] k = new double[n];
        double[] s = new double[n];
        double[] raw = new double[n];
        for (int b = 0; b < n; b++) {
            BucketResult r = bucketResult(buckets.get(b), sc, curvature);
            k[b] = r.k();
            s[b] = r.s();
            for (double w : buckets.get(b).weighted()) {
                raw[b] += w;
            }
        }
        return combine(k, raw, s, gamma, sc, curvature);
    }

    /** MAR21.4(5): unclamped S_b first, clamped alternative; curvature floors at 0. */
    private static double combine(double[] k, double[] raw, double[] clamped, double[][] gamma,
            Scenario sc, boolean curvature) {
        double v = radicand(k, raw, gamma, sc, curvature);
        if (v < 0 && !curvature) {
            v = radicand(k, clamped, gamma, sc, false);
        }
        return v < 0 ? 0 : Math.sqrt(v);
    }

    /**
     * Delta or vega capital where some buckets are "other sector"
     * buckets (equity 11, CSR non-securitisation 16, securitisation
     * non-CTP 25): their K_b is Σ|WS_k| with no correlation
     * (MAR21.56(1), MAR21.69(1), MAR21.79(1)). {@code otherSector} holds
     * zero-based indices into {@code buckets}.
     */
    public static double deltaVega(List<Bucket> buckets, double[][] gamma, Scenario sc,
            Set<Integer> otherSector) {
        int n = buckets.size();
        if (gamma.length != n) {
            throw new IllegalArgumentException("gamma must be nb x nb");
        }
        double[] k = new double[n];
        double[] s = new double[n];
        double[] raw = new double[n];
        for (int b = 0; b < n; b++) {
            double[] ws = buckets.get(b).weighted();
            for (double w : ws) {
                raw[b] += w;
            }
            k[b] = otherSector.contains(b) ? otherSectorK(ws)
                    : bucketK(ws, buckets.get(b).rho(), sc, false);
            s[b] = Math.max(-k[b], Math.min(k[b], raw[b]));
        }
        return combine(k, raw, s, gamma, sc, false);
    }

    /** Other-sector bucket delta or vega K_b = Σ|WS_k| (MAR21.56(1), MAR21.79(1)). */
    public static double otherSectorK(double[] weighted) {
        double t = 0;
        for (double w : weighted) {
            t += Math.abs(w);
        }
        return t;
    }

    /**
     * Other-sector bucket curvature K_b = max(Σ max(CVR⁺, 0), Σ max(CVR⁻, 0))
     * (MAR21.56(2), MAR21.79(2)).
     */
    public static double otherSectorCurvatureK(double[] cvrUp, double[] cvrDown) {
        double u = 0;
        double d = 0;
        for (double v : cvrUp) {
            u += Math.max(v, 0);
        }
        for (double v : cvrDown) {
            d += Math.max(v, 0);
        }
        return Math.max(u, d);
    }

    /**
     * Curvature capital with both shock directions (MAR21.5(3) and (4)).
     * {@code up.get(b)} and {@code down.get(b)} hold CVR⁺_k and CVR⁻_k of
     * bucket b with the delta ρ (squared internally). Each bucket takes
     * the larger of K⁺ and K⁻; on a tie the up scenario is chosen if
     * Σ CVR⁺ &gt; Σ CVR⁻. S_b is Σ CVR of the selected scenario, and the
     * selection is made afresh under each correlation scenario.
     */
    public static double curvature(List<Bucket> up, List<Bucket> down, double[][] gamma,
            Scenario sc) {
        int n = up.size();
        if (down.size() != n || gamma.length != n) {
            throw new IllegalArgumentException("up, down and gamma must have nb buckets");
        }
        double[] k = new double[n];
        double[] s = new double[n];
        for (int b = 0; b < n; b++) {
            double[] u = up.get(b).weighted();
            double[] d = down.get(b).weighted();
            double ku = bucketK(u, up.get(b).rho(), sc, true);
            double kd = bucketK(d, down.get(b).rho(), sc, true);
            double su = 0;
            double sd = 0;
            for (double v : u) {
                su += v;
            }
            for (double v : d) {
                sd += v;
            }
            boolean pickUp = ku > kd || (ku == kd && su > sd);
            k[b] = pickUp ? ku : kd;
            s[b] = pickUp ? su : sd;
        }
        return combine(k, s, s, gamma, sc, true);
    }


    private static double radicand(double[] k, double[] s, double[][] gamma, Scenario sc,
            boolean curvature) {
        double sum = 0;
        for (int b = 0; b < k.length; b++) {
            sum += k[b] * k[b];
            for (int c = 0; c < k.length; c++) {
                if (c == b || (curvature && s[b] < 0 && s[c] < 0)) {
                    continue;
                }
                double g = curvature
                        ? sc.apply(gamma[b][c] * gamma[b][c]) : sc.apply(gamma[b][c]);
                sum += g * s[b] * s[c];
            }
        }
        return sum;
    }

    /**
     * Curvature risk charge CVR_k = −min(V⁺ − V₀ − RW·s, V⁻ − V₀ + RW·s),
     * where V± are values after an up/down shift of size RW (the
     * curvature risk weight) and s is the delta sensitivity per unit of
     * that shift, so RW·s is the delta-implied P&amp;L of the up shift.
     */
    public static double cvr(double vUp, double vBase, double vDown, double deltaPnlUp) {
        return -Math.min(vUp - vBase - deltaPnlUp, vDown - vBase + deltaPnlUp);
    }

    /** One risk class: delta, vega and curvature inputs (any may be empty). */
    public record RiskClass(List<Bucket> delta, double[][] deltaGamma,
            List<Bucket> vega, double[][] vegaGamma,
            List<Bucket> curvature, double[][] curvatureGamma) {
        public RiskClass {
            delta = List.copyOf(delta);
            vega = List.copyOf(vega);
            curvature = List.copyOf(curvature);
            deltaGamma = copy(deltaGamma);
            vegaGamma = copy(vegaGamma);
            curvatureGamma = copy(curvatureGamma);
        }

        @Override
        public double[][] deltaGamma() {
            return copy(deltaGamma);
        }

        @Override
        public double[][] vegaGamma() {
            return copy(vegaGamma);
        }

        @Override
        public double[][] curvatureGamma() {
            return copy(curvatureGamma);
        }
    }

    private static double[][] copy(double[][] m) {
        if (m == null) {
            return null;
        }
        double[][] c = new double[m.length][];
        for (int i = 0; i < m.length; i++) {
            c[i] = m[i].clone();
        }
        return c;
    }

    /** Total SBM charge of a set of risk classes under one scenario. */
    public static double total(List<RiskClass> classes, Scenario sc) {
        double t = 0;
        for (RiskClass rc : classes) {
            t += deltaVega(rc.delta(), rc.deltaGamma(), sc);
            t += deltaVega(rc.vega(), rc.vegaGamma(), sc);
            t += curvature(rc.curvature(), rc.curvatureGamma(), sc);
        }
        return t;
    }

    /** SBM capital: the maximum of the three scenario totals. */
    public static double sbmCapital(List<RiskClass> classes) {
        double m = 0;
        for (Scenario sc : Scenario.values()) {
            m = Math.max(m, total(classes, sc));
        }
        return m;
    }

    /**
     * Vega risk weight min(RW_σ·√(LH/10), 1) with RW_σ = 0.55 (MAR21.92
     * footnote 24). Verified: gives 100% for every class except equity
     * large cap and indices, 0.55·√2 = 77.78% (MAR21.92 Table 13).
     */
    public static double vegaRiskWeight(int liquidityHorizonDays) {
        return Math.min(0.55 * Math.sqrt(liquidityHorizonDays / 10.0), 1.0);
    }

    /** Vega option-maturity correlation exp(−1%·|T_k − T_l|/min(T_k, T_l)) (MAR21.93(1), verified). */
    public static double vegaMaturityCorrelation(double tk, double tl) {
        return Math.exp(-0.01 * Math.abs(tk - tl) / Math.min(tk, tl));
    }

    /** Vega liquidity horizons, days (MAR21.92 Table 13, verified; CSR securitisation also 120). */
    public static final int VEGA_LH_GIRR = 60;
    public static final int VEGA_LH_CSR = 120;
    public static final int VEGA_LH_EQUITY_LARGE_CAP = 20;
    public static final int VEGA_LH_EQUITY_SMALL_CAP = 60;
    public static final int VEGA_LH_COMMODITY = 120;
    public static final int VEGA_LH_FX = 40;

    /** Matrix with constant off-diagonal value. */
    public static double[][] constant(int n, double rho) {
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                m[i][j] = i == j ? 1.0 : rho;
            }
        }
        return m;
    }

    /** General interest rate risk parameters. */
    public static final class Girr {
        private Girr() {
        }

        /** The ten GIRR delta tenors, years. */
        public static final double[] TENORS = {0.25, 0.5, 1, 2, 3, 5, 10, 15, 20, 30};
        /** Delta risk weights per tenor (MAR21.42 Table 1, verified). */
        public static final double[] RISK_WEIGHTS =
                {0.017, 0.017, 0.016, 0.013, 0.012, 0.011, 0.011, 0.011, 0.011, 0.011};
        /** Inflation and cross-currency basis risk weight (MAR21.43, verified). */
        public static final double INFLATION_RW = 0.016;
        public static final double XCCY_BASIS_RW = 0.016;
        /** Cross-currency inter-bucket correlation (MAR21.50, verified). */
        public static final double GAMMA = 0.5;
        /** Same tenor, different curve, same currency (MAR21.45). */
        public static final double RHO_DIFFERENT_CURVE = 0.999;
        /** Inflation curve against any tenor of the yield curve (MAR21.48). */
        public static final double RHO_INFLATION = 0.4;
        /** Cross-currency basis against yield curve, inflation or another basis curve (MAR21.49). */
        public static final double RHO_XCCY_BASIS = 0.0;

        /**
         * Different tenor and different curve: the Table 2 value times
         * 99.9% (MAR21.47; footnote 14 gives 88.69%·0.999 = 88.60% for
         * 1y against 5y). Equal tenors give 99.9% (MAR21.45).
         */
        public static double differentCurveCorrelation(double tk, double tl) {
            return tenorCorrelation(tk, tl) * RHO_DIFFERENT_CURVE;
        }

        /** Same-curve tenor correlation max(exp(−3%·|ΔT|/min T), 40%) (MAR21.46 footnote 13, verified). */
        public static double tenorCorrelation(double tk, double tl) {
            return Math.max(Math.exp(-0.03 * Math.abs(tk - tl) / Math.min(tk, tl)), 0.4);
        }

        /** Tenor correlation matrix for the given tenors on one curve. */
        public static double[][] tenorMatrix(double[] tenors) {
            double[][] m = new double[tenors.length][tenors.length];
            for (int i = 0; i < tenors.length; i++) {
                for (int j = 0; j < tenors.length; j++) {
                    m[i][j] = tenorCorrelation(tenors[i], tenors[j]);
                }
            }
            return m;
        }

        /** Risk weight divided by √2 for EUR, USD, GBP, AUD, JPY, SEK, CAD and the domestic currency (MAR21.44). */
        public static double reducedRw(double rw) {
            return rw / Math.sqrt(2);
        }
    }

    /** FX parameters. */
    public static final class Fx {
        private Fx() {
        }

        /** FX delta risk weight (MAR21.87, verified). */
        public static final double RISK_WEIGHT = 0.15;
        /** FX inter-bucket correlation (MAR21.89, verified). */
        public static final double GAMMA = 0.6;

        /** RW / √2 for the specified currency pairs and their first-order crosses (MAR21.88). */
        public static double reducedRw() {
            return RISK_WEIGHT / Math.sqrt(2);
        }
    }

    /** Equity (spot) parameters, buckets 1-13. */
    public static final class Equity {
        private Equity() {
        }

        /** Spot delta risk weights, index = bucket − 1 (MAR21.77 Table 10, verified). */
        public static final double[] SPOT_RW =
                {0.55, 0.60, 0.45, 0.55, 0.30, 0.35, 0.40, 0.50, 0.70, 0.50, 0.70, 0.15, 0.25};
        /** Repo-rate delta risk weights, index = bucket − 1 (MAR21.77 Table 10). */
        public static final double[] REPO_RW = {0.0055, 0.0060, 0.0045, 0.0055, 0.0030, 0.0035,
            0.0040, 0.0050, 0.0070, 0.0050, 0.0070, 0.0015, 0.0025};
        /** Spot against repo of the same issuer (MAR21.78(1)). */
        public static final double RHO_SPOT_REPO_SAME_ISSUER = 0.999;

        /**
         * Spot against repo of different issuers in the same bucket: the
         * spot-spot value times 99.9% (MAR21.78(4)). Repo against repo
         * uses {@link #intraBucket} unchanged (MAR21.78(3)).
         */
        public static double spotRepoDifferentIssuer(int bucket) {
            return intraBucket(bucket) * 0.999;
        }

        /** Intra-bucket correlation between two different issuers' spot (MAR21.78(2), verified). */
        public static double intraBucket(int bucket) {
            if (bucket >= 1 && bucket <= 4) {
                return 0.15;
            }
            if (bucket >= 5 && bucket <= 8) {
                return 0.25;
            }
            if (bucket == 9) {
                return 0.075;
            }
            if (bucket == 10) {
                return 0.125;
            }
            if (bucket == 12 || bucket == 13) {
                return 0.80;
            }
            if (bucket == 11) {
                throw new IllegalArgumentException("bucket 11 is aggregated by simple sum");
            }
            throw new IllegalArgumentException("bucket 1..13");
        }

        /** Inter-bucket γ: 15% among 1-10, 75% between 12 and 13, 45% otherwise, 0 with 11 (MAR21.80, verified). */
        public static double gamma(int b, int c) {
            if (b == 11 || c == 11) {
                return 0;
            }
            boolean bi = b >= 12;
            boolean ci = c >= 12;
            if (bi && ci) {
                return 0.75;
            }
            if (bi || ci) {
                return 0.45;
            }
            return 0.15;
        }
    }

    /** Commodity parameters, buckets 1-11. */
    public static final class Commodity {
        private Commodity() {
        }

        /** Delta risk weights, index = bucket − 1 (MAR21.82 Table 11, verified). */
        public static final double[] RW =
                {0.30, 0.35, 0.60, 0.80, 0.40, 0.45, 0.20, 0.35, 0.25, 0.35, 0.50};
        /** Intra-bucket commodity correlation ρ_cty, index = bucket − 1 (MAR21.83 Table 12, verified). */
        public static final double[] RHO_CTY =
                {0.55, 0.95, 0.40, 0.80, 0.60, 0.65, 0.55, 0.45, 0.15, 0.40, 0.15};
        /** Inter-bucket γ (buckets 1-10); bucket 11 takes 0 (MAR21.85, verified). */
        public static final double GAMMA = 0.20;
        /** Different tenor (MAR21.83(2), verified). */
        public static final double RHO_TENOR = 0.99;
        /** Different delivery location (MAR21.83(3), verified). */
        public static final double RHO_BASIS = 0.999;

        /** Inter-bucket γ for buckets 1-11 (MAR21.85). */
        public static double gamma(int b, int c) {
            if (b < 1 || b > 11 || c < 1 || c > 11) {
                throw new IllegalArgumentException("bucket 1..11");
            }
            if (b == c) {
                return 1.0;
            }
            return b == 11 || c == 11 ? 0.0 : GAMMA;
        }
    }

    /** Credit spread risk, non-securitisation (MAR21.51 to MAR21.57). */
    public static final class Csr {
        private Csr() {
        }

        /**
         * Delta risk weights for buckets 1-18, index = bucket − 1
         * (MAR21.53 Table 4, verified against d457). Covered bonds rated
         * AA- or better may use 1.5% instead of bucket 8's 2.5%
         * (footnote 17); that choice is the caller's.
         */
        public static final double[] RW = {0.005, 0.01, 0.05, 0.03, 0.03, 0.02, 0.015, 0.025,
            0.02, 0.04, 0.12, 0.07, 0.085, 0.055, 0.05, 0.12, 0.015, 0.05};
        /** Different names, buckets 1-15 (MAR21.54(1), verified). */
        public static final double RHO_NAME = 0.35;
        /** Different names, index buckets 17-18 (MAR21.55(1)). */
        public static final double RHO_NAME_INDEX = 0.80;
        /** Different tenor (MAR21.54(2), verified). */
        public static final double RHO_TENOR = 0.65;
        /** Different curve (bond against CDS) (MAR21.54(3), verified). */
        public static final double RHO_BASIS = 0.999;
        /** Inter-bucket rating factor when IG meets HY/NR, buckets 1-15 (MAR21.57(1)). */
        public static final double GAMMA_RATING = 0.5;

        /** Intra-bucket ρ = ρ_name·ρ_tenor·ρ_basis for buckets 1-15 (MAR21.54). */
        public static double intraBucket(boolean sameName, boolean sameTenor, boolean sameBasis) {
            return (sameName ? 1 : RHO_NAME) * (sameTenor ? 1 : RHO_TENOR)
                    * (sameBasis ? 1 : RHO_BASIS);
        }

        /** Intra-bucket ρ for index buckets 17-18 (MAR21.55). */
        public static double intraBucketIndex(boolean sameName, boolean sameTenor,
                boolean sameBasis) {
            return (sameName ? 1 : RHO_NAME_INDEX) * (sameTenor ? 1 : RHO_TENOR)
                    * (sameBasis ? 1 : RHO_BASIS);
        }

        /**
         * Delta ρ to feed into curvature for CSR non-securitisation and
         * CTP: only ρ_name applies (MAR21.100(1)); {@link FrtbSa#curvature}
         * squares it.
         */
        public static double curvatureRho(boolean sameName, boolean indexBucket) {
            return sameName ? 1 : indexBucket ? RHO_NAME_INDEX : RHO_NAME;
        }

        /** Sector table, MAR21.57 Table 5, by sector index 0..10 = 1/9 .. 7/15, 8, 16, 17, 18. */
        private static final double[][] SECTOR = {
            {1, 0.75, 0.10, 0.20, 0.25, 0.20, 0.15, 0.10, 0, 0.45, 0.45},
            {0.75, 1, 0.05, 0.15, 0.20, 0.15, 0.10, 0.10, 0, 0.45, 0.45},
            {0.10, 0.05, 1, 0.05, 0.15, 0.20, 0.05, 0.20, 0, 0.45, 0.45},
            {0.20, 0.15, 0.05, 1, 0.20, 0.25, 0.05, 0.05, 0, 0.45, 0.45},
            {0.25, 0.20, 0.15, 0.20, 1, 0.25, 0.05, 0.15, 0, 0.45, 0.45},
            {0.20, 0.15, 0.20, 0.25, 0.25, 1, 0.05, 0.20, 0, 0.45, 0.45},
            {0.15, 0.10, 0.05, 0.05, 0.05, 0.05, 1, 0.05, 0, 0.45, 0.45},
            {0.10, 0.10, 0.20, 0.05, 0.15, 0.20, 0.05, 1, 0, 0.45, 0.45},
            {0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0},
            {0.45, 0.45, 0.45, 0.45, 0.45, 0.45, 0.45, 0.45, 0, 1, 0.75},
            {0.45, 0.45, 0.45, 0.45, 0.45, 0.45, 0.45, 0.45, 0, 0.75, 1},
        };

        private static int sector(int b) {
            if (b >= 1 && b <= 7) {
                return b - 1;
            }
            if (b >= 9 && b <= 15) {
                return b - 9;
            }
            return switch (b) {
                case 8 -> 7;
                case 16 -> 8;
                case 17 -> 9;
                case 18 -> 10;
                default -> throw new IllegalArgumentException("bucket 1..18");
            };
        }

        /**
         * Inter-bucket γ = γ_rating·γ_sector (MAR21.57). γ_rating is 50%
         * when both buckets are in 1-15 and one is IG (1-8) while the
         * other is HY/NR (9-15), else 1; γ_sector is Table 5. Example:
         * buckets 1 and 10 give 0.5·0.75 = 0.375.
         */
        public static double gamma(int b, int c) {
            int sb = sector(b);
            int sc = sector(c);
            if (b == c) {
                return 1.0;
            }
            boolean bothRated = b <= 15 && c <= 15;
            double rating = bothRated && (b <= 8) != (c <= 8) ? GAMMA_RATING : 1.0;
            return rating * SECTOR[sb][sc];
        }

        /** CTP delta risk weights, buckets 1-16 (MAR21.59 Table 6). */
        public static final double[] CTP_RW = {0.04, 0.04, 0.08, 0.05, 0.04, 0.03, 0.02, 0.06,
            0.13, 0.13, 0.16, 0.10, 0.12, 0.12, 0.12, 0.13};
        /** CTP different-curve correlation (MAR21.60(1)). */
        public static final double RHO_BASIS_CTP = 0.99;

        /** CTP intra-bucket ρ (MAR21.60); γ is {@link #gamma} for buckets 1-16 (MAR21.61). */
        public static double intraBucketCtp(boolean sameName, boolean sameTenor,
                boolean sameBasis) {
            return (sameName ? 1 : RHO_NAME) * (sameTenor ? 1 : RHO_TENOR)
                    * (sameBasis ? 1 : RHO_BASIS_CTP);
        }
    }

    /**
     * CSR securitisation (non-CTP), buckets 1-25 (MAR21.62 to MAR21.71).
     * Buckets 1-8 senior IG, 9-16 non-senior IG (RW ×1.25), 17-24 HY and
     * non-rated (RW ×1.75), 25 other sector at 3.5%. Across buckets 1-24
     * γ = 0; bucket 25 is aggregated by Σ|WS| ({@link FrtbSa#otherSectorK})
     * and added to the risk-class total with no diversification
     * (MAR21.71).
     */
    public static final class SecNonCtp {
        private SecNonCtp() {
        }

        /** Senior IG risk weights, buckets 1-8 (MAR21.64 Table 8). */
        public static final double[] SENIOR_IG_RW =
            {0.009, 0.015, 0.02, 0.02, 0.008, 0.012, 0.012, 0.014};
        /** Bucket 25 risk weight (MAR21.67). */
        public static final double OTHER_RW = 0.035;
        public static final double RHO_TRANCHE = 0.40;
        public static final double RHO_TENOR = 0.80;
        public static final double RHO_BASIS = 0.999;

        /** Risk weight of bucket 1-25 (MAR21.64 to MAR21.67). */
        public static double riskWeight(int bucket) {
            if (bucket >= 1 && bucket <= 8) {
                return SENIOR_IG_RW[bucket - 1];
            }
            if (bucket >= 9 && bucket <= 16) {
                return SENIOR_IG_RW[bucket - 9] * 1.25;
            }
            if (bucket >= 17 && bucket <= 24) {
                return SENIOR_IG_RW[bucket - 17] * 1.75;
            }
            if (bucket == 25) {
                return OTHER_RW;
            }
            throw new IllegalArgumentException("bucket 1..25");
        }

        /** Intra-bucket ρ = ρ_tranche·ρ_tenor·ρ_basis (MAR21.68). */
        public static double intraBucket(boolean sameTranche, boolean sameTenor,
                boolean sameBasis) {
            return (sameTranche ? 1 : RHO_TRANCHE) * (sameTenor ? 1 : RHO_TENOR)
                    * (sameBasis ? 1 : RHO_BASIS);
        }

        /** Inter-bucket γ across buckets 1-24 is 0 (MAR21.70). */
        public static final double GAMMA = 0.0;
    }
}
