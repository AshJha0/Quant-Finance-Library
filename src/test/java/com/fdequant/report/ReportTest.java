package com.fdequant.report;

import com.fdequant.TestData;
import com.fdequant.backtest.BacktestConfig;
import com.fdequant.backtest.BacktestResult;
import com.fdequant.backtest.Backtester;
import com.fdequant.backtest.strategies.SmaCrossStrategy;
import com.fdequant.core.BarSeries;
import com.fdequant.risk.Portfolio;
import com.fdequant.simulation.MonteCarloSimulator;
import com.fdequant.simulation.SimulationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportTest {

    private ReportGenerator sampleReport() {
        BarSeries s = TestData.gbmSeries("EQ_TEST", 400, 100, 0.1, 0.2, 17);
        BacktestResult bt = Backtester.run(new SmaCrossStrategy(10, 30), s, BacktestConfig.defaults());
        SimulationResult sim = new MonteCarloSimulator(5).simulate(100_000, 0.07, 0.15, 60, 5_000);
        Portfolio p = new Portfolio().addPosition("EQ_TEST", 100, s.lastClose());
        return new ReportGenerator("Test Review")
                .addPortfolioSummary(p)
                .addStrategyPerformance(bt)
                .addMonteCarlo(sim)
                .addTradeHistory(bt.trades())
                .addTechnicalSummary(s);
    }

    @Test
    void htmlContainsTitleAndSections(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("r.html");
        sampleReport().toHtml(out);
        String html = Files.readString(out);
        assertTrue(html.contains("<h1>Test Review</h1>"));
        assertTrue(html.contains("Monte Carlo Simulation"));
        assertTrue(html.contains("Strategy Performance"));
    }

    @Test
    void csvExports(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("r.csv");
        sampleReport().toCsv(out);
        String csv = Files.readString(out);
        assertTrue(csv.startsWith("# Test Review"));
        assertTrue(csv.contains("## Monte Carlo Simulation"));
    }

    @Test
    void pdfHasValidHeaderAndTrailer(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("r.pdf");
        sampleReport().toPdf(out);
        byte[] bytes = Files.readAllBytes(out);
        String head = new String(bytes, 0, 8, StandardCharsets.ISO_8859_1);
        String tail = new String(bytes, Math.max(0, bytes.length - 64), Math.min(64, bytes.length),
                StandardCharsets.ISO_8859_1);
        assertTrue(head.startsWith("%PDF-1.4"));
        assertTrue(tail.contains("%%EOF"));
        assertTrue(tail.contains("startxref"));
    }

    @Test
    void xlsxIsWellFormedZipWithWorkbookParts(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("r.xlsx");
        sampleReport().toExcel(out);
        int parts = 0;
        boolean sawSheet = false;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(out))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                parts++;
                if (e.getName().equals("xl/worksheets/sheet1.xml")) {
                    sawSheet = true;
                    String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    assertTrue(xml.contains("Test Review"));
                }
            }
        }
        assertEquals(5, parts);
        assertTrue(sawSheet);
    }
}
