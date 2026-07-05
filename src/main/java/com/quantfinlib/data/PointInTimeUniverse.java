package com.quantfinlib.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Point-in-time universe membership — the engine-side half of
 * survivorship-bias-free backtesting.
 *
 * <p>Survivorship bias enters when a backtest's universe is built from
 * <em>today's</em> constituents: every bankruptcy, acquisition and delisting
 * has already been silently removed, so the strategy only ever "picks" from
 * winners. Removing the bias needs two things:</p>
 * <ol>
 *   <li><b>Data</b> — historical membership including dead tickers, and
 *       delisting returns (what a holder actually received). This class
 *       cannot conjure that; it comes from CRSP-style datasets.</li>
 *   <li><b>Engine</b> — screens and rebalances that only see members
 *       <em>as of each date</em>, and positions that terminate correctly
 *       when a security dies. That is what this class provides, consumed by
 *       {@code screener.StockScreener#membersAsOf} and the universe-aware
 *       {@code backtest.portfolio.PortfolioBacktester} overload.</li>
 * </ol>
 *
 * <p>Per symbol it records membership intervals (a symbol can leave and
 * rejoin an index) and at most one <b>terminal event</b>:</p>
 * <ul>
 *   <li>{@link EventType#DELISTING} with a <em>delisting return</em> — the
 *       final-day return relative to the last close (−1 = shareholders got
 *       nothing). When the true value is unknown for an involuntary
 *       delisting, the literature's convention is
 *       {@link #DEFAULT_INVOLUNTARY_DELISTING_RETURN} (Shumway 1997).</li>
 *   <li>{@link EventType#MERGER} with per-share deal terms: cash and/or
 *       shares of the acquirer.</li>
 * </ul>
 *
 * <p>Timestamps use the same epoch units as the {@code BarSeries} being
 * backtested. Not thread-safe during construction; effectively immutable
 * afterwards if not mutated.</p>
 */
public final class PointInTimeUniverse {

    /**
     * The standard haircut for involuntary delistings with unknown proceeds:
     * −30% on the last traded price (Shumway, Journal of Finance 1997).
     */
    public static final double DEFAULT_INVOLUNTARY_DELISTING_RETURN = -0.30;

    /** How a security's life ends. */
    public enum EventType {
        DELISTING, MERGER
    }

    /**
     * A security's terminal event.
     *
     * @param timestamp             when the event takes effect (first bar at/after
     *                              it applies the event)
     * @param type                  delisting or merger
     * @param delistingReturn       final-day return on the last close
     *                              (delistings; 0 for mergers)
     * @param cashPerShare          merger cash component per share
     * @param acquirerSharesPerShare merger stock component per share
     * @param acquirer              acquirer symbol ({@code null} for delistings
     *                              and all-cash deals)
     */
    public record TerminalEvent(long timestamp, EventType type, double delistingReturn,
                                double cashPerShare, double acquirerSharesPerShare,
                                String acquirer) {
    }

    private record Interval(long from, long to) { // inclusive both ends
    }

    private final Map<String, List<Interval>> memberships = new HashMap<>();
    private final Map<String, TerminalEvent> events = new HashMap<>();

    /**
     * Adds a membership interval (inclusive of both endpoints). A symbol may
     * hold several disjoint intervals — index drop and later re-add.
     */
    public PointInTimeUniverse addMembership(String symbol, long fromTimestamp,
                                             long toTimestampInclusive) {
        if (toTimestampInclusive < fromTimestamp) {
            throw new IllegalArgumentException("membership ends before it starts: " + symbol);
        }
        memberships.computeIfAbsent(symbol, k -> new ArrayList<>())
                .add(new Interval(fromTimestamp, toTimestampInclusive));
        return this;
    }

    /** Membership from a date with no known end (a current constituent). */
    public PointInTimeUniverse addMembership(String symbol, long fromTimestamp) {
        return addMembership(symbol, fromTimestamp, Long.MAX_VALUE);
    }

    /**
     * Records a delisting: membership (if any) is truncated at the event and
     * the position terminates at {@code lastClose × (1 + delistingReturn)}.
     * Use {@link #DEFAULT_INVOLUNTARY_DELISTING_RETURN} when the true
     * proceeds are unknown.
     */
    public PointInTimeUniverse recordDelisting(String symbol, long timestamp,
                                               double delistingReturn) {
        if (delistingReturn < -1) {
            throw new IllegalArgumentException(
                    "delisting return cannot be below -100%: " + delistingReturn);
        }
        putEvent(symbol, new TerminalEvent(timestamp, EventType.DELISTING, delistingReturn,
                0, 0, null));
        return this;
    }

    /**
     * Records a merger/acquisition: at the event each held share converts to
     * {@code cashPerShare} cash plus {@code acquirerSharesPerShare} shares of
     * {@code acquirer}. All-cash deals pass 0 shares and a null acquirer.
     */
    public PointInTimeUniverse recordMerger(String symbol, long timestamp, double cashPerShare,
                                            double acquirerSharesPerShare, String acquirer) {
        if (cashPerShare < 0 || acquirerSharesPerShare < 0) {
            throw new IllegalArgumentException("deal terms cannot be negative");
        }
        if (acquirerSharesPerShare > 0 && acquirer == null) {
            throw new IllegalArgumentException("stock component needs an acquirer symbol");
        }
        putEvent(symbol, new TerminalEvent(timestamp, EventType.MERGER, 0,
                cashPerShare, acquirerSharesPerShare, acquirer));
        return this;
    }

    private void putEvent(String symbol, TerminalEvent event) {
        if (events.containsKey(symbol)) {
            throw new IllegalArgumentException(symbol + " already has a terminal event");
        }
        events.put(symbol, event);
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /**
     * Whether the symbol is a universe member at {@code timestamp}: inside a
     * membership interval and not past its terminal event.
     */
    public boolean isMember(String symbol, long timestamp) {
        TerminalEvent event = events.get(symbol);
        if (event != null && timestamp >= event.timestamp()) {
            return false; // dead securities are never members
        }
        List<Interval> intervals = memberships.get(symbol);
        if (intervals == null) {
            return false;
        }
        for (Interval iv : intervals) {
            if (timestamp >= iv.from() && timestamp <= iv.to()) {
                return true;
            }
        }
        return false;
    }

    /** All members as of a timestamp, sorted for determinism. */
    public Set<String> membersAsOf(long timestamp) {
        Set<String> members = new TreeSet<>();
        for (String symbol : memberships.keySet()) {
            if (isMember(symbol, timestamp)) {
                members.add(symbol);
            }
        }
        return members;
    }

    /** The symbol's terminal event, or {@code null} while it lives. */
    public TerminalEvent terminalEvent(String symbol) {
        return events.get(symbol);
    }

    /** Every symbol that ever appears in this universe (living and dead). */
    public Set<String> allSymbols() {
        Set<String> all = new TreeSet<>(memberships.keySet());
        all.addAll(events.keySet());
        return all;
    }
}
