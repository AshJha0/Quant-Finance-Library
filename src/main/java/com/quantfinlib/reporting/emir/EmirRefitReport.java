package com.quantfinlib.reporting.emir;

import com.quantfinlib.reporting.ReportingIdentifiers;
import com.quantfinlib.reporting.RuleProvenance;
import com.quantfinlib.reporting.RuleProvenance.Evidence;
import com.quantfinlib.reporting.TransactionReport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One EMIR Refit report built by {@link EmirRefitReportBuilder}. Regime
 * {@code "EMIR_REFIT"}; the reference is the UTI. Not certified against the ESMA schema
 * (see the package javadoc).
 *
 * <p>Every check made by {@link #validate()} is listed, with its citation and evidence
 * class, by {@link #rules()}. None of them has been checked against the published text:
 * neither Commission Implementing Regulation (EU) 2022/1860 nor the ESMA EMIR Refit
 * validation rules are held in the library's source folder, so the citations are written
 * from the Annex layout as remembered, and every rule is either {@code UNCONFIRMED} or a
 * {@code LIBRARY_READING}. {@link #CHECKED_AGAINST} says the same in one line.</p>
 */
public final class EmirRefitReport implements TransactionReport {
    /** Fields required on every report. */
    static final List<String> ALWAYS = List.of("reportingTimestamp", "reportingCounterpartyId",
            "otherCounterpartyId", "entityResponsibleForReporting", "uti", "actionType");

    /** Fields required for NEWT, MODI and CORR. */
    static final List<String> TRADE_TERMS = List.of("executionTimestamp", "effectiveDate", "assetClass",
            "contractType", "notional1", "notionalCurrency1", "direction");

    /** Fields required for VALU. */
    static final List<String> VALUATION = List.of("valuationAmount", "valuationCurrency", "valuationTimestamp",
            "valuationMethod");

    private static final Set<String> ASSET_CLASSES = Set.of("COMM", "CRDT", "CURR", "EQUI", "INTR");
    private static final Set<String> CONTRACT_TYPES = Set.of("CFDS", "FRAS", "FUTR", "FORW", "OPTN",
            "SPDB", "SWAP", "SWPT", "OTHR");
    private static final Set<String> DIRECTIONS = Set.of("BYER", "SLLR", "MAKE", "TAKE");
    private static final Set<String> VAL_METHODS = Set.of("MTMA", "MTMO", "CCPV");

    /** What the checks in {@link #validate()} have been compared with. */
    public static final String CHECKED_AGAINST = "not checked: Implementing Regulation (EU) 2022/1860 "
            + "and the ESMA EMIR Refit validation rules are not on disk; every rule is UNCONFIRMED or LIBRARY_READING";

    private static final String ANNEX = "Implementing Regulation (EU) 2022/1860, Annex, ";

    private static final List<RuleProvenance> RULES = List.of(
            new RuleProvenance("EMIR-R01", "reporting timestamp, counterparties, entity responsible, UTI and action "
                    + "type are present", ANNEX + "Table 1 fields 1.1, 1.3, 1.4, 1.9; Table 2 fields 2.1, 2.151 "
                    + "(numbers indicative)", Evidence.UNCONFIRMED),
            new RuleProvenance("EMIR-R02", "action type is one of NEWT, MODI, CORR, TERM, EROR, REVI, VALU, POSC",
                    ANNEX + "Table 2 field 2.151 (number indicative)", Evidence.UNCONFIRMED),
            new RuleProvenance("EMIR-R03", "event type, when given, is one of the codes in EmirEventType",
                    ANNEX + "Table 2 field 2.152 (number indicative)", Evidence.UNCONFIRMED),
            new RuleProvenance("EMIR-R04", "event type is one this library pairs with the action type (EmirActionType)",
                    "ESMA Guidelines on reporting under EMIR Refit, action/event combinations; a conservative subset, "
                            + "stricter than the ESMA table where unsure", Evidence.LIBRARY_READING),
            new RuleProvenance("EMIR-R05", "NEWT, MODI and CORR carry execution timestamp, effective date, asset "
                    + "class, contract type, notional 1, notional currency 1 and direction",
                    ANNEX + "Table 2 fields 2.42, 2.43, 2.11, 2.10, 2.55, 2.56; Table 1 field 1.17 "
                            + "(numbers indicative)", Evidence.UNCONFIRMED),
            new RuleProvenance("EMIR-R06", "VALU carries valuation amount, currency, timestamp and method",
                    ANNEX + "Table 2 fields 2.21 to 2.24 (numbers indicative)", Evidence.UNCONFIRMED),
            new RuleProvenance("EMIR-R07", "TERM carries an early termination date (an expiration date is accepted "
                    + "instead)", ANNEX + "Table 2 fields 2.44, 2.45; accepting 2.44 alone is the library's reading "
                    + "for a termination at maturity", Evidence.LIBRARY_READING),
            new RuleProvenance("EMIR-R08", "counterparty and entity-responsible identifiers are LEIs with valid check "
                    + "digits, except a natural-person other counterparty", "ISO 17442 (MOD 97-10 check digits); "
                    + ANNEX + "Table 1 fields 1.3, 1.4, 1.9", Evidence.UNCONFIRMED),
            new RuleProvenance("EMIR-R09", "UTI is 1 to 52 upper-case letters or digits",
                    ANNEX + "Table 2 field 2.1 (number indicative)", Evidence.UNCONFIRMED),
            new RuleProvenance("EMIR-R10", "asset class is COMM, CRDT, CURR, EQUI or INTR",
                    ANNEX + "Table 2 field 2.11 (number indicative)", Evidence.UNCONFIRMED),
            new RuleProvenance("EMIR-R11", "contract type is CFDS, FRAS, FUTR, FORW, OPTN, SPDB, SWAP, SWPT or OTHR",
                    ANNEX + "Table 2 field 2.10 (number indicative)", Evidence.UNCONFIRMED),
            new RuleProvenance("EMIR-R12", "direction is BYER, SLLR, MAKE or TAKE",
                    ANNEX + "Table 1 field 1.17 (number indicative)", Evidence.UNCONFIRMED),
            new RuleProvenance("EMIR-R13", "valuation method is MTMA, MTMO or CCPV",
                    ANNEX + "Table 2 field 2.24 (number indicative)", Evidence.UNCONFIRMED),
            new RuleProvenance("EMIR-R14", "notional and valuation currencies have ISO 4217 shape",
                    "ISO 4217; " + ANNEX + "Table 2 fields 2.56, 2.22 (numbers indicative)", Evidence.UNCONFIRMED),
            new RuleProvenance("EMIR-R15", "ISIN, when given, passes the ISO 6166 check digit",
                    "ISO 6166; " + ANNEX + "Table 2 field 2.7 (number indicative)", Evidence.UNCONFIRMED),
            new RuleProvenance("EMIR-R16", "notional 1 is a non-negative decimal of at most 18 digits and 5 decimals",
                    ANNEX + "Table 2 field 2.55 (number indicative); the 18/5 format is not checked",
                    Evidence.UNCONFIRMED));

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
        require(ALWAYS, out);
        @org.jspecify.annotations.Nullable EmirActionType action = parseAction(out);
        @org.jspecify.annotations.Nullable EmirEventType event = parseEvent(out);
        if (action != null) {
            if (!action.accepts(event)) {
                out.add("eventType " + (event == null ? "(none)" : event) + " not accepted with actionType " + action);
            }
            if (action == EmirActionType.NEWT || action == EmirActionType.MODI || action == EmirActionType.CORR) {
                require(TRADE_TERMS, out);
            }
            if (action == EmirActionType.VALU) {
                require(VALUATION, out);
            }
            if (action == EmirActionType.TERM && blank("expirationDate") && blank("earlyTerminationDate")) {
                out.add("TERM needs earlyTerminationDate");
            }
        }
        for (String k : List.of("reportingCounterpartyId", "entityResponsibleForReporting", "otherCounterpartyId")) {
            String v = fields.getOrDefault(k, "");
            boolean natural = "otherCounterpartyId".equals(k) && "false".equals(fields.get("otherCounterpartyIsLegalEntity"));
            if (!v.isEmpty() && !natural && !ReportingIdentifiers.isValidLei(v)) {
                out.add(k + " is not a valid LEI");
            }
        }
        String uti = fields.getOrDefault("uti", "");
        if (!uti.isEmpty() && !EmirRefitReportBuilder.isValidUtiShape(uti)) {
            out.add("uti must be 1-52 upper-case alphanumerics");
        }
        oneOf("assetClass", ASSET_CLASSES, out);
        oneOf("contractType", CONTRACT_TYPES, out);
        oneOf("direction", DIRECTIONS, out);
        oneOf("valuationMethod", VAL_METHODS, out);
        for (String k : List.of("notionalCurrency1", "valuationCurrency")) {
            String v = fields.getOrDefault(k, "");
            if (!v.isEmpty() && !ReportingIdentifiers.isCurrencyShape(v)) {
                out.add(k + " must be an ISO 4217 code");
            }
        }
        String isin = fields.getOrDefault("isin", "");
        if (!isin.isEmpty() && !ReportingIdentifiers.isValidIsin(isin)) {
            out.add("isin check digit fails");
        }
        String n = fields.getOrDefault("notional1", "");
        if (!n.isEmpty() && !n.matches("\\d{1,18}(\\.\\d{1,5})?")) {
            out.add("notional1 must be a non-negative decimal (18 digits, 5 decimals)");
        }
        return out;
    }

    private @org.jspecify.annotations.Nullable EmirActionType parseAction(List<String> out) {
        String a = fields.getOrDefault("actionType", "");
        if (a.isEmpty()) {
            return null;
        }
        try {
            return EmirActionType.valueOf(a);
        } catch (IllegalArgumentException e) {
            out.add("unknown actionType " + a);
            return null;
        }
    }

    private @org.jspecify.annotations.Nullable EmirEventType parseEvent(List<String> out) {
        String a = fields.getOrDefault("eventType", "");
        if (a.isEmpty()) {
            return null;
        }
        try {
            return EmirEventType.valueOf(a);
        } catch (IllegalArgumentException e) {
            out.add("unknown eventType " + a);
            return null;
        }
    }

    private void oneOf(String k, Set<String> allowed, List<String> out) {
        String v = fields.getOrDefault(k, "");
        if (!v.isEmpty() && !allowed.contains(v)) {
            out.add(k + " must be one of " + allowed);
        }
    }

    private void require(List<String> keys, List<String> out) {
        for (String k : keys) {
            if (blank(k)) {
                out.add("missing " + k);
            }
        }
    }

    private boolean blank(String k) {
        String v = fields.get(k);
        return v == null || v.isBlank();
    }
}
