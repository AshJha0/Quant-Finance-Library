package com.quantfinlib.regreporting.emir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quantfinlib.regreporting.ReportingIdentifiers;
import com.quantfinlib.regreporting.RuleProvenance;
import com.quantfinlib.regreporting.registry.LeiRecord;
import com.quantfinlib.regreporting.registry.LeiRegistry;
import com.quantfinlib.regreporting.registry.LeiStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Stateful EMIR Refit rules, each case written from the condition text of ESMA74-362-2683,
 * sheet Validations_Cpty_derivative (rows cited per test). Evidence class: published
 * reference for the rule text; the cases themselves are own constructions.
 */
class EmirStatefulValidatorTest {
    private static final String LEI_A = lei("TESTLEIAAAAAAAAAAA");
    private static final String LEI_B = lei("TESTLEIBBBBBBBBBBB");
    private static final String LEI_C = lei("TESTLEICCCCCCCCCCC");
    private static final Instant T = Instant.parse("2026-03-02T10:00:00Z");
    private static final String UTI = LEI_A + "TRADE0001";

    private static String lei(String base) {
        return base + ReportingIdentifiers.leiCheckDigits(base);
    }

    private static EmirRefitReportBuilder trade(EmirActionType a, EmirEventType e, String cpty, String other) {
        return EmirRefitReportBuilder.of(a, e).reportingTimestamp(T).counterparties(cpty, other, true).uti(UTI)
                .terms(T, LocalDate.of(2026, 3, 4), LocalDate.of(2031, 3, 4), "INTR", "SWAP", "BYER",
                        new BigDecimal("1000000"), "EUR");
    }

    private static EmirRefitReport newt() {
        return trade(EmirActionType.NEWT, EmirEventType.TRAD, LEI_A, LEI_B).build();
    }

    private static EmirRefitReport plain(EmirActionType a, Instant at) {
        return EmirRefitReportBuilder.of(a, null).reportingTimestamp(at).counterparties(LEI_A, LEI_B, true)
                .uti(UTI).eventDate(at.atZone(java.time.ZoneOffset.UTC).toLocalDate()).build();
    }

    private static List<String> with(List<String> errors, String prefix) {
        return errors.stream().filter(s -> s.startsWith(prefix)).toList();
    }

    @Test
    void noSourcesMeansOnlySingleReportRules() {
        EmirRefitReport r = newt();
        assertEquals(r.validate(), EmirStatefulValidator.create().validate(r));
        assertEquals(List.of(), EmirStatefulValidator.create().validate(r));
    }

    @Test
    void receiptTimeRow5() {
        EmirStatefulValidator v = EmirStatefulValidator.create();
        assertEquals(List.of(), v.validate(newt(), T));
        assertEquals(List.of(), v.validate(newt(), Instant.parse("2026-03-03T23:59:59Z")));
        assertEquals(1, with(v.validate(newt(), T.minusSeconds(1)), "EMIR-VR-1001-02").size());
        List<String> late = v.validate(newt(), Instant.parse("2026-03-04T00:00:00Z"));
        assertEquals(1, with(late, "EMIR-VR-1001-03").size());
        assertTrue(with(late, "EMIR-VR-1001-02").isEmpty());
    }

    @Test
    void iso4217Rows47And82() {
        EmirStatefulValidator v = EmirStatefulValidator.create().withCurrencies(CurrencyRegistry.of(List.of("eur",
                "USD")));
        assertEquals(List.of(), v.validate(newt()));
        EmirRefitReport bad = trade(EmirActionType.NEWT, EmirEventType.TRAD, LEI_A, LEI_B)
                .field("notionalCurrency1", "ABC").valuation(BigDecimal.ONE, "QQQ", T, "MTMA").build();
        List<String> e = v.validate(bad);
        assertEquals(1, with(e, "EMIR-VR-2056-01").size());
        assertEquals(1, with(e, "EMIR-VR-2022-01").size());
    }

    private static LeiRegistry registry(Map<String, LeiStatus> m) {
        return lei -> Optional.ofNullable(m.get(lei)).map(s -> new LeiRecord(lei, "Name", s));
    }

    @Test
    void gleifStatusRows6To13() {
        Map<String, LeiStatus> m = new HashMap<>();
        m.put(LEI_A, LeiStatus.ISSUED);
        m.put(LEI_B, LeiStatus.LAPSED);
        EmirStatefulValidator v = EmirStatefulValidator.create().withLeiRegistry(registry(m));
        // 1.9 may be Lapsed (row 13, condition 2)
        assertEquals(List.of(), v.validate(newt()));
        // 1.4 may not be Lapsed (row 8, condition 2); nor 1.2 or 1.3, which default to 1.4 here
        List<String> e = v.validate(trade(EmirActionType.NEWT, EmirEventType.TRAD, LEI_B, LEI_A).build());
        assertEquals(1, with(e, "EMIR-VR-1004-02").size());
        assertEquals(1, with(e, "EMIR-VR-1003-03").size());
        assertTrue(with(e, "EMIR-VR-1002").isEmpty(), "1.2 accepts Lapsed (row 6, condition 2)");
        // unknown LEI: not in GLEIF
        List<String> unknown = v.validate(trade(EmirActionType.NEWT, EmirEventType.TRAD, LEI_C, LEI_A).build());
        assertEquals(1, with(unknown, "EMIR-VR-1004-01").size());
        assertEquals(1, with(unknown, "EMIR-VR-1002-01").size());
        assertEquals(1, with(unknown, "EMIR-VR-1003-02").size());
        // TERM, REVI, EROR accept any status but still need the LEI in GLEIF (condition 3)
        m.put(LEI_A, LeiStatus.RETIRED);
        List<String> eror = v.validate(plain(EmirActionType.EROR, T));
        assertTrue(with(eror, "EMIR-VR-1004").isEmpty());
        assertTrue(with(eror, "EMIR-VR-1002").isEmpty());
        m.remove(LEI_A);
        assertEquals(1, with(v.validate(plain(EmirActionType.EROR, T)), "EMIR-VR-1004-03").size());
        assertEquals(1, with(v.validate(plain(EmirActionType.EROR, T)), "EMIR-VR-1002-03").size());
    }

