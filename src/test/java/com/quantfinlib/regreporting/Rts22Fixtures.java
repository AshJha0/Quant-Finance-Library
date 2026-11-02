package com.quantfinlib.regreporting;

import com.quantfinlib.microstructure.Execution;
import com.quantfinlib.orderbook.Side;
import com.quantfinlib.regreporting.Rts22ReportBuilder.TradingCapacity;

/** Synthetic RTS 22 reports shared by the auth.016 and ESMA-rule tests. Not real entities. */
final class Rts22Fixtures {
    static final String FIRM = lei("5493000TESTFIRM001");
    static final String CPTY = lei("5493000TESTCPTY002");
    static final String CLIENT_LEI = lei("5493000TESTCLNT003");
    /** CONCAT code: country, birth date 1980-04-13, first 5 of first name, first 5 of surname. */
    static final String CONCAT = "FR19800413JOHN#SMITH";
    static final long NANOS = 1_793_614_530_123_456_000L; // 2026-11-02T10:15:30.123456Z

    private Rts22Fixtures() {
    }

    static String lei(String base) {
        return base + ReportingIdentifiers.leiCheckDigits(base);
    }

    static Rts22ReportBuilder equityDeal() {
        return new Rts22ReportBuilder(FIRM).counterparty(CPTY).venue("XLON").isin("GB0002634946")
                .priceCurrency("GBP").referencePrefix("QFL")
                .set(Rts22Field.INVESTMENT_DECISION_WITHIN_FIRM, "ALGO1")
                .set(Rts22Field.EXECUTION_WITHIN_FIRM, "ALGO1");
    }

    static Rts22TransactionReport build(Rts22ReportBuilder b, Side side, double px, String ref) {
        return b.fromExecution(new Execution("X", side, px, 100, NANOS, ""), ref);
    }

    /** AOTC for a natural-person client, executed by a trader on an EEA venue. */
    static Rts22ReportBuilder agencyForPerson() {
        return new Rts22ReportBuilder(FIRM).capacity(TradingCapacity.AOTC).client(CONCAT).counterparty(CPTY)
                .venue("XPAR").isin("GB0002634946").priceCurrency("EUR")
                .set(Rts22Field.BUYER_FIRST_NAMES, "JOHN").set(Rts22Field.BUYER_SURNAMES, "SMITH")
                .set(Rts22Field.BUYER_DATE_OF_BIRTH, "1980-04-13").set(Rts22Field.BUYER_BRANCH_COUNTRY, "FR")
                .set(Rts22Field.COUNTRY_OF_BRANCH_MEMBERSHIP, "FR")
                .set(Rts22Field.EXECUTION_WITHIN_FIRM, "FR19750102ANNE#MARTI")
                .set(Rts22Field.EXECUTION_BRANCH_COUNTRY, "FR")
                .set(Rts22Field.SHORT_SELLING_INDICATOR, "SELL");
    }

    /** OTC equity call option on US0378331005, described in full (fields 42-56). */
    static Rts22ReportBuilder otcOption() {
        return equityDeal().venue("XXXX").isin("")
                .set(Rts22Field.INSTRUMENT_ID, "")
                .set(Rts22Field.INSTRUMENT_FULL_NAME, "APPLE CALL 250 DEC27")
                .set(Rts22Field.INSTRUMENT_CLASSIFICATION, "OCESPS")
                .set(Rts22Field.NOTIONAL_CURRENCY_1, "USD")
                .set(Rts22Field.PRICE_MULTIPLIER, "100")
                .set(Rts22Field.UNDERLYING_INSTRUMENT_ID, "US0378331005")
                .set(Rts22Field.OPTION_TYPE, "CALL")
                .set(Rts22Field.STRIKE_PRICE, "250")
                .set(Rts22Field.STRIKE_PRICE_CURRENCY, "USD")
                .set(Rts22Field.OPTION_EXERCISE_STYLE, "EURO")
                .set(Rts22Field.EXPIRY_DATE, "2027-12-17")
                .set(Rts22Field.DELIVERY_TYPE, "PHYS")
                .priceCurrency("USD");
    }
}
