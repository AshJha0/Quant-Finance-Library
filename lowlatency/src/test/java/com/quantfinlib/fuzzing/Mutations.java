package com.quantfinlib.fuzzing;

import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Consumer;

/**
 * Seeded byte-level mutation of a seed corpus for the structured fuzz tests (*FuzzTest).
 * Each case is, with probability 1/20 each, an unmodified seed or up to 256 random bytes;
 * otherwise a seed with one to eight random edits: flip a bit, overwrite a byte with an
 * interesting value, insert random bytes, delete a range, duplicate a range, or truncate.
 *
 * <p>The number of cases is the system property {@code qfl.cases} (default 1000; the
 * {@code fuzz} Maven profile raises it). Generation uses {@link SplittableRandom} with a
 * fixed seed per test, and a failure is rethrown with the seed and case index, so it is
 * reproducible. This is mutation fuzzing without coverage feedback: it finds crashes on
 * malformed input (an exception outside a parser's documented contract), not deep logic
 * bugs.</p>
 */
public final class Mutations {
    private static final byte[] INTERESTING = {0, 1, (byte) 0x7f, (byte) 0x80, (byte) 0xff, '<', '>', '"', ',',
        '\n', '\r', '=', 1, '&', ';', '\\'};

    /** Cases per fuzz test. */
    public static final int CASES = Integer.getInteger("qfl.cases", 1000);

    private Mutations() {
    }

    /**
     * Runs the target on {@link #CASES} inputs derived from the corpus.
     *
     * @param seed random seed (fixed per test)
     * @param corpus the seed inputs
     * @param target the code under test; it catches the exceptions its contract documents
     */
    public static void run(long seed, List<byte[]> corpus, Consumer<byte[]> target) {
        SplittableRandom rnd = new SplittableRandom(seed);
        for (int i = 0; i < CASES; i++) {
            byte[] input;
            int pick = rnd.nextInt(20);
            if (pick == 0) {
                input = corpus.get(rnd.nextInt(corpus.size())).clone();
            } else if (pick == 1) {
                input = new byte[rnd.nextInt(257)];
                fill(rnd, input);
            } else {
                input = mutate(corpus.get(rnd.nextInt(corpus.size())), rnd);
            }
            try {
                target.accept(input);
            } catch (RuntimeException | AssertionError e) {
                throw new AssertionError("fuzz seed " + seed + " case " + i + ": " + e, e);
            }
        }
    }

    /**
     * Applies one to eight random edits to a copy of the input.
     *
     * @param seed the input
     * @param rnd the source of randomness
     * @return the mutated bytes
     */
    public static byte[] mutate(byte[] seed, SplittableRandom rnd) {
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
                    byte[] ins = new byte[1 + rnd.nextInt(16)];
                    fill(rnd, ins);
                    b = splice(b, rnd.nextInt(n + 1), 0, ins);
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

    private static void fill(SplittableRandom rnd, byte[] b) {
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) rnd.nextInt(256);
        }
    }

    private static byte[] splice(byte[] b, int at, int remove, byte[] insert) {
        byte[] out = new byte[b.length - remove + insert.length];
        System.arraycopy(b, 0, out, 0, at);
        System.arraycopy(insert, 0, out, at, insert.length);
        System.arraycopy(b, at + remove, out, at + insert.length, b.length - at - remove);
        return out;
    }
}
