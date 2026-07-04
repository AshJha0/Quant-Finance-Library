package com.quantfinlib.data;

import com.quantfinlib.core.BarSeries;
import com.quantfinlib.data.CorporateActions.CorporateAction;
import com.quantfinlib.data.CorporateActions.Type;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorporateActionsTest {

    /** Ten flat bars at 100, timestamps 0..9. */
    private static BarSeries flatSeries() {
        BarSeries.Builder b = BarSeries.builder("EQ");
        for (int i = 0; i < 10; i++) {
            b.add(i, 100, 101, 99, 100, 1_000);
        }
        return b.build();
    }

    @Test
    void splitHalvesPricesAndDoublesVolumeBeforeExDate() {
        BarSeries adjusted = CorporateActions.adjust(flatSeries(),
                List.of(new CorporateAction(5, Type.SPLIT, 2)));

        for (int i = 0; i < 5; i++) {
            assertEquals(50, adjusted.close(i), 1e-12);
            assertEquals(50.5, adjusted.high(i), 1e-12);
            assertEquals(2_000, adjusted.volume(i), 1e-12);
        }
        for (int i = 5; i < 10; i++) {
            assertEquals(100, adjusted.close(i), 1e-12);
            assertEquals(1_000, adjusted.volume(i), 1e-12);
        }
        // No artificial return across the ex-date on a flat (adjusted) tape...
        // the raw tape here is flat, so the adjusted one jumps 50 -> 100 only
        // because the raw data did not actually drop at the split. With a real
        // split the raw price halves at ex-date; simulate that:
        BarSeries.Builder real = BarSeries.builder("EQ");
        for (int i = 0; i < 5; i++) {
            real.add(i, 100, 100, 100, 100, 1_000);
        }
        for (int i = 5; i < 10; i++) {
            real.add(i, 50, 50, 50, 50, 2_000);
        }
        BarSeries realAdjusted = CorporateActions.adjust(real.build(),
                List.of(new CorporateAction(5, Type.SPLIT, 2)));
        // Continuous series: no fake -50% return across the split.
        for (int i = 0; i < 10; i++) {
            assertEquals(50, realAdjusted.close(i), 1e-12);
        }
    }

    @Test
    void dividendAppliesPriceOnlyFactor() {
        // $2 dividend off a $100 prior close: pre-ex prices scale by 0.98.
        BarSeries adjusted = CorporateActions.adjust(flatSeries(),
                List.of(new CorporateAction(5, Type.CASH_DIVIDEND, 2)));
        assertEquals(98, adjusted.close(4), 1e-12);
        assertEquals(1_000, adjusted.volume(4), 1e-12);   // volume untouched
        assertEquals(100, adjusted.close(5), 1e-12);
    }

    @Test
    void multipleActionsCompose() {
        BarSeries adjusted = CorporateActions.adjust(flatSeries(), List.of(
                new CorporateAction(3, Type.CASH_DIVIDEND, 2),   // 0.98 before t=3
                new CorporateAction(7, Type.SPLIT, 2)));          // 0.5 before t=7
        assertEquals(100 * 0.98 * 0.5, adjusted.close(2), 1e-12);
        assertEquals(100 * 0.5, adjusted.close(5), 1e-12);
        assertEquals(100, adjusted.close(8), 1e-12);
        assertEquals(2_000, adjusted.volume(2), 1e-12);   // split volume factor only
    }

    @Test
    void edgeCasesAreHandled() {
        // Action before all bars: nothing to adjust.
        BarSeries untouched = CorporateActions.adjust(flatSeries(),
                List.of(new CorporateAction(0, Type.SPLIT, 2)));
        assertEquals(100, untouched.close(0), 1e-12);

        // Dividend exceeding the prior close is rejected.
        assertThrows(IllegalArgumentException.class, () ->
                CorporateActions.adjust(flatSeries(),
                        List.of(new CorporateAction(5, Type.CASH_DIVIDEND, 150))));
        // Original series is never mutated.
        BarSeries original = flatSeries();
        CorporateActions.adjust(original, List.of(new CorporateAction(5, Type.SPLIT, 4)));
        assertEquals(100, original.close(0), 1e-12);
        assertTrue(original.volume(0) == 1_000);
    }
}
