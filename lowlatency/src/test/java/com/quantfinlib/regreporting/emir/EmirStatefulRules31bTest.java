package com.quantfinlib.regreporting.emir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quantfinlib.regreporting.ReportingIdentifiers;
import com.quantfinlib.regreporting.RuleProvenance;
import com.quantfinlib.regreporting.registry.LeiRecord;
import com.quantfinlib.regreporting.registry.LeiRegistry;
import com.quantfinlib.regreporting.registry.LeiStatus;
import com.quantfinlib.regreporting.registry.MicRecord;
import com.quantfinlib.regreporting.registry.MicRegistry;
import com.quantfinlib.regreporting.registry.MicStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The stateful EMIR Refit rules added in part 2 of 3.1.0: branch LEIs, the venue rule
 * EMIR-VR-1003-01, the UTI rule EMIR-VR-2001-03 and the "reported for that UTI" date rules.
 * Each case is written from the condition text of ESMA74-362-2683, sheet
 * Validations_Cpty_derivative (rows cited per test). Evidence class: published reference for
 * the rule text; the cases are own constructions.
 */
class EmirStatefulRules31bTest {
    private static final String LEI_A = lei("TESTLEIAAAAAAAAAAA");
    private static final String LEI_B = lei("TESTLEIBBBBBBBBBBB");
    private static final String LEI_BR = lei("TESTLEIBRANCHBRANC");
    private static final Instant T = Instant.parse("2026-03-02T10:00:00Z");
    private static final String UTI = LEI_A + "TRADE0001";
    private static final String UTI2 = LEI_A + "TRADE0002";

    private static String lei(String base) {
        return base + ReportingIdentifiers.leiCheckDigits(base);
    }

    private static EmirRefitReportBuilder trade(EmirActionType a, String uti) {
        return EmirRefitReportBuilder.of(a, EmirEventType.TRAD).reportingTimestamp(T)
                .counterparties(LEI_A, LEI_B, true).uti(uti)
                .terms(T, LocalDate.of(2026, 3, 4), LocalDate.of(2031, 3, 4), "INTR", "SWAP", "BYER",
                        new BigDecimal("1000000"), "EUR");
    }

    /** A lifecycle report carrying only the header fields and the given extra fields. */
    private static EmirRefitReport bare(EmirActionType a, Instant at, Map<String, String> extra) {
        EmirRefitReportBuilder b = EmirRefitReportBuilder.of(a, null).reportingTimestamp(at)
                .counterparties(LEI_A, LEI_B, true).uti(UTI).eventDate(at.atZone(ZoneOffset.UTC).toLocalDate());
        extra.forEach(b::field);
        return b.build();
    }

    private static List<String> with(List<String> errors, String prefix) {
        return errors.stream().filter(s -> s.startsWith(prefix)).toList();
    }

    @Test
    void branchLeiRows6To13() {
        Map<String, LeiRecord> m = Map.of(
                LEI_A, new LeiRecord(LEI_A, "A", LeiStatus.ISSUED, "general"),
                LEI_B, new LeiRecord(LEI_B, "B", LeiStatus.ISSUED),
                LEI_BR, new LeiRecord(LEI_BR, "Branch", LeiStatus.ISSUED, "BRANCH"));
        LeiRegistry reg = lei -> Optional.ofNullable(m.get(lei));
        EmirStatefulValidator v = EmirStatefulValidator.create().withLeiRegistry(reg);
        // GENERAL and unknown category: no branch failure
        assertTrue(with(v.validate(trade(EmirActionType.NEWT, UTI).build()), "EMIR-VR-100").isEmpty());
        // 1.9 is a branch: EMIR-VR-1009-04 (row 13, condition 4)
        EmirRefitReport toBranch = trade(EmirActionType.NEWT, UTI).counterparties(LEI_A, LEI_BR, true).build();
        assertEquals(List.of("EMIR-VR-1009-04"), codes(v.validate(toBranch)));
        // 1.4 a branch; 1.2 and 1.3 default to 1.4 here, so rows 6, 7 and 8 all fire
        EmirRefitReport fromBranch = trade(EmirActionType.NEWT, UTI).counterparties(LEI_BR, LEI_B, true)
                .uti(LEI_BR + "TRADE0001").build();
        List<String> e = v.validate(fromBranch);
        assertEquals(1, with(e, "EMIR-VR-1004-04").size());
        assertEquals(1, with(e, "EMIR-VR-1002-04").size());
        assertEquals(1, with(e, "EMIR-VR-1003-05").size());
        assertEquals(Optional.of(true), m.get(LEI_BR).isBranch());
        assertEquals(Optional.empty(), m.get(LEI_B).isBranch());
        assertEquals("GENERAL", m.get(LEI_A).entityCategory());
    }

