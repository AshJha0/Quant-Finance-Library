package com.quantfinlib.cli;

import com.quantfinlib.TestData;
import com.quantfinlib.data.CsvBarLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliTest {

    @TempDir
    Path dir;
    private Path csv;

    @BeforeEach
    void writeData() throws Exception {
        csv = dir.resolve("bars.csv");
        CsvBarLoader.save(TestData.gbmSeries("EQ_TEST", 600, 100, 0.10, 0.2, 7), csv);
    }

    @Test
    void backtestCommandRunsAndWritesReport() {
        Path out = dir.resolve("backtest.html");
        int code = Main.run(new String[]{"backtest",
                "--csv", csv.toString(), "--symbol", "EQ_TEST",
                "--strategy", "sma", "--fast", "10", "--slow", "30",
                "--capital", "50000", "--out", out.toString()});
        assertEquals(0, code);
        assertTrue(Files.exists(out));
        try {
            String html = Files.readString(out);
            assertTrue(html.contains("<svg"));
            assertTrue(html.contains("SMA_CROSS(10,30)"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void allStrategiesAreConstructible() {
        for (String strategy : new String[]{"sma", "ema", "rsi", "macd", "bollinger"}) {
            assertEquals(0, Main.run(new String[]{"backtest",
                    "--csv", csv.toString(), "--symbol", "EQ_TEST",
                    "--strategy", strategy}), "strategy " + strategy);
        }
    }

    @Test
    void walkforwardCommandRunsWithGrid() {
        Path out = dir.resolve("wf.html");
        int code = Main.run(new String[]{"walkforward",
                "--csv", csv.toString(), "--symbol", "EQ_TEST",
                "--train", "252", "--test", "100",
                "--fast", "5,10", "--slow", "30,50",
                "--out", out.toString()});
        assertEquals(0, code);
        assertTrue(Files.exists(out));
    }

    @Test
    void reportCommandWritesHtml() throws Exception {
        Path out = dir.resolve("market.html");
        assertEquals(0, Main.run(new String[]{"report",
                "--csv", csv.toString(), "--symbol", "EQ_TEST", "--out", out.toString()}));
        assertTrue(Files.readString(out).contains("Technical Analysis Summary"));
    }

    @Test
    void usageErrorsReturnOne() {
        assertEquals(1, Main.run(new String[]{}));
        assertEquals(1, Main.run(new String[]{"frobnicate"}));
        assertEquals(1, Main.run(new String[]{"backtest", "--symbol", "X"}));   // missing --csv
        assertEquals(1, Main.run(new String[]{"backtest", "--csv", csv.toString(),
                "--symbol", "X", "--strategy", "nope"}));
        assertEquals(0, Main.run(new String[]{"help"}));
    }

    @Test
    void executionFailuresReturnTwo() {
        assertEquals(2, Main.run(new String[]{"backtest",
                "--csv", dir.resolve("missing.csv").toString(), "--symbol", "X"}));
    }
}
