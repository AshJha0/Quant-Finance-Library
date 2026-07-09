package com.quantfinlib.persist;

import com.quantfinlib.execution.VenueScorecard;
import com.quantfinlib.fx.LpScorecard;
import com.quantfinlib.microstructure.LeadLagEstimator;
import com.quantfinlib.microstructure.OnlineAlphaLearner;
import com.quantfinlib.microstructure.SpreadForecaster;
import com.quantfinlib.microstructure.VolatilityCurve;
import com.quantfinlib.microstructure.VolumeCurve;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Multi-day persistence: learned state survives, intraday state does not. */
class CheckpointTest {

    @TempDir
    Path dir;

    // ------------------------------------------------------------------
    // Round trips
    // ------------------------------------------------------------------

    @Test
    void seasonalityTrioSurvivesTheOvernight() throws IOException {
        // Learn two days on each curve.
        VolumeCurve volume = new VolumeCurve(2, 0.5);
        volume.onVolume(0, 100);
        volume.onVolume(1, 300);
        volume.rollDay();
        VolatilityCurve vol = new VolatilityCurve(2, 0.5);
        vol.onVol(0, 2e-4);
        vol.onVol(1, 5e-5);
        vol.rollDay();
        SpreadForecaster spread = new SpreadForecaster(2, 0.5, 5_000_000_000L);
        spread.onSpread(0, 0.02, 0);
        spread.onSpread(1, 0.01, 1);
        spread.rollDay();
        // Leave intraday residue that must NOT survive the restore.
        volume.onVolume(0, 999);
        spread.onSpread(0, 5.0, 2);            // huge live deviation

        Path file = dir.resolve("eod.qflc");
        try (var w = Checkpoint.writer(file)) {
            w.section("volume", volume::writeState)
             .section("vol", vol::writeState)
             .section("spread", spread::writeState);
        }

        VolumeCurve volume2 = new VolumeCurve(2, 0.5);
        VolatilityCurve vol2 = new VolatilityCurve(2, 0.5);
        SpreadForecaster spread2 = new SpreadForecaster(2, 0.5, 5_000_000_000L);
        var r = Checkpoint.reader(file);
        assertTrue(r.section("volume", volume2::readState));
        assertTrue(r.section("vol", vol2::readState));
        assertTrue(r.section("spread", spread2::readState));

        assertEquals(100, volume2.profileVolume(0), 1e-9, "learned profile restored");
        assertEquals(300, volume2.profileVolume(1), 1e-9);
        assertEquals(1, volume2.daysLearned());
        assertEquals(0, volume2.realizedToday(), 1e-9, "intraday state is fresh");
        assertEquals(volume.expectedFractionElapsed(0, 1.0),
                volume2.expectedFractionElapsed(0, 1.0), 1e-12, "prefix sums rebuilt");

        assertEquals(2e-4, vol2.baseline(0), 1e-12);
        assertEquals(0.5, vol2.regime(1, 7.5e-5), 1e-12, "regime works off restored baseline");

        assertEquals(0.02, spread2.baseline(0), 1e-12);
        assertEquals(0.02, spread2.forecast(0, 100), 1e-12,
                "no leftover deviation from yesterday's spike");
    }

    @Test
    void learnerKeepsWeightsAndEarnedICTrust() throws IOException {
        OnlineAlphaLearner learner = new OnlineAlphaLearner();
        Random rnd = new Random(5);
        for (int i = 0; i < 20_000; i++) {
            double qi = rnd.nextDouble() * 2 - 1;
            learner.train(qi, 0, 0, 0, 0.5 * qi + 0.05 * rnd.nextGaussian());
        }
        Path file = dir.resolve("alpha.qflc");
        try (var w = Checkpoint.writer(file)) {
            w.section("alpha", learner::writeState);
        }

        OnlineAlphaLearner restored = new OnlineAlphaLearner();
        assertTrue(Checkpoint.reader(file).section("alpha", restored::readState));
        assertEquals(learner.weight(0), restored.weight(0), 0.0, "weights bit-exact");
        assertEquals(learner.outOfSampleIC(), restored.outOfSampleIC(), 0.0,
                "the IC evidence travels with the weights");
        assertEquals(learner.samples(), restored.samples());
        assertEquals(learner.normalizedPrediction(1, 0, 0, 0),
                restored.normalizedPrediction(1, 0, 0, 0), 0.0,
                "restored trust: still emits signal without retraining");
    }

