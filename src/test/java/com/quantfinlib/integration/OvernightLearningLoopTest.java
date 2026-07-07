package com.quantfinlib.integration;

import com.quantfinlib.execution.AdaptiveSor;
import com.quantfinlib.execution.BenchmarkExecutor;
import com.quantfinlib.execution.BenchmarkExecutor.Benchmark;
import com.quantfinlib.execution.VenueQuote;
import com.quantfinlib.execution.VenueScorecard;
import com.quantfinlib.microstructure.KylesLambda;
import com.quantfinlib.microstructure.OnlineAlphaLearner;
import com.quantfinlib.microstructure.SignalEngine;
import com.quantfinlib.microstructure.SpreadForecaster;
import com.quantfinlib.microstructure.VolatilityCurve;
import com.quantfinlib.microstructure.VolumeCurve;
import com.quantfinlib.orderbook.Side;
import com.quantfinlib.persist.Checkpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The multi-day session simulator: five synthetic trading days flow
 * through models → executor → router → scorecards, and every night the
 * learned state crosses the overnight through a {@code Checkpoint} into
 * FRESH instances. Each piece is unit-tested elsewhere; this is the one
 * test that proves the LOOP — curves converge to the planted shapes,
 * the alpha learner's IC gate opens only because the signal is real, the
 * toxic venue is found, and nothing is lost or poisoned at the
 * write/restore seam.
 */
class OvernightLearningLoopTest {

    private static final int BUCKETS = 26;             // 15-min buckets
    private static final int QUOTES_PER_BUCKET = 24;
    private static final int DAYS = 5;
    private static final long DT = 1_000_000_000L;     // 1s between quotes
    private static final int CLEAN = 0;
    private static final int TOXIC = 1;

    @TempDir
    Path dir;

