package com.fdequant.report;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Format-agnostic report model: an ordered list of titled table sections.
 * Exporters ({@link HtmlReportExporter}, {@link PdfReportExporter},
 * {@link XlsxReportExporter}, {@link CsvReportExporter}) render this model.
 */
public final class Report {

    public record Section(String title, List<String> headers, List<List<String>> rows) {
    }

    private final String title;
    private final LocalDateTime generatedAt;
    private final List<Section> sections;

    private Report(String title, List<Section> sections) {
        this.title = title;
        this.generatedAt = LocalDateTime.now();
        this.sections = List.copyOf(sections);
    }

    public String title()               { return title; }
    public LocalDateTime generatedAt()  { return generatedAt; }
    public List<Section> sections()     { return sections; }

    public static Builder builder(String title) {
        return new Builder(title);
    }

    public static final class Builder {
        private final String title;
        private final List<Section> sections = new ArrayList<>();

        private Builder(String title) {
            this.title = title;
        }

        /** Two-column "Metric / Value" section. Map iteration order is preserved. */
        public Builder addKeyValueSection(String sectionTitle, Map<String, String> values) {
            List<List<String>> rows = new ArrayList<>();
            values.forEach((k, v) -> rows.add(List.of(k, v)));
            sections.add(new Section(sectionTitle, List.of("Metric", "Value"), rows));
            return this;
        }

        public Builder addTableSection(String sectionTitle, List<String> headers, List<List<String>> rows) {
            sections.add(new Section(sectionTitle, List.copyOf(headers), List.copyOf(rows)));
            return this;
        }

        public Report build() {
            return new Report(title, sections);
        }
    }
}
