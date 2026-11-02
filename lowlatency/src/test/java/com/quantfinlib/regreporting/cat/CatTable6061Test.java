package com.quantfinlib.regreporting.cat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The 47 option and multi-leg event types of Tables 60 and 61 added in 3.2.0, with rules
 * CAT-R22 and CAT-R23. Required fields come from each type's table in CAT IM Technical
 * Specifications v4.1.0 r14; the cases are own constructions unless a test says otherwise.
 */
class CatTable6061Test {
    private static final Instant T0 = Instant.parse("2026-03-02T14:30:00Z");
    private static final String OSI = "AAPL  260320C00150000";
    private static final CatSchema S = CatSchema.bundledSubset();

    /** Fills every required field of {@code t} with a value of its Table 3 data type. */
    static CatEventBuilder filled(CatEventType t) {
        CatEventBuilder b = CatEventBuilder.start(t).reporter("FRMA", "R-" + t).eventTimestamp(T0);
        Map<String, String> types = new java.util.HashMap<>();
        for (CatSchema.Field f : S.fields(t.name())) {
            types.put(f.name(), f.dataType());
        }
        for (String k : t.requiredFields()) {
            String v = switch (k) {
                case "optionID" -> OSI;
                case "side" -> "B";
                case "initiator" -> "C";
                case "senderType" -> "F";
                case "orderType" -> "LMT";
                case "fulfillmentLinkType" -> "O";
                case "numberOfLegs", "legDetails" -> null;
                default -> switch (types.getOrDefault(k, "")) {
                    case "Timestamp" -> "20260302 093000.000000000";
                    case "Real Quantity" -> "10";
                    case "Whole Quantity" -> "1";
                    case "Price" -> "1.5";
                    case "Date" -> "20260302";
                    case "Unsigned" -> "1";
                    default -> "X1";
                };
            };
            if (v != null) {
                b.field(k, v);
            }
        }
        if (types.containsKey("price")) {
            b.field("price", "1.5");
        }
        if (types.containsKey("priceType")) {
            b.field("priceType", "PU");
        }
        if (t.requiredFields().contains("legDetails")) {
            b.optionLeg(OSI, "B", BigDecimal.ONE, "Open");
        }
        return b;
    }

    static List<CatEventType> added() {
        return java.util.Arrays.stream(CatEventType.values()).filter(CatTableRules.ROWS::containsKey).toList();
    }

    /** Tables 60 and 61 list 47 event types beyond the 19 of 3.1; each is option xor multi-leg. */
    @Test
    void typesAndFamilies() {
        assertEquals(47, added().size());
        assertEquals(66, CatEventType.values().length);
        Map<CatEventFamily, Integer> count = new EnumMap<>(CatEventFamily.class);
        for (CatEventType t : CatEventType.values()) {
            assertNotNull(t.family(), t.name());
            count.merge(t.family(), 1, Integer::sum);
        }
        for (CatEventType t : added()) {
            assertTrue(t.isOption() ^ t.isMultiLeg(), t.name());
            assertFalse(t.isSupplement() || t.isQuote(), t.name());
            assertTrue(S.eventNames().contains(t.name()), t.name());
        }
        assertEquals(26, added().stream().filter(CatEventType::isOption).count());
        assertEquals(21, added().stream().filter(CatEventType::isMultiLeg).count());
        assertEquals(CatEventFamily.INTERNAL_ROUTE, CatEventType.MOIR.family());
        assertEquals(CatEventFamily.CHILD_ORDER, CatEventType.MLCO.family());
        assertEquals(CatEventFamily.ALLOCATION, CatEventType.MOPA.family());
        assertEquals(CatEventFamily.QUOTE, CatEventType.MLQR.family());
        assertEquals(CatEventFamily.EFFECTIVE, CatEventType.MOOE.family());
        assertEquals(10, count.get(CatEventFamily.INTERNAL_ROUTE));
        assertEquals(12, count.size());
    }

    /** Table 76 (MOCO): parentOrderKeyDate, parentOrderID and openCloseIndicator are 'R'. */
    @Test
    void requiredFieldsTranscribed() {
        assertTrue(CatEventType.MOCO.requiredFields().containsAll(
                List.of("parentOrderKeyDate", "parentOrderID", "openCloseIndicator")));
        assertEquals(List.of("allocationKeyDate", "allocationID", "optionID", "quantity", "price", "side",
                "firmDesignatedID", "tradeDate", "allocationType", "accountHolderType"),
                CatEventType.MOPA.requiredFields());
        assertEquals(List.of("quoteKeyDate", "quoteID", "RFQID"), CatEventType.MLQS.requiredFields());
    }

