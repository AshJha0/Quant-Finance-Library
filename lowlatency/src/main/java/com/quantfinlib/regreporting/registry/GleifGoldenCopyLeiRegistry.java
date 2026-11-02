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
 * Offline LEI registry loaded from the GLEIF golden-copy (LEI-CDF) CSV file, which GLEIF
 * publishes for free download at {@code https://www.gleif.org/en/lei-data/gleif-golden-copy}.
 *
 * <p>Columns read, by header name: {@code LEI}, {@code Entity.LegalName},
 * {@code Registration.RegistrationStatus}. Other columns are ignored, so the full file and a
 * trimmed extract both load. Everything is held in memory; the full file has millions of
 * rows, so filter it first if memory matters. The header names follow the golden-copy CSV
 * layout; this class was checked only against a hand-made fixture (own test).</p>
 *
 * <p>Since 3.0.0 the optional columns {@code Registration.InitialRegistrationDate},
 * {@code Registration.LastUpdateDate} and {@code Entity.EntityStatus} are read when present,
 * so {@link #validityOn} returns the dates and entity status that the ESMA LEI rules
 * (CON-040 to CON-270) test. The column names were checked against the header of a real
 * golden-copy file (intra-day delta of 2026-11-02 08:00) and the entity status values
 * ACTIVE, INACTIVE and NULL against the LEI-CDF 3.1 XSD (published reference). Dates carry
 * a {@code Z} or {@code +01:00} offset and are converted to their UTC date.</p>
 *
 * <p>Since 3.1.0 the optional column {@code Entity.EntityCategory} is read into
 * {@link LeiRecord#entityCategory()}, for the EMIR "LEI shall pertain to a legal entity and
 * not a branch" rules. The column name and the values GENERAL, SOLE_PROPRIETOR, FUND and
 * RESIDENT_GOVERNMENT_ENTITY were seen in the same real intra-day file; BRANCH is from the
 * LEI-CDF 3.1 XSD ({@code EntityCategoryTypeEnum}).</p>
 */
public final class GleifGoldenCopyLeiRegistry implements LeiRegistry {
    private final Map<String, LeiRecord> records;
    private final Map<String, LeiValidity> validity;

    private GleifGoldenCopyLeiRegistry(Map<String, LeiRecord> records, Map<String, LeiValidity> validity) {
        this.records = records;
        this.validity = validity;
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
        int iInitial = Csv.optional(h, "Registration.InitialRegistrationDate");
        int iUpdated = Csv.optional(h, "Registration.LastUpdateDate");
        int iEntity = Csv.optional(h, "Entity.EntityStatus");
        int iCategory = Csv.optional(h, "Entity.EntityCategory");
        Map<String, LeiRecord> m = new HashMap<>();
        Map<String, LeiValidity> v = new HashMap<>();
        for (List<String> row = Csv.next(in); row != null; row = Csv.next(in)) {
            String lei = Csv.get(row, iLei);
            if (lei.isEmpty()) {
                continue;
            }
            LeiStatus status = LeiStatus.parse(Csv.get(row, iStatus));
            m.put(lei, new LeiRecord(lei, Csv.get(row, iName), status, Csv.get(row, iCategory)));
            if (iInitial >= 0 || iUpdated >= 0 || iEntity >= 0) {
                // Only INACTIVE marks the entity inactive; a blank or other value keeps 2.4 behaviour.
                boolean active = !"INACTIVE".equalsIgnoreCase(Csv.get(row, iEntity));
                v.put(lei, new LeiValidity(status, Csv.date(Csv.get(row, iInitial)), active,
                        Csv.date(Csv.get(row, iUpdated))));
            }
        }
        return new GleifGoldenCopyLeiRegistry(Map.copyOf(m), Map.copyOf(v));
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

    /**
     * The file's current data with its dates and entity status, as TRI paragraph 25 asks
     * (latest reference data, checked for validity on the trading date). The golden copy
     * holds one current record per LEI, so {@code date} does not select a historic version.
     */
    @Override
    public Optional<LeiValidity> validityOn(String lei, LocalDate date) {
        LeiValidity v = validity.get(lei);
        if (v != null) {
            return Optional.of(v);
        }
        return lookup(lei).map(r -> LeiValidity.statusOnly(r.status()));
    }
}
