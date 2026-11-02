package com.quantfinlib.regreporting.cat;

import java.util.List;

/**
 * The CAT industry-member event types this package supports, with the extra fields each
 * one requires on top of the common ones (see {@link CatEventBuilder}).
 */
public enum CatEventType {
    /** New order accepted by the member. */
    MENO(List.of("side", "price", "quantity", "orderType", "timeInForce", "tradingSession",
            "handlingInstructions", "deptType")),
    /** Order routed to another member or exchange. */
    MEOR(List.of("side", "price", "quantity", "orderType", "timeInForce", "tradingSession",
            "senderIMID", "destination", "destinationType", "routedOrderID", "session",
            "handlingInstructions")),
    /** Trade executed by the member (off-exchange). */
    MEOT(List.of("tradeID", "price", "quantity", "marketCenterID", "sideDetailsInd",
            "buyDetails", "sellDetails")),
    /** Order fulfillment (fill given to a customer from a firm execution). */
    MEOF(List.of("fulfillmentID", "price", "quantity", "fulfillmentLinkType", "side"));

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
