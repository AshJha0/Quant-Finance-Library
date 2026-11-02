package com.quantfinlib.alpha;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Combines several alpha factors into one by information-coefficient
 * weights that match the holding horizon, account for signal decay,
 * remove redundancy by orthogonalisation and shrink toward equal weights.
 *
 * <p><b>Orthogonalisation.</b> At every date the factors' cross-sectional
 * z-scores are put through classical Gram-Schmidt in the order given:
 * {@code z'_k = z_k − Σ_{j<k} (⟨z_k, z'_j⟩/⟨z'_j, z'_j⟩) z'_j}, then
 * re-scaled to unit standard deviation (a residual with no dispersion, a
 * factor fully explained by earlier ones, becomes zero). The order is a
 * modelling choice: earlier factors keep their full content and later ones
 * are credited only with what is new.</p>
 *
 * <p><b>Horizon-matched, decay-aware IC.</b> A book rebuilt every {@code H}
 * bars holds a date-{@code t} signal over the bars {@code t+1 … t+H}, so
 * the relevant skill is the average of the one-bar ICs at lags
 * {@code 0 … H−1}:
 * {@code IC_k(t) = (1/H) Σ_{l=0}^{H−1} corr(z'_k(t), r_{t+l→t+l+1})}
 * (Pearson over the names scored at {@code t}). A fast-decaying signal
 * loses weight as {@code H} grows; this is the horizon matching of Grinold
 * and Kahn (2000, Active Portfolio Management, 2nd ed., ch. 13, signal
 * decay) applied per factor. The per-date values are averaged with
 * exponential weights of half-life {@code icHalfLife} dates, most recent
 * date weight 1.</p>
 *
 * <p><b>Weights.</b> For orthogonal unit-variance signals the
 * mean-variance optimal combination is proportional to the ICs (Grinold
 * and Kahn 2000, ch. 10). The raw weights {@code IC_k / Σ|IC_j|} are shrunk
 * toward equal weights: {@code w_k = (1 − δ) IC_k/Σ|IC_j| + δ/K}. Equal
 * weighting assumes every factor is oriented so that higher is better, the
 * {@link AlphaFactor} convention. With {@code δ = 0} a factor with negative
 * estimated IC gets a negative weight.</p>
 *
 * <p>Only data up to {@code toIndex} is read when fitting: the last IC date
 * is {@code toIndex − H}.</p>
 */
public final class AlphaCombiner {

    /**
     * Combination settings.
     *
     * @param horizon        holding horizon {@code H} in bars (&ge; 1)
     * @param icHalfLife     half-life of the date weights, in IC dates (&gt; 0)
     * @param shrinkToEqual  {@code δ} in [0, 1]
     * @param step           bars between IC dates (&ge; 1)
     * @param orthogonalize  apply per-date Gram-Schmidt
     */
    public record Settings(int horizon, double icHalfLife, double shrinkToEqual, int step,
                           boolean orthogonalize) {

        public Settings {
            if (horizon < 1 || !(icHalfLife > 0) || !(shrinkToEqual >= 0 && shrinkToEqual <= 1)
                    || step < 1) {
                throw new IllegalArgumentException("invalid combiner settings");
            }
        }

        /** Orthogonalised, no shrinkage, flat date weights, one IC date per bar. */
        public static Settings of(int horizon) {
            return new Settings(horizon, Double.POSITIVE_INFINITY, 0, 1, true);
        }

        public Settings withShrinkToEqual(double delta) {
            return new Settings(horizon, icHalfLife, delta, step, orthogonalize);
        }

        public Settings withIcHalfLife(double dates) {
            return new Settings(horizon, dates, shrinkToEqual, step, orthogonalize);
        }

        public Settings withOrthogonalize(boolean on) {
            return new Settings(horizon, icHalfLife, shrinkToEqual, step, on);
        }
    }

    /** Fitted weights and the combined factor. */
    public static final class Combination implements AlphaFactor {
        private final List<AlphaFactor> factors;
        private final double[] weights;
        private final double[] ics;
        private final int dates;
        private final boolean orthogonalize;

        Combination(List<AlphaFactor> factors, double[] weights, double[] ics, int dates,
                    boolean orthogonalize) {
            this.factors = List.copyOf(factors);
            this.weights = weights.clone();
            this.ics = ics.clone();
            this.dates = dates;
            this.orthogonalize = orthogonalize;
        }

        /** Weight of factor {@code k}. */
        public double weight(int k) {
            return weights[k];
        }

        public double[] weightsCopy() {
            return weights.clone();
        }

        /** Horizon-matched, date-weighted mean IC of (orthogonalised) factor {@code k}. */
        public double ic(int k) {
            return ics[k];
        }

        public double[] icsCopy() {
            return ics.clone();
        }

        public int factorCount() {
            return weights.length;
        }

        /** IC dates used in the fit. */
        public int dates() {
            return dates;
        }

        /**
         * Combined score: {@code Σ w_k z'_k} with the same per-date
         * orthogonalisation as the fit; NaN where any factor has no score.
         */
        @Override
        public double[] scores(AlphaContext ctx, int index) {
            double[][] z = prepared(ctx, factors, index, orthogonalize);
            int n = ctx.symbolCount();
            double[] out = new double[n];
            for (int i = 0; i < n; i++) {
                if (Double.isNaN(z[0][i])) {
                    out[i] = Double.NaN;
                    continue;
                }
                double s = 0;
                for (int k = 0; k < weights.length; k++) {
                    s += weights[k] * z[k][i];
                }
                out[i] = s;
            }
            return out;
        }

        @Override
        public String name() {
            StringBuilder b = new StringBuilder("COMBO(");
            for (int k = 0; k < weights.length; k++) {
                if (k > 0) {
                    b.append(',');
                }
                b.append(factors.get(k).name()).append(':')
                        .append(String.format(Locale.ROOT, "%.3f", weights[k]));
            }
            return b.append(')').toString();
        }
    }

    private AlphaCombiner() {
    }

    /**
     * Fits the combination on IC dates {@code fromIndex, fromIndex + step, …}
     * up to {@code toIndex − horizon}.
     */
    public static Combination fit(AlphaContext ctx, List<AlphaFactor> factors, int fromIndex,
                                  int toIndex, Settings settings) {
        int k = factors.size();
        if (k == 0 || fromIndex < 0 || toIndex >= ctx.bars()
                || fromIndex > toIndex - settings.horizon()) {
            throw new IllegalArgumentException("need factors and fromIndex <= toIndex - horizon"
                    + " < bars");
        }
        int h = settings.horizon();
        List<double[]> perDate = new ArrayList<>();
        for (int t = fromIndex; t <= toIndex - h; t += settings.step()) {
            double[][] z = prepared(ctx, factors, t, settings.orthogonalize());
            double[] ic = new double[k];
            boolean ok = true;
            for (int l = 0; l < h && ok; l++) {
                double[] r = new double[ctx.symbolCount()];
                for (int i = 0; i < r.length; i++) {
                    r[i] = ctx.returnOver(i, t + l, t + l + 1);
                }
                for (int j = 0; j < k; j++) {
                    double c = pearson(z[j], r);
                    if (Double.isNaN(c)) {
                        ok = false;
                        break;
                    }
                    ic[j] += c / h;
                }
            }
            if (ok) {
                perDate.add(ic);
            }
        }
        if (perDate.isEmpty()) {
            throw new IllegalArgumentException("no IC date had three or more scored names");
        }
        double decay = Double.isInfinite(settings.icHalfLife()) ? 1
                : Math.pow(0.5, 1 / settings.icHalfLife());
        double[] mean = new double[k];
        double wsum = 0;
        double w = 1;
        for (int d = perDate.size() - 1; d >= 0; d--, w *= decay) {
            for (int j = 0; j < k; j++) {
                mean[j] += w * perDate.get(d)[j];
            }
            wsum += w;
        }
        double abs = 0;
        for (int j = 0; j < k; j++) {
            mean[j] /= wsum;
            abs += Math.abs(mean[j]);
        }
        double delta = settings.shrinkToEqual();
        double[] weights = new double[k];
        for (int j = 0; j < k; j++) {
            double raw = abs > 0 ? mean[j] / abs : 1.0 / k;
            weights[j] = (1 - delta) * raw + delta / k;
        }
        return new Combination(factors, weights, mean, perDate.size(),
                settings.orthogonalize());
    }

    /**
     * Per-date z-scores of every factor on the names all factors score,
     * Gram-Schmidt orthogonalised when asked; NaN elsewhere.
     */
    static double[][] prepared(AlphaContext ctx, List<AlphaFactor> factors, int index,
                               boolean orthogonalize) {
        int k = factors.size();
        int n = ctx.symbolCount();
        double[][] raw = new double[k][];
        boolean[] common = new boolean[n];
        Arrays.fill(common, true);
        for (int j = 0; j < k; j++) {
            raw[j] = factors.get(j).scores(ctx, index);
            if (raw[j].length != n) {
                throw new IllegalArgumentException("factor " + factors.get(j).name()
                        + " returned a misaligned array");
            }
            for (int i = 0; i < n; i++) {
                common[i] &= Double.isFinite(raw[j][i]);
            }
        }
        double[][] z = new double[k][];
        for (int j = 0; j < k; j++) {
            double[] masked = new double[n];
            for (int i = 0; i < n; i++) {
                masked[i] = common[i] ? raw[j][i] : Double.NaN;
            }
            z[j] = Factors.zScore(masked);
        }
        if (orthogonalize) {
            for (int j = 0; j < k; j++) {
                for (int q = 0; q < j; q++) {
                    double num = 0;
                    double den = 0;
                    for (int i = 0; i < n; i++) {
                        if (common[i]) {
                            num += z[j][i] * z[q][i];
                            den += z[q][i] * z[q][i];
                        }
                    }
                    if (den > 0) {
                        double b = num / den;
                        for (int i = 0; i < n; i++) {
                            if (common[i]) {
                                z[j][i] -= b * z[q][i];
                            }
                        }
                    }
                }
                double ss = 0;
                int m = 0;
                for (int i = 0; i < n; i++) {
                    if (common[i]) {
                        ss += z[j][i] * z[j][i];
                        m++;
                    }
                }
                // Residual sd relative to the unit-sd input: below 1e-10 it is rounding.
                double sd = m == 0 ? 0 : Math.sqrt(ss / m);
                for (int i = 0; i < n; i++) {
                    if (common[i]) {
                        z[j][i] = sd > 1e-10 ? z[j][i] / sd : 0;
                    }
                }
            }
        }
        return z;
    }

    /** Pearson correlation over pairs where both are finite; NaN with fewer than three or no dispersion. */
    static double pearson(double[] a, double[] b) {
        double sa = 0;
        double sb = 0;
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            if (Double.isFinite(a[i]) && Double.isFinite(b[i])) {
                sa += a[i];
                sb += b[i];
                n++;
            }
        }
        if (n < 3) {
            return Double.NaN;
        }
        double ma = sa / n;
        double mb = sb / n;
        double cab = 0;
        double caa = 0;
        double cbb = 0;
        for (int i = 0; i < a.length; i++) {
            if (Double.isFinite(a[i]) && Double.isFinite(b[i])) {
                cab += (a[i] - ma) * (b[i] - mb);
                caa += (a[i] - ma) * (a[i] - ma);
                cbb += (b[i] - mb) * (b[i] - mb);
            }
        }
        if (caa == 0 || cbb == 0) {
            return caa == 0 && cbb > 0 ? 0 : Double.NaN;
        }
        return cab / Math.sqrt(caa * cbb);
    }
}
