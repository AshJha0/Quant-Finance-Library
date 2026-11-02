package com.quantfinlib.risk;

/**
 * Supervisory haircuts for the collateral C and the NICA that enter
 * SA-CCR. BCBS 279 (March 2014) values non-cash collateral with the
 * supervisory haircuts of the comprehensive approach to credit risk
 * mitigation, which in the June 2006 Basel II text (BCBS 128) is the
 * table of paragraph 151, with an 8% add-on when the collateral currency
 * differs from the settlement currency.
 *
 * <p>The plain idea: collateral the bank holds may fall in value before
 * it can be sold, so it counts for less than its market value; collateral
 * the bank has posted may rise in value and be lost if the counterparty
 * fails, so it counts for more. Formally, for market value X and haircut
 * H (as a decimal),</p>
 *
 * <pre>
 *   held:   C = X (1 - H - H_fx)
 *   posted: C = -X (1 + H + H_fx)
 *   H_N = H_10 sqrt(N / 10)   for a holding period of N business days
 * </pre>
 *
 * <p>Worked example (own hand computation): 1,000 of A-rated corporate
 * bonds with 3 years to maturity, held, in a different currency, on a
 * 10-day period: H = 6%, H_fx = 8%, C = 1,000 * 0.86 = 860. On a 20-day
 * period both haircuts scale by sqrt(2): C = 1,000 (1 - 0.14 sqrt 2)
 * = 802.0.</p>
 *
 * <p>Table values (10-day, percent): sovereign debt AAA to AA- 0.5 / 2 /
 * 4 for residual maturity up to 1y / 1y to 5y / over 5y, A+ to BBB- 1 /
 * 3 / 6, BB+ to BB- 15; other issuers AAA to AA- 1 / 4 / 8, A+ to BBB-
 * 2 / 6 / 12; main-index equities and gold 15; other listed equities 25;
 * cash in the same currency 0. These are the Basel II figures; the
 * December 2017 Basel III revision (CRE22) splits the maturity buckets
 * further and raises several of them, and is not implemented here.
 * Research lane, deterministic.</p>
 */
public final class SaCcrHaircuts {

    /** Currency-mismatch add-on H_fx on a 10-day holding period. */
    public static final double CURRENCY_MISMATCH = 0.08;

    private SaCcrHaircuts() {
    }

    /** Collateral kinds of the supervisory haircut table. */
    public enum CollateralKind {
        /** Cash in the settlement currency (0%). */
        CASH,
        /** Gold (15%). */
        GOLD,
        /** Equities in a main index (15%). */
        MAIN_INDEX_EQUITY,
        /** Other equities listed on a recognised exchange (25%). */
        OTHER_LISTED_EQUITY,
        /** Debt issued by sovereigns. */
        SOVEREIGN_DEBT,
        /** Debt of other issuers (banks, corporates). */
        OTHER_DEBT
    }

    /** Issue rating buckets for debt collateral. */
    public enum DebtRating {
        /** AAA to AA-. */
        AAA_TO_AA,
        /** A+ to BBB-. */
        A_TO_BBB,
        /** BB+ to BB- (eligible for sovereign debt only). */
        BB
    }

    /**
     * Supervisory haircut on a 10-day holding period.
     *
     * @param kind          collateral kind
     * @param rating        issue rating for debt, ignored (may be null) otherwise
     * @param maturityYears residual maturity for debt (&ge; 0), ignored otherwise
     * @return H as a decimal
     */
    public static double haircut10Day(CollateralKind kind, DebtRating rating, double maturityYears) {
        java.util.Objects.requireNonNull(kind, "kind");
        switch (kind) {
            case CASH:
                return 0;
            case GOLD:
            case MAIN_INDEX_EQUITY:
                return 0.15;
            case OTHER_LISTED_EQUITY:
                return 0.25;
            default:
                break;
        }
        java.util.Objects.requireNonNull(rating, "rating is required for debt collateral");
        if (!(maturityYears >= 0) || maturityYears == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("maturity must be >= 0 and finite, got " + maturityYears);
        }
        int b = maturityYears <= 1 ? 0 : (maturityYears <= 5 ? 1 : 2);
        if (kind == CollateralKind.SOVEREIGN_DEBT) {
            switch (rating) {
                case AAA_TO_AA:
                    return new double[]{0.005, 0.02, 0.04}[b];
                case A_TO_BBB:
                    return new double[]{0.01, 0.03, 0.06}[b];
                default:
                    return 0.15;
            }
        }
        switch (rating) {
            case AAA_TO_AA:
                return new double[]{0.01, 0.04, 0.08}[b];
            case A_TO_BBB:
                return new double[]{0.02, 0.06, 0.12}[b];
            default:
                throw new IllegalArgumentException(
                        "BB-rated debt of non-sovereign issuers is not eligible collateral");
        }
    }

    /**
     * Scales a 10-day haircut to N business days by the square-root-of-time rule.
     *
     * @param haircut10Day     H_10
     * @param holdingBusinessDays N &gt; 0
     * @return H_N
     */
    public static double scale(double haircut10Day, double holdingBusinessDays) {
        if (!(haircut10Day >= 0) || !(holdingBusinessDays > 0)
                || !Double.isFinite(haircut10Day * holdingBusinessDays)) {
            throw new IllegalArgumentException("need H >= 0 and N > 0, finite");
        }
        return haircut10Day * Math.sqrt(holdingBusinessDays / 10);
    }

    /**
     * Haircut value of one collateral item for C or NICA.
     *
     * @param marketValue       X, the market value (&ge; 0)
     * @param kind              collateral kind
     * @param rating            issue rating for debt, else null
     * @param maturityYears     residual maturity for debt, else 0
     * @param currencyMismatch  true when the collateral currency differs
     *                          from the settlement currency
     * @param holdingBusinessDays holding period N (10 for the table values;
     *                          the caller picks it, for example the MPOR)
     * @param held              true for collateral the bank holds, false
     *                          for collateral it has posted
     * @return the signed contribution to C: positive held, negative posted
     */
    public static double collateralValue(double marketValue, CollateralKind kind, DebtRating rating,
                                         double maturityYears, boolean currencyMismatch,
                                         double holdingBusinessDays, boolean held) {
        if (!(marketValue >= 0) || marketValue == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("market value must be >= 0 and finite, got " + marketValue);
        }
        double h = scale(haircut10Day(kind, rating, maturityYears), holdingBusinessDays);
        double hfx = currencyMismatch ? scale(CURRENCY_MISMATCH, holdingBusinessDays) : 0;
        return held ? marketValue * Math.max(1 - h - hfx, 0) : -marketValue * (1 + h + hfx);
    }
}
