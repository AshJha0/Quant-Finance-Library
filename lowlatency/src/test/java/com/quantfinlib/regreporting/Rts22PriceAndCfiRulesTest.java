package com.quantfinlib.regreporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quantfinlib.core.Side;
import com.quantfinlib.regreporting.registry.InstrumentRecord;
import com.quantfinlib.regreporting.registry.InstrumentRegistry;
import com.quantfinlib.regreporting.registry.ReferenceData;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * CON-330 (rule 260), CON-331 (rule 261), CON-410 (rule 130), CON-470 (rule 150) and
 * CON-430 (rule 222) of ESMA65-8-2594 Annex 1 v1.7 (published reference), and the price
 * notations of RTS 22 Annex I Table 2 field 33 as written to the auth.016 XSD choice
 * {@code SecuritiesTransactionPrice2Choice} (published reference). Fixtures are invented.
 */
class Rts22PriceAndCfiRulesTest {
    private static final String BOND = "XS0000000009";

    private static boolean hit(List<String> errs, String code) {
        return errs.stream().anyMatch(s -> s.startsWith(code + ":"));
    }

    /** OTC bond described in full (field 43 DBFTFB), priced as given. */
    private static Rts22TransactionReport otcBond(String price, String quantityCcy) {
        Rts22ReportBuilder b = Rts22Fixtures.equityDeal().venue("XXXX").isin("")
                .set(Rts22Field.INSTRUMENT_ID, "")
                .set(Rts22Field.INSTRUMENT_FULL_NAME, "TEST 4% 2030")
                .set(Rts22Field.INSTRUMENT_CLASSIFICATION, "DBFTFB")
                .set(Rts22Field.NOTIONAL_CURRENCY_1, "EUR")
                .set(Rts22Field.PRICE_MULTIPLIER, "1")
                .set(Rts22Field.UNDERLYING_INSTRUMENT_ID, "GB0002634946")
                .set(Rts22Field.MATURITY_DATE, "2030-01-01")
                .set(Rts22Field.DELIVERY_TYPE, "CASH")
                .set(Rts22Field.QUANTITY_CURRENCY, quantityCcy)
                .priceCurrency("EUR");
        Rts22TransactionReport r = Rts22Fixtures.build(b, Side.BUY, 99.5, "B1");
        return copyWith(r, Rts22Field.PRICE, price);
    }

    private static Rts22TransactionReport copyWith(Rts22TransactionReport r, Rts22Field f, String v) {
        Map<Rts22Field, String> m = new EnumMap<>(Rts22Field.class);
        for (Rts22Field x : Rts22Field.values()) {
            m.put(x, r.get(x));
        }
        m.put(f, v);
        return new Rts22TransactionReport(m);
    }

    @Test
    void con330PercentagePriceWithUnitQuantityOnDebtCfi() {
        assertTrue(hit(otcBond("PCTG:99.5", "").validateEsmaRules(), "CON-330"));
        // Nominal quantity (field 31 set): a percentage is fine.
        assertFalse(hit(otcBond("PCTG:99.5", "EUR").validateEsmaRules(), "CON-330"));
        // Monetary price in units: fine.
        assertFalse(hit(otcBond("995.0", "").validateEsmaRules(), "CON-330"));
        // Yield is not a percentage price for this rule.
        assertFalse(hit(otcBond("YLD:4.1", "").validateEsmaRules(), "CON-330"));
    }

