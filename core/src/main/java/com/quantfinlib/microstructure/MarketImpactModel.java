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

    /**
     * Estimates a model for a trade decided at bar {@code index} from the
     * {@code window} bars strictly before it: ADV is the mean volume and
     * daily volatility the population standard deviation of close-to-close
     * returns over bars {@code [index - window, index - 1]}. The trade bar's
     * own volume and return are excluded because they are not known when
     * the order is sized. This is the one bar-data to impact-model bridge,
     * shared by the alpha and portfolio backtesters.
     *
     * <p>Bars flagged {@link com.quantfinlib.core.BarSeries#isSynthetic
     * synthetic} (forward-filled alignment placeholders) are skipped in both
     * estimates: a flat zero-volume bar would otherwise shrink ADV and
     * volatility. A return ending on a real bar after a run of synthetic
     * bars spans the gap; that is kept. Returns {@code null} when fewer than
     * two real bars remain or the window has no volume (impact needs ADV;
     * absent volume is a data gap, not free liquidity, so callers charge
     * their flat costs and skip impact).</p>
     *
     * @throws IllegalArgumentException when {@code index <= window}
     */
    public static MarketImpactModel estimate(com.quantfinlib.core.BarSeries series,
                                             int index, int window) {
        if (index <= window) {
            throw new IllegalArgumentException(
                    "index " + index + " must exceed the estimation window " + window);
        }
        int last = index - 1;
        double advSum = 0;
        double sum = 0;
        double sumSq = 0;
        int real = 0;
        for (int k = last - window + 1; k <= last; k++) {
            if (series.isSynthetic(k)) {
                continue;
            }
            double r = series.close(k) / series.close(k - 1) - 1;
            advSum += series.volume(k);
            sum += r;
            sumSq += r * r;
            real++;
        }
        if (real < 2) {
            return null;
        }
        double adv = advSum / real;
        if (adv <= 0) {
            return null;
        }
        double mean = sum / real;
        double var = Math.max(0, sumSq / real - mean * mean);
        return new MarketImpactModel(adv, Math.sqrt(var));
    }

    /** Average daily volume, in shares. */
    public double adv() {
        return adv;
    }

    /** Daily return volatility (fraction). */
    public double dailyVolatility() {
        return dailyVolatility;
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
