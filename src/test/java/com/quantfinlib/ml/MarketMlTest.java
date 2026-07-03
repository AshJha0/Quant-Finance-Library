package com.quantfinlib.ml;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketMlTest {

    @Test
    void sweepProbabilityIsCalibratedLogistic() {
        assertEquals(0.5, MarketImpactPredictor.sweepProbability(1_000, 1_000), 1e-12);
        assertTrue(MarketImpactPredictor.sweepProbability(100, 1_000) < 0.05);
        assertTrue(MarketImpactPredictor.sweepProbability(5_000, 1_000) > 0.99);
        assertEquals(1.0, MarketImpactPredictor.sweepProbability(100, 0), 1e-12);
    }

    @Test
    void impactPredictorLearnsSquareRootShape() {
        SplittableRandom rnd = new SplittableRandom(3);
        int n = 800;
        double[][] x = new double[n][];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double sizeVsAdv = rnd.nextDouble() * 0.1;
            double spread = 1 + rnd.nextDouble() * 4;
            double imb = 2 * rnd.nextDouble() - 1;
            double vol = 0.005 + rnd.nextDouble() * 0.02;
            x[i] = MarketImpactPredictor.features(sizeVsAdv, spread, imb, vol);
            y[i] = vol * Math.sqrt(sizeVsAdv) * 1e4 + rnd.nextDouble() * 0.3;   // sqrt law + noise
        }
        MarketImpactPredictor predictor = new MarketImpactPredictor().fit(x, y);
        // Larger order in identical conditions must predict more impact.
        double small = predictor.predictImpactBps(MarketImpactPredictor.features(0.01, 2, 0, 0.01));
        double large = predictor.predictImpactBps(MarketImpactPredictor.features(0.09, 2, 0, 0.01));
        assertTrue(large > small, "large=" + large + " small=" + small);
    }

    @Test
    void liquidityForecasterFindsSessionPeaks() {
        IntradayLiquidityForecaster f = new IntradayLiquidityForecaster(24);
        // Two days with the London/NY overlap (12-16 UTC) dominating.
        for (int day = 0; day < 2; day++) {
            double[] vols = new double[24];
            for (int h = 0; h < 24; h++) {
                vols[h] = (h >= 12 && h < 17) ? 1_000 : (h >= 7 && h < 12) ? 600 : 200;
            }
            f.addDay(vols);
        }
        int peak = f.peakBucket();
        assertTrue(peak >= 12 && peak < 17, "peak=" + peak);
        assertEquals("LONDON_NY_OVERLAP", IntradayLiquidityForecaster.fxSession(peak));
        assertEquals(1_000, f.forecastVolume(13), 1e-9);

        double[] profile = f.profile();
        double sum = 0;
        for (double p : profile) {
            sum += p;
        }
        assertEquals(1.0, sum, 1e-9);
        assertTrue(f.sessionShare(12, 17) > f.sessionShare(0, 5));
    }

    @Test
    void fxSessionLabels() {
        assertEquals("SYDNEY", IntradayLiquidityForecaster.fxSession(23));
        assertEquals("TOKYO", IntradayLiquidityForecaster.fxSession(2));
        assertEquals("LONDON", IntradayLiquidityForecaster.fxSession(9));
        assertEquals("NEW_YORK", IntradayLiquidityForecaster.fxSession(19));
    }

    @Test
    void quoteStuffingDetectionFlagsBurstWithNoTrades() {
        long[] messages = new long[60];
        long[] trades = new long[60];
        for (int i = 0; i < 60; i++) {
            messages[i] = 100;
            trades[i] = 20;
        }
        messages[30] = 10_000;    // burst of messages...
        trades[30] = 1;           // ...with no trading

        List<AnomalyDetector.Anomaly> anomalies =
                AnomalyDetector.detectQuoteStuffing(messages, trades, 3, 50);
        assertEquals(1, anomalies.size());
        assertEquals(30, anomalies.getFirst().intervalIndex());
        assertEquals(AnomalyDetector.QUOTE_STUFFING, anomalies.getFirst().type());

        // Same burst with matching trades (legit activity spike) is not stuffing.
        trades[30] = 2_000;
        assertTrue(AnomalyDetector.detectQuoteStuffing(messages, trades, 3, 50).isEmpty());
    }

    @Test
    void priceSpikeDetection() {
        double[] mids = new double[100];
        mids[0] = 100;
        for (int i = 1; i < 100; i++) {
            mids[i] = mids[i - 1] * (1 + 0.0001 * (i % 2 == 0 ? 1 : -1));
        }
        mids[50] = mids[49] * 1.02;   // 2% jump in a 1bp market

        List<AnomalyDetector.Anomaly> anomalies = AnomalyDetector.detectPriceSpikes(mids, 6);
        assertTrue(anomalies.stream().anyMatch(a -> a.intervalIndex() == 50));
    }
}
