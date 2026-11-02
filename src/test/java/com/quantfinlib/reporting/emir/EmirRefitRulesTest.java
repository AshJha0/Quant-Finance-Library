package com.quantfinlib.reporting.emir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quantfinlib.reporting.ReportingIdentifiers;
import com.quantfinlib.reporting.RuleProvenance;
import com.quantfinlib.reporting.RuleProvenance.Evidence;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins each rule in {@link EmirRefitReport#rules()}. Every rule is UNCONFIRMED or
 * LIBRARY_READING: Implementing Regulation (EU) 2022/1860 and the ESMA Refit validation
 * rules are not on disk, so these tests pin the library's behaviour, not the published text.
 */
class EmirRefitRulesTest {
    private static final String LEI_A = lei("TESTLEIAAAAAAAAAAA");
    private static final String LEI_B = lei("TESTLEIBBBBBBBBBBB");
    private static final Instant T = Instant.parse("2026-05-04T10:15:30Z");

    private static String lei(String base) {
        return base + ReportingIdentifiers.leiCheckDigits(base);
    }

    private static EmirRefitReportBuilder swap() {
        return EmirRefitReportBuilder.of(EmirActionType.NEWT, EmirEventType.TRAD)
                .reportingTimestamp(T).counterparties(LEI_A, LEI_B, true).uti(LEI_A + "T1")
                .terms(T, LocalDate.of(2026, 5, 6), LocalDate.of(2031, 5, 6), "INTR", "SWAP", "BYER",
                        new BigDecimal("10000000"), "EUR");
    }

    private static List<String> errs(EmirRefitReportBuilder b) {
        return b.build().validate();
    }

    @Test
    void catalogueIsCompleteOrderedAndHonest() {
        List<RuleProvenance> rules = EmirRefitReport.rules();
        assertEquals(16, rules.size());
        for (int i = 0; i < rules.size(); i++) {
            assertEquals(String.format("EMIR-R%02d", i + 1), rules.get(i).id());
            assertFalse(rules.get(i).evidence() == Evidence.PUBLISHED_REFERENCE, rules.get(i).id());
        }
        assertEquals(Evidence.LIBRARY_READING, rules.get(3).evidence());
        assertEquals(Evidence.LIBRARY_READING, rules.get(6).evidence());
        assertTrue(EmirRefitReport.CHECKED_AGAINST.startsWith("not checked"));
    }

    @Test
    void r01AlwaysFieldsTable1And2() {
        List<String> v = errs(swap().field("uti", "").field("reportingTimestamp", " "));
        assertEquals(List.of("missing reportingTimestamp", "missing uti"), v);
    }

    @Test
    void r02UnknownActionField2151() {
        assertEquals(List.of("unknown actionType XXXX"), errs(swap().field("actionType", "XXXX")));
        assertEquals(List.of("missing actionType"), errs(swap().field("actionType", "")));
    }

    @Test
    void r03UnknownEventField2152() {
        assertTrue(errs(swap().field("eventType", "NOPE")).contains("unknown eventType NOPE"));
    }

    @Test
    void r04PairingLibraryReading() {
        assertEquals(List.of("eventType (none) not accepted with actionType NEWT"), errs(swap().field("eventType", "")));
        assertTrue(EmirActionType.CORR.accepts(null));
        assertTrue(EmirActionType.CORR.accepts(EmirEventType.ETRM));
        assertFalse(EmirActionType.EROR.accepts(EmirEventType.TRAD));
        assertTrue(EmirActionType.EROR.accepts(null));
    }

    @Test
    void r05TradeTermsForNewtModiCorr() {
        for (String a : List.of("NEWT", "MODI", "CORR")) {
            assertTrue(errs(swap().field("actionType", a).field("direction", "")).contains("missing direction"), a);
        }
        List<String> term = errs(swap().field("actionType", "TERM").field("eventType", "ETRM").field("direction", ""));
        assertFalse(term.contains("missing direction"), term.toString());
    }

    @Test
    void r06ValuationBlockFields221To224() {
        List<String> v = errs(swap().field("actionType", "VALU").field("eventType", ""));
        assertTrue(v.contains("missing valuationMethod"), v.toString());
        assertFalse(errs(swap()).contains("missing valuationMethod"));
    }

    @Test
    void r07TerminationDateFields244And245() {
        EmirRefitReportBuilder t = swap().field("actionType", "TERM").field("eventType", "ETRM");
        assertEquals(List.of(), errs(t));
        assertEquals(List.of("TERM needs earlyTerminationDate"), errs(t.field("expirationDate", "")));
        assertEquals(List.of(), errs(t.field("earlyTerminationDate", "2027-01-01")));
    }

    @Test
    void r08LeiCheckDigitsField13And14() {
        String badLei = LEI_A.substring(0, 18) + (LEI_A.endsWith("00") ? "01" : "00");
        assertEquals(List.of("reportingCounterpartyId is not a valid LEI"),
                errs(swap().field("reportingCounterpartyId", badLei)));
        assertEquals(List.of("entityResponsibleForReporting is not a valid LEI"),
                errs(swap().entityResponsible(badLei)));
    }

    @Test
    void r09UtiLengthField21() {
        assertEquals(List.of(), errs(swap().uti("A".repeat(52))));
        assertEquals(List.of("uti must be 1-52 upper-case alphanumerics"), errs(swap().uti("A".repeat(53))));
    }

    @Test
    void r10ToR13CodeListsFields211210117224() {
        assertEquals(1, errs(swap().field("assetClass", "FX")).size());
        assertEquals(1, errs(swap().field("contractType", "SWAPS")).size());
        assertEquals(1, errs(swap().field("direction", "BUY")).size());
        assertEquals(1, errs(swap().field("valuationMethod", "MARK")).size());
        assertEquals(List.of(), errs(swap().field("contractType", "OTHR").field("direction", "TAKE")
                .field("assetClass", "COMM").field("valuationMethod", "CCPV")));
    }

    @Test
    void r14CurrencyShapeField256And222() {
        assertEquals(List.of("valuationCurrency must be an ISO 4217 code"), errs(swap().field("valuationCurrency", "EURO")));
    }

    @Test
    void r15IsinCheckDigitField27() {
        assertEquals(List.of(), errs(swap().field("isin", "US0378331005")));
        assertEquals(List.of("isin check digit fails"), errs(swap().field("isin", "US0378331006")));
    }

    @Test
    void r16NotionalFormatField255() {
        assertEquals(List.of(), errs(swap().field("notional1", "9".repeat(18) + ".12345")));
        String msg = "notional1 must be a non-negative decimal (18 digits, 5 decimals)";
        assertEquals(List.of(msg), errs(swap().field("notional1", "9".repeat(19))));
        assertEquals(List.of(msg), errs(swap().field("notional1", "1.123456")));
        assertEquals(List.of(msg), errs(swap().field("notional1", "-1")));
    }
}
