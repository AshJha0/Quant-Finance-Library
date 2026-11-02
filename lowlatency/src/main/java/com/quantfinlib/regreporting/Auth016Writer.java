package com.quantfinlib.regreporting;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.quantfinlib.regreporting.Rts22Field.*;

/**
 * Writes RTS 22 reports as an ISO 20022 auth.016.001.01 {@code Document}
 * following the ESMA usage guideline auth.016.001.01_ESMAUG_Reporting_1.1.0 and
 * the field-to-element mapping in section 6 of the ESMA technical reporting
 * instructions (ESMA65-8-2356).
 *
 * <p>The flat 65-field layout loses some type information the message needs.
 * The writer recovers it by these rules, which callers must respect:
 * <ul>
 *   <li>Fields 7, 16 (buyer, seller): a valid 20-character LEI is written as
 *       {@code LEI}, {@code INTC} as {@code Intl}, any other 4-character code as
 *       {@code MIC}; anything else is a natural person and needs fields 9 to 11
 *       (or 18 to 20). Fields 12 and 21 likewise: LEI, else a person with
 *       fields 13 to 15 (22 to 24).</li>
 *   <li>National identifiers: a value shaped like a CONCAT code (2 letters, 8
 *       digits, 10 letters or #) is written with scheme {@code CONCAT}; a value
 *       prefixed {@code CCPT:} with scheme CCPT; anything else, or a value
 *       prefixed {@code NIDN:}, with scheme NIDN.</li>
 *   <li>Fields 57 and 59: a person when the matching branch-country field (58
 *       or 60) is populated, otherwise an algorithm code; field 59 {@code NORE}
 *       is written as {@code Clnt}.</li>
 *   <li>Field 30 is in units unless field 31 is set, then a nominal value in
 *       that currency. Fields 33 and 51 are monetary prices in the currency of
 *       34 or 52 (negative values get {@code Sgn false}), or {@code PNDG} /
 *       {@code NOAP} for no price. Percentage, yield and basis-point prices are
 *       not supported.</li>
 *   <li>Field 41 alone gives {@code FinInstrm/Id}. When field 42 or 43 is set
 *       the full description ({@code FinInstrm/Othr}) is written instead, with
 *       field 41 as the optional ISIN inside it. Field 47 may hold several ISINs
 *       separated by spaces (a basket). Field 48 is a benchmark code when it is
 *       one of the ISO 20022 BenchmarkCurveName2Code values, else a free name.
 *       Field 49 is written as {@code <n><DAYS|WEEK|MNTH|YEAR>}, e.g.
 *       {@code 3MNTH}. Field 45 goes under {@code Intrst} for CFI codes
 *       starting SR, JR or FFN, else under {@code FX}. Swap legs
 *       ({@code SwpIn}/{@code SwpOut}) are not supported.</li>
 *   <li>Fields 61 and 63 may hold several codes separated by spaces or
 *       commas. Field 64 maps to {@code RskRdcgTx}.</li>
 * </ul>
 * The writer does not check anything; a report that fails
 * {@link Rts22TransactionReport#validate()} or
 * {@link Rts22TransactionReport#validateEsmaRules()} can produce a document the
 * XSD rejects.
 */
final class Auth016Writer {
    static final String NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:auth.016.001.01";

    private static final Pattern CONCAT = Pattern.compile("[A-Z]{2}[0-9]{8}[A-Z#]{10}");
    private static final Pattern TERM = Pattern.compile("(\\d{1,3})(DAYS|WEEK|MNTH|YEAR)");
    private static final Pattern LIST_SEP = Pattern.compile("[\\s,]+");
    private static final Set<String> BENCHMARKS = Set.of("WIBO", "TREA", "TIBO", "TLBO", "SWAP", "STBO",
            "PRBO", "PFAN", "NIBO", "MAAA", "MOSP", "LIBO", "LIBI", "JIBA", "ISDA", "GCFR", "FUSW", "EUCH",
            "EUUS", "EURI", "EONS", "EONA", "CIBO", "CDOR", "BUBO", "BBSW");

    private final StringBuilder sb = new StringBuilder();
    private int depth;

    private Auth016Writer() {
    }

