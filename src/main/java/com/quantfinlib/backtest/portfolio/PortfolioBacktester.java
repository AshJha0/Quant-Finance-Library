package com.quantfinlib.backtest.portfolio;

import com.quantfinlib.backtest.PerformanceAnalytics;
import com.quantfinlib.backtest.PerformanceMetrics;
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
 * <p>Feed this overload <b>unadjusted</b> prices (dividends are cash here —
 * adjusted prices would double-count them) aligned via
 * {@code SeriesAligner.unionForwardFill}; the forward-filled ghost bars of a
 * dead symbol are harmless because its position terminates at the event.
 * The engine cannot remove the bias in the <em>data</em>: the universe and
 * delisting returns must come from a point-in-time dataset that includes
 * dead tickers.</p>
 */
public final class PortfolioBacktester {

    public record Config(double initialCapital, double commissionRate,
                         int rebalanceEveryBars, int periodsPerYear) {

        public static Config defaults() {
            return new Config(1_000_000, 0.001, 1, 252);
        }

        public Config withRebalanceEvery(int bars) {
            return new Config(initialCapital, commissionRate, bars, periodsPerYear);
        }
    }

    /**
     * {@code dividendCashCredited} and {@code lifecycleEventsApplied} are
     * populated by the survivorship-aware overload (zero otherwise).
     */
    public record Result(double[] equityCurve, PerformanceMetrics metrics,
                         double totalCosts, double totalTurnoverNotional,
                         Map<String, Double> finalPositions,
                         double dividendCashCredited, int lifecycleEventsApplied) {
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
        strategy.init(data);

        double cash = config.initialCapital();
        Map<String, Double> positions = new HashMap<>();   // signed quantities
        double[] equity = new double[n];
        double totalCosts = 0, totalTurnover = 0;
        double dividendCash = 0;
        int eventsApplied = 0;
        // Dead = terminal event processed: never traded or valued again.
        Set<String> dead = new HashSet<>();
        // Per-symbol date-sorted dividends with their cursor in ONE holder:
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

            // 1) Cash dividends on the ex-date: holders receive, shorts pay.
            //    Processed BEFORE same-bar lifecycle events: the entitlement
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
                // 2) Terminal events reaching their effective bar — MERGERS
                //    first, then DELISTINGS, so a target's shares flow into
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
                            // Proceeds anchor on the last close BEFORE the event —
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
                        double fee = notional * config.commissionRate();
                        cash += qty * close - fee;
                        totalCosts += fee;
                        totalTurnover += notional;
                        positions.put(symbol, 0.0);
                    }
                }
            }

            double portfolioValue = cash + marketValue(positions, data, i);

            if (i % config.rebalanceEveryBars() == 0) {
                Map<String, Double> weights = strategy.targetWeights(i);
                for (String symbol : symbols) {
                    if (dead.contains(symbol)) {
                        continue; // ghost bars of a dead security are untradeable
                    }
                    // Non-members are capped at weight 0: the strategy cannot
                    // buy what is not in the universe at this date.
                    boolean tradeable = universe == null || universe.isMember(symbol, now);
                    double weight = tradeable ? weights.getOrDefault(symbol, 0.0) : 0.0;
                    double close = data.get(symbol).close(i);
                    double targetQty = weight * portfolioValue / close;
                    double currentQty = positions.getOrDefault(symbol, 0.0);
                    double delta = targetQty - currentQty;
                    if (delta == 0) {
                        continue;
                    }
                    double notional = Math.abs(delta) * close;
                    double fee = notional * config.commissionRate();
                    cash -= delta * close + fee;
                    totalCosts += fee;
                    totalTurnover += notional;
                    positions.put(symbol, targetQty);
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
                dividendCash, eventsApplied);
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
