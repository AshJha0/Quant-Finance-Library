package com.quantfinlib.examples;

import com.quantfinlib.backtest.BacktestConfig;
import com.quantfinlib.backtest.BacktestResult;
import com.quantfinlib.backtest.Backtester;
import com.quantfinlib.backtest.strategies.SmaCrossStrategy;
import com.quantfinlib.core.BarSeries;
import com.quantfinlib.dsl.Rules;
import com.quantfinlib.dsl.StrategyBuilder;
import com.quantfinlib.indicators.Indicators;
import com.quantfinlib.marketdata.MarketDataEvent;
import com.quantfinlib.marketdata.MarketDataProcessor;
import com.quantfinlib.ml.VolatilityForecaster;
import com.quantfinlib.optimization.PortfolioOptimizer;
import com.quantfinlib.report.ReportGenerator;
import com.quantfinlib.risk.CorrelationMatrix;
import com.quantfinlib.risk.Portfolio;
import com.quantfinlib.risk.PortfolioRiskAnalyzer;
import com.quantfinlib.risk.RiskMetricRegistry;
import com.quantfinlib.screener.FundamentalFilters;
import com.quantfinlib.screener.Fundamentals;
import com.quantfinlib.screener.RankingEngine;
import com.quantfinlib.screener.StockScreener;
import com.quantfinlib.screener.StockSnapshot;
import com.quantfinlib.screener.TechnicalFilters;
import com.quantfinlib.simulation.MonteCarloSimulator;
import com.quantfinlib.simulation.SimulationResult;
import com.quantfinlib.util.MathUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.SplittableRandom;

/**
 * End-to-end tour of the platform on synthetic data: indicators, backtesting,
 * the strategy DSL, risk analytics, ML forecasting, optimization, Monte Carlo,
 * screening, live market data, and report generation.
 *
 * <p>Run: {@code mvn compile exec:java} or
 * {@code java -cp target/classes com.quantfinlib.examples.QuickStartDemo}</p>
 */
public final class QuickStartDemo {

