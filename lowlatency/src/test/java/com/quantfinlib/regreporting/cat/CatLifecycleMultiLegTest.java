package com.quantfinlib.regreporting.cat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Option lifecycle, supplement and multi-leg events and cross-file linkage (3.1.0), each case
 * written from the CAT IM Technical Specifications v4.2.0 r4 table cited on the test. Cases
 * are own constructions.
 */
class CatLifecycleMultiLegTest {
    private static final Instant T0 = Instant.parse("2026-03-02T14:30:00Z");
    private static final Instant T1 = Instant.parse("2026-03-02T14:31:00Z");
    private static final String OSI = "AAPL  260320C00150000";
    private static final String OSI_P = "AAPL  260320P00140000";

    static CatEvent mono(String orderId, String roe) {
        return CatEventBuilder.newOptionOrder().reporter("FRMA", roe).orderKey(orderId, T0).eventTimestamp(T0)
                .optionId(OSI).sidePriceQty("B", new BigDecimal("2.15"), new BigDecimal("10"))
                .field("orderType", "LMT").field("timeInForce", "DAY=20260302").field("tradingSession", "REG")
                .field("deptType", "A").field("firmDesignatedID", "ACCT1").field("accountHolderType", "I")
                .field("representativeInd", "N").build();
    }

    static CatEvent moor(String orderId, String roe) {
        return CatEventBuilder.optionRoute().reporter("FRMA", roe).orderKey(orderId, T0).eventTimestamp(T0)
                .optionId(OSI).sidePriceQty("B", new BigDecimal("2.15"), new BigDecimal("10"))
                .field("orderType", "LMT").field("timeInForce", "DAY=20260302").field("tradingSession", "REG")
                .field("destinationType", "E").build();
    }

    static CatEvent mooc(String orderId, String roe) {
        return CatEventBuilder.optionCancelled().reporter("FRMA", roe).orderKey(orderId, T0).eventTimestamp(T1)
                .optionId(OSI).field("cancelQty", "10").field("leavesQty", "0").field("initiator", "C").build();
    }

    static CatEventBuilder mooa() {
        return CatEventBuilder.optionAccepted().reporter("FRMB", "A1").orderKey("B-1", T0).eventTimestamp(T0)
                .optionId(OSI).sidePriceQty("S", new BigDecimal("2.10"), new BigDecimal("5"))
                .field("receiverIMID", "123:FRMB").field("senderIMID", "456:FRMA").field("senderType", "F")
                .field("routedOrderID", "R-9").field("deptType", "A").field("orderType", "LMT")
                .field("timeInForce", "DAY=20260302").field("tradingSession", "REG");
    }

    static CatEventBuilder mlno() {
        return mlnoNoLegs().optionLeg(OSI, "B", BigDecimal.ONE, "Open").optionLeg(OSI_P, "S", BigDecimal.ONE, "Open");
    }

    static CatEventBuilder mlnoNoLegs() {
        return CatEventBuilder.multiLegNewOrder().reporter("FRMA", "M1").orderKey("ML-1", T0).eventTimestamp(T0)
                .field("deptType", "A").field("price", "-0.35").field("quantity", "10").field("orderType", "LMT")
                .field("timeInForce", "DAY=20260302").field("tradingSession", "REG")
                .field("firmDesignatedID", "ACCT1").field("accountHolderType", "I").field("representativeInd", "N")
                .field("priceType", "PU");
    }

    /** Table 69 (MOOA): required fields, senderType E/F/O (Appendix G), price rules seq 21. */
    @Test
    void optionAcceptedTable70() {
        assertEquals(List.of(), mooa().build().validate());
        assertTrue(CatEventType.MOOA.isOption());
        List<String> e = mooa().field("senderType", "X").field("price", "-1").build().validate();
        assertTrue(e.contains("senderType must be one of [E, F, O]"), e.toString());
        assertTrue(e.contains("price must be greater than or equal to zero"), e.toString());
        assertTrue(mooa().field("orderType", "MKT").build().validate()
                .contains("price must be blank when orderType is MKT"));
        assertTrue(mooa().field("receiverIMID", "").build().validate().contains("missing receiverIMID"));
    }

