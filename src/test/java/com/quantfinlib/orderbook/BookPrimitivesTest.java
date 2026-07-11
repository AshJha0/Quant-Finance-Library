package com.quantfinlib.orderbook;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct pins for the shared hot-lane primitives. These are exercised
 * transitively by every book test, but the subtlest code in the library —
 * the circular backward-shift rule in {@code mapRemoveAt} — deserves an
 * oracle that hammers it by name: a wrong circular comparator corrupts
 * probe chains only after a deletion in a wrapped run, which transitive
 * tests may never produce.
 */
class BookPrimitivesTest {

    // ------------------------------------------------------------ bitmap scans

    @Test
    void bitmapScansPinnedByHand() {
        long[] bits = {0b1010L}; // bits 1 and 3 set
        assertEquals(1, BookPrimitives.nextSetAtOrAbove(bits, 0));
        assertEquals(1, BookPrimitives.nextSetAtOrAbove(bits, 1));
        assertEquals(3, BookPrimitives.nextSetAtOrAbove(bits, 2));
        assertEquals(-1, BookPrimitives.nextSetAtOrAbove(bits, 4));

        assertEquals(3, BookPrimitives.nextSetAtOrBelow(bits, 63));
        assertEquals(3, BookPrimitives.nextSetAtOrBelow(bits, 3));
        assertEquals(1, BookPrimitives.nextSetAtOrBelow(bits, 2));
        assertEquals(-1, BookPrimitives.nextSetAtOrBelow(bits, 0));
    }

    @Test
    void bitmapScansCrossWordBoundaries() {
        // The word-boundary masking is where these break: bit 64 lives in
        // word 1 at offset 0, bit 0 in word 0 must be reachable from 127.
        long[] highOnly = {0L, 1L};
        assertEquals(64, BookPrimitives.nextSetAtOrAbove(highOnly, 0));
        assertEquals(64, BookPrimitives.nextSetAtOrAbove(highOnly, 64));
        assertEquals(-1, BookPrimitives.nextSetAtOrAbove(highOnly, 65));
        assertEquals(64, BookPrimitives.nextSetAtOrBelow(highOnly, 127));
        assertEquals(-1, BookPrimitives.nextSetAtOrBelow(highOnly, 63));

        long[] lowOnly = {1L, 0L};
        assertEquals(0, BookPrimitives.nextSetAtOrBelow(lowOnly, 127));
        assertEquals(0, BookPrimitives.nextSetAtOrAbove(lowOnly, -5)); // from clamps to 0
        assertEquals(-1, BookPrimitives.nextSetAtOrAbove(lowOnly, 1));
        // From beyond the ladder: above -> none, below -> clamps to the top.
        assertEquals(-1, BookPrimitives.nextSetAtOrAbove(lowOnly, 500));
        assertEquals(0, BookPrimitives.nextSetAtOrBelow(lowOnly, 500));
    }

    // ----------------------------------------------- open-addressing map oracle

    @Test
    void mapSurvivesRandomChurnAgainstHashMapOracle() {
        // Capacity 64, load <= 0.5 (max 30 live keys). Interleaved
        // insert/remove churn, verified key-by-key against a HashMap after
        // EVERY operation. Deterministic seed; ~250 removals guarantee many
        // deletions inside probe runs that straddle the array wrap, which
        // is exactly where a wrong backward-shift comparator corrupts state.
        int capacity = 64;
        int mask = capacity - 1;
        long[] keys = new long[capacity];
        int[] vals = new int[capacity];
        Map<Long, Integer> oracle = new HashMap<>();
        Set<Long> removed = new HashSet<>();
        Random rnd = new Random(42);
        int nextVal = 1;

        for (int op = 0; op < 500; op++) {
            if (oracle.size() < 30 && (oracle.isEmpty() || rnd.nextBoolean())) {
                long key;
                do {
                    key = 1 + rnd.nextInt(1_000_000); // strictly positive, 0 is the sentinel
                } while (oracle.containsKey(key));
                BookPrimitives.mapPut(keys, vals, mask, key, nextVal);
                oracle.put(key, nextVal);
                removed.remove(key);
                nextVal++;
            } else {
                List<Long> live = List.copyOf(oracle.keySet());
                long victim = live.get(rnd.nextInt(live.size()));
                int slot = BookPrimitives.mapFind(keys, mask, victim);
                assertTrue(slot >= 0, "live key must be findable before removal");
                BookPrimitives.mapRemoveAt(keys, vals, mask, slot);
                oracle.remove(victim);
                removed.add(victim);
            }
            // Full-state verification: every live key findable with the right
            // value; every removed key absent (no tombstone ghosts).
            for (Map.Entry<Long, Integer> e : oracle.entrySet()) {
                int slot = BookPrimitives.mapFind(keys, mask, e.getKey());
                assertTrue(slot >= 0, "lost key " + e.getKey() + " after op " + op);
                assertEquals(e.getValue().intValue(), vals[slot]);
            }
            for (long gone : removed) {
                assertEquals(-1, BookPrimitives.mapFind(keys, mask, gone),
                        "removed key " + gone + " still findable after op " + op);
            }
        }
    }

    @Test
    void midChainRemovalKeepsTrailingRunFindable() {
        // Force one probe run: keys engineered to collide by inserting more
        // keys than distinct home slots. With capacity 8 and 4 keys, remove
        // the second of a run and assert the trailers are still findable.
        int capacity = 8;
        int mask = capacity - 1;
        long[] keys = new long[capacity];
        int[] vals = new int[capacity];
        long[] inserted = new long[4];
        for (int i = 0; i < 4; i++) {
            inserted[i] = 1 + i * 7919L; // arbitrary positive keys
            BookPrimitives.mapPut(keys, vals, mask, inserted[i], 100 + i);
        }
        BookPrimitives.mapRemoveAt(keys, vals, mask,
                BookPrimitives.mapFind(keys, mask, inserted[1]));
        assertEquals(-1, BookPrimitives.mapFind(keys, mask, inserted[1]));
        for (int i : new int[]{0, 2, 3}) {
            int slot = BookPrimitives.mapFind(keys, mask, inserted[i]);
            assertTrue(slot >= 0, "key " + i + " lost after mid-chain removal");
            assertEquals(100 + i, vals[slot]);
        }
    }
}
