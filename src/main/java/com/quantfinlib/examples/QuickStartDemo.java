package com.quantfinlib.examples;

import com.quantfinlib.backtest.BacktestConfig;
import com.quantfinlib.backtest.BacktestResult;
import com.quantfinlib.backtest.Backtester;
import com.quantfinlib.backtest.ExecutionAwareBacktester;
import com.quantfinlib.backtest.ExecutionAwareResult;
import com.quantfinlib.backtest.IcebergExecution;
import com.quantfinlib.backtest.ParentOrder;
import com.quantfinlib.backtest.PerformanceMetrics;
import com.quantfinlib.backtest.SorExecution;
import com.quantfinlib.backtest.portfolio.PortfolioBacktester;
import com.quantfinlib.backtest.portfolio.PortfolioStrategy;
import com.quantfinlib.backtest.portfolio.PositionSizing;
import com.quantfinlib.backtest.strategies.SmaCrossStrategy;
import com.quantfinlib.backtest.validation.ParameterGrid;
import com.quantfinlib.backtest.validation.WalkForwardAnalyzer;
import com.quantfinlib.core.BarSeries;
import com.quantfinlib.data.CsvBarLoader;
import com.quantfinlib.dsl.Rules;
import com.quantfinlib.dsl.StrategyBuilder;
import com.quantfinlib.execution.SmartOrderRouter;
import com.quantfinlib.execution.VenueQuote;
import com.quantfinlib.hedging.CointegrationTest;
import com.quantfinlib.hedging.DeltaHedger;
import com.quantfinlib.hedging.HedgingErrorDistribution;
import com.quantfinlib.hedging.HedgingSimulator;
import com.quantfinlib.indicators.Indicators;
import com.quantfinlib.indicators.StreamingIndicators;
import com.quantfinlib.marketdata.MarketDataEvent;
import com.quantfinlib.marketdata.MarketDataProcessor;
import com.quantfinlib.ml.VolatilityForecaster;
import com.quantfinlib.optimization.PortfolioOptimizer;
import com.quantfinlib.orderbook.BookAnalytics;
import com.quantfinlib.orderbook.OrderBook;
import com.quantfinlib.orderbook.Side;
import com.quantfinlib.pricing.BinomialTree;
import com.quantfinlib.pricing.BlackScholes;
import com.quantfinlib.pricing.SabrModel;
import com.quantfinlib.pricing.VolSurface;
import com.quantfinlib.rates.BondPricer;
import com.quantfinlib.rates.YieldCurve;
import com.quantfinlib.report.ReportGenerator;
import com.quantfinlib.risk.CorrelationMatrix;
import com.quantfinlib.risk.Portfolio;
import com.quantfinlib.risk.PortfolioRiskAnalyzer;
import com.quantfinlib.risk.PreTradeLimitChecker;
import com.quantfinlib.risk.RiskMetricRegistry;
import com.quantfinlib.screener.FundamentalFilters;
import com.quantfinlib.screener.Fundamentals;
import com.quantfinlib.screener.RankingEngine;
import com.quantfinlib.screener.StockScreener;
import com.quantfinlib.screener.StockSnapshot;
import com.quantfinlib.screener.TechnicalFilters;
import com.quantfinlib.simulation.MonteCarloSimulator;
import com.quantfinlib.simulation.SimulationResult;
import com.quantfinlib.trading.PaperTradingGateway;
import com.quantfinlib.util.MathUtils;
import com.quantfinlib.volatility.EwmaVolatility;
import com.quantfinlib.volatility.Garch11;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * End-to-end tour of the platform on synthetic data: the 11 research
 * capabilities (indicators, backtesting, DSL, risk, ML, optimization, Monte
 * Carlo, screening, market data, reporting with SVG charts) plus the trading
 * and research extensions — data I/O, order book analytics, smart order
 * routing, execution-aware backtests with TCA, options hedging and vol
 * surfaces, fixed income, GARCH/EWMA, cointegration, walk-forward validation,
 * portfolio backtesting, and a risk-gated paper trading session.
 *
 * <p>Run: {@code java -cp target/classes com.quantfinlib.examples.QuickStartDemo}</p>
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

        // ================ Trading & research extensions ================

        // ---- 12. Data I/O: CSV round trip -----------------------------
        Path dataDir = Path.of("target", "data");
        CsvBarLoader.save(equity, dataDir.resolve("eq_alpha.csv"));
        BarSeries reloaded = CsvBarLoader.load(dataDir.resolve("eq_alpha.csv"), equity.symbol());
        System.out.printf("%nCSV round trip: %d bars reloaded, last close %.2f%n",
                reloaded.size(), reloaded.lastClose());

        // ---- 13. Order book analytics & smart order routing -----------
        OrderBook book = new OrderBook(symbols[1]);
        book.submitLimit(Side.BUY, 99.9, 5_000, 1);
        book.submitLimit(Side.SELL, 100.1, 3_000, 2);
        book.submitLimit(Side.SELL, 100.3, 4_000, 3);
        BookAnalytics.SweepResult sweepCost = BookAnalytics.sweep(book, Side.BUY, 5_000);
        System.out.printf("Book sweep 5k: avg %.3f, impact %.1f bps over %d levels%n",
                sweepCost.avgPrice(), sweepCost.impactBps(), sweepCost.levelsConsumed());
        SmartOrderRouter.RoutingPlan plan = SmartOrderRouter.route(Side.BUY, 900, List.of(
                new VenueQuote("LIT_A", 99.98, 400, 100.02, 400, 0, 0, false),
                new VenueQuote("LIT_B", 99.97, 600, 100.00, 300, 1.0, 0, false),
                new VenueQuote("DARK_X", 99.98, 500, 100.02, 500, 0.2, 0, true)), true);
        System.out.printf("SOR: %d legs, avg all-in %.4f, first venue %s%n",
                plan.legs().size(), plan.expectedAvgAllInPrice(), plan.legs().getFirst().venue());

        // ---- 14. Execution-aware backtest + TCA ------------------------
        ExecutionAwareResult execAware = ExecutionAwareBacktester.run(
                new SmaCrossStrategy(20, 50), equity, BacktestConfig.defaults(),
                new IcebergExecution(new SorExecution(List.of(
                        new SorExecution.VenueConfig("LIT_A", 1.0, 0.05, false),
                        new SorExecution.VenueConfig("DARK_X", 0.2, 0.03, true)),
                        5, true), 200));
        for (ParentOrder parent : execAware.parentOrders()) {
            if (!parent.fills().isEmpty()) {
                System.out.printf("Execution-aware: %d parent orders; first entry shortfall "
                                + "%.2f bps over %d bars%n",
                        execAware.parentOrders().size(),
                        execAware.tca(parent).implementationShortfallBps(),
                        parent.fillDurationBars());
                break;
            }
        }

        // ---- 15. Hedging: Greeks, MC hedging error, vol surface --------
        BlackScholes.Greeks greeks = BlackScholes.greeks(
                BlackScholes.OptionType.CALL, 100, 100, 0.03, 0, 0.2, 0.5);
        HedgingErrorDistribution hedgeDist = new HedgingSimulator(7).simulate(
                BlackScholes.OptionType.CALL, 100, 100, 0.5, 0.03, 0, 0.22, 0.18,
                126, 2_000, DeltaHedger.Config.every(1));
        System.out.printf("ATM call delta=%.3f gamma=%.4f | MC delta hedging (sold 22v, "
                        + "realized 18v): mean pnl %.3f, VaR95 %.3f%n",
                greeks.delta(), greeks.gamma(), hedgeDist.mean(), hedgeDist.valueAtRisk(0.95));
        VolSurface surface = VolSurface.builder()
                .add(0.25, 90, 0.25).add(0.25, 100, 0.20)
                .add(1.0, 90, 0.28).add(1.0, 100, 0.24).build();
        SabrModel.Params sabr = SabrModel.calibrate(100, 1.0, 1.0,
                new double[]{80, 90, 100, 110, 120},
                new double[]{surface.vol(1, 80), surface.vol(1, 90), surface.vol(1, 100),
                        surface.vol(1, 110), surface.vol(1, 120)});
        System.out.printf("Vol surface 6m@95 = %.4f | SABR fit rmse %.5f rho %.2f | "
                        + "American put %.3f vs European %.3f%n",
                surface.vol(0.5, 95), sabr.rmse(), sabr.rho(),
                BinomialTree.price(BlackScholes.OptionType.PUT,
                        BinomialTree.ExerciseStyle.AMERICAN, 90, 100, 0.05, 0, 0.2, 1, 300),
                BinomialTree.price(BlackScholes.OptionType.PUT,
                        BinomialTree.ExerciseStyle.EUROPEAN, 90, 100, 0.05, 0, 0.2, 1, 300));

        // ---- 16. Fixed income ------------------------------------------
        YieldCurve curve = YieldCurve.bootstrapAnnualParSwaps(
                new int[]{1, 2, 3, 5}, new double[]{0.03, 0.034, 0.037, 0.04});
        System.out.printf("Bootstrapped 5y zero %.3f%% | 10y 5%% bond @4.5%%: price %.2f, "
                        + "mod duration %.2f, DV01 %.4f%n",
                curve.zeroRate(5) * 100,
                BondPricer.priceFromYield(100, 0.05, 2, 10, 0.045),
                BondPricer.modifiedDuration(100, 0.05, 2, 10, 0.045),
                BondPricer.dv01(100, 0.05, 2, 10, 0.045));

        // ---- 17. Volatility models --------------------------------------
        Garch11.Params garch = Garch11.fit(equity.returns());
        System.out.printf("GARCH(1,1): alpha=%.3f beta=%.3f persistence=%.3f | EWMA vol %.4f%n",
                garch.alpha(), garch.beta(), garch.persistence(),
                EwmaVolatility.riskMetrics().latestVol(equity.returns()));

        // ---- 18. Cointegration gate for pairs trades --------------------
        CointegrationTest.EngleGrangerResult eg = CointegrationTest.engleGranger(
                series[2].closes(), series[1].closes());
        System.out.printf("Engle-Granger %s vs %s: adf t=%.2f, cointegrated@5%%=%b%n",
                symbols[2], symbols[1], eg.adfTStatistic(), eg.cointegrated5pct());

        // ---- 19. Walk-forward validation --------------------------------
        WalkForwardAnalyzer.WalkForwardResult wf = WalkForwardAnalyzer.analyze(
                equity,
                new ParameterGrid().add("fast", 10, 20).add("slow", 40, 60),
                p -> new SmaCrossStrategy(p.get("fast").intValue(), p.get("slow").intValue()),
                BacktestConfig.defaults(), 252, 126, PerformanceMetrics::sharpeRatio);
        System.out.printf("Walk-forward: %d folds, OOS return %.1f%%, efficiency %.2f%n",
                wf.folds().size(), wf.outOfSampleMetrics().totalReturn() * 100, wf.efficiency());

        // ---- 20. Portfolio backtest (inverse-vol weights) ----------------
        double[] assetVols = new double[3];
        for (int i = 1; i < 4; i++) {
            assetVols[i - 1] = MathUtils.stdDev(series[i].returns());
        }
        double[] ivw = PositionSizing.inverseVolatilityWeights(assetVols);
        Map<String, Double> targetWeights = Map.of(
                symbols[1], ivw[0], symbols[2], ivw[1], symbols[3], ivw[2]);
        PortfolioBacktester.Result pbt = PortfolioBacktester.run(new PortfolioStrategy() {
            @Override
            public String name() {
                return "INVERSE_VOL";
            }

            @Override
            public void init(Map<String, BarSeries> data) {
            }

            @Override
            public Map<String, Double> targetWeights(int index) {
                return targetWeights;
            }
        }, Map.of(symbols[1], series[1], symbols[2], series[2], symbols[3], series[3]),
                PortfolioBacktester.Config.defaults().withRebalanceEvery(21));
        System.out.printf("Portfolio backtest (inverse-vol): return %.1f%%, sharpe %.2f, "
                        + "costs %,.0f%n",
                pbt.metrics().totalReturn() * 100, pbt.metrics().sharpeRatio(), pbt.totalCosts());

        // ---- 21. Paper trading session -----------------------------------
        PaperTradingGateway gateway = new PaperTradingGateway(1_000_000, 0.0005,
                new PreTradeLimitChecker().maxOrderQuantity(50_000));
        StreamingIndicators.Ema fastEma = new StreamingIndicators.Ema(12);
        StreamingIndicators.Ema slowEma = new StreamingIndicators.Ema(26);
        boolean inPosition = false;
        for (int i = 0; i < equity.size(); i++) {
            double mid = equity.close(i);
            gateway.onQuote(symbols[1], mid - 0.02, mid + 0.02);
            double f = fastEma.update(mid);
            double s = slowEma.update(mid);
            if (Double.isNaN(s)) {
                continue;
            }
            if (f > s && !inPosition) {
                gateway.submitMarket(symbols[1], Side.BUY, 1_000);
                inPosition = true;
            } else if (f < s && inPosition) {
                gateway.submitMarket(symbols[1], Side.SELL, 1_000);
                inPosition = false;
            }
        }
        System.out.printf("Paper trading (EMA cross via risk-gated gateway): equity %,.2f, "
                        + "realized PnL %,.2f, rejections %d%n",
                gateway.equity(), gateway.realizedPnl(), gateway.rejectionLog().size());

        // ---- 10. Report generation (now with SVG charts) ---------------
        Path reports = Path.of("target", "reports");
        new ReportGenerator("quantfinlib Portfolio Review")
                .addPortfolioSummary(portfolio)
                .addStrategyPerformance(dslResult)
                .addEquityCurveChart("Equity Curve: " + dslResult.strategyName(),
                        dslResult.equityCurve())
                .addDrawdownChart("Drawdown", dslResult.equityCurve())
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
