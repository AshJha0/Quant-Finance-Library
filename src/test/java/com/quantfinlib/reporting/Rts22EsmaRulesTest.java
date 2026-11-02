package com.quantfinlib.reporting;

import com.quantfinlib.orderbook.Side;
import com.quantfinlib.reporting.Rts22ReportBuilder.TradingCapacity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Data-only ESMA rules. Each case cites the error code of ESMA65-8-2594 Annex 1,
 * sheet TransactionDataValidations, v1.7 (published reference); expected outcomes
 * are read off the rule text (own test).
 */
class Rts22EsmaRulesTest {

    private static List<String> rules(Rts22ReportBuilder b, Side side) {
        return Rts22Fixtures.build(b, side, 10, "R1").validateEsmaRules();
    }

    private static boolean hit(List<String> errs, String code) {
        return errs.stream().anyMatch(s -> s.startsWith(code + ":"));
    }

    @Test
    void cleanFixturesPass() {
        assertEquals(List.of(), rules(Rts22Fixtures.equityDeal(), Side.BUY));
        assertEquals(List.of(), rules(Rts22Fixtures.agencyForPerson(), Side.BUY));
        assertEquals(List.of(), rules(Rts22Fixtures.otcOption(), Side.SELL));
    }

    @Test
    void workedExampleDealWithoutDecisionMakerOrExecutor() {
        Rts22ReportBuilder b = new Rts22ReportBuilder(Rts22Fixtures.FIRM).counterparty(Rts22Fixtures.CPTY)
                .venue("XLON").isin("GB0002634946").priceCurrency("GBP");
        List<String> e = rules(b, Side.BUY);
        assertTrue(hit(e, "CON-570"), e.toString());
        assertTrue(e.stream().anyMatch(s -> s.startsWith("XSD") && s.contains("field 59")), e.toString());
        b.set(Rts22Field.INVESTMENT_DECISION_WITHIN_FIRM, "ALGO1").set(Rts22Field.EXECUTION_WITHIN_FIRM, "ALGO1");
        assertEquals(List.of(), rules(b, Side.BUY));
    }

    @Test
    void capacityAndTransmission() {
        // CON-251: DEAL/MTCH needs field 25 false. CON-362: transmitted needs XOFF/XXXX.
        List<String> e = rules(Rts22Fixtures.equityDeal().transmission(true), Side.BUY);
        assertTrue(hit(e, "CON-251"));
        assertTrue(hit(e, "CON-362"));
        // CON-290: DEAL with neither side the executing entity.
        assertTrue(hit(rules(Rts22Fixtures.equityDeal().set(Rts22Field.BUYER_ID, Rts22Fixtures.CLIENT_LEI),
                Side.BUY), "CON-290"));
    }

    @Test
    void offVenueFields() {
        Rts22ReportBuilder b = Rts22Fixtures.equityDeal().venue("XOFF").venueTransactionId("V1")
                .set(Rts22Field.COUNTRY_OF_BRANCH_MEMBERSHIP, "GB").set(Rts22Field.WAIVER_INDICATOR, "SIZE");
        List<String> e = rules(b, Side.BUY);
        assertTrue(hit(e, "CON-030"));
        assertTrue(hit(e, "CON-372"));
        assertTrue(hit(e, "CON-610"));
        // CON-361: XXXX needs the full description.
        assertTrue(hit(rules(Rts22Fixtures.equityDeal().venue("XXXX"), Side.BUY), "CON-361"));
    }

    @Test
    void naturalPersonIdentifiersAndDates() {
        // CON-073: CONCAT birth date (1980-04-13) must equal field 11.
        assertTrue(hit(rules(Rts22Fixtures.agencyForPerson().set(Rts22Field.BUYER_DATE_OF_BIRTH, "1980-04-14"),
                Side.BUY), "CON-073"));
        // CON-110: birth date after the trading date.
        assertTrue(hit(rules(Rts22Fixtures.agencyForPerson().client("FR123456789")
                .set(Rts22Field.BUYER_DATE_OF_BIRTH, "2030-01-01"), Side.BUY), "CON-110"));
        // CON-074: CONCAT with a digit where a letter must be (11th character).
        assertTrue(hit(rules(Rts22Fixtures.agencyForPerson().client("FR198004131OHN#SMITH"), Side.BUY),
                "CON-074"));
        // CON-591: executing trader id with lower case.
        assertTrue(hit(rules(Rts22Fixtures.agencyForPerson().set(Rts22Field.EXECUTION_WITHIN_FIRM, "fr12345"),
                Side.BUY), "CON-591"));
    }