    @Test
    void nonMonetaryPriceNeedsNoCurrency() {
        Rts22TransactionReport r = copyWith(otcBond("PCTG:99.5", "EUR"), Rts22Field.PRICE_CURRENCY, "");
        assertFalse(r.validateEsmaRules().stream().anyMatch(s -> s.contains("needs field 34")),
                r.validateEsmaRules().toString());
        Rts22TransactionReport m = copyWith(otcBond("99.5", "EUR"), Rts22Field.PRICE_CURRENCY, "");
        assertTrue(m.validateEsmaRules().stream().anyMatch(s -> s.contains("needs field 34")));
        Rts22TransactionReport strike = copyWith(Rts22Fixtures.build(Rts22Fixtures.otcOption(), Side.BUY, 5, "O1"),
                Rts22Field.STRIKE_PRICE_CURRENCY, "");
        assertTrue(strike.validateEsmaRules().stream().anyMatch(s -> s.contains("needs field 52")));
        Rts22TransactionReport bpsStrike = copyWith(strike, Rts22Field.STRIKE_PRICE, "BSPS:25");
        assertFalse(bpsStrike.validateEsmaRules().stream().anyMatch(s -> s.contains("needs field 52")));
    }

    @Test
    void priceNotationsInAuth016() {
        String pct = otcBond("PCTG:99.5", "EUR").toAuth016Xml();
        assertTrue(pct.contains("<Pctg>99.5</Pctg>"), pct);
        assertFalse(pct.contains("<MntryVal>"));
        assertTrue(otcBond("YLD:4.1", "EUR").toAuth016Xml().contains("<Yld>4.1</Yld>"));
        assertTrue(otcBond("BSPS:35", "EUR").toAuth016Xml().contains("<BsisPts>35</BsisPts>"));
        assertTrue(otcBond("99.5", "EUR").toAuth016Xml().contains("<Amt Ccy=\"EUR\">99.5</Amt>"));
        assertEquals(List.of(), otcBond("PCTG:99.5", "EUR").validate().stream()
                .filter(s -> s.contains("field 33")).toList());
        assertTrue(otcBond("PCTG:abc", "EUR").validate().stream().anyMatch(s -> s.contains("field 33")));
    }

    @Test
    void priceNotationParsing() {
        assertEquals(PriceNotation.MONETARY, PriceNotation.of("10.5"));
        assertEquals(PriceNotation.MONETARY, PriceNotation.of(""));
        assertEquals(PriceNotation.PERCENTAGE, PriceNotation.of("PCTG:1"));
        assertEquals(PriceNotation.YIELD, PriceNotation.of("YLD:1"));
        assertEquals(PriceNotation.BASIS_POINTS, PriceNotation.of("BSPS:1"));
        assertEquals(PriceNotation.NO_PRICE, PriceNotation.of("PNDG"));
        assertEquals(PriceNotation.NO_PRICE, PriceNotation.of("NOAP"));
        assertEquals("1", PriceNotation.number("PCTG:1"));
        assertEquals("-2", PriceNotation.number("-2"));
        assertEquals("PNDG", PriceNotation.number("PNDG"));
        assertEquals("PCTG:99.25", PriceNotation.PERCENTAGE.format("99.25"));
        assertEquals("7", PriceNotation.MONETARY.format("7"));
        assertEquals("YLD:", PriceNotation.YIELD.prefix());
        assertThrows(IllegalArgumentException.class, () -> PriceNotation.NO_PRICE.format("1"));
        assertThrows(NullPointerException.class, () -> PriceNotation.BASIS_POINTS.format(null));
    }

    private static ReferenceData bondRefs() {
        InstrumentRegistry ins = new InstrumentRegistry() {
            @Override
            public Optional<InstrumentRecord> lookup(String isin) {
                return BOND.equals(isin) ? Optional.of(new InstrumentRecord(BOND, "BOND", "DBFTFB", Set.of("XPAR")))
                        : Optional.empty();
            }
        };
        return new ReferenceData(Rts22ReferenceRulesTest.leis(), ins, Rts22ReferenceRulesTest.mics(), null, null,
                Set.of());
    }

    private static Rts22TransactionReport bondOnXpar(String price, String quantityCcy) {
        Rts22TransactionReport r = Rts22Fixtures.build(Rts22Fixtures.equityDeal().venue("XPAR").isin(BOND)
                .priceCurrency("EUR").set(Rts22Field.QUANTITY_CURRENCY, quantityCcy)
                .set(Rts22Field.NET_AMOUNT, "1000"), Side.BUY, 99.5, "B2");
        return copyWith(r, Rts22Field.PRICE, price);
    }

