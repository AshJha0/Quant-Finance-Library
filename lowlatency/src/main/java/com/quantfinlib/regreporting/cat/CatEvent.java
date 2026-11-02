package com.quantfinlib.regreporting.cat;

import com.quantfinlib.regreporting.RuleProvenance;
import com.quantfinlib.regreporting.RuleProvenance.Evidence;
import com.quantfinlib.regreporting.TransactionReport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * One CAT event, as built by {@link CatEventBuilder}. Regime {@code "CAT"}; the reference is
 * the {@code firmROEID}. Not certified against the CAT specification (see the package
 * javadoc).
 *
 * <p>Every check made by {@link #validate()} is listed by {@link #rules()} with its citation
 * and evidence class, checked against the CAT Reporting Technical Specifications for
 * Industry Members v4.2.0 r4 (2026-10-21); see {@link #CHECKED_AGAINST}. Up to 3.0 only the four
 * equity event types and only single-record rules were checked; linkage, reference data
 * (symbol master, IMID list, FDID), late-reporting and option or quote events were not.
 * Since 3.1.0 the new-quote (MENQ), new option order (MONO), option route (MOOR) and new
 * option quote (MONQ) types are also checked (rules CAT-R13 to CAT-R16), and
 * {@link CatLinkageChecker} checks linkage keys across a batch of events. Also since 3.1.0 the
 * option lifecycle events (MOOA, MOOM, MOOC, MOOT, MOOF), the supplements (MENOS, MONOS,
 * MLOS) and the multi-leg events (MLNO, MLOR, MLOC) are checked (rules CAT-R17 to CAT-R21);
 * {@link CatSchema} checks a JSON record line against a CAT schema file. Since 3.2.0 the other
 * simple option events of Table 59 and multi-leg events of Table 60 (47 more types, grouped by
 * {@link CatEventType#family()}) are checked for their required fields (CAT-R02), side and
 * optionID (CAT-R13, CAT-R14) or the multi-leg rules (CAT-R21), and the price and choice rules
 * of their own tables (CAT-R22, CAT-R23); see {@link #CHECKED_AGAINST_TABLES_60_61}.</p>
 *
 * <p>Multi-leg events carry their legs in {@code legDetails} (Table 95), held here as the
 * JSON array text written by {@link CatEventBuilder#optionLeg} and
 * {@link CatEventBuilder#equityLeg}. {@link #toJson()} writes every value as a JSON string,
 * that included; {@link CatSchema#toJson(CatEvent)} writes the JSON types of the schema.</p>
 *
 * <p>Worked examples from the specification, pinned in tests: the section 7.6.1.1 repair
 * record (RPR with errorROEID 12345678) and the section 7.6.2.1 firm correction (COR, no
 * errorROEID) are accepted; the section 7.6.3.1 delete records
 * ({@code {"actionType":"DEL","errorROEID":45678901}} and one with only a firmROEID) are
 * accepted.</p>
 */
public final class CatEvent implements TransactionReport {
    /**
     * Fields present on every event of the four supported types (seq 1 to 5 plus symbol,
     * eventTimestamp and manualFlag of Tables 15, 17, 47 and 51). Of these, actionType,
     * firmROEID, type, symbol and eventTimestamp are required; errorROEID is conditional and
     * CATReporterIMID optional.
     */
    public static final List<String> COMMON_FIELDS = List.of("actionType", "errorROEID", "firmROEID",
            "type", "CATReporterIMID", "symbol", "eventTimestamp", "manualFlag");

    private static final List<String> COMMON_REQUIRED = List.of("actionType", "firmROEID", "type", "symbol",
            "eventTimestamp");
    private static final Set<String> SIDES = Set.of("B", "SL", "SS", "SX");
    private static final Set<String> OPTION_SIDES = Set.of("B", "S");
    private static final Pattern WHOLE_QTY = Pattern.compile("\\d{1,12}");
    /** OSI symbol: root (6, space padded), yymmdd, C or P, strike x1000 (8 digits); FLEX % prefix. */
    private static final Pattern OSI = Pattern.compile("%?[A-Z0-9 .]{6}\\d{6}[CP]\\d{8}");
    private static final Set<String> ACTIONS = Set.of("NEW", "COR", "DEL", "RPR");
    private static final Set<String> CAPACITIES = Set.of("A", "P", "R");
    private static final Set<String> SIDE_DETAILS = Set.of("BUY", "SELL", "NA");
    private static final Set<String> LINK_TYPES = Set.of("FOR", "Y", "YE");
    private static final Set<String> OPTION_LINK_TYPES = Set.of("O", "OS", "OE");
    private static final Set<String> ORDER_SENDER_TYPES = Set.of("E", "F", "O");
    private static final Set<String> INITIATORS = Set.of("C", "F");
    private static final Set<String> PRICE_TYPES = Set.of("PU", "TC", "TS");
    private static final Set<String> LEG_KEYS = Set.of("legRefID", "symbol", "optionID", "openCloseIndicator",
            "side", "legRatioQuantity");
    private static final Set<String> BOOLEANS = Set.of("manualFlag", "electronicDupFlag", "solicitationFlag",
            "custDspIntrFlag", "affiliateFlag", "negotiatedTradeFlag", "routeRejectedFlag", "dupROIDCond",
            "multiLegInd", "cancelFlag");
    private static final List<String> TIMESTAMPS = List.of("eventTimestamp", "orderKeyDate", "tradeKeyDate",
            "fillKeyDate");
    private static final Pattern IMID = Pattern.compile("[a-zA-Z0-9]{1,7}");
    private static final Pattern PRICE = Pattern.compile("-?\\d{1,10}(\\.\\d{1,8})?");
    private static final Pattern REAL_QTY = Pattern.compile("\\d{1,12}(\\.\\d{0,5}[1-9])?");
    private static final Pattern TIMESTAMP = Pattern.compile("\\d{8}[ T]\\d{6}(\\.\\d{1,9})?|\\d{1,20}");
    private static final Pattern ERROR_ROEID = Pattern.compile("\\d{1,20}");

    /** What the checks in {@link #validate()} have been compared with. */
    public static final String CHECKED_AGAINST = "checked against CAT Reporting Technical Specifications for "
            + "Industry Members v4.2.0 r4 (2026-10-21), sections 2.5, 4.1, 4.3, 4.11.1, 4.12.1, 7.6 and "
            + "Appendix G, plus Tables 38, 61, 63 (MENQ, MONO, MOOR) and v4.1.0 r14 Table 85 (MONQ) since 3.1.0; not certified";

    /**
     * What the rules added with the option lifecycle, supplement and multi-leg types have been
     * compared with ({@link #CHECKED_AGAINST} is left as it was).
     *
     * @since 3.1.0
     */
    public static final String CHECKED_AGAINST_LIFECYCLE = "checked against CAT Reporting Technical "
            + "Specifications for Industry Members v4.2.0 r4 (2026-10-21), Tables 16, 62, 69, 78, 82, 84, 86, "
            + "94, 95, 96, 120 and 122 and Appendix G (senderType, initiator, priceType, "
            + "fulfillmentLinkType); not certified";

    /**
     * What the rules for the Table 59 and 60 types added in 3.2.0 have been compared with
     * ({@link #CHECKED_AGAINST} and {@link #CHECKED_AGAINST_LIFECYCLE} are left as they were).
     *
     * @since 3.2.0
     */
    public static final String CHECKED_AGAINST_TABLES_60_61 = "checked against CAT Reporting Technical "
            + "Specifications for Industry Members v4.2.0 r4 (2026-10-21), Tables 59 and 60 and the field "
            + "tables 64-68, 70-77, 79-81, 83, 88, 90-93, 98, 100, 101, 103, 105, 107, 108, 110, 111, 113, "
            + "115, 116, 118, 121 and 124 (since 4.0.0; the quote events keep v4.1.0 r14 Tables 86-89, 128, "
            + "130, 132, 134, 136 and 137), and Appendix G (side, initiator, "
            + "senderType, priceType, fulfillmentLinkType); not certified";

    private static final String SPEC = "CAT IM Technical Specifications v4.2.0 r4, ";

    private static final List<RuleProvenance> RULES = List.of(
            new RuleProvenance("CAT-R01", "a DEL record carries only actionType plus errorROEID or firmROEID; no "
                    + "other field is checked", SPEC + "section 7.6.3 and Table 148 (worked examples printed)",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R02", "actionType, firmROEID, type, symbol, eventTimestamp and the type's "
                    + "required non-Boolean fields are present", SPEC + "Tables 15, 17, 47, 51 Include Key 'R'; "
                    + "Booleans left out per Table 3 (absent JSON Boolean = false)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R03", "actionType is NEW, COR, RPR or DEL", SPEC + "Appendix G, actionType",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R04", "errorROEID is required with RPR, blank with NEW, and an Unsigned when "
                    + "given; COR does not need it", SPEC + "Table 15 seq 2, section 7.6.1 rule 2, section 7.6.2 "
                    + "(worked COR and RPR examples printed)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R05", "side is B, SL, SS or SX (equity)", SPEC + "Appendix G, side",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R06", "CATReporterIMID, when given, is Alphanumeric (7)",
                    SPEC + "Table 3, CAT Reporter IMID; Table 15 seq 5 'O'", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R07", "quantity is Real Quantity: Numeric (12,6), not negative, no trailing "
                    + "decimal zeros", SPEC + "Table 3, Real Quantity", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R08", "price is Price: Numeric (10,8) with a digit before any decimal point; "
                    + "on MENO and MEOR it is not negative, required when orderType is LMT and blank when MKT",
                    SPEC + "Table 3, Price; Table 15 seq 19, Table 17 seq 20", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R09", "capacity is A, P or R; sideDetailsInd is BUY, SELL or NA; "
                    + "fulfillmentLinkType is FOR, Y or YE", SPEC + "Appendix G, capacity, sideDetailsInd, "
                    + "fulfillmentLinkType (equity values)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R10", "on MEOT, sideDetailsInd BUY populates only buyDetails and SELL only "
                    + "sellDetails", SPEC + "Appendix G, sideDetailsInd", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R11", "on MEOT, sideDetailsInd NA populates both buyDetails and sellDetails",
                    SPEC + "Appendix G, sideDetailsInd says only 'not one sided'; requiring both is the "
                            + "library's reading", Evidence.LIBRARY_READING),
            new RuleProvenance("CAT-R12", "timestamps are 'YYYYMMDD HHMMSS[.fraction]' (space or T) or Unsigned "
                    + "nanoseconds; Boolean fields are true or false in any case", SPEC + "Table 3, Timestamp "
                    + "and Boolean; a fraction of 1 to 9 digits is the library's reading of the examples",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R13", "on option events (MONO, MOOR, MONQ) optionID replaces symbol and side "
                    + "is B or S", SPEC + "Tables 61, 63 and v4.1.0 r14 Table 85 seq 8 (optionID 'R'); Appendix G, side (Option "
                    + "column)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R14", "optionID is a 21-character OSI symbol, optionally prefixed by % for "
                    + "FLEX Percent options", SPEC + "Table 61 seq 8 ('21-character OSI Symbol', '%' prefix); the "
                    + "OSI layout (6+6+1+8) is the library's reading, the OSI standard is not on disk",
                    Evidence.LIBRARY_READING),
            new RuleProvenance("CAT-R15", "on MONO and MOOR, price is not negative, required when orderType is "
                    + "LMT and blank when MKT", SPEC + "Table 61 seq 17, Table 63 seq 20", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R16", "on quote events bidPrice and askPrice are Price and not negative; bidQty "
                    + "and askQty are Whole Quantity (Numeric (12,0)); quoteKeyDate is a Timestamp",
                    SPEC + "Table 38 seq 17-20, v4.1.0 r14 Table 85 seq 11-14, Table 3 Whole Quantity",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R17", "senderType is E, F or O on MOOA; initiator is C or F on MOOM, MOOC and "
                    + "MLOC; priceType is PU, TC or TS on MLNO and MLOR and required when orderType is LMT; on MOOT "
                    + "and MOOF fulfillmentLinkType is O, OS or OE (option values)", SPEC + "Table 69 seq 17, "
                    + "Table 78 seq 22, Table 82 seq 15, Table 120 seq 14, Table 94 seq 31, Table 96 seq 30; "
                    + "Appendix G senderType (order events), initiator, priceType, fulfillmentLinkType (option "
                    + "events)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R18", "cancelQty and leavesQty are Real Quantity; numberOfLegs is Whole "
                    + "Quantity and at least 1", SPEC + "Table 78 seq 27, Table 82 seq 13-14, Table 120 seq "
                    + "12-13, Table 94 seq 30, Table 96 seq 29; Table 3 Real and Whole Quantity",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R19", "legDetails on MLNO and MLOR is a non-empty JSON array of legs; each "
                    + "leg has exactly one of symbol and optionID, a side (B or S for option legs, B, SL, SS or SX "
                    + "for equity legs), a legRatioQuantity that is Real Quantity, an OSI optionID, and no "
                    + "openCloseIndicator on an equity leg; no more legs than numberOfLegs", SPEC + "Table 95 "
                    + "seq 32.n.2-32.n.6, Table 97; Appendix G side; 'no more legs than numberOfLegs' is the "
                    + "library's reading (legs that do not fit go on MLOS, Table 95 note)",
                    Evidence.LIBRARY_READING),
            new RuleProvenance("CAT-R20", "price is not negative on MOOA, MOOM, MOOT and MOOF; on MOOA, MOOM, MLNO "
                    + "and MLOR it is required when orderType is LMT and blank when MKT; a multi-leg net price may "
                    + "be negative (a credit)", SPEC + "Table 69 seq 21, Table 78 seq 24, Table 84 seq 15, Table 86 "
                    + "seq 13, Table 94 seq 16, Table 96 seq 18", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R21", "multi-leg events (MLNO, MLOR, MLOC, MLOS) carry no symbol and no "
                    + "optionID at the top level; underlying, when given, is at most 22 characters", SPEC
                    + "Tables 94, 96, 120, 122 (no symbol or optionID field; seq 8 underlying is Symbol 'O'); "
                    + "Table 3 Symbol", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R22", "on the Table 59 and 60 types added in 3.2.0, price is not negative "
                    + "where the type's table says it must be greater than or equal to zero, required when "
                    + "orderType is LMT and blank when MKT where the table says so; multi-leg net prices may be "
                    + "negative", SPEC + "price field of each table, e.g. Table 64 seq 20, Table 75 seq 14, "
                    + "Table 90 seq 17, Table 101 seq 19; the seq per type is in the message",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R23", "on the Table 59 and 60 types added in 3.2.0, initiator is C or F; "
                    + "senderType is E, F or O on order events and F, N or S on quote events (MOQR, MLQR); "
                    + "priceType is PU, TC or TS and required when orderType is LMT; option fulfillmentLinkType "
                    + "(MOFA) is O, OS or OE", SPEC + "Appendix G initiator, senderType (order and quote "
                    + "events), priceType, fulfillmentLinkType; e.g. Table 71 seq 17, v4.1.0 r14 Table 87 seq 23, Table 92 "
                    + "seq 33", Evidence.PUBLISHED_REFERENCE));

    private final Map<String, String> fields;

    CatEvent(Map<String, String> fields) {
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /**
     * Every rule {@link #validate()} applies, in order, with its citation and evidence class.
     *
     * @return the rule catalogue (immutable)
     */
    public static List<RuleProvenance> rules() {
        return RULES;
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
        return CatEventType.valueOf(fields.getOrDefault("type", ""));
    }

    @Override
    public List<String> validate() {
        List<String> out = new ArrayList<>();
        String a = v("actionType");
        if ("DEL".equals(a)) {
            if (blank("errorROEID") && blank("firmROEID")) {
                out.add("DEL needs errorROEID or firmROEID");
            }
            errorRoeidShape(out);
            return out;
        }
        CatEventType t = type();
        for (String k : COMMON_REQUIRED) {
            if ((t.isOption() || t.isMultiLeg()) && "symbol".equals(k)) {
                continue;
            }
            if (blank(k)) {
                out.add("missing " + k);
            }
        }
        for (String k : t.requiredFields()) {
            if (blank(k)) {
                out.add("missing " + k);
            }
        }
        if (!a.isEmpty() && !ACTIONS.contains(a)) {
            out.add("actionType must be one of " + new TreeSet<>(ACTIONS));
        }
        if ("RPR".equals(a) && blank("errorROEID")) {
            out.add("errorROEID required for actionType RPR");
        }
        if ("NEW".equals(a) && !blank("errorROEID")) {
            out.add("errorROEID must be blank for actionType NEW");
        }
        errorRoeidShape(out);
        oneOf("side", t.isOption() ? OPTION_SIDES : SIDES, out);
        if (t.isOption()) {
            String osi = v("optionID");
            if (!osi.isEmpty() && !OSI.matcher(osi).matches()) {
                out.add("optionID must be a 21-character OSI symbol");
            }
        }
        if (t.isQuote()) {
            checkQuote(out);
        }
        oneOf("capacity", CAPACITIES, out);
        oneOf("sideDetailsInd", SIDE_DETAILS, out);
        oneOf("fulfillmentLinkType", t == CatEventType.MOOT || t == CatEventType.MOOF
                || t == CatEventType.MOFA ? OPTION_LINK_TYPES
                : LINK_TYPES, out);
        String imid = v("CATReporterIMID");
        if (!imid.isEmpty() && !IMID.matcher(imid).matches()) {
            out.add("CATReporterIMID must be Alphanumeric (7)");
        }
        String qty = v("quantity");
        if (!qty.isEmpty() && !REAL_QTY.matcher(qty).matches()) {
            out.add("quantity must be Real Quantity: Numeric (12,6), not negative, no trailing zeros");
        }
        checkPrice(t, out);
        if (t == CatEventType.MEOT || t == CatEventType.MOOT) {
            checkSideDetails(out);
        }
        checkLifecycle(t, out);
        CatTableRules.check(t, this::v, out);
        for (String k : TIMESTAMPS) {
            String ts = v(k);
            if (!ts.isEmpty() && !TIMESTAMP.matcher(ts).matches()) {
                out.add(k + " must be 'YYYYMMDD HHMMSS.CCCNNNNNN' or Unsigned nanoseconds");
            }
        }
        for (String k : BOOLEANS) {
            String b = v(k);
            if (!b.isEmpty() && !"true".equalsIgnoreCase(b) && !"false".equalsIgnoreCase(b)) {
                out.add(k + " must be true or false");
            }
        }
        return out;
    }

    private void checkPrice(CatEventType t, List<String> out) {
        String px = v("price");
        if (!px.isEmpty() && !PRICE.matcher(px).matches()) {
            out.add("price must be Price: Numeric (10,8)");
        }
        if (t == CatEventType.MENO || t == CatEventType.MEOR || t == CatEventType.MONO
                || t == CatEventType.MOOR) {
            if (px.startsWith("-")) {
                out.add("price must be greater than or equal to zero");
            }
            String ot = v("orderType");
            if ("LMT".equals(ot) && px.isEmpty()) {
                out.add("price required when orderType is LMT");
            }
            if ("MKT".equals(ot) && !px.isEmpty()) {
                out.add("price must be blank when orderType is MKT");
            }
        }
    }

    /** CAT-R17 to CAT-R21; only the types added with the 3.1.0 lifecycle work reach a check. */
    private void checkLifecycle(CatEventType t, List<String> out) {
        String px = v("price");
        String ot = v("orderType");
        switch (t) {
            case MOOA -> oneOf("senderType", ORDER_SENDER_TYPES, out);
            case MOOM, MOOC, MLOC -> oneOf("initiator", INITIATORS, out);
            default -> {
                // no choice field of R17 on the other types
            }
        }
        boolean nonNegative = t == CatEventType.MOOA || t == CatEventType.MOOM || t == CatEventType.MOOT
                || t == CatEventType.MOOF;
        if (nonNegative && px.startsWith("-")) {
            out.add("price must be greater than or equal to zero");
        }
        if (t == CatEventType.MOOA || t == CatEventType.MOOM || t == CatEventType.MLNO
                || t == CatEventType.MLOR) {
            if ("LMT".equals(ot) && px.isEmpty()) {
                out.add("price required when orderType is LMT");
            }
            if ("MKT".equals(ot) && !px.isEmpty()) {
                out.add("price must be blank when orderType is MKT");
            }
        }
        for (String k : List.of("cancelQty", "leavesQty")) {
            String q = v(k);
            if (!q.isEmpty() && !REAL_QTY.matcher(q).matches()) {
                out.add(k + " must be Real Quantity: Numeric (12,6), not negative, no trailing zeros");
            }
        }
        if (!t.isMultiLeg()) {
            return;
        }
        if (!blank("symbol") || !blank("optionID")) {
            out.add("multi-leg events carry no top-level symbol or optionID");
        }
        if (v("underlying").length() > 22) {
            out.add("underlying must be Symbol: Text (22)");
        }
        if (t == CatEventType.MLNO || t == CatEventType.MLOR) {
            oneOf("priceType", PRICE_TYPES, out);
            if ("LMT".equals(ot) && blank("priceType")) {
                out.add("priceType required when orderType is LMT");
            }
            long n = -1;
            String legs = v("numberOfLegs");
            if (!legs.isEmpty()) {
                if (WHOLE_QTY.matcher(legs).matches() && Long.parseLong(legs) >= 1) {
                    n = Long.parseLong(legs);
                } else {
                    out.add("numberOfLegs must be Whole Quantity, at least 1");
                }
            }
            checkLegs(n, out);
        }
    }

    private void checkLegs(long numberOfLegs, List<String> out) {
        String text = v("legDetails");
        if (text.isBlank()) {
            return;
        }
        Object parsed;
        try {
            parsed = CatJson.parse(text);
        } catch (CatJson.ParseException e) {
            out.add("legDetails must be a JSON array of leg objects: " + e.getMessage());
            return;
        }
        if (!(parsed instanceof List<?> legs) || legs.isEmpty()) {
            out.add("legDetails must be a non-empty JSON array of leg objects");
            return;
        }
        if (numberOfLegs > 0 && legs.size() > numberOfLegs) {
            out.add("legDetails has " + legs.size() + " legs, more than numberOfLegs " + numberOfLegs);
        }
        for (int n = 0; n < legs.size(); n++) {
            String at = "leg " + (n + 1) + ": ";
            if (!(legs.get(n) instanceof Map<?, ?> leg)) {
                out.add(at + "must be a JSON object");
                continue;
            }
            for (Object k : leg.keySet()) {
                if (!LEG_KEYS.contains(k)) {
                    out.add(at + "unknown field " + k);
                }
            }
            checkLeg(leg, at, out);
        }
    }

    private static void checkLeg(Map<?, ?> leg, String at, List<String> out) {
        String sym = legText(leg, "symbol");
        String osi = legText(leg, "optionID");
        if (sym.isEmpty() == osi.isEmpty()) {
            out.add(at + "exactly one of symbol and optionID");
        }
        if (!osi.isEmpty() && !OSI.matcher(osi).matches()) {
            out.add(at + "optionID must be a 21-character OSI symbol");
        }
        if (!sym.isEmpty() && !legText(leg, "openCloseIndicator").isEmpty()) {
            out.add(at + "openCloseIndicator must be blank on an equity leg");
        }
        String side = legText(leg, "side");
        Set<String> sides = osi.isEmpty() ? SIDES : OPTION_SIDES;
        if (side.isEmpty()) {
            out.add(at + "missing side");
        } else if (!sides.contains(side)) {
            out.add(at + "side must be one of " + new TreeSet<>(sides));
        }
        String ratio = legText(leg, "legRatioQuantity");
        if (ratio.isEmpty()) {
            out.add(at + "missing legRatioQuantity");
        } else if (!REAL_QTY.matcher(ratio).matches()) {
            out.add(at + "legRatioQuantity must be Real Quantity");
        }
    }

    private static String legText(Map<?, ?> leg, String k) {
        Object o = leg.get(k);
        return o == null || CatJson.NULL.equals(o) ? "" : o.toString();
    }

    private void checkQuote(List<String> out) {
        for (String k : List.of("bidPrice", "askPrice")) {
            String px = v(k);
            if (!px.isEmpty() && (!PRICE.matcher(px).matches() || px.startsWith("-"))) {
                out.add(k + " must be Price: Numeric (10,8), greater than or equal to zero");
            }
        }
        for (String k : List.of("bidQty", "askQty")) {
            String q = v(k);
            if (!q.isEmpty() && !WHOLE_QTY.matcher(q).matches()) {
                out.add(k + " must be Whole Quantity: Numeric (12,0)");
            }
        }
        String ts = v("quoteKeyDate");
        if (!ts.isEmpty() && !TIMESTAMP.matcher(ts).matches()) {
            out.add("quoteKeyDate must be 'YYYYMMDD HHMMSS.CCCNNNNNN' or Unsigned nanoseconds");
        }
    }

    private void checkSideDetails(List<String> out) {
        String ind = v("sideDetailsInd");
        boolean buy = !blank("buyDetails");
        boolean sell = !blank("sellDetails");
        if ("BUY".equals(ind) && (!buy || sell)) {
            out.add("sideDetailsInd BUY populates only buyDetails");
        } else if ("SELL".equals(ind) && (buy || !sell)) {
            out.add("sideDetailsInd SELL populates only sellDetails");
        } else if ("NA".equals(ind) && (!buy || !sell)) {
            out.add("sideDetailsInd NA populates buyDetails and sellDetails");
        }
    }

    private void errorRoeidShape(List<String> out) {
        String e = v("errorROEID");
        if (!e.isEmpty() && !ERROR_ROEID.matcher(e).matches()) {
            out.add("errorROEID must be Unsigned");
        }
    }

    private void oneOf(String k, Set<String> allowed, List<String> out) {
        String x = v(k);
        if (!x.isEmpty() && !allowed.contains(x)) {
            out.add(k + " must be one of " + new TreeSet<>(allowed));
        }
    }

    private String v(String k) {
        return fields.getOrDefault(k, "");
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
