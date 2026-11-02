package com.quantfinlib.regreporting.cat;

import java.util.List;

/**
 * The CAT industry-member event types this package supports, with the non-Boolean fields
 * each one marks 'R' (required) beyond the common ones, in CAT Reporting Technical
 * Specifications for Industry Members v4.1.0 r14 (2026-02-13): Table 16 (MENO), Table 18
 * (MEOR), Table 48 (MEOT) and Table 52 (MEOF); since 3.1.0 also Table 39 (MENQ), Table 62
 * (MONO), Table 64 (MOOR) and Table 85 (MONQ); and, also since 3.1.0, the option lifecycle
 * events of Tables 70 (MOOA), 79 (MOOM), 83 (MOOC), 90 (MOOT) and 92 (MOOF), the supplements
 * of Tables 17 (MENOS), 63 (MONOS) and 139 (MLOS), and the multi-leg events of Tables 100
 * (MLNO), 102 (MLOR) and 126 (MLOC). Required Boolean fields are left out because
 * a JSON Boolean that is absent is read as false (Table 3, Boolean). Since 3.2.0 the other
 * simple option events of Table 60 (Tables 65 to 99) and multi-leg events of Table 61
 * (Tables 104 to 141) are here too, with the same reading of 'R'. Evidence: published
 * reference.
 */
public enum CatEventType {
    /** New order accepted by the member (section 4.1, Table 16). */
    MENO(List.of("orderKeyDate", "orderID", "deptType", "side", "quantity", "orderType", "timeInForce",
            "tradingSession", "firmDesignatedID", "accountHolderType", "representativeInd")),
    /** Order routed to another member or exchange (section 4.3, Table 18). */
    MEOR(List.of("orderKeyDate", "orderID", "destinationType", "side", "quantity", "orderType", "timeInForce",
            "tradingSession", "isoInd")),
    /** Trade executed by the member (section 4.11.1, Table 48). */
    MEOT(List.of("tradeKeyDate", "tradeID", "quantity", "price", "capacity", "sideDetailsInd")),
    /** Order fulfillment (section 4.12.1, Table 52). */
    MEOF(List.of("fillKeyDate", "fulfillmentID", "fulfillmentLinkType", "quantity", "price", "capacity",
            "clientDetails")),
    /**
     * New quote in an equity security (section 4.10.1, Table 39). Booleans onlyOneQuoteFlag,
     * unpricedInd and manualFlag are 'R' but left out as above.
     *
     * @since 3.1.0
     */
    MENQ(List.of("quoteKeyDate", "quoteID")),
    /**
     * New option order (section 5.1.1, Table 62). Carries optionID (seq 8) in place of symbol.
     *
     * @since 3.1.0
     */
    MONO(List.of("orderKeyDate", "orderID", "optionID", "deptType", "side", "quantity", "orderType",
            "timeInForce", "tradingSession", "firmDesignatedID", "accountHolderType", "representativeInd")),
    /**
     * Option order route (section 5.1.3, Table 64). Carries optionID (seq 8) in place of symbol.
     *
     * @since 3.1.0
     */
    MOOR(List.of("orderKeyDate", "orderID", "optionID", "destinationType", "side", "quantity", "orderType",
            "timeInForce", "tradingSession")),
    /**
     * New option quote (section 5.1.10.1, Table 85). Carries optionID (seq 8) in place of symbol.
     *
     * @since 3.1.0
     */
    MONQ(List.of("quoteKeyDate", "quoteID", "optionID", "firmDesignatedID", "accountHolderType", "RFQID")),
    /**
     * New order supplement (section 4.2, Table 17): adds aggregatedOrders or a late FDID to a
     * MENO with the same Order Key.
     *
     * @since 3.1.0
     */
    MENOS(List.of("orderKeyDate", "orderID")),
    /**
     * Option order supplement (section 5.1.2, Table 63): the MONO counterpart of MENOS.
     *
     * @since 3.1.0
     */
    MONOS(List.of("orderKeyDate", "orderID", "optionID")),
    /**
     * Option order accepted from another member or an exchange (section 5.1.4, Table 70).
     * Booleans manualFlag, electronicDupFlag, affiliateFlag and solicitationFlag are 'R' but
     * left out as above.
     *
     * @since 3.1.0
     */
    MOOA(List.of("orderKeyDate", "orderID", "optionID", "receiverIMID", "senderIMID", "senderType", "deptType",
            "side", "quantity", "orderType", "timeInForce", "tradingSession")),
    /**
     * Option order modified or cancel/replaced (section 5.1.7, Table 79).
     *
     * @since 3.1.0
     */
    MOOM(List.of("orderKeyDate", "orderID", "optionID", "initiator", "side", "quantity", "leavesQty",
            "orderType", "timeInForce", "tradingSession", "representativeInd")),
    /**
     * Option order fully or partially cancelled (section 5.1.9, Table 83).
     *
     * @since 3.1.0
     */
    MOOC(List.of("orderKeyDate", "orderID", "optionID", "cancelQty", "leavesQty", "initiator")),
    /**
     * Option trade executed manually on a floor (section 5.1.11, Table 90). Booleans
     * cancelFlag and multiLegInd are 'R' but left out as above.
     *
     * @since 3.1.0
     */
    MOOT(List.of("tradeKeyDate", "tradeID", "optionID", "quantity", "price", "capacity", "tapeTradeID",
            "sideDetailsInd", "marketCenterID", "clearingFirm")),
    /**
     * Option order fulfillment (section 5.1.12.1, Table 92).
     *
     * @since 3.1.0
     */
    MOOF(List.of("fillKeyDate", "fulfillmentID", "optionID", "quantity", "price", "fulfillmentLinkType",
            "clientDetails")),
    /**
     * Multi-leg new order (section 5.2.1, Table 100). No symbol or optionID at the top level;
     * each leg in legDetails (Table 101) carries one.
     *
     * @since 3.1.0
     */
    MLNO(List.of("orderKeyDate", "orderID", "deptType", "quantity", "orderType", "timeInForce", "tradingSession",
            "firmDesignatedID", "accountHolderType", "representativeInd", "numberOfLegs", "legDetails")),
    /**
     * Multi-leg order route (section 5.2.2, Table 102); legs restated (Table 103).
     *
     * @since 3.1.0
     */
    MLOR(List.of("orderKeyDate", "orderID", "destinationType", "quantity", "orderType", "timeInForce",
            "tradingSession", "numberOfLegs", "legDetails")),
    /**
     * Multi-leg order cancelled (section 5.2.7, Table 126).
     *
     * @since 3.1.0
     */
    MLOC(List.of("orderKeyDate", "orderID", "cancelQty", "leavesQty", "initiator")),
    /**
     * Multi-leg order supplement (section 5.2.9, Table 139): legs or aggregated orders that
     * did not fit on the MLNO or MLOR with the same Order Key.
     *
     * @since 3.1.0
     */
    MLOS(List.of("orderKeyDate", "orderID")),

