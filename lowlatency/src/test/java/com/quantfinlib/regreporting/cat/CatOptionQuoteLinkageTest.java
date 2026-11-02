package com.quantfinlib.regreporting.cat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Option, quote and linkage checks added in 3.1.0, each written from the CAT IM Technical
 * Specifications v4.1.0 r14 table cited on the test. Cases are own constructions.
 */
class CatOptionQuoteLinkageTest {
    private static final Instant T0 = Instant.parse("2026-03-02T14:30:00Z");
    /** OSI: root "AAPL  ", 2026-03-20, call, strike 150.000. */
    private static final String OSI = "AAPL  260320C00150000";

    private static CatEvent mono(String orderId, String roe) {
        return CatEventBuilder.newOptionOrder().reporter("FRMA", roe).orderKey(orderId, T0).eventTimestamp(T0)
                .optionId(OSI).sidePriceQty("B", new BigDecimal("2.15"), new BigDecimal("10"))
                .field("orderType", "LMT").field("timeInForce", "DAY=20260302").field("tradingSession", "REG")
                .field("deptType", "A").field("firmDesignatedID", "ACCT1").field("accountHolderType", "I")
                .field("representativeInd", "N").build();
    }

    private static CatEvent moor(String orderId, String roe) {
        return CatEventBuilder.optionRoute().reporter("FRMA", roe).orderKey(orderId, T0).eventTimestamp(T0)
                .optionId(OSI).sidePriceQty("B", new BigDecimal("2.15"), new BigDecimal("10"))
                .field("orderType", "LMT").field("timeInForce", "DAY=20260302").field("tradingSession", "REG")
                .field("destinationType", "E").build();
    }

    private static CatEvent meno(String orderId, String roe) {
        return CatEventBuilder.newOrder().reporter("FRMA", roe).orderKey(orderId, T0).eventTimestamp(T0)
                .symbol("TEST").sidePriceQty("B", new BigDecimal("10.25"), new BigDecimal("100"))
                .field("orderType", "LMT").field("timeInForce", "DAY=20260302").field("tradingSession", "REG")
                .field("deptType", "A").field("representativeInd", "N").field("firmDesignatedID", "ACCT1")
                .field("accountHolderType", "I").build();
    }

    /** Table 62: optionID replaces symbol; side B or S (Appendix G). */
    @Test
    void newOptionOrderTable62() {
        CatEvent e = mono("O-1", "R1");
        assertEquals(List.of(), e.validate());
        assertEquals(CatEventType.MONO, e.type());
        assertTrue(e.type().isOption());
        CatEvent sl = CatEventBuilder.newOptionOrder().reporter("FRMA", "R2").orderKey("O-2", T0)
                .eventTimestamp(T0).optionId("BAD").sidePriceQty("SL", BigDecimal.ONE, BigDecimal.ONE)
                .field("orderType", "MKT").build();
        List<String> errs = sl.validate();
        assertTrue(errs.contains("side must be one of [B, S]"), errs.toString());
        assertTrue(errs.contains("optionID must be a 21-character OSI symbol"), errs.toString());
        assertTrue(errs.contains("price must be blank when orderType is MKT"), errs.toString());
        assertTrue(errs.contains("missing deptType"), errs.toString());
        assertTrue(errs.stream().noneMatch(s -> s.equals("missing symbol")), errs.toString());
        // FLEX Percent prefix (Table 62 seq 8)
        assertEquals(List.of(), CatEventBuilder.newOptionOrder().reporter("FRMA", "R3").orderKey("O-3", T0)
                .eventTimestamp(T0).optionId("%" + OSI).sidePriceQty("S", BigDecimal.ONE, BigDecimal.ONE)
                .field("orderType", "LMT").field("timeInForce", "DAY=20260302").field("tradingSession", "REG")
                .field("deptType", "A").field("firmDesignatedID", "ACCT1").field("accountHolderType", "I")
                .field("representativeInd", "N").build().validate());
    }

    /** Table 64: destinationType 'R'. */
    @Test
    void optionRouteTable64() {
        assertEquals(List.of(), moor("O-1", "R2").validate());
        CatEvent noDest = CatEventBuilder.optionRoute().reporter("FRMA", "R9").orderKey("O-1", T0)
                .eventTimestamp(T0).optionId(OSI).sidePriceQty("B", BigDecimal.ONE, BigDecimal.ONE)
                .field("orderType", "LMT").field("timeInForce", "DAY=20260302").field("tradingSession", "REG")
                .build();
        assertEquals(List.of("missing destinationType"), noDest.validate());
    }

