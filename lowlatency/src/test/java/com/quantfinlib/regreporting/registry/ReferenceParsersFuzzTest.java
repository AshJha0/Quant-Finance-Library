package com.quantfinlib.regreporting.registry;

import com.quantfinlib.fuzzing.Mutations;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

/**
 * Structured fuzzing of the reference-data parsers: FIRDS XML
 * ({@link FirdsInstrumentRegistry#load(InputStream)}), the SIX CFI workbook
 * ({@link CfiCodeRegistry#fromSixXlsx(InputStream)}), and the GLEIF golden-copy and ISO 10383
 * MIC CSV files ({@link GleifGoldenCopyLeiRegistry#load(Reader)},
 * {@link Iso10383MicRegistry#load(Reader)}). The contract under test: malformed input raises
 * {@link IOException} and nothing else. Seeds are the committed fixtures and the synthetic
 * workbook of {@link CfiCodeRegistryTest}. Cases and seeding: see {@link Mutations}.
 */
class ReferenceParsersFuzzTest {

    private static byte[] resource(String name) {
        try (InputStream in = Objects.requireNonNull(ReferenceParsersFuzzTest.class.getResourceAsStream(name), name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Reader reader(byte[] b) {
        return new InputStreamReader(new ByteArrayInputStream(b), StandardCharsets.UTF_8);
    }

    @Test
    void firdsXmlRaisesOnlyIoException() {
        Mutations.run(301L, List.of(resource("firds-fixture.xml")), input -> {
            try {
                FirdsInstrumentRegistry.load(new ByteArrayInputStream(input)).size();
            } catch (IOException expected) {
                // documented
            }
        });
    }

    @Test
    void gleifCsvRaisesOnlyIoException() {
        Mutations.run(302L, List.of(resource("golden-copy-fixture.csv"), resource("gleif-goldencopy-real-sample.csv")),
                input -> {
                    try {
                        GleifGoldenCopyLeiRegistry.load(reader(input)).lookup("5493001KJTIIGC8Y1R12");
                    } catch (IOException expected) {
                        // documented
                    }
                });
    }

    @Test
    void micCsvRaisesOnlyIoException() {
        Mutations.run(303L, List.of(resource("iso10383-fixture.csv"), resource("iso10383-real-sample.csv")),
                input -> {
                    try {
                        Iso10383MicRegistry.load(reader(input)).lookup("XLON");
                    } catch (IOException expected) {
                        // documented
                    }
                });
    }

    private static void cfi(byte[] input) {
        try {
            CfiCodeRegistry r = CfiCodeRegistry.fromSixXlsx(new ByteArrayInputStream(input));
            r.groupCount();
            r.test("ESVUFR");
        } catch (IOException expected) {
            // documented
        }
    }

    /** Whole-archive mutations (mostly caught by the ZIP CRC). */
    @Test
    void cfiXlsxArchiveRaisesOnlyIoException() throws IOException {
        Mutations.run(304L, List.of(CfiCodeRegistryTest.workbook()), ReferenceParsersFuzzTest::cfi);
    }

    /** One inner part mutated, then re-zipped, so the XML and sheet logic is reached. */
    @Test
    void cfiXlsxPartsRaiseOnlyIoException() throws IOException {
        Map<String, String> parts = unzip(CfiCodeRegistryTest.workbook());
        List<String> names = new ArrayList<>(parts.keySet());
        SplittableRandom rnd = new SplittableRandom(305L);
        for (int i = 0; i < Mutations.CASES; i++) {
            Map<String, String> m = new LinkedHashMap<>(parts);
            String part = names.get(rnd.nextInt(names.size()));
            byte[] mutated = Mutations.mutate(m.get(part).getBytes(StandardCharsets.UTF_8), rnd);
            m.put(part, new String(mutated, StandardCharsets.UTF_8));
            try {
                cfi(CfiCodeRegistryTest.zip(m));
            } catch (RuntimeException | AssertionError e) {
                throw new AssertionError("fuzz seed 305 case " + i + " part " + part + ": " + e, e);
            }
        }
    }

    private static Map<String, String> unzip(byte[] zip) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                if (!e.isDirectory()) {
                    out.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return out;
    }
}