    private static List<String> codes(List<String> errors) {
        return errors.stream().map(s -> s.substring(0, s.indexOf(':'))).toList();
    }

    @Test
    void venueRuleRow7() {
        Map<String, MicRecord> m = Map.of(
                "XPAR", new MicRecord("XPAR", "XPAR", "Euronext Paris", MicStatus.ACTIVE, "FR", "RMKT", null, null),
                "XNYS", new MicRecord("XNYS", "XNYS", "NYSE", MicStatus.ACTIVE, "US", "RMKT", null, null),
                "XMTF", new MicRecord("XMTF", "XMTF", "Some MTF", MicStatus.ACTIVE, "DE", "MLTF", null, null),
                "XOLD", new MicRecord("XOLD", "XOLD", "Old RM", MicStatus.EXPIRED, "DE", "RMKT", null,
                        LocalDate.of(2020, 1, 1)),
                "XNOC", new MicRecord("XNOC", "XNOC", "No category", MicStatus.ACTIVE));
        MicRegistry mics = mic -> Optional.ofNullable(m.get(mic));
        EmirStatefulValidator v = EmirStatefulValidator.create().withMicRegistry(mics);
        // regulated market: 1.3 must be blank (counterparties() fills 1.3 with 1.4, so clear it)
        assertTrue(with(v.validate(venue("XPAR", "")), "EMIR-VR-1003-01").isEmpty());
        assertEquals(1, with(v.validate(venue("XPAR", LEI_A)), "EMIR-VR-1003-01").size());
        // MTF and an RM that expired before the trade: 1.3 must be populated
        assertEquals(1, with(v.validate(venue("XMTF", "")), "EMIR-VR-1003-01").size());
        assertEquals(1, with(v.validate(venue("XOLD", "")), "EMIR-VR-1003-01").size());
        assertTrue(with(v.validate(venue("XMTF", LEI_A)), "EMIR-VR-1003-01").isEmpty());
        // not decidable: non-EEA RM (equivalence), unknown MIC, unknown category
        for (String mic : List.of("XNYS", "ZZZZ", "XNOC")) {
            assertTrue(with(v.validate(venue(mic, "")), "EMIR-VR-1003-01").isEmpty(), mic);
            assertTrue(with(v.validate(venue(mic, LEI_A)), "EMIR-VR-1003-01").isEmpty(), mic);
        }
    }

    private static EmirRefitReport venue(String mic, String responsible) {
        return trade(EmirActionType.NEWT, UTI).field("venueOfExecution", mic).entityResponsible(responsible).build();
    }

