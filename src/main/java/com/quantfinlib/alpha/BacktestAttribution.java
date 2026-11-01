package com.quantfinlib.alpha;

import com.quantfinlib.risk.FactorRiskModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P&amp;L attribution of a traced backtest over time: factor, specific,
 * trading, cost and financing components per bar, with long/short leg and
 * sector splits.
 *
 * <p>For step {@code s} (bar {@code b → b+1}) with book {@code h} held over
 * the bar, cross-sectional factor returns {@code f} and specific returns
 * {@code u} from a {@link FactorRiskModel} regression of that bar
 * (exposures {@code X} at {@code close(b)}):</p>
 * <pre>
 *   factor_k  = (Σ_i h_i X_ik) f_k
 *   specific  = Σ_i h_i u_i
 *   trading   = Σ_i pnl_i − factor − specific
 *   net       = factor + specific + trading + cost + financing      (exact)
 * </pre>
 * <p>This is the holdings-based decomposition with a trading residual
 * (Menchero and Hu 2006, "Portfolio Risk Attribution", J. Performance
 * Measurement 10(3), for the factor/specific split; the trading term is
 * the transaction-based correction of Brinson-style attribution). The
 * trading term holds intrabar fills (a next-open fill earns a different
 * bar return than the book held at the close), terminal events of names
 * outside the regression and compounding across the open. With
 * close fills it is zero to rounding for names inside the regression.</p>
 *
 * <p>Legs and sectors split {@code Σ_i pnl_i}: a name is long or short by
 * the sign of its weight over the bar, or after the fill when it was not
 * held. Components are fractions of equity at the start of each bar;
 * totals are arithmetic sums over bars.</p>
 */
public final class BacktestAttribution {

    private final List<String> factorNames;
    private final List<String> sectorNames;
    private final double[][] factor;   // [steps][P]
    private final double[] specific;
    private final double[] trading;
    private final double[] cost;
    private final double[] financing;
    private final double[] longLeg;
    private final double[] shortLeg;
    private final double[][] sector;   // [steps][S]
    private final double[] net;

    private BacktestAttribution(List<String> factorNames, List<String> sectorNames, double[][] factor,
                           double[] specific, double[] trading, double[] cost, double[] financing,
                           double[] longLeg, double[] shortLeg, double[][] sector, double[] net) {
        this.factorNames = List.copyOf(factorNames);
        this.sectorNames = List.copyOf(sectorNames);
        this.factor = factor;
        this.specific = specific;
        this.trading = trading;
        this.cost = cost;
        this.financing = financing;
        this.longLeg = longLeg;
        this.shortLeg = shortLeg;
        this.sector = sector;
        this.net = net;
    }

    /**
     * Attributes a traced run. The factor model is a single ex-post
     * estimate over the traced bars, so each bar's factor returns come from
     * that bar's own cross-section. The bars before the trace must cover
     * the style lookbacks of {@code spec}.
     *
     * @param sectors sector label per symbol in context order (null = one sector)
     * @param spec    style lookbacks and model settings (its window is replaced by the trace length)
     */
    public static BacktestAttribution attribute(AlphaContext ctx, AlphaBacktester.Traced traced,
                                           String[] sectors, AlphaRiskModels.Spec spec) {
        int steps = traced.steps();
        int start = traced.startIndex();
        AlphaRiskModels.Spec s = new AlphaRiskModels.Spec(Math.max(2, steps),
                spec.momentumLookback(), spec.volLookback(), spec.capWindow(), spec.model());
        if (steps < 2) {
            throw new IllegalArgumentException("need at least two traced bars");
        }
        AlphaRiskModels.Builder builder = new AlphaRiskModels.Builder(ctx, sectors, s);
        FactorRiskModel model = builder.estimate(start + steps);
        int n = ctx.symbolCount();
        int p = model.factorCount();
        List<String> secNames = new ArrayList<>();
        Map<String, Integer> secId = new LinkedHashMap<>();
        int[] sid = new int[n];
        for (int i = 0; i < n; i++) {
            String lab = sectors == null || sectors[i] == null ? "ALL" : sectors[i];
            Integer id = secId.get(lab);
            if (id == null) {
                id = secId.size();
                secId.put(lab, id);
                secNames.add(lab);
            }
            sid[i] = id;
        }
        double[][] fac = new double[steps][p];
        double[] spec2 = new double[steps];
        double[] trd = new double[steps];
        double[] cst = new double[steps];
        double[] fin = new double[steps];
        double[] lg = new double[steps];
        double[] sh = new double[steps];
        double[][] sec = new double[steps][secNames.size()];
        double[] nt = new double[steps];
        for (int st = 0; st < steps; st++) {
            double[] h = traced.heldCopy(st);
            double[] after = st + 1 < steps ? traced.heldCopy(st + 1) : h;
            double[] pnl = traced.namePnlCopy(st);
            double[] xp = new double[p];
            double specific = 0;
            for (int i = 0; i < n; i++) {
                if (h[i] == 0 || Double.isNaN(model.residual(st, i))) {
                    continue;
                }
                for (int k = 0; k < p; k++) {
                    xp[k] += h[i] * model.exposureAt(st, i, k);
                }
                specific += h[i] * model.residual(st, i);
            }
            double factorSum = 0;
            boolean skipped = Double.isNaN(model.factorReturn(st, 0));
            for (int k = 0; k < p; k++) {
                fac[st][k] = skipped ? 0 : xp[k] * model.factorReturn(st, k);
                factorSum += fac[st][k];
            }
            if (skipped) {
                specific = 0;
            }
            double total = 0;
            for (int i = 0; i < n; i++) {
                total += pnl[i];
                double sign = h[i] != 0 ? h[i] : after[i];
                if (sign >= 0) {
                    lg[st] += pnl[i];
                } else {
                    sh[st] += pnl[i];
                }
                sec[st][sid[i]] += pnl[i];
            }
            spec2[st] = specific;
            trd[st] = total - factorSum - specific;
            cst[st] = traced.cost(st);
            fin[st] = traced.financing(st);
            nt[st] = traced.netReturn(st);
        }
        return new BacktestAttribution(model.factorNames(), List.copyOf(secNames), fac, spec2, trd,
                cst, fin, lg, sh, sec, nt);
    }