    @Test
    void gleifStatusSkippedForOldEventDate() {
        Map<String, LeiStatus> m = new HashMap<>();
        m.put(LEI_A, LeiStatus.LAPSED);
        m.put(LEI_B, LeiStatus.ISSUED);
        EmirStatefulValidator v = EmirStatefulValidator.create().withLeiRegistry(registry(m));
        // event date 2026-02-27 is earlier than the day before 2026-03-02: status of 1.3/1.4 not applied
        EmirRefitReport old = trade(EmirActionType.MODI, EmirEventType.TRAD, LEI_A, LEI_B)
                .eventDate(LocalDate.of(2026, 2, 27)).build();
        assertTrue(with(v.validate(old), "EMIR-VR-1004").isEmpty());
        EmirRefitReport recent = trade(EmirActionType.MODI, EmirEventType.TRAD, LEI_A, LEI_B)
                .eventDate(LocalDate.of(2026, 3, 1)).build();
        assertEquals(1, with(v.validate(recent), "EMIR-VR-1004-02").size());
    }

    @Test
    void actionHistoryRow184() {
        InMemoryUtiHistory h = new InMemoryUtiHistory();
        EmirStatefulValidator v = EmirStatefulValidator.create().withHistory(h);
        // 2151-02: first report must be NEWT or POSC
        assertEquals(1, with(v.validate(plain(EmirActionType.VALU, T)), "EMIR-VR-2151-02").size());
        assertTrue(with(v.validate(newt()), "EMIR-VR-2151").isEmpty());
        h.accept(newt());
        // 2151-03: only one NEWT per Counterparty ID-UTI; the other side may send its own
        assertEquals(1, with(v.validate(newt()), "EMIR-VR-2151-03").size());
        EmirRefitReport otherSide = trade(EmirActionType.NEWT, EmirEventType.TRAD, LEI_B, LEI_A).build();
        assertTrue(with(v.validate(otherSide), "EMIR-VR-2151").isEmpty());
        // 2151-06: outstanding, REVI not allowed
        assertEquals(1, with(v.validate(plain(EmirActionType.REVI, T)), "EMIR-VR-2151-06").size());
        // 2151-04: after EROR only REVI
        h.accept(plain(EmirActionType.EROR, T));
        assertEquals(1, with(v.validate(plain(EmirActionType.VALU, T)), "EMIR-VR-2151-04").size());
        assertTrue(with(v.validate(plain(EmirActionType.REVI, T)), "EMIR-VR-2151").isEmpty());
        h.accept(plain(EmirActionType.REVI, T));
        assertTrue(with(v.validate(plain(EmirActionType.VALU, T)), "EMIR-VR-2151").isEmpty());
    }

    @Test
    void nonOutstandingAfterTermOrMaturity() {
        InMemoryUtiHistory h = new InMemoryUtiHistory();
        EmirStatefulValidator v = EmirStatefulValidator.create().withHistory(h);
        h.accept(newt());
        h.accept(EmirRefitReportBuilder.of(EmirActionType.TERM, EmirEventType.ETRM).reportingTimestamp(T)
                .counterparties(LEI_A, LEI_B, true).uti(UTI).field("earlyTerminationDate", "2026-03-02")
                .eventDate(LocalDate.of(2026, 3, 2)).build());
        assertEquals(1, with(v.validate(plain(EmirActionType.TERM, T)), "EMIR-VR-2151-05").size());
        assertTrue(with(v.validate(plain(EmirActionType.CORR, T)), "EMIR-VR-2151").isEmpty());
        assertTrue(with(v.validate(plain(EmirActionType.REVI, T)), "EMIR-VR-2151").isEmpty());
        // matured: expiration 2031-03-04 is before a 2032 reporting date
        InMemoryUtiHistory h2 = new InMemoryUtiHistory();
        h2.accept(newt());
        Instant later = Instant.parse("2032-01-05T10:00:00Z");
        List<String> e = EmirStatefulValidator.create().withHistory(h2).validate(plain(EmirActionType.TERM, later));
        assertEquals(1, with(e, "EMIR-VR-2151-05").size());
    }

    @Test
    void counterpartiesUnchangedSinceFirstReport() {
        InMemoryUtiHistory h = new InMemoryUtiHistory();
        h.accept(newt());
        EmirStatefulValidator v = EmirStatefulValidator.create().withHistory(h);
        EmirRefitReport moved = trade(EmirActionType.MODI, EmirEventType.TRAD, LEI_A, LEI_C).build();
        assertEquals(1, with(v.validate(moved), "EMIR-VR-1009-09").size());
        assertTrue(with(v.validate(trade(EmirActionType.MODI, EmirEventType.TRAD, LEI_A, LEI_B).build()),
                "EMIR-VR-1009").isEmpty());
        assertEquals(List.of(), h.accepted(LEI_C, UTI));
    }

    @Test
    void catalogueCitesTheWorkbook() {
        List<RuleProvenance> rules = EmirStatefulValidator.rules();
        assertEquals(30, rules.size());
        for (RuleProvenance p : rules) {
            assertTrue(p.citation().startsWith("ESMA74-362-2683"), p.id());
            assertTrue(p.id().startsWith("EMIR-VR-"), p.id());
        }
        assertFalse(rules.stream().map(RuleProvenance::id).distinct().count() < rules.size());
    }
}