    @Test
    void leadLagKeepsCorrelationsButNotTheOvernightRing() throws IOException {
        LeadLagEstimator ll = new LeadLagEstimator(5, 0.01);
        Random rnd = new Random(13);
        double[] hist = new double[3];
        for (int i = 0; i < 5_000; i++) {
            double lead = 1e-4 * rnd.nextGaussian();
            System.arraycopy(hist, 0, hist, 1, hist.length - 1);
            hist[0] = lead;
            ll.onSample(lead, 0.5 * hist[2] + 1e-5 * rnd.nextGaussian());
        }
        Path file = dir.resolve("leadlag.qflc");
        try (var w = Checkpoint.writer(file)) {
            w.section("eurusd-eurjpy", ll::writeState);
        }

        LeadLagEstimator restored = new LeadLagEstimator(5, 0.01);
        assertTrue(Checkpoint.reader(file).section("eurusd-eurjpy", restored::readState));
        assertEquals(2, restored.bestLag(), "the learned lead survives");
        double lag2 = restored.correlationAtLag(2);
        assertEquals(ll.correlationAtLag(2), lag2, 0.0);
        assertEquals(0, restored.expectedFollowerReturn(), 0.0,
                "ring is fresh: no prediction from yesterday's pre-close returns");
        // The empty ring must never dilute the restored moments: with only
        // two post-restore samples, lag 2 has no fresh (leader, follower)
        // pair yet, so its persisted correlation is untouched — not dragged
        // toward zero by fabricated pairs against the zeroed ring.
        restored.onSample(1e-4, 1e-4);
        restored.onSample(-1e-4, 1e-4);
        assertEquals(lag2, restored.correlationAtLag(2), 0.0,
                "lag 2 resumes only once the ring holds 3 fresh samples");
    }

    @Test
    void scorecardsDoNotRelearnVenueQualityEveryMorning() throws IOException {
        VenueScorecard venues = new VenueScorecard(2, 0.05, 0.95);
        for (int i = 0; i < 50; i++) {
            venues.onFill(0, 100_000);
            venues.onMiss(1, 300_000);
        }
        venues.onDarkProbe(1, 4_000);
        LpScorecard lps = new LpScorecard(2, 0.05, 100);
        for (int i = 0; i < 50; i++) {
            lps.onFill(0, true, 1.08502, 1.08501, 40_000_000L);
            lps.onReject(1, true, 1.08501, i * 1_000L, 90_000_000L);
        }
        lps.onMid(1.08520, 10_000_000_000L);   // mature the markouts

        Path file = dir.resolve("venues.qflc");
        try (var w = Checkpoint.writer(file)) {
            w.section("venues", venues::writeState)
             .section("lps", lps::writeState);
        }

        VenueScorecard venues2 = new VenueScorecard(2, 0.05, 0.95);
        LpScorecard lps2 = new LpScorecard(2, 0.05, 100);
        var r = Checkpoint.reader(file);
        assertTrue(r.section("venues", venues2::readState));
        assertTrue(r.section("lps", lps2::readState));

        assertEquals(venues.fillRate(0), venues2.fillRate(0), 0.0);
        assertEquals(venues.fillRate(1), venues2.fillRate(1), 0.0);
        assertEquals(venues.measuredLatencyNanos(1), venues2.measuredLatencyNanos(1), 0.0);
        assertEquals(venues.expectedHiddenShares(1), venues2.expectedHiddenShares(1), 0.0);
        assertEquals(venues.sent(0), venues2.sent(0));

        assertEquals(lps.rejectRate(1), lps2.rejectRate(1), 0.0);
        assertEquals(lps.postRejectMarkout(1), lps2.postRejectMarkout(1), 0.0);
        assertEquals(lps.effectiveSpread(0), lps2.effectiveSpread(0), 0.0);
        long matured = lps2.maturedMarkouts();
        lps2.onMid(2.0, Long.MAX_VALUE);       // would mature any leftover pending
        assertEquals(matured, lps2.maturedMarkouts(),
                "pending (intraday) markouts do not cross the overnight");
    }

    @Test
    void covarianceMatrixSurvivesTheOvernight() throws IOException {
        var cov = new com.quantfinlib.microstructure.EwmaCovariance(2, 0.94);
        cov.onReturns(new double[]{1e-4, 1e-4});
        cov.onReturns(new double[]{-1e-4, -2e-4});
        Path file = dir.resolve("cov.qflc");
        try (var w = Checkpoint.writer(file)) {
            w.section("basket", cov::writeState);
        }
        var restored = new com.quantfinlib.microstructure.EwmaCovariance(2, 0.94);
        assertTrue(Checkpoint.reader(file).section("basket", restored::readState));
        assertEquals(cov.covariance(0, 1), restored.covariance(0, 1), 0.0);
        assertEquals(cov.samples(), restored.samples());
        var wrongSize = new com.quantfinlib.microstructure.EwmaCovariance(3, 0.94);
        assertThrows(IOException.class,
                () -> Checkpoint.reader(file).section("basket", wrongSize::readState));
    }

