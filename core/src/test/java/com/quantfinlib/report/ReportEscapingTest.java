package com.quantfinlib.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The escaping paths of the exporters — the part that silently corrupts a
 * deliverable: an unquoted comma shifts every CSV column after it, an
 * unescaped ampersand produces an XLSX Excel refuses to open, an unescaped
 * angle bracket breaks (or scripts) the HTML report.
 */
class ReportEscapingTest {

    private static Report trickyReport() {
        return Report.builder("Escaping")
                .addTableSection("Cells & <edges>", List.of("plain", "tricky"),
                        List.of(
                                List.of("abc", "1,234"),
                                List.of("x\"y", "P&L <x>"),
                                List.of("3.14", "a\nb")))
                .build();
    }

    @Test
    void csvQuotesCommasDoublesQuotesAndLeavesPlainCellsBare(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("r.csv");
        new CsvReportExporter().export(trickyReport(), out);
        String csv = Files.readString(out);

        assertTrue(csv.contains("abc,\"1,234\""), "comma cell must be quoted, plain cell bare");
        assertTrue(csv.contains("\"x\"\"y\""), "inner quote must be doubled per RFC 4180");
        assertTrue(csv.contains("\"a\nb\""), "embedded newline must be quoted");
        assertFalse(csv.contains("\"abc\""), "plain cells must not be quoted");
    }

    @Test
    void xlsxTypesNumbersEscapesXmlAndTreatsCommaNumbersAsText(@TempDir Path dir)
            throws IOException {
        Path out = dir.resolve("r.xlsx");
        new XlsxReportExporter().export(trickyReport(), out);
        String sheet;
        try (ZipFile zip = new ZipFile(out.toFile())) {
            ZipEntry entry = zip.getEntry("xl/worksheets/sheet1.xml");
            try (InputStream in = zip.getInputStream(entry)) {
                sheet = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        assertTrue(sheet.contains("<c t=\"n\"><v>3.14</v></c>"),
                "parseable numbers must be typed numeric");
        assertTrue(sheet.contains("<t xml:space=\"preserve\">1,234</t>"),
                "1,234 does not parse as a double -> inline string, verbatim");
        assertTrue(sheet.contains("P&amp;L &lt;x&gt;"), "XML metachars must be escaped");
        assertFalse(sheet.contains("P&L <x>"), "raw ampersand would make Excel reject the file");
    }

    @Test
    void htmlEscapesTitlesHeadersAndCells(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("r.html");
        new HtmlReportExporter().export(trickyReport(), out);
        String html = Files.readString(out);

        assertTrue(html.contains("Cells &amp; &lt;edges&gt;"), "section title must be escaped");
        assertTrue(html.contains("P&amp;L &lt;x&gt;"), "cells must be escaped");
        assertFalse(html.contains("<x>"), "raw angle brackets are markup injection");
    }
}
