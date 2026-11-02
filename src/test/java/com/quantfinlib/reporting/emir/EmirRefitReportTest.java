package com.quantfinlib.reporting.emir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quantfinlib.reporting.ReportingIdentifiers;
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
        assertEquals("2026-05-04", r.fields().get("eventDate"));
        assertEquals(LEI_A, r.fields().get("entityResponsibleForReporting"));
        assertEquals(LEI_A, r.fields().get("reportSubmittingEntityId"));
    }

    @Test
    void builderSettersAndLegDirections() {
        EmirRefitReport r = newSwap().submittingEntity(LEI_B).eventDate(LocalDate.of(2026, 5, 4))
                .legDirections("MAKE", "TAKE").build();
        assertEquals(List.of(), r.validate());
        assertEquals("", r.fields().get("direction"));
        assertEquals(LEI_B, r.fields().get("reportSubmittingEntityId"));
        EmirRefitReport kept = EmirRefitReportBuilder.of(EmirActionType.NEWT, EmirEventType.TRAD)
                .eventDate(LocalDate.of(2026, 1, 1))
                .terms(T, LocalDate.of(2026, 5, 6), LocalDate.of(2031, 5, 6), "INTR", "SWAP", "BYER",
                        BigDecimal.ONE, "EUR").build();
        assertEquals("2026-01-01", kept.fields().get("eventDate"));
    }

    @Test
    void valuationBlock() {
        EmirRefitReportBuilder b = EmirRefitReportBuilder.of(EmirActionType.VALU, null)
                .reportingTimestamp(T).counterparties(LEI_A, LEI_B, true).uti(LEI_A + "TRADE0001")
                .eventDate(LocalDate.of(2026, 5, 4));
        assertEquals(4, b.build().validate().size());
        assertEquals(List.of(), b.valuation(new BigDecimal("-12345.67"), "EUR", T, "MTMA").build().validate());
    }
}
