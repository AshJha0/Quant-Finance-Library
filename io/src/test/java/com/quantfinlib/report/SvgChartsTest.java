package com.quantfinlib.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SvgChartsTest {

    private static final double[] EQUITY = {100_000, 102_000, 101_000, 105_000, 99_000, 108_000};

    @Test
    void equityChartRendersValidSvg() {
        String svg = SvgCharts.equityChart(EQUITY);
        assertTrue(svg.startsWith("<svg"));
        assertTrue(svg.endsWith("</svg>"));
        assertTrue(svg.contains("<polyline"));
        assertTrue(svg.contains("108.0k"));   // max label
    }

    @Test
    void drawdownChartRendersFilledArea() {
        String svg = SvgCharts.drawdownChart(EQUITY);
        assertTrue(svg.contains("<polygon"));
        assertTrue(svg.contains("%"));        // percent axis labels
    }

    @Test
    void flatSeriesDoesNotDivideByZero() {
        String svg = SvgCharts.lineChart(new double[]{5, 5, 5}, 400, 200, "#000");
        assertTrue(svg.contains("<polyline"));
        assertFalse(svg.contains("NaN"));
    }

    @Test
    void chartsAppearInHtmlButNotTabularExports(@TempDir Path dir) throws Exception {
        ReportGenerator gen = new ReportGenerator("Chart Test")
                .addSection("Summary", java.util.Map.of("Metric", "1.0"))
                .addEquityCurveChart("Equity Curve", EQUITY)
                .addDrawdownChart("Drawdown", EQUITY);

        Path html = dir.resolve("r.html");
        gen.toHtml(html);
        String htmlText = Files.readString(html);
        assertTrue(htmlText.contains("<svg"));
        assertTrue(htmlText.contains("<h2>Equity Curve</h2>"));

        Path csv = dir.resolve("r.csv");
        new ReportGenerator("Chart Test")
                .addSection("Summary", java.util.Map.of("Metric", "1.0"))
                .addEquityCurveChart("Equity Curve", EQUITY)
                .toCsv(csv);
        String csvText = Files.readString(csv);
        assertFalse(csvText.contains("<svg"));
        assertFalse(csvText.contains("Equity Curve"));
        assertTrue(csvText.contains("Summary"));

        // PDF and XLSX also skip chart sections without corrupting output.
        Path pdf = dir.resolve("r.pdf");
        gen.toPdf(pdf);
        assertTrue(Files.readString(pdf, java.nio.charset.StandardCharsets.ISO_8859_1)
                .startsWith("%PDF-1.4"));
        Path xlsx = dir.resolve("r.xlsx");
        gen.toExcel(xlsx);
        assertTrue(Files.size(xlsx) > 0);
    }
}
