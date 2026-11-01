package com.quantfinlib.alpha;

import com.quantfinlib.screener.Fundamentals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Point-in-time fundamentals: every record is keyed by the time it became
 * public, and {@link #asOf} returns only what was published by then.
 *
 * <p>A fundamentals snapshot taken today and used to score history is
 * look-ahead: today's P/E is today's price over trailing earnings, so
 * "cheap" in the snapshot means "fell over the sample" and a value backtest
 * on it measures sample-path reversal. The fix is the publication date.
 * A filing for the quarter ending {@code periodEnd} is not usable until it
 * is released, typically 30 to 90 days later in US equities (the SEC 10-Q
 * deadline is 40 to 45 days after quarter end, 10-K 60 to 90 days); research
 * convention is to assume a conservative fixed lag when the true release
 * date is unknown (Fama and French 1992 use a six-month lag on fiscal
 * year-end accounting data).</p>
 *
 * <p>Ratio fields ({@code peRatio}, {@code pbRatio}) are taken as recorded,
 * that is, at the price on the record's own date. {@code Factors.value()}
 * prefers {@code eps / close(t)} for the earnings yield when {@code eps} is
 * present, so the price side updates every bar.</p>
 *
 * <p>Timestamps use the same epoch units as the {@code BarSeries} panel. Not
 * thread-safe while being filled; safe to share once filled.</p>
 */
public final class FundamentalsHistory {

    /** One published record. */
    public record Entry(long periodEnd, long publishedAt, Fundamentals fundamentals) {
    }

    private final Map<String, List<Entry>> bySymbol = new HashMap<>();

    /**
     * Adds a record with its actual publication time.
     *
     * @param periodEnd   end of the fiscal period the record describes
     * @param publishedAt when the record became public ({@code >= periodEnd})
     */
    public FundamentalsHistory add(String symbol, long periodEnd, long publishedAt,
                                   Fundamentals fundamentals) {
        if (publishedAt < periodEnd) {
            throw new IllegalArgumentException("published before the period ended: " + symbol);
        }
        if (fundamentals == null) {
            throw new IllegalArgumentException("fundamentals must not be null");
        }
        List<Entry> list = bySymbol.computeIfAbsent(symbol, k -> new ArrayList<>());
        list.add(new Entry(periodEnd, publishedAt, fundamentals));
        // Keep publication order so asOf can binary-search; ties keep the
        // later-added record last (it wins).
        list.sort((a, b) -> Long.compare(a.publishedAt(), b.publishedAt()));
        return this;
    }

    /**
     * Adds a record whose release date is unknown, assuming it became public
     * {@code reportingLag} after {@code periodEnd}.
     */
    public FundamentalsHistory addWithLag(String symbol, long periodEnd, long reportingLag,
                                          Fundamentals fundamentals) {
        if (reportingLag < 0) {
            throw new IllegalArgumentException("reportingLag must be >= 0");
        }
        return add(symbol, periodEnd, periodEnd + reportingLag, fundamentals);
    }

    /**
     * The latest record published at or before {@code timestamp}, or
     * {@code null} when nothing about the symbol was public yet.
     */
    public Fundamentals asOf(String symbol, long timestamp) {
        List<Entry> list = bySymbol.get(symbol);
        if (list == null) {
            return null;
        }
        int lo = 0;
        int hi = list.size();
        while (lo < hi) { // first entry published after timestamp
            int mid = (lo + hi) >>> 1;
            if (list.get(mid).publishedAt() <= timestamp) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo == 0 ? null : list.get(lo - 1).fundamentals();
    }

    /** All records for a symbol in publication order (unmodifiable). */
    public List<Entry> entries(String symbol) {
        return List.copyOf(bySymbol.getOrDefault(symbol, List.of()));
    }
}