    // ---- Simple option events of Table 60 (since 3.2.0) ----
    /**
     * Option Route Modified Event (Table 65). Booleans manualFlag, affiliateFlag, routeRejectedFlag,
     * dupROIDCond, multiLegInd are 'R' but left out as above.
     *
     * @since 3.2.0
     */
    MOMR(List.of("orderKeyDate", "orderID", "optionID", "destinationType", "side", "quantity", "orderType",
            "timeInForce", "tradingSession")),
    /**
     * Option Route Cancelled Event (Table 66). Booleans manualFlag, routeRejectedFlag, multiLegInd are 'R'
     * but left out as above.
     *
     * @since 3.2.0
     */
    MOCR(List.of("orderKeyDate", "orderID", "optionID", "cancelQty", "destinationType")),
    /**
     * Option Order Route Supplement Event (Table 67). Booleans routeRejectedFlag, manualFlag are 'R' but left
     * out as above.
     *
     * @since 3.2.0
     */
    MOORS(List.of("orderKeyDate", "orderID", "optionID", "destinationType")),
    /**
     * Option Route Modified Supplement Event (Table 68). Booleans manualFlag, routeRejectedFlag, multiLegInd
     * are 'R' but left out as above.
     *
     * @since 3.2.0
     */
    MOMRS(List.of("orderKeyDate", "orderID", "optionID", "destinationType")),
    /**
     * Option Route Cancelled Supplement Event (Table 69). Booleans manualFlag, routeRejectedFlag, multiLegInd
     * are 'R' but left out as above.
     *
     * @since 3.2.0
     */
    MOCRS(List.of("orderKeyDate", "orderID", "optionID", "destinationType")),
    /**
     * Option Order Internal Route Accepted Event (Table 71). Booleans manualFlag, multiLegInd are 'R' but
     * left out as above.
     *
     * @since 3.2.0
     */
    MOIR(List.of("orderKeyDate", "orderID", "optionID", "deptType", "receivingDeskType", "side", "quantity",
            "orderType", "timeInForce", "tradingSession")),
    /**
     * Option Order Internal Route Modified Event (Table 72). Booleans manualFlag are 'R' but left out as
     * above.
     *
     * @since 3.2.0
     */
    MOIM(List.of("orderKeyDate", "orderID", "optionID", "deptType", "receivingDeskType", "initiator", "side",
            "quantity", "leavesQty", "orderType", "timeInForce", "tradingSession")),
    /**
     * Option Order Internal Route Cancelled Event (Table 73). Booleans manualFlag are 'R' but left out as
     * above.
     *
     * @since 3.2.0
     */
    MOIC(List.of("orderKeyDate", "orderID", "optionID", "cancelQty", "leavesQty", "initiator")),
    /**
     * Option Order Internal Route Modification Request Event (Table 74). Booleans manualFlag are 'R' but left
     * out as above.
     *
     * @since 3.2.0
     */
    MOIMR(List.of("orderKeyDate", "orderID", "optionID", "deptType", "receivingDeskType", "side", "quantity",
            "orderType", "timeInForce", "tradingSession")),
    /**
     * Option Order Internal Route Cancel Request Event (Table 75). Booleans manualFlag are 'R' but left out
     * as above.
     *
     * @since 3.2.0
     */
    MOICR(List.of("orderKeyDate", "orderID", "optionID", "cancelQty")),
    /**
     * Child Option Order Event (Table 76). Booleans multiLegInd are 'R' but left out as above.
     *
     * @since 3.2.0
     */
    MOCO(List.of("orderKeyDate", "orderID", "optionID", "parentOrderKeyDate", "parentOrderID", "side", "quantity",
            "orderType", "timeInForce", "tradingSession", "openCloseIndicator")),
    /**
     * Child Option Order Modified Event (Table 77).
     *
     * @since 3.2.0
     */
    MOCOM(List.of("orderKeyDate", "orderID", "optionID", "side", "quantity", "leavesQty", "orderType", "timeInForce",
            "tradingSession")),
    /**
     * Child Option Order Cancelled Event (Table 78).
     *
     * @since 3.2.0
     */
    MOCOC(List.of("orderKeyDate", "orderID", "optionID", "side", "cancelQty", "leavesQty")),
    /**
     * Option Order Modified Supplement Event (Table 80).
     *
     * @since 3.2.0
     */
    MOOMS(List.of("orderKeyDate", "orderID", "optionID", "aggregatedOrders")),
    /**
     * Option Order Modification Request Event (Table 81). Booleans manualFlag are 'R' but left out as above.
     *
     * @since 3.2.0
     */
    MOOMR(List.of("orderKeyDate", "orderID", "optionID", "side", "quantity", "timeInForce", "orderType",
            "tradingSession")),
    /**
     * Option Order Adjusted Event (Table 82). Booleans manualFlag are 'R' but left out as above.
     *
     * @since 3.2.0
     */
    MOOJ(List.of("orderKeyDate", "orderID", "optionID", "initiator", "timeInForce")),
    /**
     * Option Order Cancel Request Event (Table 84). Booleans manualFlag are 'R' but left out as above.
     *
     * @since 3.2.0
     */
    MOOCR(List.of("orderKeyDate", "orderID", "optionID", "cancelQty")),
    /**
     * Option Routed Quote Event (Table 86). Booleans dupROIDCond, quoteRejectedFlag are 'R' but left out as
     * above.
     *
     * @since 3.2.0
     */
    MORQ(List.of("quoteKeyDate", "quoteID", "optionID", "senderIMID", "routedQuoteID", "destinationType", "RFQID")),
    /**
     * Option Quote Received Event (Table 87). Booleans dupROIDCond, onlyOneQuoteFlag, RFQFlag are 'R' but
     * left out as above.
     *
     * @since 3.2.0
     */
    MOQR(List.of("quoteKeyDate", "quoteID", "optionID", "receivedQuoteID", "receiverIMID", "RFQID", "senderType")),
    /**
     * Option Quote Cancelled Event (Table 88).
     *
     * @since 3.2.0
     */
    MOQC(List.of("quoteKeyDate", "quoteID", "optionID", "RFQID")),
    /**
     * Option Quote Modified Event (Table 89).
     *
     * @since 3.2.0
     */
    MOQM(List.of("quoteKeyDate", "quoteID", "optionID", "routedQuoteID", "RFQID")),
    /**
     * Option Order Fulfillment Supplement Event (Table 94).
     *
     * @since 3.2.0
     */
    MOOFS(List.of("fillKeyDate", "fulfillmentID", "optionID", "firmDetails")),
    /**
     * Option Order Fulfillment Amendment Event (Table 96). Booleans manualFlag are 'R' but left out as above.
     *
     * @since 3.2.0
     */
    MOFA(List.of("fillKeyDate", "fulfillmentID", "optionID", "fulfillmentLinkType", "quantity", "price")),
    /**
     * Option Post-Trade Allocation Event (Table 97). Booleans cancelFlag, institutionFlag are 'R' but left
     * out as above.
     *
     * @since 3.2.0
     */
    MOPA(List.of("allocationKeyDate", "allocationID", "optionID", "quantity", "price", "side", "firmDesignatedID",
            "tradeDate", "allocationType", "accountHolderType")),
    /**
     * Option Amended Allocation Event (Table 98). Booleans institutionFlag, cancelFlag are 'R' but left out
     * as above.
     *
     * @since 3.2.0
     */
    MOAA(List.of("allocationKeyDate", "allocationID", "optionID", "quantity", "price", "side", "firmDesignatedID",
            "tradeDate", "allocationType", "accountHolderType")),
    /**
     * Option Order Effective Event (Table 99).
     *
     * @since 3.2.0
     */
    MOOE(List.of("orderKeyDate", "orderID", "optionID", "timeInForce", "tradingSession", "orderType")),

