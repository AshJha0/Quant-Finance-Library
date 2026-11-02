package com.quantfinlib.regreporting.registry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Offline MIC registry loaded from the ISO 10383 CSV published by the registration
 * authority at {@code https://www.iso20022.org/market-identifier-codes}.
 *
 * <p>Columns read, by header name: {@code MIC}, {@code OPERATING MIC},
 * {@code MARKET NAME-INSTITUTION DESCRIPTION}, {@code STATUS}. Checked only against a
 * hand-made fixture with that header (own test).</p>
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
        Map<String, MicRecord> m = new HashMap<>();
        for (List<String> row = Csv.next(in); row != null; row = Csv.next(in)) {
            String mic = Csv.get(row, iMic);
            if (mic.isEmpty()) {
                continue;
            }
            m.put(mic, new MicRecord(mic, Csv.get(row, iOp), Csv.get(row, iName),
                    MicStatus.parse(Csv.get(row, iStatus))));
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
}
