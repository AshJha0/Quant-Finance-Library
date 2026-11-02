package com.quantfinlib.regreporting.registry;

import java.io.BufferedReader;
import java.io.IOException;
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
        return i < row.size() ? row.get(i).trim() : "";
    }
}
