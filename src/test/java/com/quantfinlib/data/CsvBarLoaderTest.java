package com.quantfinlib.data;

import com.quantfinlib.TestData;
import com.quantfinlib.core.BarSeries;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CsvBarLoaderTest {

    @Test
    void saveLoadRoundTripsExactly(@TempDir Path dir) throws Exception {
        BarSeries original = TestData.gbmSeries("EURUSD", 100, 1.08, 0.02, 0.08, 5);
        Path file = dir.resolve("eurusd.csv");
        CsvBarLoader.save(original, file);
        BarSeries loaded = CsvBarLoader.load(file, "EURUSD");

        assertEquals(original.size(), loaded.size());
        for (int i = 0; i < original.size(); i++) {
            assertEquals(original.timestamp(i), loaded.timestamp(i));
            assertEquals(original.open(i), loaded.open(i), 0.0);
            assertEquals(original.high(i), loaded.high(i), 0.0);
            assertEquals(original.low(i), loaded.low(i), 0.0);
            assertEquals(original.close(i), loaded.close(i), 0.0);
            assertEquals(original.volume(i), loaded.volume(i), 0.0);
        }
    }

    @Test
    void parsesRealWorldStyleCsvWithDatesAndReordering() {
        // Yahoo-style header, unordered rows, blank line, extra column.
        List<String> lines = List.of(
                "Date,Open,High,Low,Close,Adj Close,Volume",
                "2024-01-03,102.0,104.0,101.0,103.5,103.2,1200000",
                "2024-01-02,100.0,102.5,99.5,102.0,101.8,1000000",
                "",
                "2024-01-04,103.5,105.0,103.0,104.8,104.5,900000");
        BarSeries s = CsvBarLoader.parse(lines, "TEST");

        assertEquals(3, s.size());
        // Sorted by date: Jan 2 first.
        assertEquals(100.0, s.open(0), 0.0);
        assertEquals(104.8, s.close(2), 0.0);
        assertEquals(1_000_000, s.volume(0), 0.0);
    }

    @Test
    void parsesAllSupportedTimestampFormats() {
        long jan2 = java.time.LocalDate.of(2024, 1, 2)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        assertEquals(jan2, CsvBarLoader.parseTimestamp("2024-01-02"));
        assertEquals(jan2, CsvBarLoader.parseTimestamp(String.valueOf(jan2)));           // epoch ms
        assertEquals(jan2, CsvBarLoader.parseTimestamp("2024-01-02T00:00:00"));
        assertEquals(jan2, CsvBarLoader.parseTimestamp("2024-01-02 00:00:00"));
        assertEquals(jan2, CsvBarLoader.parseTimestamp("2024-01-02T00:00:00Z"));
    }

    @Test
    void detectsEpochSecondsFiles() {
        long jan2Seconds = java.time.LocalDate.of(2024, 1, 2)
                .toEpochSecond(java.time.LocalTime.MIDNIGHT, java.time.ZoneOffset.UTC);
        BarSeries s = CsvBarLoader.parse(List.of(
                "timestamp,open,high,low,close,volume",
                jan2Seconds + ",100,101,99,100.5,1000",
                (jan2Seconds + 86_400) + ",100.5,102,100,101.5,1100"), "SEC");
        assertEquals(jan2Seconds * 1000, s.timestamp(0));
        assertEquals((jan2Seconds + 86_400) * 1000, s.timestamp(1));
    }

    @Test
    void handlesQuotedFieldsAndThousandsSeparators() {
        // Vendor-style export: quoted name column with commas, quoted volume
        // with thousands separators, escaped quote inside a quoted field.
        BarSeries s = CsvBarLoader.parse(List.of(
                "Date,Name,Open,High,Low,Close,Volume",
                "2024-01-02,\"Acme, Inc. (\"\"ACME\"\")\",100.0,102.5,99.5,102.0,\"1,200,000\"",
                "2024-01-03,\"Acme, Inc.\",102.0,104.0,101.0,103.5,\"950,000\""), "ACME");

        assertEquals(2, s.size());
        assertEquals(102.0, s.close(0), 0.0);
        assertEquals(1_200_000, s.volume(0), 0.0);
        assertEquals(950_000, s.volume(1), 0.0);
    }

    @Test
    void rejectsMalformedFiles() {
        assertThrows(IllegalArgumentException.class,
                () -> CsvBarLoader.parse(List.of(), "X"));
        assertThrows(IllegalArgumentException.class,
                () -> CsvBarLoader.parse(List.of("foo,bar,baz"), "X"));
        assertThrows(IllegalArgumentException.class,
                () -> CsvBarLoader.parse(List.of("date,open,high,low,close"), "X"));   // no rows
    }
}
