package com.quantfinlib.regreporting.cat;

/**
 * The family of a CAT event type: the grouping used by the event summaries of CAT Reporting
 * Technical Specifications for Industry Members v4.1.0 r14, Table 15 (equity), Table 60
 * (simple option) and Table 61 (multi-leg option). See {@link CatEventType#family()}.
 *
 * @since 3.2.0
 */
public enum CatEventFamily {
    /** A new order, or an order accepted from another member or an exchange. */
    ORDER,
    /** A supplement that adds data to an earlier event with the same key. */
    SUPPLEMENT,
    /** A route to another member or an exchange, and its modification or cancellation. */
    ROUTE,
    /** An internal route between desks, with its modification, cancellation and requests. */
    INTERNAL_ROUTE,
    /** A child order, with its modification and cancellation. */
    CHILD_ORDER,
    /** An order modification, modification request or adjustment. */
    MODIFICATION,
    /** An order cancellation or cancel request. */
    CANCELLATION,
    /** A quote event: new, routed, received, modified or cancelled. */
    QUOTE,
    /** A trade. */
    TRADE,
    /** A fulfillment or a fulfillment amendment. */
    FULFILLMENT,
    /** A post-trade allocation or an amended allocation. */
    ALLOCATION,
    /** An order effective event (a stop or other order that becomes effective). */
    EFFECTIVE
}