    @Test
    void ensembleTrustAndDealerPanelSurviveTheOvernight() throws IOException {
        var ens = new com.quantfinlib.microstructure.AlphaEnsemble(2, 0.5);
        ens.onObservation(new double[]{0.5, -0.2}, 0);
        ens.onObservation(new double[]{0.6, 0.1}, 1e-4);
        ens.onObservation(new double[]{-0.3, 0.4}, 2e-4);
        var panel = new com.quantfinlib.rfq.RfqDealerScorecard(2, 0.5);
        var rfq = new com.quantfinlib.rfq.RfqAuction(true, 1_000_000, 2, 0);
        rfq.onQuote(0, 1_005_000, 1_000);
        panel.onAuction(rfq);

        Path file = dir.resolve("trust.qflc");
        try (var w = Checkpoint.writer(file)) {
            w.section("ensemble", ens::writeState).section("panel", panel::writeState);
        }
        var ens2 = new com.quantfinlib.microstructure.AlphaEnsemble(2, 0.5);
        var panel2 = new com.quantfinlib.rfq.RfqDealerScorecard(2, 0.5);
        var r = Checkpoint.reader(file);
        assertTrue(r.section("ensemble", ens2::readState));
        assertTrue(r.section("panel", panel2::readState));
        assertEquals(ens.componentIC(0), ens2.componentIC(0), 0.0);
        assertEquals(ens.samples(), ens2.samples());
        assertEquals(panel.avgSpreadToFairBps(0), panel2.avgSpreadToFairBps(0), 0.0);
        assertEquals(panel.quoteRate(1), panel2.quoteRate(1), 0.0);
        var wrongSize = new com.quantfinlib.microstructure.AlphaEnsemble(3, 0.5);
        assertThrows(IOException.class,
                () -> Checkpoint.reader(file).section("ensemble", wrongSize::readState));
    }

    @Test
    void lpScorecardStillReadsItsPreSeedingV1Format() throws IOException {
        // A v1.8.0-era LpScorecard checkpoint has no per-LP markout counts;
        // a restored nonzero markout EWMA must count as already-seeded so
        // the next matured markout BLENDS instead of re-seeding over it.
        Path file = dir.resolve("v1lps.qflc");
        try (var w = Checkpoint.writer(file)) {
            w.section("lps", out -> {
                out.writeByte(1);
                Checkpoint.writeLongs(out, new long[]{10, 0});    // attempts
                Checkpoint.writeLongs(out, new long[]{8, 0});     // fills
                Checkpoint.writeLongs(out, new long[]{2, 0});     // rejects
                Checkpoint.writeDoubles(out, new double[]{0.2, 0});
                Checkpoint.writeDoubles(out, new double[]{5e7, 0});
                Checkpoint.writeDoubles(out, new double[]{1e-5, 0});
                Checkpoint.writeDoubles(out, new double[]{0.0004, 0}); // markout
                out.writeLong(2);                                 // matured
            });
        }
        LpScorecard card = new LpScorecard(2, 0.5, 100);
        assertTrue(Checkpoint.reader(file).section("lps", card::readState));
        assertEquals(0.0004, card.postRejectMarkout(0), 0.0, "v1 markout restored");
        // A new matured markout must blend at alpha, not stomp the history.
        card.onReject(0, true, 1.0000, 0, 10);
        card.onMid(1.0010, 1_000);                    // move +0.0010
        assertEquals(0.0004 + 0.5 * (0.0010 - 0.0004), card.postRejectMarkout(0), 1e-12,
                "restored EWMA counts as seeded");
    }

    @Test
    void venueScorecardStillReadsItsPreMarkoutV1Format() throws IOException {
        // A v1.8.0-era checkpoint has no markout fields; restoring it must
        // recover everything it holds and leave the markout state cold.
        Path file = dir.resolve("v1card.qflc");
        try (var w = Checkpoint.writer(file)) {
            w.section("venues", out -> {
                out.writeByte(1);                          // the old format version
                Checkpoint.writeLongs(out, new long[]{10, 0});   // sent
                Checkpoint.writeLongs(out, new long[]{9, 0});    // filled
                Checkpoint.writeLongs(out, new long[]{3, 0});    // probes
                Checkpoint.writeDoubles(out, new double[]{0.9, 0});   // fillRate
                Checkpoint.writeDoubles(out, new double[]{50_000, 0}); // latency
                Checkpoint.writeDoubles(out, new double[]{4_000, 0}); // hidden
            });
        }
        VenueScorecard card = new VenueScorecard(2, 0.05, 0.95);
        assertTrue(Checkpoint.reader(file).section("venues", card::readState));
        assertEquals(0.9, card.fillRate(0), 0.0, "v1 fields restored");
        assertEquals(10, card.sent(0));
        assertEquals(0, card.postFillMarkout(0), 0.0, "markout starts cold from v1");
        assertEquals(0, card.maturedFillMarkouts());
    }

