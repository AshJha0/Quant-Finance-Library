package com.quantfinlib.reporting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One MiFIR RTS 22 transaction report: all 65 fields in Annex I order, blank
 * where not populated. Immutable; build it with {@link Rts22ReportBuilder}.
 *
 * <p>{@link #validate()} applies the checks this library can make without
 * reference data: mandatory library fields present, LEI check digits on fields 4
 * and 6 (and on 7/16 when they are 20 characters, that is, when they look like an
 * LEI rather than a national client identifier), ISIN check digit on field 41,
 * MIC shape on field 36, currency shapes, the code lists for fields 1, 29 and the
 * true/false fields, and the field 28 timestamp format. It does not check that an
 * LEI is issued and active (needs the GLEIF file), that the ISIN is admitted to
 * trading (needs ESMA FIRDS), or any conditional rule that depends on fields the
 * firm populates.
 *
 * <p>{@link #toCsv()} and {@link #toXml()} are inspection formats. Neither is
 * the ISO 20022 auth.016.001.01 message an ARM or national competent authority
 * accepts, and neither has been checked against the ESMA XSD.
 */
public final class Rts22TransactionReport implements TransactionReport {
    /** Regime label returned by {@link #regime()}. */
    public static final String REGIME = "MiFIR-RTS22";

    private static final Pattern TIMESTAMP =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z");
    private static final Pattern DECIMAL = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final Set<Rts22Field> MANDATORY = Set.of(
            Rts22Field.REPORT_STATUS, Rts22Field.TRANSACTION_REFERENCE_NUMBER,
            Rts22Field.EXECUTING_ENTITY_ID, Rts22Field.INVESTMENT_FIRM_INDICATOR,
            Rts22Field.SUBMITTING_ENTITY_ID, Rts22Field.BUYER_ID, Rts22Field.SELLER_ID,
            Rts22Field.TRANSMISSION_OF_ORDER_INDICATOR, Rts22Field.TRADING_DATE_TIME,
            Rts22Field.TRADING_CAPACITY, Rts22Field.QUANTITY, Rts22Field.PRICE,
            Rts22Field.VENUE, Rts22Field.INSTRUMENT_ID,
            Rts22Field.SECURITIES_FINANCING_TRANSACTION_INDICATOR);

    private final EnumMap<Rts22Field, String> values;

    Rts22TransactionReport(Map<Rts22Field, String> values) {
        this.values = new EnumMap<>(Rts22Field.class);
        for (Rts22Field f : Rts22Field.values()) {
            String v = values.get(f);
            this.values.put(f, v == null ? "" : v);
        }
    }

    /** Value of one field, empty string when not populated. */
    public String get(Rts22Field field) {
        return values.get(field);
    }

    @Override
    public String regime() {
        return REGIME;
    }

    @Override
    public String reference() {
        return get(Rts22Field.TRANSACTION_REFERENCE_NUMBER);
    }

    @Override
    public Map<String, String> fields() {
        Map<String, String> out = new LinkedHashMap<>();
        values.forEach((k, v) -> out.put(k.name(), v));
        return Collections.unmodifiableMap(out);
    }

    @Override
    public List<String> validate() {
        List<String> errs = new ArrayList<>();
        for (Rts22Field f : MANDATORY) {
            if (get(f).isBlank()) {
                errs.add(tag(f) + " is mandatory and blank");
            }
        }
        oneOf(errs, Rts22Field.REPORT_STATUS, "NEWT", "CANC");
        oneOf(errs, Rts22Field.TRADING_CAPACITY, "DEAL", "MTCH", "AOTC");
        oneOf(errs, Rts22Field.INVESTMENT_FIRM_INDICATOR, "true", "false");
        oneOf(errs, Rts22Field.TRANSMISSION_OF_ORDER_INDICATOR, "true", "false");
        oneOf(errs, Rts22Field.SECURITIES_FINANCING_TRANSACTION_INDICATOR, "true", "false");
        oneOf(errs, Rts22Field.DERIVATIVE_NOTIONAL_CHANGE, "INCR", "DECR");
        oneOf(errs, Rts22Field.SHORT_SELLING_INDICATOR, "SESH", "SSEX", "SELL", "UNDI");
        lei(errs, Rts22Field.EXECUTING_ENTITY_ID, true);
        lei(errs, Rts22Field.SUBMITTING_ENTITY_ID, true);
        lei(errs, Rts22Field.BUYER_ID, false);
        lei(errs, Rts22Field.SELLER_ID, false);
        lei(errs, Rts22Field.TRANSMITTING_FIRM_ID_BUYER, true);
        lei(errs, Rts22Field.TRANSMITTING_FIRM_ID_SELLER, true);
        String isin = get(Rts22Field.INSTRUMENT_ID);
        if (!isin.isBlank() && !ReportingIdentifiers.isValidIsin(isin)) {
            errs.add(tag(Rts22Field.INSTRUMENT_ID) + " '" + isin + "' is not a valid ISIN");
        }
        String mic = get(Rts22Field.VENUE);
        if (!mic.isBlank() && !ReportingIdentifiers.isValidMic(mic)) {
            errs.add(tag(Rts22Field.VENUE) + " '" + mic + "' is not a 4-character MIC");
        }
        for (Rts22Field f : List.of(Rts22Field.QUANTITY_CURRENCY, Rts22Field.PRICE_CURRENCY,
                Rts22Field.UP_FRONT_PAYMENT_CURRENCY, Rts22Field.NOTIONAL_CURRENCY_1,
                Rts22Field.NOTIONAL_CURRENCY_2, Rts22Field.STRIKE_PRICE_CURRENCY)) {
            String c = get(f);
            if (!c.isBlank() && !ReportingIdentifiers.isCurrencyShape(c)) {
                errs.add(tag(f) + " '" + c + "' is not a 3-letter currency code");
            }
        }
        for (Rts22Field f : List.of(Rts22Field.BUYER_BRANCH_COUNTRY, Rts22Field.SELLER_BRANCH_COUNTRY,
                Rts22Field.COUNTRY_OF_BRANCH_MEMBERSHIP, Rts22Field.INVESTMENT_DECISION_BRANCH_COUNTRY,
                Rts22Field.EXECUTION_BRANCH_COUNTRY)) {
            String c = get(f);
            if (!c.isBlank() && !ReportingIdentifiers.isCountryShape(c)) {
                errs.add(tag(f) + " '" + c + "' is not a 2-letter country code");
            }
        }
        String ts = get(Rts22Field.TRADING_DATE_TIME);
        if (!ts.isBlank() && !TIMESTAMP.matcher(ts).matches()) {
            errs.add(tag(Rts22Field.TRADING_DATE_TIME) + " '" + ts + "' is not YYYY-MM-DDThh:mm:ss[.f]Z UTC");
        }
        for (Rts22Field f : List.of(Rts22Field.QUANTITY, Rts22Field.PRICE)) {
            String v = get(f);
            if (!v.isBlank() && !DECIMAL.matcher(v).matches()) {
                errs.add(tag(f) + " '" + v + "' is not a plain decimal");
            }
        }
        String q = get(Rts22Field.QUANTITY);
        if (DECIMAL.matcher(q).matches() && Double.parseDouble(q) <= 0) {
            errs.add(tag(Rts22Field.QUANTITY) + " must be positive");
        }
        if (!get(Rts22Field.BUYER_ID).isBlank() && get(Rts22Field.BUYER_ID).equals(get(Rts22Field.SELLER_ID))) {
            errs.add("buyer and seller are the same party");
        }
        return List.copyOf(errs);
    }

    private void oneOf(List<String> errs, Rts22Field f, String... allowed) {
        String v = get(f);
        if (v.isBlank()) {
            return;
        }
        for (String a : allowed) {
            if (a.equals(v)) {
                return;
            }
        }
        errs.add(tag(f) + " '" + v + "' not in " + String.join("/", allowed));
    }

    private void lei(List<String> errs, Rts22Field f, boolean mustBeLei) {
        String v = get(f);
        if (v.isBlank() || (!mustBeLei && v.length() != 20)) {
            return;
        }
        if (!ReportingIdentifiers.isValidLei(v)) {
            errs.add(tag(f) + " '" + v + "' fails the ISO 17442 LEI check");
        }
    }

    private static String tag(Rts22Field f) {
        return "field " + f.number() + " (" + f.name() + ")";
    }

    /** Two-line CSV: header of {@code number_NAME} columns, then the values (RFC 4180 quoting). */
    public String toCsv() {
        StringBuilder h = new StringBuilder();
        StringBuilder v = new StringBuilder();
        for (Rts22Field f : Rts22Field.values()) {
            if (h.length() > 0) {
                h.append(',');
                v.append(',');
            }
            h.append(f.number()).append('_').append(f.name());
            v.append(csv(get(f)));
        }
        return h + "\n" + v + "\n";
    }

    private static String csv(String s) {
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    /** Simple XML rendering of the populated fields; not the ESMA auth.016 schema. */
    public String toXml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!-- Inspection format only: NOT ISO 20022 auth.016, NOT validated against the ESMA XSD -->\n");
        sb.append("<Rts22TransactionReport>\n");
        for (Rts22Field f : Rts22Field.values()) {
            String val = get(f);
            if (!val.isEmpty()) {
                sb.append("  <Field n=\"").append(f.number()).append("\" name=\"").append(f.name())
                        .append("\">").append(xml(val)).append("</Field>\n");
            }
        }
        return sb.append("</Rts22TransactionReport>\n").toString();
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
