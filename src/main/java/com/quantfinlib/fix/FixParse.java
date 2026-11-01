package com.quantfinlib.fix;

/**
 * Shared primitive parsers for the garbage-free FIX flyweights
 * ({@link FixExecReportView}, {@link FixMarketDataView}) — the
 * fraction-rejection and scaled-long price rules are load-bearing for the
 * "feed-to-order never touches a double" invariant, so they exist exactly
 * once. Package-private, static, zero allocation.
 */
final class FixParse {

    private FixParse() {
    }

    /**
     * Non-negative integer field (quantities, counts). Tolerates a zero
     * fraction ("100.0"); a real fraction, a '-' sign, any other non-digit,
     * an empty value or more than 18 digits fails loudly: such values are
     * protocol violations that must never parse into silent garbage.
     */
    static long parseLong(byte[] buf, int from, int to) {
        if (from >= to) {
            throw new IllegalArgumentException("empty integer field");
        }
        long v = 0;
        for (int i = from; i < to; i++) {
            byte b = buf[i];
            if (b == '-') {
                throw new IllegalArgumentException("negative quantity field");
            }
            if (b != '.' && (b < '0' || b > '9')) {
                throw new IllegalArgumentException("non-digit 0x" + Integer.toHexString(b & 0xFF)
                        + " in integer field");
            }
            if (i - from >= 18 && b != '.') {
                throw new IllegalArgumentException("integer field longer than 18 digits");
            }
            if (b == '.') {
                for (int j = i + 1; j < to; j++) {
                    if (buf[j] != '0') {
                        throw new IllegalArgumentException(
                                "fractional quantity not representable as long");
                    }
                }
                return v;
            }
            v = v * 10 + (b - '0');
        }
        return v;
    }

    /**
     * Numeric field that may legitimately be non-numeric on the wire —
     * e.g. ClOrdID (tag 11), which is only numeric by OUR encoder's
     * convention; unsolicited venue messages carry counterparty formats
     * ("ORD-2024-17", UUIDs). Returns {@code fallback} on any non-digit
     * instead of throwing: a foreign id must never kill the message pump.
     */
    static long parseLongOrElse(byte[] buf, int from, int to, long fallback) {
        long v = 0;
        for (int i = from; i < to; i++) {
            byte b = buf[i];
            if (b < '0' || b > '9') {
                return fallback;
            }
            v = v * 10 + (b - '0');
        }
        return from == to ? fallback : v;
    }

    /**
     * Price mantissa as a signed scaled long: "1.08505" → 108505,
     * "-0.5" → -5 (with {@link #priceDecimals} = 1). Negative prices are
     * real in FX (forward points, negative rates) and must round-trip.
     */
    static long priceMantissa(byte[] buf, int from, int to) {
        boolean negative = from < to && buf[from] == '-';
        long mantissa = 0;
        int digits = 0;
        boolean dot = false;
        for (int i = negative ? from + 1 : from; i < to; i++) {
            byte b = buf[i];
            if (b == '.') {
                if (dot) {
                    throw new IllegalArgumentException("two decimal points in price field");
                }
                dot = true;
            } else if (b < '0' || b > '9') {
                throw new IllegalArgumentException("non-digit 0x" + Integer.toHexString(b & 0xFF)
                        + " in price field");
            } else {
                digits++;
                if (mantissa > (Long.MAX_VALUE - 9) / 10) {
                    throw new IllegalArgumentException("price mantissa overflows a long");
                }
                mantissa = mantissa * 10 + (b - '0');
            }
        }
        if (digits == 0) {
            throw new IllegalArgumentException("price field has no digits");
        }
        return negative ? -mantissa : mantissa;
    }

    /** Digits after the decimal point: "1.08505" → 5; "99" → 0. */
    static int priceDecimals(byte[] buf, int from, int to) {
        for (int i = from; i < to; i++) {
            if (buf[i] == '.') {
                return to - i - 1;
            }
        }
        return 0;
    }

    /** 10^-n for n in 0..18: scaled-long → double conversion without Math.pow. */
    static final double[] NEG_POW10 = {
            1, 1e-1, 1e-2, 1e-3, 1e-4, 1e-5, 1e-6, 1e-7, 1e-8, 1e-9,
            1e-10, 1e-11, 1e-12, 1e-13, 1e-14, 1e-15, 1e-16, 1e-17, 1e-18
    };
}
