package com.quantfinlib.regreporting.cat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quantfinlib.regreporting.RuleProvenance;
import com.quantfinlib.regreporting.RuleProvenance.Evidence;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins each rule in {@link CatEvent#rules()} against CAT Reporting Technical Specifications
 * for Industry Members v4.1.0 r14 (2026-02-13). Section, table and Appendix G entries are in
 * the test names and comments.
 */
class CatRulesTest {

    private static CatEventBuilder menoB() {
        CatEventBuilder b = CatEventBuilder.newOrder();
        CatEventTest.meno().fields().forEach(b::field);
        return b;
    }

    private static List<String> errs(CatEventBuilder b) {
        return b.build().validate();
    }

    /** A bare DEL record: no type and no restatement. */
    private static CatEventBuilder del(String errorRoeid, String firmRoeid) {
        return CatEventBuilder.newOrder().action("DEL", errorRoeid).field("firmROEID", firmRoeid).field("type", "");
    }

    private static CatEventBuilder meot(String ind, String buy, String sell) {
        return CatEventBuilder.trade().reporter("FRMA", "R").eventTimestamp(Instant.EPOCH).symbol("T")
                .tradeKey("T1", Instant.EPOCH).field("price", "1").field("quantity", "1").field("capacity", "P")
                .field("sideDetailsInd", ind).field("buyDetails", buy).field("sellDetails", sell);
    }

    @Test
    void catalogueIsCompleteOrderedAndHonest() {
        List<RuleProvenance> rules = CatEvent.rules();
        assertEquals(16, rules.size());
        for (int i = 0; i < rules.size(); i++) {
            assertEquals(String.format("CAT-R%02d", i + 1), rules.get(i).id());
            assertFalse(rules.get(i).evidence() == Evidence.UNCONFIRMED, rules.get(i).id());
        }
        assertEquals(Evidence.LIBRARY_READING, rules.get(10).evidence());
        assertTrue(CatEvent.CHECKED_AGAINST.startsWith("checked against CAT Reporting Technical Specifications"));
        assertTrue(CatEvent.CHECKED_AGAINST.contains("v4.1.0 r14"));
        assertTrue(CatEvent.CHECKED_AGAINST.endsWith("not certified"));
    }

    /** Section 7.6.3.1 worked examples: a delete carries errorROEID or firmROEID and nothing else. */
    @Test
    void r01DeleteSection763WorkedExamples() {
        assertEquals(List.of(), errs(del("45678901", "")));
        assertEquals(List.of(), errs(del("", "20190416_FirmROE123")));
        assertEquals(List.of("DEL needs errorROEID or firmROEID"), errs(del("", "")));
        assertEquals(List.of("errorROEID must be Unsigned"), errs(del("E1", "")));
    }

    /** Table 16 Include Key 'R': representativeInd, firmROEID, symbol; CATReporterIMID is 'O'. */
    @Test
    void r02RequiredFieldsTable16() {
        assertEquals(List.of(), errs(menoB()));
        assertEquals(List.of(), errs(menoB().field("CATReporterIMID", "")));
        assertEquals(List.of("missing symbol"), errs(menoB().field("symbol", "")));
        assertEquals(List.of("missing representativeInd"), errs(menoB().field("representativeInd", "")));
        assertEquals(List.of("missing firmROEID"), errs(menoB().field("firmROEID", "")));
    }

    /** Appendix G, actionType: NEW, COR, RPR, DEL. */
    @Test
    void r03ActionTypeAppendixG() {
        assertEquals(List.of("actionType must be one of [COR, DEL, NEW, RPR]"), errs(menoB().field("actionType", "UPD")));
    }

    /** Table 16 seq 2; section 7.6.1.1 (RPR, errorROEID 12345678) and 7.6.2.1 (COR) worked examples. */
    @Test
    void r04ErrorRoeidTable16Seq2AndSection76() {
        assertEquals(List.of(), errs(menoB().action("RPR", "12345678")));
        assertEquals(List.of(), errs(menoB().action("COR", "")));
        assertEquals(List.of("errorROEID required for actionType RPR"), errs(menoB().action("RPR", "")));
        assertEquals(List.of("errorROEID must be blank for actionType NEW"), errs(menoB().action("NEW", "1")));
    }

    /** Appendix G, side (equity): B, SL, SS, SX. */
    @Test
    void r05SideAppendixG() {
        for (String s : List.of("B", "SL", "SS", "SX")) {
            assertEquals(List.of(), errs(menoB().field("side", s)), s);
        }
        assertEquals(1, errs(menoB().field("side", "S")).size());
    }

    /** Table 3, CAT Reporter IMID: Alphanumeric (7), case sensitive, lower case allowed. */
    @Test
    void r06ImidTable3() {
        assertEquals(List.of(), errs(menoB().field("CATReporterIMID", "abcDEF7")));
        assertEquals(List.of("CATReporterIMID must be Alphanumeric (7)"), errs(menoB().field("CATReporterIMID", "ABCDEFGH")));
        assertEquals(1, errs(menoB().field("CATReporterIMID", "AB-1")).size());
    }

    /** Table 3, Real Quantity: Numeric (12,6), not negative, no trailing decimal zeros ("100" accepted). */
    @Test
    void r07RealQuantityTable3() {
        for (String q : List.of("100", "0", "999999999999.000001", "1.5")) {
            assertEquals(List.of(), errs(menoB().field("quantity", q)), q);
        }
        for (String q : List.of("100.0", "1.50", "-1", "1234567890123", "1.1234567", ".5")) {
            assertEquals(1, errs(menoB().field("quantity", q)).size(), q);
        }
    }

    /** Table 3, Price: Numeric (10,8); Table 16 seq 19: >= 0, required for LMT, blank for MKT. */
    @Test
    void r08PriceTable3AndTable16Seq19() {
        assertEquals(List.of(), errs(menoB().field("price", "9999999999.99999999")));
        assertEquals(List.of("price must be Price: Numeric (10,8)"), errs(menoB().field("price", "12345678901")));
        assertEquals(List.of("price must be Price: Numeric (10,8)"), errs(menoB().field("price", ".25")));
        assertEquals(List.of("price must be greater than or equal to zero"), errs(menoB().field("price", "-1")));
        assertEquals(List.of("price required when orderType is LMT"), errs(menoB().field("price", "")));
        assertEquals(List.of("price must be blank when orderType is MKT"), errs(menoB().field("orderType", "MKT")));
        assertEquals(List.of(), errs(menoB().field("orderType", "MKT").field("price", "")));
        assertEquals(List.of(), errs(meot("BUY", "b", "").field("price", "-1")));
    }

    /** Appendix G: capacity A/P/R, sideDetailsInd BUY/SELL/NA. */
    @Test
    void r09ChoiceValuesAppendixG() {
        assertEquals(List.of("capacity must be one of [A, P, R]"), errs(menoB().field("capacity", "Q")));
        assertEquals(List.of("sideDetailsInd must be one of [BUY, NA, SELL]"), errs(menoB().field("sideDetailsInd", "X")));
    }

    /** Appendix G, sideDetailsInd BUY/SELL: one side only. */
    @Test
    void r10OneSidedTradeAppendixG() {
        assertEquals(List.of(), errs(meot("BUY", "b", "")));
        assertEquals(List.of(), errs(meot("SELL", "", "s")));
        assertEquals(List.of("sideDetailsInd BUY populates only buyDetails"), errs(meot("BUY", "b", "s")));
        assertEquals(List.of("sideDetailsInd BUY populates only buyDetails"), errs(meot("BUY", "", "")));
        assertEquals(List.of("sideDetailsInd SELL populates only sellDetails"), errs(meot("SELL", "b", "s")));
        assertEquals(List.of("sideDetailsInd SELL populates only sellDetails"), errs(meot("SELL", "", "")));
    }

    /** Library reading: NA (not one sided) needs both sides. */
    @Test
    void r11TwoSidedTradeLibraryReading() {
        assertEquals(List.of(), errs(meot("NA", "b", "s")));
        assertEquals(List.of("sideDetailsInd NA populates buyDetails and sellDetails"), errs(meot("NA", "b", "")));
        assertEquals(List.of("sideDetailsInd NA populates buyDetails and sellDetails"), errs(meot("NA", "", "s")));
    }

    /** Table 3, Timestamp examples (compliant and non-compliant as printed) and Boolean. */
    @Test
    void r12TimestampAndBooleanTable3() {
        for (String ts : List.of("20190617T000120.000000000", "20190617T000120", "20190617T000120.000",
                "20170107 213000.123456789", "1483842600123456789")) {
            assertEquals(List.of(), errs(menoB().field("eventTimestamp", ts)), ts);
        }
        for (String ts : List.of("20190617T0120", "20190617T000120.")) {
            assertEquals(1, errs(menoB().field("eventTimestamp", ts)).size(), ts);
        }
        assertEquals(1, errs(menoB().field("orderKeyDate", "2019-06-17")).size());
        assertEquals(List.of(), errs(menoB().field("manualFlag", "TRUE")));
        assertEquals(List.of("manualFlag must be true or false"), errs(menoB().field("manualFlag", "yes")));
    }
}
