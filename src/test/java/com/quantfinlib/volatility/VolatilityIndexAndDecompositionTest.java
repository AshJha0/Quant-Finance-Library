package com.quantfinlib.volatility;

import com.quantfinlib.pricing.BlackScholes;
import com.quantfinlib.pricing.BlackScholes.OptionType;
import com.quantfinlib.risk.RiskMetrics;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fear gauge and the systematic/idiosyncratic split. */
class VolatilityIndexAndDecompositionTest {

    // ------------------------------------------------------------------
    // VIX-style index — model-free means it must RECOVER a flat vol
    // ------------------------------------------------------------------

    @Test
    void indexRecoversAFlatVolAndReadsTheSmile() {
        // A chain priced at flat 20% vol: the variance-swap replication
        // must hand the 20% back — that is what "model-free" MEANS.
        double t = 30.0 / 365;
        int n = 81;
        double[] strikes = new double[n];
        double[] puts = new double[n];
        double[] calls = new double[n];
        for (int i = 0; i < n; i++) {
            strikes[i] = 60 + i;                  // 60..140: 8.9σ down, 5.9σ up
            // max(0, ·): the normCdf approximation can leave deep-OTM
            // prices at ~-1e-9; real chains quote >= 0 by construction.
            puts[i] = Math.max(0,
                    BlackScholes.price(OptionType.PUT, 100, strikes[i], 0, 0, 0.20, t));
            calls[i] = Math.max(0,
                    BlackScholes.price(OptionType.CALL, 100, strikes[i], 0, 0, 0.20, t));
        }
        double flat = VolatilityIndex.index(strikes, puts, calls, 100, 0, t);
        assertEquals(0.20, flat, 2e-3, "the fear gauge reads the market's own number");

        // F STRICTLY BETWEEN strikes: with F on a strike (above) the
        // (F/K0-1)^2 correction term is exactly zero and untestable —
        // dropping it would pass. At F = 100.9 the omission error is
        // ~+2.9e-3, so THIS assertion is what makes the term load-bearing
        // (chain re-priced at the shifted forward via spot = 100.9, r=0).
        double[] putsF = new double[n];
        double[] callsF = new double[n];
        for (int i = 0; i < n; i++) {
            putsF[i] = Math.max(0,
                    BlackScholes.price(OptionType.PUT, 100.9, strikes[i], 0, 0, 0.20, t));
            callsF[i] = Math.max(0,
                    BlackScholes.price(OptionType.CALL, 100.9, strikes[i], 0, 0, 0.20, t));
        }
        double offGrid = VolatilityIndex.index(strikes, putsF, callsF, 100.9, 0, t);
        assertEquals(0.20, offGrid, 2e-3,
                "the K0 != F correction term earns its keep here");

        // A put SKEW (downside priced at 25%) must RAISE the index above
        // the ATM 20% — the wings carry real premium and the replication
        // weights them in. That is why VIX > ATM implied vol.
        double[] skewedPuts = new double[n];
        for (int i = 0; i < n; i++) {
            skewedPuts[i] = Math.max(0,
                    BlackScholes.price(OptionType.PUT, 100, strikes[i], 0, 0, 0.25, t));
        }
        double skewed = VolatilityIndex.index(strikes, skewedPuts, calls, 100, 0, t);
        assertTrue(skewed > flat + 0.005 && skewed < 0.25,
                "the smile is IN the index: " + skewed);

        // Gates: extrapolation is an opinion, not a measurement.
        assertThrows(IllegalArgumentException.class,
                () -> VolatilityIndex.index(strikes, puts, calls, 150, 0, t));
        assertThrows(IllegalArgumentException.class,
                () -> VolatilityIndex.index(new double[]{90, 100}, new double[2],
                        new double[2], 95, 0, t));
        assertThrows(IllegalArgumentException.class,
                () -> VolatilityIndex.index(strikes, new double[n], new double[n],
                        100, 0, t), "an all-zero chain implies no variance: inconsistent");
    }

    // ------------------------------------------------------------------
    // Systematic vs idiosyncratic — the split is EXACT, not approximate
    // ------------------------------------------------------------------

    @Test
    void decompositionRecoversPlantedBetaAndSplitsExactly() {
        Random rnd = new Random(11);
        int n = 5_000;
        double[] market = new double[n];
        double[] asset = new double[n];
        for (int i = 0; i < n; i++) {
            market[i] = 0.02 * rnd.nextGaussian();
            asset[i] = 1.5 * market[i] + 0.01 * rnd.nextGaussian();
        }
        var d = VolatilityDecomposition.decompose(asset, market);
        assertEquals(1.5, d.beta(), 0.05, "the planted beta");
        assertEquals(RiskMetrics.beta(asset, market), d.beta(), 1e-12,
                "one beta in this library, cross-checked");
        assertEquals(1e-4, d.idiosyncraticVariance(), 1.5e-5,
                "the residual is the planted 1% noise");
        assertEquals(d.totalVariance(),
                d.systematicVariance() + d.idiosyncraticVariance(), 1e-18,
                "the OLS split is an identity, not an approximation");
        assertTrue(d.rSquared() > 0.85, "mostly a market story: " + d.rSquared());
        assertEquals(Math.sqrt(d.systematicVariance() * 252),
                d.systematicVol(252), 1e-15);

        // A clone of the market IS the market: beta 1, idio 0, R² 1.
        var clone = VolatilityDecomposition.decompose(market.clone(), market);
        assertEquals(1.0, clone.beta(), 1e-12);
        assertEquals(0.0, clone.idiosyncraticVariance(), 1e-15,
                "no company story in a market clone");
        assertEquals(1.0, clone.rSquared(), 1e-12);

        // Pure noise vs the market: nothing systematic to find.
        double[] noise = new double[n];
        for (int i = 0; i < n; i++) {
            noise[i] = 0.015 * rnd.nextGaussian();
        }
        var idio = VolatilityDecomposition.decompose(noise, market);
        assertTrue(idio.rSquared() < 0.02, "a biotech, not a utility: " + idio.rSquared());

        assertThrows(IllegalArgumentException.class,
                () -> VolatilityDecomposition.decompose(new double[30], market),
                "misaligned series");
        assertThrows(IllegalArgumentException.class,
                () -> VolatilityDecomposition.decompose(noise, new double[n]),
                "a flat benchmark decomposes nothing");
        double[] bad = asset.clone();
        bad[7] = Double.NaN;
        assertThrows(IllegalArgumentException.class,
                () -> VolatilityDecomposition.decompose(bad, market));
    }
}
