package com.quantfinlib.report;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Minimal dependency-free XLSX (Office Open XML spreadsheet) writer. Produces
 * a single-sheet workbook with all report sections stacked vertically; numeric
 * cells are typed so Excel treats them as numbers.
 */
public final class XlsxReportExporter implements ReportExporter {

    @Override
    public void export(Report report, Path path) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        try (OutputStream fos = Files.newOutputStream(path);
             ZipOutputStream zip = new ZipOutputStream(fos)) {
            put(zip, "[Content_Types].xml", CONTENT_TYPES);
            put(zip, "_rels/.rels", ROOT_RELS);
            put(zip, "xl/workbook.xml", WORKBOOK);
            put(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS);
            put(zip, "xl/worksheets/sheet1.xml", sheetXml(report));
        }
    }

    private static String sheetXml(Report report) {
        StringBuilder sb = new StringBuilder(16_384);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                .append("<sheetData>");
        appendRow(sb, List.of(report.title()));
        for (Report.Section s : report.sections()) {
            if (s.isHtml()) {
                continue;   // charts are HTML-export only
            }
            appendRow(sb, List.of());
            appendRow(sb, List.of(s.title()));
            appendRow(sb, s.headers());
            for (var row : s.rows()) {
                appendRow(sb, row);
            }
        }
        sb.append("</sheetData></worksheet>");
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, List<String> cells) {
        sb.append("<row>");
        for (String cell : cells) {
            if (isNumeric(cell)) {
                sb.append("<c t=\"n\"><v>").append(cell).append("</v></c>");
            } else {
                sb.append("<c t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(escapeXml(cell)).append("</t></is></c>");
            }
        }
        sb.append("</row>");
    }

    private static boolean isNumeric(String s) {
        // Strict OOXML decimal syntax, NOT Double.parseDouble: parseDouble
        // accepts Java float-literal forms ("1D", "3f", "+5", hex floats)
        // that are invalid inside <v> — one such cell makes Excel declare
        // the entire workbook corrupt, not just the cell.
        if (s.isEmpty()) {
            return false;
        }
        return s.matches("-?(\\d+(\\.\\d*)?|\\.\\d+)([eE][-+]?\\d+)?");
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static final String CONTENT_TYPES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
            <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
            <Default Extension="xml" ContentType="application/xml"/>
            <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
            <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
            </Types>
            """;

    private static final String ROOT_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
            </Relationships>
            """;

    private static final String WORKBOOK = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
            <sheets><sheet name="Report" sheetId="1" r:id="rId1"/></sheets>
            </workbook>
            """;

    private static final String WORKBOOK_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
            </Relationships>
            """;
}
