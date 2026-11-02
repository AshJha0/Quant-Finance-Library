package com.quantfinlib.fix;

import com.quantfinlib.core.WireProtocolException;
import com.quantfinlib.fuzzing.Mutations;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Structured fuzzing of the FIX parsers: {@link FixMessage#parse(byte[])} and the stream
 * framer {@link FixDecoder}. The contract under test: malformed input raises
 * {@link FixParseException} (parse) or {@link WireProtocolException} (framer, a corrupt
 * stream) and nothing else. Cases and seeding: see {@link Mutations}.
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

    private static final List<byte[]> CORPUS = List.of(order(), heartbeat());

    @Test
    void parseRaisesOnlyFixParseException() {
        Mutations.run(201L, CORPUS, input -> {
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
        });
    }

    @Test
    void framerRaisesOnlyDocumentedExceptions() {
        Mutations.run(202L, CORPUS, input -> {
            FixDecoder d = new FixDecoder();
            byte[] stream = new byte[input.length * 2];
            System.arraycopy(input, 0, stream, 0, input.length);
            System.arraycopy(input, 0, stream, input.length, input.length);
            int cut = stream.length / 3;   // two fragments exercise the partial-frame path
            try {
                d.feed(stream, 0, cut);
                drain(d);
                d.feed(stream, cut, stream.length - cut);
                drain(d);
            } catch (FixParseException | WireProtocolException expected) {
                // documented: a corrupt stream disconnects
            }
        });
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
