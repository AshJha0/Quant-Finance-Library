package com.quantfinlib.reporting.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quantfinlib.reporting.registry.CodeRegistry.Window;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The 3.0 loader columns: GLEIF dates and entity status, FIRDS admission windows and the
 * commodity flag, ISO 10383 country, category and dates. Hand-made fixtures that use the
 * real column and element names (checked against the files and schemas listed in
 * {@code RealSampleLoaderTest}); they cover edge cases the real extracts do not. The rules
 * the data feeds are cited where they are used.
 */
class ReferenceDataLoaderDatesTest {
    private static final LocalDate D = LocalDate.of(2026, 11, 2);

    @Test
    void csvDateFormats() throws IOException {
        assertEquals(D, Csv.date("2026-11-02"));
        assertEquals(D, Csv.date("20261102"));
        assertEquals(D, Csv.date("2026-11-02T00:00:00Z"));
        assertEquals(D, Csv.date(" 2026-11-02T23:59:59+02:00 "));
        assertEquals(D.minusDays(1), Csv.date("2026-11-02T00:30:00+01:00"), "offset converted to the UTC date");
        assertEquals(D, Csv.date("2026-11-02T00:30:00"), "no zone: own date");
        assertThrows(IOException.class, () -> Csv.date("2026-11-0xT00:30:00"));
        assertNull(Csv.date(""));
        assertNull(Csv.date("   "));
        assertThrows(IOException.class, () -> Csv.date("02/11/2026"));
        assertThrows(IOException.class, () -> Csv.date("2026113"));
    }

    // GLEIF golden copy: the dates and entity status feed CON-040 to CON-270.

    private static final String GLEIF = "LEI,Entity.LegalName,Entity.EntityStatus,Registration.RegistrationStatus,"
            + "Registration.InitialRegistrationDate,Registration.LastUpdateDate\n"
            + "TESTLEI0000000000A01,Alpha,ACTIVE,ISSUED,2015-03-01T10:00:00Z,2026-01-05T00:00:00Z\n"
            + "TESTLEI0000000000B02,Beta,INACTIVE,LAPSED,2012-01-01,2020-06-30\n"
            + "TESTLEI0000000000C03,Gamma,,ISSUED,,\n";

    @Test
    void goldenCopyReadsDatesAndEntityStatus() throws IOException {
        GleifGoldenCopyLeiRegistry r = GleifGoldenCopyLeiRegistry.load(new StringReader(GLEIF));
        LeiValidity a = r.validityOn("TESTLEI0000000000A01", D).orElseThrow();
        assertEquals(LeiStatus.ISSUED, a.status());
        assertEquals(Optional.of(LocalDate.of(2015, 3, 1)), a.initialRegistrationDate());
        assertEquals(Optional.of(LocalDate.of(2026, 1, 5)), a.lastUpdateDate());
        assertTrue(a.entityActive());
        LeiValidity b = r.validityOn("TESTLEI0000000000B02", D).orElseThrow();
        assertFalse(b.entityActive());
        assertEquals(LeiStatus.LAPSED, b.status());
        assertEquals(Optional.of(LocalDate.of(2020, 6, 30)), b.lastUpdateDate());
        LeiValidity c = r.validityOn("TESTLEI0000000000C03", D).orElseThrow();
        assertTrue(c.entityActive(), "a blank entity status keeps the entity active");
        assertEquals(Optional.empty(), c.initialRegistrationDate());
        assertEquals(Optional.empty(), r.validityOn("NOPE", D));
    }

    @Test
    void goldenCopyWithoutDateColumnsIsStatusOnly() throws IOException {
        GleifGoldenCopyLeiRegistry r = GleifGoldenCopyLeiRegistry.load(new StringReader(
                "LEI,Entity.LegalName,Registration.RegistrationStatus\nTESTLEI0000000000A01,Alpha,RETIRED\n"));
        assertEquals(LeiValidity.statusOnly(LeiStatus.RETIRED), r.validityOn("TESTLEI0000000000A01", D).orElseThrow());
    }