    static String document(List<Rts22TransactionReport> reports) {
        if (reports.isEmpty()) {
            throw new IllegalArgumentException("auth.016 needs at least one transaction");
        }
        Auth016Writer w = new Auth016Writer();
        w.sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        w.sb.append("<Document xmlns=\"").append(NAMESPACE).append("\">\n");
        w.depth = 1;
        w.open("FinInstrmRptgTxRpt");
        for (Rts22TransactionReport r : reports) {
            w.tx(r);
        }
        w.close("FinInstrmRptgTxRpt");
        w.sb.append("</Document>\n");
        return w.sb.toString();
    }

    private void tx(Rts22TransactionReport r) {
        open("Tx");
        if ("CANC".equals(r.get(REPORT_STATUS))) {
            open("Cxl");
            leaf("TxId", r.get(TRANSACTION_REFERENCE_NUMBER));
            leaf("ExctgPty", r.get(EXECUTING_ENTITY_ID));
            leaf("SubmitgPty", r.get(SUBMITTING_ENTITY_ID));
            close("Cxl");
        } else {
            open("New");
            leaf("TxId", r.get(TRANSACTION_REFERENCE_NUMBER));
            leaf("ExctgPty", r.get(EXECUTING_ENTITY_ID));
            leaf("InvstmtPtyInd", r.get(INVESTMENT_FIRM_INDICATOR));
            leaf("SubmitgPty", r.get(SUBMITTING_ENTITY_ID));
            party(r, "Buyr", BUYER_ID, BUYER_BRANCH_COUNTRY, BUYER_FIRST_NAMES, BUYER_SURNAMES,
                    BUYER_DATE_OF_BIRTH, BUYER_DECISION_MAKER_ID, BUYER_DECISION_MAKER_FIRST_NAMES,
                    BUYER_DECISION_MAKER_SURNAMES, BUYER_DECISION_MAKER_DATE_OF_BIRTH);
            party(r, "Sellr", SELLER_ID, SELLER_BRANCH_COUNTRY, SELLER_FIRST_NAMES, SELLER_SURNAMES,
                    SELLER_DATE_OF_BIRTH, SELLER_DECISION_MAKER_ID, SELLER_DECISION_MAKER_FIRST_NAMES,
                    SELLER_DECISION_MAKER_SURNAMES, SELLER_DECISION_MAKER_DATE_OF_BIRTH);
            open("OrdrTrnsmssn");
            leaf("TrnsmssnInd", r.get(TRANSMISSION_OF_ORDER_INDICATOR));
            opt("TrnsmttgBuyr", r.get(TRANSMITTING_FIRM_ID_BUYER));
            opt("TrnsmttgSellr", r.get(TRANSMITTING_FIRM_ID_SELLER));
            close("OrdrTrnsmssn");
            transaction(r);
            instrument(r);
            String idm = r.get(INVESTMENT_DECISION_WITHIN_FIRM);
            if (!idm.isEmpty()) {
                open("InvstmtDcsnPrsn");
                personOrAlgo(idm, r.get(INVESTMENT_DECISION_BRANCH_COUNTRY));
                close("InvstmtDcsnPrsn");
            }
            open("ExctgPrsn");
            String ex = r.get(EXECUTION_WITHIN_FIRM);
            if ("NORE".equals(ex)) {
                leaf("Clnt", ex);
            } else {
                personOrAlgo(ex, r.get(EXECUTION_BRANCH_COUNTRY));
            }
            close("ExctgPrsn");
            open("AddtlAttrbts");
            for (String w : list(r.get(WAIVER_INDICATOR))) {
                leaf("WvrInd", w);
            }
            opt("ShrtSellgInd", r.get(SHORT_SELLING_INDICATOR));
            for (String o : list(r.get(OTC_POST_TRADE_INDICATOR))) {
                leaf("OTCPstTradInd", o);
            }
            opt("RskRdcgTx", r.get(COMMODITY_DERIVATIVE_INDICATOR));
            leaf("SctiesFincgTxInd", r.get(SECURITIES_FINANCING_TRANSACTION_INDICATOR));
            close("AddtlAttrbts");
            close("New");
        }
        close("Tx");
    }

