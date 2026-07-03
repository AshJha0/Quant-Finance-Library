package com.quantfinlib.ml;

/**
 * ML market impact prediction: learns realized impact (bps) from order and
 * book features using gradient-boosted trees, and estimates the probability a
 * marketable order sweeps through the visible top of book.
 */
public final class MarketImpactPredictor {

    private final GradientBoostedRegressor model;
    private boolean fitted;

    public MarketImpactPredictor() {
        this.model = new GradientBoostedRegressor(150, 0.1);
    }

    /**
     * Standard feature vector.
     *
     * @param sizeVsAdv     order size / average daily volume
     * @param spreadBps     quoted spread in bps at arrival
     * @param bookImbalance depth imbalance in [-1, 1] (signed toward the order side)
     * @param volatility    recent per-period volatility
     */
    public static double[] features(double sizeVsAdv, double spreadBps,
                                    double bookImbalance, double volatility) {
        return new double[]{sizeVsAdv, spreadBps, bookImbalance, volatility};
    }

    /** Trains on historical (features, realized impact bps) observations. */
    public MarketImpactPredictor fit(double[][] x, double[] realizedImpactBps) {
        model.fit(x, realizedImpactBps);
        fitted = true;
        return this;
    }

    public double predictImpactBps(double[] features) {
        if (!fitted) {
            throw new IllegalStateException("call fit() first");
        }
        return model.predict(features);
    }

    /**
     * Probability a marketable order of {@code orderQty} sweeps beyond the
     * visible contra depth at the touch: logistic in the size/depth ratio,
     * 0.5 exactly when the order equals the visible depth.
     */
    public static double sweepProbability(long orderQty, long visibleContraDepth) {
        if (visibleContraDepth <= 0) {
            return 1;
        }
        double ratio = (double) orderQty / visibleContraDepth;
        return 1.0 / (1 + Math.exp(-4 * (ratio - 1)));
    }
}
