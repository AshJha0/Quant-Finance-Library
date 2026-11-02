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
}
