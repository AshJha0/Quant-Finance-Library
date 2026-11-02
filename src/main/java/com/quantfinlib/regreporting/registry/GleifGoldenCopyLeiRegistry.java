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
 * Offline LEI registry loaded from the GLEIF golden-copy (LEI-CDF) CSV file, which GLEIF
 * publishes for free download at {@code https://www.gleif.org/en/lei-data/gleif-golden-copy}.
 *
 * <p>Columns read, by header name: {@code LEI}, {@code Entity.LegalName},
 * {@code Registration.RegistrationStatus}. Other columns are ignored, so the full file and a
 * trimmed extract both load. Everything is held in memory; the full file has millions of
 * rows, so filter it first if memory matters. The header names follow the golden-copy CSV
 * layout; this class was checked only against a hand-made fixture (own test).</p>
 */
public final class GleifGoldenCopyLeiRegistry implements LeiRegistry {
    private final Map<String, LeiRecord> records;

    private GleifGoldenCopyLeiRegistry(Map<String, LeiRecord> records) {
        this.records = records;
    }

    /**
     * Loads a golden-copy CSV file (UTF-8).
     *
     * @param file the file
     * @return the registry
     * @throws IOException on read error or missing columns
     */
    public static GleifGoldenCopyLeiRegistry load(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return load(r);
        }
    }

    /**
     * Loads golden-copy CSV content.
     *
     * @param reader the content
     * @return the registry
     * @throws IOException on read error or missing columns
     */
    public static GleifGoldenCopyLeiRegistry load(Reader reader) throws IOException {
        BufferedReader in = reader instanceof BufferedReader b ? b : new BufferedReader(reader);
        List<String> head = Csv.next(in);
        if (head == null) {
            throw new IOException("empty golden-copy file");
        }
        Map<String, Integer> h = Csv.header(head);
        int iLei = Csv.require(h, "LEI");
        int iName = Csv.require(h, "Entity.LegalName");
        int iStatus = Csv.require(h, "Registration.RegistrationStatus");
        Map<String, LeiRecord> m = new HashMap<>();
        for (List<String> row = Csv.next(in); row != null; row = Csv.next(in)) {
            String lei = Csv.get(row, iLei);
            if (lei.isEmpty()) {
                continue;
            }
            m.put(lei, new LeiRecord(lei, Csv.get(row, iName), LeiStatus.parse(Csv.get(row, iStatus))));
        }
        return new GleifGoldenCopyLeiRegistry(Map.copyOf(m));
    }

    /**
     * Number of records loaded.
     *
     * @return the count
     */
    public int size() {
        return records.size();
    }

    @Override
    public Optional<LeiRecord> lookup(String lei) {
        return Optional.ofNullable(records.get(lei));
    }
}