    @Test
    void investmentDecisionUnderAgency() {
        // CON-571: AOTC, no transmitting firm, field 57 set, no decision maker is the firm.
        Rts22ReportBuilder b = Rts22Fixtures.agencyForPerson().set(Rts22Field.INVESTMENT_DECISION_WITHIN_FIRM, "ALGO2");
        assertTrue(hit(rules(b, Side.BUY), "CON-571"));
        b.set(Rts22Field.BUYER_DECISION_MAKER_ID, Rts22Fixtures.FIRM);
        assertFalse(hit(rules(b, Side.BUY), "CON-571"));
        // CON-572: transmitting firm for the buyer is not the buyer decision maker.
        b.set(Rts22Field.TRANSMITTING_FIRM_ID_BUYER, Rts22Fixtures.CLIENT_LEI);
        assertTrue(hit(rules(b, Side.BUY), "CON-572"));
        assertFalse(hit(rules(Rts22Fixtures.equityDeal().capacity(TradingCapacity.DEAL), Side.BUY), "CON-572"));
    }

    @Test
    void cfiConditionalFields() {
        List<String> e = rules(Rts22Fixtures.otcOption().set(Rts22Field.OPTION_TYPE, "PUTO")
                .set(Rts22Field.OPTION_EXERCISE_STYLE, "AMER").set(Rts22Field.DELIVERY_TYPE, "CASH")
                .set(Rts22Field.MATURITY_DATE, "2027-12-17").set(Rts22Field.NOTIONAL_CURRENCY_1, "")
                .set(Rts22Field.NOTIONAL_CURRENCY_2, "EUR").set(Rts22Field.EXPIRY_DATE, "2020-01-01"), Side.SELL);
        for (String code : List.of("CON-502", "CON-431", "CON-560", "CON-542", "CON-441", "CON-450", "CON-552")) {
            assertTrue(hit(e, code), code + " in " + e);
        }
        List<String> m = rules(Rts22Fixtures.otcOption().set(Rts22Field.OPTION_TYPE, "")
                .set(Rts22Field.STRIKE_PRICE, "").set(Rts22Field.EXPIRY_DATE, ""), Side.SELL);
        for (String code : List.of("CON-500", "CON-510", "CON-550")) {
            assertTrue(hit(m, code), code + " in " + m);
        }
        assertTrue(EsmaRules.cfi("OCESPS", "O*E***"));
        assertFalse(EsmaRules.cfi("OCASPS", "O*E***"));
        assertFalse(EsmaRules.cfi("OCE", "O*E***"));
    }

    @Test
    void cancellationOnlyChecksReference() {
        assertEquals(List.of(), Rts22Fixtures.build(new Rts22ReportBuilder(Rts22Fixtures.FIRM).cancellation(true),
                Side.BUY, 1, "C1").validateEsmaRules());
        assertTrue(Rts22Fixtures.build(new Rts22ReportBuilder(Rts22Fixtures.FIRM).cancellation(true),
                Side.BUY, 1, "c-1").validateEsmaRules().get(0).startsWith("XSD"));
    }

    @Test
    void concatBuyerIsNotMistakenForAnLei() {
        // Regression: a CONCAT code is 20 characters like an LEI, but ends in a letter or '#'.
        assertEquals(List.of(), Rts22Fixtures.build(Rts22Fixtures.agencyForPerson(), Side.BUY, 1, "P1").validate());
        String badLei = Rts22Fixtures.CPTY.substring(0, 18) + "00";
        assertFalse(Rts22Fixtures.build(Rts22Fixtures.equityDeal().counterparty(badLei), Side.BUY, 1, "P2")
                .validate().isEmpty());
    }

    @Test
    void newCodeListsInValidate() {
        List<String> e = Rts22Fixtures.build(Rts22Fixtures.equityDeal().set(Rts22Field.WAIVER_INDICATOR, "SIZE FOO")
                .set(Rts22Field.DELIVERY_TYPE, "XXXX"), Side.BUY, 1, "V1").validate();
        assertTrue(e.stream().anyMatch(s -> s.contains("'FOO'")), e.toString());
        assertTrue(e.stream().anyMatch(s -> s.contains("field 56")), e.toString());
    }
}
