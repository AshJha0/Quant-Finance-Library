package com.quantfinlib.ml;

import com.quantfinlib.util.MathUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Surveillance anomaly detection over interval-aggregated market activity:
 * <ul>
 *   <li><b>Quote stuffing</b> — message-rate spikes (robust z-score) combined
 *       with an abnormal order-to-trade ratio: lots of quoting, little
 *       trading.</li>
 *   <li><b>Price spikes</b> — interval returns far outside their recent
 *       distribution.</li>
 * </ul>
 *
 * <p>Scores are ROBUST z-scores — (x − median) / (1.4826 · MAD) — not
 * mean/stdev: an anomaly detector whose baseline includes the anomalies
 * inflates its own scale and misses exactly the events it hunts (a storm
 * of stuffing intervals raises the stdev until nothing clears the
 * threshold). Median/MAD ignores up to half the sample being
 * contaminated; 1.4826 rescales MAD to stdev units under normality so
 * thresholds keep their familiar sigma meaning. When MAD is 0 (more than
 * half the intervals identical) the detector falls back to mean/stdev,
 * and gives up only when that is 0 too.</p>
 */
public final class AnomalyDetector {

    public record Anomaly(int intervalIndex, String type, double score) {
    }

    public static final String QUOTE_STUFFING = "QUOTE_STUFFING";
    public static final String PRICE_SPIKE = "PRICE_SPIKE";

    private AnomalyDetector() {
    }

    /**
     * Flags intervals where the message count is a {@code zThreshold}-sigma
     * outlier AND the order-to-trade ratio exceeds {@code minOrderToTradeRatio}.
     *
     * @param messagesPerInterval order/cancel/replace message counts per interval
     * @param tradesPerInterval   trade counts per interval (aligned)
     */
    public static List<Anomaly> detectQuoteStuffing(long[] messagesPerInterval, long[] tradesPerInterval,
                                                    double zThreshold, double minOrderToTradeRatio) {
        if (messagesPerInterval.length != tradesPerInterval.length) {
            throw new IllegalArgumentException("series must align");
        }
        double[] m = new double[messagesPerInterval.length];
        for (int i = 0; i < m.length; i++) {
            m[i] = messagesPerInterval[i];
        }
        double center = median(m);
        double scale = robustScale(m, center);
        List<Anomaly> out = new ArrayList<>();
        if (scale == 0) {
            return out;
        }
        for (int i = 0; i < m.length; i++) {
            double z = (m[i] - center) / scale;
            double otr = messagesPerInterval[i] / (double) Math.max(1, tradesPerInterval[i]);
            if (z >= zThreshold && otr >= minOrderToTradeRatio) {
                out.add(new Anomaly(i, QUOTE_STUFFING, z));
            }
        }
        return out;
    }

    /** Flags intervals whose return is a {@code zThreshold}-sigma outlier. */
    public static List<Anomaly> detectPriceSpikes(double[] mids, double zThreshold) {
        if (mids.length < 3) {
            return List.of();
        }
        double[] rets = new double[mids.length - 1];
        for (int i = 1; i < mids.length; i++) {
            rets[i - 1] = mids[i] / mids[i - 1] - 1;
        }
        double center = median(rets);
        double scale = robustScale(rets, center);
        List<Anomaly> out = new ArrayList<>();
        if (scale == 0) {
            return out;
        }
        for (int i = 0; i < rets.length; i++) {
            double z = Math.abs(rets[i] - center) / scale;
            if (z >= zThreshold) {
                out.add(new Anomaly(i + 1, PRICE_SPIKE, z));
            }
        }
        return out;
    }

    private static double median(double[] v) {
        double[] sorted = v.clone();
        java.util.Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : 0.5 * (sorted[n / 2 - 1] + sorted[n / 2]);
    }

    /** 1.4826 * MAD, falling back to stdev when MAD is degenerate. */
    private static double robustScale(double[] v, double center) {
        double[] dev = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            dev[i] = Math.abs(v[i] - center);
        }
        double mad = median(dev);
        return mad > 0 ? 1.4826 * mad : MathUtils.stdDev(v);
    }
}