    /** Table 78 (MOOM) and Table 82 (MOOC): initiator C/F; cancelQty, leavesQty Real Quantity. */
    @Test
    void modifiedAndCancelledTables79And83() {
        assertEquals(List.of(), mooc("O-1", "C1").validate());
        CatEvent bad = CatEventBuilder.optionCancelled().reporter("FRMA", "C2").orderKey("O-1", T0)
                .eventTimestamp(T1).optionId(OSI).field("cancelQty", "10.50").field("leavesQty", "-1")
                .field("initiator", "X").build();
        assertEquals(List.of("initiator must be one of [C, F]",
                "cancelQty must be Real Quantity: Numeric (12,6), not negative, no trailing zeros",
                "leavesQty must be Real Quantity: Numeric (12,6), not negative, no trailing zeros"), bad.validate());
        CatEvent moom = CatEventBuilder.optionModified().reporter("FRMA", "M2").orderKey("O-2", T1)
                .eventTimestamp(T1).optionId(OSI).sidePriceQty("B", new BigDecimal("2.20"), new BigDecimal("10"))
                .field("leavesQty", "10").field("initiator", "C").field("orderType", "LMT")
                .field("timeInForce", "DAY=20260302").field("tradingSession", "REG").field("representativeInd", "N")
                .build();
        assertEquals(List.of(), moom.validate());
    }

    /** Table 84 (MOOT) side details, Table 86 (MOOF) option fulfillmentLinkType O/OS/OE. */
    @Test
    void tradeAndFulfillmentTables90And92() {
        CatEvent moot = CatEventBuilder.optionTrade().reporter("FRMA", "T1").field("tradeKeyDate",
                "20260302 093000.000000000").field("tradeID", "TR-1").eventTimestamp(T0).optionId(OSI)
                .field("quantity", "5").field("price", "2.15").field("capacity", "A").field("tapeTradeID", "X1")
                .field("sideDetailsInd", "BUY").field("buyDetails", "[{}]").field("marketCenterID", "C")
                .field("clearingFirm", "123").build();
        assertEquals(List.of(), moot.validate());
        CatEventBuilder moof = CatEventBuilder.optionFulfillment().reporter("FRMA", "F1")
                .fillKey("FL-1", T0).eventTimestamp(T0).optionId(OSI).field("quantity", "5")
                .field("price", "2.15").field("fulfillmentLinkType", "O").field("clientDetails", "[{}]");
        assertEquals(List.of(), moof.build().validate());
        assertEquals(List.of("fulfillmentLinkType must be one of [O, OE, OS]"),
                moof.field("fulfillmentLinkType", "Y").build().validate());
    }

