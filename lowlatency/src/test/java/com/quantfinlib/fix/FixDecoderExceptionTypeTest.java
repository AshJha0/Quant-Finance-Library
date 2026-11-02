package com.quantfinlib.fix;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.quantfinlib.core.QuantFinException;
import com.quantfinlib.core.WireProtocolException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** A corrupt FIX frame raises WireProtocolException, still an IllegalStateException. */
class FixDecoderExceptionTypeTest {

    @Test
    void corruptBodyLengthIsAWireProtocolException() {
        FixDecoder d = new FixDecoder();
        byte[] bad = "8=FIX.4.4\u00019=12x\u000135=0\u000110=000\u0001".getBytes(StandardCharsets.US_ASCII);
        d.feed(bad, 0, bad.length);
        IllegalStateException e = assertThrows(IllegalStateException.class, d::poll);
        assertInstanceOf(WireProtocolException.class, e);
        assertInstanceOf(QuantFinException.class, e);
    }

    @Test
    void parseExceptionIsALibraryException() {
        assertInstanceOf(QuantFinException.class, FixParseException.garbled("x"));
    }
}
