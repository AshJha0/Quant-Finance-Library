package com.quantfinlib.reporting.cat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quantfinlib.reporting.RuleProvenance;
import com.quantfinlib.reporting.RuleProvenance.Evidence;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins each rule in {@link CatEvent#rules()}. The CAT IM Technical Specifications are not on
 * disk, so these pin the library's behaviour; no rule is a published reference.
 */
class CatRulesTest {

    private static List<String> errs(CatEvent e) {
        return e.validate();
    }

    private static CatEventBuilder menoB() {
        CatEvent base = CatEventTest.meno();
        CatEventBuilder b = CatEventBuilder.newOrder();
        base.fields().forEach(b::field);
        return b;
    }

    @Test
    void catalogueIsCompleteOrderedAndHonest() {
        List<RuleProvenance> rules = CatEvent.rules();
        assertEquals(8, rules.size());
        for (int i = 0; i < rules.size(); i++) {
            assertEquals("CAT-R0" + (i + 1), rules.get(i).id());
            assertFalse(rules.get(i).evidence() == Evidence.PUBLISHED_REFERENCE);
        }
        assertEquals(Evidence.LIBRARY_READING, rules.get(3).evidence());
        assertTrue(CatEvent.CHECKED_AGAINST.startsWith("not checked"));
    }

    @Test
    void r01CommonFields() {
        assertEquals(List.of(), errs(menoB().build()));
        assertEquals(List.of("missing symbol"), errs(menoB().field("symbol", "").build()));
        assertEquals(List.of("missing orderID"), errs(menoB().field("orderID", "").build()));
    }

    @Test
    void r02PerTypeFields() {
        assertEquals(List.of("missing deptType"), errs(menoB().field("deptType", "").build()));
    }

    @Test
    void r03ActionTypeValues() {
        assertEquals(List.of("actionType must be one of [NEW, COR, DEL, RPR]").size(),
                errs(menoB().field("actionType", "UPD").build()).size());
    }

    @Test
    void r04ErrorRoeidLibraryReading() {
        assertEquals(List.of("errorROEID required for actionType RPR"),
                errs(menoB().field("actionType", "RPR").build()));
        assertEquals(List.of(), errs(menoB().field("actionType", "DEL").build()));
        assertEquals(List.of(), errs(menoB().field("actionType", "COR").field("errorROEID", "E1").build()));
    }

    @Test
    void r05ToR08Formats() {
        assertEquals(List.of(), errs(menoB().field("side", "SX").field("CATReporterIMID", "ABCDEF7")
                .field("quantity", "0.000001").field("price", "-1.12345678").build()));
        assertEquals(1, errs(menoB().field("side", "S").build()).size());
        assertEquals(1, errs(menoB().field("CATReporterIMID", "ABCDEFGH").build()).size());
        assertEquals(List.of("quantity must be a non-negative decimal"),
                errs(menoB().field("quantity", "1.1234567").build()));
        assertEquals(List.of("price must be a decimal with at most 8 places"),
                errs(menoB().field("price", "1.123456789").build()));
    }
}