    /** Table 94 and Table 95 (MLNO, legDetails); Table 96 (MLOR); Table 120 (MLOC). */
    @Test
    void multiLegTables100To102And126() {
        CatEvent ok = mlno().build();
        assertEquals(List.of(), ok.validate(), ok.fields().toString());
        assertEquals("2", ok.fields().get("numberOfLegs"));
        assertTrue(ok.fields().get("legDetails").startsWith("[{\"optionID\":\"AAPL  260320C00150000\""));
        assertTrue(CatEventType.MLNO.isMultiLeg());
        assertFalse(CatEventType.MLNO.isOption());
        // equity leg in a stock-tied strategy; openCloseIndicator must be blank on it
        assertEquals(List.of(), mlno().equityLeg("AAPL", "SL", new BigDecimal("100")).field("numberOfLegs", "3")
                .build().validate());
        List<String> e = mlno().field("priceType", "").field("symbol", "AAPL").field("numberOfLegs", "1")
                .optionLeg("BAD", "SL", new BigDecimal("1.50"), "").build().validate();
        assertTrue(e.contains("priceType required when orderType is LMT"), e.toString());
        assertTrue(e.contains("multi-leg events carry no top-level symbol or optionID"), e.toString());
        assertTrue(e.contains("legDetails has 3 legs, more than numberOfLegs 1"), e.toString());
        assertTrue(e.contains("leg 3: optionID must be a 21-character OSI symbol"), e.toString());
        assertTrue(e.contains("leg 3: side must be one of [B, S]"), e.toString());
        assertFalse(e.contains("leg 3: legRatioQuantity must be Real Quantity"), e.toString());
        CatEvent hand = mlnoNoLegs().field("numberOfLegs", "3").field("legDetails", "[{\"symbol\":\"AAPL\",\"optionID\":\"" + OSI
                + "\",\"side\":\"B\"},{\"symbol\":\"MSFT\",\"openCloseIndicator\":\"Open\",\"side\":\"B\","
                + "\"legRatioQuantity\":1.50,\"x\":1},3]").build();
        assertEquals(List.of("leg 1: exactly one of symbol and optionID", "leg 1: missing legRatioQuantity",
                "leg 2: unknown field x", "leg 2: openCloseIndicator must be blank on an equity leg",
                "leg 2: legRatioQuantity must be Real Quantity", "leg 3: must be a JSON object"), hand.validate());
        assertEquals(List.of("legDetails must be a non-empty JSON array of leg objects"),
                mlnoNoLegs().field("numberOfLegs", "1").field("legDetails", "[]").build().validate());
        assertTrue(mlnoNoLegs().field("numberOfLegs", "1").field("legDetails", "[{").build().validate().get(0)
                .startsWith("legDetails must be a JSON array of leg objects"));
        assertEquals(List.of("numberOfLegs must be Whole Quantity, at least 1"),
                mlno().field("numberOfLegs", "0").build().validate());
        CatEvent mlor = CatEventBuilder.multiLegRoute().reporter("FRMA", "M3").orderKey("ML-1", T0)
                .eventTimestamp(T0).field("destinationType", "E").field("price", "-0.35").field("quantity", "10")
                .field("orderType", "LMT").field("timeInForce", "DAY=20260302").field("tradingSession", "REG")
                .field("priceType", "PU").optionLeg(OSI, "B", BigDecimal.ONE, "").optionLeg(OSI_P, "S",
                        BigDecimal.ONE, "").build();
        assertEquals(List.of(), mlor.validate());
        CatEvent mloc = CatEventBuilder.multiLegCancelled().reporter("FRMA", "M4").orderKey("ML-1", T0)
                .eventTimestamp(T1).field("cancelQty", "10").field("leavesQty", "0").field("initiator", "F")
                .build();
        assertEquals(List.of(), mloc.validate());
        assertEquals(List.of("underlying must be Symbol: Text (22)"), CatEventBuilder.multiLegSupplement()
                .reporter("FRMA", "M5").orderKey("ML-1", T0).eventTimestamp(T1)
                .field("underlying", "X".repeat(23)).build().validate());
    }

    /** Tables 16, 62, 122 (supplements) and Tables 78, 82, 120 (lifecycle) linkage: LK04, LK05. */
    @Test
    void supplementAndLifecycleLinkage() {
        CatEvent monos = CatEventBuilder.optionOrderSupplement().reporter("FRMA", "S1").orderKey("O-1", T0)
                .eventTimestamp(T1).optionId(OSI).field("firmDesignatedID", "ACCT2").build();
        assertEquals(List.of(), monos.validate());
        assertTrue(CatEventType.MONOS.isSupplement());
        assertEquals(List.of(), CatLinkageChecker.check(List.of(mono("O-1", "R1"), monos, mooc("O-1", "C1")),
                "FRMA"));
        List<CatLinkageChecker.Issue> un = CatLinkageChecker.check(List.of(monos, mooc("O-1", "C1")), "FRMA");
        assertEquals(List.of("CAT-LK04", "CAT-LK05"), un.stream().map(CatLinkageChecker.Issue::ruleId).toList());
        assertTrue(un.get(0).message().startsWith("MONOS has no matching [MONO, MOOA] Order Key"), un.toString());
        // MENOS links to a MENO only
        CatEvent menos = CatEventBuilder.newOrderSupplement().reporter("FRMA", "S2").orderKey("O-1", T0)
                .eventTimestamp(T1).symbol("TEST").build();
        assertEquals(List.of(), menos.validate());
        assertEquals(1, CatLinkageChecker.check(List.of(mono("O-1", "R1"), menos), "FRMA").size());
        // multi-leg: MLOR, MLOC and MLOS link to the MLNO (key without symbol, Table 94)
        CatEvent mlos = CatEventBuilder.multiLegSupplement().reporter("FRMA", "S3").orderKey("ML-1", T0)
                .eventTimestamp(T1).build();
        CatEvent mlor = CatEventBuilder.multiLegRoute().reporter("FRMA", "M3").orderKey("ML-1", T0)
                .eventTimestamp(T0).build();
        CatEvent mloc = CatEventBuilder.multiLegCancelled().reporter("FRMA", "M4").orderKey("ML-1", T0)
                .eventTimestamp(T1).build();
        assertEquals(List.of(), CatLinkageChecker.check(List.of(mlno().build(), mlos, mlor, mloc), "FRMA"));
        assertEquals(List.of("CAT-LK02", "CAT-LK04", "CAT-LK05"), CatLinkageChecker.check(List.of(mlos, mlor,
                mloc), "FRMA").stream().map(CatLinkageChecker.Issue::ruleId).toList());
    }

