package com.quantfinlib.data;

import com.quantfinlib.core.BarSeries;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Aligns multi-asset bar series onto one shared timeline — the bridge from
 * raw vendor files ({@link CsvBarLoader}) to the index-aligned input the
 * {@code PortfolioBacktester} requires. Real data never lines up: different
 * holidays, listing dates, and gaps.
 *
 * <ul>
 *   <li>{@link #intersect} — keep only timestamps present in <b>every</b>
 *       series (strictest, no synthetic bars).</li>
 *   <li>{@link #unionForwardFill} — union of timestamps from each series'
 *       first bar onward; gaps carry the previous close forward as a flat
 *       zero-volume bar.</li>
 * </ul>
 */
public final class SeriesAligner {

    private SeriesAligner() {
    }

    /** Timestamps common to all series, in order; input map order preserved. */
    public static Map<String, BarSeries> intersect(Map<String, BarSeries> input) {
        if (input.isEmpty()) {
            throw new IllegalArgumentException("no series supplied");
        }
        Set<Long> common = null;
        for (BarSeries s : input.values()) {
            Set<Long> timestamps = new HashSet<>();
            for (int i = 0; i < s.size(); i++) {
                timestamps.add(s.timestamp(i));
            }
            if (common == null) {
                common = timestamps;
            } else {
                common.retainAll(timestamps);
            }
        }
        if (common.isEmpty()) {
            throw new IllegalArgumentException("series share no common timestamps");
        }
        TreeSet<Long> ordered = new TreeSet<>(common);

        Map<String, BarSeries> out = new LinkedHashMap<>();
        for (Map.Entry<String, BarSeries> e : input.entrySet()) {
            BarSeries s = e.getValue();
            Map<Long, Integer> indexByTs = new HashMap<>();
            for (int i = 0; i < s.size(); i++) {
                indexByTs.put(s.timestamp(i), i);
            }
            BarSeries.Builder b = BarSeries.builder(s.symbol());
            for (long ts : ordered) {
                b.add(s.bar(indexByTs.get(ts)));
            }
            out.put(e.getKey(), b.build());
        }
        return out;
    }

    /**
     * Union of timestamps from the latest series start onward; missing bars
     * are forward-filled as flat bars at the previous close with zero volume.
     */
    public static Map<String, BarSeries> unionForwardFill(Map<String, BarSeries> input) {
        if (input.isEmpty()) {
            throw new IllegalArgumentException("no series supplied");
        }
        long start = Long.MIN_VALUE;
        TreeSet<Long> union = new TreeSet<>();
        for (BarSeries s : input.values()) {
            start = Math.max(start, s.timestamp(0));
            for (int i = 0; i < s.size(); i++) {
                union.add(s.timestamp(i));
            }
        }
        // Only from the point where every series has traded at least once.
        TreeSet<Long> timeline = new TreeSet<>(union.tailSet(start, true));
        if (timeline.isEmpty()) {
            throw new IllegalArgumentException("series share no overlapping period");
        }

        Map<String, BarSeries> out = new LinkedHashMap<>();
        for (Map.Entry<String, BarSeries> e : input.entrySet()) {
            BarSeries s = e.getValue();
            BarSeries.Builder b = BarSeries.builder(s.symbol());
            int cursor = 0;
            double lastClose = Double.NaN;
            for (long ts : timeline) {
                while (cursor < s.size() && s.timestamp(cursor) < ts) {
                    lastClose = s.close(cursor);
                    cursor++;
                }
                if (cursor < s.size() && s.timestamp(cursor) == ts) {
                    b.add(s.bar(cursor));
                    lastClose = s.close(cursor);
                    cursor++;
                } else {
                    // Gap: carry the previous close as a flat zero-volume bar.
                    b.add(ts, lastClose, lastClose, lastClose, lastClose, 0);
                }
            }
            out.put(e.getKey(), b.build());
        }
        return out;
    }
}
