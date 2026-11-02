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
 * a JSON Boolean that is absent is read as false (Table 3, Boolean). Evidence: published
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
    MLOS(List.of("orderKeyDate", "orderID"));

    private final List<String> required;

    CatEventType(List<String> required) {
        this.required = required;
    }

    /**
     * Whether this is an option event, identified by {@code optionID} (the OSI symbol)
     * rather than {@code symbol}, with side values B or S (Appendix G, side).
     *
     * @return true for MONO, MOOR, MONQ and (since the 3.1.0 lifecycle additions) MONOS,
     *     MOOA, MOOM, MOOC, MOOT and MOOF
     * @since 3.1.0
     */
    public boolean isOption() {
        return switch (this) {
            case MONO, MOOR, MONQ, MONOS, MOOA, MOOM, MOOC, MOOT, MOOF -> true;
            default -> false;
        };
    }

    /**
     * Whether this is a multi-leg option event (section 5.2), which has neither symbol nor
     * optionID at the top level; the instruments are in legDetails (Table 101).
     *
     * @return true for MLNO, MLOR, MLOC and MLOS
     * @since 3.1.0
     */
    public boolean isMultiLeg() {
        return this == MLNO || this == MLOR || this == MLOC || this == MLOS;
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
     * Field names required for this event type in addition to the common ones.
     *
     * @return required field names
     */
    public List<String> requiredFields() {
        return List.copyOf(required);
    }
}
