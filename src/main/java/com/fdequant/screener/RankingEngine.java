package com.fdequant.screener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Ranking engine: scores stocks by a weighted blend of min-max-normalized
 * criteria and sorts them best-first.
 */
public final class RankingEngine {

    public record ScoredStock(StockSnapshot stock, double score) {
    }

    private record Criterion(String name, double weight, ToDoubleFunction<StockSnapshot> extractor) {
    }

    private final List<Criterion> criteria = new ArrayList<>();

    /**
     * @param weight    relative importance; use a negative weight to prefer
     *                  smaller values (e.g. lower P/E ranks higher)
     * @param extractor metric to score, e.g. {@code s -> s.fundamentals().roe()}
     */
    public RankingEngine addCriterion(String name, double weight, ToDoubleFunction<StockSnapshot> extractor) {
        criteria.add(new Criterion(name, weight, extractor));
        return this;
    }

    public List<ScoredStock> rank(List<StockSnapshot> stocks) {
        if (criteria.isEmpty()) {
            throw new IllegalStateException("no ranking criteria configured");
        }
        int n = stocks.size();
        double[] scores = new double[n];
        double totalAbsWeight = 0;
        for (Criterion c : criteria) {
            totalAbsWeight += Math.abs(c.weight());
        }

        for (Criterion c : criteria) {
            double[] values = new double[n];
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                values[i] = c.extractor().applyAsDouble(stocks.get(i));
                if (!Double.isNaN(values[i])) {
                    min = Math.min(min, values[i]);
                    max = Math.max(max, values[i]);
                }
            }
            for (int i = 0; i < n; i++) {
                double norm = Double.isNaN(values[i]) || max == min
                        ? 0.5
                        : (values[i] - min) / (max - min);
                if (c.weight() < 0) {
                    norm = 1 - norm;
                }
                scores[i] += Math.abs(c.weight()) / totalAbsWeight * norm;
            }
        }

        List<ScoredStock> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new ScoredStock(stocks.get(i), scores[i]));
        }
        out.sort(Comparator.comparingDouble(ScoredStock::score).reversed());
        return out;
    }
}
