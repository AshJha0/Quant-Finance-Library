package com.quantfinlib.reporting;

/**
 * How a price in field 33 or 51 is expressed. RTS 22 Annex I, Table 2, field 33 allows a
 * monetary value, a percentage, a yield or basis points, and the auth.016 XSD choice
 * {@code SecuritiesTransactionPrice2Choice} has the elements {@code MntryVal}, {@code Pctg},
 * {@code Yld} and {@code BsisPts} (published reference, ESMA65-8-2598 Annex 2, XSD 1.1.0).
 * The library stores the non-monetary forms in the field as a prefixed value, the same way
 * it stores {@code CCPT:} and {@code NIDN:} national identifiers: {@code PCTG:99.25},
 * {@code YLD:4.1}, {@code BSPS:35}. A bare decimal is a monetary value; {@code PNDG} and
 * {@code NOAP} mean no price.
 *
 * @since 3.0.0
 */
public enum PriceNotation {
    /** Monetary value in the currency of field 34 or 52 (a bare decimal). */
    MONETARY(""),
    /** Percentage, written {@code PCTG:<decimal>}. */
    PERCENTAGE("PCTG:"),
    /** Yield, written {@code YLD:<decimal>}. */
    YIELD("YLD:"),
    /** Basis points, written {@code BSPS:<decimal>}. */
    BASIS_POINTS("BSPS:"),
    /** {@code PNDG} or {@code NOAP}: no price available or not applicable. */
    NO_PRICE("");

    private final String prefix;

    PriceNotation(String prefix) {
        this.prefix = prefix;
    }

    /**
     * The prefix written before the number, empty for {@link #MONETARY} and {@link #NO_PRICE}.
     *
     * @return the prefix
     */
    public String prefix() {
        return prefix;
    }

    /**
     * The field value for a number in this notation.
     *
     * @param number a plain decimal
     * @return the value to store in field 33 or 51
     * @throws IllegalArgumentException for {@link #NO_PRICE}
     */
    public String format(String number) {
        if (this == NO_PRICE) {
            throw new IllegalArgumentException("NO_PRICE has no number; use PNDG or NOAP");
        }
        return prefix + java.util.Objects.requireNonNull(number, "number");
    }

    /**
     * The notation of a field value. An empty value is {@link #MONETARY}.
     *
     * @param value the field 33 or 51 value
     * @return its notation
     */
    public static PriceNotation of(String value) {
        if ("PNDG".equals(value) || "NOAP".equals(value)) {
            return NO_PRICE;
        }
        for (PriceNotation n : new PriceNotation[] {PERCENTAGE, YIELD, BASIS_POINTS}) {
            if (value.startsWith(n.prefix)) {
                return n;
            }
        }
        return MONETARY;
    }

    /**
     * The number in a field value, without the prefix ({@code PNDG}/{@code NOAP} returned as is).
     *
     * @param value the field value
     * @return the number part
     */
    public static String number(String value) {
        return value.substring(of(value).prefix.length());
    }
}
