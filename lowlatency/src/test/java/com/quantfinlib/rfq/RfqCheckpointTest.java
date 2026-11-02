package com.quantfinlib.rfq;

import com.quantfinlib.persist.Checkpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RFQ dealer-panel checkpoints (moved from core {@code persist.CheckpointTest} in 4.0). */
class RfqCheckpointTest {

    @TempDir
    Path dir;

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

}
