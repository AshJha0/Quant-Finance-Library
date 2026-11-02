package com.quantfinlib.fuzzing;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;

/**
 * Byte-level mutation of a seed corpus for the structured fuzz tests (*FuzzTest). Each
 * generated input is a seed with one to eight random edits: flip a bit, overwrite a byte
 * with an interesting value, insert random bytes, delete a range, duplicate a range, or
 * truncate. The edits are driven by a jqwik-generated long, so a failing input is
 * reproducible from the jqwik seed printed with the failure.
 *
 * <p>This is mutation fuzzing without coverage feedback: it finds crashes on malformed
 * input (an exception outside a parser's documented contract, a hang), not deep
 * logic bugs. See the 3.1 handback for why Jazzer is not used.</p>
 */
public final class Mutations {
    private static final byte[] INTERESTING = {0, 1, (byte) 0x7f, (byte) 0x80, (byte) 0xff, '<', '>', '"', ',',
        '\n', '\r', '=', 1, '&', ';', '\\'};

    private Mutations() {
    }

    /**
     * Mutated copies of the seeds, plus the unmodified seeds and short random byte strings.
     *
     * @param seeds the corpus
     * @return the arbitrary
     */
    public static Arbitrary<byte[]> of(List<byte[]> seeds) {
        Arbitrary<byte[]> mutated = Arbitraries.of(seeds).flatMap(s -> Arbitraries.longs()
                .map(r -> mutate(s, new Random(r))));
        Arbitrary<byte[]> random = Arbitraries.bytes().array(byte[].class).ofMaxSize(256);
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(1, Arbitraries.of(seeds)),
                net.jqwik.api.Tuple.of(1, random),
                net.jqwik.api.Tuple.of(18, mutated));
    }

    /**
     * Applies one to eight random edits to a copy of the seed.
     *
     * @param seed the input
     * @param rnd the source of randomness
     * @return the mutated bytes
     */
    public static byte[] mutate(byte[] seed, Random rnd) {
        byte[] b = seed.clone();
        int ops = 1 + rnd.nextInt(8);
        for (int i = 0; i < ops; i++) {
            int n = b.length;
            switch (rnd.nextInt(6)) {
                case 0 -> {
                    if (n > 0) {
                        int p = rnd.nextInt(n);
                        b[p] = (byte) (b[p] ^ (1 << rnd.nextInt(8)));
                    }
                }
                case 1 -> {
                    if (n > 0) {
                        b[rnd.nextInt(n)] = INTERESTING[rnd.nextInt(INTERESTING.length)];
                    }
                }
                case 2 -> {
                    int p = n == 0 ? 0 : rnd.nextInt(n + 1);
                    byte[] ins = new byte[1 + rnd.nextInt(16)];
                    rnd.nextBytes(ins);
                    b = splice(b, p, 0, ins);
                }
                case 3 -> {
                    if (n > 0) {
                        int p = rnd.nextInt(n);
                        b = splice(b, p, Math.min(n - p, 1 + rnd.nextInt(32)), new byte[0]);
                    }
                }
                case 4 -> {
                    if (n > 0) {
                        int p = rnd.nextInt(n);
                        int len = Math.min(n - p, 1 + rnd.nextInt(64));
                        b = splice(b, p, 0, Arrays.copyOfRange(b, p, p + len));
                    }
                }
                default -> {
                    if (n > 0) {
                        b = Arrays.copyOf(b, rnd.nextInt(n));
                    }
                }
            }
        }
        return b;
    }

    private static byte[] splice(byte[] b, int at, int remove, byte[] insert) {
        byte[] out = new byte[b.length - remove + insert.length];
        System.arraycopy(b, 0, out, 0, at);
        System.arraycopy(insert, 0, out, at, insert.length);
        System.arraycopy(b, at + remove, out, at + insert.length, b.length - at - remove);
        return out;
    }
}
