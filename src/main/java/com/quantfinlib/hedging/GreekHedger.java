package com.quantfinlib.hedging;

import com.quantfinlib.util.MathUtils;

/**
 * Greek-neutralization: solves the hedge quantities that flatten a
 * portfolio's option Greeks using available hedge instruments — the standard
 * delta-gamma and delta-gamma-vega hedging recipes, plus a general linear
 * solver for arbitrary greek/instrument combinations.
 */
public final class GreekHedger {

    /** Per-unit Greeks of a hedge instrument (the underlying is delta=1, gamma=0, vega=0). */
    public record Instrument(String name, double delta, double gamma, double vega) {

        public static Instrument underlying(String name) {
            return new Instrument(name, 1, 0, 0);
        }
    }

    private GreekHedger() {
    }

    /**
     * Delta-gamma hedge with the underlying plus one option:
     * returns {@code [underlyingQty, optionQty]} that zero both Greeks.
     */
    public static double[] deltaGammaHedge(double portfolioDelta, double portfolioGamma,
                                           double optionDelta, double optionGamma) {
        if (optionGamma == 0) {
            throw new IllegalArgumentException("hedge option must have gamma");
        }
        double optionQty = -portfolioGamma / optionGamma;
        double underlyingQty = -(portfolioDelta + optionQty * optionDelta);
        return new double[]{underlyingQty, optionQty};
    }

    /**
     * Delta-gamma-vega hedge with the underlying plus two options:
     * returns {@code [underlyingQty, option1Qty, option2Qty]}.
     */
    public static double[] deltaGammaVegaHedge(double portfolioDelta, double portfolioGamma,
                                               double portfolioVega,
                                               Instrument option1, Instrument option2) {
        Instrument underlying = Instrument.underlying("UNDERLYING");
        return neutralize(new double[]{portfolioDelta, portfolioGamma, portfolioVega},
                new Instrument[]{underlying, option1, option2});
    }

    /**
     * General case: solves per-instrument quantities so that the summed
     * instrument Greeks exactly offset the portfolio Greeks. The number of
     * instruments must equal the number of Greeks
     * ({@code portfolioGreeks = [delta, gamma, vega][..count]}), and the
     * instrument Greeks must be linearly independent.
     */
    public static double[] neutralize(double[] portfolioGreeks, Instrument[] instruments) {
        int n = portfolioGreeks.length;
        if (instruments.length != n) {
            throw new IllegalArgumentException("need exactly " + n + " instruments for " + n + " greeks");
        }
        double[][] m = new double[n][n];
        double[] target = new double[n];
        for (int g = 0; g < n; g++) {
            target[g] = -portfolioGreeks[g];
            for (int j = 0; j < n; j++) {
                m[g][j] = greekOf(instruments[j], g);
            }
        }
        return MathUtils.solveLinear(m, target);
    }

    /** Residual portfolio Greeks after applying the hedge quantities (for verification). */
    public static double[] residualGreeks(double[] portfolioGreeks, Instrument[] instruments,
                                          double[] quantities) {
        double[] out = portfolioGreeks.clone();
        for (int g = 0; g < out.length; g++) {
            for (int j = 0; j < instruments.length; j++) {
                out[g] += quantities[j] * greekOf(instruments[j], g);
            }
        }
        return out;
    }

    private static double greekOf(Instrument instrument, int index) {
        return switch (index) {
            case 0 -> instrument.delta();
            case 1 -> instrument.gamma();
            case 2 -> instrument.vega();
            default -> throw new IllegalArgumentException("greek index " + index);
        };
    }
}
