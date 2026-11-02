package com.quantfinlib.risk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SA-CCR basis and volatility hedging sets, margin agreements across
 * netting sets, and supervisory collateral haircuts. Every expected value
 * is an own hand computation written out in the comment beside it; BCBS
 * 279 Annex 4a has no worked example for these features.
 */
class SaCcrExtensionsTest {

    private static final double SD_10Y = (1 - Math.exp(-0.5)) / 0.05;   // 7.86939

    @Test
    void basisTradeTakesHalfFactorAndDoesNotNet() {
        SaCcrTrade swap = SaCcrTrade.interestRate("USD", 10_000, 0, 10, 10, -1, 0);
        SaCcrTrade basis = SaCcrTrade.interestRate("USD 3M/OIS", 10_000, 0, 10, 10, 1, 0).asBasis();
        // basis alone: 0.5 * 0.5% * 78,694 = 196.7
        assertEquals(0.5 * 0.005 * 10_000 * SD_10Y,
                SaCcr.unmargined(List.of(basis), 0).addOnInterestRate(), 1e-9);
        // with the opposite ordinary swap: 393.5 + 196.7, no offset
        assertEquals(1.5 * 0.005 * 10_000 * SD_10Y,
                SaCcr.unmargined(List.of(swap, basis), 0).addOnInterestRate(), 1e-9);
        // same key without the basis flag would net to zero
        SaCcrTrade plain = SaCcrTrade.interestRate("USD", 10_000, 0, 10, 10, 1, 0);
        assertEquals(0, SaCcr.unmargined(List.of(swap, plain), 0).addOnInterestRate(), 1e-9);
    }

    @Test
    void volatilityTradeTakesFiveTimesFactor() {
        SaCcrTrade varSwap = SaCcrTrade.equity("ACME", false, 1_000, 1, 1, 0).asVolatility();
        // SF 32% * 5 = 160%, A = 1,600; sqrt((0.5 A)^2 + 0.75 A^2) = A
        assertEquals(1_600, SaCcr.unmargined(List.of(varSwap), 0).addOnEquity(), 1e-9);
        // its own hedging set: an ordinary short on ACME does not offset it
        SaCcrTrade shortStock = SaCcrTrade.equity("ACME", false, 1_000, 1, -1, 0);
        assertEquals(1_600 + 320,
                SaCcr.unmargined(List.of(varSwap, shortStock), 0).addOnEquity(), 1e-9);
        // FX volatility: 4% * 5 * 1,000 = 200
        SaCcrTrade fxVol = SaCcrTrade.fx("EURUSD", 1_000, 1, 1, 0).asVolatility();
        assertEquals(200, SaCcr.unmargined(List.of(fxVol), 0).addOnFx(), 1e-9);
        // commodity volatility: 18% * 5 * 1,000 = 900, single type gives A
        SaCcrTrade oilVol = SaCcrTrade.commodity(SaCcr.CommoditySet.ENERGY, "Crude Oil",
                1_000, 1, 1, 0).asVolatility();
        assertEquals(900, SaCcr.unmargined(List.of(oilVol), 0).addOnCommodity(), 1e-9);
    }

    @Test
    void fxCannotBeBasisAndOldConstructorIsOrdinary() {
        assertThrows(IllegalArgumentException.class,
                () -> SaCcrTrade.fx("EURUSD", 1_000, 1, 1, 0).asBasis());
        SaCcrTrade t = new SaCcrTrade(SaCcr.AssetClass.FX, "EURUSD", null, false, null,
                1_000, 0, 0, 1, 1, 0);
        assertEquals(SaCcr.HedgingSetType.ORDINARY, t.hedgingSetType());
    }

