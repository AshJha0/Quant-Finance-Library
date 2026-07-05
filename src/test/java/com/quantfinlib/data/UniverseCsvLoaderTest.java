package com.quantfinlib.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The universe CSV interchange format: ISO and epoch dates, open-ended and
 * closed memberships, delistings with and without explicit returns, merger
 * deal terms, comments/blank lines, and row-level error reporting.
 */
class UniverseCsvLoaderTest {

    private static long millis(String isoDate) {
        return LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    @Test
    void loadsTheDocumentedFormat(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("universe.csv");
        Files.write(file, List.of(
                "# S&P example universe — comments and blanks are fine",
                "symbol,event,date,end_date,value,acquirer_shares,acquirer",
                "",
                "AAPL,MEMBER,2010-01-01,,,,",
                "YHOO,MEMBER,2000-01-03,2017-06-13,,,",
                "LEH,MEMBER,2000-01-03,2008-09-15,,,",
                "LEH,DELIST,2008-09-15,,-1.0,,",
                "WCOM,MEMBER,2000-01-03,2002-07-01,,,",
                "WCOM,DELIST,2002-07-01,,,,",
                "TWX,MEMBER,2000-01-03,2018-06-15,,,",
                "TWX,MERGER,2018-06-15,,48.53,0.5471,T"));
        PointInTimeUniverse u = UniverseCsvLoader.load(file);

        // Open-ended membership.
        assertTrue(u.isMember("AAPL", millis("2020-01-01")));
        assertFalse(u.isMember("AAPL", millis("2009-12-31")));
        // Closed membership: end_date inclusive.
        assertTrue(u.isMember("YHOO", millis("2017-06-13")));
        assertFalse(u.isMember("YHOO", millis("2017-06-14")));
        // Explicit delisting return.
        PointInTimeUniverse.TerminalEvent leh = u.terminalEvent("LEH");
        assertEquals(PointInTimeUniverse.EventType.DELISTING, leh.type());
        assertEquals(-1.0, leh.delistingReturn());
        assertEquals(millis("2008-09-15"), leh.timestamp());
        // Empty value → the Shumway involuntary-delisting default.
        assertEquals(PointInTimeUniverse.DEFAULT_INVOLUNTARY_DELISTING_RETURN,
                u.terminalEvent("WCOM").delistingReturn());
        // Merger deal terms.
        PointInTimeUniverse.TerminalEvent twx = u.terminalEvent("TWX");
        assertEquals(PointInTimeUniverse.EventType.MERGER, twx.type());
        assertEquals(48.53, twx.cashPerShare());
        assertEquals(0.5471, twx.acquirerSharesPerShare());
        assertEquals("T", twx.acquirer());
        // Dead names are out of the universe at their event.
        assertFalse(u.isMember("LEH", millis("2008-09-15")));
    }

    @Test
    void epochTimestampsWorkLikeBarFiles() {
        // Small numerics (below the plausible seconds range) pass through
        // as raw millis, exactly like CsvBarLoader bar files.
        PointInTimeUniverse u = UniverseCsvLoader.parse(List.of(
                "symbol,event,date,end_date,value,acquirer_shares,acquirer",
                "X,MEMBER,1000,2000,,,",
                "X,DELIST,2000,,-0.5,,"));
        assertTrue(u.isMember("X", 1500));
        assertFalse(u.isMember("X", 2000)); // dead at the event
        assertEquals(-0.5, u.terminalEvent("X").delistingReturn());
    }

    @Test
    void epochSecondsFilesScaleToMillisLikeBarFiles() {
        // An all-numeric file in the plausible seconds range (years
        // 1973..5138) scales ×1000 — the same whole-file heuristic
        // CsvBarLoader applies, so seconds-stamped universe and bar files
        // line up instead of the universe landing silently in 1970.
        long secs2010 = millis("2010-01-01") / 1000;   // 1_262_304_000
        long secs2020 = millis("2020-01-01") / 1000;
        PointInTimeUniverse u = UniverseCsvLoader.parse(List.of(
                "symbol,event,date,end_date,value,acquirer_shares,acquirer",
                "X,MEMBER," + secs2010 + "," + secs2020 + ",,,"));
        assertTrue(u.isMember("X", millis("2015-06-15")));
        assertFalse(u.isMember("X", millis("2021-01-01")));
        // Mixed ISO + numeric disables the heuristic: numerics stay millis.
        PointInTimeUniverse mixed = UniverseCsvLoader.parse(List.of(
                "symbol,event,date,end_date,value,acquirer_shares,acquirer",
                "A,MEMBER,2010-01-01,,,,",
                "B,MEMBER," + millis("2010-01-01") + ",,,,"));
        assertTrue(mixed.isMember("B", millis("2015-01-01")));
    }

    @Test
    void errorsCarryTheLineNumber() {
        // Wrong column count.
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> UniverseCsvLoader.parse(List.of(
                        "symbol,event,date,end_date,value,acquirer_shares,acquirer",
                        "X,MEMBER,1000")));
        assertTrue(e1.getMessage().contains("line 2"));
        // Unknown event.
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> UniverseCsvLoader.parse(List.of(
                        "symbol,event,date,end_date,value,acquirer_shares,acquirer",
                        "X,SPINOFF,1000,,,,")));
        assertTrue(e2.getMessage().contains("line 2"));
        // Domain validation surfaces with the row context.
        IllegalArgumentException e3 = assertThrows(IllegalArgumentException.class,
                () -> UniverseCsvLoader.parse(List.of(
                        "symbol,event,date,end_date,value,acquirer_shares,acquirer",
                        "X,DELIST,1000,,-1.5,,")));
        assertTrue(e3.getMessage().contains("X"));
        // Wrong header and empty file are rejected outright.
        assertThrows(IllegalArgumentException.class,
                () -> UniverseCsvLoader.parse(List.of("ticker,action,when")));
        assertThrows(IllegalArgumentException.class, () -> UniverseCsvLoader.parse(List.of()));
    }
}