    @Test
    void con331PercentagePriceForDebtInReferenceData() {
        List<String> e = bondOnXpar("PCTG:99.5", "").validateWithReferenceData(bondRefs(),
                Rts22ReferenceRulesTest.SUBMIT);
        assertTrue(hit(e, "CON-331"), e.toString());
        assertFalse(hit(bondOnXpar("PCTG:99.5", "EUR").validateWithReferenceData(bondRefs(),
                Rts22ReferenceRulesTest.SUBMIT), "CON-331"));
        assertFalse(hit(bondOnXpar("99.5", "").validateWithReferenceData(bondRefs(),
                Rts22ReferenceRulesTest.SUBMIT), "CON-331"));
        // An equity in the reference data is not a debt instrument.
        Rts22TransactionReport eq = copyWith(Rts22Fixtures.build(Rts22Fixtures.equityDeal().venue("XPAR")
                .priceCurrency("EUR"), Side.BUY, 1, "E1"), Rts22Field.PRICE, "PCTG:50");
        assertFalse(hit(eq.validateWithReferenceData(Rts22ReferenceRulesTest.refs(Rts22ReferenceRulesTest.leis()),
                Rts22ReferenceRulesTest.SUBMIT), "CON-331"));
    }

    @Test
    void con410And470CheckDigits() {
        Rts22TransactionReport bad41 = Rts22Fixtures.build(Rts22Fixtures.equityDeal().isin("GB0002634947"),
                Side.BUY, 1, "C1");
        assertTrue(hit(bad41.validateEsmaRules(), "CON-410"));
        assertFalse(hit(Rts22Fixtures.build(Rts22Fixtures.equityDeal(), Side.BUY, 1, "C2").validateEsmaRules(),
                "CON-410"));
        Rts22TransactionReport bad47 = copyWith(Rts22Fixtures.build(Rts22Fixtures.otcOption(), Side.BUY, 1, "C3"),
                Rts22Field.UNDERLYING_INSTRUMENT_ID, "US0378331005,US0378331006");
        List<String> e = bad47.validateEsmaRules();
        assertTrue(e.stream().anyMatch(s -> s.startsWith("CON-470:") && s.contains("US0378331006")), e.toString());
        assertFalse(e.stream().anyMatch(s -> s.startsWith("CON-470:") && s.contains("US0378331005")), e.toString());
        // Wrong length is the XSD's business, not CON-410.
        assertFalse(hit(Rts22Fixtures.build(Rts22Fixtures.equityDeal().isin("GB000263494"), Side.BUY, 1, "C4")
                .validateEsmaRules(), "CON-410"));
    }

    @Test
    void con430CfiTableIsCallerInput() {
        Rts22TransactionReport r = otcBond("99.5", "EUR");
        ReferenceData none = ReferenceData.of(null, null, null);
        assertFalse(hit(r.validateWithReferenceData(none, Rts22ReferenceRulesTest.SUBMIT), "CON-430"));
        ReferenceData table = none.withCfiCodes(Set.of("ESVUFR")::contains);
        assertTrue(hit(r.validateWithReferenceData(table, Rts22ReferenceRulesTest.SUBMIT), "CON-430"));
        ReferenceData ok = none.withCfiCodes(Set.of("DBFTFB")::contains);
        assertFalse(hit(r.validateWithReferenceData(ok, Rts22ReferenceRulesTest.SUBMIT), "CON-430"));
        // Field 43 empty: nothing to check.
        Rts22TransactionReport eq = Rts22Fixtures.build(Rts22Fixtures.equityDeal(), Side.BUY, 1, "E2");
        assertFalse(hit(eq.validateWithReferenceData(table, Rts22ReferenceRulesTest.SUBMIT), "CON-430"));
    }
}
