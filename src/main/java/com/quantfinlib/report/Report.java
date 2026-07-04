package com.quantfinlib.report;

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

    /**
     * A report section: either tabular (headers + rows) or raw HTML content
     * such as an inline SVG chart ({@code html != null}). HTML sections are
     * rendered by the HTML exporter only; tabular exporters skip them.
     */
    public record Section(String title, List<String> headers, List<List<String>> rows, String html) {

        public Section(String title, List<String> headers, List<List<String>> rows) {
            this(title, headers, rows, null);
        }

        public boolean isHtml() {
            return html != null;
        }
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

        /** Raw HTML section (e.g. an inline SVG chart); HTML export only. */
        public Builder addHtmlSection(String sectionTitle, String html) {
            sections.add(new Section(sectionTitle, List.of(), List.of(), html));
            return this;
        }

        public Report build() {
            return new Report(title, sections);
        }
    }
}