    // ------------------------------------------------------------------
    // Safety properties
    // ------------------------------------------------------------------

    @Test
    void configMismatchThrowsInsteadOfMisaligning() throws IOException {
        VolumeCurve four = new VolumeCurve(4, 0.5);
        four.rollDay();
        Path file = dir.resolve("mismatch.qflc");
        try (var w = Checkpoint.writer(file)) {
            w.section("volume", four::writeState);
        }
        VolumeCurve five = new VolumeCurve(5, 0.5);
        assertThrows(IOException.class,
                () -> Checkpoint.reader(file).section("volume", five::readState));
    }

    @Test
    void unknownStateVersionThrows() throws IOException {
        Path file = dir.resolve("future.qflc");
        try (var w = Checkpoint.writer(file)) {
            w.section("volume", out -> out.writeByte(99));
        }
        VolumeCurve curve = new VolumeCurve(2, 0.5);
        assertThrows(IOException.class,
                () -> Checkpoint.reader(file).section("volume", curve::readState));
    }

    @Test
    void missingSectionIsAColdStartNotACrash() throws IOException {
        Path file = dir.resolve("partial.qflc");
        try (var w = Checkpoint.writer(file)) {
            w.section("volume", new VolumeCurve(2, 0.5)::writeState);
        }
        var r = Checkpoint.reader(file);
        VolatilityCurve untouched = new VolatilityCurve(2, 0.5);
        assertFalse(r.section("vol", untouched::readState), "absent section reports false");
        assertEquals(0, untouched.daysLearned(), "model untouched");
        assertEquals(1, r.names().size());
        assertTrue(r.names().contains("volume"));
    }

    @Test
    void unconsumedBytesAreAFormatDriftNotASilentSkip() throws IOException {
        VolumeCurve curve = new VolumeCurve(2, 0.5);
        curve.rollDay();
        Path file = dir.resolve("drift.qflc");
        try (var w = Checkpoint.writer(file)) {
            w.section("volume", out -> {
                curve.writeState(out);
                out.writeInt(42);              // a future field this build doesn't read
            });
        }
        VolumeCurve reader = new VolumeCurve(2, 0.5);
        assertThrows(IOException.class,
                () -> Checkpoint.reader(file).section("volume", reader::readState));
    }

    @Test
    void duplicateSectionNamesAreRejected() throws IOException {
        try (var w = Checkpoint.writer(dir.resolve("dup.qflc"))) {
            w.section("x", out -> out.writeByte(1));
            assertThrows(IOException.class, () -> w.section("x", out -> out.writeByte(1)));
        }
    }

    @Test
    void commitIsAtomicReplaceWithNoTempLeftover() throws IOException {
        Path file = dir.resolve("eod.qflc");
        for (int day = 0; day < 2; day++) {
            VolumeCurve curve = new VolumeCurve(2, 0.5);
            curve.onVolume(0, 100 * (day + 1));
            curve.rollDay();
            try (var w = Checkpoint.writer(file)) {
                w.section("volume", curve::writeState);
            }
        }
        VolumeCurve restored = new VolumeCurve(2, 0.5);
        Checkpoint.reader(file).section("volume", restored::readState);
        assertEquals(200, restored.profileVolume(0), 1e-9, "second save replaced the first");
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "no temp files left behind");
        }
    }

    @Test
    void aFailedSectionCommitsNothing() throws IOException {
        Path file = dir.resolve("eod.qflc");
        try (var w = Checkpoint.writer(file)) {
            w.section("volume", new VolumeCurve(2, 0.5)::writeState);
        }
        long before = Files.getLastModifiedTime(file).toMillis();
        byte[] original = Files.readAllBytes(file);

        assertThrows(IOException.class, () -> {
            try (var w = Checkpoint.writer(file)) {
                w.section("good", out -> out.writeByte(1));
                w.section("bad", out -> {
                    throw new IOException("model blew up mid-save");
                });
            }
        });
        assertEquals(before, Files.getLastModifiedTime(file).toMillis());
        org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(file),
                "yesterday's checkpoint is untouched by a failed save");
    }

    @Test
    void garbageFilesAreRejectedLoudly() throws IOException {
        Path notACheckpoint = dir.resolve("random.bin");
        Files.write(notACheckpoint, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9});
        assertThrows(IOException.class, () -> Checkpoint.reader(notACheckpoint));
    }
}