    public List<String> factorNames() {
        return factorNames;
    }

    public List<String> sectorNames() {
        return sectorNames;
    }

    public int steps() {
        return net.length;
    }

    /** P&amp;L from factor {@code k} in step {@code s}. */
    public double factor(int s, int k) {
        return factor[s][k];
    }

    /** P&amp;L from all factors in step {@code s}. */
    public double factorTotal(int s) {
        double t = 0;
        for (double v : factor[s]) {
            t += v;
        }
        return t;
    }

    public double specific(int s) {
        return specific[s];
    }

    public double trading(int s) {
        return trading[s];
    }

    public double cost(int s) {
        return cost[s];
    }

    public double financing(int s) {
        return financing[s];
    }

    public double longLeg(int s) {
        return longLeg[s];
    }

    public double shortLeg(int s) {
        return shortLeg[s];
    }

    /** P&amp;L of sector {@code j} (index into {@link #sectorNames()}) in step {@code s}. */
    public double sector(int s, int j) {
        return sector[s][j];
    }

    /** Net return of step {@code s}. */
    public double net(int s) {
        return net[s];
    }

    /** Sum over steps of factor {@code k}'s P&amp;L. */
    public double cumulativeFactor(int k) {
        double t = 0;
        for (double[] row : factor) {
            t += row[k];
        }
        return t;
    }

    public double cumulativeSpecific() {
        return sum(specific);
    }

    public double cumulativeTrading() {
        return sum(trading);
    }

    public double cumulativeCost() {
        return sum(cost);
    }

    public double cumulativeFinancing() {
        return sum(financing);
    }

    public double cumulativeLong() {
        return sum(longLeg);
    }

    public double cumulativeShort() {
        return sum(shortLeg);
    }

    public double cumulativeSector(int j) {
        double t = 0;
        for (double[] row : sector) {
            t += row[j];
        }
        return t;
    }

    public double cumulativeNet() {
        return sum(net);
    }

    private static double sum(double[] x) {
        double t = 0;
        for (double v : x) {
            t += v;
        }
        return t;
    }

    /** A plain-text table of the cumulative components. */
    public String format() {
        StringBuilder b = new StringBuilder();
        b.append(String.format(java.util.Locale.ROOT, "%-14s %10s%n", "component", "sum"));
        for (int k = 0; k < factorNames.size(); k++) {
            b.append(String.format(java.util.Locale.ROOT, "%-14s %10.5f%n", factorNames.get(k),
                    cumulativeFactor(k)));
        }
        b.append(String.format(java.util.Locale.ROOT, "%-14s %10.5f%n", "specific",
                cumulativeSpecific()));
        b.append(String.format(java.util.Locale.ROOT, "%-14s %10.5f%n", "trading",
                cumulativeTrading()));
        b.append(String.format(java.util.Locale.ROOT, "%-14s %10.5f%n", "cost", cumulativeCost()));
        b.append(String.format(java.util.Locale.ROOT, "%-14s %10.5f%n", "financing",
                cumulativeFinancing()));
        b.append(String.format(java.util.Locale.ROOT, "%-14s %10.5f%n", "net", cumulativeNet()));
        b.append(String.format(java.util.Locale.ROOT, "%-14s %10.5f%n", "long leg",
                cumulativeLong()));
        b.append(String.format(java.util.Locale.ROOT, "%-14s %10.5f%n", "short leg",
                cumulativeShort()));
        return b.toString();
    }
}
