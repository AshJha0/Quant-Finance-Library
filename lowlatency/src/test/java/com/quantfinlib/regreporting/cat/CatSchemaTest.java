package com.quantfinlib.regreporting.cat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quantfinlib.regreporting.RuleProvenance;
import com.quantfinlib.regreporting.RuleProvenance.Evidence;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link CatSchema}: the Table 3 data-type examples, the section 6.1.2.2 record example and
 * the section 6.1.4.3 schema example of CAT IM Technical Specifications v4.2.0 r4 are
 * pinned; the other cases are own constructions.
 */
class CatSchemaTest {
    private static final CatSchema S = CatSchema.bundledSubset();

    /** Section 6.1.2.2 rule 3c, printed MENO repair record (one line). */
    private static final String PRINTED_RPR = "{\"actionType\":\"RPR\",\"errorROEID\":12345678,\"firmROEID\":"
            + "\"20170801_firmROEO12345\",\"type\":\"MENO\",\"orderKeyDate\":\"20170801T143031.000000\","
            + "\"orderID\":\"O12345\",\"symbol\":\"XYZ\",\"eventTimestamp\":\"20170801T143031.123456\","
            + "\"manualFlag\":false,\"deptType\":\"O\",\"side\":\"B\",\"price\":10.01,\"quantity\":500,"
            + "\"orderType\":\"LMT\",\"timeInForce\":{\"DAY\":20170801},\"tradingSession\":\"REG\","
            + "\"custDspIntrFlag\":false,\"firmDesignatedID\":\"PROP456\",\"accountHolderType\":\"O\","
            + "\"negotiatedTradeFlag\":false}";

    private static List<String> rules(List<CatSchema.Issue> issues) {
        return issues.stream().map(CatSchema.Issue::ruleId).toList();
    }

    @Test
    void bundledSubsetCoversThePackagesTypes() {
        assertEquals("4.2.0", S.version());
        for (CatEventType t : CatEventType.values()) {
            if (CatTableRules.V41_ONLY.contains(t)) {
                // the v4.1.0 r14 quote types are not in v4.2.0 (deprecated in 4.0.0)
                assertFalse(S.eventNames().contains(t.name()), t.name());
                continue;
            }
            assertTrue(S.eventNames().contains(t.name()), t.name());
            for (String f : t.requiredFields()) {
                CatSchema.Field def = S.fields(t.name()).stream().filter(x -> x.name().equals(f)).findFirst()
                        .orElseThrow(() -> new AssertionError(t + "." + f));
                assertEquals("Required", def.required(), t + "." + f);
            }
        }
        assertEquals(55, S.eventNames().size());
        CatSchema.Field ts = S.fields("MENO").stream().filter(f -> f.name().equals("orderKeyDate")).findFirst()
                .orElseThrow();
        assertEquals(List.of("STRING", "NUMBER"), ts.jsonTypes());
        assertEquals(List.of(), S.fields("XXXX"));
    }

    /** Table 3, Numeric (6,4) and (6,0): every printed complying and non-complying example. */
    @Test
    void numericExamplesTable3() {
        for (String ok : List.of("-999999.9999", "-0.1", "0", "0.0001", "100", "100.100", "999999.99", "0.25",
                "099999.9990", "0999999.99990")) {
            assertNull(CatSchema.numeric(ok, 6, 4), ok);
        }
        for (String bad : List.of("1234567.0", ".123", "1.12345", "10.", "40a")) {
            assertNotNull(CatSchema.numeric(bad, 6, 4), bad);
        }
        assertNotNull(CatSchema.numeric("1234.5", 6, 0));
        assertNotNull(CatSchema.numeric("1234.0", 6, 0));
        assertNull(CatSchema.numeric("-9999999999.99999999", 10, 8));
    }

    /** Table 3, Timestamp STRING: the printed examples. */
    @Test
    void timestampExamplesTable3() {
        for (String ok : List.of("20190617T000120.000000000", "20190617T000120", "20190617T000120.000",
                "20170107T213000.123456789", "20170107 213000.123456789", "20190617 000120.123000000")) {
            assertNull(CatSchema.timestamp(ok), ok);
        }
        for (String bad : List.of("20190617T0120", "20190617T000120.", "20191317T000120", "20190617T240000")) {
            assertNotNull(CatSchema.timestamp(bad), bad);
        }
    }

    /** Table 3, Text: printable ASCII without comma, pipe, double quote, @. */
    @Test
    void textRulesTable3() {
        assertNull(CatSchema.textProblem("1234\\ABCD", 64));
        for (String bad : List.of("a,b", "a|b", "a\"b", "a@b", "a\tb", "café")) {
            assertNotNull(CatSchema.textProblem(bad, 64), bad);
        }
        assertNotNull(CatSchema.textProblem("x".repeat(65), 64));
    }

