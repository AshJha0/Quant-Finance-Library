package com.quantfinlib.fix;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Primitive header parsers used by {@link FixSession}: sequence numbers and
 * UTCTimestamp values. Package-private; split out of the session in 3.1 with
 * no change in behaviour.
 */
final class FixSessionParsing {

    /** Returned by {@link #parseUtcMillis} for a malformed timestamp. */
    static final long BAD_TIME = Long.MIN_VALUE;

    private FixSessionParsing() {
    }

    /** Non-negative integer, or -1 when absent or malformed. */
    static long parsePositive(String s) {
        if (s == null || s.isEmpty() || s.length() > 18) {
            return -1;
        }
        long v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            v = v * 10 + (c - '0');
        }
        return v;
    }

    /**
     * UTCTimestamp {@code YYYYMMDD-HH:MM:SS[.sss...]} (FIX 4.4 Vol 1) to epoch
     * milliseconds; fractions beyond milliseconds are truncated. Returns
     * {@link #BAD_TIME} when malformed.
     */
    static long parseUtcMillis(String s) {
        if (s == null || s.length() < 17 || s.charAt(8) != '-' || s.charAt(11) != ':'
                || s.charAt(14) != ':') {
            return BAD_TIME;
        }
        try {
            int year = digits(s, 0, 4);
            int month = digits(s, 4, 6);
            int day = digits(s, 6, 8);
            int hour = digits(s, 9, 11);
            int minute = digits(s, 12, 14);
            int second = digits(s, 15, 17);
            int millis = 0;
            if (s.length() > 17) {
                if (s.charAt(17) != '.' || s.length() == 18 || s.length() > 27) {
                    return BAD_TIME;
                }
                int fracEnd = Math.min(s.length(), 21);
                digits(s, 18, s.length());   // validates every fraction digit
                millis = digits(s, 18, fracEnd);
                for (int i = fracEnd - 18; i < 3; i++) {
                    millis *= 10;
                }
            }
            if (second == 60) {
                second = 59;   // leap second: FIX allows 60
            }
            return LocalDateTime.of(year, month, day, hour, minute, second)
                    .toEpochSecond(ZoneOffset.UTC) * 1000L + millis;
        } catch (NumberFormatException | DateTimeException e) {
            return BAD_TIME;
        }
    }

    private static int digits(String s, int from, int to) {
        int v = 0;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                throw new NumberFormatException(s);
            }
            if (to - from <= 9) {
                v = v * 10 + (c - '0');
            }
        }
        return v;
    }
}
