package com.quantfinlib.regreporting.cat;

import java.util.List;

/**
 * The CAT industry-member event types this package supports, with the non-Boolean fields
 * each one marks 'R' (required) beyond the common ones, in CAT Reporting Technical
 * Specifications for Industry Members v4.1.0 r14 (2026-02-13): Table 16 (MENO), Table 18
 * (MEOR), Table 48 (MEOT) and Table 52 (MEOF). Required Boolean fields are left out because
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
            "clientDetails"));

    private final List<String> required;

    CatEventType(List<String> required) {
        this.required = required;
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
