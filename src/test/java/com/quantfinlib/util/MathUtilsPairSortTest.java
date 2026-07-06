package com.quantfinlib.util;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The primitive dual-array sort backing rank/selection code: agreement with
 * the JDK sort on random data (keys AND permutation), tie stability of the
 * key order, adversarial inputs (sorted, reversed, constant), and small
 * arrays through the insertion-sort path.
 */
class MathUtilsPairSortTest {

    @Test
    void agreesWithJdkSortOnRandomData() {
        SplittableRandom rnd = new SplittableRandom(11);
        for (int trial = 0; trial < 50; trial++) {
            int n = 1 + rnd.nextInt(500);
            double[] keys = new double[n];
            int[] values = new int[n];
            for (int i = 0; i < n; i++) {
                keys[i] = rnd.nextInt(50) - 25;   // plenty of ties
                values[i] = i;
            }
            double[] expectedKeys = keys.clone();
            java.util.Arrays.sort(expectedKeys);
            double[] originals = keys.clone();

            MathUtils.pairSort(keys, values);
            assertArrayEquals(expectedKeys, keys, "trial " + trial);
            // The permutation must carry each original key with its index.
            for (int i = 0; i < n; i++) {
                assertEquals(keys[i], originals[values[i]], 0.0,
                        "trial " + trial + " slot " + i);
            }
        }
    }

    @Test
    void adversarialShapesAndSmallArrays() {
        // Already sorted, reversed, and constant keys (median-of-three guards).
        for (double[] shape : new double[][]{
                {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16},
                {16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1},
                {7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7},
                {3.0}, {}}) {
            double[] keys = shape.clone();
            int[] values = new int[keys.length];
            for (int i = 0; i < values.length; i++) {
                values[i] = i;
            }
            double[] expected = shape.clone();
            java.util.Arrays.sort(expected);
            MathUtils.pairSort(keys, values);
            assertArrayEquals(expected, keys);
        }
        assertThrows(IllegalArgumentException.class,
                () -> MathUtils.pairSort(new double[2], new int[3]));
    }
}
