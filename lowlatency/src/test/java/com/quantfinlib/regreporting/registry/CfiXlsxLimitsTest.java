package com.quantfinlib.regreporting.registry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Zip-bomb guards of the XLSX reader: entry count, per-part and total size (own test). */
class CfiXlsxLimitsTest {

    static byte[] zip(int entries, int bytesEach) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            for (int i = 0; i < entries; i++) {
                z.putNextEntry(new ZipEntry("xl/p" + i + ".xml"));
                z.write(new byte[bytesEach]);
                z.closeEntry();
            }
        }
        return bo.toByteArray();
    }

    @Test
    void entryCountIsCapped() throws IOException {
        byte[] b = zip(11, 1);
        assertEquals(10, CfiCodeRegistry.unzip(new ByteArrayInputStream(zip(10, 1)), 10, 100, 1000).size());
        IOException e = assertThrows(IOException.class,
                () -> CfiCodeRegistry.unzip(new ByteArrayInputStream(b), 10, 100, 1000));
        assertTrue(e.getMessage().contains("zip entries"));
    }

    @Test
    void totalSizeIsCapped() throws IOException {
        byte[] b = zip(5, 100);   // each part under the 200-byte part cap, 500 in total
        IOException e = assertThrows(IOException.class,
                () -> CfiCodeRegistry.unzip(new ByteArrayInputStream(b), 100, 200, 450));
        assertTrue(e.getMessage().contains("uncompressed"));
        assertEquals(5, CfiCodeRegistry.unzip(new ByteArrayInputStream(b), 100, 200, 500).size());
    }

    @Test
    void partSizeIsCapped() throws IOException {
        byte[] b = zip(1, 300);
        assertThrows(IOException.class, () -> CfiCodeRegistry.unzip(new ByteArrayInputStream(b), 100, 200, 1000));
    }

    @Test
    void defaultsAreTheDocumentedOnes() {
        assertEquals(10_000, CfiCodeRegistry.MAX_ENTRIES);
        assertEquals(64L << 20, CfiCodeRegistry.MAX_ENTRY_BYTES);
        assertEquals(256L << 20, CfiCodeRegistry.MAX_TOTAL_BYTES);
    }
}