    @Test
    void goldenCopyEachOptionalColumnAloneAndFirst() throws IOException {
        LeiValidity entityOnly = GleifGoldenCopyLeiRegistry.load(new StringReader(
                "LEI,Entity.LegalName,Registration.RegistrationStatus,Entity.EntityStatus\n"
                        + "TESTLEI0000000000A01,Alpha,ISSUED,INACTIVE\n")).validityOn("TESTLEI0000000000A01", D).orElseThrow();
        assertFalse(entityOnly.entityActive());
        LeiValidity first = GleifGoldenCopyLeiRegistry.load(new StringReader(
                "Registration.InitialRegistrationDate,LEI,Entity.LegalName,Registration.RegistrationStatus\n"
                        + "2015-03-01,TESTLEI0000000000A01,Alpha,ISSUED\n")).validityOn("TESTLEI0000000000A01", D).orElseThrow();
        assertEquals(Optional.of(LocalDate.of(2015, 3, 1)), first.initialRegistrationDate());
        LeiValidity updatedFirst = GleifGoldenCopyLeiRegistry.load(new StringReader(
                "Registration.LastUpdateDate,LEI,Entity.LegalName,Registration.RegistrationStatus\n"
                        + "2020-01-02,TESTLEI0000000000A01,Alpha,ISSUED\n")).validityOn("TESTLEI0000000000A01", D).orElseThrow();
        assertEquals(Optional.of(LocalDate.of(2020, 1, 2)), updatedFirst.lastUpdateDate());
        LeiValidity entityFirst = GleifGoldenCopyLeiRegistry.load(new StringReader(
                "Entity.EntityStatus,LEI,Entity.LegalName,Registration.RegistrationStatus\n"
                        + "INACTIVE,TESTLEI0000000000A01,Alpha,ISSUED\n")).validityOn("TESTLEI0000000000A01", D).orElseThrow();
        assertFalse(entityFirst.entityActive());
    }

    @Test
    void goldenCopyBadDateRejected() {
        assertThrows(IOException.class, () -> GleifGoldenCopyLeiRegistry.load(new StringReader(
                "LEI,Entity.LegalName,Registration.RegistrationStatus,Registration.InitialRegistrationDate\n"
                        + "TESTLEI0000000000A01,Alpha,ISSUED,yesterday\n")));
    }

    @Test
    void gleifApiParsesDatesAndEntityStatus() {
        String json = "{\"data\":{\"attributes\":{\"lei\":\"X\",\"entity\":{\"legalName\":{\"name\":\"Alpha\"},"
                + "\"status\":\"INACTIVE\"},\"registration\":{\"initialRegistrationDate\":\"2015-03-01T10:00:00Z\","
                + "\"lastUpdateDate\":\"2026-01-05T08:00:00+01:00\",\"status\":\"ISSUED\"}}}}";
        LeiValidity v = GleifApiLeiRegistry.parseValidity("X", json);
        assertEquals(LeiStatus.ISSUED, v.status());
        assertFalse(v.entityActive());
        assertEquals(Optional.of(LocalDate.of(2015, 3, 1)), v.initialRegistrationDate());
        assertEquals(Optional.of(LocalDate.of(2026, 1, 5)), v.lastUpdateDate());
        LeiValidity bare = GleifApiLeiRegistry.parseValidity("X",
                "{\"entity\":{\"status\":\"ACTIVE\"},\"registration\":{\"status\":\"LAPSED\",\"lastUpdateDate\":\"bad\"}}");
        assertTrue(bare.entityActive());
        assertEquals(LeiStatus.LAPSED, bare.status());
        assertEquals(Optional.empty(), bare.lastUpdateDate());
        assertEquals(Optional.empty(), bare.initialRegistrationDate());
    }

    // FIRDS: admission windows feed CON-361, CON-411 and CON-471; CmmdtyDerivInd feeds CON-640.

    private static final String FIRDS = """
            <BizData><Pyld><Document><FinInstrmRptgRefDataRpt>
              <RefData>
                <FinInstrmGnlAttrbts><Id>XS0000000009</Id><FullNm>BOND</FullNm><ClssfctnTp>DBFTFB</ClssfctnTp>
                  <CmmdtyDerivInd>false</CmmdtyDerivInd></FinInstrmGnlAttrbts>
                <TradgVnRltdAttrbts><Id>XPAR</Id><ReqForAdmssnDt>2026-10-01T00:00:00Z</ReqForAdmssnDt>
                  <AdmssnApprvlDtByIssr>2026-10-05T00:00:00Z</AdmssnApprvlDtByIssr>
                  <FrstTradDt>2026-10-07T00:00:00Z</FrstTradDt><TermntnDt>2026-12-31T23:59:59Z</TermntnDt>
                </TradgVnRltdAttrbts>
              </RefData>
              <RefData>
                <FinInstrmGnlAttrbts><Id>XS0000000009</Id><FullNm>BOND</FullNm></FinInstrmGnlAttrbts>
                <TradgVnRltdAttrbts><Id>XPAR</Id><FrstTradDt>2026-09-15</FrstTradDt>
                  <TermntnDt>2026-11-30</TermntnDt></TradgVnRltdAttrbts>
                <TradgVnRltdAttrbts><Id>XETR</Id><FrstTradDt>2026-11-10</FrstTradDt></TradgVnRltdAttrbts>
              </RefData>
              <RefData>
                <FinInstrmGnlAttrbts><Id>DE000A0AAAA9</Id><FullNm>POWER</FullNm><ClssfctnTp>FCEPSX</ClssfctnTp>
                  <CmmdtyDerivInd>true</CmmdtyDerivInd></FinInstrmGnlAttrbts>
                <TradgVnRltdAttrbts><Id>XEEE</Id></TradgVnRltdAttrbts>
              </RefData>
            </FinInstrmRptgRefDataRpt></Document></Pyld></BizData>
            """;

