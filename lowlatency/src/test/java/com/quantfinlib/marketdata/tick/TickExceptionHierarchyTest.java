package com.quantfinlib.marketdata.tick;

import com.quantfinlib.core.QuantFinException;
import java.io.IOException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tick-file exception keeps its JDK supertype (moved from core {@code ExceptionHierarchyTest} in 4.0). */
class TickExceptionHierarchyTest {

    @Test
    void truncatedTickFileExceptionIsAnIoAndLibraryException() {
        assertTrue(IOException.class.isAssignableFrom(TickFileReader.TruncatedTickFileException.class));
        assertTrue(QuantFinException.class.isAssignableFrom(TickFileReader.TruncatedTickFileException.class));
    }
}
