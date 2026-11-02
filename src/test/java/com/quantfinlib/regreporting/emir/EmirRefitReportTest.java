package com.quantfinlib.regreporting.emir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quantfinlib.regreporting.ReportingIdentifiers;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmirRefitReportTest {
    private static final String LEI_A = lei("TESTLEIAAAAAAAAAAA");
    private static final String LEI_B = lei("TESTLEIBBBBBBBBBBB");
    private static final Instant T = Instant.parse("2026-05-04T10:15:30.999Z");

    private static String lei(String base) {
        return base + ReportingIdentifiers.leiCheckDigits(base);
    }

    private static EmirRefitReportBuilder newSwap() {
        return EmirRefitReportBuilder.of(EmirActionType.NEWT, EmirEventType.TRAD)
                .reportingTimestamp(T).counterparties(LEI_A, LEI_B, true)
                .uti(EmirRefitReportBuilder.uti(LEI_A, "TRADE0001"))
                .terms(T, LocalDate.of(2026, 5, 6), LocalDate.of(2031, 5, 6), "INTR", "SWAP", "BYER",
                        new BigDecimal("10000000"), "EUR");
    }

    @Test
    void utiGeneration() {
        String u = EmirRefitReportBuilder.uti(LEI_A, "TRADE0001");
        assertEquals(29, u.length());
        assertTrue(u.startsWith(LEI_A));
        assertThrows(IllegalArgumentException.class, () -> EmirRefitReportBuilder.uti("BAD", "X"));
        assertThrows(IllegalArgumentException.class, () -> EmirRefitReportBuilder.uti(LEI_A, "X".repeat(33)));
        assertTrue(EmirRefitReportBuilder.isValidUtiShape("A".repeat(52)));
        assertFalse(EmirRefitReportBuilder.isValidUtiShape("a1"));
    }

    @Test
    void newTradeMapsAndValidates() {
        EmirRefitReport r = newSwap().build();
        assertEquals(List.of(), r.validate());
        assertEquals("EMIR_REFIT", r.regime());
        assertEquals(LEI_A + "TRADE0001", r.reference());
        assertEquals("2026-05-04T10:15:30Z", r.fields().get("reportingTimestamp"));
        assertEquals("NEWT", r.fields().get("actionType"));
        assertEquals("TRAD", r.fields().get("eventType"));
        assertEquals(LEI_A, r.fields().get("entityResponsibleForReporting"));
    }

    @Test
    void actionEventPairing() {
        assertTrue(EmirActionType.TERM.accepts(EmirEventType.ETRM));
        assertFalse(EmirActionType.TERM.accepts(EmirEventType.TRAD));
        assertTrue(EmirActionType.VALU.accepts(null));
        assertFalse(EmirActionType.VALU.accepts(EmirEventType.TRAD));
        assertFalse(EmirActionType.NEWT.accepts(null));
        EmirRefitReport r = newSwap().field("actionType", "VALU").build();
        assertTrue(r.validate().contains("eventType TRAD not accepted with actionType VALU"), r.validate().toString());
    }

    @Test
    void valuationNeedsValuationBlock() {
        EmirRefitReportBuilder b = EmirRefitReportBuilder.of(EmirActionType.VALU, null)
                .reportingTimestamp(T).counterparties(LEI_A, LEI_B, true).uti(LEI_A + "TRADE0001");
        assertEquals(List.of("missing valuationAmount", "missing valuationCurrency", "missing valuationTimestamp",
                "missing valuationMethod"), b.build().validate());
        assertEquals(List.of(), b.valuation(new BigDecimal("-12345.67"), "EUR", T, "MTMA").build().validate());
    }

    @Test
    void naturalPersonOtherCounterpartyAllowed() {
        EmirRefitReport r = newSwap().counterparties(LEI_A, "CLIENT42", false).build();
        assertEquals(List.of(), r.validate());
        EmirRefitReport bad = newSwap().counterparties(LEI_A, "CLIENT42", true).build();
        assertEquals(List.of("otherCounterpartyId is not a valid LEI"), bad.validate());
    }

    @Test
    void badCodesReported() {
        EmirRefitReport r = newSwap().field("assetClass", "XXXX").field("notionalCurrency1", "eur")
                .field("isin", "US0378331006").field("eventType", "NOPE").build();
        List<String> v = r.validate();
        assertTrue(v.contains("unknown eventType NOPE"), v.toString());
        assertTrue(v.stream().anyMatch(s -> s.startsWith("assetClass must")), v.toString());
        assertTrue(v.contains("notionalCurrency1 must be an ISO 4217 code"), v.toString());
        assertTrue(v.contains("isin check digit fails"), v.toString());
    }

    @Test
    void terminationNeedsDate() {
        EmirRefitReport r = EmirRefitReportBuilder.of(EmirActionType.TERM, EmirEventType.ETRM)
                .reportingTimestamp(T).counterparties(LEI_A, LEI_B, true).uti(LEI_A + "TRADE0001").build();
        assertEquals(List.of("TERM needs earlyTerminationDate"), r.validate());
        assertEquals(List.of(), EmirRefitReportBuilder.of(EmirActionType.TERM, EmirEventType.ETRM)
                .reportingTimestamp(T).counterparties(LEI_A, LEI_B, true).uti(LEI_A + "TRADE0001")
                .field("earlyTerminationDate", "2027-01-01").build().validate());
    }
}
