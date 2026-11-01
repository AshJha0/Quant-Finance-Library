package com.quantfinlib.rates;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToDoubleBiFunction;

/**
 * Curve risk on a bootstrapped OIS + projection curve pair by bumping the
 * market quotes and rebuilding: the sensitivities a desk hedges with,
 * because they are expressed in the instruments it can trade.
 *
 * <p>All results are the change in {@code pv(discount, projection)} for a
 * +1bp move in the named quote(s), by central difference
 * {@code (PV(+1bp) - PV(-1bp)) / 2}, which removes the second-order term.</p>
 * <ul>
 *   <li>{@link #bucketedDv01}: one quote at a time (OIS quotes first, then
 *       projection quotes, each in the order given). Rebuilding moves the
 *       projection curve too when an OIS quote moves, because the projection
 *       bootstrap discounts on OIS.</li>
 *   <li>{@link #keyRateDv01}: Ho (1992, "Key rate durations", Journal of
 *       Fixed Income 2(2)) triangular weights on the pillar times of both
 *       instrument sets; key j moves each quote by
 *       {@code w_j(t) * 1bp}, w_j the hat function on the key tenors (flat
 *       beyond the first and last key). The weights sum to one at every t,
 *       so the key-rate DV01s add up to {@link #parallelDv01} to first
 *       order.</li>
 *   <li>{@link #parallelDv01}: every quote +1bp.</li>
 * </ul>
 * <p>On a log-linear curve the buckets add up to the parallel DV01 to
 * about 1e-7 relative. A monotone-convex curve is continuous but not
 * differentiable in its quotes where an interval changes shape zone, so a
 * 1bp bump can cross a kink and the buckets then add up only to within a
 * few percent of the parallel figure; the gap closes as the bump shrinks.</p>
 */
public final class MultiCurveRisk {

    private static final double BP = 1e-4;

    private MultiCurveRisk() {
    }

    /**
     * Builds the curve pair: OIS from {@code oisInstruments}, projection from
     * {@code projectionInstruments} discounted on it (the OIS curve itself
     * when that list is empty).
     */
    public static CurvePair buildCurves(LocalDate valuationDate,
                                              List<CurveInstrument> oisInstruments,
                                              List<CurveInstrument> projectionInstruments,
                                              CurveInterpolation interpolation) {
        DiscountCurve ois = MultiCurveBootstrap.bootstrap(valuationDate, oisInstruments, interpolation);
        DiscountCurve proj = projectionInstruments.isEmpty() ? ois
                : MultiCurveBootstrap.bootstrapProjection(valuationDate, projectionInstruments,
                        interpolation, ois);
        return new CurvePair(ois, proj);
    }

    /** Per-quote DV01, OIS quotes first then projection quotes. */
    public static double[] bucketedDv01(LocalDate valuationDate, List<CurveInstrument> oisInstruments,
                                        List<CurveInstrument> projectionInstruments,
                                        CurveInterpolation interpolation,
                                        ToDoubleBiFunction<DiscountCurve, DiscountCurve> pv) {
        Objects.requireNonNull(pv, "pv");
        int nO = oisInstruments.size();
        int n = nO + projectionInstruments.size();
        double[] out = new double[n];
        for (int k = 0; k < n; k++) {
            double[] w = new double[n];
            w[k] = 1;
            out[k] = bumped(valuationDate, oisInstruments, projectionInstruments, interpolation, pv, w);
        }
        return out;
    }

    /** DV01 for a +1bp move in every quote. */
    public static double parallelDv01(LocalDate valuationDate, List<CurveInstrument> oisInstruments,
                                      List<CurveInstrument> projectionInstruments,
                                      CurveInterpolation interpolation,
                                      ToDoubleBiFunction<DiscountCurve, DiscountCurve> pv) {
        Objects.requireNonNull(pv, "pv");
        double[] w = new double[oisInstruments.size() + projectionInstruments.size()];
        java.util.Arrays.fill(w, 1);
        return bumped(valuationDate, oisInstruments, projectionInstruments, interpolation, pv, w);
    }

    /**
     * Key-rate DV01s at {@code keyTenorsYears} (strictly increasing, ACT/365F
     * years); one value per key.
     */
    public static double[] keyRateDv01(LocalDate valuationDate, List<CurveInstrument> oisInstruments,
                                       List<CurveInstrument> projectionInstruments,
                                       CurveInterpolation interpolation,
                                       ToDoubleBiFunction<DiscountCurve, DiscountCurve> pv,
                                       double[] keyTenorsYears) {
        Objects.requireNonNull(pv, "pv");
        double[] keys = keyTenorsYears.clone();
        if (keys.length == 0) {
            throw new IllegalArgumentException("need at least one key tenor");
        }
        for (int j = 0; j < keys.length; j++) {
            if (!Double.isFinite(keys[j]) || (j > 0 && !(keys[j] > keys[j - 1]))) {
                throw new IllegalArgumentException("key tenors must be finite and strictly increasing");
            }
        }
        List<CurveInstrument> all = new ArrayList<>(oisInstruments);
        all.addAll(projectionInstruments);
        double[] out = new double[keys.length];
        for (int j = 0; j < keys.length; j++) {
            double[] w = new double[all.size()];
            for (int k = 0; k < w.length; k++) {
                double t = DayCount.ACT_365.yearFraction(valuationDate, all.get(k).pillarDate());
                w[k] = hat(keys, j, t);
            }
            out[j] = bumped(valuationDate, oisInstruments, projectionInstruments, interpolation, pv, w);
        }
        return out;
    }

    /** Ho (1992) triangular weight of key {@code j} at time {@code t}. */
    static double hat(double[] keys, int j, double t) {
        int m = keys.length;
        if (t <= keys[0]) {
            return j == 0 ? 1 : 0;
        }
        if (t >= keys[m - 1]) {
            return j == m - 1 ? 1 : 0;
        }
        if (j > 0 && t > keys[j - 1] && t <= keys[j]) {
            return (t - keys[j - 1]) / (keys[j] - keys[j - 1]);
        }
        if (j < m - 1 && t > keys[j] && t < keys[j + 1]) {
            return (keys[j + 1] - t) / (keys[j + 1] - keys[j]);
        }
        return 0;
    }

    private static double bumped(LocalDate val, List<CurveInstrument> ois, List<CurveInstrument> proj,
                                 CurveInterpolation interp,
                                 ToDoubleBiFunction<DiscountCurve, DiscountCurve> pv, double[] w) {
        CurvePair up = buildCurves(val, shift(ois, w, 0, BP), shift(proj, w, ois.size(), BP), interp);
        CurvePair dn = buildCurves(val, shift(ois, w, 0, -BP), shift(proj, w, ois.size(), -BP), interp);
        return 0.5 * (pv.applyAsDouble(up.discount(), up.projection()) - pv.applyAsDouble(dn.discount(), dn.projection()));
    }

    private static List<CurveInstrument> shift(List<CurveInstrument> in, double[] w, int offset,
                                               double bump) {
        List<CurveInstrument> out = new ArrayList<>(in.size());
        for (int i = 0; i < in.size(); i++) {
            CurveInstrument c = in.get(i);
            double wi = w[offset + i];
            out.add(wi == 0 ? c : c.withQuote(c.quote() + wi * bump));
        }
        return out;
    }
}
