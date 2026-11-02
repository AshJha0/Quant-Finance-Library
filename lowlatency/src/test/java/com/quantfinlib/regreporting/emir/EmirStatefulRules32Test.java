package com.quantfinlib.regreporting.emir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quantfinlib.regreporting.ReportingIdentifiers;
import com.quantfinlib.regreporting.RuleProvenance;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The EMIR Refit rules added in 3.2.0: EMIR-VR-2001-04 and its siblings 2002-02, 2003-05 and
 * 2004-04 (fields 2.1 to 2.4 differ), and EMIR-VR-1009-08 under the column AD numbering.
 * Rule texts: ESMA74-362-2683, sheet Validations_Cpty_derivative, rows 13 and 26 to 29
 * (published reference). The cases are own constructions.
 */
class EmirStatefulRules32Test {
    private static final String LEI_A = lei("TESTLEIAAAAAAAAAAA");
    private static final String LEI_B = lei("TESTLEIBBBBBBBBBBB");
    private static final Instant T = Instant.parse("2026-03-02T10:00:00Z");
    private static final String UTI = LEI_A + "TRADE0001";

    private static String lei(String base) {
        return base + ReportingIdentifiers.leiCheckDigits(base);
    }

    private static EmirRefitReportBuilder trade() {
        return EmirRefitReportBuilder.of(EmirActionType.NEWT, EmirEventType.TRAD).reportingTimestamp(T)
                .counterparties(LEI_A, LEI_B, true).uti(UTI)
                .terms(T, LocalDate.of(2026, 3, 4), LocalDate.of(2031, 3, 4), "INTR", "SWAP", "BYER",
                        new BigDecimal("1000000"), "EUR");
    }

    private static List<String> with(List<String> errors, String prefix) {
        return errors.stream().filter(s -> s.startsWith(prefix)).toList();
    }

    @Test
    void distinctIdentifiersRows26To29() {
        EmirStatefulValidator v = EmirStatefulValidator.create();
        // all four distinct: no failure
        EmirRefitReport ok = trade().reportTrackingNumber("TVTRN1").priorUti(LEI_A + "OLD1")
                .subsequentPositionUti(LEI_A + "POS1").build();
        assertTrue(with(v.validate(ok), "EMIR-VR-200").isEmpty());
        // 2.3 equals 2.1: both directions fire, 2001-04 and 2003-05
        List<String> e = v.validate(trade().priorUti(UTI).build());
        assertEquals(1, with(e, "EMIR-VR-2001-04").size());
        assertEquals(1, with(e, "EMIR-VR-2003-05").size());
        assertTrue(with(e, "EMIR-VR-2002-02").isEmpty());
        // 2.2 equals 2.4
        e = v.validate(trade().reportTrackingNumber("X1").subsequentPositionUti("X1").build());
        assertEquals(1, with(e, "EMIR-VR-2002-02").size());
        assertEquals(1, with(e, "EMIR-VR-2004-04").size());
        assertTrue(with(e, "EMIR-VR-2001-04").isEmpty());
        // blank fields are never compared
        assertTrue(with(v.validate(trade().build()), "EMIR-VR-200").isEmpty());
        // the single-report validator is unchanged
        assertTrue(with(trade().priorUti(UTI).build().validate(), "EMIR-VR-2001-04").isEmpty());
    }

    @Test
    void clientCodePrefixUnderColumnAdIs1009Dash08() {
        EmirRefitReport r = trade().counterparties(LEI_A, "CLIENT0001", false).build();
        EmirStatefulValidator v = EmirStatefulValidator.create();
        assertEquals(1, with(v.validate(r), "EMIR-VR-1009-07:").size());
        assertTrue(with(v.validate(r), "EMIR-VR-1009-08").isEmpty());
        List<String> ad = v.withColumnAdCodes().validate(r);
        assertEquals(1, with(ad, "EMIR-VR-1009-08:").size());
        assertTrue(with(ad, "EMIR-VR-1009-07").isEmpty());
        // 1.4 equals 1.9 (AC 1009-05) becomes AD 1009-06; a 73-character code (AC 06) becomes 07
        EmirRefitReport same = trade().counterparties(LEI_A, LEI_A, true).build();
        assertEquals(1, with(v.withColumnAdCodes().validate(same), "EMIR-VR-1009-06:").size());
        EmirRefitReport longCode = trade().counterparties(LEI_A, LEI_A + "X".repeat(53), false).build();
        assertEquals(1, with(v.withColumnAdCodes().validate(longCode), "EMIR-VR-1009-07:").size());
        // the flag survives later with... calls
        assertEquals(1, with(v.withColumnAdCodes().withHistory(new InMemoryUtiHistory()).validate(r),
                "EMIR-VR-1009-08:").size());
    }

    @Test
    void catalogueListsThe32Rules() {
        List<String> ids = EmirStatefulValidator.rules().stream().map(RuleProvenance::id).toList();
        assertTrue(ids.contains("EMIR-VR-1009-08"));
        assertTrue(ids.contains("EMIR-VR-2001-04/2002-02/2003-05/2004-04"));
    }
}
