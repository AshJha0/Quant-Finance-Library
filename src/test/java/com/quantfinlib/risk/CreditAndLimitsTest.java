package com.quantfinlib.risk;

import com.quantfinlib.orderbook.Side;
import com.quantfinlib.risk.CounterpartyExposureTracker.CounterpartyTrade;
import com.quantfinlib.risk.SettlementRiskAnalyzer.SettlementLeg;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditAndLimitsTest {

    // ---- Counterparty exposure -----------------------------------------

    @Test
    void currentExposureNetsWithinCounterparty() {
        CounterpartyExposureTracker tracker = new CounterpartyExposureTracker()
                .addTrade(new CounterpartyTrade("BANK_A", "FX_FWD", 1_000_000, 50_000, 0.5))
                .addTrade(new CounterpartyTrade("BANK_A", "FX_SWAP", 2_000_000, -30_000, 2))
                .addTrade(new CounterpartyTrade("BANK_B", "FX_FWD", 500_000, -10_000, 0.25));

        assertEquals(20_000, tracker.currentExposure("BANK_A"), 1e-9);   // 50k - 30k
        assertEquals(0, tracker.currentExposure("BANK_B"), 1e-9);        // floored at 0
    }

    @Test
    void pfeUsesTenorAddOns() {
        CounterpartyExposureTracker tracker = new CounterpartyExposureTracker()
                .addTrade(new CounterpartyTrade("BANK_A", "FX_FWD", 1_000_000, 0, 0.5))   // 1%
                .addTrade(new CounterpartyTrade("BANK_A", "FX_SWAP", 1_000_000, 0, 3))    // 5%
                .addTrade(new CounterpartyTrade("BANK_A", "XCCY", 1_000_000, 0, 7));      // 7.5%

        assertEquals(10_000 + 50_000 + 75_000, tracker.potentialFutureExposure("BANK_A"), 1e-9);
        assertEquals(tracker.potentialFutureExposure("BANK_A"), tracker.totalExposure("BANK_A"), 1e-9);
        assertTrue(tracker.allExposures().containsKey("BANK_A"));
    }

    // ---- Pre-trade limits ------------------------------------------------

    private final PreTradeLimitChecker checker = new PreTradeLimitChecker()
            .maxOrderQuantity(1_000_000)
            .maxOrderNotional(10_000_000)
            .maxPositionQuantity(2_000_000)
            .priceCollarPct(0.02)
            .counterpartyLimit("HEDGE_X", 5_000_000)
            .restrictSymbol("SANCTIONED");

    @Test
    void cleanOrderPasses() {
        PreTradeLimitChecker.CheckResult r = checker.check(
                new PreTradeLimitChecker.OrderRequest("EURUSD", Side.BUY, 100_000, 1.085, "HEDGE_X"),
                1.0851, 0, 0);
        assertTrue(r.approved(), String.valueOf(r.violations()));
    }

    @Test
    void violationsAreAllCollected() {
        PreTradeLimitChecker.CheckResult r = checker.check(
                new PreTradeLimitChecker.OrderRequest("EURUSD", Side.BUY, 2_000_000, 1.20, "HEDGE_X"),
                1.085, 1_500_000, 4_000_000);
        assertFalse(r.approved());
        // Oversize, notional low enough? 2M * 1.2 = 2.4M ok; position 3.5M breach; collar ~10.6% breach; cp 4M+2.4M > 5M.
        assertTrue(r.violations().stream().anyMatch(v -> v.startsWith("MAX_ORDER_QTY")));
        assertTrue(r.violations().stream().anyMatch(v -> v.startsWith("MAX_POSITION")));
        assertTrue(r.violations().stream().anyMatch(v -> v.startsWith("PRICE_COLLAR")));
        assertTrue(r.violations().stream().anyMatch(v -> v.startsWith("COUNTERPARTY_LIMIT")));
    }

    @Test
    void restrictedSymbolBlocked() {
        PreTradeLimitChecker.CheckResult r = checker.check(
                new PreTradeLimitChecker.OrderRequest("SANCTIONED", Side.BUY, 1, 1.0, "HEDGE_X"),
                1.0, 0, 0);
        assertFalse(r.approved());
        assertTrue(r.violations().getFirst().startsWith("RESTRICTED_SYMBOL"));
    }

    // ---- Settlement (Herstatt) risk ---------------------------------------

    @Test
    void herstattExposureOnlyWhenPayingFirst() {
        List<SettlementLeg> legs = List.of(
                // Pay EUR at 07:00 CET, receive USD at 15:00 NY: Herstatt window.
                new SettlementLeg("BANK_A", "EUR", 1_000_000, 7_00, "USD", 1_085_000, 15_00),
                // Receive first: no Herstatt exposure.
                new SettlementLeg("BANK_A", "USD", 500_000, 16_00, "EUR", 460_000, 9_00),
                new SettlementLeg("BANK_B", "EUR", 200_000, 7_00, "USD", 217_000, 15_00));

        Map<String, Double> exposure = SettlementRiskAnalyzer.herstattExposure(legs);
        assertEquals(1_085_000, exposure.get("BANK_A"), 1e-9);
        assertEquals(217_000, exposure.get("BANK_B"), 1e-9);
    }

    @Test
    void peakExposureTracksOverlappingWindows() {
        List<SettlementLeg> legs = List.of(
                new SettlementLeg("BANK_A", "EUR", 0, 100, "USD", 500_000, 300),
                new SettlementLeg("BANK_A", "EUR", 0, 200, "USD", 400_000, 250),
                // Third window opens only after the first two have settled.
                new SettlementLeg("BANK_A", "EUR", 0, 400, "USD", 300_000, 500));

        // Between t=200 and t=250 both early legs are outstanding: 900k peak.
        assertEquals(900_000, SettlementRiskAnalyzer.peakExposure(legs, "BANK_A"), 1e-9);
    }

    // ---- Concentration ------------------------------------------------------

    @Test
    void herfindahlAndEffectivePositions() {
        double[] equal = {25, 25, 25, 25};
        assertEquals(0.25, ConcentrationRisk.herfindahlIndex(equal), 1e-12);
        assertEquals(4.0, ConcentrationRisk.effectivePositions(equal), 1e-9);

        double[] concentrated = {97, 1, 1, 1};
        assertTrue(ConcentrationRisk.herfindahlIndex(concentrated) > 0.9);
        assertEquals(0.98, ConcentrationRisk.topNShare(concentrated, 2), 1e-9);
    }

    @Test
    void limitBreachesFlagOversizedGroups() {
        Map<String, Double> byCcy = new java.util.LinkedHashMap<>();
        byCcy.put("USD", 600.0);
        byCcy.put("EUR", 300.0);
        byCcy.put("JPY", 100.0);
        assertEquals(List.of("USD"), ConcentrationRisk.limitBreaches(byCcy, 0.5));
        assertEquals(0.6, ConcentrationRisk.shares(byCcy).get("USD"), 1e-12);
    }
}