    // ---- Multi-leg option events of Table 61 (since 3.2.0) ----
    /**
     * Multi-Leg Route Modified Event (Table 104). Booleans manualFlag, affiliateFlag, routeRejectedFlag,
     * dupROIDCond are 'R' but left out as above.
     *
     * @since 3.2.0
     */
    MLMR(List.of("orderKeyDate", "orderID", "destinationType", "quantity", "orderType", "timeInForce",
            "tradingSession", "numberOfLegs", "legDetails")),
    /**
     * Multi-Leg Route Cancelled Event (Table 106). Booleans manualFlag, routeRejectedFlag are 'R' but left
     * out as above.
     *
     * @since 3.2.0
     */
    MLCR(List.of("orderKeyDate", "orderID", "cancelQty", "destinationType")),
    /**
     * Multi-Leg Order Accepted Event (Table 107). Booleans manualFlag, electronicDupFlag, affiliateFlag,
     * solicitationFlag are 'R' but left out as above.
     *
     * @since 3.2.0
     */
    MLOA(List.of("orderKeyDate", "orderID", "receiverIMID", "senderIMID", "senderType", "quantity", "orderType",
            "timeInForce", "tradingSession", "numberOfLegs", "legDetails", "deptType")),
    /**
     * Multi-Leg Order Internal Route Accepted Event (Table 109). Booleans manualFlag are 'R' but left out as
     * above.
     *
     * @since 3.2.0
     */
    MLIR(List.of("orderKeyDate", "orderID", "deptType", "receivingDeskType", "quantity", "orderType", "timeInForce",
            "tradingSession", "numberOfLegs", "legDetails")),
    /**
     * Multi-Leg Order Internal Route Modified Event (Table 111). Booleans manualFlag are 'R' but left out as
     * above.
     *
     * @since 3.2.0
     */
    MLIM(List.of("orderKeyDate", "orderID", "deptType", "receivingDeskType", "initiator", "quantity", "leavesQty",
            "orderType", "timeInForce", "tradingSession", "numberOfLegs", "legDetails")),
    /**
     * Multi-Leg Order Internal Route Cancelled Event (Table 113). Booleans manualFlag are 'R' but left out as
     * above.
     *
     * @since 3.2.0
     */
    MLIC(List.of("orderKeyDate", "orderID", "cancelQty", "leavesQty", "initiator")),
    /**
     * Multi-Leg Order Internal Route Modification Request Event (Table 114). Booleans manualFlag are 'R' but
     * left out as above.
     *
     * @since 3.2.0
     */
    MLIMR(List.of("orderKeyDate", "orderID", "deptType", "receivingDeskType", "quantity", "orderType", "timeInForce",
            "tradingSession", "numberOfLegs", "legDetails")),
    /**
     * Multi-Leg Order Internal Route Cancel Request Event (Table 116). Booleans manualFlag are 'R' but left
     * out as above.
     *
     * @since 3.2.0
     */
    MLICR(List.of("orderKeyDate", "orderID", "cancelQty")),
    /**
     * Multi-Leg Child Order Event (Table 117).
     *
     * @since 3.2.0
     */
    MLCO(List.of("orderKeyDate", "orderID", "parentOrderKeyDate", "parentOrderID", "quantity", "orderType",
            "timeInForce", "tradingSession", "numberOfLegs", "legDetails")),
    /**
     * Multi-Leg Child Order Modified Event (Table 119).
     *
     * @since 3.2.0
     */
    MLCOM(List.of("orderKeyDate", "orderID", "quantity", "leavesQty", "orderType", "timeInForce", "tradingSession",
            "numberOfLegs", "legDetails")),
    /**
     * Multi-Leg Child Order Cancelled Event (Table 121).
     *
     * @since 3.2.0
     */
    MLCOC(List.of("orderKeyDate", "orderID", "cancelQty", "leavesQty")),
    /**
     * Multi-Leg Order Modified Event (Table 122). Booleans manualFlag, electronicDupFlag are 'R' but left out
     * as above.
     *
     * @since 3.2.0
     */
    MLOM(List.of("orderKeyDate", "orderID", "initiator", "quantity", "leavesQty", "orderType", "timeInForce",
            "tradingSession", "representativeInd", "numberOfLegs", "legDetails")),
    /**
     * Multi-Leg Order Modification Request Event (Table 124). Booleans manualFlag are 'R' but left out as
     * above.
     *
     * @since 3.2.0
     */
    MLOMR(List.of("orderKeyDate", "orderID", "timeInForce", "quantity", "orderType", "tradingSession", "numberOfLegs",
            "legDetails")),
    /**
     * Multi-Leg Order Cancel Request Event (Table 127). Booleans manualFlag are 'R' but left out as above.
     *
     * @since 3.2.0
     */
    MLOCR(List.of("orderKeyDate", "orderID", "cancelQty")),
    /**
     * Multi-Leg New Quote Event (Table 128). Booleans onlyOneQuoteFlag, RFQFlag are 'R' but left out as
     * above.
     *
     * @since 3.2.0
     */
    MLNQ(List.of("quoteKeyDate", "quoteID", "firmDesignatedID", "accountHolderType", "numberOfLegs", "RFQID",
            "legDetails")),
    /**
     * Multi-Leg Routed Quote Event (Table 130). Booleans dupROIDCond, quoteRejectedFlag are 'R' but left out
     * as above.
     *
     * @since 3.2.0
     */
    MLRQ(List.of("quoteKeyDate", "quoteID", "senderIMID", "routedQuoteID", "destinationType", "numberOfLegs", "RFQID",
            "legDetails")),
    /**
     * Multi-Leg Order Supplement Event (Table 132). Booleans quoteRejectedFlag are 'R' but left out as above.
     *
     * @since 3.2.0
     */
    MLQS(List.of("quoteKeyDate", "quoteID", "RFQID")),
    /**
     * Multi-Leg Quote Received Event (Table 134). Booleans dupROIDCond, onlyOneQuoteFlag, RFQFlag are 'R' but
     * left out as above.
     *
     * @since 3.2.0
     */
    MLQR(List.of("quoteKeyDate", "quoteID", "receivedQuoteID", "receiverIMID", "numberOfLegs", "RFQID", "senderType",
            "legDetails")),
    /**
     * Multi-Leg Quote Cancelled Event (Table 136).
     *
     * @since 3.2.0
     */
    MLQC(List.of("quoteKeyDate", "quoteID", "RFQID")),
    /**
     * Multi-Leg Quote Modified Event (Table 137).
     *
     * @since 3.2.0
     */
    MLQM(List.of("quoteKeyDate", "quoteID", "routedQuoteID", "numberOfLegs", "RFQID", "legDetails")),
    /**
     * Multi-Leg Order Effective Event (Table 141).
     *
     * @since 3.2.0
     */
    MLOE(List.of("orderKeyDate", "orderID", "quantity", "orderType", "timeInForce", "tradingSession", "numberOfLegs",
            "legDetails"));

