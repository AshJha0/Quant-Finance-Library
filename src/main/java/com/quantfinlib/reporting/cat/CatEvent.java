package com.quantfinlib.reporting.cat;

import com.quantfinlib.reporting.TransactionReport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One CAT event, as built by {@link CatEventBuilder}. Regime {@code "CAT"}; the reference is
 * the {@code firmROEID}. Not certified against the CAT specification (see the package
 * javadoc).
 */
public final class CatEvent implements TransactionReport {
    /** Fields present on every event. */
    public static final List<String> COMMON_FIELDS = List.of("actionType", "errorROEID", "firmROEID",
            "type", "CATReporterIMID", "symbol", "eventTimestamp", "manualFlag", "orderKeyDate", "orderID");

    private static final Set<String> SIDES = Set.of("B", "SL", "SS", "SX");
    private static final Set<String> ACTIONS = Set.of("NEW", "COR", "DEL", "RPR");

    private final Map<String, String> fields;

    CatEvent(Map<String, String> fields) {
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    @Override
    public String regime() {
        return "CAT";
    }

    @Override
    public String reference() {
        return fields.getOrDefault("firmROEID", "");
    }

    @Override
    public Map<String, String> fields() {
        return fields;
    }

    /**
     * The event type.
     *
     * @return the type
     */
    public CatEventType type() {
        return CatEventType.valueOf(fields.get("type"));
    }

    @Override
    public List<String> validate() {
        List<String> out = new ArrayList<>();
        CatEventType t = type();
        for (String k : COMMON_FIELDS) {
            if (!"errorROEID".equals(k) && blank(k) && !("orderID".equals(k) && t == CatEventType.MEOT)
                    && !("orderKeyDate".equals(k) && t == CatEventType.MEOT)) {
                out.add("missing " + k);
            }
        }
        for (String k : t.requiredFields()) {
            if (blank(k)) {
                out.add("missing " + k);
            }
        }
        String a = fields.getOrDefault("actionType", "");
        if (!a.isEmpty() && !ACTIONS.contains(a)) {
            out.add("actionType must be one of " + ACTIONS);
        }
        if (("COR".equals(a) || "DEL".equals(a) || "RPR".equals(a)) && blank("errorROEID")) {
            // RPR repairs a rejected record; COR/DEL amend an accepted one. Both reference it.
            if (!"DEL".equals(a)) {
                out.add("errorROEID required for actionType " + a);
            }
        }
        String side = fields.getOrDefault("side", "");
        if (!side.isEmpty() && !SIDES.contains(side)) {
            out.add("side must be one of " + SIDES);
        }
        String imid = fields.getOrDefault("CATReporterIMID", "");
        if (!imid.isEmpty() && !imid.matches("[A-Z0-9]{1,7}")) {
            out.add("CATReporterIMID must be 1-7 upper-case alphanumerics");
        }
        positive("quantity", out);
        String px = fields.getOrDefault("price", "");
        if (!px.isEmpty() && !px.matches("-?\\d+(\\.\\d{1,8})?")) {
            out.add("price must be a decimal with at most 8 places");
        }
        return out;
    }

    private void positive(String k, List<String> out) {
        String v = fields.getOrDefault(k, "");
        if (!v.isEmpty() && !v.matches("\\d+(\\.\\d{1,6})?")) {
            out.add(k + " must be a non-negative decimal");
        } else if (!v.isEmpty() && Double.parseDouble(v) <= 0) {
            out.add(k + " must be positive");
        }
    }

    private boolean blank(String k) {
        String v = fields.get(k);
        return v == null || v.isBlank();
    }

    /**
     * Serialises the event as a single JSON object with string values (booleans and
     * numbers are left as strings; the CAT schema types some of them as numbers).
     *
     * @return JSON text
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            quote(sb, e.getKey()).append(':');
            quote(sb, e.getValue());
        }
        return sb.append('}').toString();
    }

    private static StringBuilder quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"');
    }
}
