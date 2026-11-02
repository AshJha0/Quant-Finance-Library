package com.quantfinlib.regreporting.cat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small strict JSON reader (RFC 8259) for CAT records and the CAT schema file. Objects
 * become {@code Map<String, Object>} in source order, arrays {@code List<Object>}, strings
 * {@link String}, {@code true}/{@code false} {@link Boolean}, {@code null} {@link #NULL},
 * and numbers {@link Num}, which keeps the literal text so that precision and scale can be
 * checked exactly (Table 3, Numeric). No dependency; package-private.
 */
final class CatJson {
    /** The JSON {@code null} literal. */
    static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    /**
     * A JSON number, kept as written.
     *
     * @param text the literal
     */
    record Num(String text) {
        @Override
        public String toString() {
            return text;
        }
    }

    /** Thrown on malformed input, with the character offset. */
    static final class ParseException extends Exception {
        private static final long serialVersionUID = 1L;

        ParseException(String message, int at) {
            super(message + " at offset " + at);
        }
    }

    private final String s;
    private int i;

    private CatJson(String s) {
        this.s = s;
    }

    /**
     * Parses one JSON value that must span the whole text (whitespace allowed around it).
     *
     * @param text the JSON text
     * @return the value
     * @throws ParseException when the text is not one well-formed JSON value
     */
    static Object parse(String text) throws ParseException {
        CatJson p = new CatJson(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != text.length()) {
            throw new ParseException("trailing characters", p.i);
        }
        return v;
    }

    private Object value(int depth) throws ParseException {
        if (depth > 64) {
            throw new ParseException("nesting deeper than 64", i);
        }
        if (i >= s.length()) {
            throw new ParseException("unexpected end", i);
        }
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", NULL);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield number();
                }
                throw new ParseException("unexpected character '" + c + "'", i);
            }
        };
    }

    private Map<String, Object> object(int depth) throws ParseException {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') {
            i++;
            return Collections.unmodifiableMap(m);
        }
        while (true) {
            ws();
            if (peek() != '"') {
                throw new ParseException("expected a string key", i);
            }
            String k = string();
            ws();
            expect(':');
            ws();
            Object v = value(depth + 1);
            if (m.put(k, v) != null) {
                throw new ParseException("duplicate key \"" + k + "\"", i);
            }
            ws();
            char c = next();
            if (c == '}') {
                return Collections.unmodifiableMap(m);
            }
            if (c != ',') {
                throw new ParseException("expected ',' or '}'", i - 1);
            }
        }
    }

    private List<Object> array(int depth) throws ParseException {
        List<Object> a = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') {
            i++;
            return Collections.unmodifiableList(a);
        }
        while (true) {
            ws();
            a.add(value(depth + 1));
            ws();
            char c = next();
            if (c == ']') {
                return Collections.unmodifiableList(a);
            }
            if (c != ',') {
                throw new ParseException("expected ',' or ']'", i - 1);
            }
        }
    }

    private String string() throws ParseException {
        i++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= s.length()) {
                throw new ParseException("unterminated string", i);
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                throw new ParseException("control character in string", i - 1);
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) {
                        throw new ParseException("short \\u escape", i);
                    }
                    try {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw new ParseException("bad \\u escape", i);
                    }
                    i += 4;
                }
                default -> throw new ParseException("bad escape \\" + e, i - 1);
            }
        }
    }

    private Num number() throws ParseException {
        int start = i;
        if (peek() == '-') {
            i++;
        }
        if (peek() == '0') {
            i++;
        } else if (peek() >= '1' && peek() <= '9') {
            digits();
        } else {
            throw new ParseException("bad number", i);
        }
        if (peek() == '.') {
            i++;
            if (!(peek() >= '0' && peek() <= '9')) {
                throw new ParseException("digit expected after '.'", i);
            }
            digits();
        }
        if (peek() == 'e' || peek() == 'E') {
            i++;
            if (peek() == '+' || peek() == '-') {
                i++;
            }
            if (!(peek() >= '0' && peek() <= '9')) {
                throw new ParseException("digit expected in exponent", i);
            }
            digits();
        }
        return new Num(s.substring(start, i));
    }

    private void digits() {
        while (peek() >= '0' && peek() <= '9') {
            i++;
        }
    }

    private Object literal(String word, Object v) throws ParseException {
        if (!s.startsWith(word, i)) {
            throw new ParseException("bad literal", i);
        }
        i += word.length();
        return v;
    }

    private void expect(char c) throws ParseException {
        if (next() != c) {
            throw new ParseException("expected '" + c + "'", i - 1);
        }
    }

    private char next() throws ParseException {
        if (i >= s.length()) {
            throw new ParseException("unexpected end", i);
        }
        return s.charAt(i++);
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            i++;
        }
    }
}
