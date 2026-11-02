package com.quantfinlib.data;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.microstructure.MarketImpactModel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Forward-filled bars are flagged and excluded from decision-time impact estimates. */
class SyntheticBarTest {

    @Test
    void unionForwardFillFlagsGapsAndSlicesKeepTheFlag() {
        BarSeries a = BarSeries.builder("A").add(1, 10, 10, 10, 10, 5).add(2, 11, 11, 11, 11, 5)
                .add(3, 12, 12, 12, 12, 5).build();
        BarSeries b = BarSeries.builder("B").add(1, 20, 20, 20, 20, 5)
                .add(3, 22, 22, 22, 22, 5).build();
        Map<String, BarSeries> in = new LinkedHashMap<>();
        in.put("A", a);
        in.put("B", b);
        Map<String, BarSeries> out = SeriesAligner.unionForwardFill(in);
        BarSeries bb = out.get("B");
        assertFalse(bb.isSynthetic(0));
        assertTrue(bb.isSynthetic(1));
        assertEquals(20, bb.close(1), 0);
        assertEquals(0, bb.volume(1), 0);
        assertFalse(bb.isSynthetic(2));
        assertTrue(bb.slice(1, 3).isSynthetic(0));
        assertFalse(out.get("A").hasSyntheticBars());
    }

    @Test
    void impactEstimateUsesOnlyRealBarsBeforeTheTradeBar() {
        // Bars 0..5 real with volume 100 and +1% steps; bar 6 is the trade bar
        // with a huge volume that must not enter ADV.
        BarSeries.Builder b = BarSeries.builder("X");
        double c = 100;
        for (int i = 0; i < 6; i++) {
            b.add(i, c, c, c, c, 100);
            c *= 1.01;
        }
        b.add(6, c, c, c, c, 1_000_000);
        MarketImpactModel m = MarketImpactModel.estimate(b.build(), 6, 5);
        assertEquals(100, m.adv(), 1e-9);
        assertEquals(0, m.dailyVolatility(), 1e-12); // constant +1% returns

        // With synthetic bars in the window they are skipped.
        BarSeries.Builder g = BarSeries.builder("G");
        g.add(0, 100, 100, 100, 100, 100);
        g.add(1, 101, 101, 101, 101, 300);
        g.addSynthetic(2, 101, 101, 101, 101, 0);
        g.addSynthetic(3, 101, 101, 101, 101, 0);
        g.add(4, 102, 102, 102, 102, 500);
        g.add(5, 102, 102, 102, 102, 9_999);
        MarketImpactModel gm = MarketImpactModel.estimate(g.build(), 5, 4);
        assertEquals(400, gm.adv(), 1e-9); // (300 + 500)/2, bars 1 and 4
    }
}
