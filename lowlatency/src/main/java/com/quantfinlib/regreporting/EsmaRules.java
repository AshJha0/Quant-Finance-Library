package com.quantfinlib.regreporting;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static com.quantfinlib.regreporting.Rts22Field.*;

/**
 * Data-only ESMA validation rules (ESMA65-8-2594 Annex 1, sheet
 * TransactionDataValidations, v1.7). Rule texts are cited by error code; see
 * {@link Rts22TransactionReport#validateEsmaRules()} for the list and scope.
 */
final class EsmaRules {
    /** CON-074 etc: 2 letters, 8 digits, then letters or #, with the 11th and 16th letters. */
    private static final Pattern CONCAT = Pattern.compile("[A-Z]{2}[0-9]{8}[A-Z][A-Z#]{4}[A-Z][A-Z#]{4}");
    /** CON-074 etc: national id or passport, 3-35 chars, 2 letters first; FI allows + and -, LV allows -. */
    private static final Pattern NATIONAL =
            Pattern.compile("[A-Z]{2}[A-Z0-9]{1,33}|FI[A-Z0-9+\\-]{1,33}|LV[A-Z0-9\\-]{1,33}");
    private static final Pattern TX_ID = Pattern.compile("[A-Z0-9]{1,52}");

    private final Rts22TransactionReport r;
    private final List<String> errs = new ArrayList<>();

    private EsmaRules(Rts22TransactionReport r) {
        this.r = r;
    }

    static List<String> check(Rts22TransactionReport r) {
        EsmaRules e = new EsmaRules(r);
        e.run();
        return List.copyOf(e.errs);
    }

    private String v(Rts22Field f) {
        return r.get(f);
    }

    private boolean has(Rts22Field f) {
        return !r.get(f).isEmpty();
    }

    private void err(String code, String msg) {
        errs.add(code + ": " + msg);
    }

