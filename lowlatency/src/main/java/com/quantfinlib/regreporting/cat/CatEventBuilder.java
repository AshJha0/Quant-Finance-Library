package com.quantfinlib.regreporting.cat;

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
 * {@code symbol}, {@code eventTimestamp}, {@code manualFlag} (default {@code false}).
 * Per type, the names in {@link CatEventType#requiredFields()} (the 'R' fields of
 * Tables 16, 18, 48 and 52 of the v4.1.0 r14 specification). Anything else in the specification (FDID customer
 * records, representative orders, aggregated orders, options legs, quote events, CAIS)
 * is not populated; use {@link #field(String, String)} to add it by name.</p>
 *
 * <p>Timestamps are written in US Eastern time as {@code yyyyMMdd HHmmss.SSSSSSSSS}, the
 * STRING form of Table 3 (Timestamp) in the v4.1.0 r14 specification; the event must carry its time in
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
    private final StringBuilder legs = new StringBuilder();
    private int legCount;

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
     * Starts a new-quote (MENQ) event (section 4.10.1, Table 39).
     *
     * @return the builder
     * @since 3.1.0
     */
    public static CatEventBuilder newQuote() {
        return new CatEventBuilder(CatEventType.MENQ);
    }

    /**
     * Starts a new option order (MONO) event (section 5.1.1, Table 62). Set the OSI symbol
     * with {@link #optionId(String)}; {@code symbol} stays blank.
     *
     * @return the builder
     * @since 3.1.0
     */
    public static CatEventBuilder newOptionOrder() {
        return new CatEventBuilder(CatEventType.MONO);
    }

    /**
     * Starts an option order route (MOOR) event (section 5.1.3, Table 64).
     *
     * @return the builder
     * @since 3.1.0
     */
    public static CatEventBuilder optionRoute() {
        return new CatEventBuilder(CatEventType.MOOR);
    }

    /**
     * Starts a new option quote (MONQ) event (section 5.1.10.1, Table 85).
     *
     * @return the builder
     * @since 3.1.0
     */
    public static CatEventBuilder newOptionQuote() {
        return new CatEventBuilder(CatEventType.MONQ);
    }

    /**
     * Starts a new order supplement (MENOS) event (section 4.2, Table 17).
     *
     * @return the builder
     * @since 3.1.0
     */
    public static CatEventBuilder newOrderSupplement() {
        return new CatEventBuilder(CatEventType.MENOS);
    }

    /**
     * Starts an option order supplement (MONOS) event (section 5.1.2, Table 63).
     *
     * @return the builder
     * @since 3.1.0
     */
    public static CatEventBuilder optionOrderSupplement() {
        return new CatEventBuilder(CatEventType.MONOS);
    }

    /**
     * Starts an option order accepted (MOOA) event (section 5.1.4, Table 70).
     *
     * @return the builder
     * @since 3.1.0
     */
    public static CatEventBuilder optionAccepted() {
        return new CatEventBuilder(CatEventType.MOOA);
    }

    /**
     * Starts an option order modified (MOOM) event (section 5.1.7, Table 79). For a new Order Key, set priorOrderKeyDate and priorOrderID (seq 9-10) with {@link #field(String, String)}.
     *
     * @return the builder
     * @since 3.1.0
     */
    public static CatEventBuilder optionModified() {
        return new CatEventBuilder(CatEventType.MOOM);
    }

    /**
     * Starts an option order cancelled (MOOC) event (section 5.1.9, Table 83).
     *
     * @return the builder
     * @since 3.1.0
     */
    public static CatEventBuilder optionCancelled() {
        return new CatEventBuilder(CatEventType.MOOC);
    }

    /**
     * Starts an option trade (MOOT) event (section 5.1.11, Table 90).
     *
     * @return the builder
     * @since 3.1.0
     */
    public static CatEventBuilder optionTrade() {
        return new CatEventBuilder(CatEventType.MOOT);
    }

    /**
     * Starts an option order fulfillment (MOOF) event (section 5.1.12.1, Table 92).
     *
     * @return the builder
     * @since 3.1.0
     */
    public static CatEventBuilder optionFulfillment() {
        return new CatEventBuilder(CatEventType.MOOF);
    }

    /**
     * Starts a multi-leg new order (MLNO) event (section 5.2.1, Table 100). Add legs with {@link #optionLeg} and {@link #equityLeg}.
     *
     * @return the builder
     * @since 3.1.0
     */
    public static CatEventBuilder multiLegNewOrder() {
        return new CatEventBuilder(CatEventType.MLNO);
    }

    /**
     * Starts a multi-leg order route (MLOR) event (section 5.2.2, Table 102). Legs are restated (Table 103).
     *
     * @return the builder
     * @since 3.1.0
     */
    public static CatEventBuilder multiLegRoute() {
        return new CatEventBuilder(CatEventType.MLOR);
    }

    /**
     * Starts a multi-leg order cancelled (MLOC) event (section 5.2.7, Table 126).
     *
     * @return the builder
     * @since 3.1.0
     */
    public static CatEventBuilder multiLegCancelled() {
        return new CatEventBuilder(CatEventType.MLOC);
    }

    /**
     * Starts a multi-leg order supplement (MLOS) event (section 5.2.9, Table 139).
     *
     * @return the builder
     * @since 3.1.0
     */
    public static CatEventBuilder multiLegSupplement() {
        return new CatEventBuilder(CatEventType.MLOS);
    }

    /**
     * Adds an option leg to legDetails (Table 101 seq 32.n.1 to 32.n.6). The builder writes
     * legDetails as a JSON array and, if numberOfLegs is still blank, sets it to the number of
     * legs added.
     *
     * @param osi the leg's 21-character OSI symbol (32.n.3)
     * @param side B or S (32.n.5)
     * @param ratio legRatioQuantity (32.n.6)
     * @param openClose openCloseIndicator (32.n.4), or an empty string when not required
     * @return this
     * @since 3.1.0
     */
    public CatEventBuilder optionLeg(String osi, String side, BigDecimal ratio, String openClose) {
        legs.append(legCount++ == 0 ? "" : ",").append("{\"optionID\":").append(jsonString(osi));
        if (!openClose.isEmpty()) {
            legs.append(",\"openCloseIndicator\":").append(jsonString(openClose));
        }
        legs.append(",\"side\":").append(jsonString(side)).append(",\"legRatioQuantity\":")
                .append(ratio.stripTrailingZeros().toPlainString()).append('}');
        return this;
    }

    /**
     * Adds an equity leg to legDetails (Table 101 seq 32.n.2, 32.n.5, 32.n.6); see
     * {@link #optionLeg}.
     *
     * @param symbol the stock symbol (32.n.2)
     * @param side B, SL, SS or SX (32.n.5)
     * @param ratio legRatioQuantity (32.n.6)
     * @return this
     * @since 3.1.0
     */
    public CatEventBuilder equityLeg(String symbol, String side, BigDecimal ratio) {
        legs.append(legCount++ == 0 ? "" : ",").append("{\"symbol\":").append(jsonString(symbol))
                .append(",\"side\":").append(jsonString(side)).append(",\"legRatioQuantity\":")
                .append(ratio.stripTrailingZeros().toPlainString()).append('}');
        return this;
    }

    private static String jsonString(String v) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.append('"').toString();
    }

    /**
     * Sets the option's OSI symbol (field optionID, seq 8 of Tables 62, 64 and 85).
     *
     * @param osi the 21-character OSI symbol
     * @return this
     * @since 3.1.0
     */
    public CatEventBuilder optionId(String osi) {
        return field("optionID", osi);
    }

    /**
     * Sets the quote ID and its key date (MENQ and MONQ, Table 39 seq 6-7, Table 85 seq 6-7).
     *
     * @param quoteId firm-assigned quote ID
     * @param quoteKeyTime when the ID was assigned
     * @return this
     * @since 3.1.0
     */
    public CatEventBuilder quoteKey(String quoteId, Instant quoteKeyTime) {
        return field("quoteID", quoteId).field("quoteKeyDate", TS.format(quoteKeyTime));
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
     * Sets the trade ID and its key date (MEOT, Table 48 seq 6 and 7).
     *
     * @param tradeId firm-assigned trade ID
     * @param tradeKeyTime when the ID was assigned
     * @return this
     */
    public CatEventBuilder tradeKey(String tradeId, Instant tradeKeyTime) {
        return field("tradeID", tradeId).field("tradeKeyDate", TS.format(tradeKeyTime));
    }

    /**
     * Sets the fulfillment ID and its key date (MEOF, Table 52 seq 6 and 7).
     *
     * @param fulfillmentId firm-assigned fulfillment ID
     * @param fillKeyTime when the ID was assigned
     * @return this
     */
    public CatEventBuilder fillKey(String fulfillmentId, Instant fillKeyTime) {
        return field("fulfillmentID", fulfillmentId).field("fillKeyDate", TS.format(fillKeyTime));
    }

    /**
     * Sets the event timestamp (Eastern time, nanoseconds).
     *
     * @param t time
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
        if (legCount > 0) {
            Map<String, String> out = new LinkedHashMap<>(f);
            out.put("legDetails", "[" + legs + "]");
            if (out.getOrDefault("numberOfLegs", "").isEmpty()) {
                out.put("numberOfLegs", Integer.toString(legCount));
            }
            return new CatEvent(out);
        }
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
