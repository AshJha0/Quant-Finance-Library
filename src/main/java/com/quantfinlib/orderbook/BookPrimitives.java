package com.quantfinlib.orderbook;

/**
 * The zero-allocation building blocks shared by the hot-lane books
 * ({@link HftOrderBook} venue-side, {@code marketdata.L3BookBuilder}
 * participant-side): occupancy-bitmap scans and a primitive open-addressing
 * long→int map with backward-shift deletion. Static methods over
 * caller-owned arrays — {@code invokestatic} with no receiver, so sharing
 * costs nothing on the hot path and the subtlest code in the library
 * (the circular probe-order rule in {@link #mapRemoveAt}) exists exactly
 * once.
 *
 * <p>Map convention: key 0 is the empty-slot sentinel (both books use
 * strictly positive ids/refs); capacity is a power of two with
 * {@code mask = capacity - 1} and load factor ≤ 0.5. Scans return -1 for
 * "none".</p>
 */
public final class BookPrimitives {

    private static final int NONE = -1;

    private BookPrimitives() {
    }

    /** Lowest set bit at or above {@code from}, or -1. Bits past the ladder must be clear. */
    public static int nextSetAtOrAbove(long[] bits, int from) {
        if (from < 0) {
            from = 0;
        }
        int word = from >>> 6;
        if (word >= bits.length) {
            return NONE;
        }
        long w = bits[word] & (-1L << (from & 63)); // mask below `from`
        while (true) {
            if (w != 0) {
                return (word << 6) + Long.numberOfTrailingZeros(w);
            }
            if (++word >= bits.length) {
                return NONE;
            }
            w = bits[word];
        }
    }

    /** Highest set bit at or below {@code from}, or -1. */
    public static int nextSetAtOrBelow(long[] bits, int from) {
        if (from < 0) {
            return NONE;
        }
        int max = (bits.length << 6) - 1;
        if (from > max) {
            from = max;
        }
        int word = from >>> 6;
        long w = bits[word] & (-1L >>> (63 - (from & 63))); // mask above `from`
        while (true) {
            if (w != 0) {
                return (word << 6) + 63 - Long.numberOfLeadingZeros(w);
            }
            if (--word < 0) {
                return NONE;
            }
            w = bits[word];
        }
    }

    /** Inserts into the open-addressing map (caller guarantees the key is absent). */
    public static void mapPut(long[] keys, int[] vals, int mask, long key, int value) {
        int slot = (int) mix(key) & mask;
        while (keys[slot] != 0) {
            slot = (slot + 1) & mask;
        }
        keys[slot] = key;
        vals[slot] = value;
    }

    /** Slot of {@code key}, or -1. */
    public static int mapFind(long[] keys, int mask, long key) {
        int slot = (int) mix(key) & mask;
        while (keys[slot] != 0) {
            if (keys[slot] == key) {
                return slot;
            }
            slot = (slot + 1) & mask;
        }
        return NONE;
    }

    /**
     * Backward-shift deletion: re-places every entry of the probe run that
     * follows the hole, so lookups never need tombstones and cancel churn
     * cannot degrade probe lengths over a long session.
     */
    public static void mapRemoveAt(long[] keys, int[] vals, int mask, int slot) {
        int hole = slot;
        int probe = (hole + 1) & mask;
        while (keys[probe] != 0) {
            int home = (int) mix(keys[probe]) & mask;
            // Move back iff the entry's home position is "at or before" the
            // hole in circular probe order (standard backward-shift rule).
            boolean move = hole <= probe
                    ? (home <= hole || home > probe)
                    : (home <= hole && home > probe);
            if (move) {
                keys[hole] = keys[probe];
                vals[hole] = vals[probe];
                hole = probe;
            }
            probe = (probe + 1) & mask;
        }
        keys[hole] = 0;
    }

    /** Stafford variant 13 finalizer: cheap, well-mixed long hash. */
    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
