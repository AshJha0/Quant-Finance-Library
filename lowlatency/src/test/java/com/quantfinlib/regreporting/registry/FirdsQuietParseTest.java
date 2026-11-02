package com.quantfinlib.regreporting.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * {@link FirdsInstrumentRegistry#load(java.io.InputStream)} reports bad input only through
 * its {@link IOException}, never on stderr (the JDK parser printed {@code [Fatal Error]}
 * before 3.1.0), and still honours the declared encoding and a UTF-8 byte order mark.
 * Evidence class: self-consistency.
 */
class FirdsQuietParseTest {
    private static final String DOC = "<Doc><RefData><FinInstrmGnlAttrbts><Id>DE000A0D6554</Id>"
            + "<FullNm>Café SA</FullNm><ClssfctnTp>ESVUFR</ClssfctnTp></FinInstrmGnlAttrbts></RefData></Doc>";

    @Test
    void malformedUtf8IsQuiet() {
        byte[] bad = {'<', 'D', 'o', 'c', '>', (byte) 0xC3, '(', '<', '/', 'D', 'o', 'c', '>'};
        PrintStream old = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            assertThrows(IOException.class, () -> FirdsInstrumentRegistry.load(new ByteArrayInputStream(bad)));
        } finally {
            System.setErr(old);
        }
        assertEquals("", err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void declaredEncodingAndBomStillParse() throws IOException {
        byte[] latin = ("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + DOC).getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(1, FirdsInstrumentRegistry.load(new ByteArrayInputStream(latin)).size());
        byte[] utf8 = DOC.getBytes(StandardCharsets.UTF_8);
        byte[] bom = new byte[utf8.length + 3];
        bom[0] = (byte) 0xEF;
        bom[1] = (byte) 0xBB;
        bom[2] = (byte) 0xBF;
        System.arraycopy(utf8, 0, bom, 3, utf8.length);
        assertEquals(1, FirdsInstrumentRegistry.load(new ByteArrayInputStream(bom)).size());
        byte[] utf16 = ("﻿" + DOC).getBytes(StandardCharsets.UTF_16BE);
        assertEquals(1, FirdsInstrumentRegistry.load(new ByteArrayInputStream(utf16)).size());
        IOException e = assertThrows(IOException.class, () -> FirdsInstrumentRegistry.load(new ByteArrayInputStream(
                "<?xml version=\"1.0\" encoding=\"NOPE-42\"?><Doc/>".getBytes(StandardCharsets.US_ASCII))));
        assertTrue(e.getMessage().contains("NOPE-42"));
    }
}
