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
 * Industry Members v4.1.0 r14 (2026-02-13); see {@link #CHECKED_AGAINST}. Up to 3.0 only the four
 * equity event types and only single-record rules were checked; linkage, reference data
 * (symbol master, IMID list, FDID), late-reporting and option or quote events were not.
 * Since 3.1.0 the new-quote (MENQ), new option order (MONO), option route (MOOR) and new
 * option quote (MONQ) types are also checked (rules CAT-R13 to CAT-R16), and
 * {@link CatLinkageChecker} checks linkage keys across a batch of events.</p>
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
     * eventTimestamp and manualFlag of Tables 16, 18, 48 and 52). Of these, actionType,
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
            + "Industry Members v4.1.0 r14 (2026-02-13), sections 2.5, 4.1, 4.3, 4.11.1, 4.12.1, 7.6 and "
            + "Appendix G, plus Tables 39, 62, 64 and 85 (MENQ, MONO, MOOR, MONQ) since 3.1.0; not certified";

    private static final String SPEC = "CAT IM Technical Specifications v4.1.0 r14, ";

    private static final List<RuleProvenance> RULES = List.of(
            new RuleProvenance("CAT-R01", "a DEL record carries only actionType plus errorROEID or firmROEID; no "
                    + "other field is checked", SPEC + "section 7.6.3 and Table 165 (worked examples printed)",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R02", "actionType, firmROEID, type, symbol, eventTimestamp and the type's "
                    + "required non-Boolean fields are present", SPEC + "Tables 16, 18, 48, 52 Include Key 'R'; "
                    + "Booleans left out per Table 3 (absent JSON Boolean = false)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R03", "actionType is NEW, COR, RPR or DEL", SPEC + "Appendix G, actionType",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R04", "errorROEID is required with RPR, blank with NEW, and an Unsigned when "
                    + "given; COR does not need it", SPEC + "Table 16 seq 2, section 7.6.1 rule 2, section 7.6.2 "
                    + "(worked COR and RPR examples printed)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R05", "side is B, SL, SS or SX (equity)", SPEC + "Appendix G, side",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R06", "CATReporterIMID, when given, is Alphanumeric (7)",
                    SPEC + "Table 3, CAT Reporter IMID; Table 16 seq 5 'O'", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R07", "quantity is Real Quantity: Numeric (12,6), not negative, no trailing "
                    + "decimal zeros", SPEC + "Table 3, Real Quantity", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R08", "price is Price: Numeric (10,8) with a digit before any decimal point; "
                    + "on MENO and MEOR it is not negative, required when orderType is LMT and blank when MKT",
                    SPEC + "Table 3, Price; Table 16 seq 19, Table 18 seq 20", Evidence.PUBLISHED_REFERENCE),
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
                    + "is B or S", SPEC + "Tables 62, 64, 85 seq 8 (optionID 'R'); Appendix G, side (Option "
                    + "column)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R14", "optionID is a 21-character OSI symbol, optionally prefixed by % for "
                    + "FLEX Percent options", SPEC + "Table 62 seq 8 ('21-character OSI Symbol', '%' prefix); the "
                    + "OSI layout (6+6+1+8) is the library's reading, the OSI standard is not on disk",
                    Evidence.LIBRARY_READING),
            new RuleProvenance("CAT-R15", "on MONO and MOOR, price is not negative, required when orderType is "
                    + "LMT and blank when MKT", SPEC + "Table 62 seq 17, Table 64 seq 20", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("CAT-R16", "on quote events bidPrice and askPrice are Price and not negative; bidQty "
                    + "and askQty are Whole Quantity (Numeric (12,0)); quoteKeyDate is a Timestamp",
                    SPEC + "Table 39 seq 17-20, Table 85 seq 11-14, Table 3 Whole Quantity",
                    Evidence.PUBLISHED_REFERENCE));

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
            if (t.isOption() && "symbol".equals(k)) {
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
        oneOf("fulfillmentLinkType", LINK_TYPES, out);
        String imid = v("CATReporterIMID");
        if (!imid.isEmpty() && !IMID.matcher(imid).matches()) {
            out.add("CATReporterIMID must be Alphanumeric (7)");
        }
        String qty = v("quantity");
        if (!qty.isEmpty() && !REAL_QTY.matcher(qty).matches()) {
            out.add("quantity must be Real Quantity: Numeric (12,6), not negative, no trailing zeros");
        }
        checkPrice(t, out);
        if (t == CatEventType.MEOT) {
            checkSideDetails(out);
        }
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