    @Test
    void utiUnchangedRow26() {
        Function<EmirRefitReport, String> key = r -> r.fields().getOrDefault("internalTradeId", "");
        InMemoryUtiHistory h = new InMemoryUtiHistory(key);
        EmirStatefulValidator v = EmirStatefulValidator.create().withHistory(h).withTradeKey(key);
        h.accept(trade(EmirActionType.NEWT, UTI).field("internalTradeId", "T-1").build());
        assertEquals(Optional.of(UTI), h.firstUtiForTradeKey(LEI_A, "T-1"));
        assertTrue(with(v.validate(trade(EmirActionType.MODI, UTI).field("internalTradeId", "T-1").build()),
                "EMIR-VR-2001-03").isEmpty());
        assertEquals(1, with(v.validate(trade(EmirActionType.MODI, UTI2).field("internalTradeId", "T-1").build()),
                "EMIR-VR-2001-03").size());
        // no key on the report, or a history without keys: skipped
        assertTrue(with(v.validate(trade(EmirActionType.MODI, UTI2).build()), "EMIR-VR-2001-03").isEmpty());
        InMemoryUtiHistory plain = new InMemoryUtiHistory();
        plain.accept(trade(EmirActionType.NEWT, UTI).field("internalTradeId", "T-1").build());
        assertEquals(Optional.empty(), plain.firstUtiForTradeKey(LEI_A, "T-1"));
    }

    @Test
    void unchangedFieldsRows12And67() {
        InMemoryUtiHistory h = new InMemoryUtiHistory();
        h.accept(trade(EmirActionType.NEWT, UTI).build());
        EmirStatefulValidator v = EmirStatefulValidator.create().withHistory(h);
        EmirRefitReport flipped = trade(EmirActionType.MODI, UTI).counterparties(LEI_A, "CLIENT0001", false).build();
        assertEquals(1, with(v.validate(flipped), "EMIR-VR-1008-02").size());
        // MODI moving 2.42 (row 67, condition 3); CORR may
        Instant moved = T.minusSeconds(3600);
        EmirRefitReport modi = trade(EmirActionType.MODI, UTI).terms(moved, LocalDate.of(2026, 3, 4),
                LocalDate.of(2031, 3, 4), "INTR", "SWAP", "BYER", new BigDecimal("1000000"), "EUR").build();
        assertEquals(1, with(v.validate(modi), "EMIR-VR-2042-03").size());
        EmirRefitReport corr = trade(EmirActionType.CORR, UTI).terms(moved, LocalDate.of(2026, 3, 4),
                LocalDate.of(2031, 3, 4), "INTR", "SWAP", "BYER", new BigDecimal("1000000"), "EUR").build();
        assertTrue(with(v.validate(corr), "EMIR-VR-2042-03").isEmpty());
    }

    @Test
    void earlyTerminationDateRow70() {
        InMemoryUtiHistory h = new InMemoryUtiHistory();
        h.accept(trade(EmirActionType.NEWT, UTI).build());
        EmirStatefulValidator v = EmirStatefulValidator.create().withHistory(h);
        // 2045-06: first 2.45 must come with TERM
        assertEquals(1, with(v.validate(bare(EmirActionType.MODI, T, Map.of("earlyTerminationDate", "2026-03-02"))),
                "EMIR-VR-2045-06").size());
        assertTrue(with(v.validate(bare(EmirActionType.TERM, T, Map.of("earlyTerminationDate", "2026-03-02"))),
                "EMIR-VR-2045-06").isEmpty());
        // 2045-02: before the 2.42 date held in the history (2026-03-02)
        assertEquals(1, with(v.validate(bare(EmirActionType.TERM, T, Map.of("earlyTerminationDate", "2026-03-01"))),
                "EMIR-VR-2045-02").size());
        // 2045-03: not earlier than the 2.44 previously reported (2031-03-04)
        Instant late = Instant.parse("2031-03-05T09:00:00Z");
        assertEquals(1, with(v.validate(bare(EmirActionType.TERM, late,
                Map.of("earlyTerminationDate", "2031-03-04"))), "EMIR-VR-2045-03").size());
        // after TERM, a CORR must carry 2.45 (2045-07) until REVI
        h.accept(bare(EmirActionType.TERM, T, Map.of("earlyTerminationDate", "2026-03-02")));
        assertEquals(1, with(v.validate(bare(EmirActionType.CORR, T, Map.of())), "EMIR-VR-2045-07").size());
        assertTrue(with(v.validate(bare(EmirActionType.CORR, T, Map.of("earlyTerminationDate", "2026-03-02"))),
                "EMIR-VR-2045").isEmpty());
        // 2045-08: REVI after TERM (not EROR) leaves 2.45 blank
        assertEquals(1, with(v.validate(bare(EmirActionType.REVI, T, Map.of("earlyTerminationDate", "2026-03-02"))),
                "EMIR-VR-2045-08").size());
        assertTrue(with(v.validate(bare(EmirActionType.REVI, T, Map.of())), "EMIR-VR-2045-08").isEmpty());
        h.accept(bare(EmirActionType.REVI, T, Map.of()));
        assertTrue(with(v.validate(bare(EmirActionType.CORR, T, Map.of())), "EMIR-VR-2045-07").isEmpty());
    }