    private void party(Rts22TransactionReport r, String tag, Rts22Field id, Rts22Field branch,
                       Rts22Field first, Rts22Field last, Rts22Field dob, Rts22Field dm,
                       Rts22Field dmFirst, Rts22Field dmLast, Rts22Field dmDob) {
        open(tag);
        open("AcctOwnr");
        open("Id");
        String v = r.get(id);
        if (isLei(v)) {
            leaf("LEI", v);
        } else if ("INTC".equals(v)) {
            leaf("Intl", v);
        } else if (v.length() == 4) {
            leaf("MIC", v);
        } else {
            person(v, r.get(first), r.get(last), r.get(dob));
        }
        close("Id");
        opt("CtryOfBrnch", r.get(branch));
        close("AcctOwnr");
        String d = r.get(dm);
        if (!d.isEmpty()) {
            open("DcsnMakr");
            if (isLei(d)) {
                leaf("LEI", d);
            } else {
                person(d, r.get(dmFirst), r.get(dmLast), r.get(dmDob));
            }
            close("DcsnMakr");
        }
        close(tag);
    }

    private void person(String id, String first, String last, String dob) {
        open("Prsn");
        leaf("FrstNm", first);
        leaf("Nm", last);
        leaf("BirthDt", dob);
        otherId(id);
        close("Prsn");
    }

    private void personOrAlgo(String id, String branch) {
        if (branch.isEmpty()) {
            leaf("Algo", id);
        } else {
            open("Prsn");
            leaf("CtryOfBrnch", branch);
            otherId(id);
            close("Prsn");
        }
    }

    private void otherId(String raw) {
        String scheme;
        String id = raw;
        if (raw.startsWith("CCPT:") || raw.startsWith("NIDN:")) {
            scheme = raw.substring(0, 4);
            id = raw.substring(5);
        } else {
            scheme = CONCAT.matcher(raw).matches() ? "CONCAT" : "NIDN";
        }
        open("Othr");
        leaf("Id", id);
        open("SchmeNm");
        leaf("CONCAT".equals(scheme) ? "Prtry" : "Cd", scheme);
        close("SchmeNm");
        close("Othr");
    }

    private void transaction(Rts22TransactionReport r) {
        open("Tx");
        leaf("TradDt", r.get(TRADING_DATE_TIME));
        leaf("TradgCpcty", r.get(TRADING_CAPACITY));
        open("Qty");
        String qccy = r.get(QUANTITY_CURRENCY);
        if (qccy.isEmpty()) {
            leaf("Unit", r.get(QUANTITY));
        } else {
            amount("NmnlVal", r.get(QUANTITY), qccy);
        }
        close("Qty");
        opt("DerivNtnlChng", r.get(DERIVATIVE_NOTIONAL_CHANGE));
        price("Pric", r.get(PRICE), r.get(PRICE_CURRENCY));
        opt("NetAmt", r.get(NET_AMOUNT));
        leaf("TradVn", r.get(VENUE));
        opt("CtryOfBrnch", r.get(COUNTRY_OF_BRANCH_MEMBERSHIP));
        String upf = r.get(UP_FRONT_PAYMENT);
        if (!upf.isEmpty()) {
            open("UpFrntPmt");
            signedAmount(upf, r.get(UP_FRONT_PAYMENT_CURRENCY));
            close("UpFrntPmt");
        }
        opt("TradPlcMtchgId", r.get(TRADING_VENUE_TRANSACTION_ID));
        opt("CmplxTradCmpntId", r.get(COMPLEX_TRADE_COMPONENT_ID));
        close("Tx");
    }

    private void price(String tag, String value, String ccy) {
        open(tag);
        if ("PNDG".equals(value) || "NOAP".equals(value)) {
            open("NoPric");
            leaf("Pdg", value);
            opt("Ccy", ccy);
            close("NoPric");
        } else {
            open("Pric");
            switch (PriceNotation.of(value)) {
                case PERCENTAGE -> leaf("Pctg", PriceNotation.number(value));
                case YIELD -> leaf("Yld", PriceNotation.number(value));
                case BASIS_POINTS -> leaf("BsisPts", PriceNotation.number(value));
                default -> {
                    open("MntryVal");
                    signedAmount(value, ccy);
                    close("MntryVal");
                }
            }
            close("Pric");
        }
        close(tag);
    }

    private void signedAmount(String value, String ccy) {
        boolean negative = value.startsWith("-");
        amount("Amt", negative ? value.substring(1) : value, ccy);
        if (negative) {
            leaf("Sgn", "false");
        }
    }

    private void amount(String tag, String value, String ccy) {
        indent();
        sb.append('<').append(tag).append(" Ccy=\"").append(esc(ccy)).append("\">").append(esc(value))
                .append("</").append(tag).append(">\n");
    }

