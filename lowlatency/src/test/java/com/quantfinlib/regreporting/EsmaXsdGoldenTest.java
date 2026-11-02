package com.quantfinlib.regreporting;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The ESMA XSD check that runs on every pull request. The schemas themselves are
 * fetched only by the nightly job (ESMA65-8-2598 annex 2 zip, SHA-256 pinned in
 * {@code scripts/reference-files.sha256}), where {@link Auth016XmlTest},
 * {@link Auth016EnvelopeTest} and {@link StatusAdviceTest} validate three fixed
 * documents against them. This test pins the SHA-256 of those same three
 * documents ({@code esma-xsd-golden.sha256}), so a pull request that changes the
 * bytes of a validated document fails here, before the nightly run, and the
 * golden file has to be updated in the same change.
 *
 * <p>Evidence: self-consistency on pull requests; externally anchored nightly
 * (the bytes with these hashes are the bytes the ESMA XSDs accept).</p>
 */
class EsmaXsdGoldenTest {

    static Map<String, String> documents() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("auth016-samples", Rts22TransactionReport.toAuth016Xml(Auth016XmlTest.samples()));
        m.put("auth016-envelope", Auth016EnvelopeTest.file());
        m.put("auth031-worked-example", StatusAdviceTest.workedExample().toXml());
        return m;
    }

    static String sha256(String s) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    static Map<String, String> golden() throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        try (InputStream in = EsmaXsdGoldenTest.class.getResourceAsStream("esma-xsd-golden.sha256")) {
            assertNotNull(in, "esma-xsd-golden.sha256");
            for (String raw : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String line = raw.strip();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    String[] f = line.split("\\s+");
                    m.put(f[1], f[0]);
                }
            }
        }
        return m;
    }

    @Test
    void validatedDocumentsAreUnchanged() throws Exception {
        Map<String, String> expected = golden();
        Map<String, String> actual = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : documents().entrySet()) {
            actual.put(e.getKey(), sha256(e.getValue()));
        }
        assertEquals(expected, actual, "a document validated nightly against the ESMA XSDs changed; "
                + "check it against the XSDs (QFL_ESMA_* variables) and update esma-xsd-golden.sha256");
    }
}
