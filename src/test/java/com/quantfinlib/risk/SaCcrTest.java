package com.quantfinlib.risk;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SA-CCR pinned to the Annex 4a worked examples of BCBS 279 (March
 * 2014), pages 21-29. The document rounds its printed figures to the
 * unit (deltas and multipliers to 0.01 and 0.001), so each check allows
 * half a unit of the printed precision.
 */
class SaCcrTest {

    private static final double SWAPTION_DELTA = SaCcr.optionDelta(false, true, 0.06, 0.05, 1,
            SaCcr.OPTION_VOL_INTEREST_RATE);

    private static List<SaCcrTrade> example1() {
        return List.of(
                SaCcrTrade.interestRate("USD", 10_000, 0, 10, 10, 1, 30),
                SaCcrTrade.interestRate("USD", 10_000, 0, 4, 4, -1, -20),
                SaCcrTrade.interestRate("EUR", 5_000, 1, 11, 11, SWAPTION_DELTA, 50));
    }

    private static List<SaCcrTrade> example2() {
        return List.of(
                SaCcrTrade.credit("Firm A", SaCcr.CreditGrade.AA, 10_000, 0, 3, 3, 1, 20),
                SaCcrTrade.credit("Firm B", SaCcr.CreditGrade.BBB, 10_000, 0, 6, 6, -1, -40),
                SaCcrTrade.credit("CDX.IG", SaCcr.CreditGrade.INVESTMENT_GRADE_INDEX,
                        10_000, 0, 5, 5, 1, 0));
    }

    private static List<SaCcrTrade> example3() {
        return List.of(
                SaCcrTrade.commodity(SaCcr.CommoditySet.ENERGY, "Crude Oil", 10_000, 0.75, 1, -50),
                SaCcrTrade.commodity(SaCcr.CommoditySet.ENERGY, "Crude Oil", 20_000, 2, -1, -30),
                SaCcrTrade.commodity(SaCcr.CommoditySet.METALS, "Silver", 10_000, 5, 1, 100));
    }

    @Test
    void supervisoryInputsOfExample1() {
        assertEquals(-0.27, SWAPTION_DELTA, 0.005);
        assertEquals(7.87, SaCcr.supervisoryDuration(0, 10), 0.005);
        assertEquals(3.63, SaCcr.supervisoryDuration(0, 4), 0.005);
        assertEquals(7.49, SaCcr.supervisoryDuration(1, 11), 0.005);
        assertEquals(78_694, 10_000 * SaCcr.supervisoryDuration(0, 10), 0.5);
        assertEquals(36_254, 10_000 * SaCcr.supervisoryDuration(0, 4), 0.5);
        assertEquals(37_428, 5_000 * SaCcr.supervisoryDuration(1, 11), 0.5);
    }

    @Test
    void example1InterestRateSwapsAndSwaption() {
        SaCcr.Result r = SaCcr.unmargined(example1(), 0);
        assertEquals(60, r.replacementCost(), 1e-9);
        assertEquals(1, r.multiplier(), 0);
        // 0.5% * (59,270 + 10,083) = 346.8
        double usd = Math.sqrt(36_254.0 * 36_254 + 78_694.0 * 78_694 - 1.4 * 36_254 * 78_694);
        assertEquals(59_270, usd, 1);
        assertEquals(347, r.addOnInterestRate(), 0.5);
        assertEquals(569, r.ead(), 0.5);
    }

    @Test
    void example2CreditDefaultSwaps() {
        SaCcr.Result r = SaCcr.unmargined(example2(), 0);
        assertEquals(0, r.replacementCost(), 0);
        assertEquals(282, r.addOnCredit(), 0.5);
        assertEquals(0.965, r.multiplier(), 0.0005);
        assertEquals(381, r.ead(), 0.5);
    }

    @Test
    void example3CommodityForwards() {
        SaCcr.Result r = SaCcr.unmargined(example3(), 0);
        assertEquals(20, r.replacementCost(), 1e-9);
        assertEquals(3_841, r.addOnCommodity(), 0.5);
        assertEquals(5_406, r.ead(), 0.5);
    }

    @Test
    void example4CombinedRatesAndCredit() {
        List<SaCcrTrade> all = new ArrayList<>(example1());
        all.addAll(example2());
        SaCcr.Result r = SaCcr.unmargined(all, 0);
        assertEquals(40, r.replacementCost(), 1e-9);
        assertEquals(629, r.addOnAggregate(), 0.5);
        assertEquals(936, r.ead(), 0.5);
    }

