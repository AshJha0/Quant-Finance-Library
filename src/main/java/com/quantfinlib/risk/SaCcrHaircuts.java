package com.quantfinlib.risk;

/**
 * Supervisory haircuts for the collateral C and the NICA that enter
 * SA-CCR. BCBS 279 (March 2014) values non-cash collateral with the
 * supervisory haircuts of the comprehensive approach to credit risk
 * mitigation. Two tables are offered. {@link Table#BASEL_II_2006} is
 * the June 2006 Basel II text (BCBS 128): the table of paragraph 151,
 * the 8% currency add-on of paragraph 152, and the square-root-of-time
 * scaling of paragraphs 168-169. {@link Table#CRE22} is the current
 * consolidated Basel Framework (December 2017 revision): the table of
 * CRE22.49 for jurisdictions that use external ratings, the same 8%
 * add-on in CRE22.52, and the same scaling in CRE22.56-59. Every value
 * below was checked against those texts (published reference).
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
 * cash in the same currency 0 (BCBS 128 para 151).</p>
 *
 * <p>CRE22.49 values (10-day, percent), buckets up to 1y / 1y to 3y /
 * 3y to 5y / 5y to 10y / over 10y: sovereign AAA to AA- 0.5 / 2 / 2 / 4
 * / 4, A+ to BBB- 1 / 3 / 3 / 6 / 6, BB+ to BB- 15; other issuers AAA to
 * AA- 1 / 3 / 4 / 6 / 12, A+ to BBB- 2 / 4 / 6 / 12 / 20, BB not
 * eligible; main-index equities and gold 20; other listed equities 30;
 * cash 0. Worked example (own hand computation): the same 1,000 of
 * A-rated 3-year corporate bonds, held, different currency, 10 days,
 * gives H = 4% under CRE22, so C = 1,000 * (1 - 0.04 - 0.08) = 880.
 * Securitisation collateral and the CRE22.50 table for jurisdictions
 * without external ratings are not implemented. Research lane,
 * deterministic.</p>
 */
public final class SaCcrHaircuts {

    /** Currency-mismatch add-on H_fx on a 10-day holding period (BCBS 128 para 152; CRE22.52). */
    public static final double CURRENCY_MISMATCH = 0.08;

    private SaCcrHaircuts() {
    }

    /** Which supervisory haircut table to use. */
    public enum Table {
        /** BCBS 128 (June 2006) paragraph 151. */
        BASEL_II_2006,
        /** Basel Framework CRE22.49 (December 2017 revision, external-ratings jurisdictions). */
        CRE22
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
     * Supervisory haircut on a 10-day holding period from the chosen table.
     *
     * @param table         BCBS 128 para 151 or CRE22.49
     * @param kind          collateral kind
     * @param rating        issue rating for debt, ignored (may be null) otherwise
     * @param maturityYears residual maturity for debt (&ge; 0), ignored otherwise
     * @return H as a decimal
     */
    public static double haircut10Day(Table table, CollateralKind kind, DebtRating rating,
                                      double maturityYears) {
        java.util.Objects.requireNonNull(table, "table");
        if (table == Table.BASEL_II_2006) {
            return haircut10Day(kind, rating, maturityYears);
        }
        java.util.Objects.requireNonNull(kind, "kind");
        switch (kind) {
            case CASH:
                return 0;
            case GOLD:
            case MAIN_INDEX_EQUITY:
                return 0.20;
            case OTHER_LISTED_EQUITY:
                return 0.30;
            default:
                break;
        }
        java.util.Objects.requireNonNull(rating, "rating is required for debt collateral");
        if (!(maturityYears >= 0) || maturityYears == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("maturity must be >= 0 and finite, got " + maturityYears);
        }
        int b = maturityYears <= 1 ? 0 : maturityYears <= 3 ? 1 : maturityYears <= 5 ? 2
                : maturityYears <= 10 ? 3 : 4;
        if (kind == CollateralKind.SOVEREIGN_DEBT) {
            switch (rating) {
                case AAA_TO_AA:
                    return new double[]{0.005, 0.02, 0.02, 0.04, 0.04}[b];
                case A_TO_BBB:
                    return new double[]{0.01, 0.03, 0.03, 0.06, 0.06}[b];
                default:
                    return 0.15;
            }
        }
        switch (rating) {
            case AAA_TO_AA:
                return new double[]{0.01, 0.03, 0.04, 0.06, 0.12}[b];
            case A_TO_BBB:
                return new double[]{0.02, 0.04, 0.06, 0.12, 0.20}[b];
            default:
                throw new IllegalArgumentException(
                        "BB-rated debt of non-sovereign issuers is not eligible collateral");
        }
    }

    /**
     * Supervisory haircut on a 10-day holding period, BCBS 128 para 151
     * (the 2006 table, the default kept for 2.2 compatibility).
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
     * Scales a 10-day haircut to N business days by the square-root-of-time
     * rule, H_N = H_10 sqrt(N / 10) (BCBS 128 paras 168-169; CRE22.56-59).
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
     * Haircut value of one collateral item for C or NICA, from the chosen table.
     *
     * @param table             BCBS 128 para 151 or CRE22.49
     * @param marketValue       X, the market value (&ge; 0)
     * @param kind              collateral kind
     * @param rating            issue rating for debt, else null
     * @param maturityYears     residual maturity for debt, else 0
     * @param currencyMismatch  true when the currencies differ
     * @param holdingBusinessDays holding period N
     * @param held              true for collateral held, false for posted
     * @return the signed contribution to C
     */
    public static double collateralValue(Table table, double marketValue, CollateralKind kind,
                                         DebtRating rating, double maturityYears,
                                         boolean currencyMismatch, double holdingBusinessDays,
                                         boolean held) {
        if (!(marketValue >= 0) || marketValue == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("market value must be >= 0 and finite, got " + marketValue);
        }
        double h = scale(haircut10Day(table, kind, rating, maturityYears), holdingBusinessDays);
        double hfx = currencyMismatch ? scale(CURRENCY_MISMATCH, holdingBusinessDays) : 0;
        return held ? marketValue * Math.max(1 - h - hfx, 0) : -marketValue * (1 + h + hfx);
    }

    /**
     * Haircut value of one collateral item for C or NICA, BCBS 128 para 151 table.
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
        return collateralValue(Table.BASEL_II_2006, marketValue, kind, rating, maturityYears,
                currencyMismatch, holdingBusinessDays, held);
    }
}