    private void run() {
        String txId = v(TRANSACTION_REFERENCE_NUMBER);
        if (!txId.isEmpty() && !TX_ID.matcher(txId).matches()) {
            err("XSD", "field 2 '" + txId + "' must match [A-Z0-9]{1,52}");
        }
        if ("CANC".equals(v(REPORT_STATUS))) {
            return;
        }
        // CON-410 (rule 130) and CON-470 (rule 150): ISO 6166 check digit of fields 41 and 47.
        String isin = v(INSTRUMENT_ID);
        if (isin.length() == 12 && !ReportingIdentifiers.isValidIsin(isin)) {
            err("CON-410", "field 41 ISIN " + isin + " fails the ISO 6166 check digit");
        }
        for (String u : Auth016Writer.list(v(UNDERLYING_INSTRUMENT_ID))) {
            if (u.length() == 12 && !ReportingIdentifiers.isValidIsin(u)) {
                err("CON-470", "field 47 ISIN " + u + " fails the ISO 6166 check digit");
            }
        }
        String venue = v(VENUE);
        boolean offVenue = "XOFF".equals(venue) || "XXXX".equals(venue);
        String cap = v(TRADING_CAPACITY);
        boolean principalOrMatched = "DEAL".equals(cap) || "MTCH".equals(cap);
        boolean transmitted = "true".equals(v(TRANSMISSION_OF_ORDER_INDICATOR));

        if (offVenue && has(TRADING_VENUE_TRANSACTION_ID)) {
            err("CON-030", "field 3 must be blank when field 36 is " + venue);
        }
        if (principalOrMatched && transmitted) {
            err("CON-251", "field 25 must be false when field 29 is " + cap);
        }
        if ("DEAL".equals(cap) && !v(EXECUTING_ENTITY_ID).isEmpty()
                && !v(EXECUTING_ENTITY_ID).equals(v(BUYER_ID)) && !v(EXECUTING_ENTITY_ID).equals(v(SELLER_ID))) {
            err("CON-290", "with DEAL the executing entity (field 4) must be buyer (7) or seller (16)");
        }
        if (transmitted && !offVenue) {
            err("CON-362", "field 25 true needs field 36 XOFF or XXXX, not " + venue);
        }
        if (offVenue && has(COUNTRY_OF_BRANCH_MEMBERSHIP)) {
            err("CON-372", "field 37 must be blank when field 36 is " + venue);
        }
        if ("XXXX".equals(venue) && !has(INSTRUMENT_FULL_NAME) && !has(INSTRUMENT_CLASSIFICATION)) {
            err("CON-361", "field 36 XXXX needs the full instrument description (fields 42-56)");
        }
        if (offVenue && has(WAIVER_INDICATOR)) {
            err("CON-610", "field 61 must be blank when field 36 is " + venue);
        }

        LocalDate tradeDate = tradeDate();
        party("CON-074", "CON-073", "CON-110", BUYER_ID, BUYER_DATE_OF_BIRTH, true, tradeDate);
        party("CON-123", "CON-122", "CON-150", BUYER_DECISION_MAKER_ID, BUYER_DECISION_MAKER_DATE_OF_BIRTH,
                false, tradeDate);
        party("CON-164", "CON-163", "CON-200", SELLER_ID, SELLER_DATE_OF_BIRTH, true, tradeDate);
        party("CON-213", "CON-212", "CON-240", SELLER_DECISION_MAKER_ID, SELLER_DECISION_MAKER_DATE_OF_BIRTH,
                false, tradeDate);
        if (has(INVESTMENT_DECISION_BRANCH_COUNTRY)) {
            idShape("CON-574", INVESTMENT_DECISION_WITHIN_FIRM, v(INVESTMENT_DECISION_WITHIN_FIRM));
        }
        if (has(EXECUTION_BRANCH_COUNTRY)) {
            idShape("CON-591", EXECUTION_WITHIN_FIRM, v(EXECUTION_WITHIN_FIRM));
        }

        decisionMaker(cap);

        if (!has(EXECUTION_WITHIN_FIRM)) {
            err("XSD", "field 59 is mandatory for a new report (ExctgPrsn); use NORE when the client decided");
        }
        String px = v(PRICE);
        if (!px.isEmpty() && PriceNotation.of(px) == PriceNotation.MONETARY && !has(PRICE_CURRENCY)) {
            err("XSD", "a monetary price (field 33) needs field 34");
        }
        if (has(STRIKE_PRICE) && PriceNotation.of(v(STRIKE_PRICE)) == PriceNotation.MONETARY
                && !has(STRIKE_PRICE_CURRENCY)) {
            err("XSD", "a monetary strike price (field 51) needs field 52");
        }
        if (has(UP_FRONT_PAYMENT) && !has(UP_FRONT_PAYMENT_CURRENCY)) {
            err("XSD", "field 38 needs field 39");
        }
        if (has(NOTIONAL_CURRENCY_2) && !has(NOTIONAL_CURRENCY_1)) {
            err("CON-450", "field 45 can only be populated when field 44 is");
        }
        notBefore("CON-541", MATURITY_DATE, tradeDate);
        notBefore("CON-552", EXPIRY_DATE, tradeDate);

        if (has(INSTRUMENT_FULL_NAME) || has(INSTRUMENT_CLASSIFICATION)) {
            instrument();
        }
    }

    private LocalDate tradeDate() {
        String t = v(TRADING_DATE_TIME);
        return t.length() >= 10 ? date(t.substring(0, 10)) : null;
    }

    private static LocalDate date(String s) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private void party(String shapeRule, String concatDobRule, String dobRule, Rts22Field id, Rts22Field dob,
                       boolean micOrIntcAllowed, LocalDate tradeDate) {
        String raw = v(id);
        if (raw.isEmpty() || Auth016Writer.isLei(raw)
                || (micOrIntcAllowed && ("INTC".equals(raw) || raw.length() == 4))) {
            return;
        }
        idShape(shapeRule, id, raw);
        String bare = bare(raw);
        LocalDate birth = date(v(dob));
        if (CONCAT.matcher(bare).matches() && birth != null) {
            String ymd = bare.substring(2, 10);
            if (!ymd.equals(birth.toString().replace("-", ""))) {
                err(concatDobRule, "birth date in CONCAT code of " + Rts22TransactionReport.tag(id)
                        + " differs from " + Rts22TransactionReport.tag(dob));
            }
        }
        if (birth != null && tradeDate != null && birth.isAfter(tradeDate)) {
            err(dobRule, Rts22TransactionReport.tag(dob) + " is after the trading date");
        }
    }

