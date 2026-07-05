package com.quantfinlib.backtest.portfolio;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.data.PointInTimeUniverse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Point-in-time cross-sectional momentum — the classic equity factor, built
 * to demonstrate (and test) universe-aware backtesting: at every rebalance
 * the strategy ranks <b>only the stocks that are index members at that
 * bar's timestamp</b>, goes long the best trailing performers and short the
 * worst.
 *
 * <p>Momentum definition is the academic standard "12-1":
 * {@code close(i − skip) / close(i − lookback) − 1} — trailing
 * {@code lookback} bars with the most recent {@code skip} bars excluded
 * (short-term reversal contaminates raw 12-month momentum; Jegadeesh-Titman
 * 1993). Each side is equal-weighted at {@code grossPerSide} total, so the
 * default book is dollar-neutral with 2× gross-per-side exposure.</p>
 *
 * <p><b>Why point-in-time matters here specifically</b>: momentum is the
 * factor survivorship bias flatters most. The stocks that delisted are
 * disproportionately the past losers a short book would have held — remove
 * them from the universe and the short side looks artificially safe, while
 * the long side never picks a name that was about to be acquired away.
 * Ranking over {@code universe.isMember(symbol, now)} and running on the
 * {@linkplain PortfolioBacktester survivorship-aware overload} closes both
 * gaps (a {@code null} universe reproduces the naive everything-always-
 * tradeable behavior — useful for measuring the bias).</p>
 *
 * <p>Candidates also need {@code lookback} bars of history at the rebalance
 * bar; earlier bars produce an empty book (the engine holds cash).</p>
 */
public final class CrossSectionalMomentum implements PortfolioStrategy {

    /**
     * @param lookbackBars momentum window (252 ≈ 12 months of dailies)
     * @param skipBars     most-recent bars excluded (21 ≈ 1 month)
     * @param perSide      names held long and short (shrinks when the member
     *                     count can't fill both sides without overlap)
     * @param grossPerSide total absolute weight per side (0.5 → 1× gross,
     *                     dollar-neutral)
     */
    public record Config(int lookbackBars, int skipBars, int perSide, double grossPerSide) {

        public Config {
            if (lookbackBars <= 0 || skipBars < 0 || skipBars >= lookbackBars
                    || perSide <= 0 || grossPerSide <= 0) {
                throw new IllegalArgumentException(
                        "need lookback > skip >= 0, perSide > 0, grossPerSide > 0");
            }
        }

        /** The academic 12-1 monthly-rebalance setup on daily bars. */
        public static Config twelveMinusOne(int perSide) {
            return new Config(252, 21, perSide, 0.5);
        }
    }

    private final PointInTimeUniverse universe;   // null = everything always tradeable
    private final Config config;
    private Map<String, BarSeries> data;
    private List<String> symbols;
    private BarSeries clock;                      // aligned series: any one keeps time

    public CrossSectionalMomentum(PointInTimeUniverse universe, Config config) {
        this.universe = universe;
        this.config = config;
    }

    @Override
    public String name() {
        return "XS_MOMENTUM(" + config.lookbackBars() + "-" + config.skipBars()
                + ", " + config.perSide() + "/side)";
    }

    @Override
    public void init(Map<String, BarSeries> data) {
        this.data = data;
        this.symbols = new ArrayList<>(data.keySet());
        this.clock = data.values().iterator().next();
    }

    @Override
    public Map<String, Double> targetWeights(int index) {
        if (index < config.lookbackBars()) {
            return Map.of(); // not enough history: hold cash
        }
        long now = clock.timestamp(index);

        // The point-in-time step: rank ONLY the names that are members at
        // this bar. Dead and dropped stocks never enter the cross-section.
        List<String> candidates = new ArrayList<>();
        List<Double> momentum = new ArrayList<>();
        for (String symbol : symbols) {
            if (universe != null && !universe.isMember(symbol, now)) {
                continue;
            }
            BarSeries s = data.get(symbol);
            double past = s.close(index - config.lookbackBars());
            double recent = s.close(index - config.skipBars());
            candidates.add(symbol);
            momentum.add(recent / past - 1);
        }
        // Both sides need at least one name each without overlapping.
        int side = Math.min(config.perSide(), candidates.size() / 2);
        if (side == 0) {
            return Map.of();
        }

        // Rank descending by momentum (indices into candidates).
        Integer[] order = new Integer[candidates.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(momentum.get(b), momentum.get(a)));

        Map<String, Double> weights = new HashMap<>();
        double w = config.grossPerSide() / side;
        for (int i = 0; i < side; i++) {
            weights.put(candidates.get(order[i]), w);                       // winners long
            weights.put(candidates.get(order[order.length - 1 - i]), -w);   // losers short
        }
        return weights;
    }
}