    @Test
    void printedRepairRecordSection6122() {
        List<CatSchema.Issue> issues = S.validateLine(PRINTED_RPR);
        // the printed example omits representativeInd, which Table 15 seq 33 marks 'R'
        assertEquals(List.of("CAT-S05"), rules(issues), issues.toString());
        assertEquals("representativeInd", issues.get(0).field());
        String fixed = PRINTED_RPR.substring(0, PRINTED_RPR.length() - 1) + ",\"representativeInd\":\"N\"}";
        assertEquals(List.of(), S.validateLine(fixed));
        assertEquals(List.of(), S.validateLine(fixed.replace("\"price\":10.01", "\"price\":10.010")));
        assertEquals(List.of("CAT-S07"), rules(S.validateLine(fixed.replace("\"quantity\":500",
                "\"quantity\":500.0"))));
        assertEquals(List.of("CAT-S06"), rules(S.validateLine(fixed.replace("\"price\":10.01",
                "\"price\":\"10.01\""))));
        // the same timestamp as Unsigned nanoseconds (Table 3 example)
        assertEquals(List.of(), S.validateLine(fixed.replace("\"20170801T143031.123456\"",
                "1483842600123456789")));
    }

    @Test
    void recordShapeRules() {
        assertEquals(List.of("CAT-S02"), rules(S.validateLine("{\"type\":")));
        assertEquals(List.of("CAT-S02"), rules(S.validateLine("[1]")));
        assertEquals(List.of("CAT-S02"), rules(S.validateLine("{\"a\":1,\"a\":2}")));
        assertEquals(List.of("CAT-S01"), rules(S.validateLine("{\"type\":\n\"MENO\"}")));
        assertTrue(rules(S.validateLine("{\"type\":\"MENO\",\"orderID\":\"" + "x".repeat(8200) + "\"}"))
                .contains("CAT-S01"));
        assertEquals(List.of("CAT-S03"), rules(S.validateLine("{\"type\":\"ZZZZ\"}")));
        // DEL needs no other field (section 7.6.3.1, printed)
        assertEquals(List.of(), S.validateLine("{\"actionType\":\"DEL\",\"errorROEID\":45678901,\"type\":\"MENO\"}"));
        String fixed = PRINTED_RPR.substring(0, PRINTED_RPR.length() - 1) + ",\"representativeInd\":\"N\"}";
        assertEquals(List.of("CAT-S04"), rules(S.validateLine(fixed.replace("\"orderID\"", "\"OrderID\"")
                .replace("\"O12345\",", "\"O12345\",\"orderID\":\"O1\","))));
        assertEquals(List.of("CAT-S09"), rules(S.validateLine(fixed.replace(
                "\"deptType\":\"O\"", "\"deptType\":\"O\",\"minQty\":\"\""))));
        assertEquals(List.of("CAT-S08"), rules(S.validateLine(fixed.replace("\"side\":\"B\"", "\"side\":\"S\""))));
        assertEquals(List.of("CAT-S07"), rules(S.validateLine(fixed.replace("\"PROP456\"", "\"PROP,456\""))));
        assertEquals(List.of("CAT-S07"), rules(S.validateLine(fixed.replace("12345678", "-1"))));
        List<CatSchema.Issue> two = S.validateLines(List.of(fixed, "{}"));
        assertEquals(1, two.size());
        assertEquals(2, two.get(0).line());
    }