    /** Table 39 (MENQ) and Table 85 (MONQ). */
    @Test
    void quotesTables39And85() {
        CatEvent q = CatEventBuilder.newQuote().reporter("FRMA", "Q1").quoteKey("QT-1", T0).eventTimestamp(T0)
                .symbol("TEST").field("bidPrice", "10.00").field("bidQty", "100").build();
        assertEquals(List.of(), q.validate());
        assertTrue(q.type().isQuote());
        CatEvent bad = CatEventBuilder.newQuote().reporter("FRMA", "Q2").quoteKey("QT-2", T0).eventTimestamp(T0)
                .symbol("TEST").field("askPrice", "-1").field("askQty", "1.5").build();
        assertEquals(List.of("askPrice must be Price: Numeric (10,8), greater than or equal to zero",
                "askQty must be Whole Quantity: Numeric (12,0)"), bad.validate());
        CatEvent oq = CatEventBuilder.newOptionQuote().reporter("FRMA", "Q3").quoteKey("QT-3", T0)
                .eventTimestamp(T0).optionId(OSI).field("firmDesignatedID", "ACCT1")
                .field("accountHolderType", "I").build();
        assertEquals(List.of("missing RFQID"), oq.validate());
        assertTrue(CatEventBuilder.fieldOrder(CatEventType.MONQ).contains("RFQID"));
    }

    @Test
    void linkageOrderKeys() {
        // linked: MENO and MEOR share the Order Key; MONO and MOOR likewise
        CatEvent meor = CatEventBuilder.route().reporter("FRMA", "R5").orderKey("O-1", T0).eventTimestamp(T0)
                .symbol("TEST").sidePriceQty("B", BigDecimal.ONE, BigDecimal.ONE).build();
        assertEquals(List.of(), CatLinkageChecker.check(List.of(meno("O-1", "R1"), meor, mono("O-1", "R3"),
                moor("O-1", "R4")), "FRMA"));
        // unlinked route; a MOOR never links to a MENO
        List<CatLinkageChecker.Issue> un = CatLinkageChecker.check(List.of(meno("O-1", "R1"), moor("O-1", "R4")),
                "FRMA");
        assertEquals(1, un.size());
        assertEquals("CAT-LK02", un.get(0).ruleId());
        assertEquals("R4", un.get(0).firmRoeId());
        // duplicate Order Key: every event with the key is reported
        List<CatLinkageChecker.Issue> dup = CatLinkageChecker.check(List.of(meno("O-1", "R1"), meno("O-1", "R2")),
                "FRMA");
        assertEquals(List.of("R1", "R2"), dup.stream().map(CatLinkageChecker.Issue::firmRoeId).toList());
        assertTrue(dup.stream().allMatch(i -> i.ruleId().equals("CAT-LK01")));
    }

    @Test
    void linkageImidFallbackAndMissingFields() {
        // the IMID on the event may be blank: the file's IMID is used (Table 16 seq 5 'O')
        CatEvent blankImid = CatEventBuilder.newOrder().reporter("", "R7").orderKey("O-1", T0).eventTimestamp(T0)
                .symbol("TEST").build();
        assertEquals(1, CatLinkageChecker.check(List.of(meno("O-1", "R1"), blankImid), "FRMA").stream()
                .filter(i -> i.firmRoeId().equals("R7") && i.ruleId().equals("CAT-LK01")).count());
        CatEvent noId = CatEventBuilder.trade().reporter("FRMA", "T1").eventTimestamp(T0).symbol("TEST").build();
        List<CatLinkageChecker.Issue> m = CatLinkageChecker.check(List.of(noId), "FRMA");
        assertEquals("CAT-LK03", m.get(0).ruleId());
        // COR records reuse the original's key and are not keyed again
        CatEvent cor = CatEventBuilder.newOrder().reporter("FRMA", "R8").orderKey("O-1", T0).eventTimestamp(T0)
                .symbol("TEST").action("COR", "").build();
        assertEquals(List.of(), CatLinkageChecker.check(List.of(meno("O-1", "R1"), cor), "FRMA"));
        assertEquals(3, CatLinkageChecker.rules().size());
    }
}
