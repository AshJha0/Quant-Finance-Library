package com.quantfinlib.risk;

import java.util.List;

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
 * If the radicand of Δ is negative the calculation is repeated with
 * S<sub>b</sub> = Σ WS<sub>k</sub> unclamped (the MAR21 alternative
 * specification); if that is still negative this class returns 0 and
 * the caller should treat the case as a data error. Curvature uses the
 * squared delta correlations and the ψ rule (cross term dropped when
 * both terms are negative), with CVR from up/down full revaluation.
 * Correlation scenarios: high = min(1.25ρ, 1), low = max(2ρ − 1,
 * 0.75ρ), applied to ρ and γ alike.
 *
 * <p><b>Worked example</b> (own test {@code FrtbSaTest}): two GIRR
 * sensitivities of 100 and −50 at tenors 1y and 2y in one currency,
 * RW 1.6% and 1.3%: WS = 1.6, −0.65; ρ = exp(−0.03·1/1) = 0.97045;
 * K = √(2.56 + 0.4225 − 2·0.97045·1.04) = 0.98182.</p>
 *
 * <p>Correlation matrices are supplied by the caller; the supervisory
 * parameter helpers {@link Girr}, {@link Fx}, {@link Equity},
 * {@link Commodity} and {@link Csr} build the ones this library can
 * state with confidence. Everything else (CSR inter-bucket table,
 * securitisation, equity repo weights) is caller supplied.</p>
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
                double r = sc.apply(rho[k][l]);
                if (curvature) {
                    r = r * r;
                }
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
        double v = radicand(k, s, gamma, sc, curvature);
        if (v < 0) {
            v = radicand(k, raw, gamma, sc, curvature);
        }
        return v < 0 ? 0 : Math.sqrt(v);
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
                double g = sc.apply(gamma[b][c]);
                if (curvature) {
                    g = g * g;
                }
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

    /** Vega risk weight min(RW_σ·√(LH/10), 1) with RW_σ = 0.55 (per MAR21). */
    public static double vegaRiskWeight(int liquidityHorizonDays) {
        return Math.min(0.55 * Math.sqrt(liquidityHorizonDays / 10.0), 1.0);
    }

    /** Vega option-maturity correlation exp(−1%·|T_k − T_l|/min(T_k, T_l)). */
    public static double vegaMaturityCorrelation(double tk, double tl) {
        return Math.exp(-0.01 * Math.abs(tk - tl) / Math.min(tk, tl));
    }

    /** Vega liquidity horizons, days (per MAR21). */
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
        /** Delta risk weights per tenor (per MAR21, d457 values). */
        public static final double[] RISK_WEIGHTS =
                {0.017, 0.017, 0.016, 0.013, 0.012, 0.011, 0.011, 0.011, 0.011, 0.011};
        /** Inflation and cross-currency basis risk weight. */
        public static final double INFLATION_RW = 0.016;
        public static final double XCCY_BASIS_RW = 0.016;
        /** Cross-currency inter-bucket correlation. */
        public static final double GAMMA = 0.5;

        /** Same-curve tenor correlation max(exp(−3%·|ΔT|/min T), 40%). */
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

        /** Risk weight divided by √2 for the specified/domestic currencies (per MAR21). */
        public static double reducedRw(double rw) {
            return rw / Math.sqrt(2);
        }
    }

    /** FX parameters. */
    public static final class Fx {
        private Fx() {
        }

        public static final double RISK_WEIGHT = 0.15;
        public static final double GAMMA = 0.6;

        /** RW / √2 for the specified liquid currency pairs. */
        public static double reducedRw() {
            return RISK_WEIGHT / Math.sqrt(2);
        }
    }

    /** Equity (spot) parameters, buckets 1-13. */
    public static final class Equity {
        private Equity() {
        }

        /** Spot delta risk weights, index = bucket − 1. */
        public static final double[] SPOT_RW =
                {0.55, 0.60, 0.45, 0.55, 0.30, 0.35, 0.40, 0.50, 0.70, 0.50, 0.70, 0.15, 0.25};

        /** Intra-bucket correlation between two different issuers' spot. */
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

        /** Inter-bucket γ: 15% among 1-10, 75% between 12 and 13, 45% index vs 1-10, 0 with 11. */
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

        /** Delta risk weights, index = bucket − 1. */
        public static final double[] RW =
                {0.30, 0.35, 0.60, 0.80, 0.40, 0.45, 0.20, 0.35, 0.25, 0.35, 0.50};
        /** Intra-bucket commodity correlation ρ_cty, index = bucket − 1. */
        public static final double[] RHO_CTY =
                {0.55, 0.95, 0.40, 0.80, 0.60, 0.65, 0.55, 0.45, 0.15, 0.40, 0.15};
        /** Inter-bucket γ (buckets 1-10); bucket 11 takes 0. */
        public static final double GAMMA = 0.20;
        public static final double RHO_TENOR = 0.99;
        public static final double RHO_BASIS = 0.999;
    }

    /** Credit spread risk, non-securitisation. */
    public static final class Csr {
        private Csr() {
        }

        /**
         * Delta risk weights for buckets 1-18, index = bucket − 1, as
         * recalled from d457. Treat as indicative and verify against the
         * text before relying on them; the inter-bucket γ table is not
         * provided and must be supplied by the caller.
         */
        public static final double[] RW = {0.005, 0.01, 0.05, 0.03, 0.03, 0.02, 0.015, 0.025,
            0.02, 0.04, 0.12, 0.07, 0.085, 0.055, 0.05, 0.12, 0.015, 0.05};
        public static final double RHO_NAME = 0.35;
        public static final double RHO_TENOR = 0.65;
        public static final double RHO_BASIS = 0.999;

        /** Intra-bucket ρ = ρ_name·ρ_tenor·ρ_basis (each 1 when equal). */
        public static double intraBucket(boolean sameName, boolean sameTenor, boolean sameBasis) {
            return (sameName ? 1 : RHO_NAME) * (sameTenor ? 1 : RHO_TENOR)
                    * (sameBasis ? 1 : RHO_BASIS);
        }
    }
}