    @Test
    void oneMarginAgreementOverTwoNettingSets() {
        List<SaCcrTrade> ns1 = List.of(SaCcrTrade.fx("EURUSD", 1_000, 1, 1, 100));
        List<SaCcrTrade> ns2 = List.of(SaCcrTrade.fx("EURUSD", 1_000, 1, -1, -40));
        SaCcr.Result r = SaCcr.oneMarginAgreementManyNettingSets(List.of(ns1, ns2), 30);
        // RC = max(100 + 0 - 30, 0) = 70
        assertEquals(70, r.replacementCost(), 1e-12);
        // PFE: 40 * 1 + 40 * (0.05 + 0.95 exp(-40 / 76)) = 40 + 24.4495
        double m2 = 0.05 + 0.95 * Math.exp(-40.0 / 76);
        assertEquals(40 + 40 * m2, r.pfe(), 1e-9);
        assertEquals(64.4495, r.pfe(), 1e-4);
        assertEquals(1.4 * (70 + 40 + 40 * m2), r.ead(), 1e-9);
        assertEquals(80, r.addOnAggregate(), 1e-12);
    }

    @Test
    void nettingSetSplitIntoMarginedAndUnmarginedParts() {
        SaCcr.Margin csa = new SaCcr.Margin(0, 5, 0, 10);
        SaCcr.SubNettingSet margined = new SaCcr.SubNettingSet(
                List.of(SaCcrTrade.fx("EURUSD", 1_000, 1, 1, 50)), 20, csa);
        SaCcr.SubNettingSet unmargined = new SaCcr.SubNettingSet(
                List.of(SaCcrTrade.fx("GBPUSD", 1_000, 1, 1, 10)), 0, null);
        SaCcr.Result r = SaCcr.subNettingSets(List.of(margined, unmargined));
        // RC = max(60 - 20, 0 + 5 - 0, 0) = 40
        assertEquals(40, r.replacementCost(), 1e-12);
        // margined MF = 1.5 sqrt(10/250) = 0.3: 4% * 300 = 12; unmargined 4% * 1,000 = 40
        assertEquals(52, r.pfe(), 1e-9);
        assertEquals(1.4 * 92, r.ead(), 1e-9);
    }

    @Test
    void subNettingSetReplacementCostIsPerPart() {
        // BCBS 279 para 185: the sub-netting-set split applies to RC as well as PFE.
        SaCcr.Margin csa = new SaCcr.Margin(0, 5, 0, 10);
        SaCcr.SubNettingSet margined = new SaCcr.SubNettingSet(
                List.of(SaCcrTrade.fx("EURUSD", 1_000, 1, -1, -30)), 0, csa);
        SaCcr.SubNettingSet unmargined = new SaCcr.SubNettingSet(
                List.of(SaCcrTrade.fx("GBPUSD", 1_000, 1, 1, 50)), 0, null);
        SaCcr.Result r = SaCcr.subNettingSets(List.of(margined, unmargined));
        // RC = max(-30, 5, 0) + max(50, 0) = 55; netting first would give max(20, 5) = 20
        assertEquals(55, r.replacementCost(), 1e-12);
    }

    @Test
    void supervisoryHaircuts() {
        assertEquals(0.06, SaCcrHaircuts.haircut10Day(SaCcrHaircuts.CollateralKind.OTHER_DEBT,
                SaCcrHaircuts.DebtRating.A_TO_BBB, 3), 0);
        assertEquals(0.005, SaCcrHaircuts.haircut10Day(SaCcrHaircuts.CollateralKind.SOVEREIGN_DEBT,
                SaCcrHaircuts.DebtRating.AAA_TO_AA, 1), 0);
        assertEquals(0.15, SaCcrHaircuts.haircut10Day(SaCcrHaircuts.CollateralKind.SOVEREIGN_DEBT,
                SaCcrHaircuts.DebtRating.BB, 7), 0);
        assertEquals(0.25, SaCcrHaircuts.haircut10Day(
                SaCcrHaircuts.CollateralKind.OTHER_LISTED_EQUITY, null, 0), 0);
        assertThrows(IllegalArgumentException.class, () -> SaCcrHaircuts.haircut10Day(
                SaCcrHaircuts.CollateralKind.OTHER_DEBT, SaCcrHaircuts.DebtRating.BB, 2));
        // held, 6% + 8% mismatch: 1,000 * 0.86 = 860
        assertEquals(860, SaCcrHaircuts.collateralValue(1_000,
                SaCcrHaircuts.CollateralKind.OTHER_DEBT, SaCcrHaircuts.DebtRating.A_TO_BBB, 3,
                true, 10, true), 1e-9);
        // 20 days: 1,000 (1 - 0.14 sqrt 2) = 802.0
        assertEquals(802.0, SaCcrHaircuts.collateralValue(1_000,
                SaCcrHaircuts.CollateralKind.OTHER_DEBT, SaCcrHaircuts.DebtRating.A_TO_BBB, 3,
                true, 20, true), 0.05);
        // posted AAA sovereign 7y: -1,000 * 1.04
        assertEquals(-1_040, SaCcrHaircuts.collateralValue(1_000,
                SaCcrHaircuts.CollateralKind.SOVEREIGN_DEBT, SaCcrHaircuts.DebtRating.AAA_TO_AA, 7,
                false, 10, false), 1e-9);
    }

