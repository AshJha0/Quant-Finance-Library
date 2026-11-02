package com.quantfinlib.regreporting.registry;

import com.quantfinlib.fuzzing.Mutations;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Structured fuzzing of the reference-data parsers: FIRDS XML
 * ({@link FirdsInstrumentRegistry#load(InputStream)}), the SIX CFI workbook
 * ({@link CfiCodeRegistry#fromSixXlsx(InputStream)}), and the GLEIF golden-copy and ISO 10383
 * MIC CSV files ({@link GleifGoldenCopyLeiRegistry#load(Reader)},
 * {@link Iso10383MicRegistry#load(Reader)}). The contract under test: malformed input raises
 * {@link IOException} and nothing else. Seeds are the committed fixtures and the synthetic
 * workbook of {@link CfiCodeRegistryTest}. The default build runs jqwik's default number of
 * tries; {@code -Pfuzz} raises it.
 */
class ReferenceParsersFuzzTest {

    private static byte[] resource(String name) {
        try (InputStream in = Objects.requireNonNull(ReferenceParsersFuzzTest.class.getResourceAsStream(name), name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Provide
    Arbitrary<byte[]> firds() {
        return Mutations.of(List.of(resource("firds-fixture.xml")));
    }

    @Provide
    Arbitrary<byte[]> gleif() {
        return Mutations.of(List.of(resource("golden-copy-fixture.csv"),
                resource("gleif-goldencopy-real-sample.csv")));
    }

    @Provide
    Arbitrary<byte[]> mic() {
        return Mutations.of(List.of(resource("iso10383-fixture.csv"), resource("iso10383-real-sample.csv")));
    }

    /** Whole-archive mutations (mostly caught by the ZIP CRC) and inner-part mutations, re-zipped. */
    @Provide
    Arbitrary<byte[]> xlsx() throws IOException {
        byte[] wb = CfiCodeRegistryTest.workbook();
        Map<String, String> parts = unzip(wb);
        Arbitrary<byte[]> inner = Arbitraries.of(List.copyOf(parts.keySet())).flatMap(part -> Arbitraries.longs()
                .map(seed -> {
                    Map<String, String> m = new LinkedHashMap<>(parts);
                    byte[] mutated = Mutations.mutate(m.get(part).getBytes(StandardCharsets.UTF_8),
                            new Random(seed));
                    m.put(part, new String(mutated, StandardCharsets.UTF_8));
                    try {
                        return CfiCodeRegistryTest.zip(m);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }));
        return Arbitraries.oneOf(Mutations.of(List.of(wb)), inner, inner, inner);
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

    @Property
    void firdsXmlRaisesOnlyIoException(@ForAll("firds") byte[] input) {
        try {
            FirdsInstrumentRegistry.load(new ByteArrayInputStream(input)).size();
        } catch (IOException expected) {
            // documented
        }
    }

    @Property
    void cfiXlsxRaisesOnlyIoException(@ForAll("xlsx") byte[] input) {
        try {
            CfiCodeRegistry r = CfiCodeRegistry.fromSixXlsx(new ByteArrayInputStream(input));
            r.groupCount();
            r.test("ESVUFR");
        } catch (IOException expected) {
            // documented
        }
    }

    @Property
    void gleifCsvRaisesOnlyIoException(@ForAll("gleif") byte[] input) {
        try {
            GleifGoldenCopyLeiRegistry.load(reader(input)).lookup("5493001KJTIIGC8Y1R12");
        } catch (IOException expected) {
            // documented
        }
    }

    @Property
    void micCsvRaisesOnlyIoException(@ForAll("mic") byte[] input) {
        try {
            Iso10383MicRegistry.load(reader(input)).lookup("XLON");
        } catch (IOException expected) {
            // documented
        }
    }

    private static Reader reader(byte[] b) {
        return new InputStreamReader(new ByteArrayInputStream(b), StandardCharsets.UTF_8);
    }
}
