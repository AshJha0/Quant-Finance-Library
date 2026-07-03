package com.fdequant.report;

import com.fdequant.backtest.BacktestResult;
import com.fdequant.backtest.PerformanceMetrics;
import com.fdequant.backtest.Trade;
import com.fdequant.core.BarSeries;
import com.fdequant.indicators.Indicators;
import com.fdequant.risk.Portfolio;
import com.fdequant.risk.PortfolioRiskAnalyzer;
import com.fdequant.simulation.SimulationResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Professional Report Generator: assembles portfolio summaries, performance
 * analytics, risk analysis, allocations, strategy results, trade history,
 * Monte Carlo results and technical summaries into a {@link Report}, and
 * exports to PDF, Excel (.xlsx), HTML or CSV.
 */
public final class ReportGenerator {

    private final Report.Builder builder;

    public ReportGenerator(String title) {
        this.builder = Report.builder(title);
    }

    public ReportGenerator addPortfolioSummary(Portfolio portfolio) {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("Total value", money(portfolio.totalValue()));
        kv.put("Positions", String.valueOf(portfolio.size()));
        portfolio.positions().forEach(p ->
                kv.put(p.symbol(), String.format(Locale.ROOT, "%.4f @ %s = %s",
                        p.quantity(), money(p.price()), money(p.value()))));
        builder.addKeyValueSection("Portfolio Summary", kv);
        return this;
    }

    public ReportGenerator addPerformance(String title, PerformanceMetrics m) {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("Total return", pct(m.totalReturn()));
        kv.put("CAGR", pct(m.cagr()));
        kv.put("Annualized return", pct(m.annualizedReturn()));
        kv.put("Annualized volatility", pct(m.annualizedVolatility()));
        kv.put("Sharpe ratio", num(m.sharpeRatio()));
        kv.put("Sortino ratio", num(m.sortinoRatio()));
        kv.put("Calmar ratio", num(m.calmarRatio()));
        kv.put("Max drawdown", pct(m.maxDrawdown()));
        kv.put("Profit factor", num(m.profitFactor()));
        kv.put("Win rate", pct(m.winRate()));
        kv.put("Trades", String.valueOf(m.tradeCount()));
        kv.put("Final equity", money(m.finalEquity()));
        builder.addKeyValueSection(title, kv);
        return this;
    }

    public ReportGenerator addStrategyPerformance(BacktestResult result) {
        return addPerformance("Strategy Performance: " + result.strategyName()
                + " on " + result.symbol(), result.metrics());
    }

    public ReportGenerator addRiskAnalysis(PortfolioRiskAnalyzer.RiskReport risk) {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("Annualized volatility", pct(risk.annualizedVolatility()));
        kv.put("VaR (95%, historical)", pct(risk.valueAtRisk()));
        kv.put("CVaR (95%)", pct(risk.conditionalValueAtRisk()));
        kv.put("VaR (95%, parametric)", pct(risk.parametricVaR()));
        kv.put("Sharpe ratio", num(risk.sharpeRatio()));
        builder.addKeyValueSection("Risk Analysis", kv);

        List<List<String>> rows = new ArrayList<>();
        risk.exposures().forEach((symbol, weight) -> rows.add(List.of(
                symbol, pct(weight),
                pct(risk.assetVolatilities().getOrDefault(symbol, Double.NaN)),
                pct(risk.assetVaR().getOrDefault(symbol, Double.NaN)),
                pct(risk.riskContributions().getOrDefault(symbol, Double.NaN)))));
        builder.addTableSection("Risk Decomposition",
                List.of("Asset", "Weight", "Ann. Volatility", "VaR(95%)", "Risk Contribution"), rows);
        return this;
    }

    public ReportGenerator addAllocation(Map<String, Double> weights) {
        Map<String, String> kv = new LinkedHashMap<>();
        weights.forEach((k, v) -> kv.put(k, pct(v)));
        builder.addKeyValueSection("Asset Allocation", kv);
        return this;
    }