    @Test
    void cre22Haircuts() {
        // Basel Framework CRE22.49 (external-ratings table) and CRE22.52 (8% FX)
        SaCcrHaircuts.Table t = SaCcrHaircuts.Table.CRE22;
        SaCcrHaircuts.CollateralKind sov = SaCcrHaircuts.CollateralKind.SOVEREIGN_DEBT;
        SaCcrHaircuts.CollateralKind oth = SaCcrHaircuts.CollateralKind.OTHER_DEBT;
        SaCcrHaircuts.DebtRating aaa = SaCcrHaircuts.DebtRating.AAA_TO_AA;
        SaCcrHaircuts.DebtRating a = SaCcrHaircuts.DebtRating.A_TO_BBB;
        double[] years = {0.5, 2, 4, 7, 15};
        double[][] expected = {
            {0.005, 0.02, 0.02, 0.04, 0.04}, {0.01, 0.03, 0.03, 0.06, 0.06},
            {0.01, 0.03, 0.04, 0.06, 0.12}, {0.02, 0.04, 0.06, 0.12, 0.20}};
        for (int i = 0; i < years.length; i++) {
            assertEquals(expected[0][i], SaCcrHaircuts.haircut10Day(t, sov, aaa, years[i]), 0);
            assertEquals(expected[1][i], SaCcrHaircuts.haircut10Day(t, sov, a, years[i]), 0);
            assertEquals(expected[2][i], SaCcrHaircuts.haircut10Day(t, oth, aaa, years[i]), 0);
            assertEquals(expected[3][i], SaCcrHaircuts.haircut10Day(t, oth, a, years[i]), 0);
        }
        // bucket tops are inclusive: 3y falls in the 1y-3y bucket
        assertEquals(0.03, SaCcrHaircuts.haircut10Day(t, oth, aaa, 3), 0);
        assertEquals(0.15, SaCcrHaircuts.haircut10Day(t, sov, SaCcrHaircuts.DebtRating.BB, 20), 0);
        assertThrows(IllegalArgumentException.class,
                () -> SaCcrHaircuts.haircut10Day(t, oth, SaCcrHaircuts.DebtRating.BB, 2));
        assertEquals(0.20, SaCcrHaircuts.haircut10Day(t, SaCcrHaircuts.CollateralKind.GOLD, null, 0), 0);
        assertEquals(0.30, SaCcrHaircuts.haircut10Day(t,
                SaCcrHaircuts.CollateralKind.OTHER_LISTED_EQUITY, null, 0), 0);
        assertEquals(0, SaCcrHaircuts.haircut10Day(t, SaCcrHaircuts.CollateralKind.CASH, null, 0), 0);
        // held A-rated 3y corporate, FX mismatch, 10 days: 1,000 * (1 - 0.04 - 0.08) = 880
        assertEquals(880, SaCcrHaircuts.collateralValue(t, 1_000, oth, a, 3, true, 10, true), 1e-9);
        // the 2006 table is unchanged: 860
        assertEquals(860, SaCcrHaircuts.collateralValue(SaCcrHaircuts.Table.BASEL_II_2006, 1_000,
                oth, a, 3, true, 10, true), 1e-9);
    }

