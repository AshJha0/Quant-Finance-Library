package com.quantfinlib.regreporting.cat;

import java.util.List;

/**
 * The CAT industry-member event types this package supports, with the non-Boolean fields
 * each one marks 'R' (required) beyond the common ones, in CAT Reporting Technical
 * Specifications for Industry Members v4.1.0 r14 (2026-02-13): Table 16 (MENO), Table 18
 * (MEOR), Table 48 (MEOT) and Table 52 (MEOF); since 3.1.0 also Table 39 (MENQ), Table 62
 * (MONO), Table 64 (MOOR) and Table 85 (MONQ). Required Boolean fields are left out because
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
    MONQ(List.of("quoteKeyDate", "quoteID", "optionID", "firmDesignatedID", "accountHolderType", "RFQID"));

    private final List<String> required;

    CatEventType(List<String> required) {
        this.required = required;
    }

    /**
     * Whether this is an option event, identified by {@code optionID} (the OSI symbol)
     * rather than {@code symbol}, with side values B or S (Appendix G, side).
     *
     * @return true for MONO, MOOR and MONQ
     * @since 3.1.0
     */
    public boolean isOption() {
        return this == MONO || this == MOOR || this == MONQ;
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
