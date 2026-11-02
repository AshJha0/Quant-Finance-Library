package com.quantfinlib.trading;

import com.quantfinlib.core.ConfigurationException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The limits-file parser throws the library's configuration exception
 * (moved from the main module's ExceptionHierarchyTest in 3.0, when
 * {@code trading} moved to the low-latency module).
 */
class LimitsConfigExceptionTest {

    @Test
    void limitsParserThrowsConfiguration() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LimitsConfig.parse("no equals sign"));
        assertInstanceOf(ConfigurationException.class, e);
        e = assertThrows(IllegalArgumentException.class, () -> LimitsConfig.parse("bogusKey = 1"));
        assertInstanceOf(ConfigurationException.class, e);
    }
}