    @Test
    void cre22SecuritisationRows() {
        // CRE22.49 securitisation column, buckets <=1 / 1-3 / 3-5 / 5-10 / >10
        SaCcrHaircuts.Table t = SaCcrHaircuts.Table.CRE22;
        SaCcrHaircuts.CollateralKind sec = SaCcrHaircuts.CollateralKind.SECURITISATION;
        double[] years = {1, 3, 5, 10, 11};
        double[] aaa = {0.02, 0.08, 0.08, 0.16, 0.16};
        double[] a = {0.04, 0.12, 0.12, 0.24, 0.24};
        for (int i = 0; i < years.length; i++) {
            assertEquals(aaa[i], SaCcrHaircuts.haircut10Day(t, sec, SaCcrHaircuts.DebtRating.AAA_TO_AA, years[i]), 0);
            assertEquals(a[i], SaCcrHaircuts.haircut10Day(t, sec, SaCcrHaircuts.DebtRating.A_TO_BBB, years[i]), 0);
        }
        assertThrows(IllegalArgumentException.class,
                () -> SaCcrHaircuts.haircut10Day(t, sec, SaCcrHaircuts.DebtRating.BB, 2));
        // no securitisation row in BCBS 128 para 151, no other-exposure row in CRE22.49
        assertThrows(IllegalArgumentException.class,
                () -> SaCcrHaircuts.haircut10Day(sec, SaCcrHaircuts.DebtRating.AAA_TO_AA, 2));
        assertThrows(IllegalArgumentException.class, () -> SaCcrHaircuts.haircut10Day(t,
                SaCcrHaircuts.CollateralKind.OTHER_EXPOSURE, null, 0));
        // held AAA 2y securitisation, posted side, 20 days: -1,000 * (1 + 0.08*sqrt 2) = -1,113.137
        assertEquals(-1_000 * (1 + 0.08 * Math.sqrt(2)),
                SaCcrHaircuts.collateralValue(t, 1_000, sec, SaCcrHaircuts.DebtRating.AAA_TO_AA, 2, false, 20, false),
                1e-9);
    }

    @Test
    void cre22NoExternalRatingsTable() {
        // CRE22.50, buckets <=1 / 1-3 / 3-5 / 5-10 / >10
        double[] years = {0.5, 2, 4, 7, 15};
        double[][] expected = {
            {0.005, 0.02, 0.02, 0.04, 0.04}, {0.01, 0.03, 0.03, 0.06, 0.06}, {0.15, 0.15, 0.15, 0.15, 0.15},
            {0.02, 0.04, 0.06, 0.12, 0.20}, {0.04, 0.12, 0.12, 0.24, 0.24}};
        SaCcrHaircuts.NoRatingsDebtClass[] cls = SaCcrHaircuts.NoRatingsDebtClass.values();
        for (int c = 0; c < cls.length; c++) {
            for (int i = 0; i < years.length; i++) {
                assertEquals(expected[c][i], SaCcrHaircuts.haircut10DayNoExternalRatings(cls[c], years[i]), 0);
            }
        }
        SaCcrHaircuts.Table t = SaCcrHaircuts.Table.CRE22_NO_EXTERNAL_RATINGS;
        assertEquals(0.20, SaCcrHaircuts.haircut10Day(t, SaCcrHaircuts.CollateralKind.MAIN_INDEX_EQUITY, null, 0), 0);
        assertEquals(0.30, SaCcrHaircuts.haircut10Day(t, SaCcrHaircuts.CollateralKind.OTHER_LISTED_EQUITY, null, 0), 0);
        assertEquals(0.30, SaCcrHaircuts.haircut10Day(t, SaCcrHaircuts.CollateralKind.OTHER_EXPOSURE, null, 0), 0);
        assertEquals(0, SaCcrHaircuts.haircut10Day(t, SaCcrHaircuts.CollateralKind.CASH, null, 0), 0);
        assertThrows(IllegalArgumentException.class, () -> SaCcrHaircuts.haircut10Day(t,
                SaCcrHaircuts.CollateralKind.SOVEREIGN_DEBT, SaCcrHaircuts.DebtRating.AAA_TO_AA, 1));
        // worked example: 1,000 senior securitisation, SA RW 50%, 4y, held, same ccy, 10d: 1,000*(1-0.12) = 880
        double h = SaCcrHaircuts.haircut10DayNoExternalRatings(
                SaCcrHaircuts.NoRatingsDebtClass.SENIOR_SECURITISATION_SA_RW_BELOW_100, 4);
        assertEquals(880, SaCcrHaircuts.collateralValue(1_000, h, false, 10, true), 1e-9);
    }
}
