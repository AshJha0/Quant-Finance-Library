package com.quantfinlib.regreporting.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quantfinlib.regreporting.ReportingIdentifiers;
import com.quantfinlib.regreporting.TransactionReport;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class RegistryTest {

    private static InputStream res(String name) {
        return Objects.requireNonNull(RegistryTest.class.getResourceAsStream(name), name);
    }

    private static Reader reader(String name) {
        return new InputStreamReader(res(name), StandardCharsets.UTF_8);
    }

    static GleifGoldenCopyLeiRegistry leis() throws IOException {
        try (Reader r = reader("golden-copy-fixture.csv")) {
            return GleifGoldenCopyLeiRegistry.load(r);
        }
    }

    static Iso10383MicRegistry mics() throws IOException {
        try (Reader r = reader("iso10383-fixture.csv")) {
            return Iso10383MicRegistry.load(r);
        }
    }

    static FirdsInstrumentRegistry firds() throws IOException {
        try (InputStream in = res("firds-fixture.xml")) {
            return FirdsInstrumentRegistry.load(in);
        }
    }

    @Test
    void goldenCopyStatusesAndQuotedNames() throws IOException {
        GleifGoldenCopyLeiRegistry r = leis();
        assertEquals(4, r.size());
        assertEquals(LeiStatus.ISSUED, r.status("TESTLEI0000000000A01"));
        assertEquals("Example Bank, Plc", r.lookup("TESTLEI0000000000A01").orElseThrow().legalName());
        assertEquals("Lapsed \"Holdings\" Ltd", r.lookup("TESTLEI0000000000B02").orElseThrow().legalName());
        assertEquals(LeiStatus.LAPSED, r.status("TESTLEI0000000000B02"));
        assertTrue(r.status("TESTLEI0000000000C03").isMaintained());
        assertFalse(r.status("TESTLEI0000000000D04").isMaintained());
        assertEquals(LeiStatus.UNKNOWN, r.status("NOPE"));
    }

    @Test
    void goldenCopyMissingColumnRejected() {
        assertThrows(IOException.class, () -> GleifGoldenCopyLeiRegistry.load(new StringReader("LEI,Name\nX,Y\n")));
        assertThrows(IOException.class, () -> GleifGoldenCopyLeiRegistry.load(new StringReader("")));
    }

    @Test
    void micStatuses() throws IOException {
        Iso10383MicRegistry r = mics();
        assertEquals(3, r.size());
        assertEquals(MicStatus.ACTIVE, r.status("TSTA"));
        assertTrue(r.status("TSTB").isUsable());
        assertTrue(r.lookup("TSTB").orElseThrow().isSegment());
        assertEquals("TEST EXCHANGE A, SEGMENT B", r.lookup("TSTB").orElseThrow().name());
        assertEquals(MicStatus.EXPIRED, r.status("TSTX"));
        assertFalse(r.status("TSTX").isUsable());
        assertEquals(MicStatus.UNKNOWN, r.status("ZZZZ"));
    }

    @Test
    void firdsMergesVenuesPerIsin() throws IOException {
        FirdsInstrumentRegistry r = firds();
        assertEquals(2, r.size());
        InstrumentRecord a = r.lookup("US0378331005").orElseThrow();
        assertEquals(Set.of("TSTA", "TSTB"), a.tradingVenues());
        assertEquals("ESVUFR", a.cfi());
        assertEquals("TEST EQUITY ORD", a.fullName());
        assertTrue(r.isAdmittedOn("US0378331005", "TSTB"));
        assertFalse(r.isAdmittedOn("US0378331005", "TSTX"));
        assertTrue(r.isKnown("DE0005140008"));
        assertFalse(r.isKnown("GB0002634946"));
    }

    @Test
    void firdsRejectsDtd() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE a [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><a>&x;</a>";
        assertThrows(IOException.class, () -> FirdsInstrumentRegistry.load(
                new java.io.ByteArrayInputStream(xxe.getBytes(StandardCharsets.UTF_8))));
    }

    private static final String API_BODY = "{\"data\":{\"type\":\"lei-records\",\"id\":\"X\",\"attributes\":{"
            + "\"lei\":\"X\",\"entity\":{\"legalName\":{\"name\":\"Example \\\"Q\\\" AG\",\"language\":\"de\"},"
            + "\"status\":\"ACTIVE\"},\"registration\":{\"initialRegistrationDate\":\"2014-01-01T00:00:00Z\","
            + "\"status\":\"LAPSED\",\"corroborationLevel\":\"FULLY_CORROBORATED\"}}}}";

    @Test
    void apiParseTakesRegistrationNotEntityStatus() {
        LeiRecord r = GleifApiLeiRegistry.parse("X", API_BODY);
        assertEquals("Example \"Q\" AG", r.legalName());
        assertEquals(LeiStatus.LAPSED, r.status());
    }

    @Test
    void apiClientAgainstLocalServer() throws IOException {
        String lei = "TESTLEI00000000000" + ReportingIdentifiers.leiCheckDigits("TESTLEI00000000000");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/lei-records/", ex -> {
            boolean hit = ex.getRequestURI().getPath().endsWith(lei);
            byte[] b = (hit ? API_BODY : "{}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(hit ? 200 : 404, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/lei-records/");
            GleifApiLeiRegistry api = new GleifApiLeiRegistry(
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), base, Duration.ofSeconds(5));
            assertEquals(LeiStatus.LAPSED, api.status(lei));
            String other = "TESTLEI00000000000" .substring(0, 17) + "1";
            String otherLei = other + ReportingIdentifiers.leiCheckDigits(other);
            assertTrue(api.lookup(otherLei).isEmpty());
            assertTrue(api.lookup("not-an-lei").isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "QFL_NETWORK_TESTS", matches = "1")
    void liveGleifLookup() {
        // GLEIF's own LEI (GLEIF publishes it on its site): 506700GE1G29325QX363.
        GleifApiLeiRegistry api = new GleifApiLeiRegistry(Duration.ofSeconds(10), Duration.ofSeconds(20));
        LeiRecord r = api.lookup("506700GE1G29325QX363").orElseThrow();
        assertTrue(r.legalName().toUpperCase(java.util.Locale.ROOT).contains("GLEIF")
                || r.legalName().contains("Global Legal Entity Identifier"), r.legalName());
        assertTrue(r.status().isMaintained(), r.status().name());
    }

    private record Rep(Map<String, String> fields) implements TransactionReport {
        @Override
        public String regime() {
            return "TEST";
        }

        @Override
        public String reference() {
            return "R1";
        }

        @Override
        public List<String> validate() {
            return List.of();
        }
    }

    @Test
    void checkerRejectsLapsedUnknownExpiredAndForwardsClean() throws IOException {
        List<TransactionReport> sent = new ArrayList<>();
        ReferenceDataChecker c = new ReferenceDataChecker(leis(), firds(), mics(),
                Set.of("buyer", "seller"), Set.of("isin"), Set.of("venue"), sent::add);
        Rep clean = new Rep(Map.of("buyer", "TESTLEI0000000000A01", "seller", "TESTLEI0000000000C03",
                "isin", "US0378331005", "venue", "TSTB"));
        c.submit(clean);
        assertEquals(1, sent.size());
        Rep bad = new Rep(Map.of("buyer", "TESTLEI0000000000B02", "seller", "",
                "isin", "GB0002634946", "venue", "TSTX"));
        List<String> p = c.check(bad);
        assertEquals(3, p.size(), p.toString());
        assertTrue(p.get(0).contains("LAPSED"));
        assertThrows(IllegalArgumentException.class, () -> c.submit(bad));
        assertEquals(1, sent.size());
        assertTrue(c.check(new Rep(Map.of("venue", "XOFF"))).isEmpty());
    }
}
