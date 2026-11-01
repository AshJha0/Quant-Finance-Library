package com.quantfinlib.risk;

import java.util.Objects;

/**
 * One derivative as the SA-CCR sees it (BCBS 279, March 2014, Annex 4):
 * the asset class, the hedging-set key, the inputs to the trade-level
 * adjusted notional and the supervisory delta. Build trades with the
 * per-asset-class factories; each fills the fields its class does not
 * use with neutral values.
 *
 * <p>Units: notionals and market values in the reporting (domestic)
 * currency; times in years from the reference date. {@code delta} is
 * the supervisory delta of BCBS 279 paragraph 159: +1 long and -1 short
 * in the primary risk factor, or the option and CDO-tranche formulas in
 * {@link SaCcr#optionDelta} and {@link SaCcr#cdoTrancheDelta}.</p>
 *
 * @param assetClass    the SA-CCR asset class
 * @param hedgingSet    currency (rates), currency pair (FX), entity name
 *                      (credit, equity) or commodity type (commodity)
 * @param creditGrade   rating bucket for credit trades, else null
 * @param index         true for index credit or equity trades
 * @param commoditySet  commodity hedging set, else null
 * @param notional      trade notional (rates, credit) or adjusted notional
 *                      (FX foreign leg, equity, commodity)
 * @param startYears    S_i, start of the referenced period (rates, credit), else 0
 * @param endYears      E_i, end of the referenced period (rates, credit), else 0
 * @param maturityYears M_i, remaining maturity of the contract
 * @param delta         supervisory delta (|delta| &le; 15, the CDO-tranche maximum)
 * @param marketValue   current market value of the trade
 */
public record SaCcrTrade(SaCcr.AssetClass assetClass, String hedgingSet,
                         SaCcr.CreditGrade creditGrade, boolean index,
                         SaCcr.CommoditySet commoditySet, double notional,
                         double startYears, double endYears, double maturityYears,
                         double delta, double marketValue) {

    /** Validates every field. */
    public SaCcrTrade {
        Objects.requireNonNull(assetClass, "assetClass");
        Objects.requireNonNull(hedgingSet, "hedgingSet");
        if (hedgingSet.isBlank()) {
            throw new IllegalArgumentException("hedgingSet must not be blank");
        }
        finiteNonNegative("notional", notional);
        finiteNonNegative("startYears", startYears);
        finiteNonNegative("maturityYears", maturityYears);
        if (!Double.isFinite(endYears) || endYears < startYears) {
            throw new IllegalArgumentException(
                    "endYears must be finite and >= startYears, got " + endYears);
        }
        if (!Double.isFinite(delta) || delta < -15 || delta > 15) {
            throw new IllegalArgumentException("delta must be in [-15, 15], got " + delta);
        }
        if (!Double.isFinite(marketValue)) {
            throw new IllegalArgumentException("marketValue must be finite, got " + marketValue);
        }
        if ((assetClass == SaCcr.AssetClass.CREDIT) != (creditGrade != null)) {
            throw new IllegalArgumentException("a credit grade is required for credit trades only");
        }
        if ((assetClass == SaCcr.AssetClass.COMMODITY) != (commoditySet != null)) {
            throw new IllegalArgumentException(
                    "a commodity hedging set is required for commodity trades only");
        }
    }

    private static void finiteNonNegative(String name, double v) {
        if (!(v >= 0) || v == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException(name + " must be >= 0 and finite, got " + v);
        }
    }

    /**
     * Interest-rate derivative. The hedging set is the currency; the
     * maturity bucket comes from {@code endYears} (under 1y, 1y to 5y,
     * over 5y).
     *
     * @param currency      hedging-set currency, e.g. "USD"
     * @param notional      trade notional
     * @param startYears    S_i (0 when the period has started)
     * @param endYears      E_i
     * @param maturityYears M_i (for a physically settled swaption, the end
     *                      of the underlying swap)
     * @param delta         supervisory delta
     * @param marketValue   current market value
     * @return the trade
     */
    public static SaCcrTrade interestRate(String currency, double notional, double startYears,
                                          double endYears, double maturityYears, double delta,
                                          double marketValue) {
        return new SaCcrTrade(SaCcr.AssetClass.INTEREST_RATE, currency, null, false, null,
                notional, startYears, endYears, maturityYears, delta, marketValue);
    }

    /**
     * FX derivative. The hedging set is the currency pair; the adjusted
     * notional is the foreign leg converted to the domestic currency.
     *
     * @param currencyPair       hedging set, e.g. "EURUSD"
     * @param foreignLegNotional foreign leg in domestic currency
     * @param maturityYears      M_i
     * @param delta              supervisory delta
     * @param marketValue        current market value
     * @return the trade
     */
    public static SaCcrTrade fx(String currencyPair, double foreignLegNotional,
                                double maturityYears, double delta, double marketValue) {
        return new SaCcrTrade(SaCcr.AssetClass.FX, currencyPair, null, false, null,
                foreignLegNotional, 0, 0, maturityYears, delta, marketValue);
    }

    /**
     * Credit derivative on a single name or an index. Trades on the same
     * entity name aggregate into one entity-level effective notional.
     *
     * @param entity        reference entity or index name
     * @param grade         rating bucket, or one of the two index grades
     * @param notional      trade notional
     * @param startYears    S_i
     * @param endYears      E_i
     * @param maturityYears M_i
     * @param delta         supervisory delta (+1 protection buyer)
     * @param marketValue   current market value
     * @return the trade
     */
    public static SaCcrTrade credit(String entity, SaCcr.CreditGrade grade, double notional,
                                    double startYears, double endYears, double maturityYears,
                                    double delta, double marketValue) {
        Objects.requireNonNull(grade, "grade");
        return new SaCcrTrade(SaCcr.AssetClass.CREDIT, entity, grade, grade.isIndex(), null,
                notional, startYears, endYears, maturityYears, delta, marketValue);
    }

    /**
     * Equity derivative on a single name or an index.
     *
     * @param entity           stock or index name
     * @param index            true for an index
     * @param adjustedNotional price of one unit times number of units
     * @param maturityYears    M_i
     * @param delta            supervisory delta
     * @param marketValue      current market value
     * @return the trade
     */
    public static SaCcrTrade equity(String entity, boolean index, double adjustedNotional,
                                    double maturityYears, double delta, double marketValue) {
        return new SaCcrTrade(SaCcr.AssetClass.EQUITY, entity, null, index, null,
                adjustedNotional, 0, 0, maturityYears, delta, marketValue);
    }

    /**
     * Commodity derivative. The commodity type "Electricity" (any case)
     * takes the 40% factor; every other type takes 18%.
     *
     * @param set              commodity hedging set
     * @param commodityType    type inside the set, e.g. "Crude Oil"
     * @param adjustedNotional price of one unit times number of units
     * @param maturityYears    M_i
     * @param delta            supervisory delta
     * @param marketValue      current market value
     * @return the trade
     */
    public static SaCcrTrade commodity(SaCcr.CommoditySet set, String commodityType,
                                       double adjustedNotional, double maturityYears,
                                       double delta, double marketValue) {
        Objects.requireNonNull(set, "set");
        return new SaCcrTrade(SaCcr.AssetClass.COMMODITY, commodityType, null, false, set,
                adjustedNotional, 0, 0, maturityYears, delta, marketValue);
    }
}