    /** CatSchema.toJson writes typed JSON that passes its own schema, for builder-made events. */
    @Test
    void typedJsonRoundTrip() {
        Instant t0 = Instant.parse("2026-03-02T14:30:00Z");
        CatEvent mono = CatLifecycleMultiLegTest.mono("O-1", "R1");
        String line = S.toJson(mono);
        assertTrue(line.contains("\"price\":2.15"), line);
        assertTrue(line.contains("\"manualFlag\":false"), line);
        assertTrue(line.contains("\"timeInForce\":{\"DAY\":20260302}"), line);
        assertEquals(List.of(), S.validateLine(line), line);
        CatEvent mlno = CatLifecycleMultiLegTest.mlno().build();
        String ml = S.toJson(mlno);
        assertTrue(ml.contains("\"legDetails\":[{\"optionID\""), ml);
        assertTrue(ml.contains("\"numberOfLegs\":2"), ml);
        assertEquals(List.of(), S.validateLine(ml), ml);
        CatEvent meno = CatEventBuilder.newOrder().reporter("FRMA", "R2").orderKey("O-2", t0).eventTimestamp(t0)
                .symbol("TEST").sidePriceQty("B", new BigDecimal("10.25"), new BigDecimal("100"))
                .field("orderType", "LMT").field("timeInForce", "GTT=20260302T160000.000000")
                .field("handlingInstructions", "AOK|DISP=10.00|DLVT=MM1@MM2").field("tradingSession", "REG")
                .field("deptType", "A").field("representativeInd", "N").field("firmDesignatedID", "ACCT1")
                .field("accountHolderType", "I").field("notInSchema", "1").build();
        String j = S.toJson(meno);
        assertTrue(j.contains("\"handlingInstructions\":{\"AOK\":true,\"DISP\":10.00,\"DLVT\":[\"MM1\",\"MM2\"]}"), j);
        assertTrue(j.contains("\"timeInForce\":{\"GTT\":\"20260302T160000.000000\"}"), j);
        assertEquals(List.of("CAT-S04"), rules(S.validateLine(j)));
    }

    /** Section 6.1.4.3 printed schema fragment (MECO), plus load, scoped choices and dataTypes. */
    @Test
    void loadsTheSection6153Layout(@TempDir Path dir) throws IOException {
        String schema = "{\"dataTypes\":[{\"dataType\":\"Text (64)\",\"JSONDataType\":\"STRING\",\"maxLength\":8}],"
                + "\"eventDefinitions\":[{\"eventName\":\"MECO\",\"fields\":[{\"name\":\"actionType\",\"dataType\":"
                + "\"Choice\",\"JSONDataType\":\"STRING\",\"required\":\"Required\",\"position\":\"1\"},{\"name\":"
                + "\"errorROEID\",\"dataType\":\"Unsigned\",\"JSONDataType\":\"NUMBER\",\"required\":\"Conditional\","
                + "\"position\":\"2\"},{\"name\":\"firmROEID\",\"dataType\":\"Text (64)\",\"JSONDataType\":\"STRING\","
                + "\"required\":\"Required\",\"position\":\"3\"},{\"name\":\"type\",\"dataType\":\"Message Type\","
                + "\"JSONDataType\":\"STRING\",\"required\":\"Required\",\"position\":\"4\"},{\"name\":"
                + "\"nbboTimestamp\",\"dataType\":\"Timestamp\",\"JSONDataType\":[\"STRING\",\"NUMBER\"],"
                + "\"required\":\"Conditional\",\"position\":\"31\"}]}],\"choices\":[{\"name\":\"actionType\","
                + "\"eventNames\":[\"MECO\"],\"values\":[\"NEW\"]}]}";
        Path f = dir.resolve("schema.json");
        Files.writeString(f, schema);
        CatSchema s = CatSchema.load(f);
        assertEquals("", s.version());
        assertEquals(31, s.fields("MECO").get(4).position());
        assertEquals(List.of(), s.validateLine("{\"actionType\":\"NEW\",\"firmROEID\":\"A1\",\"type\":\"MECO\","
                + "\"nbboTimestamp\":1483842600123456789}"));
        assertEquals(List.of("CAT-S08", "CAT-S07"), rules(s.validateLine("{\"actionType\":\"COR\",\"firmROEID\":"
                + "\"123456789\",\"type\":\"MECO\"}")));
        for (String bad : List.of("[]", "{}", "{\"eventDefinitions\":[1]}", "{\"eventDefinitions\":[{\"eventName\":"
                + "\"X\",\"fields\":[{\"name\":\"a\",\"dataType\":\"Text (1)\",\"JSONDataType\":1,\"required\":"
                + "\"Required\"}]}]}", "not json", "{\"eventDefinitions\":[{\"eventName\":\"X\",\"fields\":[{"
                + "\"name\":\"a\",\"dataType\":\"Text (1)\",\"JSONDataType\":\"STRING\",\"required\":\"Required\","
                + "\"position\":\"p\"}]}]}")) {
            assertThrows(IOException.class, () -> CatSchema.parse(bad), bad);
        }
    }

    @Test
    void ruleCatalogue() {
        List<RuleProvenance> r = CatSchema.rules();
        assertEquals(9, r.size());
        for (int i = 0; i < r.size(); i++) {
            assertEquals(String.format("CAT-S%02d", i + 1), r.get(i).id());
            assertTrue(r.get(i).evidence() != Evidence.UNCONFIRMED);
        }
        assertTrue(CatEvent.CHECKED_AGAINST_LIFECYCLE.contains("Tables 16, 62, 69"));
    }
}