    private static FirdsInstrumentRegistry firds() throws IOException {
        return FirdsInstrumentRegistry.load(new ByteArrayInputStream(FIRDS.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void firdsWindowsMergeAcrossRepeatedRecords() throws IOException {
        FirdsInstrumentRegistry r = firds();
        assertEquals(2, r.size());
        String bond = "XS0000000009";
        InstrumentRecord rec = r.lookup(bond).orElseThrow();
        // XPAR: earliest start over both records (2026-09-15) to the later termination (2026-12-31).
        assertEquals(new Window(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 12, 31)), rec.admissions().get("XPAR"));
        assertEquals(new Window(LocalDate.of(2026, 11, 10), null), rec.admissions().get("XETR"));
        assertEquals(Set.of("XPAR", "XETR"), rec.tradingVenues());
        assertFalse(r.isKnownOn(bond, LocalDate.of(2026, 9, 14)));
        assertTrue(r.isKnownOn(bond, LocalDate.of(2026, 9, 15)));
        assertTrue(r.isAdmittedOn(bond, "XPAR", LocalDate.of(2026, 12, 31)));
        assertFalse(r.isAdmittedOn(bond, "XPAR", LocalDate.of(2027, 1, 1)));
        assertTrue(r.isKnownOn(bond, LocalDate.of(2027, 1, 1)), "still on XETR, open-ended");
        assertFalse(r.isAdmittedOn(bond, "XETR", D));
        assertFalse(r.isAdmittedOn(bond, "XLON", D));
        assertFalse(r.isKnownOn("NOPE00000000", D));
        assertFalse(r.isAdmittedOn("NOPE00000000", "XPAR", D));
        assertEquals("DBFTFB", rec.cfi());
    }

    @Test
    void firdsStartIsEarliestOfTheThreeDates() throws IOException {
        // Only the first record, on its own: request 10-01, approval 10-05, first trade 10-07.
        String one = FIRDS.substring(0, FIRDS.indexOf("</RefData>") + 10) + "</FinInstrmRptgRefDataRpt></Document></Pyld></BizData>";
        FirdsInstrumentRegistry r = FirdsInstrumentRegistry.load(new ByteArrayInputStream(one.getBytes(StandardCharsets.UTF_8)));
        assertEquals(LocalDate.of(2026, 10, 1), r.lookup("XS0000000009").orElseThrow().admissions().get("XPAR").from());
    }

    @Test
    void firdsCommodityFlag() throws IOException {
        FirdsInstrumentRegistry r = firds();
        assertEquals(Optional.of(true), r.isCommodityDerivative("DE000A0AAAA9"));
        assertEquals(Optional.of(false), r.isCommodityDerivative("XS0000000009"));
        assertEquals(Optional.empty(), r.isCommodityDerivative("NOPE00000000"));
        // A venue with no dates is present on every date (2.4 behaviour).
        assertTrue(r.isAdmittedOn("DE000A0AAAA9", "XEEE", LocalDate.of(1999, 1, 1)));
    }

    @Test
    void firdsBadDateRejected() {
        String bad = "<RefData><FinInstrmGnlAttrbts><Id>XS0000000009</Id></FinInstrmGnlAttrbts>"
                + "<TradgVnRltdAttrbts><Id>XPAR</Id><FrstTradDt>soon</FrstTradDt></TradgVnRltdAttrbts></RefData>";
        assertThrows(IOException.class,
                () -> FirdsInstrumentRegistry.load(new ByteArrayInputStream(bad.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void instrumentRecordShapes() {
        InstrumentRecord old = new InstrumentRecord("I", "N", "", Set.of("XPAR"));
        assertTrue(old.isAdmittedOn("XPAR", D));
        assertTrue(old.isKnownOn(D));
        assertNull(old.commodityDerivative());
        InstrumentRecord none = new InstrumentRecord("I", "N", "", Set.of());
        assertTrue(none.isKnownOn(D), "a record with no venues is known (2.4: present in the file)");
        InstrumentRecord w = new InstrumentRecord("I", "N", "", Set.of(),
                Map.of("XETR", new Window(D, D)), Boolean.TRUE);
        assertEquals(Set.of("XETR"), w.tradingVenues());
        assertTrue(w.isKnownOn(D));
        assertFalse(w.isKnownOn(D.plusDays(1)));
        assertEquals(new Window(null, D), InstrumentRecord.widen(new Window(null, D.minusDays(1)), new Window(D, D)));
        assertEquals(new Window(D.minusDays(1), null),
                InstrumentRecord.widen(new Window(D, null), new Window(D.minusDays(1), D)));
        assertEquals(new Window(D, D.plusDays(2)),
                InstrumentRecord.widen(new Window(D.plusDays(1), D.plusDays(2)), new Window(D, D.plusDays(1))));
    }

    // ISO 10383: country feeds the EEA tests of CON-411, CON-471 and CON-610; SINT the SI
    // branch of CON-411; dates CON-072, CON-162 and CON-360.

    private static final String MICS = "MIC,OPERATING MIC,MARKET NAME-INSTITUTION DESCRIPTION,"
            + "ISO COUNTRY CODE (ISO 3166),MARKET CATEGORY CODE,STATUS,CREATION DATE,EXPIRY DATE\n"
            + "XPAR,XPAR,EURONEXT PARIS,FR,RMKT,ACTIVE,20050527,\n"
            + "SIFR,SIFR,TEST SI,FR,SINT,ACTIVE,20180103,\n"
            + "XOLD,XOLD,OLD MARKET,DE,MLTF,EXPIRED,20100101,20261101\n"
            + "XNEW,XNEW,NEW MARKET,IE,MLTF,ACTIVE,20261103,\n"
            + "XBLK,XBLK,BLANK,,,ACTIVE,,\n";

    @Test
    void micCountryCategoryAndDates() throws IOException {
        Iso10383MicRegistry r = Iso10383MicRegistry.load(new StringReader(MICS));
        assertEquals(Optional.of("FR"), r.country("XPAR"));
        assertEquals(Optional.of(false), r.isSystematicInternaliser("XPAR"));
        assertEquals(Optional.of(true), r.isSystematicInternaliser("SIFR"));
        assertEquals(Optional.empty(), r.country("XBLK"));
        assertEquals(Optional.empty(), r.isSystematicInternaliser("XBLK"));
        assertEquals(Optional.empty(), r.country("NONE"));
        assertEquals(Optional.empty(), r.isSystematicInternaliser("NONE"));
        // Expired on 2026-11-01: active the day before, not on the expiry date.
        assertTrue(r.isActiveOn("XOLD", LocalDate.of(2026, 10, 31)));
        assertFalse(r.isActiveOn("XOLD", LocalDate.of(2026, 11, 1)));
        // Created on 2026-11-03: not active on 2026-11-02.
        assertFalse(r.isActiveOn("XNEW", D));
        assertTrue(r.isActiveOn("XNEW", D.plusDays(1)));
        assertTrue(r.isActiveOn("XBLK", D), "no dates: the status decides");
        assertFalse(r.isActiveOn("NONE", D));
    }

    @Test
    void micRecordRules() {
        MicRecord old = new MicRecord("XA", "XA", "N", MicStatus.EXPIRED);
        assertFalse(old.isActiveOn(D));
        assertFalse(old.isSystematicInternaliser());
        MicRecord unknown = new MicRecord("XA", "XA", "N", MicStatus.UNKNOWN, "FR", "", D.minusDays(1), null);
        assertFalse(unknown.isActiveOn(D), "an unknown status is never active");
        MicRecord expiredNoCreation = new MicRecord("XA", "XA", "N", MicStatus.EXPIRED, "FR", "", null, D);
        assertTrue(expiredNoCreation.isActiveOn(D.minusDays(1)));
        assertFalse(expiredNoCreation.isActiveOn(D));
        assertThrows(NullPointerException.class, () -> old.isActiveOn(null));
        assertThrows(NullPointerException.class,
                () -> new MicRecord("XA", "XA", "N", MicStatus.ACTIVE, null, "", null, null));
        assertThrows(NullPointerException.class,
                () -> new MicRecord("XA", "XA", "N", MicStatus.ACTIVE, "", null, null, null));
    }

    @Test
    void micBadDateRejected() {
        assertThrows(IOException.class, () -> Iso10383MicRegistry.load(new StringReader(
                "MIC,OPERATING MIC,MARKET NAME-INSTITUTION DESCRIPTION,STATUS,CREATION DATE\nXA,XA,N,ACTIVE,2026-13-01\n")));
    }

    @Test
    void referenceDataCfiTable() {
        ReferenceData r = ReferenceData.of(null, null, null);
        assertNull(r.cfiCodes());
        ReferenceData withCfi = r.withCfiCodes(Set.of("ESVUFR")::contains);
        assertTrue(withCfi.cfiCodes().test("ESVUFR"));
        assertFalse(withCfi.cfiCodes().test("XXXXXX"));
        assertThrows(NullPointerException.class, () -> r.withCfiCodes(null));
        assertEquals(Set.of(), new ReferenceData(null, null, null, null, null, null).preEuroCurrencies());
    }
}
