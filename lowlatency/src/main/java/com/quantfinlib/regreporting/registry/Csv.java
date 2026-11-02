package com.quantfinlib.regreporting.registry;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Minimal RFC 4180 reader (quoted fields, doubled quotes, embedded newlines). */
final class Csv {
    private Csv() {
    }

    /** Reads the next record, or null at end of input. */
    static @Nullable List<String> next(BufferedReader in) throws IOException {
        int c = in.read();
        if (c == -1) {
            return null;
        }
        List<String> out = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false;
        while (c != -1) {
            char ch = (char) c;
            if (quoted) {
                if (ch == '"') {
                    in.mark(1);
                    int n = in.read();
                    if (n == '"') {
                        f.append('"');
                    } else {
                        quoted = false;
                        if (n != -1) {
                            in.reset();
                        }
                    }
                } else {
                    f.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                out.add(f.toString());
                f.setLength(0);
            } else if (ch == '\n') {
                break;
            } else if (ch != '\r') {
                f.append(ch);
            }
            c = in.read();
        }
        out.add(f.toString());
        return out;
    }

    /** Maps header names (trimmed, upper-case, BOM stripped) to column index. */
    static Map<String, Integer> header(List<String> h) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < h.size(); i++) {
            String k = h.get(i).replace("﻿", "").trim().toUpperCase(Locale.ROOT);
            m.putIfAbsent(k, i);
        }
        return m;
    }

    static int require(Map<String, Integer> h, String name) throws IOException {
        Integer i = h.get(name.toUpperCase(Locale.ROOT));
        if (i == null) {
            throw new IOException("missing CSV column: " + name);
        }
        return i;
    }

    static String get(List<String> row, int i) {
        return i >= 0 && i < row.size() ? row.get(i).trim() : "";
    }

    /** Column index of an optional column, or -1 when the header lacks it. */
    static int optional(Map<String, Integer> h, String name) {
        return h.getOrDefault(name.toUpperCase(Locale.ROOT), -1);
    }

    /**
     * Parses a date written {@code yyyy-MM-dd}, {@code yyyyMMdd}, or an ISO date-time. A
     * date-time with a zone or offset is converted to its UTC date (the GLEIF golden copy
     * mixes {@code Z} and {@code +01:00} values; ESMA reference data is in UTC); one without
     * a zone keeps its own date. Empty gives null.
     */
    static @Nullable LocalDate date(String s) throws IOException {
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            if (t.length() == 8 && t.chars().allMatch(Character::isDigit)) {
                return LocalDate.parse(t, DateTimeFormatter.BASIC_ISO_DATE);
            }
            if (t.length() > 10) {
                try {
                    return OffsetDateTime.parse(t).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
                } catch (DateTimeParseException noZone) {
                    return LocalDate.parse(t.substring(0, 10));
                }
            }
            return LocalDate.parse(t);
        } catch (DateTimeParseException e) {
            throw new IOException("unparsable date '" + s + "'", e);
        }
    }
}
