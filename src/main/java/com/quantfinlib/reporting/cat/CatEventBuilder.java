package com.quantfinlib.reporting.cat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds a {@link CatEvent}.
 *
 * <p>Populated fields. Common to all types: {@code actionType} (default {@code NEW}),
 * {@code errorROEID}, {@code firmROEID}, {@code type}, {@code CATReporterIMID},
 * {@code symbol}, {@code eventTimestamp}, {@code manualFlag} (default {@code false}),
 * {@code orderKeyDate}, {@code orderID}. Per type, the names in
 * {@link CatEventType#requiredFields()}, plus {@code firmDesignatedID} and
 * {@code accountHolderType} for MENO. Anything else in the specification (FDID customer
 * records, representative orders, aggregated orders, options legs, quote events, CAIS)
 * is not populated; use {@link #field(String, String)} to add it by name.</p>
 *
 * <p>Timestamps are written in US Eastern time as {@code yyyyMMdd HHmmss.SSSSSSSSS}, the
 * text form in the CAT technical specification; the event must carry its time in
 * {@link Instant} and the zone is applied here. Worked example: an order accepted at
 * 2026-03-02T14:30:00.123456789Z (09:30 Eastern, EST) gives
 * {@code eventTimestamp = "20260302 093000.123456789"} (own test).</p>
 */
public final class CatEventBuilder {
    /** US Eastern, the CAT reporting time zone. */
    public static final ZoneId EASTERN = ZoneId.of("America/New_York");

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd HHmmss.SSSSSSSSS")
            .withZone(EASTERN);

    private final Map<String, String> f = new LinkedHashMap<>();

    private CatEventBuilder(CatEventType type) {
        for (String k : CatEvent.COMMON_FIELDS) {
            f.put(k, "");
        }
        f.put("actionType", "NEW");
        f.put("manualFlag", "false");
        f.put("type", type.name());
        for (String k : type.requiredFields()) {
            f.put(k, "");
        }
    }

    /**
     * Starts a new-order (MENO) event.
     *
     * @return the builder
     */
    public static CatEventBuilder newOrder() {
        CatEventBuilder b = new CatEventBuilder(CatEventType.MENO);
        b.f.put("firmDesignatedID", "");
        b.f.put("accountHolderType", "");
        return b;
    }

    /**
     * Starts an order-route (MEOR) event.
     *
     * @return the builder
     */
    public static CatEventBuilder route() {
        return new CatEventBuilder(CatEventType.MEOR);
    }

    /**
     * Starts a trade (MEOT) event.
     *
     * @return the builder
     */
    public static CatEventBuilder trade() {
        return new CatEventBuilder(CatEventType.MEOT);
    }

    /**
     * Starts an order-fulfillment (MEOF) event.
     *
     * @return the builder
     */
    public static CatEventBuilder fulfillment() {
        return new CatEventBuilder(CatEventType.MEOF);
    }

    /**
     * Sets any field by its specification name.
     *
     * @param name field name
     * @param value value (empty clears it)
     * @return this
     */
    public CatEventBuilder field(String name, String value) {
        f.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
        return this;
    }

    /**
     * Sets the reporter IMID and the firm's record ID.
     *
     * @param imid CAT reporter IMID
     * @param firmRoeId unique record ID in the submission
     * @return this
     */
    public CatEventBuilder reporter(String imid, String firmRoeId) {
        return field("CATReporterIMID", imid).field("firmROEID", firmRoeId);
    }

    /**
     * Sets the order key: order ID plus order key date (the MENO event time).
     *
     * @param orderId firm order ID
     * @param orderKeyTime time of the originating MENO
     * @return this
     */
    public CatEventBuilder orderKey(String orderId, Instant orderKeyTime) {
        return field("orderID", orderId).field("orderKeyDate", TS.format(orderKeyTime));
    }

    /**
     * Sets the event time.
     *
     * @param t event time
     * @return this
     */
    public CatEventBuilder eventTimestamp(Instant t) {
        return field("eventTimestamp", TS.format(t));
    }

    /**
     * Sets the symbol.
     *
     * @param symbol listing symbol
     * @return this
     */
    public CatEventBuilder symbol(String symbol) {
        return field("symbol", symbol);
    }

    /**
     * Sets side, price and quantity.
     *
     * @param side B, SL, SS or SX
     * @param price limit or execution price (use {@code null}-free {@link BigDecimal})
     * @param quantity shares
     * @return this
     */
    public CatEventBuilder sidePriceQty(String side, BigDecimal price, BigDecimal quantity) {
        return field("side", side).field("price", price.toPlainString()).field("quantity", quantity.toPlainString());
    }

    /**
     * Sets the action type and the ID of the record being corrected or repaired.
     *
     * @param actionType NEW, COR, DEL or RPR
     * @param errorRoeId the record being amended (empty for NEW)
     * @return this
     */
    public CatEventBuilder action(String actionType, String errorRoeId) {
        return field("actionType", actionType).field("errorROEID", errorRoeId);
    }

    /**
     * Builds the event. Call {@link CatEvent#validate()} before submitting.
     *
     * @return the event
     */
    public CatEvent build() {
        return new CatEvent(f);
    }

    /**
     * Formats a date as CAT {@code yyyyMMdd}.
     *
     * @param d the date
     * @return text
     */
    public static String date(LocalDate d) {
        return DateTimeFormatter.BASIC_ISO_DATE.format(d);
    }

    /**
     * The common plus type-specific field names, in output order.
     *
     * @param type the type
     * @return names
     */
    public static List<String> fieldOrder(CatEventType type) {
        List<String> out = new java.util.ArrayList<>(CatEvent.COMMON_FIELDS);
        out.addAll(type.requiredFields());
        return out;
    }
}
