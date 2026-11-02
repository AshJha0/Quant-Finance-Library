package com.quantfinlib.regreporting.cat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Linkage of the 47 Table 59/60 types (CAT-LK01, LK04, LK06 to LK09, since 4.0.0). Keys are
 * those of each table's linkage list in CAT IM Technical Specifications v4.2.0 r4; the cases
 * are own constructions.
 */
@SuppressWarnings("removal") // covers the v4.1.0 r14 quote types kept until 5.0.0
class CatTable6061LinkageTest {
    private static final String KD = "20260302 093000.000000000";

    private static CatEventBuilder f(CatEventType t, String roe) {
        return CatTable6061Test.filled(t).reporter("FRMA", roe);
    }

    private static List<String> rules(CatEvent... events) {
        return CatLinkageChecker.check(List.of(events), "FRMA").stream().map(CatLinkageChecker.Issue::ruleId)
                .toList();
    }

    /** Table 75: a child order links to its parent through the Parent Order Key; Table 77 cancel. */
    @Test
    void childOrderParentKey() {
        CatEvent parent = f(CatEventType.MONO, "P").field("orderID", "P1").build();
        CatEvent child = f(CatEventType.MOCO, "C").field("orderID", "C1").field("parentOrderID", "P1")
                .field("parentOrderKeyDate", KD).build();
        CatEvent cancel = f(CatEventType.MOCOC, "X").field("orderID", "C1").build();
        CatEvent route = f(CatEventType.MOOR, "R").field("orderID", "C1").build();
        assertEquals(List.of(), rules(parent, child, cancel, route));
        assertEquals(List.of("CAT-LK06"), rules(child, cancel));
        assertEquals(List.of("CAT-LK07"), rules(parent, f(CatEventType.MOCOC, "Y").field("orderID", "C9").build()));
    }

    /** Table 76: a modified child order links through its Prior Order Key and assigns a new key. */
    @Test
    void priorOrderKey() {
        CatEvent child = f(CatEventType.MOCO, "C").field("orderID", "C1").field("parentOrderID", "").build();
        CatEvent mod = f(CatEventType.MOCOM, "M").field("orderID", "C2").field("priorOrderID", "C1")
                .field("priorOrderKeyDate", KD).build();
        CatEvent cancel = f(CatEventType.MOCOC, "X").field("orderID", "C2").build();
        assertEquals(List.of(), rules(child, mod, cancel));
        CatEvent orphan = f(CatEventType.MOCOM, "M2").field("orderID", "C3").field("priorOrderID", "C9")
                .field("priorOrderKeyDate", KD).build();
        assertEquals(List.of("CAT-LK07"), rules(child, orphan));
    }

    /** v4.1.0 r14 Tables 87-89 (option quotes), 128 and 132 (multi-leg quote and supplement). */
    @Test
    void quoteLifecycle() {
        CatEvent recv = f(CatEventType.MOQR, "Q").field("quoteID", "Q1").build();
        CatEvent canc = f(CatEventType.MOQC, "QC").field("quoteID", "Q1").build();
        CatEvent routed = f(CatEventType.MORQ, "QR").field("quoteID", "Q2").build();
        assertEquals(List.of("CAT-LK08"), rules(recv, canc, routed));
        CatEvent mlnq = f(CatEventType.MLNQ, "N").field("quoteID", "Q5").build();
        CatEvent mlqs = f(CatEventType.MLQS, "S").field("quoteID", "Q5").build();
        assertEquals(List.of(), rules(mlnq, mlqs));
        assertEquals(List.of("CAT-LK04"), rules(mlqs));
        // an option quote never satisfies a multi-leg quote key
        assertEquals(List.of("CAT-LK04"), rules(f(CatEventType.MOQR, "Q9").field("quoteID", "Q5").build(), mlqs));
    }

    /** Table 90 (MOFA: same fulfillmentID or Prior Fulfillment Key) and Table 92 (MOAA). */
    @Test
    void fulfillmentAndAllocationAmendments() {
        CatEvent moof = f(CatEventType.MOOF, "F").field("fulfillmentID", "F1").build();
        CatEvent same = f(CatEventType.MOFA, "A1").field("fulfillmentID", "F1").build();
        assertEquals(List.of(), rules(moof, same));
        assertEquals(List.of("CAT-LK09"), rules(same));
        CatEvent mopa = f(CatEventType.MOPA, "P").field("allocationID", "A1").build();
        CatEvent moaa = f(CatEventType.MOAA, "AA").field("allocationID", "A2").field("priorAllocationID", "A1")
                .field("priorAllocationKeyDate", KD).build();
        assertEquals(List.of(), rules(mopa, moaa));
        assertEquals(List.of("CAT-LK09"), rules(moaa));
    }

    /** Table 5: two internal-route accepted events with one Order Key are both rejected. */
    @Test
    void duplicateKeyAmongNewOrigins() {
        CatEvent a = f(CatEventType.MOIR, "I1").field("orderID", "D1").build();
        CatEvent b = f(CatEventType.MOIR, "I2").field("orderID", "D1").build();
        assertEquals(List.of("CAT-LK01", "CAT-LK01"), rules(a, b));
    }

    /** Table 101: an MLOA is an origin for multi-leg routes and cancels. */
    @Test
    void multiLegAcceptedOrder() {
        CatEvent mloa = f(CatEventType.MLOA, "A").field("orderID", "M1").build();
        CatEvent mlcr = f(CatEventType.MLCR, "C").field("orderID", "M1").build();
        assertEquals(List.of(), rules(mloa, mlcr));
        assertEquals(List.of("CAT-LK07"), rules(mlcr));
    }
}