    /** Table 78 seq 9-10: a MOOM with a prior key assigns a new Order Key; routes follow the new key. */
    @Test
    void modifiedWithNewOrderKeyTable79() {
        CatEvent moom = CatEventBuilder.optionModified().reporter("FRMA", "M1").orderKey("O-2", T1)
                .eventTimestamp(T1).optionId(OSI).field("priorOrderKeyDate", "20260302 093000.000000000")
                .field("priorOrderID", "O-1").build();
        CatEvent route = CatEventBuilder.optionRoute().reporter("FRMA", "R2").orderKey("O-2", T1)
                .eventTimestamp(T1).optionId(OSI).build();
        assertEquals(List.of(), CatLinkageChecker.check(List.of(mono("O-1", "R1"), moom, route), "FRMA"));
        List<CatLinkageChecker.Issue> orphan = CatLinkageChecker.check(List.of(moom, route), "FRMA");
        assertEquals(List.of("CAT-LK05"), orphan.stream().map(CatLinkageChecker.Issue::ruleId).toList());
        // a MOOA assigns an Order Key; MOOR links to it
        CatEvent fromA = CatEventBuilder.optionRoute().reporter("FRMB", "R3").orderKey("B-1", T0)
                .eventTimestamp(T0).optionId(OSI).build();
        assertEquals(List.of(), CatLinkageChecker.check(List.of(mooa().build(), fromA), "FRMB"));
    }

    /** Section 2.6.1, Table 5: keys are per CATReporterIMID, not per file (checkFiles). */
    @Test
    void crossFileLinkage() {
        CatLinkageChecker.Batch a = new CatLinkageChecker.Batch("FRMA_20260302_A.json", "FRMA",
                List.of(mono("O-1", "R1")));
        CatLinkageChecker.Batch b = new CatLinkageChecker.Batch("FRMA_20260302_B.json", "FRMA",
                List.of(moor("O-1", "R2"), mooc("O-1", "C1")));
        assertEquals(2, CatLinkageChecker.checkFiles(List.of(b)).size());
        assertEquals(List.of(), CatLinkageChecker.checkFiles(List.of(a, b)));
        // the same key in two files is a duplicate; each issue names its file
        CatLinkageChecker.Batch c = new CatLinkageChecker.Batch("FRMA_20260302_C.json", "FRMA",
                List.of(mono("O-1", "R9")));
        List<CatLinkageChecker.FileIssue> dup = CatLinkageChecker.checkFiles(List.of(a, b, c));
        assertEquals(List.of("FRMA_20260302_A.json", "FRMA_20260302_C.json"),
                dup.stream().map(CatLinkageChecker.FileIssue::fileName).toList());
        assertTrue(dup.stream().allMatch(i -> i.issue().ruleId().equals("CAT-LK01")));
        // a different firm's file never links (IMID is part of the key)
        CatLinkageChecker.Batch other = new CatLinkageChecker.Batch("FRMZ_20260302.json", "FRMZ",
                List.of(CatEventBuilder.optionRoute().reporter("", "Z1").orderKey("O-1", T0).eventTimestamp(T0)
                        .optionId(OSI).build()));
        assertEquals("CAT-LK02", CatLinkageChecker.checkFiles(List.of(a, other)).get(0).issue().ruleId());
    }
}
