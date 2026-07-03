package com.quantfinlib.microstructure;

/**
 * Temporary / permanent market impact models for large trades, parameterized
 * by average daily volume (ADV) and daily volatility:
 *
 * <ul>
 *   <li><b>Square-root law</b> (empirical standard):
 *       {@code impact = Y * sigma_daily * sqrt(Q / ADV)}.</li>
 *   <li><b>Almgren-Chriss style decomposition</b>: linear temporary impact in
 *       participation rate and linear permanent impact in size, with the
 *       expected cost of an execution schedule
 *       {@code E[cost] = permanent/2 + temporary}.</li>
 * </ul>
 *
 * All results in basis points of the arrival price. Coefficients default to
 * commonly cited magnitudes; calibrate per market with {@link #withCoefficients}.
 */
public final class MarketImpactModel {

    private final double adv;
    private final double dailyVolatility;
    private final double y;          // square-root law coefficient (~1)
    private final double etaBps;     // temporary impact bps at 100% participation
    private final double gamma;      // permanent impact coefficient

    public MarketImpactModel(double adv, double dailyVolatility) {
        this(adv, dailyVolatility, 1.0, 20.0, 0.5);
    }

    private MarketImpactModel(double adv, double dailyVolatility, double y, double etaBps, double gamma) {
        if (adv <= 0) {
            throw new IllegalArgumentException("adv must be positive");
        }
        this.adv = adv;
        this.dailyVolatility = dailyVolatility;
        this.y = y;
        this.etaBps = etaBps;
        this.gamma = gamma;
    }

    /** Returns a copy with calibrated coefficients (square-root Y, temporary eta bps, permanent gamma). */
    public MarketImpactModel withCoefficients(double y, double etaBps, double gamma) {
        return new MarketImpactModel(adv, dailyVolatility, y, etaBps, gamma);
    }

    /** Square-root-law total impact for an order of {@code quantity}. */
    public double squareRootImpactBps(double quantity) {
        return y * dailyVolatility * Math.sqrt(quantity / adv) * 1e4;
    }

    /** Temporary (execution-rate) impact at the given participation rate in [0, 1]. */
    public double temporaryImpactBps(double participationRate) {
        return etaBps * participationRate;
    }

    /** Permanent (information) impact, linear in size relative to ADV. */
    public double permanentImpactBps(double quantity) {
        return gamma * dailyVolatility * (quantity / adv) * 1e4;
    }

    /**
     * Expected implementation cost of executing {@code quantity} at the given
     * participation rate: half the permanent impact (average price concession
     * over the schedule) plus the full temporary impact.
     */
    public double expectedCostBps(double quantity, double participationRate) {
        return permanentImpactBps(quantity) / 2 + temporaryImpactBps(participationRate);
    }
}
