package com.quantfinlib.backtest.portfolio;

import com.quantfinlib.backtest.FillPolicy;
import com.quantfinlib.backtest.PerformanceAnalytics;
import com.quantfinlib.backtest.PerformanceMetrics;
import com.quantfinlib.backtest.TradeCostModel;
import com.quantfinlib.core.BarSeries;
import com.quantfinlib.data.CorporateActions;
import com.quantfinlib.data.PointInTimeUniverse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Multi-asset, long/short portfolio backtester: rebalances positions (possibly
 * fractional and negative) toward the strategy's target weights at a
 * configurable cadence, charging commission on traded notional. This is where
 * the {@code optimization} package meets the backtester — feed optimizer
 * weights, vol-target overlays, or momentum rankings straight in.
 *
 * <p>The {@linkplain #run(PortfolioStrategy, Map, Config, PointInTimeUniverse,
 * Map) survivorship-aware overload} additionally consumes a
 * {@link PointInTimeUniverse} and per-symbol cash dividends, closing the
 * engine-side gaps that make naive backtests survivorship-biased:</p>
 * <ul>
 *   <li><b>Delistings</b> terminate positions at
 *       {@code lastClose × (1 + delistingReturn)} on the event bar — a
 *       bankruptcy really costs −100%, instead of a forward-filled flat line;</li>
 *   <li><b>Mergers</b> convert positions to cash and/or acquirer shares at
 *       the recorded deal terms;</li>
 *   <li><b>Index drops</b> (membership ends, security lives on) force a sale
 *       at that bar's close — non-members are untradeable thereafter;</li>
 *   <li><b>Cash dividends</b> credit {@code position × amount} on the
 *       ex-date (shorts pay), so unadjusted price series carry the full
 *       total return and cash drag is real.</li>
 * </ul>
 *
 * <p>Feed this overload <b>unadjusted</b> prices (dividends are cash here;
 * adjusted prices would double-count them) aligned via
 * {@code SeriesAligner.unionForwardFill}; the forward-filled ghost bars of a
 * dead symbol are harmless because its position terminates at the event.
 * Signals, however, must not be computed on unadjusted prices: a 2:1 split
 * reads as a −50% return. The
 * {@linkplain #run(PortfolioStrategy, Map, Map, Config, PointInTimeUniverse, Map)
 * price-pair overload} hands the strategy an adjusted copy for signals
 * ({@link PortfolioStrategy#init(Map, Map)}) while the engine trades,
 * values and pays dividends on the unadjusted series. The engine cannot
 * remove the bias in the <em>data</em>: the universe and delisting returns
 * must come from a point-in-time dataset that includes dead tickers.</p>
 *
 * <p><b>Fill timing.</b> Target weights are decided at a bar's close and
 * filled according to {@link Config#fillPolicy()}: at the next bar's open
 * (default), the next bar's close, or the decision bar's own close
 * ({@code CLOSE}, the optimistic zero-latency mode). Next-open orders are
 * sized against the portfolio value marked at that open. Lifecycle events
 * and dividends of the fill bar are processed first; dead and non-member
 * names are not traded. A decision on the last bar under a next-bar policy
 * is dropped.</p>
 *
 * <p><b>Financing</b> ({@link Config#withFinancing}) accrues each bar on
 * the book held over it: interest on positive cash (short proceeds sit in
 * cash, so the short rebate is implicit), a margin rate on negative cash,
 * and a borrow fee on short notional, per symbol or by default. Without it
 * shorts, cash and leverage are free and a 150/50 book shows the same
 * equity as an unlevered one.</p>
 */
public final class PortfolioBacktester {

    /**
     * Financing terms, all annual.
     *
     * @param cashRate          rate earned on positive cash (0.03 = 3%)
     * @param marginRate        rate paid on negative cash (leverage)
     * @param defaultBorrowBps  borrow fee on short notional, bps per year
     * @param borrowBpsBySymbol per-symbol borrow fee overrides (hard-to-borrow names)
     */
    public record Financing(double cashRate, double marginRate, double defaultBorrowBps,
                            Map<String, Double> borrowBpsBySymbol) {

        public Financing {
            if (!Double.isFinite(cashRate) || marginRate < 0 || defaultBorrowBps < 0) {
                throw new IllegalArgumentException("invalid financing terms");
            }
            borrowBpsBySymbol = Map.copyOf(borrowBpsBySymbol);
        }

        /** No financing: cash, leverage and shorts are free. */
        public static Financing none() {
            return new Financing(0, 0, 0, Map.of());
        }

        double borrowBps(String symbol) {
            return borrowBpsBySymbol.getOrDefault(symbol, defaultBorrowBps);
        }

        boolean isNone() {
            return cashRate == 0 && marginRate == 0 && defaultBorrowBps == 0
                    && borrowBpsBySymbol.isEmpty();
        }
    }

    /**
     * {@code costModel}, when set, supersedes the flat {@code commissionRate}
     * for every trade (rebalances and forced index-drop sales alike), the
     * shared {@link TradeCostModel} seam that makes a run survivorship-aware
     * and execution-aware at once. Null keeps the flat commission.
     *
     * @param fillPolicy when a decision fills (default {@code NEXT_OPEN})
     * @param financing  cash, margin and borrow rates (default none)
     */
    public record Config(double initialCapital, double commissionRate,
                         int rebalanceEveryBars, int periodsPerYear,
                         TradeCostModel costModel, FillPolicy fillPolicy, Financing financing) {

        public Config {
            if (fillPolicy == null || financing == null || rebalanceEveryBars < 1
                    || periodsPerYear < 1 || commissionRate < 0) {
                throw new IllegalArgumentException("invalid portfolio backtest config");
            }
        }

        /** Next-open fills, no financing. */
        public Config(double initialCapital, double commissionRate, int rebalanceEveryBars,
                      int periodsPerYear, TradeCostModel costModel) {
            this(initialCapital, commissionRate, rebalanceEveryBars, periodsPerYear, costModel,
                    FillPolicy.NEXT_OPEN, Financing.none());
        }

        public static Config defaults() {
            return new Config(1_000_000, 0.001, 1, 252, null);
        }

        public Config withRebalanceEvery(int bars) {
            return new Config(initialCapital, commissionRate, bars, periodsPerYear, costModel,
                    fillPolicy, financing);
        }

        /** Pluggable per-trade costs (e.g. {@code TradeCostModel.institutional}). */
        public Config withCostModel(TradeCostModel model) {
            return new Config(initialCapital, commissionRate, rebalanceEveryBars,
                    periodsPerYear, model, fillPolicy, financing);
        }

        public Config withFillPolicy(FillPolicy policy) {
            return new Config(initialCapital, commissionRate, rebalanceEveryBars,
                    periodsPerYear, costModel, policy, financing);
        }

        public Config withFinancing(Financing terms) {
            return new Config(initialCapital, commissionRate, rebalanceEveryBars,
                    periodsPerYear, costModel, fillPolicy, terms);
        }

        /** One-way trade fee for {@code notional} of {@code series} at bar {@code index}. */
        double fee(com.quantfinlib.core.BarSeries series, int index, double notional) {
            return costModel != null
                    ? notional * costModel.costBps(series, index, notional) / 1e4
                    : notional * commissionRate;
        }
    }

    /**
     * {@code dividendCashCredited} and {@code lifecycleEventsApplied} are
     * populated by the survivorship-aware overload (zero otherwise).
     */
    public static final class Result {
        private final double[] equityCurve;
        private final PerformanceMetrics metrics;
        private final double totalCosts;
        private final double totalTurnoverNotional;
        private final Map<String, Double> finalPositions;
        private final double dividendCashCredited;
        private final int lifecycleEventsApplied;
        private final double financingCost;

        /**
         * Creates the value; array and collection arguments are copied.
         */
        public Result(double[] equityCurve, PerformanceMetrics metrics, double totalCosts,
                double totalTurnoverNotional, Map<String, Double> finalPositions,
                double dividendCashCredited, int lifecycleEventsApplied, double financingCost) {
            this.equityCurve = equityCurve == null ? null : equityCurve.clone();
            this.metrics = metrics;
            this.totalCosts = totalCosts;
            this.totalTurnoverNotional = totalTurnoverNotional;
            this.finalPositions = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(finalPositions));
            this.dividendCashCredited = dividendCashCredited;
            this.lifecycleEventsApplied = lifecycleEventsApplied;
            this.financingCost = financingCost;
        }

        /** Element {@code i} of {@code equityCurve}. */
        public double equityCurve(int i) {
            return equityCurve[i];
        }

        /** Length of {@code equityCurve}. */
        public int equityCurveLength() {
            return equityCurve.length;
        }

        /** A fresh copy of {@code equityCurve}. */
        public double[] equityCurveCopy() {
            return equityCurve == null ? null : equityCurve.clone();
        }

        /** {@code metrics}. */
        public PerformanceMetrics metrics() {
            return metrics;
        }

        /** {@code totalCosts}. */
        public double totalCosts() {
            return totalCosts;
        }

        /** {@code totalTurnoverNotional}. */
        public double totalTurnoverNotional() {
            return totalTurnoverNotional;
        }

        /** {@code finalPositions}. */
        public Map<String, Double> finalPositions() {
            return finalPositions;
        }

        /** {@code dividendCashCredited}. */
        public double dividendCashCredited() {
            return dividendCashCredited;
        }

        /** {@code lifecycleEventsApplied}. */
        public int lifecycleEventsApplied() {
            return lifecycleEventsApplied;
        }

        /** {@code financingCost}. */
        public double financingCost() {
            return financingCost;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Result o)) {
                return false;
            }
            return java.util.Arrays.equals(equityCurve, o.equityCurve)
                    && java.util.Objects.equals(metrics, o.metrics)
                    && Double.compare(totalCosts, o.totalCosts) == 0
                    && Double.compare(totalTurnoverNotional, o.totalTurnoverNotional) == 0
                    && java.util.Objects.equals(finalPositions, o.finalPositions)
                    && Double.compare(dividendCashCredited, o.dividendCashCredited) == 0
                    && lifecycleEventsApplied == o.lifecycleEventsApplied
                    && Double.compare(financingCost, o.financingCost) == 0;
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Arrays.hashCode(equityCurve);
            h = 31 * h + java.util.Objects.hashCode(metrics);
            h = 31 * h + Double.hashCode(totalCosts);
            h = 31 * h + Double.hashCode(totalTurnoverNotional);
            h = 31 * h + java.util.Objects.hashCode(finalPositions);
            h = 31 * h + Double.hashCode(dividendCashCredited);
            h = 31 * h + Integer.hashCode(lifecycleEventsApplied);
            h = 31 * h + Double.hashCode(financingCost);
            return h;
        }

        @Override
        public String toString() {
            return "Result[" + "equityCurve=" + java.util.Arrays.toString(equityCurve) + ", " + "metrics=" + metrics + ", " + "totalCosts=" + totalCosts + ", " + "totalTurnoverNotional=" + totalTurnoverNotional + ", " + "finalPositions=" + finalPositions + ", " + "dividendCashCredited=" + dividendCashCredited + ", " + "lifecycleEventsApplied=" + lifecycleEventsApplied + ", " + "financingCost=" + financingCost + "]";
        }
    }

    /** Same-bar processing order for terminal events (see the run loop). */
    private static final PointInTimeUniverse.EventType[] TERMINAL_ORDER = {
            PointInTimeUniverse.EventType.MERGER, PointInTimeUniverse.EventType.DELISTING};

    /** A symbol's date-sorted dividends plus the replay cursor, in one place. */
    private static final class DividendStream {
        final List<CorporateActions.CorporateAction> sorted;
        int cursor;

        DividendStream(List<CorporateActions.CorporateAction> sorted) {
            this.sorted = sorted;
        }
    }

    private PortfolioBacktester() {
    }

    /** Classic run: every supplied symbol is tradeable on every bar. */
    public static Result run(PortfolioStrategy strategy, Map<String, BarSeries> data, Config config) {
        return run(strategy, data, config, null, Map.of());
    }

    /**
     * Survivorship-aware run (see the class doc for semantics).
     *
     * @param universe      point-in-time membership and terminal events;
     *                      {@code null} behaves like the classic overload
     * @param cashDividends per-symbol {@code CASH_DIVIDEND} actions applied
     *                      as cash on the ex-date (other action types are
     *                      ignored here — apply splits to the price series
     *                      via {@link CorporateActions#adjust} instead)
     */
    public static Result run(PortfolioStrategy strategy, Map<String, BarSeries> data, Config config,
                             PointInTimeUniverse universe,
                             Map<String, List<CorporateActions.CorporateAction>> cashDividends) {
        return run(strategy, data, data, config, universe, cashDividends);
    }

    /**
     * Survivorship-aware run on a price pair: {@code signalData} (split and
     * dividend adjusted, for example via {@link CorporateActions#adjust}) is
     * what the strategy sees for its signals; {@code data} (unadjusted) is
     * what the engine trades, values and pays dividends on. Both maps must
     * hold the same symbols on the same timeline.
     */
    public static Result run(PortfolioStrategy strategy, Map<String, BarSeries> data,
                             Map<String, BarSeries> signalData, Config config,
                             PointInTimeUniverse universe,
                             Map<String, List<CorporateActions.CorporateAction>> cashDividends) {
        if (data.isEmpty()) {
            throw new IllegalArgumentException("no series supplied");
        }
        // Sorted for determinism: with a caller-supplied HashMap, iteration
        // order would otherwise decide within-bar processing order (and
        // with it the outcome of same-bar lifecycle interactions).
        List<String> symbols = new ArrayList<>(data.keySet());
        symbols.sort(String::compareTo);
        int n = data.get(symbols.getFirst()).size();
        for (String s : symbols) {
            if (data.get(s).size() != n) {
                throw new IllegalArgumentException("series must be index-aligned: " + s);
            }
        }
        if (!signalData.keySet().equals(data.keySet())) {
            throw new IllegalArgumentException("signal and trade data must hold the same symbols");
        }
        for (String s : symbols) {
            BarSeries sig = signalData.get(s);
            if (sig.size() != n || sig.timestamp(0) != data.get(s).timestamp(0)
                    || sig.timestamp(n - 1) != data.get(s).timestamp(n - 1)) {
                throw new IllegalArgumentException("signal series misaligned with trade series: " + s);
            }
        }
        strategy.init(signalData, data);
        FillPolicy fillPolicy = config.fillPolicy();
        Map<String, Double> pendingWeights = null;
        double financingCost = 0;

        double cash = config.initialCapital();
        Map<String, Double> positions = new HashMap<>();   // signed quantities
        double[] equity = new double[n];
        double totalCosts = 0, totalTurnover = 0;
        double dividendCash = 0;
        int eventsApplied = 0;
        // Dead = terminal event processed: never traded or valued again.
        Set<String> dead = new HashSet<>();
        // Per-symbol date-sorted dividends with their cursor in one holder:
        // impossible to desynchronize, and no boxed-Integer churn per bar.
        Map<String, DividendStream> divs = new HashMap<>();
        for (Map.Entry<String, List<CorporateActions.CorporateAction>> e : cashDividends.entrySet()) {
            List<CorporateActions.CorporateAction> sorted = new ArrayList<>(e.getValue());
            sorted.sort(java.util.Comparator.comparingLong(
                    CorporateActions.CorporateAction::exTimestamp));
            divs.put(e.getKey(), new DividendStream(sorted));
        }
        // Any symbol's timeline works for bar timestamps: series are aligned.
        BarSeries clock = data.get(symbols.getFirst());

        for (int i = 0; i < n; i++) {
            long now = clock.timestamp(i);

            // 0) Financing accrued over (i-1, i] on the book held at close i-1.
            if (i > 0 && !config.financing().isNone()) {
                double charge = financingCharge(config, positions, data, cash, i - 1);
                cash -= charge;
                financingCost += charge;
            }

            // 1) Cash dividends on the ex-date: holders receive, shorts pay.
            //    Processed before same-bar lifecycle events: the entitlement
            //    belongs to whoever held through the prior close, so a name
            //    that delists (or drops from the index) on its own ex-date
            //    still pays its holder before the position terminates.
            for (Map.Entry<String, DividendStream> e : divs.entrySet()) {
                String symbol = e.getKey();
                DividendStream stream = e.getValue();
                while (stream.cursor < stream.sorted.size()
                        && stream.sorted.get(stream.cursor).exTimestamp() <= now) {
                    CorporateActions.CorporateAction action = stream.sorted.get(stream.cursor);
                    if (action.type() == CorporateActions.Type.CASH_DIVIDEND) {
                        // Symbols dead from earlier bars hold 0: credit is 0.
                        double qty = positions.getOrDefault(symbol, 0.0);
                        double credit = qty * action.value();
                        cash += credit;
                        dividendCash += credit;
                    }
                    stream.cursor++;
                }
            }

            if (universe != null) {
                // 2) Terminal events reaching their effective bar — mergers
                //    first, then delistings, so a target's shares flow into
                //    an acquirer that dies on the same bar and settle at the
                //    acquirer's terms, independent of symbol ordering.
                for (PointInTimeUniverse.EventType phase : TERMINAL_ORDER) {
                    for (String symbol : symbols) {
                        if (dead.contains(symbol)) {
                            continue;
                        }
                        PointInTimeUniverse.TerminalEvent event = universe.terminalEvent(symbol);
                        if (event == null || event.type() != phase || now < event.timestamp()) {
                            continue;
                        }
                        double qty = positions.getOrDefault(symbol, 0.0);
                        if (qty != 0) {
                            // Proceeds anchor on the last close before the event —
                            // the event bar's own (possibly forward-filled) price
                            // is exactly what must not be trusted.
                            double lastClose = data.get(symbol).close(Math.max(0, i - 1));
                            if (event.type() == PointInTimeUniverse.EventType.DELISTING) {
                                cash += qty * lastClose * (1 + event.delistingReturn());
                            } else {
                                cash += qty * event.cashPerShare();
                                if (event.acquirerSharesPerShare() > 0) {
                                    String acquirer = event.acquirer();
                                    if (!data.containsKey(acquirer) || dead.contains(acquirer)) {
                                        throw new IllegalArgumentException(
                                                "merger of " + symbol + " pays shares of " + acquirer
                                                        + ", which is not a live series in the backtest");
                                    }
                                    positions.merge(acquirer,
                                            qty * event.acquirerSharesPerShare(), Double::sum);
                                }
                            }
                            positions.put(symbol, 0.0);
                        }
                        dead.add(symbol);
                        eventsApplied++;
                    }
                }
                // 3) Index drops: alive but no longer a member → forced sale
                //    at this bar's close (commission charged like any trade).
                for (String symbol : symbols) {
                    double qty = positions.getOrDefault(symbol, 0.0);
                    if (qty != 0 && !dead.contains(symbol) && !universe.isMember(symbol, now)) {
                        double close = data.get(symbol).close(i);
                        double notional = Math.abs(qty) * close;
                        double fee = config.fee(data.get(symbol), i, notional);
                        cash += qty * close - fee;
                        totalCosts += fee;
                        totalTurnover += notional;
                        positions.put(symbol, 0.0);
                    }
                }
            }

            // 4) Fill orders decided on the previous bar.
            if (pendingWeights != null && fillPolicy != FillPolicy.CLOSE) {
                boolean atOpen = fillPolicy == FillPolicy.NEXT_OPEN;
                double[] tc = rebalance(pendingWeights, symbols, data, positions, dead, universe,
                        now, i, atOpen, cash, config);
                cash = tc[0];
                totalCosts += tc[1];
                totalTurnover += tc[2];
                pendingWeights = null;
            }

            // 5) Decide at this bar's close.
            if (i % config.rebalanceEveryBars() == 0) {
                Map<String, Double> weights = strategy.targetWeights(i);
                if (fillPolicy == FillPolicy.CLOSE) {
                    double[] tc = rebalance(weights, symbols, data, positions, dead, universe,
                            now, i, false, cash, config);
                    cash = tc[0];
                    totalCosts += tc[1];
                    totalTurnover += tc[2];
                } else {
                    pendingWeights = Map.copyOf(weights);
                }
            }
            equity[i] = cash + marketValue(positions, data, i);
        }

        Map<String, Double> finalPositions = new LinkedHashMap<>();
        for (String symbol : symbols) {
            double qty = positions.getOrDefault(symbol, 0.0);
            if (qty != 0) {
                finalPositions.put(symbol, qty);
            }
        }
        PerformanceMetrics metrics = PerformanceAnalytics.compute(
                equity, List.of(), config.periodsPerYear());
        return new Result(equity, metrics, totalCosts, totalTurnover, finalPositions,
                dividendCash, eventsApplied, financingCost);
    }

    /**
     * Trades every live, tradeable symbol to its target weight of the
     * portfolio value marked at the fill point. Returns {cash, costs, turnover}.
     */
    private static double[] rebalance(Map<String, Double> weights, List<String> symbols,
                                      Map<String, BarSeries> data, Map<String, Double> positions,
                                      Set<String> dead, PointInTimeUniverse universe, long now,
                                      int i, boolean atOpen, double cash, Config config) {
        double portfolioValue = cash;
        for (Map.Entry<String, Double> p : positions.entrySet()) {
            BarSeries s = data.get(p.getKey());
            portfolioValue += p.getValue() * (atOpen ? s.open(i) : s.close(i));
        }
        double costs = 0;
        double turnover = 0;
        for (String symbol : symbols) {
            if (dead.contains(symbol)) {
                continue; // ghost bars of a dead security are untradeable
            }
            // Non-members are capped at weight 0: the strategy cannot buy
            // what is not in the universe at this date.
            boolean tradeable = universe == null || universe.isMember(symbol, now);
            double weight = tradeable ? weights.getOrDefault(symbol, 0.0) : 0.0;
            BarSeries s = data.get(symbol);
            double price = atOpen ? s.open(i) : s.close(i);
            double targetQty = weight * portfolioValue / price;
            double currentQty = positions.getOrDefault(symbol, 0.0);
            double delta = targetQty - currentQty;
            if (delta == 0) {
                continue;
            }
            double notional = Math.abs(delta) * price;
            double fee = config.fee(s, i, notional);
            cash -= delta * price + fee;
            costs += fee;
            turnover += notional;
            positions.put(symbol, targetQty);
        }
        return new double[]{cash, costs, turnover};
    }

    /** One bar of financing on the book held at close {@code i}. */
    private static double financingCharge(Config config, Map<String, Double> positions,
                                          Map<String, BarSeries> data, double cash, int i) {
        Financing f = config.financing();
        double perBar = 1.0 / config.periodsPerYear();
        double charge = cash >= 0 ? -cash * f.cashRate() * perBar : -cash * f.marginRate() * perBar;
        for (Map.Entry<String, Double> p : positions.entrySet()) {
            if (p.getValue() < 0) {
                double notional = -p.getValue() * data.get(p.getKey()).close(i);
                charge += notional * f.borrowBps(p.getKey()) / 1e4 * perBar;
            }
        }
        return charge;
    }

    private static double marketValue(Map<String, Double> positions,
                                      Map<String, BarSeries> data, int index) {
        double value = 0;
        for (Map.Entry<String, Double> p : positions.entrySet()) {
            value += p.getValue() * data.get(p.getKey()).close(index);
        }
        return value;
    }
}
