package com.quantfinlib.data;

import com.quantfinlib.core.Bar;
import com.quantfinlib.core.BarSeries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Corporate action adjustment: back-adjusts a raw price series for splits and
 * cash dividends (CRSP-style multiplicative factors), so returns computed
 * across ex-dates reflect economics rather than mechanical price drops — the
 * difference between toy and usable equity backtests.
 *
 * <ul>
 *   <li><b>Split r-for-1</b>: bars before the ex-date get prices ÷ r and
 *       volume × r.</li>
 *   <li><b>Cash dividend d</b>: bars before the ex-date get prices ×
 *       {@code (prevClose - d) / prevClose}, where prevClose is the last
 *       close before the ex-date.</li>
 * </ul>
 */
public final class CorporateActions {

    public enum Type {
        SPLIT, CASH_DIVIDEND
    }

    /**
     * @param exTimestamp first bar timestamp trading on the adjusted basis
     * @param value       split ratio (2 = 2-for-1) or cash dividend per share
     */
    public record CorporateAction(long exTimestamp, Type type, double value) {

        public CorporateAction {
            if (value <= 0) {
                throw new IllegalArgumentException("action value must be positive");
            }
        }
    }

    private CorporateActions() {
    }

    /** Returns a new back-adjusted series; the input is untouched. */
    public static BarSeries adjust(BarSeries series, List<CorporateAction> actions) {
        int n = series.size();
        double[] priceFactor = new double[n];
        double[] volumeFactor = new double[n];
        java.util.Arrays.fill(priceFactor, 1.0);
        java.util.Arrays.fill(volumeFactor, 1.0);

        List<CorporateAction> sorted = new ArrayList<>(actions);
        sorted.sort(Comparator.comparingLong(CorporateAction::exTimestamp));

        for (CorporateAction action : sorted) {
            int exIndex = firstIndexAtOrAfter(series, action.exTimestamp());
            if (exIndex <= 0) {
                continue;   // no bars before the ex-date: nothing to adjust
            }
            double factor;
            double volFactor = 1;
            if (action.type() == Type.SPLIT) {
                factor = 1 / action.value();
                volFactor = action.value();
            } else {
                double prevClose = series.close(exIndex - 1);
                if (action.value() >= prevClose) {
                    throw new IllegalArgumentException(
                            "dividend " + action.value() + " >= prior close " + prevClose);
                }
                factor = (prevClose - action.value()) / prevClose;
            }
            for (int i = 0; i < exIndex; i++) {
                priceFactor[i] *= factor;
                volumeFactor[i] *= volFactor;
            }
        }

        BarSeries.Builder builder = BarSeries.builder(series.symbol());
        for (int i = 0; i < n; i++) {
            Bar bar = series.bar(i);
            builder.add(bar.timestamp(),
                    bar.open() * priceFactor[i],
                    bar.high() * priceFactor[i],
                    bar.low() * priceFactor[i],
                    bar.close() * priceFactor[i],
                    bar.volume() * volumeFactor[i]);
        }
        return builder.build();
    }

    private static int firstIndexAtOrAfter(BarSeries series, long timestamp) {
        for (int i = 0; i < series.size(); i++) {
            if (series.timestamp(i) >= timestamp) {
                return i;
            }
        }
        return series.size();
    }
}
