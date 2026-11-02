package com.quantfinlib.risk;

import java.util.List;

/**
 * FRTB standardised default risk charge (DRC) for non-securitisation
 * positions, styled after BCBS d457 chapter MAR22. Not certified.
 *
 * <p><b>Plain idea.</b> Each position's loss if the issuer defaulted
 * tomorrow (jump to default, JTD) is scaled down for maturities under a
 * year, longs and shorts of the same obligor are netted, and in each
 * bucket the risk-weighted net longs are offset by risk-weighted net
 * shorts only to the extent of the hedge benefit ratio. Buckets are
 * summed with no diversification.</p>
 *
 * <p><b>Formal statement (per MAR22).</b></p>
 * <pre>  JTD_long  = max(LGD·notional + P&amp;L, 0),  JTD_short = min(−LGD·notional + P&amp;L, 0)
 *  scaled JTD = JTD · min(max(M, 0.25), 1)
 *  WtS  = Σ JTD_long / (Σ JTD_long + Σ |JTD_short|)
 *  DRC_b = max(Σ RW·JTD_long − WtS · Σ RW·|JTD_short|, 0)</pre>
 *
 * <p><b>Worked example</b> (own test): one bucket with a net long of 100
 * at RW 6% and a net short of 50 at RW 3%: WtS = 100/150, DRC =
 * 6 − (2/3)·1.5 = 5.</p>
 */
public final class FrtbDrc {

    private FrtbDrc() {
    }

    /** LGD by seniority (MAR22.12, verified: equity and non-senior 100%, senior 75%, covered bonds 25%). */
    public static final double LGD_SENIOR = 0.75;
    public static final double LGD_NON_SENIOR = 1.0;
    public static final double LGD_EQUITY = 1.0;
    public static final double LGD_COVERED_BOND = 0.25;

    /** Default risk weights by credit quality (MAR22.24 Table 2, verified). */
    public enum CreditQuality {
        AAA(0.005), AA(0.02), A(0.03), BBB(0.06), BB(0.15), B(0.30), CCC(0.50),
        UNRATED(0.15), DEFAULTED(1.0);

        private final double rw;

        CreditQuality(double rw) {
            this.rw = rw;
        }

        public double riskWeight() {
            return rw;
        }
    }

    /**
     * Gross JTD of one position. {@code notional} is signed (positive
     * long credit exposure, negative short); {@code pnl} is the market
     * value less notional already recognised (MAR22.11, verified).
     */
    public static double jtd(double notional, double lgd, double pnl) {
        if (notional >= 0) {
            return Math.max(lgd * notional + pnl, 0);
        }
        return Math.min(lgd * notional + pnl, 0);
    }

    /** Maturity scaling min(max(M, 0.25), 1); M in years (MAR22.15 and MAR22.18 floor, verified). */
    public static double maturityScale(double maturityYears) {
        return Math.min(Math.max(maturityYears, 0.25), 1.0);
    }

    /**
     * Net JTD of one obligor from its gross JTDs (signed) and maturities
     * in years: Σ JTD_i·min(max(M_i, 0.25), 1) (MAR22.15, MAR22.19(3)).
     * The caller must only pass shorts that may offset the longs, ie of
     * the same or lower seniority (MAR22.19(1)).
     */
    public static double netObligor(double[] grossJtd, double[] maturityYears) {
        if (grossJtd.length != maturityYears.length) {
            throw new IllegalArgumentException("arrays must align");
        }
        double t = 0;
        for (int i = 0; i < grossJtd.length; i++) {
            t += grossJtd[i] * maturityScale(maturityYears[i]);
        }
        return t;
    }

    /** A net JTD per obligor (signed) and its risk weight. */
    public record NetJtd(double jtd, double riskWeight) {
    }

    /** Hedge benefit ratio HBR = Σ long / (Σ long + Σ|short|), not risk-weighted (MAR22.23, verified). */
    public static double hedgeBenefitRatio(List<NetJtd> bucket) {
        double l = 0;
        double s = 0;
        for (NetJtd j : bucket) {
            if (j.jtd() > 0) {
                l += j.jtd();
            } else {
                s -= j.jtd();
            }
        }
        return l + s == 0 ? 0 : l / (l + s);
    }

    /** DRC of one bucket, max(Σ RW·long − HBR·Σ RW·|short|, 0) (MAR22.25, verified). */
    public static double bucketCharge(List<NetJtd> bucket) {
        double wts = hedgeBenefitRatio(bucket);
        double l = 0;
        double s = 0;
        for (NetJtd j : bucket) {
            if (j.jtd() > 0) {
                l += j.riskWeight() * j.jtd();
            } else {
                s -= j.riskWeight() * j.jtd();
            }
        }
        return Math.max(l - wts * s, 0);
    }

    /** Total non-securitisation DRC: simple sum of bucket charges (MAR22.26, verified). */
    public static double total(List<List<NetJtd>> buckets) {
        double t = 0;
        for (List<NetJtd> b : buckets) {
            t += bucketCharge(b);
        }
        return t;
    }
}
