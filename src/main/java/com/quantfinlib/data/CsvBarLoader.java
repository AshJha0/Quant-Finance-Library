package com.quantfinlib.data;

import com.quantfinlib.core.Bar;
import com.quantfinlib.core.BarSeries;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * CSV market data I/O: loads real historical OHLCV bars into a
 * {@link BarSeries} and saves series back out — the interchange format for
 * the whole library.
 *
 * <p>The loader is tolerant of real-world files: header names are matched
 * case-insensitively ({@code date}/{@code time}/{@code timestamp}/{@code datetime},
 * {@code open}, {@code high}, {@code low}, {@code close}, optional
 * {@code volume}), timestamps may be epoch millis, epoch seconds,
 * {@code yyyy-MM-dd}, ISO local or offset date-times, rows may be unordered
 * (they are sorted by time), and blank lines are skipped.</p>
 */
public final class CsvBarLoader {

    private CsvBarLoader() {
    }

    public static BarSeries load(Path path, String symbol) throws IOException {
        return parse(Files.readAllLines(path), symbol);
    }

    public static BarSeries parse(List<String> lines, String symbol) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("empty CSV");
        }
        String headerLine = lines.getFirst().replace("﻿", "");   // strip BOM
        String[] headers = splitCsv(headerLine);
        int tsCol = -1, oCol = -1, hCol = -1, lCol = -1, cCol = -1, vCol = -1;
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].trim().toLowerCase(Locale.ROOT);
            switch (h) {
                case "timestamp", "time", "date", "datetime" -> tsCol = tsCol < 0 ? i : tsCol;
                case "open" -> oCol = i;
                case "high" -> hCol = i;
                case "low" -> lCol = i;
                case "close", "adj close", "adj_close" -> cCol = cCol < 0 ? i : cCol;
                case "volume", "vol" -> vCol = i;
                default -> { /* ignore extra columns */ }
            }
        }
        if (tsCol < 0 || oCol < 0 || hCol < 0 || lCol < 0 || cCol < 0) {
            throw new IllegalArgumentException(
                    "CSV must have timestamp/date, open, high, low, close columns; got: " + headerLine);
        }

        List<Bar> bars = new ArrayList<>(lines.size() - 1);
        boolean allNumeric = true;
        long minTs = Long.MAX_VALUE, maxTs = Long.MIN_VALUE;
        for (int r = 1; r < lines.size(); r++) {
            String line = lines.get(r).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] cells = splitCsv(line);
            String rawTs = cells[tsCol].trim();
            allNumeric &= rawTs.chars().allMatch(Character::isDigit);
            long ts = parseTimestamp(rawTs);
            minTs = Math.min(minTs, ts);
            maxTs = Math.max(maxTs, ts);
            bars.add(new Bar(ts,
                    parseNumber(cells[oCol]),
                    parseNumber(cells[hCol]),
                    parseNumber(cells[lCol]),
                    parseNumber(cells[cCol]),
                    vCol >= 0 && vCol < cells.length ? parseNumber(cells[vCol]) : 0));
        }
        if (bars.isEmpty()) {
            throw new IllegalArgumentException("CSV has a header but no data rows");
        }
        // Whole-file epoch-seconds detection: if every numeric timestamp sits in the
        // plausible seconds range (years 1973..5138), the file is in seconds.
        if (isEpochSecondsFile(allNumeric, minTs, maxTs)) {
            List<Bar> scaled = new ArrayList<>(bars.size());
            for (Bar b : bars) {
                scaled.add(new Bar(b.timestamp() * 1000, b.open(), b.high(), b.low(), b.close(), b.volume()));
            }
            bars = scaled;
        }
        bars.sort(Comparator.comparingLong(Bar::timestamp));
        return BarSeries.fromBars(symbol, bars);
    }

    /** Writes the series as {@code timestamp,open,high,low,close,volume} with epoch-millis timestamps. */
    public static void save(BarSeries series, Path path) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        try (Writer w = Files.newBufferedWriter(path)) {
            w.write("timestamp,open,high,low,close,volume\n");
            for (int i = 0; i < series.size(); i++) {
                w.write(String.format(Locale.ROOT, "%d,%s,%s,%s,%s,%s%n",
                        series.timestamp(i), series.open(i), series.high(i),
                        series.low(i), series.close(i), series.volume(i)));
            }
        }
    }

    /**
     * RFC-4180-style field split: comma/semicolon delimiters, double quotes
     * protect embedded delimiters, {@code ""} escapes a quote inside a
     * quoted field.
     */
    static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',' || c == ';') {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        out.add(current.toString());
        return out.toArray(new String[0]);
    }

    /** Numeric parse tolerant of vendor thousands separators ("1,234.5"). */
    static double parseNumber(String value) {
        return Double.parseDouble(value.trim().replace(",", ""));
    }

    /**
     * The whole-file epoch-seconds heuristic, shared with
     * {@code UniverseCsvLoader} so bar files and universe files can never
     * disagree on the scale: all-numeric timestamps sitting entirely in the
     * plausible seconds range (years 1973..5138) are seconds, ×1000.
     */
    static boolean isEpochSecondsFile(boolean allNumeric, long minTs, long maxTs) {
        return allNumeric && minTs >= 100_000_000L && maxTs < 100_000_000_000L;
    }

    /**
     * Epoch millis from a numeric timestamp (seconds-vs-millis is resolved at
     * file level in {@link #parse}), {@code yyyy-MM-dd}, or ISO date-times
     * (UTC assumed when unzoned).
     */
    static long parseTimestamp(String value) {
        if (value.chars().allMatch(Character::isDigit)) {
            return Long.parseLong(value);
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
                    .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (java.time.format.DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(value.replace(' ', 'T'))
                    .toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (java.time.format.DateTimeParseException ignored) {
            // fall through
        }
        return OffsetDateTime.parse(value).toInstant().toEpochMilli();
    }
}
