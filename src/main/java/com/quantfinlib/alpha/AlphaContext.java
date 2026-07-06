package com.quantfinlib.alpha;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.data.PointInTimeUniverse;
import com.quantfinlib.screener.Fundamentals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The research dataset an alpha factor operates on: an index-aligned panel
 * of price series over a fixed symbol order, with optional fundamentals.
 *
 * <p>Everything in {@code com.quantfinlib.alpha} works <em>cross-sectionally</em>:
 * at a bar index, a factor scores every symbol, and downstream steps
 * (evaluation, construction, backtest) consume those scores as arrays
 * aligned with {@link #symbols()}. Freezing the symbol order once here is
 * what makes plain {@code double[]} the interchange type for the whole
 * pipeline — no per-step map lookups, no ordering ambiguity.</p>
 *
 * <p>Series must be index-aligned (same length, same bar times — see
 * {@code data.SeriesAligner}); the constructor enforces equal length, the
 * timestamp discipline is the caller's (documented) responsibility.
 * Fundamentals are an optional static snapshot: factors that need them
 * ({@code Factors.value()}, {@code Factors.quality()}) return NaN for
 * symbols without entries. A point-in-time fundamentals history is data
 * this library cannot invent — the snapshot is honest about that.</p>
 *
 * <p><b>Survivorship</b>: alpha research is the stage survivorship bias
 * flatters most — the delisted losers a short book would have held are the
 * exact names a today's-constituents panel lacks. Attach a
 * {@link PointInTimeUniverse} via {@link #withUniverse} and every built-in
 * factor scores non-members/dead names as NaN at each bar
 * ({@link #isActive}), so ICs, validation and constructed weights only ever
 * see the point-in-time cross-section. Without a universe the panel is
 * survivorship-blind — fine for methodology work, dishonest for
 * performance claims. Custom {@link AlphaFactor}s should honor
 * {@code isActive} the same way. Note the weight-based
 * {@code AlphaBacktester} still earns ghost returns on a name that dies
 * <em>mid-hold</em> (weights only change at rebalances); for
 * lifecycle-exact accounting feed the weights into
 * {@code backtest.portfolio.PortfolioBacktester}'s survivorship-aware
 * overload.</p>
 */
public final class AlphaContext {

    private final List<String> symbols;      // frozen order: the panel's axis
    private final BarSeries[] series;        // aligned with symbols
    private final Fundamentals[] fundamentals; // aligned; null entry = unknown
    private final PointInTimeUniverse universe; // null = everything always active
    private final int bars;

    private AlphaContext(List<String> symbols, BarSeries[] series,
                         Fundamentals[] fundamentals, PointInTimeUniverse universe, int bars) {
        this.symbols = symbols;
        this.series = series;
        this.fundamentals = fundamentals;
        this.universe = universe;
        this.bars = bars;
    }

    /** Panel without fundamentals (technical factors only). */
    public static AlphaContext of(Map<String, BarSeries> data) {
        return of(data, Map.of());
    }

    /** Panel with a fundamentals snapshot for value/quality factors. */
    public static AlphaContext of(Map<String, BarSeries> data,
                                  Map<String, Fundamentals> fundamentals) {
        if (data.isEmpty()) {
            throw new IllegalArgumentException("no series supplied");
        }
        // Sort symbols for a deterministic panel axis regardless of the
        // caller's map implementation — results must not depend on hash order.
        List<String> symbols = new ArrayList<>(data.keySet());
        symbols.sort(String::compareTo);
        int n = data.get(symbols.getFirst()).size();
        BarSeries[] series = new BarSeries[symbols.size()];
        Fundamentals[] funda = new Fundamentals[symbols.size()];
        for (int i = 0; i < symbols.size(); i++) {
            BarSeries s = data.get(symbols.get(i));
            if (s.size() != n) {
                throw new IllegalArgumentException(
                        "series must be index-aligned (see data.SeriesAligner): "
                                + symbols.get(i) + " has " + s.size() + " bars, expected " + n);
            }
            series[i] = s;
            funda[i] = fundamentals.get(symbols.get(i));
        }
        return new AlphaContext(List.copyOf(symbols), series, funda, null, n);
    }

    /**
     * The same panel with a point-in-time universe attached: built-in
     * factors then score non-members as NaN per bar (see the class doc).
     * Universe timestamps must be in the same units as the bar timestamps.
     */
    public AlphaContext withUniverse(PointInTimeUniverse universe) {
        return new AlphaContext(symbols, series, fundamentals, universe, bars);
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

    /** Fundamentals for symbol index {@code i}, or {@code null} when unknown. */
    public Fundamentals fundamentals(int i) {
        return fundamentals[i];
    }

    /** Bar timestamp at {@code index} (taken from the first series). */
    public long timestamp(int index) {
        return series[0].timestamp(index);
    }

    /**
     * Simple return of symbol {@code i} over {@code (fromIndex, toIndex]} —
     * the forward-return building block evaluation and backtesting share.
     */
    public double returnOver(int i, int fromIndex, int toIndex) {
        return series[i].close(toIndex) / series[i].close(fromIndex) - 1;
    }
}
