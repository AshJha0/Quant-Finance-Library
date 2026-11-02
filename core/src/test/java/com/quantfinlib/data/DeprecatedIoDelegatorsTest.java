package com.quantfinlib.data;

import com.quantfinlib.core.BarSeries;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The 3.2 deprecated {@code data} I/O classes give the same results as {@code com.quantfinlib.io}. */
@SuppressWarnings("removal")
class DeprecatedIoDelegatorsTest {

    private static final List<String> BARS = List.of(
            "date,open,high,low,close,volume",
            "2024-01-03,11,12,10,11.5,200",
            "2024-01-02,10,11,9,10.5,100");

    @Test
    void csvBarLoaderDelegates(@TempDir Path dir) throws IOException {
        BarSeries old = CsvBarLoader.parse(BARS, "X");
        BarSeries neu = com.quantfinlib.io.CsvBarLoader.parse(BARS, "X");
        assertEquals(neu.size(), old.size());
        assertEquals(neu.close(0), old.close(0));
        Path file = dir.resolve("x.csv");
        CsvBarLoader.save(old, file);
        assertEquals(old.size(), CsvBarLoader.load(file, "X").size());
    }

    @Test
    void universeCsvLoaderDelegates(@TempDir Path dir) throws IOException {
        List<String> lines = List.of(
                "symbol,event,date,end_date,value,acquirer_shares,acquirer",
                "AAPL,MEMBER,2010-01-01,,,,");
        PointInTimeUniverse u = UniverseCsvLoader.parse(lines);
        assertTrue(u.allSymbols().contains("AAPL"));
        Path file = dir.resolve("u.csv");
        java.nio.file.Files.write(file, lines);
        assertEquals(u.allSymbols(), UniverseCsvLoader.load(file).allSymbols());
    }

    @Test
    void httpBarFetcherDelegates() {
        HttpBarFetcher f = new HttpBarFetcher(Duration.ofMillis(200));
        assertThrows(Exception.class, () -> f.fetchCsv(URI.create("http://127.0.0.1:1/x.csv"), "X"));
        assertDoesNotThrow(() -> new HttpBarFetcher());
    }
}
