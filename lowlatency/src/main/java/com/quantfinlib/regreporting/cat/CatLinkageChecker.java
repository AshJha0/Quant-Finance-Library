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
 * <p>{@link #check(List, String)} is batch-local: CAT links across files and days, so an
 * unlinked route there may be linked by an order reported in another file. Since 3.1.0
 * {@link #checkFiles(List)} takes several files (each with its own file-name IMID) and checks
 * every key across all of them, which is the scope CAT itself uses; pass the earlier days'
 * files too when orders rest overnight.</p>
 *
 * <p>Since 3.1.0 the option lifecycle, supplement and multi-leg types take part too:</p>
 * <ul>
 *   <li>MOOA, MLNO, MOOT and MOOF assign keys like MONO, MENO, MEOT and MEOF (Tables 70, 100,
 *       90 and 92 linkage lists; the multi-leg Order Key has no symbol, Table 100);</li>
 *   <li>MOOR links to a MONO, MOOA or key-assigning MOOM; MLOR links to an MLNO (CAT-LK02);</li>
 *   <li>a supplement links to its parent: MENOS to MENO, MONOS to MONO or MOOA, MLOS to MLNO
 *       (CAT-LK04, Tables 17, 63 and 139);</li>
 *   <li>MOOM, MOOC and MLOC need the order they act on (CAT-LK05). A MOOM that carries
 *       priorOrderKeyDate and priorOrderID assigns a new Order Key and links through the prior
 *       one (Table 79 seq 9-10).</li>
 * </ul>
 * <p>For the original types the results of {@link #check(List, String)} are unchanged: the
 * new rules only fire on events of the new types.</p>
 *
 * @since 3.1.0
 */
public final class CatLinkageChecker {
    private static final String SPEC = "CAT IM Technical Specifications v4.1.0 r14, ";

    private static final Set<CatEventType> ORIGINS = Set.of(CatEventType.MENO, CatEventType.MONO,
            CatEventType.MOOA, CatEventType.MLNO, CatEventType.MEOT, CatEventType.MOOT, CatEventType.MEOF,
            CatEventType.MOOF, CatEventType.MENQ, CatEventType.MONQ);
    private static final Set<CatEventType> OPTION_ORDER_PARENTS = Set.of(CatEventType.MONO, CatEventType.MOOA,
            CatEventType.MOOM);

    private static final List<RuleProvenance> RULES = List.of(
            new RuleProvenance("CAT-LK01", "no two NEW originating events (MENO, MONO, MEOT, MEOF, MENQ, MONQ; "
                    + "since 3.1.0 also MOOA, MLNO, MOOT, MOOF and a key-assigning MOOM) share an Order, Trade, "
                    + "Fulfillment or Quote Key; every event with a duplicated key is reported", SPEC + "section "
                    + "2.6.1, Table 5 ('Duplication of Order Keys results in the rejection of all events with the "
                    + "same key'; same for Trade and Fulfillment Keys)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-LK02", "every NEW route (MEOR, MOOR; since 3.1.0 MLOR) has the Order Key of a "
                    + "NEW MENO, MONO (or MOOA, key-assigning MOOM) or MLNO in the batch", SPEC + "section 2.6.1, "
                    + "Table 5 (Order Key links an order event to a subsequent route), Table 18, Table 64 and Table "
                    + "102 linkage lists; batch-local scope of check() is the library's reading",
                    Evidence.LIBRARY_READING),
            new RuleProvenance("CAT-LK03", "every keyed field of a NEW event is present", SPEC
                    + "section 2.6, 'these data elements must create a Linkage Key that is unique'",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-LK04", "every NEW supplement has the Order Key of its parent: MENOS of a MENO, "
                    + "MONOS of a MONO or MOOA, MLOS of an MLNO", SPEC + "section 4.2 and Table 17, section 5.1.2 "
                    + "and Table 63, section 5.2.9 and Table 139 (supplement 'to the New Order event'; Order Key "
                    + "in each linkage list)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-LK05", "every NEW MOOM, MOOC and MLOC has the Order Key of the order it "
                    + "modifies or cancels; a MOOM with priorOrderKeyDate and priorOrderID links through the prior "
                    + "key and assigns its own", SPEC + "Table 79 seq 9-10 and linkage list, Table 83 and Table 126 "
                    + "linkage lists", Evidence.PUBLISHED_REFERENCE));

    /**
     * One linkage problem.
     *
     * @param firmRoeId the firmROEID of the event concerned
     * @param ruleId CAT-LK01 to CAT-LK05
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

    /**
     * One submitted data file: its name, the CATReporterIMID of its file name (used when an
     * event leaves CATReporterIMID blank, Table 16 seq 5) and its events.
     *
     * @param fileName the file name, used only to say where an issue is
     * @param fileImid the file's CATReporterIMID
     * @param events the file's events, in any order
     * @since 3.1.0
     */
    public record Batch(String fileName, String fileImid, List<CatEvent> events) {
        /** Validates non-null components and copies the list. */
        public Batch {
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(fileImid, "fileImid");
            events = List.copyOf(events);
        }
    }

    /**
     * A linkage problem with the file of the event concerned.
     *
     * @param fileName the {@link Batch#fileName()} of the event
     * @param issue the problem
     * @since 3.1.0
     */
    public record FileIssue(String fileName, Issue issue) {
        /** Validates non-null components. */
        public FileIssue {
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(issue, "issue");
        }
    }

    private record Entry(CatEvent event, String fileImid, String fileName) {
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
        List<Entry> in = new ArrayList<>();
        for (CatEvent e : events) {
            in.add(new Entry(e, fileImid, ""));
        }
        List<Issue> out = new ArrayList<>();
        for (FileIssue fi : run(in)) {
            out.add(fi.issue());
        }
        return out;
    }

    /**
     * Checks several files together, so that a route, supplement, modification or
     * cancellation in one file links to the order reported in another, and a key used in two
     * files is a duplicate (section 2.6.1, Table 5: keys are unique per CATReporterIMID, not per
     * file).
     *
     * <p>Worked example (own test): file A holds a MONO with Order Key (20260302
     * 093000.000000000, FRMA, AAPL 260320C00150000, O-1); file B holds the MOOR for it and a
     * MOOC with the same key. Checked alone, file B gives two issues (LK02, LK05); checked with
     * {@code checkFiles(List.of(a, b))} it gives none.</p>
     *
     * @param batches the files, in submission order
     * @return the issues, each with the name of the file of the event concerned
     * @since 3.1.0
     */
    public static List<FileIssue> checkFiles(List<Batch> batches) {
        List<Entry> in = new ArrayList<>();
        for (Batch b : batches) {
            for (CatEvent e : b.events()) {
                in.add(new Entry(e, b.fileImid(), b.fileName()));
            }
        }
        return run(in);
    }

    private static List<FileIssue> run(List<Entry> in) {
        List<FileIssue> out = new ArrayList<>();
        Map<String, List<Entry>> origins = new LinkedHashMap<>();
        List<Entry> routes = new ArrayList<>();
        List<Entry> dependents = new ArrayList<>();
        for (Entry en : in) {
            CatEvent e = en.event();
            Map<String, String> f = e.fields();
            if (!"NEW".equals(f.getOrDefault("actionType", ""))) {
                continue;
            }
            CatEventType t = e.type();
            List<String> missing = new ArrayList<>();
            String key = key(e, en.fileImid(), keyFields(t), missing);
            if (!missing.isEmpty()) {
                out.add(issue(en, "CAT-LK03", "linkage key fields missing: " + missing));
                continue;
            }
            if (t == CatEventType.MEOR || t == CatEventType.MOOR || t == CatEventType.MLOR) {
                routes.add(en);
            } else if (ORIGINS.contains(t) || (t == CatEventType.MOOM && hasPrior(f))) {
                origins.computeIfAbsent(key, k -> new ArrayList<>()).add(en);
                if (t == CatEventType.MOOM) {
                    dependents.add(en);
                }
            } else {
                dependents.add(en);
            }
        }
        for (Map.Entry<String, List<Entry>> g : origins.entrySet()) {
            if (g.getValue().size() > 1) {
                for (Entry en : g.getValue()) {
                    out.add(issue(en, "CAT-LK01", "duplicate linkage key " + g.getKey() + " on "
                            + g.getValue().size() + " events"));
                }
            }
        }
        for (Entry r : routes) {
            CatEventType t = r.event().type();
            Set<CatEventType> parents = switch (t) {
                case MOOR -> OPTION_ORDER_PARENTS;
                case MLOR -> Set.of(CatEventType.MLNO);
                default -> Set.of(CatEventType.MENO);
            };
            String name = switch (t) {
                case MOOR -> "MONO";
                case MLOR -> "MLNO";
                default -> "MENO";
            };
            if (!linked(origins, r, parents, false)) {
                out.add(issue(r, "CAT-LK02", "route has no matching " + name + " Order Key "
                        + orderKey(r, false)));
            }
        }
        for (Entry d : dependents) {
            CatEventType t = d.event().type();
            boolean prior = t == CatEventType.MOOM && hasPrior(d.event().fields());
            Set<CatEventType> parents = switch (t) {
                case MENOS -> Set.of(CatEventType.MENO);
                case MONOS -> Set.of(CatEventType.MONO, CatEventType.MOOA);
                case MLOS, MLOC -> Set.of(CatEventType.MLNO);
                case MOOM, MOOC -> OPTION_ORDER_PARENTS;
                default -> Set.of();
            };
            if (parents.isEmpty() || linked(origins, d, parents, prior)) {
                continue;
            }
            String rule = t.isSupplement() ? "CAT-LK04" : "CAT-LK05";
            out.add(issue(d, rule, t + " has no matching " + parents.stream().map(Enum::name).sorted().toList()
                    + " Order Key " + orderKey(d, prior)));
        }
        return out;
    }

    private static FileIssue issue(Entry en, String rule, String message) {
        return new FileIssue(en.fileName(), new Issue(en.event().reference(), rule, message));
    }

    private static boolean hasPrior(Map<String, String> f) {
        return !f.getOrDefault("priorOrderID", "").isEmpty() && !f.getOrDefault("priorOrderKeyDate", "").isEmpty();
    }

    /** Whether an origin of an allowed parent type has the Order Key of {@code d}. */
    private static boolean linked(Map<String, List<Entry>> origins, Entry d, Set<CatEventType> parents,
            boolean prior) {
        String key = orderKey(d, prior);
        for (List<Entry> group : origins.values()) {
            for (Entry o : group) {
                CatEventType ot = o.event().type();
                if (parents.contains(ot) && ot.isMultiLeg() == d.event().type().isMultiLeg()
                        && orderKey(o, false).equals(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The Order Key text (no kind prefix). For a MOOM with a prior key, {@code prior} selects
     * priorOrderKeyDate and priorOrderID in place of orderKeyDate and orderID.
     */
    private static String orderKey(Entry en, boolean prior) {
        CatEvent e = en.event();
        List<String> parts = e.type().isMultiLeg() ? List.of("orderKeyDate", "CATReporterIMID", "orderID")
                : List.of("orderKeyDate", "CATReporterIMID", "symbol", "orderID");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String name = prior && "orderKeyDate".equals(p) ? "priorOrderKeyDate"
                    : prior && "orderID".equals(p) ? "priorOrderID" : p;
            sb.append(value(e.fields(), name, e.type(), en.fileImid())).append('|');
        }
        return sb.toString();
    }

    private static String key(CatEvent e, String fileImid, List<String> parts, List<String> missing) {
        CatEventType t = e.type();
        StringBuilder key = new StringBuilder(kind(t)).append('|');
        for (String p : parts) {
            String v = value(e.fields(), p, t, fileImid);
            if (v.isEmpty() && !"RFQID".equals(p)) {
                missing.add(p);
            }
            key.append(v).append('|');
        }
        return key.toString();
    }

    private static String kind(CatEventType t) {
        if (t.isMultiLeg()) {
            return "MLORDER";
        }
        return switch (t) {
            case MEOT, MOOT -> "TRADE";
            case MEOF, MOOF -> "FILL";
            case MENQ, MONQ -> "QUOTE";
            default -> "ORDER";
        };
    }

    private static List<String> keyFields(CatEventType t) {
        if (t.isMultiLeg()) {
            return List.of("orderKeyDate", "CATReporterIMID", "orderID");
        }
        return switch (t) {
            case MEOT, MOOT -> List.of("tradeKeyDate", "CATReporterIMID", "symbol", "tradeID");
            case MEOF, MOOF -> List.of("fillKeyDate", "CATReporterIMID", "symbol", "fulfillmentID");
            case MENQ, MONQ -> List.of("quoteKeyDate", "CATReporterIMID", "symbol", "quoteID", "RFQID");
            default -> List.of("orderKeyDate", "CATReporterIMID", "symbol", "orderID");
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
