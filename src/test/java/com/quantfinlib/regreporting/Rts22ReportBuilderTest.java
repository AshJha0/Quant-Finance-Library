package com.quantfinlib.regreporting;

import com.quantfinlib.fix.ExecutionReport;
import com.quantfinlib.fix.NewOrderSingle;
import com.quantfinlib.microstructure.Execution;
import com.quantfinlib.orderbook.Side;
import com.quantfinlib.regreporting.ReportingIdentifiers.Granularity;
import com.quantfinlib.regreporting.Rts22ReportBuilder.TradingCapacity;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Rts22ReportBuilderTest {
    // Synthetic LEIs: arbitrary 18-character bases with check digits computed by the library
    // and cross-checked below with BigInteger (independent computation). Not real entities.
    private static final String FIRM = lei("5493000TESTFIRM001");
    private static final String CPTY = lei("5493000TESTCPTY002");
    private static final Instant T = Instant.parse("2026-11-02T10:15:30.123456789Z");

    private static String lei(String base) {
        return base + ReportingIdentifiers.leiCheckDigits(base);
    }

    /** Independent MOD 97-10 check: letters to 10..35, the whole number mod 97 must be 1. */
    private static boolean bigIntLei(String lei) {
        StringBuilder sb = new StringBuilder();
        for (char c : lei.toCharArray()) {
            sb.append(Character.digit(c, 36));
        }
        return new BigInteger(sb.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    @Test
    void leiCheckDigitsAgreeWithBigIntegerAndCatchCorruption() {
        for (String base : List.of("5493000TESTFIRM001", "HWUPKR0MPOU8FGXBT3", "000000000000000000", "ZZZZZZZZZZZZZZZZZZ")) {
            String l = lei(base);
            assertTrue(ReportingIdentifiers.isValidLei(l), l);
            assertTrue(bigIntLei(l), l);
        }
        // Single-character substitutions are always caught by MOD 97-10.
        char[] c = FIRM.toCharArray();
        for (int i = 0; i < 18; i++) {
            char orig = c[i];
            c[i] = orig == 'A' ? 'B' : 'A';
            assertFalse(ReportingIdentifiers.isValidLei(new String(c)));
            c[i] = orig;
        }
        assertFalse(ReportingIdentifiers.isValidLei(FIRM.toLowerCase(java.util.Locale.ROOT)));
        assertFalse(ReportingIdentifiers.isValidLei(FIRM.substring(0, 19)));
        assertFalse(ReportingIdentifiers.isValidLei(FIRM.substring(0, 18) + "AB"));
    }

    @Test
    void isinLuhnCheck() {
        // Published examples: Apple US0378331005, BAE Systems GB0002634946, Siemens DE0007236101.
        assertTrue(ReportingIdentifiers.isValidIsin("US0378331005"));
        assertTrue(ReportingIdentifiers.isValidIsin("GB0002634946"));
        assertTrue(ReportingIdentifiers.isValidIsin("DE0007236101"));
        assertFalse(ReportingIdentifiers.isValidIsin("US0378331006"));
        assertFalse(ReportingIdentifiers.isValidIsin("US0378331"));
        assertFalse(ReportingIdentifiers.isValidIsin("1S0378331005"));
        assertFalse(ReportingIdentifiers.isValidIsin("us0378331005"));
    }

    @Test
    void micAndTimestamps() {
        assertTrue(ReportingIdentifiers.isValidMic("XLON"));
        assertTrue(ReportingIdentifiers.isValidMic("XOFF"));
        assertFalse(ReportingIdentifiers.isValidMic("XLO"));
        assertFalse(ReportingIdentifiers.isValidMic("xlon"));
        assertEquals("2026-11-02T10:15:30.123456Z", ReportingIdentifiers.formatTimestamp(T, Granularity.MICROS));
        assertEquals("2026-11-02T10:15:30.123Z", ReportingIdentifiers.formatTimestamp(T, Granularity.MILLIS));
        assertEquals("2026-11-02T10:15:30Z", ReportingIdentifiers.formatTimestamp(T, Granularity.SECONDS));
    }

    private static ExecutionReport fill(Side side) {
        var nos = new NewOrderSingle("c1", "BAE", side, 100, NewOrderSingle.ORD_TYPE_LIMIT, 101.25,
                NewOrderSingle.TIF_DAY);
        return ExecutionReport.filled(nos, "o1", "E-42", 101.25);
    }

    private static Rts22ReportBuilder builder() {
        return new Rts22ReportBuilder(FIRM).counterparty(CPTY).venue("XLON").isin("GB0002634946")
                .priceCurrency("GBP").referencePrefix("QFL-");
    }

    @Test
    void dealBuyMapsFirmAsBuyer() {
        Rts22TransactionReport r = builder().fromExecution(fill(Side.BUY), T);
        assertEquals("NEWT", r.get(Rts22Field.REPORT_STATUS));
        assertEquals("QFL-E-42", r.reference());
        assertEquals(FIRM, r.get(Rts22Field.EXECUTING_ENTITY_ID));
        assertEquals(FIRM, r.get(Rts22Field.SUBMITTING_ENTITY_ID));
        assertEquals(FIRM, r.get(Rts22Field.BUYER_ID));
        assertEquals(CPTY, r.get(Rts22Field.SELLER_ID));
        assertEquals("100", r.get(Rts22Field.QUANTITY));
        assertEquals("101.25", r.get(Rts22Field.PRICE));
        assertEquals("2026-11-02T10:15:30.123456Z", r.get(Rts22Field.TRADING_DATE_TIME));
        assertEquals("DEAL", r.get(Rts22Field.TRADING_CAPACITY));
        assertEquals("", r.get(Rts22Field.SHORT_SELLING_INDICATOR));
        assertEquals(List.of(), r.validate());
        assertEquals(65, r.fields().size());
        assertEquals(Rts22TransactionReport.REGIME, r.regime());
    }

    @Test
    void agencySellMapsClientAsSeller() {
        Rts22TransactionReport r = builder().capacity(TradingCapacity.AOTC).client("GB123456C")
                .fromExecution(fill(Side.SELL), T);
        assertEquals(CPTY, r.get(Rts22Field.BUYER_ID));
        assertEquals("GB123456C", r.get(Rts22Field.SELLER_ID));
        assertEquals(List.of(), r.validate());
    }

    @Test
    void nonFillRejectedAndFirmLeiChecked() {
        var nos = new NewOrderSingle("c1", "BAE", Side.BUY, 100, NewOrderSingle.ORD_TYPE_LIMIT, 1.0,
                NewOrderSingle.TIF_DAY);
        assertThrows(IllegalArgumentException.class,
                () -> builder().fromExecution(ExecutionReport.accepted(nos, "o", "e"), T));
        assertThrows(IllegalArgumentException.class, () -> new Rts22ReportBuilder("NOTANLEI"));
    }

    @Test
    void validationFlagsBadFields() {
        String badCpty = CPTY.substring(0, 19) + (CPTY.charAt(19) == '0' ? '1' : '0');
        Rts22TransactionReport r = new Rts22ReportBuilder(FIRM).counterparty(badCpty).venue("XL")
                .isin("GB0002634947").priceCurrency("gbp")
                .set(Rts22Field.SHORT_SELLING_INDICATOR, "MAYBE")
                .fromExecution(fill(Side.BUY), T);
        List<String> e = r.validate();
        assertTrue(e.stream().anyMatch(s -> s.startsWith("field 16 ")), e::toString);
        assertTrue(e.stream().anyMatch(s -> s.startsWith("field 36 ")), e::toString);
        assertTrue(e.stream().anyMatch(s -> s.startsWith("field 41 ")), e::toString);
        assertTrue(e.stream().anyMatch(s -> s.startsWith("field 34 ")), e::toString);
        assertTrue(e.stream().anyMatch(s -> s.startsWith("field 62 ")), e::toString);
        Rts22TransactionReport blank = new Rts22ReportBuilder(FIRM).fromExecution(fill(Side.BUY), T);
        assertTrue(blank.validate().stream().anyMatch(s -> s.contains("SELLER_ID") && s.contains("mandatory")));
    }

    @Test
    void microstructureExecutionMapsVenueAndNanos() {
        long nanos = T.getEpochSecond() * 1_000_000_000L + T.getNano();
        var ex = new Execution("GB0002634946", Side.SELL, 50.5, 200, nanos, "XLON");
        Rts22TransactionReport r = new Rts22ReportBuilder(FIRM).counterparty(CPTY)
                .granularity(Granularity.MILLIS).fromExecution(ex, "T-1");
        assertEquals("XLON", r.get(Rts22Field.VENUE));
        assertEquals("GB0002634946", r.get(Rts22Field.INSTRUMENT_ID));
        assertEquals("2026-11-02T10:15:30.123Z", r.get(Rts22Field.TRADING_DATE_TIME));
        assertEquals(FIRM, r.get(Rts22Field.SELLER_ID));
        assertEquals(List.of(), r.validate());
    }

    @Test
    void csvAndXmlOutput() {
        Rts22TransactionReport r = builder().set(Rts22Field.INSTRUMENT_FULL_NAME, "BAE SYSTEMS, \"ORD\"")
                .fromExecution(fill(Side.BUY), T);
        String[] lines = r.toCsv().split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("1_REPORT_STATUS,2_TRANSACTION_REFERENCE_NUMBER,"));
        assertTrue(lines[0].endsWith("65_SECURITIES_FINANCING_TRANSACTION_INDICATOR"));
        assertTrue(lines[1].contains("\"BAE SYSTEMS, \"\"ORD\"\"\""));
        String xml = r.toXml();
        assertTrue(xml.contains("NOT validated against the ESMA XSD"));
        assertTrue(xml.contains("<Field n=\"42\" name=\"INSTRUMENT_FULL_NAME\">BAE SYSTEMS, &quot;ORD&quot;</Field>"));
        assertFalse(xml.contains("name=\"NET_AMOUNT\""));
    }

    @Test
    void fieldTableHas65NumberedFields() {
        Rts22Field[] f = Rts22Field.values();
        assertEquals(65, f.length);
        for (int i = 0; i < f.length; i++) {
            assertEquals(i + 1, f[i].number());
        }
    }

    @Test
    void sinkReceivesReports() {
        List<TransactionReport> out = new ArrayList<>();
        TransactionReportSink sink = out::add;
        sink.submit(builder().fromExecution(fill(Side.BUY), T));
        assertEquals("QFL-E-42", out.get(0).reference());
    }
}
