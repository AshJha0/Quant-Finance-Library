package com.quantfinlib.regreporting.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.quantfinlib.microstructure.Execution;
import com.quantfinlib.core.Side;
import com.quantfinlib.regreporting.ReportingIdentifiers;
import com.quantfinlib.regreporting.Rts22Field;
import com.quantfinlib.regreporting.Rts22ReportBuilder;
import com.quantfinlib.regreporting.Rts22TransactionReport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * CON-430 table (ESMA65-8-2594 Annex 1 v1.7 rule 222). The SIX ISO 10962 workbook is ISO
 * copyright and not redistributed, so this test builds a tiny workbook in its layout
 * (sheet names like {@code ESXXXX}, "First attribute" sections in column A and letters in
 * column B, shared and inline strings, as in {@code cfi-20210507-current.xlsx}); the letters for group ES are
 * those of that file. Set QFL_SIX_CFI_XLSX to the real file to run the last test.
 */
class CfiCodeRegistryTest {

    private static String cell(String ref, String v) {
        return "<c r=\"" + ref + "\" t=\"inlineStr\"><is><t>" + v + "</t></is></c>";
    }

    /** A row with column A and B text (either may be empty); shared-string index when {@code s} is set. */
    private static String row(int n, String a, String b, Integer s) {
        StringBuilder sb = new StringBuilder("<row r=\"" + n + "\">");
        if (!a.isEmpty()) {
            sb.append(s == null ? cell("A" + n, a) : "<c r=\"A" + n + "\" t=\"s\"><v>" + s + "</v></c>");
        }
        if (!b.isEmpty()) {
            sb.append(cell("B" + n, b));
        }
        sb.append("<c r=\"D" + n + "\" t=\"inlineStr\"><is><t>ignored</t></is></c>");
        return sb.append("</row>").toString();
    }

    private static String sheet(String[][] rows) {
        StringBuilder sb = new StringBuilder("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<sheetData>");
        for (int i = 0; i < rows.length; i++) {
            Integer s = rows[i][0].equals("First attribute") ? Integer.valueOf(0) : null;
            sb.append(row(i + 1, rows[i][0], rows[i][1], s));
        }
        return sb.append("</sheetData></worksheet>").toString();
    }

    /** Group ES with the real attribute letters of the SIX file, group DB with only X in attribute 4. */
    static byte[] workbook() throws IOException {
        String ss = "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<si><r><t>First </t></r><r><t>attribute</t></r></si></sst>";
        String es = sheet(new String[][] {{"Common/ordinary shares", "(E-S)"}, {"First attribute", ""},
            {"", "V"}, {"", "N"}, {"", "R"}, {"", "E"}, {"Second attribute", ""}, {"", "T"}, {"", "U"},
            {"Third attribute", ""}, {"", "O"}, {"", "P"}, {"", "F"}, {"Fourth attribute", ""}, {"", "B"},
            {"", "R"}, {"", "N"}, {"", "M"}, {"", "ab"}, {"", "q"}, {"Return to Equities", "Z"}, {"", "Y"}});
        String db = sheet(new String[][] {{"Bonds", "(D-B)"}, {"First attribute", ""}, {"", "F"},
            {"Second attribute", ""}, {"", "T"}, {"Third attribute", ""}, {"", "F"}, {"Fourth attribute", ""},
            {"", "X"}});
        String wb = "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>"
                + "<sheet name=\"Introduction\" sheetId=\"1\" r:id=\"rId1\"/>"
                + "<sheet name=\"ESXXXX\" sheetId=\"2\" r:id=\"rId2\"/>"
                + "<sheet name=\"DBXXXX\" sheetId=\"3\" r:id=\"rId3\"/></sheets></workbook>";
        String rels = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Target=\"worksheets/sheet2.xml\"/>"
                + "<Relationship Id=\"rId3\" Target=\"/xl/worksheets/sheet3.xml\"/></Relationships>";
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("xl/workbook.xml", wb);
        parts.put("xl/_rels/workbook.xml.rels", rels);
        parts.put("xl/sharedStrings.xml", ss);
        parts.put("xl/worksheets/sheet1.xml", "<worksheet><sheetData><row r=\"1\"><c r=\"D1\" t=\"s\"><v>1</v></c>"
                + "</row></sheetData></worksheet>");
        parts.put("xl/worksheets/sheet2.xml", es);
        parts.put("xl/worksheets/sheet3.xml", db);
        parts.put("docProps/app.xml", "<x/>");
        return zip(parts);
    }

