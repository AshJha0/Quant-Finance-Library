package com.quantfinlib.alpha;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.data.PointInTimeUniverse;
import com.quantfinlib.screener.Fundamentals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The research dataset an alpha factor operates on: an index-aligned panel
 * of price series over a fixed symbol order, with optional fundamentals and
 * an optional point-in-time universe.
 *
 * <p>Everything in {@code com.quantfinlib.alpha} works cross-sectionally:
 * at a bar index a factor scores every symbol, and downstream steps
 * (evaluation, construction, backtest) consume those scores as arrays
 * aligned with {@link #symbols()}. Symbols are sorted once here, so plain
 * {@code double[]} is the interchange type for the whole pipeline.</p>
 *
 * <p><b>Alignment.</b> Series must share one timeline (see
 * {@code data.SeriesAligner}). {@link #of} checks both the length and every
 * bar timestamp against the first series: a one-bar offset in one name
 * silently turns a cross-sectional IC into a lead-lag estimate.</p>
 *
 * <p><b>Fundamentals.</b> Two sources:</p>
 * <ul>
 *   <li>a {@link FundamentalsHistory} ({@link #withFundamentalsHistory}),
 *       keyed by publication time, which makes fundamental factors
 *       point-in-time;</li>
 *   <li>a static snapshot ({@link #of(Map, Map)}). Scoring history with
 *       today's snapshot is look-ahead, so {@link #fundamentalsAsOf} only
 *       serves the snapshot at the last bar and throws for earlier bars.</li>
 * </ul>
 *
 * <p><b>Survivorship.</b> Attach a {@link PointInTimeUniverse} with
 * {@link #withUniverse}. Then:</p>
 * <ul>
 *   <li>{@link #isActive} is point-in-time membership, and every built-in
 *       factor scores inactive names as NaN;</li>
 *   <li>{@link #returnOver} is terminal-event aware: a name alive at the
 *       start of a window that delists inside it returns
 *       {@code close(e−1)·(1 + delistingReturn)/close(from) − 1}, where
 *       {@code e} is the first bar at or after the event; a merged name
 *       returns its deal value (cash plus acquirer shares marked at the
 *       acquirer's price). Without this the forward-filled ghost bars after
 *       a delisting read as a zero return, which removes exactly the losses
 *       a short book is paid to capture.</li>
 * </ul>
 * <p>The terminal value convention matches
 * {@code backtest.portfolio.PortfolioBacktester}: proceeds anchor on the
 * last close before the event bar.</p>
 */
public final class AlphaContext {

    private final List<String> symbols;        // frozen order: the panel's axis
    private final BarSeries[] series;          // aligned with symbols
    private final Fundamentals[] fundamentals; // static snapshot; null entry = unknown
    private final FundamentalsHistory history; // null = no point-in-time source
    private final PointInTimeUniverse universe; // null = everything always active
    private final int[] terminalIndex;         // first bar at/after the terminal event, -1 = none
    private final int[] acquirerIndex;         // panel index of the acquirer, -1 = none/absent
    private final int bars;

    private AlphaContext(List<String> symbols, BarSeries[] series, Fundamentals[] fundamentals,
                         FundamentalsHistory history, PointInTimeUniverse universe, int bars) {
        this.symbols = List.copyOf(symbols);
        this.series = series;
        this.fundamentals = fundamentals;
        this.history = history;
        this.universe = universe;
        this.bars = bars;
        this.terminalIndex = new int[symbols.size()];
        this.acquirerIndex = new int[symbols.size()];
        Arrays.fill(terminalIndex, -1);
        Arrays.fill(acquirerIndex, -1);
        if (universe != null) {
            for (int i = 0; i < symbols.size(); i++) {
                PointInTimeUniverse.TerminalEvent ev = universe.terminalEvent(symbols.get(i));
                if (ev == null) {
                    continue;
                }
                terminalIndex[i] = firstBarAtOrAfter(ev.timestamp());
                if (ev.acquirer() != null) {
                    acquirerIndex[i] = symbols.indexOf(ev.acquirer());
                }
            }
        }
    }

    /** Panel without fundamentals (technical factors only). */
    public static AlphaContext of(Map<String, BarSeries> data) {
        return of(data, Map.of());
    }

    /**
     * Panel with a static fundamentals snapshot. The snapshot is only valid
     * at the last bar (see the class doc); attach a
     * {@link FundamentalsHistory} to score fundamentals historically.
     *
     * @throws IllegalArgumentException when series differ in length or in
     *                                  any bar timestamp
     */
    public static AlphaContext of(Map<String, BarSeries> data,
                                  Map<String, Fundamentals> fundamentals) {
        if (data.isEmpty()) {
            throw new IllegalArgumentException("no series supplied");
        }
        // Sorted for a deterministic panel axis regardless of map order.
        List<String> symbols = new ArrayList<>(data.keySet());
        symbols.sort(String::compareTo);
        BarSeries first = data.get(symbols.getFirst());
        int n = first.size();
        BarSeries[] series = new BarSeries[symbols.size()];
        Fundamentals[] funda = new Fundamentals[symbols.size()];
        for (int i = 0; i < symbols.size(); i++) {
            BarSeries s = data.get(symbols.get(i));
            if (s.size() != n) {
                throw new IllegalArgumentException(
                        "series must be index-aligned (see data.SeriesAligner): "
                                + symbols.get(i) + " has " + s.size() + " bars, expected " + n);
            }
            for (int k = 0; k < n; k++) {
                if (s.timestamp(k) != first.timestamp(k)) {
                    throw new IllegalArgumentException("series must share one timeline: "
                            + symbols.get(i) + " bar " + k + " is at " + s.timestamp(k)
                            + " but " + symbols.getFirst() + " bar " + k + " is at "
                            + first.timestamp(k) + " (see data.SeriesAligner)");
                }
            }
            series[i] = s;
            funda[i] = fundamentals.get(symbols.get(i));
        }
        return new AlphaContext(List.copyOf(symbols), series, funda, null, null, n);
    }

    /**
     * The same panel with a point-in-time universe attached (see the class
     * doc). Universe timestamps must be in the bar timestamps' units.
     */
    public AlphaContext withUniverse(PointInTimeUniverse universe) {
        return new AlphaContext(symbols, series, fundamentals, history, universe, bars);
    }

    /** The same panel with point-in-time fundamentals attached. */
    public AlphaContext withFundamentalsHistory(FundamentalsHistory history) {
        return new AlphaContext(symbols, series, fundamentals, history, universe, bars);
    }

    /**
     * Whether symbol {@code i} is in the tradeable cross-section at
     * {@code barIndex}: always true without a universe, otherwise
     * point-in-time membership (dead and dropped names excluded).
     */
    public boolean isActive(int i, int barIndex) {
        return universe == null || universe.isMember(symbols.get(i), timestamp(barIndex));
    }

    /** The frozen symbol order every score/weight array aligns with. */
    public List<String> symbols() {
        return symbols;
    }

    public int symbolCount() {
        return symbols.size();
    }

    /** Panel length in bars (every series has exactly this many). */
    public int bars() {
        return bars;
    }

    /** Price series for symbol index {@code i} (the panel axis, not the bar). */
    public BarSeries series(int i) {
        return series[i];
    }

    /** Static snapshot fundamentals for symbol {@code i}, or {@code null}. */
    public Fundamentals fundamentals(int i) {
        return fundamentals[i];
    }

    /** Whether a point-in-time fundamentals history is attached. */
    public boolean hasFundamentalsHistory() {
        return history != null;
    }

    /**
     * Fundamentals of symbol {@code i} as known at bar {@code index}.
     * With a {@link FundamentalsHistory}: the latest record published at or
     * before the bar ({@code null} when none). With only a static snapshot:
     * the snapshot at the last bar; earlier bars throw, because using
     * today's fundamentals to score the past is look-ahead.
     *
     * @throws IllegalStateException for a historical bar without a history
     */
    public Fundamentals fundamentalsAsOf(int i, int index) {
        if (history != null) {
            return history.asOf(symbols.get(i), timestamp(index));
        }
        if (index < bars - 1 && fundamentals[i] != null) {
            throw new IllegalStateException("fundamentals for " + symbols.get(i)
                    + " are a static snapshot, which is look-ahead when scoring bar " + index
                    + " of " + bars + "; attach a FundamentalsHistory with publication dates");
        }
        return fundamentals[i];
    }

    /** Bar timestamp at {@code index} (all series share it). */
    public long timestamp(int index) {
        return series[0].timestamp(index);
    }

    /**
     * First bar at or after symbol {@code i}'s terminal event, or −1 when
     * there is no universe, no event, or the event falls after the panel.
     */
    public int terminalIndex(int i) {
        return terminalIndex[i];
    }

    /**
     * Panel index of the acquirer receiving symbol {@code i}'s holders in a
     * stock merger, or −1.
     */
    public int acquirerIndex(int i) {
        return acquirerIndex[i];
    }

    /** Acquirer shares paid per share of symbol {@code i} at its terminal event (0 if none). */
    public double terminalAcquirerShares(int i) {
        if (universe == null) {
            return 0;
        }
        PointInTimeUniverse.TerminalEvent ev = universe.terminalEvent(symbols.get(i));
        return ev == null ? 0 : ev.acquirerSharesPerShare();
    }

    /**
     * Simple return of symbol {@code i} over {@code (fromIndex, toIndex]},
     * close to close, terminal-event aware (see the class doc). NaN when the
     * name was already dead at {@code fromIndex}.
     */
    public double returnOver(int i, int fromIndex, int toIndex) {
        return returnBetween(i, fromIndex, false, toIndex, false);
    }

    /**
     * Simple return between two price points, each the open or the close of
     * a bar: the building block for fill-lagged backtests. Terminal-event
     * aware: once the event bar is reached the position is worth its
     * terminal value (cash, plus acquirer shares marked at the acquirer's
     * price at the end point). NaN when the name was already dead at the
     * start point.
     *
     * @throws IllegalStateException when a stock merger pays shares of an
     *                               acquirer outside the panel
     */
    public double returnBetween(int i, int fromIndex, boolean fromOpen,
                                int toIndex, boolean toOpen) {
        int e = terminalIndex[i];
        if (e >= 0 && fromIndex >= e) {
            return Double.NaN;
        }
        double start = fromOpen ? series[i].open(fromIndex) : series[i].close(fromIndex);
        return mark(i, toIndex, toOpen) / start - 1;
    }

    /**
     * Per-share value at a price point of a position held in symbol
     * {@code i} from before its terminal event: the price before the event
     * bar, the terminal value from it on.
     */
    double mark(int i, int index, boolean atOpen) {
        int e = terminalIndex[i];
        if (e < 0 || index < e) {
            return atOpen ? series[i].open(index) : series[i].close(index);
        }
        PointInTimeUniverse.TerminalEvent ev = universe.terminalEvent(symbols.get(i));
        if (ev.type() == PointInTimeUniverse.EventType.DELISTING) {
            double last = series[i].close(Math.max(0, e - 1));
            return last * (1 + ev.delistingReturn());
        }
        double value = ev.cashPerShare();
        if (ev.acquirerSharesPerShare() > 0) {
            int a = acquirerIndex[i];
            if (a < 0) {
                throw new IllegalStateException("merger of " + symbols.get(i) + " pays shares of "
                        + ev.acquirer() + ", which is not in the panel; add its series");
            }
            value += ev.acquirerSharesPerShare()
                    * (atOpen ? series[a].open(index) : series[a].close(index));
        }
        return value;
    }

    private int firstBarAtOrAfter(long ts) {
        int lo = 0;
        int hi = bars;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (timestamp(mid) < ts) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo == bars ? -1 : lo;
    }
}
