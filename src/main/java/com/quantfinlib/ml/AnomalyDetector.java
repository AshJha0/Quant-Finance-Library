package com.quantfinlib.ml;

import com.quantfinlib.util.MathUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Surveillance anomaly detection over interval-aggregated market activity:
 * <ul>
 *   <li><b>Quote stuffing</b> — message-rate spikes (z-score) combined with an
 *       abnormal order-to-trade ratio: lots of quoting, little trading.</li>
 *   <li><b>Price spikes</b> — interval returns far outside their recent
 *       distribution.</li>
 * </ul>
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
        double mean = MathUtils.mean(m);
        double std = MathUtils.stdDev(m);
        List<Anomaly> out = new ArrayList<>();
        for (int i = 0; i < m.length; i++) {
            if (std == 0) {
                break;
            }
            double z = (m[i] - mean) / std;
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
        double mean = MathUtils.mean(rets);
        double std = MathUtils.stdDev(rets);
        List<Anomaly> out = new ArrayList<>();
        if (std == 0) {
            return out;
        }
        for (int i = 0; i < rets.length; i++) {
            double z = Math.abs(rets[i] - mean) / std;
            if (z >= zThreshold) {
                out.add(new Anomaly(i + 1, PRICE_SPIKE, z));
            }
        }
        return out;
    }
}