    private void instrument(Rts22TransactionReport r) {
        open("FinInstrm");
        String isin = r.get(INSTRUMENT_ID);
        if (r.get(INSTRUMENT_FULL_NAME).isEmpty() && r.get(INSTRUMENT_CLASSIFICATION).isEmpty()) {
            leaf("Id", isin);
        } else {
            String cfi = r.get(INSTRUMENT_CLASSIFICATION);
            open("Othr");
            open("FinInstrmGnlAttrbts");
            opt("Id", isin);
            leaf("FullNm", r.get(INSTRUMENT_FULL_NAME));
            leaf("ClssfctnTp", cfi);
            opt("NtnlCcy", r.get(NOTIONAL_CURRENCY_1));
            close("FinInstrmGnlAttrbts");
            String mat = r.get(MATURITY_DATE);
            if (!mat.isEmpty()) {
                open("DebtInstrmAttrbts");
                leaf("MtrtyDt", mat);
                close("DebtInstrmAttrbts");
            }
            open("DerivInstrmAttrbts");
            opt("XpryDt", r.get(EXPIRY_DATE));
            leaf("PricMltplr", r.get(PRICE_MULTIPLIER));
            underlying(r);
            opt("OptnTp", r.get(OPTION_TYPE));
            String strike = r.get(STRIKE_PRICE);
            if (!strike.isEmpty()) {
                price("StrkPric", strike, r.get(STRIKE_PRICE_CURRENCY));
            }
            opt("OptnExrcStyle", r.get(OPTION_EXERCISE_STYLE));
            leaf("DlvryTp", r.get(DELIVERY_TYPE));
            String n2 = r.get(NOTIONAL_CURRENCY_2);
            if (!n2.isEmpty()) {
                open("AsstClssSpcfcAttrbts");
                boolean rates = cfi.startsWith("SR") || cfi.startsWith("JR") || cfi.startsWith("FFN");
                open(rates ? "Intrst" : "FX");
                leaf("OthrNtnlCcy", n2);
                close(rates ? "Intrst" : "FX");
                close("AsstClssSpcfcAttrbts");
            }
            close("DerivInstrmAttrbts");
            close("Othr");
        }
        close("FinInstrm");
    }

    private void underlying(Rts22TransactionReport r) {
        open("UndrlygInstrm");
        open("Othr");
        List<String> isins = list(r.get(UNDERLYING_INSTRUMENT_ID));
        String index = r.get(UNDERLYING_INDEX_NAME);
        if (isins.size() > 1) {
            open("Bskt");
            for (String i : isins) {
                leaf("ISIN", i);
            }
            close("Bskt");
        } else {
            open("Sngl");
            if (index.isEmpty()) {
                leaf("ISIN", isins.isEmpty() ? "" : isins.get(0));
            } else {
                open("Indx");
                if (!isins.isEmpty()) {
                    leaf("ISIN", isins.get(0));
                }
                open("Nm");
                open("RefRate");
                leaf(BENCHMARKS.contains(index) ? "Indx" : "Nm", index);
                close("RefRate");
                Matcher m = TERM.matcher(r.get(UNDERLYING_INDEX_TERM));
                if (m.matches()) {
                    open("Term");
                    leaf("Unit", m.group(2));
                    leaf("Val", new BigDecimal(m.group(1)).toPlainString());
                    close("Term");
                }
                close("Nm");
                close("Indx");
            }
            close("Sngl");
        }
        close("Othr");
        close("UndrlygInstrm");
    }

    static boolean isLei(String v) {
        return v.length() == 20 && ReportingIdentifiers.isValidLei(v);
    }

    static List<String> list(String v) {
        String t = v.trim();
        return t.isEmpty() ? List.of() : List.of(LIST_SEP.split(t.toUpperCase(Locale.ROOT)));
    }

    private void open(String tag) {
        indent();
        sb.append('<').append(tag).append(">\n");
        depth++;
    }

    private void close(String tag) {
        depth--;
        indent();
        sb.append("</").append(tag).append(">\n");
    }

    private void leaf(String tag, String value) {
        indent();
        sb.append('<').append(tag).append('>').append(esc(value)).append("</").append(tag).append(">\n");
    }

    private void opt(String tag, String value) {
        if (!value.isEmpty()) {
            leaf(tag, value);
        }
    }

    private void indent() {
        sb.append("  ".repeat(depth));
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
