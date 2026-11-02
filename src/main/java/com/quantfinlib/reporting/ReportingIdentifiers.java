package com.quantfinlib.reporting;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Syntax and check-digit validation for the identifiers a transaction report
 * carries, and UTC timestamp formatting at a chosen granularity.
 *
 * <p>LEI, plain idea: 20 characters whose last two are check digits chosen so
 * that the whole code, read as a number with letters replaced by 10..35, leaves
 * remainder 1 when divided by 97. Formal statement: ISO 17442 uses ISO 7064
 * MOD 97-10, the same scheme as the IBAN (published reference: ISO 17442-1:2020,
 * section 4). Characters 1-18 are upper-case alphanumeric, 19-20 are digits.
 * Worked example: for base {@code 5493001KJTIIGC8Y1R} the check digits are
 * {@code 98 - (base*100 mod 97)}; {@link #leiCheckDigits} computes them and the
 * test cross-checks with {@link java.math.BigInteger} (independent computation).
 *
 * <p>ISIN: 2 letters (country), 9 alphanumerics, 1 check digit. Letters become
 * two digits (A=10 .. Z=35) and the Luhn algorithm is applied to the resulting
 * digit string (published reference: ISO 6166; example US0378331005 is valid).
 *
 * <p>MIC: 4 upper-case alphanumerics (ISO 10383). Only the shape is checked; whether
 * the code is live in the ISO 10383 registry needs the registry file, which is not
 * bundled. {@code XOFF} and {@code XXXX} are the RTS 22 values for off-venue
 * trading and are accepted.
 */
public final class ReportingIdentifiers {
    private ReportingIdentifiers() {
    }

    /** Timestamp precision. RTS 25 sets the minimum per trading activity; the firm picks. */
    public enum Granularity {
        /** Whole seconds. */
        SECONDS(0, ChronoUnit.SECONDS),
        /** Milliseconds (RTS 25 minimum for most non-HFT electronic trading). */
        MILLIS(3, ChronoUnit.MILLIS),
        /** Microseconds (RTS 25 minimum for high-frequency algorithmic trading). */
        MICROS(6, ChronoUnit.MICROS);

        private final DateTimeFormatter fmt;
        private final ChronoUnit unit;

        Granularity(int digits, ChronoUnit unit) {
            this.unit = unit;
            String frac = digits == 0 ? "" : "." + "S".repeat(digits);
            this.fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss" + frac + "'Z'")
                    .withZone(ZoneOffset.UTC);
        }
    }

    /** Formats {@code t} in UTC as {@code YYYY-MM-DDThh:mm:ss[.f..]Z}, truncating to the granularity. */
    public static String formatTimestamp(Instant t, Granularity g) {
        return g.fmt.format(t.truncatedTo(g.unit));
    }

    /** True for a syntactically valid LEI with correct ISO 7064 MOD 97-10 check digits. */
    public static boolean isValidLei(String lei) {
        if (lei == null || lei.length() != 20) {
            return false;
        }
        for (int i = 0; i < 18; i++) {
            if (!isUpperAlnum(lei.charAt(i))) {
                return false;
            }
        }
        if (!Character.isDigit(lei.charAt(18)) || !Character.isDigit(lei.charAt(19))) {
            return false;
        }
        return mod97(lei) == 1;
    }

    /** The two check digits for an 18-character LEI base. */
    public static String leiCheckDigits(String base18) {
        if (base18 == null || base18.length() != 18) {
            throw new IllegalArgumentException("LEI base must be 18 characters");
        }
        for (int i = 0; i < 18; i++) {
            if (!isUpperAlnum(base18.charAt(i))) {
                throw new IllegalArgumentException("LEI base must be upper-case alphanumeric");
            }
        }
        int check = 98 - mod97(base18 + "00");
        return check < 10 ? "0" + check : Integer.toString(check);
    }

    private static int mod97(String s) {
        int r = 0;
        for (int i = 0; i < s.length(); i++) {
            int v = Character.digit(s.charAt(i), 36);
            r = v >= 10 ? (r * 100 + v) % 97 : (r * 10 + v) % 97;
        }
        return r;
    }

    /** True for a syntactically valid ISIN with a correct Luhn check digit. */
    public static boolean isValidIsin(String isin) {
        if (isin == null || isin.length() != 12) {
            return false;
        }
        if (!Character.isUpperCase(isin.charAt(0)) || !Character.isUpperCase(isin.charAt(1))
                || !isUpperAlnum(isin.charAt(0)) || !isUpperAlnum(isin.charAt(1))) {
            return false;
        }
        for (int i = 2; i < 11; i++) {
            if (!isUpperAlnum(isin.charAt(i))) {
                return false;
            }
        }
        if (!Character.isDigit(isin.charAt(11))) {
            return false;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            digits.append(Character.digit(isin.charAt(i), 36));
        }
        return luhnCheckDigit(digits) == isin.charAt(11) - '0';
    }

    private static int luhnCheckDigit(CharSequence payload) {
        int sum = 0;
        boolean dbl = true; // rightmost payload digit is doubled
        for (int i = payload.length() - 1; i >= 0; i--) {
            int d = payload.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return (10 - sum % 10) % 10;
    }

    /** True for a 4-character upper-case alphanumeric MIC (shape only). */
    public static boolean isValidMic(String mic) {
        if (mic == null || mic.length() != 4) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if (!isUpperAlnum(mic.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** True for three upper-case ASCII letters (ISO 4217 shape; the code list is not checked). */
    public static boolean isCurrencyShape(String ccy) {
        return ccy != null && ccy.matches("[A-Z]{3}");
    }

    /** True for two upper-case ASCII letters (ISO 3166-1 alpha-2 shape). */
    public static boolean isCountryShape(String cc) {
        return cc != null && cc.matches("[A-Z]{2}");
    }

    private static boolean isUpperAlnum(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
