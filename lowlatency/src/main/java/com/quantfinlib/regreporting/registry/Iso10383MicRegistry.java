package com.quantfinlib.regreporting.registry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Offline MIC registry loaded from the ISO 10383 CSV published by the registration
 * authority at {@code https://www.iso20022.org/market-identifier-codes}.
 *
 * <p>Columns read, by header name: {@code MIC}, {@code OPERATING MIC},
 * {@code MARKET NAME-INSTITUTION DESCRIPTION}, {@code STATUS}, and when present
 * {@code ISO COUNTRY CODE (ISO 3166)}, {@code MARKET CATEGORY CODE}, {@code CREATION DATE}
 * and {@code EXPIRY DATE} (dates as {@code yyyyMMdd} or {@code yyyy-MM-dd}). The optional
 * columns feed {@link #country}, {@link #isSystematicInternaliser} (category {@code SINT})
 * and the date test in {@link #isActiveOn}, which the ESMA rules CON-072, CON-162, CON-360
 * (MIC active on the trading date), CON-411 and CON-610 (EEA venue or SI) need. All column
 * names and the {@code yyyyMMdd} date format were checked against the header of the real
 * ISO 10383 CSV and the Release 2.0 factsheet (published reference; a five-row extract of
 * the file is a test fixture). A file without the optional columns loads as in 2.4.</p>
 */
public final class Iso10383MicRegistry implements MicRegistry {
    private final Map<String, MicRecord> records;

    private Iso10383MicRegistry(Map<String, MicRecord> records) {
        this.records = records;
    }

    /**
     * Loads the CSV file (UTF-8).
     *
     * @param file the file
     * @return the registry
     * @throws IOException on read error or missing columns
     */
    public static Iso10383MicRegistry load(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return load(r);
        }
    }

    /**
     * Loads CSV content.
     *
     * @param reader the content
     * @return the registry
     * @throws IOException on read error or missing columns
     */
    public static Iso10383MicRegistry load(Reader reader) throws IOException {
        BufferedReader in = reader instanceof BufferedReader b ? b : new BufferedReader(reader);
        List<String> head = Csv.next(in);
        if (head == null) {
            throw new IOException("empty ISO 10383 file");
        }
        Map<String, Integer> h = Csv.header(head);
        int iMic = Csv.require(h, "MIC");
        int iOp = Csv.require(h, "OPERATING MIC");
        int iName = Csv.require(h, "MARKET NAME-INSTITUTION DESCRIPTION");
        int iStatus = Csv.require(h, "STATUS");
        int iCountry = Csv.optional(h, "ISO COUNTRY CODE (ISO 3166)");
        int iCategory = Csv.optional(h, "MARKET CATEGORY CODE");
        int iCreated = Csv.optional(h, "CREATION DATE");
        int iExpiry = Csv.optional(h, "EXPIRY DATE");
        Map<String, MicRecord> m = new HashMap<>();
        for (List<String> row = Csv.next(in); row != null; row = Csv.next(in)) {
            String mic = Csv.get(row, iMic);
            if (mic.isEmpty()) {
                continue;
            }
            m.put(mic, new MicRecord(mic, Csv.get(row, iOp), Csv.get(row, iName),
                    MicStatus.parse(Csv.get(row, iStatus)), Csv.get(row, iCountry), Csv.get(row, iCategory),
                    Csv.date(Csv.get(row, iCreated)), Csv.date(Csv.get(row, iExpiry))));
        }
        return new Iso10383MicRegistry(Map.copyOf(m));
    }

    /**
     * Number of entries.
     *
     * @return the count
     */
    public int size() {
        return records.size();
    }

    @Override
    public Optional<MicRecord> lookup(String mic) {
        return Optional.ofNullable(records.get(mic));
    }

    /** From the record's dates when the file has them, else from the current status. */
    @Override
    public boolean isActiveOn(String mic, LocalDate date) {
        MicRecord r = records.get(mic);
        return r != null && r.isActiveOn(date);
    }

    /** The ISO 3166 code from the file, empty when the column or value is missing. */
    @Override
    public Optional<String> country(String mic) {
        MicRecord r = records.get(mic);
        return r == null || r.country().isEmpty() ? Optional.empty() : Optional.of(r.country());
    }

    /** Market category {@code SINT}; empty when the column or value is missing. */
    @Override
    public Optional<Boolean> isSystematicInternaliser(String mic) {
        MicRecord r = records.get(mic);
        return r == null || r.marketCategory().isEmpty() ? Optional.empty()
                : Optional.of(r.isSystematicInternaliser());
    }
}
