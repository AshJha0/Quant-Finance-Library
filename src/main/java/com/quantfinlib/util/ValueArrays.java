package com.quantfinlib.util;

/**
 * Defensive-copy helpers for immutable result types.
 *
 * <p>Result and report classes copy their array and collection arguments once
 * on construction and never hand out the internal copy. Callers read single
 * elements through indexed accessors (no allocation) and ask for a copy only
 * when they need the whole array. Collections are copied with
 * {@code List.copyOf} or an unmodifiable ordered copy at the call site.
 */
public final class ValueArrays {

    private ValueArrays() {
    }

    /**
     * Deep copy of a two-dimensional array.
     *
     * @param a the array to copy, may be null; rows may be null
     * @return a new array with copied rows, or null when {@code a} is null
     */
    public static double[][] copy(double[][] a) {
        if (a == null) {
            return null;
        }
        double[][] c = new double[a.length][];
        for (int i = 0; i < a.length; i++) {
            c[i] = a[i] == null ? null : a[i].clone();
        }
        return c;
    }
}
