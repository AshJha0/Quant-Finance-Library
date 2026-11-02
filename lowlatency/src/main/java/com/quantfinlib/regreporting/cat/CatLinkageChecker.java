package com.quantfinlib.regreporting.cat;

import com.quantfinlib.regreporting.RuleProvenance;
import com.quantfinlib.regreporting.RuleProvenance.Evidence;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Checks CAT linkage keys across a batch of events from one Industry Member.
 *
 * <p><b>Plain idea.</b> CAT does not link events by time or by file order; it links them by
 * keys built from a few fields ("daisy chain"). A route must carry the same Order Key as the
 * new-order event it routes, and two new orders must never share one, or CAT rejects every
 * event with that key. A single event cannot see either problem; a batch can.</p>
 *
 * <p><b>Formal statement.</b> Keys are those of CAT Reporting Technical Specifications for
 * Industry Members v4.1.0 r14, section 2.6.1, Table 5, and the "Linkage Keys for this
 * Reportable Event" lists under each event table:</p>
 * <ul>
 *   <li>Order Key (MENO, MEOR, MONO, MOOR): orderKeyDate, CATReporterIMID, symbol (or
 *       optionID), orderID;</li>
 *   <li>Trade Key (MEOT): tradeKeyDate, CATReporterIMID, symbol, tradeID;</li>
 *   <li>Fulfillment Key (MEOF): fillKeyDate, CATReporterIMID, symbol (or optionID),
 *       fulfillmentID;</li>
 *   <li>Quote Key (MENQ) and Option Quote Key (MONQ): quoteKeyDate, CATReporterIMID, symbol
 *       or optionID, quoteID, RFQID (Table 39 and Table 85 linkage lists).</li>
 * </ul>
 * <p>The CATReporterIMID of an event is optional when it equals the file name's (Table 16
 * seq 5); the checker uses the file IMID passed in when an event leaves it blank. Only
 * events with actionType NEW are keyed: COR and RPR records re-use the key of the record
 * they replace, and DEL carries none. {@link #rules()} gives the citations.</p>
 *
 * <p><b>Worked example</b> (own test): a MENO with Order Key (20260302 093000.000000000,
 * FRMA, TEST, O-1) and a MEOR with the same key link; a MEOR with orderID O-2 is unlinked
 * (LK02); a second MENO with orderID O-1 makes both MENO records fail LK01.</p>
 *
 * <p>Batch-local by design: CAT links across files and days, so an unlinked route here may
 * be linked by an order reported in another file. Pass all of a day's events for a firm to
 * get a meaningful LK02.</p>
 *
 * @since 3.1.0
 */
public final class CatLinkageChecker {
    private static final String SPEC = "CAT IM Technical Specifications v4.1.0 r14, ";

    private static final Set<CatEventType> ORIGIN_ORDER = Set.of(CatEventType.MENO, CatEventType.MONO);
    private static final Set<CatEventType> ROUTES = Set.of(CatEventType.MEOR, CatEventType.MOOR);

    private static final List<RuleProvenance> RULES = List.of(
            new RuleProvenance("CAT-LK01", "no two NEW originating events (MENO, MONO, MEOT, MEOF, MENQ, MONQ) "
                    + "share an Order, Trade, Fulfillment or Quote Key; every event with a duplicated key is "
                    + "reported", SPEC + "section 2.6.1, Table 5 ('Duplication of Order Keys results in the "
                    + "rejection of all events with the same key'; same for Trade and Fulfillment Keys)",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-LK02", "every NEW route (MEOR, MOOR) has the Order Key of a NEW MENO or MONO "
                    + "in the batch", SPEC + "section 2.6.1, Table 5 (Order Key links an order event to a "
                    + "subsequent route), Table 18 and Table 64 linkage lists; batch-local scope is the "
                    + "library's reading", Evidence.LIBRARY_READING),
            new RuleProvenance("CAT-LK03", "every keyed field of a NEW event is present", SPEC
                    + "section 2.6, 'these data elements must create a Linkage Key that is unique'",
                    Evidence.PUBLISHED_REFERENCE));

    /**
     * One linkage problem.
     *
     * @param firmRoeId the firmROEID of the event concerned
     * @param ruleId CAT-LK01, CAT-LK02 or CAT-LK03
     * @param message what is wrong
     */
    public record Issue(String firmRoeId, String ruleId, String message) {
        /** Validates non-null components. */
        public Issue {
            Objects.requireNonNull(firmRoeId, "firmRoeId");
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(message, "message");
        }
    }

    private CatLinkageChecker() {
    }

    /**
     * Every linkage rule, with citation and evidence class.
     *
     * @return the rule catalogue (immutable)
     */
    public static List<RuleProvenance> rules() {
        return RULES;
    }

    /**
     * Checks a batch.
     *
     * @param events the events, in any order
     * @param fileImid the CATReporterIMID of the submission file, used when an event leaves it blank
     * @return the issues, empty when every key is unique and every route links
     */
    public static List<Issue> check(List<CatEvent> events, String fileImid) {
        Objects.requireNonNull(fileImid, "fileImid");
        List<Issue> out = new ArrayList<>();
        Map<String, List<CatEvent>> origins = new LinkedHashMap<>();
        List<CatEvent> routes = new ArrayList<>();
        for (CatEvent e : events) {
            Map<String, String> f = e.fields();
            if (!"NEW".equals(f.getOrDefault("actionType", ""))) {
                continue;
            }
            CatEventType t = e.type();
            List<String> parts = keyFields(t);
            List<String> missing = new ArrayList<>();
            StringBuilder key = new StringBuilder(kind(t)).append('|');
            for (String p : parts) {
                String v = value(f, p, t, fileImid);
                if (v.isEmpty() && !"RFQID".equals(p)) {
                    missing.add(p);
                }
                key.append(v).append('|');
            }
            if (!missing.isEmpty()) {
                out.add(new Issue(e.reference(), "CAT-LK03", "linkage key fields missing: " + missing));
                continue;
            }
            if (ROUTES.contains(t)) {
                routes.add(e);
            } else {
                origins.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(e);
            }
        }
        for (Map.Entry<String, List<CatEvent>> en : origins.entrySet()) {
            if (en.getValue().size() > 1) {
                for (CatEvent e : en.getValue()) {
                    out.add(new Issue(e.reference(), "CAT-LK01", "duplicate linkage key " + en.getKey()
                            + " on " + en.getValue().size() + " events"));
                }
            }
        }
        for (CatEvent r : routes) {
            CatEventType origin = r.type() == CatEventType.MOOR ? CatEventType.MONO : CatEventType.MENO;
            String key = orderKey(r, fileImid);
            boolean linked = false;
            for (List<CatEvent> group : origins.values()) {
                CatEvent o = group.get(0);
                if (o.type() == origin && orderKey(o, fileImid).equals(key)) {
                    linked = true;
                    break;
                }
            }
            if (!linked) {
                out.add(new Issue(r.reference(), "CAT-LK02", "route has no matching " + origin + " Order Key "
                        + key));
            }
        }
        return out;
    }

    private static String orderKey(CatEvent e, String fileImid) {
        StringBuilder sb = new StringBuilder();
        for (String p : keyFields(CatEventType.MENO)) {
            sb.append(value(e.fields(), p, e.type(), fileImid)).append('|');
        }
        return sb.toString();
    }

    private static String kind(CatEventType t) {
        return switch (t) {
            case MENO, MEOR, MONO, MOOR -> "ORDER";
            case MEOT -> "TRADE";
            case MEOF -> "FILL";
            case MENQ, MONQ -> "QUOTE";
        };
    }

    private static List<String> keyFields(CatEventType t) {
        return switch (t) {
            case MENO, MEOR, MONO, MOOR -> List.of("orderKeyDate", "CATReporterIMID", "symbol", "orderID");
            case MEOT -> List.of("tradeKeyDate", "CATReporterIMID", "symbol", "tradeID");
            case MEOF -> List.of("fillKeyDate", "CATReporterIMID", "symbol", "fulfillmentID");
            case MENQ, MONQ -> List.of("quoteKeyDate", "CATReporterIMID", "symbol", "quoteID", "RFQID");
        };
    }

    /** symbol means optionID on option events; a blank IMID falls back to the file's. */
    private static String value(Map<String, String> f, String name, CatEventType t, String fileImid) {
        if ("symbol".equals(name) && t.isOption()) {
            return f.getOrDefault("optionID", "");
        }
        String v = f.getOrDefault(name, "");
        return "CATReporterIMID".equals(name) && v.isEmpty() ? fileImid : v;
    }
}
