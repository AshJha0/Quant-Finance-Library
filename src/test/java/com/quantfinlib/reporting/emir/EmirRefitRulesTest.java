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
 * Pins each rule in {@link EmirRefitReport#rules()} against the ESMA EMIR Refit validation
 * rules, ESMA74-362-2683, sheet Validations_Cpty_derivative. Error codes (EMIR-VR-xxxx-nn)
 * and field numbers are in the test names and messages.
 */
class EmirRefitRulesTest {
    private static final String LEI_A = lei("TESTLEIAAAAAAAAAAA");
    private static final String LEI_B = lei("TESTLEIBBBBBBBBBBB");
    private static final Instant T = Instant.parse("2026-05-04T10:15:30Z");
    private static final String D = "2026-05-04";

    private static String lei(String base) {
        return base + ReportingIdentifiers.leiCheckDigits(base);
    }

    private static EmirRefitReportBuilder swap() {
        return EmirRefitReportBuilder.of(EmirActionType.NEWT, EmirEventType.TRAD)
                .reportingTimestamp(T).counterparties(LEI_A, LEI_B, true).uti(LEI_A + "T1")
                .terms(T, LocalDate.of(2026, 5, 6), LocalDate.of(2031, 5, 6), "INTR", "SWAP", "BYER",
                        new BigDecimal("10000000"), "EUR");
    }

    private static EmirRefitReportBuilder term() {
        return EmirRefitReportBuilder.of(EmirActionType.TERM, EmirEventType.ETRM).reportingTimestamp(T)
                .counterparties(LEI_A, LEI_B, true).uti(LEI_A + "T1").field("earlyTerminationDate", D)
                .eventDate(LocalDate.parse(D));
    }

    private static EmirRefitReportBuilder bare(EmirActionType a) {
        return EmirRefitReportBuilder.of(a, null).reportingTimestamp(T).counterparties(LEI_A, LEI_B, true)
                .uti(LEI_A + "T1").eventDate(LocalDate.parse(D));
    }

    private static List<String> errs(EmirRefitReportBuilder b) {
        return b.build().validate();
    }

    @Test
    void catalogueIsCompleteOrderedAndHonest() {
        List<RuleProvenance> rules = EmirRefitReport.rules();
        assertEquals(22, rules.size());
        for (int i = 0; i < rules.size(); i++) {
            RuleProvenance r = rules.get(i);
            assertEquals(String.format("EMIR-R%02d", i + 1), r.id());
            assertFalse(r.evidence() == Evidence.UNCONFIRMED, r.id());
            assertTrue(r.citation().contains("ESMA74-362-2683"), r.id());
            assertTrue(r.citation().contains("2022/1860, Annex"), r.id());
        }
        assertEquals(Evidence.LIBRARY_READING, rules.get(8).evidence());
        assertEquals(Evidence.LIBRARY_READING, rules.get(15).evidence());
        assertTrue(EmirRefitReport.CHECKED_AGAINST.startsWith("checked against ESMA74-362-2683"));
        assertTrue(EmirRefitReport.CHECKED_AGAINST.endsWith("not certified"));
    }

    @Test
    void r01MandatoryFieldsColumnsLtoS() {
        assertEquals(List.of(), errs(swap()));
        assertEquals(List.of("EMIR-R01: missing field 1.2 reportSubmittingEntityId"),
                errs(swap().field("reportSubmittingEntityId", "")));
        assertEquals(List.of("EMIR-R01: missing field 2.153 eventDate"), errs(swap().field("eventDate", " ")));
        assertEquals(List.of("EMIR-R01: missing field 2.151 actionType"), errs(swap().field("actionType", "")));
        assertEquals(List.of("EMIR-R01: missing field 1.8 otherCounterpartyIsLegalEntity"),
                errs(swap().field("otherCounterpartyIsLegalEntity", "")));
    }

    @Test
    void r02ActionTypeVr2151_01() {
        assertEquals(List.of("EMIR-VR-2151-01: unknown actionType XXXX"), errs(swap().field("actionType", "XXXX")));
    }

    @Test
    void r03EventTypeVr2152_01() {
        assertTrue(errs(swap().field("eventType", "NOPE")).contains("EMIR-VR-2152-01: unknown eventType NOPE"));
        assertEquals(List.of("EMIR-VR-2152-01: eventType PTNG is not in the ESMA list"),
                errs(swap().field("eventType", "PTNG")));
        assertEquals(List.of(), errs(swap().field("eventType", "CORP")));
    }

    @Test
    void r04PairingVr2152_02To05() {
        assertEquals(List.of("EMIR-VR-2152-02: eventType (none) not accepted with actionType NEWT"),
                errs(swap().field("eventType", "")));
        assertEquals(List.of("EMIR-VR-2152-02: eventType ETRM not accepted with actionType NEWT"),
                errs(swap().field("eventType", "ETRM")));
        assertEquals(List.of(), errs(swap().field("actionType", "MODI").field("eventType", "")));
        assertEquals(List.of(), errs(swap().field("actionType", "MODI").field("eventType", "UPDT")));
        assertEquals(List.of("EMIR-VR-2152-03: eventType CLRG not accepted with actionType MODI"),
                errs(swap().field("actionType", "MODI").field("eventType", "CLRG")));
        assertEquals(List.of(), errs(term()));
        assertEquals(List.of("EMIR-VR-2152-04: eventType TRAD not accepted with actionType TERM"),
                errs(term().field("eventType", "TRAD")));
        assertEquals(List.of("EMIR-VR-2152-04: eventType (none) not accepted with actionType TERM"),
                errs(term().field("eventType", "")));
        assertEquals(List.of("EMIR-VR-2152-05: eventType TRAD not accepted with actionType CORR"),
                errs(swap().field("actionType", "CORR")));
        assertEquals(List.of(), errs(swap().field("actionType", "CORR").field("eventType", "")));
        assertFalse(EmirActionType.VALU.accepts(EmirEventType.TRAD));
        assertTrue(EmirActionType.EROR.accepts(null));
    }

    @Test
    void r05TradeTermsRows35To82() {
        assertEquals(List.of("EMIR-R05: missing field 2.55 notional1"), errs(swap().field("notional1", "")));
        List<String> posc = errs(swap().field("actionType", "POSC").field("eventType", "").field("assetClass", ""));
        assertEquals(List.of("EMIR-R05: missing field 2.11 assetClass"), posc);
        assertEquals(List.of(), errs(term()));
    }

    @Test
    void r06DirectionVr1017And1018() {
        assertEquals(List.of("EMIR-VR-1017-01: give field 1.17 direction or both 1.18 and 1.19"),
                errs(swap().field("direction", "")));
        assertEquals(List.of("EMIR-VR-1017-01: give field 1.17 direction or both 1.18 and 1.19"),
                errs(swap().field("direction", "").field("directionLeg1", "MAKE")));
        assertEquals(List.of(), errs(swap().legDirections("MAKE", "TAKE")));
        assertEquals(List.of("EMIR-VR-1018-03: fields 1.18 and 1.19 must differ"), errs(swap().legDirections("MAKE", "MAKE")));
        assertEquals(1, errs(swap().field("direction", "MAKE")).size());
        assertEquals(1, errs(swap().legDirections("PAYR", "TAKE")).size());
        assertEquals(1, errs(swap().legDirections("TAKE", "RECV")).size());
    }

    @Test
    void r07ValuationVr2022_03And2024_03() {
        assertEquals(4, errs(bare(EmirActionType.VALU)).size());
        assertEquals(List.of(), errs(bare(EmirActionType.VALU).valuation(BigDecimal.TEN, "EUR", T, "MTMO")));
        assertEquals(List.of("EMIR-VR-2022-03: field 2.22 must be blank when 2.21 is blank"),
                errs(swap().field("valuationCurrency", "EUR")));
        assertEquals(List.of("EMIR-VR-2024-03: field 2.24 must be blank when 2.21 is blank"),
                errs(swap().field("valuationMethod", "MTMA")));
    }

    @Test
    void r08EarlyTerminationRow70() {
        assertEquals(List.of("EMIR-R08: TERM needs field 2.45 earlyTerminationDate"),
                errs(term().field("earlyTerminationDate", "")));
        assertEquals(List.of("EMIR-R08: field 2.45 earlyTerminationDate must be blank with NEWT"),
                errs(swap().field("earlyTerminationDate", "2027-01-01").field("reportingTimestamp", "2027-01-02T00:00:00Z")));
        assertEquals(List.of("EMIR-VR-2045-05: field 2.45 must not be after the date of 1.1",
                "EMIR-VR-2153-04: field 2.153 must not be after the date of 1.1"),
                errs(term().field("earlyTerminationDate", "2026-05-05").eventDate(LocalDate.of(2026, 5, 5))));
        assertEquals(List.of("EMIR-VR-2153-07: for TERM field 2.153 must equal field 2.45"),
                errs(term().eventDate(LocalDate.of(2026, 5, 3))));
        assertEquals(List.of("EMIR-VR-2045-02: field 2.45 must not be before the date of 2.42"),
                errs(term().field("executionTimestamp", "2026-05-04T00:00:00Z").field("earlyTerminationDate", "2026-05-03")
                        .eventDate(LocalDate.of(2026, 5, 3)).field("eventDate", "2026-05-03"))
                        .stream().filter(s -> s.contains("2045")).toList());
    }

    @Test
    void r09LeiCheckDigitsLibraryReading() {
        String bad = LEI_A.substring(0, 18) + (LEI_A.endsWith("00") ? "01" : "00");
        assertEquals(List.of("EMIR-VR-1002-01: field 1.2 reportSubmittingEntityId is not a valid LEI"),
                errs(swap().submittingEntity(bad)));
        assertEquals(List.of("EMIR-VR-1003-02: field 1.3 entityResponsibleForReporting is not a valid LEI"),
                errs(swap().entityResponsible(bad)));
        assertEquals(List.of("EMIR-VR-1009-01: field 1.9 otherCounterpartyId is not a valid LEI"),
                errs(swap().field("otherCounterpartyId", bad)));
        assertEquals(1, errs(swap().field("reportingCounterpartyId", bad)).stream()
                .filter(s -> s.startsWith("EMIR-VR-1004-01")).count());
    }

    @Test
    void r10CounterpartyTwoVr1008And1009() {
        assertEquals(List.of(), errs(swap().counterparties(LEI_A, LEI_A + "CLIENT42", false)));
        assertEquals(List.of(), errs(swap().counterparties(LEI_A, LEI_B, true).field("otherCounterpartyIsLegalEntity", "TRUE")));
        assertEquals(List.of("EMIR-VR-1009-07: field 1.9 client code must start with the LEI in field 1.4"),
                errs(swap().counterparties(LEI_A, "CLIENT42", false)));
        assertEquals(List.of(), errs(swap().counterparties(LEI_A, "CLIENT42", false).field("actionType", "MODI")));
        assertEquals(List.of("EMIR-VR-1009-06: field 1.9 client code must be up to 72 alphanumerics"),
                errs(swap().counterparties(LEI_A, LEI_A + "X".repeat(53), false)));
        assertEquals(List.of("EMIR-VR-1008-01: field 1.8 must be true or false"),
                errs(swap().field("otherCounterpartyIsLegalEntity", "Y")));
        assertEquals(List.of("EMIR-VR-1009-05: fields 1.4 and 1.9 hold the same LEI and it is not the CCP in 2.33"),
                errs(swap().counterparties(LEI_A, LEI_A, true)));
        assertEquals(List.of(), errs(swap().counterparties(LEI_A, LEI_A, true).field("centralCounterparty", LEI_A)));
    }

    @Test
    void r11UtiVr2001_01And02() {
        assertEquals(List.of(), errs(swap().uti("A".repeat(52))));
        assertEquals(1, errs(swap().uti("A".repeat(53))).size());
        assertEquals(List.of("EMIR-VR-2001-02: uti for NEWT may contain only A-Z and 0-9"), errs(swap().uti("AB-12")));
        assertEquals(List.of(), errs(swap().uti("AB-1:2.3_4").field("actionType", "MODI")));
        assertEquals(1, errs(swap().uti("-AB").field("actionType", "MODI")).size());
        assertEquals(1, errs(swap().uti("AB_").field("actionType", "MODI")).size());
        assertEquals(1, errs(swap().uti("ab").field("actionType", "MODI")).size());
    }

    @Test
    void r12AndR13CodeListsVr2011And2010() {
        assertEquals(1, errs(swap().field("assetClass", "FX")).size());
        assertEquals(1, errs(swap().field("contractType", "SWAPS")).size());
        assertEquals(List.of(), errs(swap().field("contractType", "OTHR").field("assetClass", "COMM")));
    }

    @Test
    void r14ValuationMethodVr2024_01And02() {
        EmirRefitReportBuilder v = bare(EmirActionType.VALU).valuation(BigDecimal.ONE, "EUR", T, "CCPV");
        assertEquals(List.of(), errs(v));
        assertEquals(List.of(), errs(v.field("cleared", "Y")));
        assertEquals(List.of("EMIR-VR-2024-02: valuationMethod CCPV not allowed with field 2.31 'N'"),
                errs(v.field("cleared", "N")));
        assertEquals(List.of(), errs(v.field("valuationMethod", "MTMA")));
        assertEquals(List.of("EMIR-VR-2024-01: valuationMethod MTMA not allowed with field 2.31 'Y'"),
                errs(v.field("cleared", "Y")));
        assertEquals(1, errs(v.field("cleared", "").field("valuationMethod", "MARK")).size());
    }

    @Test
    void r15CurrencyVr2056And2022() {
        assertEquals(List.of("EMIR-VR-2056-01: field 2.56 notionalCurrency1 must be three upper-case letters"),
                errs(swap().field("notionalCurrency1", "eur")));
        assertEquals(List.of("EMIR-VR-2056-02: field 2.56 notionalCurrency1 special code XAU is not allowed"),
                errs(swap().field("notionalCurrency1", "XAU")));
        assertEquals(List.of("EMIR-VR-2022-02: field 2.22 valuationCurrency special code XXX is not allowed"),
                errs(bare(EmirActionType.VALU).valuation(BigDecimal.ONE, "XXX", T, "MTMA")));
    }

    @Test
    void r16IsinVr2007_02() {
        assertEquals(List.of(), errs(swap().field("isin", "US0378331005")));
        assertEquals(List.of("EMIR-VR-2007-02: isin must be 12 alphanumerics with a valid check digit"),
                errs(swap().field("isin", "US0378331006")));
    }

    /** Row 81 worked example: the default value of 25 nines is accepted. */
    @Test
    void r17NotionalVr2055_01DefaultValueAccepted() {
        assertEquals(List.of(), errs(swap().field("notional1", "9999999999999999999999999")));
        assertEquals(List.of(), errs(swap().field("notional1", "9".repeat(20) + ".12345")));
        String msg = "EMIR-VR-2055-01: field 2.55 notional1 must be a non-negative decimal of at most 25 digits, 5 decimals";
        for (String n : List.of("9".repeat(26), "9".repeat(21) + ".12345", "1.123456", "-1", "1.", ".5", "1e5", "1.x")) {
            assertEquals(List.of(msg), errs(swap().field("notional1", n)), n);
        }
    }

    @Test
    void r18ValuationAmountVr2021_01() {
        EmirRefitReportBuilder v = bare(EmirActionType.VALU).valuation(BigDecimal.ONE, "EUR", T, "MTMA");
        assertEquals(List.of(), errs(v.field("valuationAmount", "-" + "9".repeat(20) + ".12345")));
        assertEquals(1, errs(v.field("valuationAmount", "-" + "9".repeat(26))).size());
        assertEquals(1, errs(v.field("valuationAmount", "--1")).size());
    }

    @Test
    void r19FormatsVr1001_01EtAl() {
        assertEquals(List.of("EMIR-VR-1001-01: field 1.1 reportingTimestamp must be YYYY-MM-DDThh:mm:ssZ"),
                errs(swap().field("reportingTimestamp", "2026-05-04T10:15:30.1Z")));
        assertEquals(List.of("EMIR-VR-2043-01: field 2.43 effectiveDate must be YYYY-MM-DD"),
                errs(swap().field("effectiveDate", "20260506")));
        assertEquals(List.of("EMIR-VR-2043-01: field 2.43 effectiveDate is not a valid date"),
                errs(swap().field("effectiveDate", "2026-13-06")));
        assertEquals(List.of("EMIR-VR-1001-01: field 1.1 reportingTimestamp is not a valid date"),
                errs(swap().field("reportingTimestamp", "2026-02-30T10:15:30Z")));
        assertEquals(1, errs(bare(EmirActionType.VALU).valuation(BigDecimal.ONE, "EUR", T, "MTMA")
                .field("valuationTimestamp", "2026-05-04")).size());
    }

    @Test
    void r20DateBoundsVr1001_04And05() {
        assertTrue(errs(swap().field("reportingTimestamp", "2024-04-28T23:59:59Z"))
                .contains("EMIR-VR-1001-05: field 1.1 must be on or after 2024-04-29"));
        assertEquals(List.of("EMIR-VR-1001-04: field 1.1 must not be before field 2.42"),
                errs(swap().field("reportingTimestamp", "2026-05-04T10:15:29Z")));
        assertTrue(errs(swap().field("executionTimestamp", "1949-12-31T00:00:00Z").field("eventDate", "2014-02-12"))
                .contains("EMIR-VR-2042-02: field 2.42 must be on or after 1950-01-01"));
        assertTrue(errs(swap().field("effectiveDate", "1949-12-31"))
                .contains("EMIR-VR-2043-02: field 2.43 must be on or after 1950-01-01"));
        assertTrue(errs(swap().field("expirationDate", "2014-02-11"))
                .contains("EMIR-VR-2044-03: field 2.44 must be on or after 2014-02-12"));
        assertTrue(errs(term().field("earlyTerminationDate", "2014-02-11").eventDate(LocalDate.of(2014, 2, 11)))
                .containsAll(List.of("EMIR-VR-2045-04: field 2.45 must be on or after 2014-02-12",
                        "EMIR-VR-2153-03: field 2.153 must be on or after 2014-02-12")));
    }

    @Test
    void r21ExpirationVr2044_01And02() {
        assertEquals(List.of("EMIR-VR-2044-01: field 2.44 expirationDate is required unless 2.10 is CFDS"),
                errs(swap().field("expirationDate", "")));
        assertEquals(List.of(), errs(swap().field("expirationDate", "").field("contractType", "CFDS")));
        assertTrue(errs(swap().field("expirationDate", "2026-05-05"))
                .contains("EMIR-VR-2044-02: field 2.44 must not be before 2.42 or 2.43"));
        assertTrue(errs(swap().field("expirationDate", "2026-05-03").field("effectiveDate", "2026-05-01"))
                .contains("EMIR-VR-2044-02: field 2.44 must not be before 2.42 or 2.43"));
    }

    @Test
    void r22EventDateVr2153() {
        assertEquals(List.of("EMIR-VR-2153-02: for NEWT field 2.153 must equal the date of 2.42"),
                errs(swap().field("executionTimestamp", "2026-05-03T10:00:00Z")));
        assertEquals(List.of("EMIR-VR-2153-02: field 2.153 must not be before the date of 2.42",
                "EMIR-VR-2153-02: for NEWT field 2.153 must equal the date of 2.42"),
                errs(swap().eventDate(LocalDate.of(2026, 5, 3))));
        assertEquals(List.of(), errs(bare(EmirActionType.EROR)));
        assertEquals(List.of("EMIR-VR-2153-05: for EROR field 2.153 must equal the date of 1.1"),
                errs(bare(EmirActionType.EROR).eventDate(LocalDate.of(2026, 5, 3))));
        assertEquals(List.of("EMIR-VR-2153-05: for REVI field 2.153 must equal the date of 1.1"),
                errs(bare(EmirActionType.REVI).eventDate(LocalDate.of(2026, 5, 3))));
        assertEquals(List.of("EMIR-VR-2153-08: field 2.153 must not be after 2.44"),
                errs(swap().field("actionType", "MODI").field("expirationDate", "2026-05-06")
                        .field("effectiveDate", "2026-05-04").field("reportingTimestamp", "2026-05-08T00:00:00Z")
                        .eventDate(LocalDate.of(2026, 5, 7))));
        assertEquals(List.of(), errs(swap().field("actionType", "REVI").field("eventType", "")
                .field("expirationDate", "2026-05-06").field("effectiveDate", "2026-05-04")
                .field("reportingTimestamp", "2026-05-07T00:00:00Z").eventDate(LocalDate.of(2026, 5, 7))));
        assertEquals(List.of("EMIR-VR-2153-04: field 2.153 must not be after the date of 1.1"),
                errs(bare(EmirActionType.VALU).valuation(BigDecimal.ONE, "EUR", T, "MTMA")
                        .eventDate(LocalDate.of(2026, 5, 5))));
    }
}