    private void idShape(String rule, Rts22Field f, String raw) {
        String bare = bare(raw);
        boolean concatLike = bare.length() == 20 && bare.matches("[A-Z]{2}[0-9]{8}.*");
        boolean ok = concatLike ? CONCAT.matcher(bare).matches()
                : bare.length() >= 3 && bare.length() <= 35 && NATIONAL.matcher(bare).matches();
        if (!ok) {
            err(rule, Rts22TransactionReport.tag(f) + " '" + raw + "' is not a valid CONCAT or national id");
        }
    }

    private static String bare(String raw) {
        return raw.startsWith("CCPT:") || raw.startsWith("NIDN:") ? raw.substring(5) : raw;
    }

    private void decisionMaker(String cap) {
        boolean receiving = has(TRANSMITTING_FIRM_ID_BUYER) || has(TRANSMITTING_FIRM_ID_SELLER);
        if ("DEAL".equals(cap) && !receiving && !has(INVESTMENT_DECISION_WITHIN_FIRM)) {
            err("CON-570", "field 57 is mandatory with DEAL when fields 26 and 27 are blank");
        }
        if (!has(INVESTMENT_DECISION_WITHIN_FIRM) || !("MTCH".equals(cap) || "AOTC".equals(cap))) {
            return;
        }
        String exec = v(EXECUTING_ENTITY_ID);
        String dmB = v(BUYER_DECISION_MAKER_ID);
        String dmS = v(SELLER_DECISION_MAKER_ID);
        if (!receiving && !exec.equals(dmB) && !exec.equals(dmS)) {
            err("CON-571", "field 57 must be blank with " + cap
                    + " unless a decision maker (12 or 21) is the executing entity");
        }
        String tb = v(TRANSMITTING_FIRM_ID_BUYER);
        String ts = v(TRANSMITTING_FIRM_ID_SELLER);
        if ((!tb.isEmpty() && !tb.equals(dmB)) || (!ts.isEmpty() && !ts.equals(dmS))) {
            err("CON-572", "field 57 must be blank with " + cap
                    + " when a transmitting firm (26/27) is not the matching decision maker (12/21)");
        }
    }

    private void notBefore(String rule, Rts22Field f, LocalDate tradeDate) {
        LocalDate d = date(v(f));
        if (d != null && tradeDate != null && d.isBefore(tradeDate)) {
            err(rule, Rts22TransactionReport.tag(f) + " is before the trading date");
        }
    }

