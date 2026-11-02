package com.quantfinlib.reporting.emir;

import com.quantfinlib.reporting.ReportingIdentifiers;
import com.quantfinlib.reporting.RuleProvenance;
import com.quantfinlib.reporting.RuleProvenance.Evidence;
import com.quantfinlib.reporting.TransactionReport;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One EMIR Refit report built by {@link EmirRefitReportBuilder}. Regime
 * {@code "EMIR_REFIT"}; the reference is the UTI. Not certified (see the package javadoc).
 *
 * <p>{@link #validate()} applies the single-report, data-only part of the ESMA EMIR Refit
 * validation rules, workbook ESMA74-362-2683, sheet {@code Validations_Cpty_derivative}
 * (trade-level columns L to S give M/C/O/- per action type, column AC the conditions,
 * column AD the error codes). Each message starts with the ESMA error code it implements,
 * or with this library's rule id where the check combines several codes. {@link #rules()}
 * lists every rule with its sheet row and evidence class. Field numbers and formats were
 * checked against the Annex of Implementing Regulation (EU) 2022/1860 (Tables 1 and 2). Rules that need earlier reports
 * for the same UTI, the trade repository's receipt time, GLEIF status or the official
 * ISO 4217 list are not checked; the package javadoc lists them.</p>
 *
 * <p>Worked example from the workbook (row 81, EMIR-VR-2055-01): the default notional
 * {@code 9999999999999999999999999} (25 digits) is accepted; 26 digits is not.</p>
 */
public final class EmirRefitReport implements TransactionReport {
    /** Fields mandatory for every action type at trade level (rows 5, 6, 8, 12, 13, 26, 184, 186). */
    static final List<String> ALWAYS = List.of("reportingTimestamp", "reportSubmittingEntityId",
            "reportingCounterpartyId", "otherCounterpartyIsLegalEntity", "otherCounterpartyId", "uti",
            "actionType", "eventDate");

    /** Fields mandatory for NEWT, MODI, CORR and POSC (rows 35, 36, 67, 68, 81, 82). */
    static final List<String> TRADE_TERMS = List.of("executionTimestamp", "effectiveDate", "assetClass",
            "contractType", "notional1", "notionalCurrency1");

    /** Fields mandatory for VALU (rows 46 to 49, column N). */
    static final List<String> VALUATION = List.of("valuationAmount", "valuationCurrency", "valuationTimestamp",
            "valuationMethod");

    /** Field numbers of the keys used here, for messages. */
    private static final Map<String, String> NUM = Map.ofEntries(Map.entry("reportingTimestamp", "1.1"),
            Map.entry("reportSubmittingEntityId", "1.2"), Map.entry("entityResponsibleForReporting", "1.3"),
            Map.entry("reportingCounterpartyId", "1.4"), Map.entry("otherCounterpartyIsLegalEntity", "1.8"),
            Map.entry("otherCounterpartyId", "1.9"), Map.entry("direction", "1.17"),
            Map.entry("directionLeg1", "1.18"), Map.entry("directionLeg2", "1.19"), Map.entry("uti", "2.1"),
            Map.entry("isin", "2.7"), Map.entry("contractType", "2.10"), Map.entry("assetClass", "2.11"),
            Map.entry("valuationAmount", "2.21"), Map.entry("valuationCurrency", "2.22"),
            Map.entry("valuationTimestamp", "2.23"), Map.entry("valuationMethod", "2.24"),
            Map.entry("executionTimestamp", "2.42"), Map.entry("effectiveDate", "2.43"),
            Map.entry("expirationDate", "2.44"), Map.entry("earlyTerminationDate", "2.45"),
            Map.entry("notional1", "2.55"), Map.entry("notionalCurrency1", "2.56"),
            Map.entry("actionType", "2.151"), Map.entry("eventDate", "2.153"));

    private static final Set<String> ASSET_CLASSES = Set.of("COMM", "CRDT", "CURR", "EQUI", "INTR");
    private static final Set<String> CONTRACT_TYPES = Set.of("CFDS", "FRAS", "FUTR", "FORW", "OPTN",
            "SPDB", "SWAP", "SWPT", "OTHR");
    /** Rows 47 and 82, condition 2. */
    private static final Set<String> SPECIAL_CURRENCIES = Set.of("XAG", "XAU", "XBA", "XBB", "XBC", "XBD",
            "XDR", "XEU", "XFU", "XPD", "XPT", "XXX");
    private static final Set<EmirActionType> TRADE_ACTIONS = EnumSet.of(EmirActionType.NEWT, EmirActionType.MODI,
            EmirActionType.CORR, EmirActionType.POSC);
    /** Row 70: 2.45 is '-' (not applicable) for these actions. */
    private static final Set<EmirActionType> NO_EARLY_TERM = EnumSet.of(EmirActionType.NEWT, EmirActionType.MODI,
            EmirActionType.VALU, EmirActionType.EROR, EmirActionType.POSC);
    private static final Pattern TS = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
    private static final Pattern UTI_ANY = Pattern.compile("[A-Z0-9]([A-Z0-9:.\\-_]{0,50}[A-Z0-9])?");
    private static final Pattern UTI_STRICT = Pattern.compile("[A-Z0-9]{1,52}");
    private static final Pattern CLIENT_CODE = Pattern.compile("[A-Za-z0-9]{1,72}");
    private static final LocalDate MIN_REPORTING = LocalDate.of(2024, 4, 29);
    private static final LocalDate MIN_1950 = LocalDate.of(1950, 1, 1);
    private static final LocalDate MIN_2014 = LocalDate.of(2014, 2, 12);

    /** What the checks in {@link #validate()} have been compared with. */
    public static final String CHECKED_AGAINST = "checked against ESMA74-362-2683 EMIR Refit validation rules "
            + "(xlsx, sheet Validations_Cpty_derivative; no version or publication date printed, file last "
            + "modified 2025-10-08) and Commission Implementing Regulation (EU) 2022/1860 of 10 June 2022, "
            + "Annex Tables 1 and 2 (field numbers and formats; OJ L 262, 7.10.2022); not certified";

    /**
     * Source prefix. Field numbers, names and formats were checked against the Annex of
     * Implementing Regulation (EU) 2022/1860 (Table 1 counterparty data, Table 2 common data);
     * conditions and error codes against the ESMA workbook.
     */
    private static final String WB = "Implementing Regulation (EU) 2022/1860, Annex Tables 1-2 (field numbers and "
            + "formats); ESMA74-362-2683, sheet Validations_Cpty_derivative, ";

    private static final List<RuleProvenance> RULES = List.of(
            new RuleProvenance("EMIR-R01", "fields 1.1, 1.2, 1.4, 1.8, 1.9, 2.1, 2.151 and 2.153 are present for "
                    + "every action type", WB + "rows 5, 6, 8, 12, 13, 26, 184, 186, columns L-S all 'M'",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-R02", "action type is NEWT, MODI, CORR, TERM, EROR, REVI, VALU or POSC",
                    WB + "row 184, EMIR-VR-2151-01", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-R03", "event type, when given, is TRAD, NOVA, COMP, ETRM, CLRG, EXER, ALOC, "
                    + "CREV, CORP, INCP or UPDT (PTNG is not in the list)", WB + "row 185, EMIR-VR-2152-01",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-R04", "event type is required with NEWT and TERM, optional with MODI, blank "
                    + "with CORR, EROR, REVI, VALU and POSC, and in the action's allowed set",
                    WB + "row 185, EMIR-VR-2152-02 to -05; columns L-S", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-R05", "NEWT, MODI, CORR and POSC carry 2.42, 2.43, 2.10, 2.11, 2.55 and 2.56",
                    WB + "rows 35, 36, 67, 68, 81, 82, columns L, M, O, S 'M'", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-R06", "for NEWT, MODI, CORR and POSC either 1.17 or both 1.18 and 1.19 are "
                    + "given; 1.17 is BYER or SLLR; 1.18 and 1.19 are MAKE or TAKE and differ",
                    WB + "rows 21-23, EMIR-VR-1017-01/02, EMIR-VR-1018-02/03, EMIR-VR-1019-02",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-R07", "VALU carries 2.21 to 2.24; 2.22 and 2.24 are blank when 2.21 is blank",
                    WB + "rows 46-49 column N 'M'; EMIR-VR-2022-03, EMIR-VR-2024-03", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-R08", "2.45 is required with TERM and blank with NEWT, MODI, VALU, EROR, POSC; "
                    + "when given it is on or after the date of 2.42 and on or before the date of 1.1; with TERM "
                    + "2.153 equals 2.45", WB + "row 70 columns L-S, EMIR-VR-2045-02, EMIR-VR-2045-05; row 186 "
                    + "EMIR-VR-2153-07", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-R09", "1.2, 1.3, 1.4 and (when 1.8 is true) 1.9 are 20-character LEIs with valid "
                    + "ISO 17442 check digits", WB + "rows 6-8, 13 column G (20 alphanumeric LEI); the check-digit "
                    + "test is the library's necessary condition for 'included in the GLEIF database' "
                    + "(EMIR-VR-1002-01 etc.), which needs reference data; ISO 17442 itself is not on disk",
                    Evidence.LIBRARY_READING),
            new RuleProvenance("EMIR-R10", "1.8 is true or false; with false, 1.9 is up to 72 alphanumerics and, for "
                    + "NEWT and POSC, starts with the LEI in 1.4; 1.4 and 1.9 differ unless 1.9 is the CCP in 2.33",
                    WB + "rows 12-13, EMIR-VR-1008-01, EMIR-VR-1009-05/06/07; true/false accepted in any case "
                    + "(the workbook prints TRUE/FALSE, XML booleans are lower case)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-R11", "UTI is up to 52 characters A-Z, 0-9 and ':', '.', '-', '_' (not first or "
                    + "last); for NEWT and POSC only A-Z and 0-9", WB + "row 26, EMIR-VR-2001-01/02",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-R12", "asset class is COMM, CRDT, CURR, EQUI or INTR",
                    WB + "row 36, EMIR-VR-2011-01", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-R13", "contract type is CFDS, FRAS, FUTR, FORW, OPTN, SPDB, SWAP, SWPT or OTHR",
                    WB + "row 35, EMIR-VR-2010-01", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-R14", "valuation method is CCPV when 2.31 is Y, MTMA or MTMO when 2.31 is N, "
                    + "one of the three when 2.31 is not given", WB + "row 49, EMIR-VR-2024-01/02",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-R15", "2.56 and 2.22 are three upper-case letters and not a special code (XAU, "
                    + "XXX, ...); membership of the official ISO 4217 list is not checked",
                    WB + "rows 47 and 82, EMIR-VR-2022-01/02, EMIR-VR-2056-01/02", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-R16", "ISIN is 12 alphanumerics with a valid ISO 6166 check digit",
                    WB + "row 32, EMIR-VR-2007-02 (12 characters including a check digit); the check-digit "
                    + "algorithm is ISO 6166, not on disk", Evidence.LIBRARY_READING),
            new RuleProvenance("EMIR-R17", "2.55 is >= 0 with at most 25 digits, at most 5 of them decimals",
                    WB + "row 81, EMIR-VR-2055-01 (default 25 nines accepted)", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-R18", "2.21 is signed with at most 25 digits, at most 5 of them decimals",
                    WB + "row 46, EMIR-VR-2021-01", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-R19", "1.1, 2.23 and 2.42 are YYYY-MM-DDThh:mm:ssZ; 2.43, 2.44, 2.45 and 2.153 "
                    + "are YYYY-MM-DD", WB + "rows 5, 48, 67-70, 186, EMIR-VR-1001-01, -2023-01, -2042-01, "
                    + "-2043-01, -2044-01, -2045-01, -2153-01", Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-R20", "1.1 is on or after 2024-04-29 and not before 2.42; 2.42 and 2.43 are on "
                    + "or after 1950-01-01; 2.44, 2.45 and 2.153 on or after 2014-02-12",
                    WB + "EMIR-VR-1001-04/05, -2042-02, -2043-02, -2044-03, -2045-04, -2153-03",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-R21", "for NEWT, MODI, CORR and POSC, 2.44 is required unless 2.10 is CFDS; 2.44 "
                    + "is not before 2.43 or the date of 2.42", WB + "row 69, EMIR-VR-2044-01/02",
                    Evidence.PUBLISHED_REFERENCE),
            new RuleProvenance("EMIR-R22", "2.153 is not after the date of 1.1 and not before the date of 2.42; equals "
                    + "the date of 2.42 for NEWT and POSC and the date of 1.1 for REVI and EROR; not after 2.44 or "
                    + "2.45 except for REVI", WB + "row 186, EMIR-VR-2153-02/04/05/08",
                    Evidence.PUBLISHED_REFERENCE));

    private final Map<String, String> fields;

    EmirRefitReport(Map<String, String> fields) {
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
        return "EMIR_REFIT";
    }

    @Override
    public String reference() {
        return fields.getOrDefault("uti", "");
    }

    @Override
    public Map<String, String> fields() {
        return fields;
    }

    @Override
    public List<String> validate() {
        List<String> out = new ArrayList<>();
        require("EMIR-R01", ALWAYS, out);
        @Nullable EmirActionType action = parseAction(out);
        @Nullable EmirEventType event = parseEvent(out);
        if (action != null) {
            checkEvent(action, event, out);
            if (TRADE_ACTIONS.contains(action)) {
                require("EMIR-R05", TRADE_TERMS, out);
                if (blank("direction") && (blank("directionLeg1") || blank("directionLeg2"))) {
                    out.add("EMIR-VR-1017-01: give field 1.17 direction or both 1.18 and 1.19");
                }
                if (blank("expirationDate") && !"CFDS".equals(v("contractType"))) {
                    out.add("EMIR-VR-2044-01: field 2.44 expirationDate is required unless 2.10 is CFDS");
                }
            }
            if (action == EmirActionType.VALU) {
                require("EMIR-R07", VALUATION, out);
            }
            if (action == EmirActionType.TERM && blank("earlyTerminationDate")) {
                out.add("EMIR-R08: TERM needs field 2.45 earlyTerminationDate");
            }
            if (NO_EARLY_TERM.contains(action) && !blank("earlyTerminationDate")) {
                out.add("EMIR-R08: field 2.45 earlyTerminationDate must be blank with " + action);
            }
        }
        checkParties(action, out);
        checkCodes(out);
        decimal("notional1", false, "EMIR-VR-2055-01", out);
        decimal("valuationAmount", true, "EMIR-VR-2021-01", out);
        checkDates(action, out);
        return out;
    }

    private static void checkEvent(EmirActionType action, @Nullable EmirEventType event, List<String> out) {
        if (event == EmirEventType.PTNG) {
            out.add("EMIR-VR-2152-01: eventType PTNG is not in the ESMA list");
        } else if (!action.accepts(event)) {
            String code = switch (action) {
                case NEWT -> "EMIR-VR-2152-02";
                case MODI -> "EMIR-VR-2152-03";
                case TERM -> "EMIR-VR-2152-04";
                default -> "EMIR-VR-2152-05";
            };
            out.add(code + ": eventType " + (event == null ? "(none)" : event.name())
                    + " not accepted with actionType " + action);
        }
    }

    private void checkParties(@Nullable EmirActionType action, List<String> out) {
        String legal = v("otherCounterpartyIsLegalEntity");
        boolean natural = "false".equalsIgnoreCase(legal);
        if (!legal.isEmpty() && !natural && !"true".equalsIgnoreCase(legal)) {
            out.add("EMIR-VR-1008-01: field 1.8 must be true or false");
        }
        lei("reportSubmittingEntityId", "EMIR-VR-1002-01", out);
        lei("entityResponsibleForReporting", "EMIR-VR-1003-02", out);
        lei("reportingCounterpartyId", "EMIR-VR-1004-01", out);
        String cp1 = v("reportingCounterpartyId");
        String cp2 = v("otherCounterpartyId");
        if (!natural) {
            lei("otherCounterpartyId", "EMIR-VR-1009-01", out);
            if (!cp2.isEmpty() && cp2.equals(cp1) && !cp2.equals(v("centralCounterparty"))) {
                out.add("EMIR-VR-1009-05: fields 1.4 and 1.9 hold the same LEI and it is not the CCP in 2.33");
            }
        } else if (!cp2.isEmpty()) {
            if (!CLIENT_CODE.matcher(cp2).matches()) {
                out.add("EMIR-VR-1009-06: field 1.9 client code must be up to 72 alphanumerics");
            }
            if ((action == EmirActionType.NEWT || action == EmirActionType.POSC) && !cp1.isEmpty()
                    && !cp2.startsWith(cp1)) {
                out.add("EMIR-VR-1009-07: field 1.9 client code must start with the LEI in field 1.4");
            }
        }
        String uti = v("uti");
        if (!uti.isEmpty()) {
            boolean strict = action == EmirActionType.NEWT || action == EmirActionType.POSC;
            if (!UTI_ANY.matcher(uti).matches()) {
                out.add("EMIR-VR-2001-01: uti must be up to 52 of A-Z 0-9 : . - _ (no special first or last)");
            } else if (strict && !UTI_STRICT.matcher(uti).matches()) {
                out.add("EMIR-VR-2001-02: uti for " + action + " may contain only A-Z and 0-9");
            }
        }
    }

    private void checkCodes(List<String> out) {
        oneOf("assetClass", ASSET_CLASSES, "EMIR-VR-2011-01", out);
        oneOf("contractType", CONTRACT_TYPES, "EMIR-VR-2010-01", out);
        oneOf("direction", Set.of("BYER", "SLLR"), "EMIR-VR-1017-02", out);
        oneOf("directionLeg1", Set.of("MAKE", "TAKE"), "EMIR-VR-1018-02", out);
        oneOf("directionLeg2", Set.of("MAKE", "TAKE"), "EMIR-VR-1019-02", out);
        if (!blank("directionLeg1") && v("directionLeg1").equals(v("directionLeg2"))) {
            out.add("EMIR-VR-1018-03: fields 1.18 and 1.19 must differ");
        }
        String method = v("valuationMethod");
        if (!method.isEmpty()) {
            String cleared = v("cleared");
            Set<String> allowed = "Y".equals(cleared) ? Set.of("CCPV")
                    : "N".equals(cleared) ? Set.of("MTMA", "MTMO") : Set.of("CCPV", "MTMA", "MTMO");
            if (!allowed.contains(method)) {
                out.add(("Y".equals(cleared) ? "EMIR-VR-2024-01" : "EMIR-VR-2024-02") + ": valuationMethod "
                        + method + " not allowed with field 2.31 '" + cleared + "'");
            }
        }
        if (blank("valuationAmount")) {
            if (!blank("valuationCurrency")) {
                out.add("EMIR-VR-2022-03: field 2.22 must be blank when 2.21 is blank");
            }
            if (!method.isEmpty()) {
                out.add("EMIR-VR-2024-03: field 2.24 must be blank when 2.21 is blank");
            }
        }
        currency("notionalCurrency1", "EMIR-VR-2056", out);
        currency("valuationCurrency", "EMIR-VR-2022", out);
        String isin = v("isin");
        if (!isin.isEmpty() && !ReportingIdentifiers.isValidIsin(isin)) {
            out.add("EMIR-VR-2007-02: isin must be 12 alphanumerics with a valid check digit");
        }
    }

    private void checkDates(@Nullable EmirActionType action, List<String> out) {
        @Nullable LocalDate rep = timestamp("reportingTimestamp", "EMIR-VR-1001-01", out);
        @Nullable LocalDate exe = timestamp("executionTimestamp", "EMIR-VR-2042-01", out);
        timestamp("valuationTimestamp", "EMIR-VR-2023-01", out);
        @Nullable LocalDate eff = date("effectiveDate", "EMIR-VR-2043-01", out);
        @Nullable LocalDate exp = date("expirationDate", "EMIR-VR-2044-01", out);
        @Nullable LocalDate etd = date("earlyTerminationDate", "EMIR-VR-2045-01", out);
        @Nullable LocalDate evd = date("eventDate", "EMIR-VR-2153-01", out);
        before(rep, MIN_REPORTING, "EMIR-VR-1001-05: field 1.1 must be on or after 2024-04-29", out);
        if (rep != null && exe != null && v("reportingTimestamp").compareTo(v("executionTimestamp")) < 0) {
            out.add("EMIR-VR-1001-04: field 1.1 must not be before field 2.42");
        }
        before(exe, MIN_1950, "EMIR-VR-2042-02: field 2.42 must be on or after 1950-01-01", out);
        before(eff, MIN_1950, "EMIR-VR-2043-02: field 2.43 must be on or after 1950-01-01", out);
        before(exp, MIN_2014, "EMIR-VR-2044-03: field 2.44 must be on or after 2014-02-12", out);
        before(etd, MIN_2014, "EMIR-VR-2045-04: field 2.45 must be on or after 2014-02-12", out);
        before(evd, MIN_2014, "EMIR-VR-2153-03: field 2.153 must be on or after 2014-02-12", out);
        if (exp != null && ((eff != null && exp.isBefore(eff)) || (exe != null && exp.isBefore(exe)))) {
            out.add("EMIR-VR-2044-02: field 2.44 must not be before 2.42 or 2.43");
        }
        before(etd, exe, "EMIR-VR-2045-02: field 2.45 must not be before the date of 2.42", out);
        before(rep, etd, "EMIR-VR-2045-05: field 2.45 must not be after the date of 1.1", out);
        if (evd == null || action == null) {
            return;
        }
        before(rep, evd, "EMIR-VR-2153-04: field 2.153 must not be after the date of 1.1", out);
        before(evd, exe, "EMIR-VR-2153-02: field 2.153 must not be before the date of 2.42", out);
        if ((action == EmirActionType.NEWT || action == EmirActionType.POSC) && exe != null && !evd.equals(exe)) {
            out.add("EMIR-VR-2153-02: for " + action + " field 2.153 must equal the date of 2.42");
        }
        if ((action == EmirActionType.REVI || action == EmirActionType.EROR) && rep != null && !evd.equals(rep)) {
            out.add("EMIR-VR-2153-05: for " + action + " field 2.153 must equal the date of 1.1");
        }
        if (action == EmirActionType.TERM && etd != null && !evd.equals(etd)) {
            out.add("EMIR-VR-2153-07: for TERM field 2.153 must equal field 2.45");
        }
        if (action != EmirActionType.REVI) {
            before(exp, evd, "EMIR-VR-2153-08: field 2.153 must not be after 2.44", out);
            before(etd, evd, "EMIR-VR-2153-08: field 2.153 must not be after 2.45", out);
        }
    }

    /** Adds {@code msg} when both dates are known and {@code d} is before {@code min}. */
    private static void before(@Nullable LocalDate d, @Nullable LocalDate min, String msg, List<String> out) {
        if (d != null && min != null && d.isBefore(min)) {
            out.add(msg);
        }
    }

    private @Nullable LocalDate timestamp(String k, String code, List<String> out) {
        String s = v(k);
        if (s.isBlank()) {
            return null;
        }
        if (!TS.matcher(s).matches()) {
            out.add(code + ": field " + NUM.get(k) + " " + k + " must be YYYY-MM-DDThh:mm:ssZ");
            return null;
        }
        return parse(s.substring(0, 10), k, code, out);
    }

    private @Nullable LocalDate date(String k, String code, List<String> out) {
        String s = v(k);
        if (s.isBlank()) {
            return null;
        }
        if (s.length() != 10) {
            out.add(code + ": field " + NUM.get(k) + " " + k + " must be YYYY-MM-DD");
            return null;
        }
        return parse(s, k, code, out);
    }

    private static @Nullable LocalDate parse(String s, String k, String code, List<String> out) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            out.add(code + ": field " + NUM.get(k) + " " + k + " is not a valid date");
            return null;
        }
    }

    private void decimal(String k, boolean signed, String code, List<String> out) {
        String s = v(k);
        if (s.isEmpty()) {
            return;
        }
        String body = signed && s.startsWith("-") ? s.substring(1) : s;
        int dot = body.indexOf('.');
        String intPart = dot < 0 ? body : body.substring(0, dot);
        String frac = dot < 0 ? "" : body.substring(dot + 1);
        boolean ok = !intPart.isEmpty() && intPart.chars().allMatch(c -> c >= '0' && c <= '9')
                && frac.chars().allMatch(c -> c >= '0' && c <= '9') && (dot < 0 || !frac.isEmpty())
                && frac.length() <= 5 && intPart.length() + frac.length() <= 25;
        if (!ok) {
            out.add(code + ": field " + NUM.get(k) + " " + k + " must be " + (signed ? "a signed" : "a non-negative")
                    + " decimal of at most 25 digits, 5 decimals");
        }
    }

    private void currency(String k, String code, List<String> out) {
        String s = v(k);
        if (s.isEmpty()) {
            return;
        }
        if (!ReportingIdentifiers.isCurrencyShape(s)) {
            out.add(code + "-01: field " + NUM.get(k) + " " + k + " must be three upper-case letters");
        } else if (SPECIAL_CURRENCIES.contains(s)) {
            out.add(code + "-02: field " + NUM.get(k) + " " + k + " special code " + s + " is not allowed");
        }
    }

    private void lei(String k, String code, List<String> out) {
        String s = v(k);
        if (!s.isEmpty() && !ReportingIdentifiers.isValidLei(s)) {
            out.add(code + ": field " + NUM.get(k) + " " + k + " is not a valid LEI");
        }
    }

    private @Nullable EmirActionType parseAction(List<String> out) {
        String a = v("actionType");
        if (a.isEmpty()) {
            return null;
        }
        try {
            return EmirActionType.valueOf(a);
        } catch (IllegalArgumentException e) {
            out.add("EMIR-VR-2151-01: unknown actionType " + a);
            return null;
        }
    }

    private @Nullable EmirEventType parseEvent(List<String> out) {
        String a = v("eventType");
        if (a.isEmpty()) {
            return null;
        }
        try {
            return EmirEventType.valueOf(a);
        } catch (IllegalArgumentException e) {
            out.add("EMIR-VR-2152-01: unknown eventType " + a);
            return null;
        }
    }

    private void oneOf(String k, Set<String> allowed, String code, List<String> out) {
        String s = v(k);
        if (!s.isEmpty() && !allowed.contains(s)) {
            out.add(code + ": field " + NUM.get(k) + " " + k + " must be one of " + new TreeSet<>(allowed));
        }
    }

    private void require(String rule, List<String> keys, List<String> out) {
        for (String k : keys) {
            if (blank(k)) {
                out.add(rule + ": missing field " + NUM.get(k) + " " + k);
            }
        }
    }

    private String v(String k) {
        return fields.getOrDefault(k, "");
    }

    private boolean blank(String k) {
        return v(k).isBlank();
    }
}