    public ReportGenerator addTradeHistory(List<Trade> trades) {
        List<List<String>> rows = new ArrayList<>();
        for (Trade t : trades) {
            rows.add(List.of(t.symbol(), String.valueOf(t.entryIndex()), String.valueOf(t.exitIndex()),
                    num(t.entryPrice()), num(t.exitPrice()), num(t.quantity()),
                    money(t.pnl()), pct(t.returnPct()), t.exitReason()));
        }
        builder.addTableSection("Trade History",
                List.of("Symbol", "Entry Bar", "Exit Bar", "Entry Px", "Exit Px",
                        "Qty", "PnL", "Return", "Exit Reason"), rows);
        return this;
    }

    public ReportGenerator addMonteCarlo(SimulationResult sim) {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("Simulations", String.valueOf(sim.simulations()));
        kv.put("Initial value", money(sim.initialValue()));
        kv.put("Expected value", money(sim.expectedValue()));
        kv.put("Median outcome", money(sim.medianValue()));
        kv.put("Probability of profit", pct(sim.probabilityOfProfit()));
        kv.put("Probability of loss", pct(sim.probabilityOfLoss()));
        kv.put("VaR (95%)", pct(sim.valueAtRisk(0.95)));
        kv.put("CVaR (95%)", pct(sim.conditionalValueAtRisk(0.95)));
        double[] ci = sim.confidenceInterval(0.90);
        kv.put("90% confidence interval", money(ci[0]) + " - " + money(ci[1]));
        kv.put("Best case", money(sim.bestCase()));
        kv.put("Worst case", money(sim.worstCase()));
        builder.addKeyValueSection("Monte Carlo Simulation", kv);
        return this;
    }

    /** Snapshot of key technical indicators on the last bar of the series. */
    public ReportGenerator addTechnicalSummary(BarSeries s) {
        int i = s.size() - 1;
        double[] close = s.closes();
        Indicators.Macd macd = Indicators.macd(close, 12, 26, 9);
        Indicators.Bollinger boll = Indicators.bollinger(close, 20, 2);
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("Last close", num(close[i]));
        kv.put("RSI(14)", num(Indicators.rsi(close, 14)[i]));
        kv.put("SMA(50)", num(Indicators.sma(close, Math.min(50, s.size()))[i]));
        kv.put("EMA(20)", num(Indicators.ema(close, Math.min(20, s.size()))[i]));
        kv.put("MACD", num(macd.line()[i]));
        kv.put("MACD signal", num(macd.signal()[i]));
        kv.put("Bollinger upper", num(boll.upper()[i]));
        kv.put("Bollinger lower", num(boll.lower()[i]));
        kv.put("ATR(14)", num(Indicators.atr(s, 14)[i]));
        builder.addKeyValueSection("Technical Analysis Summary: " + s.symbol(), kv);
        return this;
    }

    public ReportGenerator addSection(String title, Map<String, String> keyValues) {
        builder.addKeyValueSection(title, keyValues);
        return this;
    }

    public Report build() {
        return builder.build();
    }

    public void toHtml(Path path) throws IOException {
        new HtmlReportExporter().export(build(), path);
    }

    public void toCsv(Path path) throws IOException {
        new CsvReportExporter().export(build(), path);
    }

    public void toPdf(Path path) throws IOException {
        new PdfReportExporter().export(build(), path);
    }

    public void toExcel(Path path) throws IOException {
        new XlsxReportExporter().export(build(), path);
    }

    // ------------------------------------------------------------------

    private static String pct(double v) {
        return Double.isNaN(v) ? "n/a" : String.format(Locale.ROOT, "%.2f%%", v * 100);
    }

    private static String num(double v) {
        if (Double.isNaN(v)) {
            return "n/a";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "inf" : "-inf";
        }
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static String money(double v) {
        return String.format(Locale.ROOT, "%,.2f", v);
    }
}