    /** CFI wildcard match: '*' matches any character. */
    static boolean cfi(String code, String... patterns) {
        if (code.length() != 6) {
            return false;
        }
        for (String p : patterns) {
            boolean ok = true;
            for (int i = 0; i < 6 && ok; i++) {
                ok = p.charAt(i) == '*' || p.charAt(i) == code.charAt(i);
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }

    private void mandatory(String rule, Rts22Field f, String c, String... patterns) {
        if (cfi(c, patterns) && !has(f)) {
            err(rule, Rts22TransactionReport.tag(f) + " is mandatory for CFI " + c);
        }
    }

    private void forbidden(String rule, Rts22Field f, String c, String... patterns) {
        if (cfi(c, patterns) && has(f)) {
            err(rule, Rts22TransactionReport.tag(f) + " must not be populated for CFI " + c);
        }
    }

    /**
     * The condition of CON-330 and CON-331: a debt CFI ({@code D*****}), quantity in units
     * (no field 31, so auth.016 {@code Qty/Unit}) and a percentage price in field 33.
     */
    static boolean unitDebtPercentage(Rts22TransactionReport r, String cfi) {
        return cfi(cfi, "D*****") && r.get(QUANTITY_CURRENCY).isEmpty()
                && PriceNotation.of(r.get(PRICE)) == PriceNotation.PERCENTAGE;
    }

    private void instrument() {
        if (!has(INSTRUMENT_FULL_NAME) || !has(INSTRUMENT_CLASSIFICATION) || !has(PRICE_MULTIPLIER)
                || (!has(UNDERLYING_INSTRUMENT_ID) && !has(UNDERLYING_INDEX_NAME)) || !has(DELIVERY_TYPE)) {
            err("XSD", "a full instrument description needs fields 42, 43, 46, 47 or 48, and 56");
        }
        String c = v(INSTRUMENT_CLASSIFICATION);
        mandatory("CON-441", NOTIONAL_CURRENCY_1, c,
                "O*****", "S*****", "C*****", "R*****", "F*****", "E*****", "D*****", "H*****", "J*****");
        mandatory("CON-480", UNDERLYING_INDEX_NAME, c, "O**I**", "O**N**", "FFI***", "FFN***", "RWI***", "RFI***");
        forbidden("CON-481", UNDERLYING_INDEX_NAME, c, "DB****", "DT****", "DY****", "ES****", "EP****",
                "EC****", "EF****", "EL****", "ED****", "EM****", "EX****", "C*****");
        mandatory("CON-490", UNDERLYING_INDEX_TERM, c, "O**N**", "FFN***");
        mandatory("CON-500", OPTION_TYPE, c, "O*****", "H*****", "RW****");
        forbidden("CON-501", OPTION_TYPE, c, "F*****", "S*****", "E*****", "C*****", "D*****", "J*****");
        mandatory("CON-510", STRIKE_PRICE, c, "O*****", "H*****", "RW****");
        forbidden("CON-511", STRIKE_PRICE, c, "F*****", "S*****", "E*****", "C*****", "D*****", "J*****");
        mandatory("CON-530", OPTION_EXERCISE_STYLE, c, "O*****", "H*****", "RW****", "RF****");
        forbidden("CON-531", OPTION_EXERCISE_STYLE, c, "F*****", "S*****", "E*****", "C*****", "D*****", "J*****");
        mandatory("CON-540", MATURITY_DATE, c, "D*****");
        forbidden("CON-542", MATURITY_DATE, c, "R*****", "O*****", "F*****", "S*****", "E*****", "C*****",
                "H*****", "J*****");
        mandatory("CON-550", EXPIRY_DATE, c, "O*****", "F*****", "JC**F*");
        forbidden("CON-551", EXPIRY_DATE, c, "E*****", "C*****", "D*****");

        // CON-330 (rule 260): debt CFI in field 43, quantity in units -> price not a percentage.
        if (unitDebtPercentage(r, c)) {
            err("CON-330", "price notation is incorrect: field 33 is a percentage but field 30 is in units"
                    + " for the debt instrument with CFI " + c);
        }

        String ot = v(OPTION_TYPE);
        String want = cfi(c, "OC****", "H**A**", "H**B**", "RW**C*", "H**C**") ? "CALL"
                : cfi(c, "OP****", "H**D**", "H**E**", "RW**P*", "H**F**") ? "PUTO"
                : cfi(c, "OM****", "H**G**", "H**H**", "RW**B*", "H**I**") ? "OTHR" : null;
        if (want != null && !ot.isEmpty() && !want.equals(ot)) {
            err("CON-502", "field 50 must be " + want + " for CFI " + c + ", not " + ot);
        }

        String style = v(OPTION_EXERCISE_STYLE);
        String[] allowed = switch (style) {
            case "EURO" -> new String[] {"O*E***", "H**A**", "H**D**", "H**G**", "RW***E", "RF***E"};
            case "AMER" -> new String[] {"O*A***", "H**B**", "H**E**", "H**H**", "RW***A", "RF***A"};
            case "BERM" -> new String[] {"O*B***", "H**C**", "H**F**", "H**I**", "RW***B", "RF***B"};
            default -> null;
        };
        if (allowed != null && !cfi(c, allowed)) {
            err("CON-431", "CFI " + c + " is not consistent with option style " + style);
        }

        String dt = v(DELIVERY_TYPE);
        if (!dt.isEmpty()) {
            if (cfi(c, "OC**P*", "OP**P*", "FF*P**", "FC*P**", "SR***P", "ST***P", "SE***P", "SC***P", "SF***P",
                    "SM***P", "HR***P", "HT***P", "HE***P", "HC***P", "HF***P", "HM***P", "IF***P", "JE***P",
                    "JF***P", "JC***P", "JR***P", "JT***P", "LL***P") && !"PHYS".equals(dt)) {
                err("CON-560", "field 56 must be PHYS for CFI " + c);
            }
            if (cfi(c, "OC**C*", "OP**C*", "FF*C**", "FC*C**", "SR***C", "ST***C", "SE***C", "SC***C", "SM***C",
                    "HR***C", "HT***C", "HE***C", "HC***C", "HF***C", "HM***C", "JE***C", "JF***C", "JC***C",
                    "JR***C", "JT***C", "LL***C") && !"CASH".equals(dt)) {
                err("CON-560", "field 56 must be CASH for CFI " + c);
            }
        }
    }
}
