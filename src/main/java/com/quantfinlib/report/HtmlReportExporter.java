package com.quantfinlib.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;

/**
 * Self-contained styled HTML report: one file, inline CSS, no external
 * assets — so it can be emailed, archived for compliance, or opened from a
 * network share years later and still render identically (a report whose
 * stylesheet lives on a CDN is a report that rots). The one exporter that
 * renders {@link Report.Section#isHtml() HTML sections} (inline
 * {@link SvgCharts} output); tabular exporters skip them. Every title,
 * header and cell is HTML-escaped — report content is DATA, and a symbol
 * named {@code <script>} must render as text, not execute (tested).
 */
public final class HtmlReportExporter implements ReportExporter {

    @Override
    public void export(Report report, Path path) throws IOException {
        StringBuilder sb = new StringBuilder(16_384);
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<title>").append(escape(report.title())).append("</title>\n<style>\n")
                .append("body{font-family:'Segoe UI',Arial,sans-serif;margin:2rem;color:#1a1a2e;background:#f7f8fa}\n")
                .append("h1{border-bottom:3px solid #16a085;padding-bottom:.4rem}\n")
                .append("h2{color:#16697a;margin-top:2rem}\n")
                .append(".meta{color:#666;font-size:.9rem}\n")
                .append("table{border-collapse:collapse;background:#fff;box-shadow:0 1px 3px rgba(0,0,0,.12);min-width:40%}\n")
                .append("th{background:#16697a;color:#fff;text-align:left;padding:.5rem .9rem}\n")
                .append("td{padding:.45rem .9rem;border-bottom:1px solid #e3e6ea}\n")
                .append("tr:nth-child(even){background:#f2f5f7}\n")
                .append("</style>\n</head>\n<body>\n")
                .append("<h1>").append(escape(report.title())).append("</h1>\n")
                .append("<p class=\"meta\">Generated ")
                .append(report.generatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append(" by quantfinlib Quant-Finance-Library</p>\n");

        for (Report.Section s : report.sections()) {
            sb.append("<h2>").append(escape(s.title())).append("</h2>\n");
            if (s.isHtml()) {
                sb.append(s.html()).append('\n');
                continue;
            }
            sb.append("<table>\n<tr>");
            for (String h : s.headers()) {
                sb.append("<th>").append(escape(h)).append("</th>");
            }
            sb.append("</tr>\n");
            for (var row : s.rows()) {
                sb.append("<tr>");
                for (String cell : row) {
                    sb.append("<td>").append(escape(cell)).append("</td>");
                }
                sb.append("</tr>\n");
            }
            sb.append("</table>\n");
        }
        sb.append("</body>\n</html>\n");

        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, sb.toString());
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