    private final List<String> required;

    CatEventType(List<String> required) {
        this.required = required;
    }

    /**
     * Whether this is an option event, identified by {@code optionID} (the OSI symbol)
     * rather than {@code symbol}, with side values B or S (Appendix G, side).
     *
     * @return true for MONO, MOOR, MONQ and (since the 3.1.0 lifecycle additions) MONOS,
     *     MOOA, MOOM, MOOC, MOOT and MOOF; since 3.2.0 also for the other simple option events
     *     of Table 60
     * @since 3.1.0
     */
    public boolean isOption() {
        return switch (this) {
            case MONO, MOOR, MONQ, MONOS, MOOA, MOOM, MOOC, MOOT, MOOF -> true;
            // the simple option events of Table 60 added in 3.2.0
            case MOMR, MOCR, MOORS, MOMRS, MOCRS, MOIR, MOIM, MOIC, MOIMR,
                    MOICR, MOCO, MOCOM, MOCOC, MOOMS, MOOMR, MOOJ, MOOCR, MORQ,
                    MOQR, MOQC, MOQM, MOOFS, MOFA, MOPA, MOAA, MOOE -> true;
            default -> false;
        };
    }

    /**
     * Whether this is a multi-leg option event (section 5.2), which has neither symbol nor
     * optionID at the top level; the instruments are in legDetails (Table 101).
     *
     * @return true for MLNO, MLOR, MLOC and MLOS; since 3.2.0 also for the other multi-leg
     *     events of Table 61
     * @since 3.1.0
     */
    public boolean isMultiLeg() {
        return switch (this) {
            case MLNO, MLOR, MLOC, MLOS -> true;
            // the multi-leg events of Table 61 added in 3.2.0
            case MLMR, MLCR, MLOA, MLIR, MLIM, MLIC, MLIMR, MLICR, MLCO, MLCOM,
                    MLCOC, MLOM, MLOMR, MLOCR, MLNQ, MLRQ, MLQS, MLQR, MLQC, MLQM, MLOE -> true;
            default -> false;
        };
    }