    @Test
    void reviveRules2044And2045AfterEror() {
        InMemoryUtiHistory h = new InMemoryUtiHistory();
        h.accept(trade(EmirActionType.NEWT, UTI).build());
        h.accept(bare(EmirActionType.TERM, T, Map.of("earlyTerminationDate", "2026-03-02")));
        EmirStatefulValidator v = EmirStatefulValidator.create().withHistory(h);
        // 2044-04: REVI after TERM, 2.44 before the date of 1.1
        assertEquals(1, with(v.validate(bare(EmirActionType.REVI, T, Map.of("expirationDate", "2026-03-01"))),
                "EMIR-VR-2044-04").size());
        // after EROR the REVI may restore 2.45 and an old 2.44
        h.accept(bare(EmirActionType.EROR, T, Map.of()));
        List<String> e = v.validate(bare(EmirActionType.REVI, T,
                Map.of("expirationDate", "2026-03-01", "earlyTerminationDate", "2026-03-02")));
        assertTrue(with(e, "EMIR-VR-2044-04").isEmpty());
        assertTrue(with(e, "EMIR-VR-2045-08").isEmpty());
    }

    @Test
    void eventDateRow186() {
        InMemoryUtiHistory h = new InMemoryUtiHistory();
        h.accept(trade(EmirActionType.NEWT, UTI).build());
        EmirStatefulValidator v = EmirStatefulValidator.create().withHistory(h);
        // 2153-06: CORR event date before the 2.42 date from the history
        assertEquals(1, with(v.validate(bare(EmirActionType.CORR, T, Map.of("eventDate", "2026-03-01"))),
                "EMIR-VR-2153-06").size());
        assertTrue(with(v.validate(bare(EmirActionType.CORR, T, Map.of())), "EMIR-VR-2153-06").isEmpty());
        // 2153-08: event date after the 2.44 reported for the UTI (current report leaves 2.44 blank)
        Instant after = Instant.parse("2031-03-05T09:00:00Z");
        assertEquals(1, with(v.validate(bare(EmirActionType.VALU, after, Map.of())), "EMIR-VR-2153-08").size());
        assertTrue(with(v.validate(bare(EmirActionType.REVI, after, Map.of())), "EMIR-VR-2153-08").isEmpty());
        // and after the 2.45 of a TERM, until a REVI resets it
        h.accept(bare(EmirActionType.TERM, T, Map.of("earlyTerminationDate", "2026-03-02")));
        Instant next = Instant.parse("2026-03-03T09:00:00Z");
        assertEquals(1, with(v.validate(bare(EmirActionType.VALU, next, Map.of())), "EMIR-VR-2153-08").size());
        h.accept(bare(EmirActionType.REVI, next, Map.of()));
        assertTrue(with(v.validate(bare(EmirActionType.VALU, next, Map.of())), "EMIR-VR-2153-08").isEmpty());
    }

    @Test
    void catalogueListsTheNewRules() {
        List<String> ids = EmirStatefulValidator.rules().stream().map(RuleProvenance::id).toList();
        assertEquals(30, ids.size());
        for (String id : List.of("EMIR-VR-1003-01", "EMIR-VR-2001-03", "EMIR-VR-2153-06", "EMIR-VR-2153-08",
                "EMIR-VR-2045-07", "EMIR-VR-1008-02")) {
            assertTrue(ids.contains(id), id);
        }
    }
}
