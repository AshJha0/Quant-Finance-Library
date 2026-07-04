package com.quantfinlib.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal dependency-free PDF writer (PDF 1.4, Courier text, multi-page).
 * Renders each report section as a monospaced table.
 */
public final class PdfReportExporter implements ReportExporter {

    private static final int LINES_PER_PAGE = 56;
    private static final int PAGE_WIDTH = 612;
    private static final int PAGE_HEIGHT = 792;

    @Override
    public void export(Report report, Path path) throws IOException {
        List<String> lines = layout(report);
        List<List<String>> pages = paginate(lines);

        ByteArrayOutputStream out = new ByteArrayOutputStream(32_768);
        List<Integer> offsets = new ArrayList<>();      // offsets[i] = byte offset of object i+1
        write(out, "%PDF-1.4\n");

        int pageCount = pages.size();
        // Object layout: 1=Catalog, 2=Pages, 3=Font, then per page i: (4+2i)=Page, (5+2i)=Content.
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pageCount; i++) {
            kids.append(4 + 2 * i).append(" 0 R ");
        }

        offsets.add(out.size());
        write(out, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        offsets.add(out.size());
        write(out, "2 0 obj\n<< /Type /Pages /Kids [" + kids.toString().trim()
                + "] /Count " + pageCount + " >>\nendobj\n");
        offsets.add(out.size());
        write(out, "3 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Courier >>\nendobj\n");

        for (int i = 0; i < pageCount; i++) {
            int pageObj = 4 + 2 * i;
            int contentObj = 5 + 2 * i;
            offsets.add(out.size());
            write(out, pageObj + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "
                    + PAGE_WIDTH + " " + PAGE_HEIGHT + "] /Contents " + contentObj
                    + " 0 R /Resources << /Font << /F1 3 0 R >> >> >>\nendobj\n");

            byte[] stream = contentStream(pages.get(i)).getBytes(StandardCharsets.ISO_8859_1);
            offsets.add(out.size());
            write(out, contentObj + " 0 obj\n<< /Length " + stream.length + " >>\nstream\n");
            out.write(stream);
            write(out, "endstream\nendobj\n");
        }

        int xrefOffset = out.size();
        int totalObjects = offsets.size();
        StringBuilder xref = new StringBuilder();
        xref.append("xref\n0 ").append(totalObjects + 1).append("\n");
        xref.append("0000000000 65535 f \n");
        for (int off : offsets) {
            xref.append(String.format("%010d 00000 n \n", off));
        }
        xref.append("trailer\n<< /Size ").append(totalObjects + 1)
                .append(" /Root 1 0 R >>\nstartxref\n").append(xrefOffset).append("\n%%EOF\n");
        write(out, xref.toString());

        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.write(path, out.toByteArray());
    }

    // ------------------------------------------------------------------

    private static List<String> layout(Report report) {
        List<String> lines = new ArrayList<>();
        lines.add(report.title());
        lines.add("Generated " + report.generatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                + " by quantfinlib Quant-Finance-Library");
        for (Report.Section s : report.sections()) {
            if (s.isHtml()) {
                continue;   // charts are HTML-export only
            }
            lines.add("");
            lines.add("== " + s.title() + " ==");
            int cols = s.headers().size();
            int[] widths = new int[cols];
            for (int c = 0; c < cols; c++) {
                widths[c] = s.headers().get(c).length();
            }
            for (var row : s.rows()) {
                for (int c = 0; c < cols && c < row.size(); c++) {
                    widths[c] = Math.max(widths[c], row.get(c).length());
                }
            }
            lines.add(formatRow(s.headers(), widths));
            for (var row : s.rows()) {
                lines.add(formatRow(row, widths));
            }
        }
        return lines;
    }

    private static String formatRow(List<String> cells, int[] widths) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < widths.length; c++) {
            String v = c < cells.size() ? cells.get(c) : "";
            sb.append(v);
            for (int p = v.length(); p < widths[c] + 2; p++) {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    private static List<List<String>> paginate(List<String> lines) {
        List<List<String>> pages = new ArrayList<>();
        for (int i = 0; i < lines.size(); i += LINES_PER_PAGE) {
            pages.add(lines.subList(i, Math.min(lines.size(), i + LINES_PER_PAGE)));
        }
        if (pages.isEmpty()) {
            pages.add(List.of(""));
        }
        return pages;
    }

    private static String contentStream(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        sb.append("BT\n/F1 9 Tf\n12 TL\n50 ").append(PAGE_HEIGHT - 50).append(" Td\n");
        boolean first = true;
        for (String line : lines) {
            if (!first) {
                sb.append("T*\n");
            }
            sb.append('(').append(escape(line)).append(") Tj\n");
            first = false;
        }
        sb.append("ET\n");
        return sb.toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c == '\\' || c == '(' || c == ')') {
                sb.append('\\');
            }
            // Courier/Type1 is Latin-1; replace anything outside it.
            sb.append(c <= 255 ? c : '?');
        }
        return sb.toString();
    }

    private static void write(ByteArrayOutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}
