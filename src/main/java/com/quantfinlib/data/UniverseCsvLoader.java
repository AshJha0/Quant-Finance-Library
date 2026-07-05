package com.quantfinlib.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads a {@link PointInTimeUniverse} from a user-supplied CSV file — the
 * defined interchange format for the membership/lifecycle data the engine
 * cannot invent.
 *
 * <h2>File format</h2>
 * <p>Header (required, column order fixed):</p>
 * <pre>
 * symbol,event,date,end_date,value,acquirer_shares,acquirer
 * </pre>
 * <p>One row per membership interval or lifecycle event:</p>
 * <pre>
 * # comments and blank lines are ignored
 * AAPL,MEMBER,2010-01-01,,,,                  &lt;- member since date, still in
 * YHOO,MEMBER,2000-01-03,2017-06-13,,,        &lt;- left the index on end_date
 * LEH,MEMBER,2000-01-03,2008-09-15,,,
 * LEH,DELIST,2008-09-15,,-1.0,,               &lt;- shareholders wiped out
 * WCOM,DELIST,2002-07-01,,,,                  &lt;- value empty: Shumway -30% default
 * TWX,MERGER,2018-06-15,,48.53,0.5471,T       &lt;- $48.53 + 0.5471 T shares per share
 * </pre>
 * <ul>
 *   <li><b>event</b> — {@code MEMBER}, {@code DELIST} or {@code MERGER}
 *       (case-insensitive);</li>
 *   <li><b>date / end_date</b> — ISO dates ({@code 2018-06-15}) or epoch
 *       numbers, converted exactly like {@code CsvBarLoader} bar timestamps
 *       (epoch millis internally), so universe dates and bar timestamps
 *       line up by construction. Empty {@code end_date} = open-ended
 *       membership;</li>
 *   <li><b>value</b> — DELIST: the delisting return (final-day return on
 *       the last close, −1 = worthless; empty defaults to
 *       {@link PointInTimeUniverse#DEFAULT_INVOLUNTARY_DELISTING_RETURN});
 *       MERGER: cash per share (empty = 0);</li>
 *   <li><b>acquirer_shares / acquirer</b> — MERGER stock component
 *       (empty = all-cash deal).</li>
 * </ul>
 *
 * <h2>Sourcing the data (honesty section)</h2>
 * <p>Free constituent lists — e.g.
 * <a href="https://github.com/datasets/s-and-p-500-companies">datasets/s-and-p-500-companies</a>
 * — publish <em>today's</em> members: loading one gives every symbol an
 * open-ended membership from your chosen start date, which reproduces the
 * survivorship-biased universe (useful as a baseline to quantify the bias
 * against, and the format accepts it). Removing the bias requires
 * historical membership <em>changes</em> and delisting returns — CRSP,
 * Norgate, Sharadar, or hand-curated index change announcements — expressed
 * in this same format.</p>
 */
public final class UniverseCsvLoader {

    private static final String EXPECTED_HEADER =
            "symbol,event,date,end_date,value,acquirer_shares,acquirer";

    private UniverseCsvLoader() {
    }

    /** Loads a universe file (see the class doc for the format). */
    public static PointInTimeUniverse load(Path file) throws IOException {
        return parse(Files.readAllLines(file));
    }

    /** Parses in-memory lines — same format, no file required (tests, HTTP). */
    public static PointInTimeUniverse parse(List<String> lines) {
        // Two passes: rows are validated and collected first so the same
        // whole-file epoch-seconds detection CsvBarLoader applies to bar
        // files can apply here — otherwise a seconds-stamped universe file
        // would silently land in January 1970 next to millis-stamped bars.
        List<String[]> rows = new ArrayList<>();
        List<Integer> lineNumbers = new ArrayList<>();
        boolean headerSeen = false;
        boolean allNumeric = true;
        long minTs = Long.MAX_VALUE;
        long maxTs = Long.MIN_VALUE;
        for (int lineNo = 0; lineNo < lines.size(); lineNo++) {
            String line = lines.get(lineNo).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (!headerSeen) {
                if (!normalize(line).equals(EXPECTED_HEADER)) {
                    throw new IllegalArgumentException(
                            "expected header '" + EXPECTED_HEADER + "' but found: " + line);
                }
                headerSeen = true;
                continue;
            }
            // -1 keeps trailing empty fields, so short rows are still 7 columns.
            String[] f = line.split(",", -1);
            if (f.length != 7) {
                throw new IllegalArgumentException(
                        "line " + (lineNo + 1) + ": expected 7 columns, found " + f.length);
            }
            for (String raw : new String[]{f[2].trim(), f[3].trim()}) {
                if (raw.isEmpty()) {
                    continue;
                }
                if (raw.chars().allMatch(Character::isDigit)) {
                    long v = Long.parseLong(raw);
                    minTs = Math.min(minTs, v);
                    maxTs = Math.max(maxTs, v);
                } else {
                    allNumeric = false; // ISO dates present: values are millis
                }
            }
            rows.add(f);
            lineNumbers.add(lineNo + 1);
        }
        if (!headerSeen) {
            throw new IllegalArgumentException("empty universe file (no header)");
        }
        // Same heuristic and bounds as CsvBarLoader: all-numeric files whose
        // values fit the plausible seconds range (years 1973..5138) scale.
        long scale = allNumeric && minTs >= 100_000_000L && maxTs < 100_000_000_000L ? 1000 : 1;

        PointInTimeUniverse universe = new PointInTimeUniverse();
        for (int r = 0; r < rows.size(); r++) {
            String[] f = rows.get(r);
            int lineNo = lineNumbers.get(r);
            String symbol = f[0].trim();
            String event = f[1].trim().toUpperCase(Locale.ROOT);
            if (symbol.isEmpty()) {
                throw new IllegalArgumentException("line " + lineNo + ": empty symbol");
            }
            try {
                switch (event) {
                    case "MEMBER" -> {
                        long from = timestamp(f[2].trim(), scale);
                        if (f[3].trim().isEmpty()) {
                            universe.addMembership(symbol, from);
                        } else {
                            universe.addMembership(symbol, from, timestamp(f[3].trim(), scale));
                        }
                    }
                    case "DELIST" -> {
                        double ret = f[4].trim().isEmpty()
                                ? PointInTimeUniverse.DEFAULT_INVOLUNTARY_DELISTING_RETURN
                                : Double.parseDouble(f[4].trim());
                        universe.recordDelisting(symbol, timestamp(f[2].trim(), scale), ret);
                    }
                    case "MERGER" -> {
                        double cash = f[4].trim().isEmpty() ? 0 : Double.parseDouble(f[4].trim());
                        double shares = f[5].trim().isEmpty() ? 0 : Double.parseDouble(f[5].trim());
                        String acquirer = f[6].trim().isEmpty() ? null : f[6].trim();
                        universe.recordMerger(symbol, timestamp(f[2].trim(), scale),
                                cash, shares, acquirer);
                    }
                    default -> throw new IllegalArgumentException("unknown event '" + f[1] + "'");
                }
            } catch (RuntimeException e) {
                // Re-wrap with the line number: universe files are hand-curated,
                // and "which row is wrong" is the whole error message battle.
                // RuntimeException (not just IAE) so malformed dates
                // (DateTimeParseException) and numbers carry the row too.
                throw new IllegalArgumentException(
                        "line " + lineNo + " (" + symbol + "): " + e.getMessage(), e);
            }
        }
        return universe;
    }

    /** ISO dates parse to millis directly; numeric values scale per the file heuristic. */
    private static long timestamp(String raw, long scale) {
        if (raw.chars().allMatch(Character::isDigit)) {
            return Long.parseLong(raw) * scale;
        }
        return CsvBarLoader.parseTimestamp(raw);
    }

    private static String normalize(String header) {
        return header.toLowerCase(Locale.ROOT).replace(" ", "");
    }
}
