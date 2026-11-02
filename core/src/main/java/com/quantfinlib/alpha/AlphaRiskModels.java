package com.quantfinlib.alpha;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.risk.FactorRiskModel;
import com.quantfinlib.screener.Fundamentals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link FactorRiskModel} from an {@link AlphaContext} panel, with
 * style factors taken from {@link Factors}.
 *
 * <p>Styles: {@code SIZE} (log capitalisation), {@code MOMENTUM}
 * ({@link Factors#momentum}{@code (momentumLookback, 0)}),
 * {@code LOW_VOL} ({@link Factors#lowVolatility}) and, when the panel has
 * fundamentals, {@code VALUE} ({@link Factors#value()}). Capitalisation is
 * {@link Fundamentals#marketCap()} as of the bar when the panel carries it;
 * otherwise the trailing {@code capWindow}-bar mean dollar volume stands in
 * as a liquidity-size proxy, which keeps the regression weights and the
 * size buckets ordered by tradable size.</p>
 *
 * <p>Period {@code j} of the model is the close-to-close return from bar
 * {@code j − 1} to bar {@code j} with exposures and capitalisations taken
 * at bar {@code j − 1}; the forecast row is bar {@code index}. Only data up
 * to {@code close(index)} is read, so the model can be rebuilt inside a
 * backtest without look-ahead.</p>
 */
public final class AlphaRiskModels {

    /**
     * Risk model specification.
     *
     * @param window           estimation periods (&ge; 2)
     * @param momentumLookback momentum lookback in bars
     * @param volLookback      low-volatility lookback in bars
     * @param capWindow        bars of dollar volume for the size proxy
     * @param model            factor risk model settings
     */
    public record Spec(int window, int momentumLookback, int volLookback, int capWindow,
                       FactorRiskModel.Settings model) {

        public Spec {
            if (window < 2 || momentumLookback < 1 || volLookback < 2 || capWindow < 1
                    || model == null) {
                throw new IllegalArgumentException("invalid risk model spec");
            }
        }

        /** 120-period window, 60-bar momentum and volatility, 20-bar size proxy, default model. */
        public static Spec defaults() {
            return new Spec(120, 60, 60, 20, FactorRiskModel.Settings.defaults());
        }

        /** First bar at which a model can be built. */
        public int firstIndex() {
            return window + Math.max(Math.max(momentumLookback, volLookback + 1), capWindow);
        }
    }

    private AlphaRiskModels() {
    }

    /**
     * Estimates the model on the {@code spec.window()} periods ending at
     * {@code index}, forecasting the period after it.
     *
     * @param sectors sector label per symbol in context order (null = one sector)
     */
    public static FactorRiskModel estimate(AlphaContext ctx, int index, String[] sectors,
                                           Spec spec) {
        return new Builder(ctx, sectors, spec).estimate(index);
    }

    /** Caches per-bar exposures across repeated estimates on one panel. */
    static final class Builder {
        final AlphaContext ctx;
        final Spec spec;
        final int[] sectorIndex;
        final List<String> sectorNames;
        final List<String> styleNames;
        final List<AlphaFactor> styles;
        final boolean fundamentals;
        final Map<Integer, double[][]> styleCache = new java.util.HashMap<>();
        final Map<Integer, double[]> capCache = new java.util.HashMap<>();

        Builder(AlphaContext ctx, String[] sectors, Spec spec) {
            this.ctx = ctx;
            this.spec = spec;
            int n = ctx.symbolCount();
            if (sectors != null && sectors.length != n) {
                throw new IllegalArgumentException("one sector label per symbol");
            }
            Map<String, Integer> ids = new LinkedHashMap<>();
            sectorIndex = new int[n];
            for (int i = 0; i < n; i++) {
                String s = sectors == null || sectors[i] == null ? "ALL" : sectors[i];
                sectorIndex[i] = ids.computeIfAbsent(s, k -> ids.size());
            }
            sectorNames = List.copyOf(ids.keySet());
            fundamentals = hasFundamentals(ctx);
            List<String> names = new ArrayList<>();
            List<AlphaFactor> fs = new ArrayList<>();
            names.add("SIZE");
            fs.add(null);
            names.add("MOMENTUM");
            fs.add(Factors.momentum(spec.momentumLookback(), 0));
            names.add("LOW_VOL");
            fs.add(Factors.lowVolatility(spec.volLookback()));
            if (fundamentals) {
                names.add("VALUE");
                fs.add(Factors.value());
            }
            styleNames = List.copyOf(names);
            styles = fs;
        }

        private static boolean hasFundamentals(AlphaContext ctx) {
            for (int i = 0; i < ctx.symbolCount(); i++) {
                Fundamentals f = ctx.fundamentals(i);
                if (f != null && Double.isFinite(f.marketCap()) && f.marketCap() > 0) {
                    return true;
                }
            }
            return ctx.hasFundamentalsHistory();
        }

        int[] sectorIndex() {
            return sectorIndex;
        }

        double[] caps(int bar) {
            return capCache.computeIfAbsent(bar, this::computeCaps);
        }

        private double[] computeCaps(int bar) {
            int n = ctx.symbolCount();
            double[] c = new double[n];
            for (int i = 0; i < n; i++) {
                int e = ctx.terminalIndex(i);
                if (!ctx.isActive(i, bar) || (e >= 0 && bar >= e)) {
                    c[i] = Double.NaN;
                    continue;
                }
                double cap = Double.NaN;
                if (fundamentals) {
                    Fundamentals f = ctx.hasFundamentalsHistory() ? ctx.fundamentalsAsOf(i, bar)
                            : ctx.fundamentals(i);
                    cap = f == null ? Double.NaN : f.marketCap();
                } else {
                    BarSeries s = ctx.series(i);
                    double sum = 0;
                    int from = Math.max(0, bar - spec.capWindow() + 1);
                    for (int j = from; j <= bar; j++) {
                        sum += s.close(j) * s.volume(j);
                    }
                    cap = sum / (bar - from + 1);
                }
                c[i] = Double.isFinite(cap) && cap > 0 ? cap : Double.NaN;
            }
            return c;
        }

        double[][] styles(int bar) {
            return styleCache.computeIfAbsent(bar, this::computeStyles);
        }

        private double[][] computeStyles(int bar) {
            int n = ctx.symbolCount();
            double[][] out = new double[styles.size()][];
            double[] caps = caps(bar);
            double[] size = new double[n];
            for (int i = 0; i < n; i++) {
                size[i] = Math.log(caps[i]);
            }
            out[0] = size;
            for (int k = 1; k < styles.size(); k++) {
                out[k] = styles.get(k).scores(ctx, bar);
            }
            return out;
        }

        FactorRiskModel estimate(int index) {
            int w = spec.window();
            if (index < spec.firstIndex() || index >= ctx.bars()) {
                throw new IllegalArgumentException("index " + index + " needs at least "
                        + spec.firstIndex() + " bars of history");
            }
            int n = ctx.symbolCount();
            double[][] returns = new double[w][n];
            double[][][] st = new double[w + 1][][];
            double[][] caps = new double[w + 1][];
            for (int p = 0; p < w; p++) {
                int from = index - w + p;
                st[p] = styles(from);
                caps[p] = caps(from);
                for (int i = 0; i < n; i++) {
                    double r = Double.isNaN(caps[p][i]) ? Double.NaN
                            : ctx.returnOver(i, from, from + 1);
                    returns[p][i] = Double.isFinite(r) ? r : Double.NaN;
                }
            }
            st[w] = styles(index);
            caps[w] = caps(index);
            return FactorRiskModel.estimate(returns, st, styleNames, sectorIndex, sectorNames,
                    caps, spec.model());
        }
    }
}
