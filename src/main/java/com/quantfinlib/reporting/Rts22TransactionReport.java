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
 * and 6 (and on 7/16 when they are 20 characters ending in two digits, that is, when
 * they look like an LEI rather than a CONCAT or national client identifier), ISIN check digit on field 41,
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
            Rts22Field.VENUE,
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
        // Field 41 may be blank when the instrument is described in fields 42-56: auth.016
        // FinInstrm is a choice of Id or Othr, and Othr carries the ISIN only optionally.
        if (get(Rts22Field.INSTRUMENT_ID).isBlank() && get(Rts22Field.INSTRUMENT_FULL_NAME).isBlank()
                && get(Rts22Field.INSTRUMENT_CLASSIFICATION).isBlank()) {
            errs.add(tag(Rts22Field.INSTRUMENT_ID)
                    + " is mandatory and blank unless fields 42-56 describe the instrument");
        }
        oneOf(errs, Rts22Field.REPORT_STATUS, "NEWT", "CANC");
        oneOf(errs, Rts22Field.TRADING_CAPACITY, "DEAL", "MTCH", "AOTC");
        oneOf(errs, Rts22Field.INVESTMENT_FIRM_INDICATOR, "true", "false");
        oneOf(errs, Rts22Field.TRANSMISSION_OF_ORDER_INDICATOR, "true", "false");
        oneOf(errs, Rts22Field.SECURITIES_FINANCING_TRANSACTION_INDICATOR, "true", "false");
        oneOf(errs, Rts22Field.DERIVATIVE_NOTIONAL_CHANGE, "INCR", "DECR");
        oneOf(errs, Rts22Field.SHORT_SELLING_INDICATOR, "SESH", "SSEX", "SELL", "UNDI");
        oneOf(errs, Rts22Field.COMMODITY_DERIVATIVE_INDICATOR, "true", "false");
        oneOf(errs, Rts22Field.OPTION_TYPE, "PUTO", "CALL", "OTHR");
        oneOf(errs, Rts22Field.OPTION_EXERCISE_STYLE, "EURO", "AMER", "ASIA", "BERM", "OTHR");
        oneOf(errs, Rts22Field.DELIVERY_TYPE, "PHYS", "CASH", "OPTL");
        allOf(errs, Rts22Field.WAIVER_INDICATOR, "RFPT", "NLIQ", "OILQ", "PRIC", "SIZE", "ILQD");
        allOf(errs, Rts22Field.OTC_POST_TRADE_INDICATOR, "BENC", "ACTX", "LRGS", "ILQD", "SIZE", "CANC",
                "AMND", "SDIV", "RPRI", "DUPL", "TNCP", "TPAC", "XFPH");
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

    private void allOf(List<String> errs, Rts22Field f, String... allowed) {
        for (String v : Auth016Writer.list(get(f))) {
            if (!List.of(allowed).contains(v)) {
                errs.add(tag(f) + " '" + v + "' not in " + String.join("/", allowed));
            }
        }
    }

    /**
     * ESMA validation rules for MiFIR transaction reports that can be decided
     * from this report alone, without reference data, the GLEIF file, the MIC
     * registry or earlier submissions.
     *
     * <p>Plain idea: many fields only make sense together. A firm dealing on own
     * account must appear as buyer or seller; an off-venue trade has no venue
     * transaction id; an option needs an option type, and so on. ESMA lists these
     * as numbered rules with CON error codes, and an NCA rejects a report that
     * breaks one.
     *
     * <p>Source (published reference): ESMA65-8-2594, Annex 1 to the MiFIR
     * transaction reporting instructions, sheet TransactionDataValidations,
     * version 1.7. Each message starts with the ESMA error code. Rules checked:
     * CON-030 (XOFF/XXXX part only), 073, 074, 110, 122, 123, 150, 163, 164, 200,
     * 212, 213, 240, 251, 290, 361 (XXXX needs the full description), 362, 372
     * (XOFF/XXXX part), 431, 441, 450, 480, 481, 490, 500, 501, 502, 510, 511,
     * 530, 531, 540, 541, 542, 550, 551, 552, 560, 570, 571, 572, 574, 591 and
     * 610 (XOFF/XXXX part). The CFI-based rules apply when field 43 is populated,
     * that is, when the report carries its own instrument description. Messages
     * starting {@code XSD} are element cardinalities of the auth.016 usage
     * guideline that the flat field layout cannot enforce (field 59 is mandatory,
     * monetary prices need a currency, field 2 is {@code [A-Z0-9]{1,52}}, a full
     * instrument description needs fields 42, 43, 46, 47 or 48, and 56).
     *
     * <p>Not checked here, because they need data outside the report: LEI status in
     * GLEIF, MIC and SI status, country and currency validity on the trading date,
     * instrument presence in FIRDS (these are in {@link #validateWithReferenceData}), uniqueness and cancellation history of field
     * 2, and the date the file reaches the NCA. Cancellations are only checked
     * for field 2, since auth.016 carries just fields 2, 4 and 6 for them.
     *
     * <p>Worked example ({@code Rts22EsmaRulesTest}, own test): a DEAL fill built
     * with the default builder and no field 57 breaks CON-570 and the XSD rule for
     * field 59; setting 57 and 59 to algorithm codes clears both.
     *
     * @return rule violations, empty when none
     */
    public List<String> validateEsmaRules() {
        return EsmaRules.check(this);
    }

    /**
     * This report as a one-transaction ISO 20022 auth.016.001.01 document
     * (namespace {@code urn:iso:std:iso:20022:tech:xsd:auth.016.001.01}). How
     * the flat fields map to message elements, and the conventions callers must
     * follow for party, person and instrument fields, is in the package
     * documentation.
     */
    public String toAuth016Xml() {
        return Auth016Writer.document(List.of(this));
    }

    /**
     * Several reports as one auth.016.001.01 document, one {@code Tx} each, in
     * order. The business application header (head.001) and
     * file envelope (head.003) are not written here; {@link #toSubmissionXml} adds them.
     */
    public static String toAuth016Xml(List<Rts22TransactionReport> reports) {
        return Auth016Writer.document(List.copyOf(reports));
    }

    /**
     * The ESMA validation rules that need reference data (ESMA65-8-2594 Annex 1, sheet
     * TransactionDataValidations, v1.7). {@link #validateEsmaRules()} covers the
     * data-only rules; call both for the full set this library implements.
     *
     * <p>In plain terms: an NCA rejects a report whose LEIs are not live in GLEIF on the
     * trading date, whose ISIN is not in FIRDS for the venue, whose MIC was not active,
     * or whose currency or country code was not valid on that date. This method runs
     * those checks against the registries the caller supplies.
     *
     * <p>Rules run, each skipped when its registry in {@code refs} is null:
     * <ul>
     *   <li>LEI ({@code refs.leis()}): CON-040/041 (field 4; Lapsed is allowed only
     *       when field 5 is false, or when the LEI is renewed by the submission date),
     *       CON-060 (field 6, on the submission date), CON-070, CON-120, CON-160, CON-210
     *       (fields 7, 12, 16, 21 when they hold an LEI; InitialRegistrationDate not checked
     *       before 3 July 2018), CON-260, CON-270 (fields 26, 27). Status must be Issued,
     *       Pending transfer or Pending archival, or Lapsed where allowed; the
     *       InitialRegistrationDate on or before the date; the entity Active, or its
     *       LastUpdateDate on or after the date.</li>
     *   <li>Country ({@code refs.countries()}): CON-071, CON-121, CON-161, CON-211, CON-573,
     *       CON-590 (country prefix of a national id) and CON-080, CON-170, CON-371,
     *       CON-580, CON-600 (branch country fields).</li>
     *   <li>Currency ({@code refs.currencies()}): CON-310, CON-340, CON-390, CON-440,
     *       CON-451, CON-520, with the special codes (XAU, XXX and the others) rejected and
     *       pre-euro codes accepted for fields 31, 44 and 45.</li>
     *   <li>MIC ({@code refs.mics()}): CON-072, CON-162 (MIC as buyer or seller),
     *       CON-360 (field 36), CON-610 (waiver on a non-EEA venue).</li>
     *   <li>Instrument ({@code refs.instruments()}): CON-361 (XXXX for an ISIN in FIRDS),
     *       CON-411 (field 41 in FIRDS for the venue, or for any EEA venue when off venue,
     *       on an SI or on a non-EEA venue), CON-471 (underlyings in field 47), and, from
     *       the FIRDS CFI, CON-351 (net amount for bonds), CON-381 (up-front payment for
     *       CDS), CON-640 (commodity flag).</li>
     * </ul>
     * An NCA pends CON-411 and CON-471 for up to 7 days before rejecting; this method
     * reports them at once and says so in the message. EEA membership of a venue comes
     * from {@link com.quantfinlib.reporting.registry.MicRegistry#country}; when it is not
     * known, the EEA-dependent parts of CON-411, CON-471 and CON-610 are skipped.
     * Cancellations get only CON-060.
     *
     * <p>Not implemented: CON-330/331 (percentage prices are not representable here),
     * CON-430 (needs the ISO 10962 table), the 7-day pending workflow, and rules on
     * field 2 uniqueness and cancellation history.
     *
     * <p>Worked example ({@code Rts22ReferenceRulesTest}, own test): a buy on XPAR whose
     * counterparty LEI has status RETIRED in the fixture registry fails CON-160 for
     * field 16; with status LAPSED it passes, because CON-160 accepts a lapsed LEI.
     *
     * @param refs the reference data
     * @param submissionDate the date the file is delivered to the NCA (CON-040, CON-060)
     * @return rule violations, empty when none
     * @since 2.4.0
     */
    public List<String> validateWithReferenceData(com.quantfinlib.reporting.registry.ReferenceData refs,
            java.time.LocalDate submissionDate) {
        return EsmaReferenceRules.check(this, refs, submissionDate);
    }

    /**
     * Reports wrapped for submission to a competent authority or ARM: a head.003.001.01
     * {@code BizData} envelope holding the head.001.001.01 Business Application Header
     * ({@code Hdr/AppHdr}) and the auth.016.001.01 document ({@code Pyld/Document}),
     * following sections 6.4 and 6.5 of the ESMA technical reporting instructions
     * (ESMA65-8-2356, paragraphs 124 to 132).
     *
     * <p>{@code Fr} is the submitting entity LEI (which must equal field 6 of every
     * report), {@code To} the two-letter country code of the competent authority,
     * {@code MsgDefIdr} is {@code auth.016.001.01}, and {@code CreDt} is {@code created}
     * in UTC to the second.
     *
     * <p>Worked example ({@code Auth016EnvelopeTest}, own test): one equity fill with
     * authority FR, id {@code FILE0001} and time 2026-11-02T10:15:30.5Z gives
     * {@code <CreDt>2026-11-02T10:15:30Z</CreDt>} and validates against the ESMA
     * head.003, head.001 and auth.016 XSDs when they are supplied.
     *
     * @param reports the reports, at least one
     * @param submittingLei LEI of the submitting entity
     * @param ncaCountry ISO 3166 country code of the competent authority, e.g. {@code FR}
     * @param businessMessageId the file identifier, 1 to 35 characters (rules set nationally)
     * @param created creation time
     * @return the XML file
     * @throws IllegalArgumentException on an invalid LEI, country or id, or a field 6 mismatch
     * @since 2.4.0
     */
    public static String toSubmissionXml(List<Rts22TransactionReport> reports, String submittingLei,
            String ncaCountry, String businessMessageId, java.time.Instant created) {
        return BusinessEnvelopeWriter.envelope(List.copyOf(reports), submittingLei, ncaCountry,
                businessMessageId, created);
    }

    private void lei(List<String> errs, Rts22Field f, boolean mustBeLei) {
        String v = get(f);
        // A 20-character CONCAT code (fields 7/16) ends in a letter or '#'; an LEI ends in two check digits.
        boolean leiShaped = v.length() == 20 && Character.isDigit(v.charAt(18)) && Character.isDigit(v.charAt(19));
        if (v.isBlank() || (!mustBeLei && !leiShaped)) {
            return;
        }
        if (!ReportingIdentifiers.isValidLei(v)) {
            errs.add(tag(f) + " '" + v + "' fails the ISO 17442 LEI check");
        }
    }

    static String tag(Rts22Field f) {
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
