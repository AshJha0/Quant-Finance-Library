package com.quantfinlib.risk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * FRTB standardised default risk charge (DRC) for securitisations, both
 * outside the correlation trading portfolio (non-CTP, MAR22.27 to
 * MAR22.35) and inside it (CTP, MAR22.36 to MAR22.45). Styled after BCBS
 * d457 (January 2019) and the consolidated Basel Framework chapter MAR22;
 * risk weights from the securitisation framework CRE40 to CRE42. Not
 * certified.
 *
 * <p><b>Plain idea.</b> A tranche's loss on default is already priced in
 * its banking-book risk weight, so the jump-to-default amount is simply
 * the tranche's market value. Longs and shorts net only within exactly
 * the same tranche of the same pool (or index series). Inside a bucket the
 * risk-weighted shorts offset the longs only to the extent of the hedge
 * benefit ratio, as for non-securitisations. Non-CTP buckets are summed
 * with no diversification. CTP buckets (one per index) may go negative
 * and are aggregated with a second 50% haircut on net-short buckets.</p>
 *
 * <p><b>Formal statement.</b></p>
 * <pre>
 *  gross JTD = market value (signed)                 MAR22.27, MAR22.36, MAR22.37
 *  nth-to-default: A = (N-1)/names, D = N/names      MAR22.38
 *  net JTD  = Σ JTD_i·min(max(M_i, 0.25), 1)  within one tranche   MAR22.29, 22.30, 22.39
 *  non-CTP: DRC_b = max(Σ RW·long − HBR_b·Σ RW·|short|, 0); DRC = Σ_b DRC_b   MAR22.33, 22.35
 *  CTP:     DRC_b = Σ RW·long − HBR_ctp·Σ RW·|short|   (no floor)            MAR22.44
 *           DRC_ctp = max(Σ_b (max(DRC_b,0) + 0.5·min(DRC_b,0)), 0)          MAR22.45
 * </pre>
 *
 * <p><b>Risk weights.</b> MAR22.34(1) and MAR22.42 take the banking-book
 * securitisation risk weight with the maturity component set to zero, ie a
 * one-year tranche maturity. This class implements SEC-SA (CRE41.1 to
 * CRE41.22) and the one-year columns of SEC-ERBA (CRE42.2 to CRE42.14).
 * SEC-IRBA (CRE44) needs the bank's own K_IRB for the pool, so a weight
 * from it is taken as caller input in {@link NetJtd#riskWeight()}. The
 * banking-book weight is a multiple of exposure that includes the 8%
 * capital ratio (1250% means a deduction). Turning it into a DRC weight
 * per unit of JTD is done here by multiplying by 8% ({@link #drcRiskWeight});
 * the Basel text does not print that factor, so it is an interpretation,
 * and it is the one consistent with the 1250% weight producing a charge
 * equal to the full market value (the fair-value cap of MAR22.34(3)).
 * Non-tranched CTP products use the non-securitisation weights of
 * MAR22.24 ({@link FrtbDrc.CreditQuality}), MAR22.43.</p>
 *
 * <p><b>Worked example</b> (published reference, MAR22.45): CTP index
 * buckets of +100 (CDX North America IG) and −100 (Major Sovereign) give
 * a total of 100 − 0.5·100 = 50.</p>
 */
public final class FrtbDrcSecuritisation {

    private FrtbDrcSecuritisation() {
    }

    /** The 1250% banking-book risk weight (deduction), as a multiple. */
    public static final double RW_1250 = 12.5;

    /** A net JTD of one tranche (signed, positive long) and its DRC risk weight per unit of JTD. */
    public record NetJtd(double jtd, double riskWeight) {
        public NetJtd {
            if (!Double.isFinite(jtd) || !(riskWeight >= 0) || !Double.isFinite(riskWeight)) {
                throw new IllegalArgumentException("need finite jtd and riskWeight >= 0");
            }
        }
    }

    // ---------------------------------------------------------------- JTD

    /**
     * Gross JTD of a securitisation position or of a CTP single-name or
     * index hedge: its signed market value, no LGD applied (MAR22.27,
     * MAR22.36, MAR22.37).
     */
    public static double grossJtd(double signedMarketValue) {
        if (!Double.isFinite(signedMarketValue)) {
            throw new IllegalArgumentException("market value must be finite");
        }
        return signedMarketValue;
    }

    /** Attachment point (N − 1) / names of an nth-to-default product (MAR22.38(1)). */
    public static double nthToDefaultAttachment(int n, int totalNames) {
        checkNtd(n, totalNames);
        return (n - 1) / (double) totalNames;
    }

    /** Detachment point N / names of an nth-to-default product (MAR22.38(2)). */
    public static double nthToDefaultDetachment(int n, int totalNames) {
        checkNtd(n, totalNames);
        return n / (double) totalNames;
    }

    private static void checkNtd(int n, int totalNames) {
        if (n < 1 || n > totalNames) {
            throw new IllegalArgumentException("need 1 <= N <= total names");
        }
    }

    /**
     * Net JTD of one specific tranche (same pool or index series and the
     * same attachment and detachment points) across maturities, with the
     * one-year scaling and three-month floor of MAR22.15 to MAR22.18
     * (MAR22.30, MAR22.39). Different tranches, pools, series or index
     * families must not be passed together (MAR22.29, MAR22.39(4)); a
     * replication by decomposition (MAR22.28, MAR22.30(1)-(2), MAR22.39(2)-(3))
     * is the caller's job before this call.
     */
    public static double netTranche(double[] grossJtd, double[] maturityYears) {
        return FrtbDrc.netObligor(grossJtd, maturityYears);
    }

    // ------------------------------------------------------- risk weights

    /** Converts a banking-book securitisation risk weight (a multiple, 12.5 = 1250%) to a DRC weight: ×8% (see class note). */
    public static double drcRiskWeight(double bankingBookRiskWeight) {
        if (!(bankingBookRiskWeight >= 0) || !Double.isFinite(bankingBookRiskWeight)) {
            throw new IllegalArgumentException("risk weight must be >= 0 and finite");
        }
        return 0.08 * bankingBookRiskWeight;
    }

    /** K_A = (1 − W)·K_SA + 0.5·W (CRE41.8). */
    public static double kA(double kSa, double w) {
        if (!(kSa >= 0 && kSa <= 1) || !(w >= 0 && w <= 1)) {
            throw new IllegalArgumentException("need K_SA and W in [0, 1]");
        }
        return (1 - w) * kSa + 0.5 * w;
    }

    /**
     * K_SSFA = (e^{a·u} − e^{a·l}) / (a·(u − l)) with a = −1/(p·K_A),
     * u = D − K_A, l = max(A − K_A, 0) (CRE41.11).
     */
    public static double kSsfa(double kA, double attach, double detach, double p) {
        double a = -1 / (p * kA);
        double u = detach - kA;
        double l = Math.max(attach - kA, 0);
        return (Math.exp(a * u) - Math.exp(a * l)) / (a * (u - l));
    }

    /**
     * SEC-SA banking-book risk weight as a multiple (CRE41.11 to CRE41.22).
     * p = 1 (CRE41.12), 1.5 for a resecuritisation (CRE41.16(3)), 0.5 for
     * STC (CRE41.21). D ≤ K_A gives 1250% (CRE41.13(1)); A ≥ K_A gives
     * 12.5·K_SSFA (CRE41.13(2)); otherwise the weighted average of
     * CRE41.13(3). Floors: 15% (CRE41.15), 100% for a resecuritisation
     * (CRE41.18), STC 10% senior and 15% non-senior (CRE41.22). The
     * no-lower-than-next-senior-rated rule of CRE41.15 and the unknown-W
     * adjustment of CRE41.9 are left to the caller.
     *
     * @param kA     K_A from {@link #kA}
     * @param attach A in [0, 1)
     * @param detach D in (A, 1]
     */
    public static double secSaRiskWeight(double kA, double attach, double detach,
                                         boolean resecuritisation, boolean stc, boolean senior) {
        if (!(attach >= 0 && detach > attach && detach <= 1) || !(kA > 0 && kA <= 1)) {
            throw new IllegalArgumentException("need 0 <= A < D <= 1 and 0 < K_A <= 1");
        }
        if (resecuritisation && stc) {
            throw new IllegalArgumentException("a resecuritisation cannot be STC");
        }
        double p = resecuritisation ? 1.5 : stc ? 0.5 : 1.0;
        double rw;
        if (detach <= kA) {
            rw = RW_1250;
        } else if (attach >= kA) {
            rw = 12.5 * kSsfa(kA, attach, detach, p);
        } else {
            rw = 12.5 * (kA - attach) / (detach - attach)
                    + 12.5 * kSsfa(kA, attach, detach, p) * (detach - kA) / (detach - attach);
        }
        double floor = resecuritisation ? 1.0 : stc ? (senior ? 0.10 : 0.15) : 0.15;
        return Math.max(rw, floor);
    }

    /** Long-term rating grades of SEC-ERBA Tables 2 and 4 (CRE42.4, CRE42.13). */
    public enum ErbaRating {
        AAA(0.15, 0.15, 0.10, 0.15), AA_PLUS(0.15, 0.15, 0.10, 0.15), AA(0.25, 0.30, 0.15, 0.15),
        AA_MINUS(0.30, 0.40, 0.15, 0.25), A_PLUS(0.40, 0.60, 0.20, 0.35), A(0.50, 0.80, 0.30, 0.60),
        A_MINUS(0.60, 1.20, 0.35, 0.95), BBB_PLUS(0.75, 1.70, 0.45, 1.50), BBB(0.90, 2.20, 0.55, 1.80),
        BBB_MINUS(1.20, 3.30, 0.70, 2.70), BB_PLUS(1.40, 4.70, 1.20, 4.05), BB(1.60, 6.20, 1.35, 5.35),
        BB_MINUS(2.00, 7.50, 1.70, 6.45), B_PLUS(2.50, 9.00, 2.25, 8.10), B(3.10, 10.50, 2.80, 9.45),
        B_MINUS(3.80, 11.30, 3.40, 10.15), CCC(4.60, 12.50, 4.15, 12.50),
        BELOW_CCC_MINUS(12.5, 12.5, 12.5, 12.5);

        private final double senior;
        private final double nonSenior;
        private final double stcSenior;
        private final double stcNonSenior;

        /** One-year columns: Table 2 senior, non-senior; Table 4 senior, non-senior. */
        ErbaRating(double senior, double nonSenior, double stcSenior, double stcNonSenior) {
            this.senior = senior;
            this.nonSenior = nonSenior;
            this.stcSenior = stcSenior;
            this.stcNonSenior = stcNonSenior;
        }
    }

    /** Short-term rating grades of SEC-ERBA Tables 1 and 3 (CRE42.2, CRE42.12). */
    public enum ErbaShortTermRating {
        A1_P1(0.15, 0.10), A2_P2(0.50, 0.30), A3_P3(1.00, 0.60), OTHER(12.5, 12.5);

        private final double rw;
        private final double stcRw;

        ErbaShortTermRating(double rw, double stcRw) {
            this.rw = rw;
            this.stcRw = stcRw;
        }

        /** Banking-book risk weight as a multiple (Table 1, or Table 3 for STC). */
        public double riskWeight(boolean stc) {
            return stc ? stcRw : rw;
        }
    }

    /**
     * SEC-ERBA banking-book risk weight as a multiple, one-year tranche
     * maturity (MAR22.34(1), so the maturity interpolation of CRE42.5(1)
     * is not needed). Non-senior tranches: table weight × (1 − min(T, 50%))
     * with T = D − A (CRE42.5(2)), then, outside STC, floored at the
     * senior weight of the same rating (CRE42.7). Floors 15%, or STC 10% senior and 15%
     * non-senior (CRE42.7, CRE42.14).
     *
     * @param thickness T = D − A, ignored for senior tranches
     */
    public static double erbaRiskWeight(ErbaRating rating, boolean senior, double thickness, boolean stc) {
        Objects.requireNonNull(rating, "rating");
        double seniorRw = stc ? rating.stcSenior : rating.senior;
        double rw;
        if (senior) {
            rw = seniorRw;
        } else {
            if (!(thickness > 0 && thickness <= 1)) {
                throw new IllegalArgumentException("thickness must be in (0, 1]");
            }
            double table = stc ? rating.stcNonSenior : rating.nonSenior;
            rw = table * (1 - Math.min(thickness, 0.5));
            if (!stc) {
                // CRE42.7 only; CRE42.14 replaces it for STC and has no senior comparison
                rw = Math.max(rw, seniorRw);
            }
        }
        double floor = stc ? (senior ? 0.10 : 0.15) : 0.15;
        return Math.max(rw, floor);
    }

    // ------------------------------------------------- non-CTP bucketing

    /** The 11 asset classes of MAR22.31(2)(a), plus the corporates bucket of MAR22.31(1). */
    public enum AssetClass {
        CORPORATES_EX_SME, ABCP, AUTO_LOANS_LEASES, RMBS, CREDIT_CARDS, CMBS, CLO, CDO_SQUARED,
        SME, STUDENT_LOANS, OTHER_RETAIL, OTHER_WHOLESALE
    }

    /** The four regions of MAR22.31(2)(b). */
    public enum Region {
        ASIA, EUROPE, NORTH_AMERICA, OTHER
    }

    /**
     * A non-CTP bucket (MAR22.31, MAR22.32). Corporates take all regions,
     * so their region is null; the "other bucket" of MAR22.32(2), for
     * exposures that cannot be assigned, has both fields null.
     */
    public record NonCtpBucket(AssetClass assetClass, Region region) {
        public NonCtpBucket {
            if (assetClass == AssetClass.CORPORATES_EX_SME && region != null) {
                throw new IllegalArgumentException("corporates take all regions (MAR22.31(1))");
            }
            if (assetClass != null && assetClass != AssetClass.CORPORATES_EX_SME && region == null) {
                throw new IllegalArgumentException("non-corporate buckets need a region (MAR22.31(2))");
            }
            if (assetClass == null && region != null) {
                throw new IllegalArgumentException("the other bucket has no region");
            }
        }

        /** Corporates bucket, all regions (MAR22.31(1)). */
        public static NonCtpBucket corporates() {
            return new NonCtpBucket(AssetClass.CORPORATES_EX_SME, null);
        }

        /** The other bucket for unassignable exposures (MAR22.32(2)). */
        public static NonCtpBucket other() {
            return new NonCtpBucket(null, null);
        }
    }

    /** A net tranche JTD tagged with its bucket key (a {@link NonCtpBucket}, or an index name for the CTP). */
    public record Position<K>(K bucket, NetJtd netJtd) {
        public Position {
            Objects.requireNonNull(bucket, "bucket");
            Objects.requireNonNull(netJtd, "netJtd");
        }
    }

    /** Groups positions by bucket key in first-seen order. */
    public static <K> Map<K, List<NetJtd>> byBucket(List<Position<K>> positions) {
        Map<K, List<NetJtd>> m = new LinkedHashMap<>();
        for (Position<K> p : positions) {
            m.computeIfAbsent(p.bucket(), k -> new ArrayList<>()).add(p.netJtd());
        }
        return m;
    }

    private static double[] sums(List<NetJtd> bucket) {
        double l = 0;
        double s = 0;
        double rl = 0;
        double rs = 0;
        for (NetJtd j : bucket) {
            if (j.jtd() > 0) {
                l += j.jtd();
                rl += j.riskWeight() * j.jtd();
            } else {
                s -= j.jtd();
                rs -= j.riskWeight() * j.jtd();
            }
        }
        return new double[]{l, s, rl, rs};
    }

    /** HBR = Σ long / (Σ long + Σ |short|), unweighted (MAR22.23); 0 for an empty set. */
    public static double hedgeBenefitRatio(List<NetJtd> positions) {
        double[] x = sums(positions);
        return x[0] + x[1] == 0 ? 0 : x[0] / (x[0] + x[1]);
    }

    /** Non-CTP bucket charge, max(Σ RW·long − HBR_b·Σ RW·|short|, 0) (MAR22.33). */
    public static double nonCtpBucketCharge(List<NetJtd> bucket) {
        double[] x = sums(bucket);
        return Math.max(x[2] - hedgeBenefitRatio(bucket) * x[3], 0);
    }

    /** Non-CTP total: simple sum of bucket charges (MAR22.35). */
    public static <K> double nonCtpTotal(List<Position<K>> positions) {
        double t = 0;
        for (List<NetJtd> b : byBucket(positions).values()) {
            t += nonCtpBucketCharge(b);
        }
        return t;
    }

    // --------------------------------------------------------------- CTP

    /**
     * CTP index-bucket charge with the portfolio-wide HBR_ctp and no floor
     * (MAR22.44): Σ RW·long − HBR_ctp·Σ RW·|short|; may be negative.
     */
    public static double ctpBucketCharge(List<NetJtd> bucket, double hbrCtp) {
        if (!(hbrCtp >= 0 && hbrCtp <= 1)) {
            throw new IllegalArgumentException("HBR must be in [0, 1]");
        }
        double[] x = sums(bucket);
        return x[2] - hbrCtp * x[3];
    }

    /** CTP aggregation max(Σ_b (max(DRC_b, 0) + 0.5·min(DRC_b, 0)), 0) (MAR22.45). */
    public static double ctpAggregate(List<Double> bucketCharges) {
        double t = 0;
        for (double b : bucketCharges) {
            t += Math.max(b, 0) + 0.5 * Math.min(b, 0);
        }
        return Math.max(t, 0);
    }

    /**
     * Total CTP DRC: one bucket per index, bespokes in the bucket of their
     * index (MAR22.40, MAR22.41); HBR_ctp from all CTP positions together
     * (MAR22.44(1)); aggregated by MAR22.45.
     */
    public static <K> double ctpTotal(List<Position<K>> positions) {
        List<NetJtd> all = new ArrayList<>();
        for (Position<K> p : positions) {
            all.add(p.netJtd());
        }
        double hbr = hedgeBenefitRatio(all);
        List<Double> charges = new ArrayList<>();
        for (List<NetJtd> b : byBucket(positions).values()) {
            charges.add(ctpBucketCharge(b, hbr));
        }
        return ctpAggregate(charges);
    }
}
