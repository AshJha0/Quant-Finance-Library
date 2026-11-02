package com.quantfinlib.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BarSeries}, {@link Bar} and the library exceptions, tested in the
 * stable module itself. Before 4.0 the CSV loader tests covered much of this
 * as a side effect; they moved to the io module with the loaders.
 */
class BarSeriesCoreTest {

    private static BarSeries three() {
        return BarSeries.builder("X")
                .add(1_000, 10, 11, 9, 10.5, 100)
                .add(new Bar(2_000, 10.5, 12, 10, 11.5, 200))
                .addSynthetic(3_000, 11.5, 11.5, 11.5, 11.5, 0)
                .build();
    }

    @Test
    void accessorsCopiesAndSlices() {
        BarSeries s = three();
        assertEquals("X", s.symbol());
        assertEquals(3, s.size());
        assertEquals(2_000, s.timestamp(1));
        assertEquals(10.5, s.open(1), 0);
        assertEquals(12, s.high(1), 0);
        assertEquals(10, s.low(1), 0);
        assertEquals(200, s.volume(1), 0);
        assertEquals(11.5, s.lastClose(), 0);
        assertEquals(new Bar(2_000, 10.5, 12, 10, 11.5, 200), s.bar(1));
        assertTrue(s.hasSyntheticBars());
        assertTrue(s.isSynthetic(2));
        assertFalse(s.isSynthetic(0));
        assertArrayEquals(new long[]{1_000, 2_000, 3_000}, s.timestampsCopy());
        assertArrayEquals(new double[]{10, 10.5, 11.5}, s.opensCopy(), 0);
        assertArrayEquals(new double[]{11, 12, 11.5}, s.highsCopy(), 0);
        assertArrayEquals(new double[]{9, 10, 11.5}, s.lowsCopy(), 0);
        assertArrayEquals(new double[]{10.5, 11.5, 11.5}, s.closesCopy(), 0);
        assertArrayEquals(new double[]{100, 200, 0}, s.volumesCopy(), 0);
        double[] dst = new double[2];
        s.copyClosesInto(dst, 1, 2);
        assertArrayEquals(new double[]{11.5, 11.5}, dst, 0);
        BarSeries tail = s.slice(1, 3);
        assertEquals(2, tail.size());
        assertEquals(11.5, tail.close(0), 0);
        assertEquals(11.5 / 10.5 - 1, s.returns()[0], 1e-15);
        assertEquals(Math.log(11.5 / 10.5), s.logReturns()[0], 1e-15);
        BarSeries fromBars = BarSeries.fromBars("Y", List.of(s.bar(0), s.bar(1)));
        assertEquals(2, fromBars.size());
        assertEquals(3, BarSeries.of("Z", new double[]{1, 2, 3}).size());
    }

    @Test
    void barHelpers() {
        Bar b = new Bar(0, 10, 12, 9, 11, 5);
        assertEquals((12 + 9 + 11) / 3.0, b.typicalPrice(), 1e-15);
        assertEquals(3, b.range(), 0);
        assertTrue(b.isBullish());
        assertFalse(new Bar(0, 11, 12, 9, 10, 5).isBullish());
    }

    @Test
    void exceptionsCarryMessageAndCause() {
        RuntimeException cause = new IllegalStateException("c");
        assertEquals("m", new MalformedDataException("m").getMessage());
        assertSame(cause, new MalformedDataException("m", cause).getCause());
        assertEquals("m", new ConfigurationException("m").getMessage());
        assertSame(cause, new ConfigurationException("m", cause).getCause());
        IOException f = new MalformedFileException("f");
        assertEquals("f", f.getMessage());
        assertSame(cause, new MalformedFileException("f", cause).getCause());
        assertThrows(IllegalArgumentException.class, () -> new Bar(0, 10, 9, 11, 10, 1));
    }
}
