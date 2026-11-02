package com.quantfinlib.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quantfinlib.io.CsvBarLoader;
import com.quantfinlib.marketdata.tick.TickFileReader;
import com.quantfinlib.io.UniverseCsvLoader;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Each library exception is a subtype of the JDK exception its sites threw
 * in 2.0, so a 2.0 catch block still catches it.
 */
class ExceptionHierarchyTest {

    @Test
    void typesExtendTheirJdkPredecessors() {
        assertTrue(IllegalStateException.class.isAssignableFrom(WireProtocolException.class));
        assertTrue(IllegalArgumentException.class.isAssignableFrom(MalformedDataException.class));
        assertTrue(IllegalArgumentException.class.isAssignableFrom(ConfigurationException.class));
        assertTrue(IOException.class.isAssignableFrom(TickFileReader.TruncatedTickFileException.class));
        assertTrue(QuantFinException.class.isAssignableFrom(TickFileReader.TruncatedTickFileException.class));
        RuntimeException cause = new NumberFormatException("x");
        assertSame(cause, new MalformedDataException("m", cause).getCause());
        assertSame(cause, new ConfigurationException("c", cause).getCause());
        assertEquals("w", ((QuantFinException) new WireProtocolException("w")).getMessage());
    }

    @Test
    void csvLoadersThrowMalformedData() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CsvBarLoader.parse(List.of(), "X"));
        assertInstanceOf(MalformedDataException.class, e);
        e = assertThrows(IllegalArgumentException.class,
                () -> CsvBarLoader.parse(List.of("foo,bar", "1,2"), "X"));
        assertInstanceOf(MalformedDataException.class, e);
        e = assertThrows(IllegalArgumentException.class, () -> UniverseCsvLoader.parse(List.of()));
        assertInstanceOf(MalformedDataException.class, e);
    }

}
