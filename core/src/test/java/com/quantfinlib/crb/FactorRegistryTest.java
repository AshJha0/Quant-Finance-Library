package com.quantfinlib.crb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Registration-order ids, idempotence, and the probe-never-registers rule. */
class FactorRegistryTest {

    @Test
    void idsAreRegistrationOrderAndIdempotent() {
        FactorRegistry reg = new FactorRegistry();
        assertEquals(0, reg.id("EQ:AAPL"));
        assertEquals(1, reg.id("CCY:EUR"));
        assertEquals(0, reg.id("EQ:AAPL"), "re-registration must return the same id");
        assertEquals(2, reg.size());
        assertEquals("EQ:AAPL", reg.name(0));
        assertEquals("CCY:EUR", reg.name(1));
    }

    @Test
    void probeNeverRegisters() {
        FactorRegistry reg = new FactorRegistry();
        reg.id("EQ:AAPL");
        assertEquals(-1, reg.idIfPresent("FXVEGA:EURUSD"));
        assertEquals(1, reg.size(), "idIfPresent must not grow the registry");
        assertEquals(0, reg.idIfPresent("EQ:AAPL"));
        assertThrows(IndexOutOfBoundsException.class, () -> reg.name(9));
    }
}