    /** Every added type, filled with its required fields, passes validate(); left blank it does not. */
    @Test
    void everyAddedTypeBuildsAndValidates() {
        for (CatEventType t : added()) {
            assertEquals(List.of(), filled(t).build().validate(), t.name());
            List<String> blank = CatEventBuilder.start(t).reporter("FRMA", "B").eventTimestamp(T0).build()
                    .validate();
            assertTrue(blank.contains("missing " + t.requiredFields().get(0)), t + " " + blank);
            List<CatSchema.Issue> si = S.validateLine(S.toJson(filled(t).build()));
            assertTrue(si.stream().noneMatch(i -> i.ruleId().equals("CAT-S03") || i.ruleId().equals("CAT-S05")),
                    t + " " + si);
        }
        assertThrows(NullPointerException.class, () -> CatEventBuilder.start(null));
    }

    /** CAT-R22: Table 65 seq 20, Table 76 seq 14, Table 104 seq 20. */
    @Test
    void priceRules() {
        List<String> v = filled(CatEventType.MOMR).field("price", "-1").build().validate();
        assertTrue(v.contains("price must be greater than or equal to zero (Table 65 seq 20)"), v.toString());
        v = filled(CatEventType.MOCO).field("price", "").build().validate();
        assertTrue(v.contains("price required when orderType is LMT (Table 76 seq 14)"), v.toString());
        v = filled(CatEventType.MLMR).field("orderType", "MKT").build().validate();
        assertTrue(v.contains("price must be blank when orderType is MKT (Table 104 seq 20)"), v.toString());
        // multi-leg net price may be negative (a credit), Table 104 seq 20
        assertEquals(List.of(), filled(CatEventType.MLMR).field("price", "-0.5").build().validate());
        // MOFA price (Table 96 seq 17) must not be negative, but there is no orderType rule
        v = filled(CatEventType.MOFA).field("price", "-2").build().validate();
        assertTrue(v.contains("price must be greater than or equal to zero (Table 96 seq 17)"), v.toString());
    }

    /** CAT-R23: Appendix G initiator, senderType (order and quote events), priceType, fulfillmentLinkType. */
    @Test
    void choiceRules() {
        List<String> v = filled(CatEventType.MOIM).field("initiator", "X").build().validate();
        assertTrue(v.contains("initiator must be one of [C, F] (Table 72 seq 17)"), v.toString());
        v = filled(CatEventType.MLOA).field("senderType", "N").build().validate();
        assertTrue(v.contains("senderType must be one of [E, F, O] (Table 107 seq 17)"), v.toString());
        assertEquals(List.of(), filled(CatEventType.MOQR).field("senderType", "N").build().validate());
        v = filled(CatEventType.MOQR).field("senderType", "E").build().validate();
        assertTrue(v.contains("senderType must be one of [F, N, S] (Table 87 seq 23)"), v.toString());
        v = filled(CatEventType.MLIM).field("priceType", "").build().validate();
        assertTrue(v.contains("priceType required when orderType is LMT (Table 111 seq 29)"), v.toString());
        v = filled(CatEventType.MLIM).field("priceType", "ZZ").build().validate();
        assertTrue(v.contains("priceType must be one of [PU, TC, TS] (Table 111 seq 29)"), v.toString());
        v = filled(CatEventType.MOFA).field("fulfillmentLinkType", "Y").build().validate();
        assertTrue(v.stream().anyMatch(s -> s.startsWith("fulfillmentLinkType")), v.toString());
        v = filled(CatEventType.MOCO).field("side", "SL").build().validate();
        assertTrue(v.stream().anyMatch(s -> s.startsWith("side")), v.toString());
    }

    /** CAT-R21 now also covers the added multi-leg types (Table 117 has no symbol or optionID field). */
    @Test
    void multiLegHasNoTopLevelSymbol() {
        List<String> v = filled(CatEventType.MLCO).symbol("AAPL").build().validate();
        assertTrue(v.contains("multi-leg events carry no top-level symbol or optionID"), v.toString());
        v = filled(CatEventType.MOIR).field("optionID", "BAD").build().validate();
        assertTrue(v.contains("optionID must be a 21-character OSI symbol"), v.toString());
    }

    /** The added types are skipped by the linkage checker; the 3.1 results do not change. */
    @Test
    void linkageSkipsAddedTypes() {
        List<CatEvent> batch = List.of(filled(CatEventType.MOIR).build(), filled(CatEventType.MLCOC).build(),
                filled(CatEventType.MOPA).build());
        assertEquals(List.of(), CatLinkageChecker.check(batch, "FRMA"));
        CatEvent moor = CatLifecycleMultiLegTest.moor("O-9", "R9");
        List<CatEvent> withRoute = new java.util.ArrayList<>(batch);
        withRoute.add(moor);
        assertEquals(CatLinkageChecker.check(List.of(moor), "FRMA"), CatLinkageChecker.check(withRoute, "FRMA"));
    }
}
