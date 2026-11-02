package com.quantfinlib.regreporting.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The loaders against small extracts of real files (published reference for the layouts).
 * <ul>
 *   <li>{@code gleif-goldencopy-real-sample.csv}: full header and four rows of the GLEIF
 *       golden-copy LEI2 intra-day delta of 2026-11-02 08:00 (GLEIF data, CC0).</li>
 *   <li>{@code gleif-api-real-sample.json}: the GLEIF API record of LEI 506700GE1G29325QX363,
 *       fetched 2026-11 (CC0).</li>
 *   <li>{@code iso10383-real-sample.csv}: header and five rows of the ISO 10383 MIC CSV as
 *       published in 2026-11 (free to use per the registration authority).</li>
 * </ul>
 * No ESMA FIRDS data is committed; the FIRDS element names are checked against the FULINS
 * schema (ESMA65-8-9654 1.2.1, not redistributed) and exercised with hand-made XML in
 * {@code ReferenceDataLoaderDatesTest}.
 */
class RealSampleLoaderTest {
    private static Reader reader(String name) {
        InputStream in = Objects.requireNonNull(RealSampleLoaderTest.class.getResourceAsStream(name), name);
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    @Test
    void goldenCopyRealRows() throws IOException {
        GleifGoldenCopyLeiRegistry r;
        try (Reader in = reader("gleif-goldencopy-real-sample.csv")) {
            r = GleifGoldenCopyLeiRegistry.load(in);
        }
        assertEquals(4, r.size());
        LocalDate d = LocalDate.of(2026, 11, 2);
        LeiValidity a = r.validityOn("159558AMFM99CRKAS859", d).orElseThrow();
        assertEquals(LeiStatus.ISSUED, a.status());
        assertTrue(a.entityActive());
        assertEquals(Optional.of(LocalDate.of(2023, 6, 21)), a.initialRegistrationDate());
        assertEquals(Optional.of(LocalDate.of(2026, 11, 2)), a.lastUpdateDate());
        assertEquals(LeiStatus.LAPSED, r.status("2138001GIGEFL84LZJ22"));
        LeiValidity retired = r.validityOn("2549000QF98Q09WAW874", d).orElseThrow();
        assertEquals(LeiStatus.RETIRED, retired.status());
        assertFalse(retired.entityActive());
        // 2026-11-01T22:08:43+01:00 is 21:08 UTC on 2026-11-01.
        assertEquals(Optional.of(LocalDate.of(2026, 11, 1)), retired.lastUpdateDate());
        assertEquals(Optional.of(LocalDate.of(2017, 12, 29)), retired.initialRegistrationDate());
        assertEquals(LeiStatus.PENDING_ARCHIVAL, r.status("2549001UDLAZCFT3MU07"));
        assertEquals("Riverdale Holding AB", r.lookup("159558AMFM99CRKAS859").orElseThrow().legalName());
    }

    @Test
    void gleifApiRealRecord() throws IOException {
        String json;
        try (InputStream in = Objects.requireNonNull(
                RealSampleLoaderTest.class.getResourceAsStream("gleif-api-real-sample.json"))) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        LeiValidity v = GleifApiLeiRegistry.parseValidity("506700GE1G29325QX363", json);
        assertEquals(LeiStatus.ISSUED, v.status());
        assertTrue(v.entityActive());
        assertEquals(Optional.of(LocalDate.of(2018, 3, 16)), v.initialRegistrationDate());
        assertEquals(Optional.of(LocalDate.of(2026, 4, 23)), v.lastUpdateDate());
    }

    @Test
    void iso10383RealRows() throws IOException {
        Iso10383MicRegistry r;
        try (Reader in = reader("iso10383-real-sample.csv")) {
            r = Iso10383MicRegistry.load(in);
        }
        assertEquals(5, r.size());
        assertEquals(Optional.of("FR"), r.country("XPAR"));
        assertEquals(Optional.of(false), r.isSystematicInternaliser("XPAR"));
        assertTrue(r.lookup("XBLK").orElseThrow().isSegment());
        assertEquals(Optional.of(true), r.isSystematicInternaliser("UCBG"));
        assertEquals(Optional.of("US"), r.country("XNYS"));
        assertTrue(r.isActiveOn("XPAR", LocalDate.of(2005, 6, 27)));
        assertFalse(r.isActiveOn("XPAR", LocalDate.of(2005, 6, 26)));
        // MBCP: SI, created 2021-06-28, expired (deactivated) 2025-08-25.
        assertTrue(r.isActiveOn("MBCP", LocalDate.of(2025, 8, 24)));
        assertFalse(r.isActiveOn("MBCP", LocalDate.of(2025, 8, 25)));
        assertEquals(MicStatus.EXPIRED, r.status("MBCP"));
    }
}
