package com.quantfinlib.report;

import java.util.Locale;

/**
 * Dependency-free inline SVG charts for HTML reports: equity curves and
 * drawdown charts rendered as self-contained SVG strings (no external assets,
 * no JavaScript).
 *
 * <p>Why hand-rolled SVG and not a charting library: the report must open
 * identically in a browser, an email client, and an archive viewer ten
 * years from now — inline vector markup with no script and no fetch is the
 * only format with that guarantee, and it keeps the library's
 * zero-dependency promise. SVG is also resolution-independent (a compliance
 * PDF print at 300dpi stays sharp) and diff-able in version control.
 * {@code Locale.US} formatting throughout: an SVG coordinate written as
 * {@code "1,5"} by a German-locale JVM is the classic silently-blank-chart
 * bug. Output plugs into {@link Report.Builder#addHtmlSection} and renders
 * via {@link HtmlReportExporter} only.</p>
 */
public final class SvgCharts {

    private static final int LEFT = 70, RIGHT = 20, TOP = 20, BOTTOM = 30;

    private SvgCharts() {
    }

    /** Equity curve line chart (720x300). */
    public static String equityChart(double[] equity) {
        return lineChart(equity, 720, 300, "#16697a");
    }

    /** Drawdown area chart (720x220): 0 at the top, drawdowns filled below. */
    public static String drawdownChart(double[] equity) {
        double[] dd = new double[equity.length];
        double peak = equity[0];
        for (int i = 0; i < equity.length; i++) {
            peak = Math.max(peak, equity[i]);
            dd[i] = peak > 0 ? -(peak - equity[i]) / peak * 100 : 0;   // percent, <= 0
        }
        int width = 720, height = 220;
        double min = 0, max = 0;
        for (double d : dd) {
            min = Math.min(min, d);
        }
        if (min == 0) {
            min = -1;
        }
        StringBuilder sb = svgHeader(width, height, min, max, "%");
        StringBuilder points = new StringBuilder();
        points.append(fmt(x(0, dd.length, width))).append(',').append(fmt(y(0, min, max, height)));
        for (int i = 0; i < dd.length; i++) {
            points.append(' ').append(fmt(x(i, dd.length, width)))
                    .append(',').append(fmt(y(dd[i], min, max, height)));
        }
        points.append(' ').append(fmt(x(dd.length - 1, dd.length, width)))
                .append(',').append(fmt(y(0, min, max, height)));
        sb.append("<polygon points=\"").append(points)
                .append("\" fill=\"#c0392b\" fill-opacity=\"0.35\" stroke=\"#c0392b\" stroke-width=\"1\"/>\n");
        sb.append("</svg>");
        return sb.toString();
    }

    /** Generic line chart of a value series. */
    public static String lineChart(double[] values, int width, int height, String strokeColor) {
        if (values.length < 2) {
            throw new IllegalArgumentException("need at least two points");
        }
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        if (min == max) {
            min -= 1;
            max += 1;
        }
        StringBuilder sb = svgHeader(width, height, min, max, "");
        StringBuilder points = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                points.append(' ');
            }
            points.append(fmt(x(i, values.length, width))).append(',')
                    .append(fmt(y(values[i], min, max, height)));
        }
        sb.append("<polyline points=\"").append(points)
                .append("\" fill=\"none\" stroke=\"").append(strokeColor)
                .append("\" stroke-width=\"1.5\"/>\n</svg>");
        return sb.toString();
    }

    // ------------------------------------------------------------------

    private static StringBuilder svgHeader(int width, int height, double min, double max, String unit) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width)
                .append("\" height=\"").append(height)
                .append("\" viewBox=\"0 0 ").append(width).append(' ').append(height)
                .append("\" font-family=\"Segoe UI,Arial,sans-serif\" font-size=\"11\">\n");
        // Plot frame.
        sb.append("<rect x=\"").append(LEFT).append("\" y=\"").append(TOP)
                .append("\" width=\"").append(width - LEFT - RIGHT)
                .append("\" height=\"").append(height - TOP - BOTTOM)
                .append("\" fill=\"#fbfcfd\" stroke=\"#c9d2d9\"/>\n");
        // Y-axis labels: max, mid, min.
        double mid = (min + max) / 2;
        for (double[] tick : new double[][]{{max}, {mid}, {min}}) {
            double v = tick[0];
            double ty = y(v, min, max, height);
            sb.append("<line x1=\"").append(LEFT).append("\" y1=\"").append(fmt(ty))
                    .append("\" x2=\"").append(width - RIGHT).append("\" y2=\"").append(fmt(ty))
                    .append("\" stroke=\"#e3e8ec\" stroke-dasharray=\"3,3\"/>\n");
            sb.append("<text x=\"").append(LEFT - 6).append("\" y=\"").append(fmt(ty + 4))
                    .append("\" text-anchor=\"end\" fill=\"#556\">").append(label(v)).append(unit)
                    .append("</text>\n");
        }
        return sb;
    }

    private static double x(int index, int count, int width) {
        return LEFT + (double) index / (count - 1) * (width - LEFT - RIGHT);
    }

    private static double y(double value, double min, double max, int height) {
        double frac = (value - min) / (max - min);
        return TOP + (1 - frac) * (height - TOP - BOTTOM);
    }

    private static String label(double v) {
        if (Math.abs(v) >= 1_000_000) {
            return String.format(Locale.ROOT, "%.2fM", v / 1_000_000);
        }
        if (Math.abs(v) >= 10_000) {
            return String.format(Locale.ROOT, "%.1fk", v / 1_000);
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
