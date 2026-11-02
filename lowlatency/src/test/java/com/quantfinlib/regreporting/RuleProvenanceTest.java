package com.quantfinlib.regreporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.quantfinlib.regreporting.RuleProvenance.Evidence;
import org.junit.jupiter.api.Test;

class RuleProvenanceTest {
    @Test
    void rejectsBlankAndNull() {
        assertEquals("X", new RuleProvenance("X", "r", "c", Evidence.UNCONFIRMED).id());
        assertThrows(IllegalArgumentException.class, () -> new RuleProvenance(" ", "r", "c", Evidence.UNCONFIRMED));
        assertThrows(IllegalArgumentException.class, () -> new RuleProvenance("X", "", "c", Evidence.UNCONFIRMED));
        assertThrows(IllegalArgumentException.class, () -> new RuleProvenance("X", "r", " ", Evidence.UNCONFIRMED));
        assertThrows(NullPointerException.class, () -> new RuleProvenance("X", "r", "c", null));
    }
}
