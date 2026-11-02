package com.quantfinlib.fix;

import com.quantfinlib.core.WireProtocolException;
import com.quantfinlib.fuzzing.Mutations;
import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Structured fuzzing of the FIX parsers: {@link FixMessage#parse(byte[])} and the stream
 * framer {@link FixDecoder}. The contract under test: malformed input raises
 * {@link FixParseException} (parse) or {@link WireProtocolException} (framer, a corrupt
 * stream) and nothing else: no other runtime exception, no array index error, no hang.
 * The default build runs jqwik's default number of tries; {@code -Pfuzz} raises it.
 */
class FixParserFuzzTest {

    private static byte[] order() {
        return FixMessage.builder(FixMessage.NEW_ORDER_SINGLE).field(FixMessage.CL_ORD_ID, "ord-1")
                .field(FixMessage.SYMBOL, "EURUSD").field(FixMessage.SIDE, '1')
                .field(FixMessage.ORDER_QTY, 1_000_000L).field(FixMessage.ORD_TYPE, '2')
                .field(FixMessage.PRICE, 1.0851).encode("CLIENT", "VENUE", 7, "20260704-12:00:00.000");
    }

    private static byte[] heartbeat() {
        return FixMessage.builder("0").encode("A", "B", 1, "20260704-12:00:00.000");
    }

    @Provide
    Arbitrary<byte[]> fixInputs() {
        return Mutations.of(List.of(order(), heartbeat()));
    }

    @Property
    void parseRaisesOnlyFixParseException(@ForAll("fixInputs") byte[] input) {
        try {
            FixMessage m = FixMessage.parse(input);
            m.msgType();
            for (int i = 0; i < m.fieldCount(); i++) {
                m.tagAt(i);
                m.valueAt(i);
            }
        } catch (FixParseException expected) {
            // documented
        }
    }

    @Property
    void framerRaisesOnlyDocumentedExceptions(@ForAll("fixInputs") byte[] input) {
        FixDecoder d = new FixDecoder();
        byte[] stream = new byte[input.length * 2];
        System.arraycopy(input, 0, stream, 0, input.length);
        System.arraycopy(input, 0, stream, input.length, input.length);
        // two fragments exercise the partial-frame path
        int cut = stream.length / 3;
        try {
            d.feed(stream, 0, cut);
            drain(d);
            d.feed(stream, cut, stream.length - cut);
            drain(d);
        } catch (FixParseException | WireProtocolException expected) {
            // documented: a corrupt stream disconnects
        }
    }

    private static void drain(FixDecoder d) {
        for (int i = 0; i < 1000; i++) {
            if (d.poll() == null) {
                return;
            }
        }
        throw new AssertionError("framer did not stop producing messages");
    }
}
