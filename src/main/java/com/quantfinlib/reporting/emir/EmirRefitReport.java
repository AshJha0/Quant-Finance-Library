package com.quantfinlib.reporting.emir;

import com.quantfinlib.reporting.ReportingIdentifiers;
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

    private final Map<String, String> fields;

    EmirRefitReport(Map<String, String> fields) {
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
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