    static byte[] zip(Map<String, String> parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("xl/worksheets/"));
            z.closeEntry();
            for (Map.Entry<String, String> e : parts.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    static CfiCodeRegistry table() throws IOException {
        return CfiCodeRegistry.fromSixXlsx(new ByteArrayInputStream(workbook()));
    }

    @Test
    void workedExample() throws IOException {
        CfiCodeRegistry t = table();
        assertEquals(2, t.groupCount());
        assertTrue(t.test("ESVUFR"));
        assertFalse(t.test("ESXUFR"), "X is not listed for attribute 1 of ES");
        assertFalse(t.test("EZVUFR"), "no group EZ");
        assertEquals(Optional.of("no ISO 10962 group EZ"), t.problem("EZVUFR"));
        assertEquals(Optional.of("letter X is not allowed as attribute 1 of group ES"), t.problem("ESXUFR"));
        assertEquals(Optional.empty(), t.problem("ESNTPM"));
        assertEquals(Set.of('V', 'N', 'R', 'E'), t.allowed("ES", 1));
        assertEquals(Set.of('B', 'R', 'N', 'M'), t.allowed("ES", 4));
        assertEquals(Set.of(), t.allowed("ZZ", 1));
        // Every position is checked.
        assertFalse(t.test("ESVAFR"));
        assertFalse(t.test("ESVUAR"));
        assertFalse(t.test("ESVUFA"));
        // DB lists X for attribute 4 only.
        assertTrue(t.test("DBFTFX"));
        assertFalse(t.test("DBXTFX"));
    }

    @Test
    void undefinedLeniency() throws IOException {
        CfiCodeRegistry lenient = table().withUndefinedAllowed();
        assertTrue(lenient.test("ESXXXX"));
        assertTrue(lenient.test("ESVXFX"));
        assertFalse(lenient.test("ESAXXX"));
        assertFalse(lenient.test("EZXXXX"));
    }

    @Test
    void badInput() throws IOException {
        CfiCodeRegistry t = table();
        assertFalse(t.test(null));
        assertFalse(t.test("ESVUF"));
        assertFalse(t.test("ESVUFRR"));
        assertThrows(IllegalArgumentException.class, () -> t.allowed("ES", 0));
        assertThrows(IllegalArgumentException.class, () -> t.allowed("ES", 5));
        assertThrows(NullPointerException.class, () -> t.allowed(null, 1));
        assertThrows(NullPointerException.class, () -> CfiCodeRegistry.fromSixXlsx((java.io.InputStream) null));
    }

    @Test
    void notAWorkbook() throws IOException {
        assertThrows(IOException.class, () -> CfiCodeRegistry.fromSixXlsx(new ByteArrayInputStream(new byte[0])));
        Map<String, String> noGroups = new LinkedHashMap<>();
        noGroups.put("xl/workbook.xml", "<workbook><sheets><sheet name=\"Introduction\" id=\"rId1\"/></sheets></workbook>");
        noGroups.put("xl/_rels/workbook.xml.rels", "<Relationships/>");
        assertThrows(IOException.class, () -> CfiCodeRegistry.fromSixXlsx(new ByteArrayInputStream(zip(noGroups))));
        Map<String, String> missingSheet = new LinkedHashMap<>(noGroups);
        missingSheet.put("xl/workbook.xml", "<workbook><sheets><sheet name=\"ESXXXX\" id=\"rId1\"/></sheets></workbook>");
        missingSheet.put("xl/_rels/workbook.xml.rels",
                "<Relationships><Relationship Id=\"rId1\" Target=\"worksheets/nope.xml\"/></Relationships>");
        assertThrows(IOException.class, () -> CfiCodeRegistry.fromSixXlsx(new ByteArrayInputStream(zip(missingSheet))));
        Map<String, String> noRels = new LinkedHashMap<>();
        noRels.put("xl/workbook.xml", "<workbook/>");
        assertThrows(IOException.class, () -> CfiCodeRegistry.fromSixXlsx(new ByteArrayInputStream(zip(noRels))));
        Map<String, String> badXml = new LinkedHashMap<>(noRels);
        badXml.put("xl/_rels/workbook.xml.rels", "<Relationships");
        assertThrows(IOException.class, () -> CfiCodeRegistry.fromSixXlsx(new ByteArrayInputStream(zip(badXml))));
    }

    @Test
    void loadsFromPath() throws IOException {
        Path p = Files.createTempFile("qfl30_rts22_cfi", ".xlsx");
        try {
            Files.write(p, workbook());
            assertTrue(CfiCodeRegistry.fromSixXlsx(p).test("ESVUFR"));
        } finally {
            Files.deleteIfExists(p);
        }
    }

    @Test
    void con430UsesTheTable() throws IOException {
        // An OTC bond described in full with CFI DBFTFB: attribute 4 of DB lists only X here.
        String lei = "5493000TESTFIRM001" + ReportingIdentifiers.leiCheckDigits("5493000TESTFIRM001");
        Rts22TransactionReport r = new Rts22ReportBuilder(lei).venue("XXXX").isin("")
                .set(Rts22Field.INSTRUMENT_CLASSIFICATION, "DBFTFB")
                .fromExecution(new Execution("X", Side.BUY, 99.5, 100, 1_793_614_530_123_456_000L, ""), "C1");
        ReferenceData refs = ReferenceData.of(null, null, null).withCfiCodes(table());
        List<String> e = r.validateWithReferenceData(refs, LocalDate.of(2026, 11, 3));
        assertTrue(e.stream().anyMatch(s -> s.startsWith("CON-430:") && s.contains("attribute 4 of group DB")),
                e.toString());
        Rts22TransactionReport ok = new Rts22ReportBuilder(lei).venue("XXXX").isin("")
                .set(Rts22Field.INSTRUMENT_CLASSIFICATION, "DBFTFX")
                .fromExecution(new Execution("X", Side.BUY, 99.5, 100, 1_793_614_530_123_456_000L, ""), "C2");
        assertFalse(ok.validateWithReferenceData(refs, LocalDate.of(2026, 11, 3)).stream()
                .anyMatch(s -> s.startsWith("CON-430:")));
    }

    @Test
    void realSixWorkbook() throws IOException {
        String env = System.getenv("QFL_SIX_CFI_XLSX");
        assumeTrue(env != null && Files.isRegularFile(Path.of(env)), "QFL_SIX_CFI_XLSX not set");
        CfiCodeRegistry t = CfiCodeRegistry.fromSixXlsx(Path.of(env));
        assertEquals(78, t.groupCount());
        // ED: the column D patterns carry a stray X; the listed fourth-attribute letters are B, R, N, M.
        assertEquals(Set.of('B', 'R', 'N', 'M'), t.allowed("ED", 4));
        for (String c : new String[] {"DBFTFB", "OCESPS", "FCEPSX", "SCBCCA", "JFTXFP"}) {
            assertTrue(t.test(c), c);
        }
        assertEquals(Set.of('V', 'N', 'R', 'E'), t.allowed("ES", 1));
        assertTrue(t.test("ESVUFR"));
        assertFalse(t.test("ESXUFR"));
    }
}