    /**
     * Whether this is a supplement event, which adds data to the event with the same Order
     * Key and must link to it (Tables 17, 63 and 139).
     *
     * @return true for MENOS, MONOS and MLOS
     * @since 3.1.0
     */
    public boolean isSupplement() {
        return this == MENOS || this == MONOS || this == MLOS;
    }

    /**
     * Whether this is a quote event (MENQ, MONQ), keyed by quoteKeyDate and quoteID.
     *
     * @return true for quote events
     * @since 3.1.0
     */
    public boolean isQuote() {
        return this == MENQ || this == MONQ;
    }

    /**
     * The event family, used to group the types for review and for the linkage checks: the
     * columns of Tables 60 and 61 (orders, routes, internal routes, child orders,
     * modifications, cancellations, quotes, trades, fulfillments, allocations, effective
     * events and supplements).
     *
     * @return the family
     * @since 3.2.0
     */
    public CatEventFamily family() {
        return switch (this) {
            case MLOA, MENO, MONO, MLNO, MOOA -> CatEventFamily.ORDER;
            case MOORS, MOMRS, MOCRS, MOOMS, MOOFS, MLQS, MENOS, MONOS, MLOS -> CatEventFamily.SUPPLEMENT;
            case MOMR, MOCR, MLMR, MLCR, MEOR, MOOR, MLOR -> CatEventFamily.ROUTE;
            case MOIR, MOIM, MOIC, MOIMR, MOICR, MLIR, MLIM, MLIC, MLIMR, MLICR -> CatEventFamily.INTERNAL_ROUTE;
            case MOCO, MOCOM, MOCOC, MLCO, MLCOM, MLCOC -> CatEventFamily.CHILD_ORDER;
            case MOOMR, MOOJ, MLOM, MLOMR, MOOM -> CatEventFamily.MODIFICATION;
            case MOOCR, MLOCR, MOOC, MLOC -> CatEventFamily.CANCELLATION;
            case MORQ, MOQR, MOQC, MOQM, MLNQ, MLRQ, MLQR, MLQC, MLQM, MENQ, MONQ -> CatEventFamily.QUOTE;
            case MEOT, MOOT -> CatEventFamily.TRADE;
            case MOFA, MEOF, MOOF -> CatEventFamily.FULFILLMENT;
            case MOPA, MOAA -> CatEventFamily.ALLOCATION;
            case MOOE, MLOE -> CatEventFamily.EFFECTIVE;
        };
    }

    /**
     * Field names required for this event type in addition to the common ones.
     *
     * @return required field names
     */
    public List<String> requiredFields() {
        return List.copyOf(required);
    }
}