    @Test
    void fiveDaysOfLearningSurviveFourOvernights() throws IOException {
        Random rnd = new Random(42);
        Path eod = dir.resolve("eod.qflc");

        // Fresh instances each morning — state must arrive via the file.
        VolumeCurve volume = new VolumeCurve(BUCKETS, 0.3);
        VolatilityCurve vol = new VolatilityCurve(BUCKETS, 0.3);
        SpreadForecaster spread = new SpreadForecaster(BUCKETS, 0.3, 5 * DT);
        OnlineAlphaLearner learner = new OnlineAlphaLearner(0.05, 1e-4, 0.01);
        KylesLambda kyle = new KylesLambda(0.02);
        VenueScorecard venues = new VenueScorecard(2, 0.05, 0.95, 2 * DT);

        double mid = 100.0;
        for (int day = 0; day < DAYS; day++) {
            if (day > 0) {
                // Morning: brand-new objects, state restored from last night.
                volume = new VolumeCurve(BUCKETS, 0.3);
                vol = new VolatilityCurve(BUCKETS, 0.3);
                spread = new SpreadForecaster(BUCKETS, 0.3, 5 * DT);
                learner = new OnlineAlphaLearner(0.05, 1e-4, 0.01);
                kyle = new KylesLambda(0.02);
                venues = new VenueScorecard(2, 0.05, 0.95, 2 * DT);
                var r = Checkpoint.reader(eod);
                assertTrue(r.section("volume", volume::readState));
                assertTrue(r.section("vol", vol::readState));
                assertTrue(r.section("spread", spread::readState));
                assertTrue(r.section("alpha", learner::readState));
                assertTrue(r.section("kyle", kyle::readState));
                assertTrue(r.section("venues", venues::readState));
                assertEquals(day, volume.daysLearned(), "learning is continuous");
            }
            SignalEngine sig = new SignalEngine(1);    // intraday: rebuilt daily
            AdaptiveSor sor = new AdaptiveSor(venues);
            sor.register("CLEAN", CLEAN);
            sor.register("TOXIC", TOXIC);
            BenchmarkExecutor exec = BenchmarkExecutor.of(Side.BUY, 60_000, Benchmark.VWAP);

            double prevMid = mid;
            double plantedQi = 0;          // the CURRENT book's imbalance
            long cleanQty = 0;
            long toxicQty = 0;
            long ts = day * 1_000_000L * DT;
            for (int b = 0; b < BUCKETS; b++) {
                double edge = (b - (BUCKETS - 1) / 2.0) / ((BUCKETS - 1) / 2.0);
                double sigma = 1e-4 * (1 + edge * edge);         // U-shaped vol
                double halfSpread = 0.005 * (1 + edge * edge);   // U-shaped spread

                for (int q = 0; q < QUOTES_PER_BUCKET; q++) {
                    ts += DT;
                    // Planted alpha with honest causality: the PREVIOUS quote's
                    // imbalance leans THIS interval's return — exactly the
                    // alignment trainFrom's snapshot mechanism assumes.
                    double flow = Math.round(rnd.nextGaussian() * 800);
                    double ret = 0.4 * plantedQi * sigma + sigma * rnd.nextGaussian();
                    double newMid = mid * (1 + ret) + 1e-5 * flow;

                    double qi = rnd.nextDouble() * 2 - 1;
                    plantedQi = qi;
                    long bidSz = Math.round(600 * (1 + qi)) + 1;
                    long askSz = Math.round(600 * (1 - qi)) + 1;

                    sig.onQuote(0, newMid - halfSpread, bidSz, newMid + halfSpread,
                            askSz, ts);
                    sig.onTrade(0, flow > 0, Math.max(1, (long) Math.abs(flow)), ts);
                    learner.trainFrom(sig, 0, (newMid - mid) / mid);
                    kyle.onSample(newMid - mid, flow == 0 ? 1 : flow);
                    volume.onVolume(b, 500 + Math.round(1500 * edge * edge));
                    vol.onVol(b, sig.volPerSqrtSecond(0));
                    spread.onSpread(b, 2 * halfSpread, ts);
                    venues.onMid(newMid, ts);
                    mid = newMid;
                }

                // One routed child per bucket, worked toward VWAP.
                var m = new BenchmarkExecutor.MarketState(mid, 2 * halfSpread,
                        vol.regime(b, sig.volPerSqrtSecond(0)), 50_000,
                        volume.expectedFractionElapsed(b, 1.0), sig.alpha(0),
                        kyle.impactBps(2_000, mid));
                long due = exec.dueQuantity((b + 1.0) / BUCKETS, m);
                if (due > 0) {
                    // Alternate the listing order so pure ties alternate too:
                    // both venues must earn a markout history before the
                    // adverse-selection term can separate them.
                    var cleanQuote = new VenueQuote("CLEAN", mid - halfSpread, 25_000,
                            mid + halfSpread, 25_000, 0, 100_000, false);
                    var toxicQuote = new VenueQuote("TOXIC", mid - halfSpread, 25_000,
                            mid + halfSpread, 25_000, 0, 100_000, false);
                    var plan = sor.route(Side.BUY, due, (b & 1) == 0
                            ? List.of(cleanQuote, toxicQuote)
                            : List.of(toxicQuote, cleanQuote));
                    for (var leg : plan.lit()) {
                        boolean toxic = leg.venue().equals("TOXIC");
                        venues.onFill(toxic ? TOXIC : CLEAN, 100_000, true, mid, ts);
                        exec.onFill(leg.quantity());
                        if (toxic) {
                            toxicQty += leg.quantity();
                        } else {
                            cleanQty += leg.quantity();
                        }
                        // The planted toxicity: fills at TOXIC revert.
                        mid += toxic ? -0.01 : +0.002;
                    }
                }
                exec.onMarketVolume(12_000);
            }
            assertTrue(exec.executed() > 30_000,
                    "day " + day + " parent mostly worked: " + exec.executed());
            if (day == DAYS - 1) {
                // Routing genuinely abandons the toxic venue: by the final
                // day its restored adverse markout loses EVERY tie.
                assertEquals(0, toxicQty, "no flow to the fading venue on day 5");
                assertTrue(cleanQty > 0, "the clean venue carries the day");
            }

            volume.rollDay();
            vol.rollDay();
            spread.rollDay();
            try (var w = Checkpoint.writer(eod)) {
                w.section("volume", volume::writeState)
                 .section("vol", vol::writeState)
                 .section("spread", spread::writeState)
                 .section("alpha", learner::writeState)
                 .section("kyle", kyle::writeState)
                 .section("venues", venues::writeState);
            }
            assertEquals(mid, prevMid, 25, "the walk stayed sane");
        }

        // ---- After five days: the loop actually learned. ----
        assertEquals(DAYS, volume.daysLearned());
        assertTrue(volume.profileVolume(0) > 2 * volume.profileVolume(BUCKETS / 2),
                "U-shaped volume learned: open " + volume.profileVolume(0)
                        + " vs lunch " + volume.profileVolume(BUCKETS / 2));
        assertTrue(vol.baseline(0) > vol.baseline(BUCKETS / 2),
                "U-shaped vol learned");
        assertTrue(spread.baseline(0) > spread.baseline(BUCKETS / 2),
                "U-shaped spread learned");

        assertTrue(learner.outOfSampleIC() > 0.05,
                "the planted alpha earned its IC across restores: "
                        + learner.outOfSampleIC());
        assertTrue(learner.normalizedPrediction(1, 0, 0, 0) > 0,
                "the gate opened on real signal");

        assertEquals(1e-5, kyle.lambda(), 5e-6,
                "planted depth recovered through the overnights: " + kyle.lambda());

        assertTrue(venues.postFillMarkout(TOXIC) < venues.postFillMarkout(CLEAN),
                "toxicity found: TOXIC " + venues.postFillMarkout(TOXIC)
                        + " vs CLEAN " + venues.postFillMarkout(CLEAN));
        assertTrue(venues.postFillMarkout(TOXIC) < 0, "and it is genuinely adverse");

        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "one checkpoint file, no temp debris");
        }

        // The negative control: a learner fed pure noise through the same
        // five days must NOT have earned trust.
        OnlineAlphaLearner noise = new OnlineAlphaLearner(0.05, 1e-4, 0.01);
        Random nr = new Random(9);
        for (int i = 0; i < DAYS * BUCKETS * QUOTES_PER_BUCKET; i++) {
            noise.train(nr.nextDouble() * 2 - 1, nr.nextDouble() * 2 - 1,
                    nr.nextDouble() * 2 - 1, nr.nextDouble() * 2 - 1,
                    1e-4 * nr.nextGaussian());
        }
        assertTrue(Math.abs(noise.outOfSampleIC()) < 0.1,
                "noise earns nothing: " + noise.outOfSampleIC());
    }
}
