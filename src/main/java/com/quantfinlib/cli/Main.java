package com.quantfinlib.cli;

import com.quantfinlib.backtest.BacktestConfig;
import com.quantfinlib.backtest.BacktestResult;
import com.quantfinlib.backtest.Backtester;
import com.quantfinlib.backtest.PerformanceMetrics;
import com.quantfinlib.backtest.TradingStrategy;
import com.quantfinlib.backtest.strategies.BollingerBandsStrategy;
import com.quantfinlib.backtest.strategies.EmaCrossStrategy;
import com.quantfinlib.backtest.strategies.MacdStrategy;
import com.quantfinlib.backtest.strategies.RsiStrategy;
import com.quantfinlib.backtest.strategies.SmaCrossStrategy;
import com.quantfinlib.backtest.validation.ParameterGrid;
import com.quantfinlib.backtest.validation.WalkForwardAnalyzer;
import com.quantfinlib.core.BarSeries;
import com.quantfinlib.data.CsvBarLoader;
import com.quantfinlib.report.ReportGenerator;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Command-line entry point: run backtests, walk-forward validation, and HTML
 * reports on CSV bar data without writing Java.
 *
 * <pre>
 * java -cp quant-finance-library.jar com.quantfinlib.cli.Main \
 *     backtest --csv bars.csv --symbol EURUSD --strategy sma --fast 10 --slow 30 --out report.html
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** Testable entry: 0 = ok, 1 = usage error, 2 = execution failure. */
    public static int run(String[] args) {
        if (args.length == 0) {
            printUsage();
            return 1;
        }
        if (args[0].equals("help") || args[0].equals("-h") || args[0].equals("--help")) {
            printUsage();
            return 0;
        }
        try {
            Map<String, String> opts = parseOptions(args);
            return switch (args[0]) {
                case "backtest" -> backtest(opts);
                case "walkforward" -> walkforward(opts);
                case "report" -> report(opts);
                default -> {
                    System.err.println("Unknown command: " + args[0]);
                    printUsage();
                    yield 1;
                }
            };
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            System.err.println("Failed: " + e);
            return 2;
        }
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    private static int backtest(Map<String, String> opts) throws Exception {
        BarSeries series = loadSeries(opts);
        TradingStrategy strategy = buildStrategy(opts);
        BacktestConfig config = BacktestConfig.defaults()
                .withInitialCapital(doubleOpt(opts, "capital", 100_000));

        BacktestResult result = Backtester.run(strategy, series, config);
        System.out.println(result);
        PerformanceMetrics m = result.metrics();
        System.out.printf(Locale.ROOT,
                "Final equity: %,.2f | annualized vol %.2f%% | trades %d%n",
                m.finalEquity(), m.annualizedVolatility() * 100, m.tradeCount());

        String out = opts.get("out");
        if (out != null) {
            new ReportGenerator("Backtest: " + strategy.name() + " on " + series.symbol())
                    .addStrategyPerformance(result)
                    .addEquityCurveChart("Equity Curve", result.equityCurve())
                    .addDrawdownChart("Drawdown", result.equityCurve())
                    .addTradeHistory(result.trades())
                    .addTechnicalSummary(series)
                    .toHtml(Path.of(out));
            System.out.println("Report written to " + out);
        }
        return 0;
    }

    private static int walkforward(Map<String, String> opts) throws Exception {
        BarSeries series = loadSeries(opts);
        int train = (int) doubleOpt(opts, "train", 252);
        int test = (int) doubleOpt(opts, "test", 63);
        double[] fast = parseList(opts.getOrDefault("fast", "5,10,20"));
        double[] slow = parseList(opts.getOrDefault("slow", "40,60"));

        WalkForwardAnalyzer.WalkForwardResult result = WalkForwardAnalyzer.analyze(
                series,
                new ParameterGrid().add("fast", fast).add("slow", slow),
                p -> new SmaCrossStrategy(p.get("fast").intValue(), p.get("slow").intValue()),
                BacktestConfig.defaults().withInitialCapital(doubleOpt(opts, "capital", 100_000)),
                train, test, PerformanceMetrics::sharpeRatio);

        for (WalkForwardAnalyzer.Fold fold : result.folds()) {
            System.out.printf(Locale.ROOT,
                    "fold train[%d,%d) test[%d,%d): params %s | IS %.2f OOS %.2f%n",
                    fold.trainFrom(), fold.trainTo(), fold.testFrom(), fold.testTo(),
                    fold.bestParams(), fold.inSampleObjective(), fold.outOfSampleObjective());
        }
        System.out.printf(Locale.ROOT,
                "OOS: return %.2f%%, sharpe %.2f, maxDD %.2f%% | walk-forward efficiency %.2f%n",
                result.outOfSampleMetrics().totalReturn() * 100,
                result.outOfSampleMetrics().sharpeRatio(),
                result.outOfSampleMetrics().maxDrawdown() * 100,
                result.efficiency());

        String out = opts.get("out");
        if (out != null) {
            new ReportGenerator("Walk-Forward: " + series.symbol())
                    .addPerformance("Out-of-Sample Performance", result.outOfSampleMetrics())
                    .addEquityCurveChart("Out-of-Sample Equity (stitched)", result.outOfSampleEquity())
                    .addDrawdownChart("Out-of-Sample Drawdown", result.outOfSampleEquity())
                    .toHtml(Path.of(out));
            System.out.println("Report written to " + out);
        }
        return 0;
    }

    private static int report(Map<String, String> opts) throws Exception {
        BarSeries series = loadSeries(opts);
        String out = required(opts, "out");
        new ReportGenerator("Market Report: " + series.symbol())
                .addTechnicalSummary(series)
                .addEquityCurveChart("Close Price", series.closes())
                .addDrawdownChart("Drawdown from Peak", series.closes())
                .toHtml(Path.of(out));
        System.out.println("Report written to " + out);
        return 0;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static BarSeries loadSeries(Map<String, String> opts) throws Exception {
        return CsvBarLoader.load(Path.of(required(opts, "csv")), required(opts, "symbol"));
    }

    private static TradingStrategy buildStrategy(Map<String, String> opts) {
        String name = opts.getOrDefault("strategy", "sma").toLowerCase(Locale.ROOT);
        return switch (name) {
            case "sma" -> new SmaCrossStrategy(
                    (int) doubleOpt(opts, "fast", 10), (int) doubleOpt(opts, "slow", 30));
            case "ema" -> new EmaCrossStrategy(
                    (int) doubleOpt(opts, "fast", 12), (int) doubleOpt(opts, "slow", 26));
            case "rsi" -> new RsiStrategy((int) doubleOpt(opts, "period", 14),
                    doubleOpt(opts, "oversold", 30), doubleOpt(opts, "overbought", 70));
            case "macd" -> new MacdStrategy();
            case "bollinger" -> new BollingerBandsStrategy(
                    (int) doubleOpt(opts, "period", 20), doubleOpt(opts, "k", 2.0));
            default -> throw new IllegalArgumentException(
                    "unknown strategy '" + name + "' (sma|ema|rsi|macd|bollinger)");
        };
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("expected --option, got '" + args[i] + "'");
            }
            String key = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                opts.put(key, args[++i]);
            } else {
                opts.put(key, "true");
            }
        }
        return opts;
    }

    private static String required(Map<String, String> opts, String key) {
        String value = opts.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing required option --" + key);
        }
        return value;
    }

    private static double doubleOpt(Map<String, String> opts, String key, double defaultValue) {
        String value = opts.get(key);
        return value == null ? defaultValue : Double.parseDouble(value);
    }

    private static double[] parseList(String csv) {
        String[] parts = csv.split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Double.parseDouble(parts[i].trim());
        }
        return out;
    }

    private static void printUsage() {
        System.out.println("""
                quantfinlib CLI

                Commands:
                  backtest    --csv <file> --symbol <sym> [--strategy sma|ema|rsi|macd|bollinger]
                              [--fast N --slow N | --period N ...] [--capital N] [--out report.html]
                  walkforward --csv <file> --symbol <sym> [--train 252] [--test 63]
                              [--fast 5,10,20] [--slow 40,60] [--out report.html]
                  report      --csv <file> --symbol <sym> --out report.html
                  help

                CSV format: header with date/timestamp, open, high, low, close[, volume].""");
    }
}
