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
 * Industry Members v4.2.0 r4, section 2.6.1, Table 5, and the "Linkage Keys for this
 * Reportable Event" lists under each event table:</p>
 * <ul>
 *   <li>Order Key (MENO, MEOR, MONO, MOOR): orderKeyDate, CATReporterIMID, symbol (or
 *       optionID), orderID;</li>
 *   <li>Trade Key (MEOT): tradeKeyDate, CATReporterIMID, symbol, tradeID;</li>
 *   <li>Fulfillment Key (MEOF): fillKeyDate, CATReporterIMID, symbol (or optionID),
 *       fulfillmentID;</li>
 *   <li>Quote Key (MENQ) and Option Quote Key (MONQ): quoteKeyDate, CATReporterIMID, symbol
 *       or optionID, quoteID, RFQID (Table 38 and v4.1.0 r14 Table 85 linkage lists).</li>
 * </ul>
 * <p>The CATReporterIMID of an event is optional when it equals the file name's (Table 15
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
 *   <li>MOOA, MLNO, MOOT and MOOF assign keys like MONO, MENO, MEOT and MEOF (Tables 69, 94,
 *       84 and 86 linkage lists; the multi-leg Order Key has no symbol, Table 94);</li>
 *   <li>MOOR links to a MONO, MOOA or key-assigning MOOM; MLOR links to an MLNO (CAT-LK02);</li>
 *   <li>a supplement links to its parent: MENOS to MENO, MONOS to MONO or MOOA, MLOS to MLNO
 *       (CAT-LK04, Tables 16, 62 and 122);</li>
 *   <li>MOOM, MOOC and MLOC need the order they act on (CAT-LK05). A MOOM that carries
 *       priorOrderKeyDate and priorOrderID assigns a new Order Key and links through the prior
 *       one (Table 78 seq 9-10).</li>
 * </ul>
 * <p>For the original types the results of {@link #check(List, String)} are unchanged: the
 * new rules only fire on events of the new types.</p>

 *
 * <p>Since 4.0.0 the 47 Table 59 and 60 types added in 3.2.0 take part too, each with the keys
 * of the linkage list under its own table:</p>
 * <ul>
 *   <li>MOIR, MOCO, MLIR and MLCO assign an Order Key; when they carry parentOrderKeyDate and
 *       parentOrderID, the Parent Order Key must belong to an order in scope (CAT-LK06; Table 5,
 *       Tables 70, 75, 103 and 111). MLOA, the received and new quotes MOQR, MLNQ and MLQR, and
 *       the allocation MOPA assign keys too;</li>
 *   <li>MOIM, MOCOM, MOOJ, MOOE, MLIM, MLCOM, MLOM and MLOE (orders), MOQM and MLQM (quotes),
 *       MOFA (fulfillments) and MOAA (allocations) link through their prior key when they carry
 *       one and then assign their own key; without one they link through their own key;</li>
 *   <li>every other new type needs an event in scope with its key: route, internal-route and
 *       child-order events, requests and adjustments (CAT-LK07), quote events (CAT-LK08),
 *       fulfillment and allocation amendments (CAT-LK09), supplements (CAT-LK04).</li>
 * </ul>
 * <p>For the new types any originating event with the same key kind links: Order, Quote,
 * Fulfillment and Allocation Keys are unique per reporter (Table 5), so the key alone names the
 * event. That is the library's reading. Routes (MOOR, MLOR) may also route internal-route, child,
 * accepted, adjusted and effective orders; MOOM and MOOC accept MOOJ and MOOE as the order acted
 * on, and MLOC accepts MLOA, MLOM and MLOE. Batches of the 3.1 types alone give the same results
 * as in 3.x.</p>
 *
 * @since 3.1.0
 */
@SuppressWarnings("removal") // the v4.1.0 r14 quote types stay linkage-checked until 5.0.0
public final class CatLinkageChecker {
    private static final String SPEC = "CAT IM Technical Specifications v4.2.0 r4, ";

    private static final Set<CatEventType> ORIGINS = Set.of(CatEventType.MENO, CatEventType.MONO,
            CatEventType.MOOA, CatEventType.MLNO, CatEventType.MEOT, CatEventType.MOOT, CatEventType.MEOF,
            CatEventType.MOOF, CatEventType.MENQ, CatEventType.MONQ);
    private static final Set<CatEventType> OPTION_ORDER_PARENTS = Set.of(CatEventType.MONO, CatEventType.MOOA,
            CatEventType.MOOM, CatEventType.MOOJ, CatEventType.MOOE);
    private static final Set<CatEventType> OPTION_ROUTE_PARENTS = Set.of(CatEventType.MONO, CatEventType.MOOA,
            CatEventType.MOOM, CatEventType.MOOJ, CatEventType.MOOE, CatEventType.MOIR, CatEventType.MOIM,
            CatEventType.MOCO, CatEventType.MOCOM);
    private static final Set<CatEventType> ML_ORDER_PARENTS = Set.of(CatEventType.MLNO, CatEventType.MLOA,
            CatEventType.MLOM, CatEventType.MLOE);
    private static final Set<CatEventType> ML_ROUTE_PARENTS = Set.of(CatEventType.MLNO, CatEventType.MLOA,
            CatEventType.MLOM, CatEventType.MLOE, CatEventType.MLIR, CatEventType.MLIM, CatEventType.MLCO,
            CatEventType.MLCOM);
    /** Table 59/60 types that assign a key (Tables 70, 75, 91, 101, 103, 111; v4.1.0 r14 Tables 87, 128, 134). */
    private static final Set<CatEventType> NEW_ORIGINS = Set.of(CatEventType.MOIR, CatEventType.MOCO,
            CatEventType.MLIR, CatEventType.MLCO, CatEventType.MLOA, CatEventType.MOQR, CatEventType.MLNQ,
            CatEventType.MLQR, CatEventType.MOPA);
    /** Table 59/60 types that link through a prior key when they carry one, then assign their own. */
    private static final Set<CatEventType> NEW_PRIOR = Set.of(CatEventType.MOIM, CatEventType.MOCOM,
            CatEventType.MOOJ, CatEventType.MOOE, CatEventType.MLIM, CatEventType.MLCOM, CatEventType.MLOM,
            CatEventType.MLOE, CatEventType.MOQM, CatEventType.MLQM, CatEventType.MOFA, CatEventType.MOAA);
    private static final Set<CatEventType> WITH_PARENT = Set.of(CatEventType.MOIR, CatEventType.MOCO,
            CatEventType.MLIR, CatEventType.MLCO);

    private static final List<RuleProvenance> RULES = List.of(
            new RuleProvenance("CAT-LK01", "no two NEW originating events (MENO, MONO, MEOT, MEOF, MENQ, MONQ; "
                    + "since 3.1.0 also MOOA, MLNO, MOOT, MOOF and a key-assigning MOOM) share an Order, Trade, "
                    + "Fulfillment or Quote Key; every event with a duplicated key is reported", SPEC + "section "
                    + "2.6.1, Table 5 ('Duplication of Order Keys results in the rejection of all events with the "
                    + "same key'; same for Trade and Fulfillment Keys)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-LK02", "every NEW route (MEOR, MOOR; since 3.1.0 MLOR) has the Order Key of a "
                    + "NEW MENO, MONO (or MOOA, key-assigning MOOM) or MLNO in the batch", SPEC + "section 2.6.1, "
                    + "Table 5 (Order Key links an order event to a subsequent route), Table 17, Table 63 and Table "
                    + "96 linkage lists; batch-local scope of check() is the library's reading",
                    Evidence.LIBRARY_READING),
            new RuleProvenance("CAT-LK03", "every keyed field of a NEW event is present", SPEC
                    + "section 2.6, 'these data elements must create a Linkage Key that is unique'",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-LK04", "every NEW supplement has the Order Key of its parent: MENOS of a MENO, "
                    + "MONOS of a MONO or MOOA, MLOS of an MLNO", SPEC + "section 4.2 and Table 16, section 5.1.2 "
                    + "and Table 62, section 5.2.8 and Table 122 (supplement 'to the New Order event'; Order Key "
                    + "in each linkage list)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-LK05", "every NEW MOOM, MOOC and MLOC has the Order Key of the order it "
                    + "modifies or cancels; a MOOM with priorOrderKeyDate and priorOrderID links through the prior "
                    + "key and assigns its own", SPEC + "Table 78 seq 9-10 and linkage list, Table 82 and Table 120 "
                    + "linkage lists", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-LK06", "since 4.0.0: a NEW MOIR, MOCO, MLIR or MLCO that carries "
                    + "parentOrderKeyDate and parentOrderID has the Parent Order Key of an order in scope", SPEC
                    + "section 2.6.1, Table 5 (Parent Order Key), Tables 70, 75, 103 and 111 linkage lists",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-LK07", "since 4.0.0: every NEW Table 59/60 order event that does not "
                    + "assign a key (route modified or cancelled, internal-route and child-order events, "
                    + "modification and cancel requests, adjustments, effective events) has the Order Key, or "
                    + "the Prior Order Key when it carries one, of an order in scope", SPEC + "Tables 64, 65, "
                    + "71-77, 80, 81, 83, 93, 98, 100, 105-118, 121 and 124 linkage lists; 'any order with "
                    + "that key' is the library's reading", Evidence.LIBRARY_READING),
            new RuleProvenance("CAT-LK08", "since 4.0.0: every NEW option or multi-leg routed, cancelled or "
                    + "modified quote has the Quote Key, or the Prior Quote Key when it carries one, of a quote "
                    + "in scope", SPEC + "Table 5 (Quote Key), v4.1.0 r14 Tables 86, 88, 89, 130, 136 and 137 linkage "
                    + "lists", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-LK09", "since 4.0.0: every NEW MOFA has the Fulfillment Key (or Prior "
                    + "Fulfillment Key) of an option fulfillment in scope, and every NEW MOAA the Allocation Key "
                    + "(or Prior Allocation Key) of an allocation in scope", SPEC + "Table 5 (Fulfillment, Prior "
                    + "Fulfillment, Allocation and Prior Allocation Keys), Tables 90 and 92 linkage lists",
                    Evidence.PUBLISHED_REFERENCE));

    /**
     * One linkage problem.
     *
     * @param firmRoeId the firmROEID of the event concerned
     * @param ruleId CAT-LK01 to CAT-LK09
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
     * event leaves CATReporterIMID blank, Table 15 seq 5) and its events.
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
        List<Entry> newDependents = new ArrayList<>();
        List<Entry> parented = new ArrayList<>();
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
            if (CatTableRules.ROWS.containsKey(t)) {
                boolean prior = NEW_PRIOR.contains(t) && hasPrior(t, f);
                if (NEW_ORIGINS.contains(t) || prior) {
                    origins.computeIfAbsent(key, k -> new ArrayList<>()).add(en);
                }
                if (WITH_PARENT.contains(t) && hasParent(f)) {
                    parented.add(en);
                }
                if (!NEW_ORIGINS.contains(t)) {
                    newDependents.add(en);
                }
            } else if (t == CatEventType.MEOR || t == CatEventType.MOOR || t == CatEventType.MLOR) {
                routes.add(en);
            } else if (ORIGINS.contains(t) || (t == CatEventType.MOOM && hasPrior(t, f))) {
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
                case MOOR -> OPTION_ROUTE_PARENTS;
                case MLOR -> ML_ROUTE_PARENTS;
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
            boolean prior = t == CatEventType.MOOM && hasPrior(t, d.event().fields());
            Set<CatEventType> parents = switch (t) {
                case MENOS -> Set.of(CatEventType.MENO);
                case MONOS -> Set.of(CatEventType.MONO, CatEventType.MOOA);
                case MLOS -> Set.of(CatEventType.MLNO);
                case MLOC -> ML_ORDER_PARENTS;
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
        for (Entry p : parented) {
            String pk = keyWith(p, orderFields(p.event().type()), "parentOrderKeyDate", "parentOrderID", "ORDER");
            if (!hasOther(origins, pk, p)) {
                out.add(issue(p, "CAT-LK06", p.event().type() + " has no order with Parent Order Key " + pk));
            }
        }
        for (Entry d : newDependents) {
            CatEventType t = d.event().type();
            String lk;
            if (NEW_PRIOR.contains(t) && hasPrior(t, d.event().fields())) {
                String[] pf = priorFields(t);
                lk = keyWith(d, keyFields(t), pf[0], pf[1], baseKind(t));
            } else {
                lk = key(d.event(), d.fileImid(), keyFields(t), new ArrayList<>());
            }
            if (hasOther(origins, lk, d)) {
                continue;
            }
            String rule = t.family() == CatEventFamily.SUPPLEMENT ? "CAT-LK04" : switch (baseKind(t)) {
                case "QUOTE" -> "CAT-LK08";
                case "FILL", "ALLOC" -> "CAT-LK09";
                default -> "CAT-LK07";
            };
            out.add(issue(d, rule, t + " has no matching event with key " + lk));
        }
        return out;
    }

    /** Whether an origin other than {@code self} has the full key {@code k}. */
    private static boolean hasOther(Map<String, List<Entry>> origins, String k, Entry self) {
        // Identity is intended: Entry is a record, so two submissions of the same event
        // in one file are equal by value, yet each must count as the other's match.
        // Only the entry being checked is skipped; isSameEntry keeps the one
        // ReferenceEquality suppression in a single named place.
        for (Entry o : origins.getOrDefault(k, List.of())) {
            if (!isSameEntry(o, self)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("ReferenceEquality") // identity, not value equality, by design
    private static boolean isSameEntry(Entry a, Entry b) {
        return a == b;
    }

    private static List<String> orderFields(CatEventType t) {
        return t.isMultiLeg() ? List.of("orderKeyDate", "CATReporterIMID", "orderID")
                : List.of("orderKeyDate", "CATReporterIMID", "symbol", "orderID");
    }

    /**
     * The full key (with kind prefix) built from {@code parts}, with the date part (the first)
     * read from {@code dateField} and the ID part (the last one other than RFQID) from
     * {@code idField}: the prior or parent key of Table 5.
     */
    private static String keyWith(Entry en, List<String> parts, String dateField, String idField, String base) {
        CatEvent e = en.event();
        CatEventType t = e.type();
        int id = "RFQID".equals(parts.get(parts.size() - 1)) ? parts.size() - 2 : parts.size() - 1;
        StringBuilder sb = new StringBuilder(t.isMultiLeg() ? "ML" + base : base).append('|');
        for (int i = 0; i < parts.size(); i++) {
            String name = i == 0 ? dateField : i == id ? idField : parts.get(i);
            sb.append(value(e.fields(), name, t, en.fileImid())).append('|');
        }
        return sb.toString();
    }

    private static boolean hasParent(Map<String, String> f) {
        return !f.getOrDefault("parentOrderID", "").isEmpty() && !f.getOrDefault("parentOrderKeyDate", "").isEmpty();
    }

    private static String[] priorFields(CatEventType t) {
        return switch (baseKind(t)) {
            case "QUOTE" -> new String[] {"priorQuoteKeyDate", "priorQuoteID"};
            case "FILL" -> new String[] {"priorFillKeyDate", "priorFulfillmentID"};
            case "ALLOC" -> new String[] {"priorAllocationKeyDate", "priorAllocationID"};
            default -> new String[] {"priorOrderKeyDate", "priorOrderID"};
        };
    }

    private static FileIssue issue(Entry en, String rule, String message) {
        return new FileIssue(en.fileName(), new Issue(en.event().reference(), rule, message));
    }

    private static boolean hasPrior(CatEventType t, Map<String, String> f) {
        String[] pf = priorFields(t);
        return !f.getOrDefault(pf[1], "").isEmpty() && !f.getOrDefault(pf[0], "").isEmpty();
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
        return t.isMultiLeg() ? "ML" + baseKind(t) : baseKind(t);
    }

    /** The key kind without the multi-leg prefix (the groups of Table 5). */
    private static String baseKind(CatEventType t) {
        return switch (t) {
            case MEOT, MOOT -> "TRADE";
            case MEOF, MOOF, MOOFS, MOFA -> "FILL";
            case MENQ, MONQ, MORQ, MOQR, MOQC, MOQM, MLNQ, MLRQ, MLQS, MLQR, MLQC, MLQM -> "QUOTE";
            case MOPA, MOAA -> "ALLOC";
            default -> "ORDER";
        };
    }

    private static List<String> keyFields(CatEventType t) {
        String k = baseKind(t);
        if (t.isMultiLeg()) {
            return "QUOTE".equals(k) ? List.of("quoteKeyDate", "CATReporterIMID", "quoteID", "RFQID")
                    : List.of("orderKeyDate", "CATReporterIMID", "orderID");
        }
        return switch (k) {
            case "TRADE" -> List.of("tradeKeyDate", "CATReporterIMID", "symbol", "tradeID");
            case "FILL" -> List.of("fillKeyDate", "CATReporterIMID", "symbol", "fulfillmentID");
            case "QUOTE" -> List.of("quoteKeyDate", "CATReporterIMID", "symbol", "quoteID", "RFQID");
            case "ALLOC" -> List.of("allocationKeyDate", "CATReporterIMID", "symbol", "allocationID");
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
