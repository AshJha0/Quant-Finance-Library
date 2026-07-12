package com.quantfinlib.report;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** CSV export: sections separated by "## title" marker lines. */
public final class CsvReportExporter implements ReportExporter {

    @Override
    public void export(Report report, Path path) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        try (Writer w = Files.newBufferedWriter(path)) {
            w.write("# " + report.title() + "\n");
            for (Report.Section s : report.sections()) {
                if (s.isHtml()) {
                    continue;   // charts are HTML-export only
                }
                w.write("\n## " + s.title() + "\n");
                w.write(String.join(",", s.headers().stream().map(CsvReportExporter::quote).toList()) + "\n");
                for (var row : s.rows()) {
                    w.write(String.join(",", row.stream().map(CsvReportExporter::quote).toList()) + "\n");
                }
            }
        }
    }

    private static String quote(String v) {
        // \r included: a bare carriage return is a record terminator to
        // strict RFC-4180 readers and must be quoted like \n.
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