    public static void main(String[] args) throws Exception {
        // ---- Synthetic market data -----------------------------------
        String[] symbols = {"FX_EURUSD", "EQ_ALPHA", "EQ_BETA", "CMDTY_GOLD"};
        double[] drifts = {0.02, 0.12, 0.08, 0.05};
        double[] vols = {0.08, 0.25, 0.18, 0.15};
        BarSeries[] series = new BarSeries[symbols.length];
        for (int i = 0; i < symbols.length; i++) {
            series[i] = syntheticSeries(symbols[i], 756, 100 + 20 * i, drifts[i], vols[i], 11 + i);
        }
        BarSeries equity = series[1];

        // ---- 9. Technical indicators ---------------------------------
        double[] close = equity.closes();
        double[] rsi = Indicators.rsi(close, 14);
        System.out.printf("RSI(14) last = %.1f, ATR(14) last = %.3f%n",
                rsi[rsi.length - 1], Indicators.atr(equity, 14)[equity.size() - 1]);

        // ---- 6. Backtesting a built-in strategy ----------------------
        BacktestResult smaResult = Backtester.run(new SmaCrossStrategy(20, 50), equity,
                BacktestConfig.defaults());
        System.out.println(smaResult);

        // ---- 11. Strategy Builder DSL --------------------------------
        double[] fast = Indicators.ema(close, 12);
        double[] slow = Indicators.ema(close, 26);
        BacktestResult dslResult = StrategyBuilder.named("EMA momentum + risk stops")
                .enterWhen(Rules.crossAbove(fast, slow))
                .exitWhen(Rules.crossBelow(fast, slow))
                .withStopLoss(0.05)
                .withTakeProfit(0.15)
                .build()
                .backtest(equity, 100_000);
        System.out.println(dslResult);

        // ---- 1 & 5. Portfolio risk analysis + custom metrics ---------
        double[][] returns = new double[symbols.length][];
        for (int i = 0; i < symbols.length; i++) {
            returns[i] = series[i].returns();
        }
        double[] weights = {0.25, 0.25, 0.25, 0.25};
        PortfolioRiskAnalyzer analyzer = new PortfolioRiskAnalyzer(symbols, returns, weights);
        PortfolioRiskAnalyzer.RiskReport risk = analyzer.analyze(0.95, 252);
        System.out.printf("Portfolio ann. vol = %.2f%%, VaR95 = %.2f%%, CVaR95 = %.2f%%%n",
                risk.annualizedVolatility() * 100, risk.valueAtRisk() * 100,
                risk.conditionalValueAtRisk() * 100);
        System.out.println("Custom metrics: "
                + RiskMetricRegistry.withDefaults().calculateAll(analyzer.portfolioReturns()));

        // ---- 3. ML volatility forecasting ----------------------------
        VolatilityForecaster forecaster = VolatilityForecaster.weekly().fit(equity.returns());
        System.out.printf("ML vol forecast (next week, daily units) = %.4f, risk score = %.0f/100%n",
                forecaster.forecast(equity.returns()), forecaster.riskScore(equity.returns()));

        // ---- 4. Portfolio optimization -------------------------------
        double[] annualMeans = new double[symbols.length];
        double[][] dailyCov = CorrelationMatrix.covariance(returns);
        double[][] annualCov = new double[symbols.length][symbols.length];
        for (int i = 0; i < symbols.length; i++) {
            annualMeans[i] = MathUtils.mean(returns[i]) * 252;
            for (int j = 0; j < symbols.length; j++) {
                annualCov[i][j] = dailyCov[i][j] * 252;
            }
        }
        PortfolioOptimizer optimizer = new PortfolioOptimizer(annualMeans, annualCov);
        PortfolioOptimizer.Allocation maxSharpe = optimizer.maxSharpe(0.02);
        System.out.printf("Max-Sharpe: ret=%.2f%%, vol=%.2f%%, sharpe=%.2f%n",
                maxSharpe.expectedReturn() * 100, maxSharpe.volatility() * 100, maxSharpe.sharpe());

        // ---- 8. Monte Carlo simulation -------------------------------
        SimulationResult sim = new MonteCarloSimulator(7).simulatePortfolio(
                1_000_000, maxSharpe.weights(), dailyMeans(returns), dailyCov, 252, 50_000);
        System.out.println(sim);

        // ---- 7. Stock screener ---------------------------------------
        List<StockSnapshot> universe = List.of(
                new StockSnapshot(symbols[1], series[1],
                        new Fundamentals(50e9, 18, 3.2, 6.1, 0.22, 0.012, 0.6)),
                new StockSnapshot(symbols[2], series[2],
                        new Fundamentals(12e9, 34, 6.0, 2.2, 0.09, 0.0, 1.9)),
                new StockSnapshot(symbols[3], series[3],
                        new Fundamentals(8e9, 11, 1.1, 4.0, 0.15, 0.03, 0.4)));
        StockScreener screener = new StockScreener(universe);
        var matches = screener.screen(
                FundamentalFilters.marketCapAbove(5e9),
                TechnicalFilters.rsiAbove(14, 20));
        var ranked = new RankingEngine()
                .addCriterion("ROE", 1.0, s -> s.fundamentals().roe())
                .addCriterion("P/E (lower better)", -0.5, s -> s.fundamentals().peRatio())
                .rank(matches);
        System.out.println("Screener top pick: " + ranked.getFirst().stock().symbol());

        // ---- 2. Real-time market data --------------------------------
        Portfolio portfolio = new Portfolio()
                .addPosition(symbols[1], 100, series[1].lastClose())
                .addPosition(symbols[3], 50, series[3].lastClose());
        try (MarketDataProcessor mdp = new MarketDataProcessor()) {
            mdp.monitor(portfolio);
            mdp.start();
            mdp.publish(new MarketDataEvent(symbols[1], series[1].lastClose() * 1.01, 500, System.nanoTime()));
            mdp.publish(new MarketDataEvent(symbols[3], series[3].lastClose() * 0.99, 200, System.nanoTime()));
            Thread.sleep(100);
            System.out.printf("Live-marked portfolio value = %,.2f (%d ticks processed)%n",
                    portfolio.totalValue(), mdp.processedCount());
        }

        // ---- 10. Report generation -----------------------------------
        Path reports = Path.of("target", "reports");
        new ReportGenerator("quantfinlib Portfolio Review")
                .addPortfolioSummary(portfolio)
                .addStrategyPerformance(dslResult)
                .addRiskAnalysis(risk)
                .addAllocation(java.util.Map.of(
                        symbols[0], maxSharpe.weights()[0], symbols[1], maxSharpe.weights()[1],
                        symbols[2], maxSharpe.weights()[2], symbols[3], maxSharpe.weights()[3]))
                .addMonteCarlo(sim)
                .addTradeHistory(dslResult.trades())
                .addTechnicalSummary(equity)
                .toHtml(reports.resolve("portfolio-review.html"));
        new ReportGenerator("quantfinlib Portfolio Review")
                .addStrategyPerformance(dslResult)
                .addMonteCarlo(sim)
                .toPdf(reports.resolve("portfolio-review.pdf"));
        new ReportGenerator("quantfinlib Portfolio Review")
                .addStrategyPerformance(dslResult)
                .addMonteCarlo(sim)
                .toExcel(reports.resolve("portfolio-review.xlsx"));
        System.out.println("Reports written to " + reports.toAbsolutePath());
    }

    private static double[] dailyMeans(double[][] returns) {
        double[] m = new double[returns.length];
        for (int i = 0; i < returns.length; i++) {
            m[i] = MathUtils.mean(returns[i]);
        }
        return m;
    }

    /** Deterministic synthetic GBM daily series with realistic OHLC structure. */
    public static BarSeries syntheticSeries(String symbol, int days, double startPrice,
                                            double annualDrift, double annualVol, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        double dt = 1.0 / 252;
        double price = startPrice;
        BarSeries.Builder b = BarSeries.builder(symbol);
        for (int d = 0; d < days; d++) {
            double z = rnd.nextGaussian();
            double next = price * Math.exp((annualDrift - 0.5 * annualVol * annualVol) * dt
                    + annualVol * Math.sqrt(dt) * z);
            double high = Math.max(price, next) * (1 + rnd.nextDouble() * 0.005);
            double low = Math.min(price, next) * (1 - rnd.nextDouble() * 0.005);
            double volume = 1_000_000 * (0.5 + rnd.nextDouble());
            b.add(d * 86_400_000L, price, high, low, next, volume);
            price = next;
        }
        return b.build();
    }
}