    @Test
    void example5MarginedRatesAndCommodities() {
        List<SaCcrTrade> all = new ArrayList<>(example1());
        all.addAll(example3());
        SaCcr.Margin weekly = new SaCcr.Margin(0, 5, 150, 10 + 5 - 1);
        SaCcr.Result r = SaCcr.margined(all, 200, weekly);
        assertEquals(80, r.marketValue(), 1e-9);
        assertEquals(0, r.replacementCost(), 0);
        assertEquals(123, r.addOnInterestRate(), 0.5);
        assertEquals(1_278, r.addOnCommodity(), 0.5);
        assertEquals(1_401, r.addOnAggregate(), 0.5);
        assertEquals(0.958, r.multiplier(), 0.0005);
        assertEquals(1_879, r.ead(), 0.5);
    }

    @Test
    void fxAndEquityAddOnsFollowTheFormulas() {
        // FX: 4% * |1,000 - 400| * MF(0.5y) on one pair.
        SaCcr.Result fx = SaCcr.unmargined(List.of(
                SaCcrTrade.fx("EURUSD", 1_000, 0.5, 1, 0),
                SaCcrTrade.fx("EURUSD", 400, 0.5, -1, 0)), 0);
        assertEquals(0.04 * 600 * Math.sqrt(0.5), fx.addOnFx(), 1e-9);
        // Equity: two single names hedge only through the 50% systematic part.
        SaCcr.Result eq = SaCcr.unmargined(List.of(
                SaCcrTrade.equity("X", false, 100, 2, 1, 0),
                SaCcrTrade.equity("SPX", true, 100, 2, -1, 0)), 0);
        double a = 0.32 * 100, b = -0.20 * 100;
        double expected = Math.sqrt(Math.pow(0.5 * a + 0.8 * b, 2) + 0.75 * a * a + 0.36 * b * b);
        assertEquals(expected, eq.addOnEquity(), 1e-9);
    }

    @Test
    void marginedReplacementCostUsesThresholdAndMta() {
        SaCcr.Margin m = new SaCcr.Margin(100, 10, 20, 10);
        SaCcr.Result r = SaCcr.margined(List.of(
                SaCcrTrade.interestRate("USD", 1_000, 0, 2, 2, 1, 50)), 60, m);
        assertEquals(90, r.replacementCost(), 1e-12);   // TH + MTA - NICA beats V - C < 0
    }

    @Test
    void cdoDeltaAndMultiplierEdges() {
        // Equity tranche 0-3%: 15 / (1 * 1.42).
        assertEquals(15 / 1.42, SaCcr.cdoTrancheDelta(0, 0.03, true), 1e-12);
        assertEquals(-15 / (1.42 * 1.98), SaCcr.cdoTrancheDelta(0.03, 0.07, false), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> SaCcr.cdoTrancheDelta(0.1, 0.1, true));
        assertEquals(1, SaCcr.multiplier(-5, 0), 0);
        assertEquals(0.05, SaCcr.multiplier(-1e9, 1), 1e-12);
        assertEquals(1, SaCcr.multiplier(10, 1), 0);
        // Margined MF ignores maturity: 1.5 sqrt(10/250) = 0.3.
        assertEquals(0.3, SaCcr.maturityFactor(7, new SaCcr.Margin(0, 0, 0, 10)), 1e-12);
        assertEquals(Math.sqrt(10 / 250.0), SaCcr.maturityFactor(0.001, null), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> SaCcr.unmargined(List.of(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> SaCcrTrade.interestRate("USD", 1, 2, 1, 1, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SaCcr.Margin(-1, 0, 0, 10));
        assertThrows(IllegalArgumentException.class,
                () -> SaCcr.optionDelta(true, true, -1, 1, 1, 0.5));
    }

    @Test
    void boughtCallDeltaIsTheNormalCdf() {
        double d1 = (Math.log(1.1) + 0.5 * 0.04) / 0.2;
        assertEquals(com.quantfinlib.util.MathUtils.normCdf(d1),
                SaCcr.optionDelta(true, true, 1.1, 1.0, 1, 0.2), 1e-15);
        assertEquals(-com.quantfinlib.util.MathUtils.normCdf(d1),
                SaCcr.optionDelta(true, false, 1.1, 1.0, 1, 0.2), 1e-15);
    }
}
