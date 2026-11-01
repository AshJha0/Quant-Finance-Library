package com.quantfinlib.sbe;

/**
 * Integer decimal: {@code value = mantissa × 10^exponent}, the SBE
 * {@code decimal} composite (int64 mantissa, int8 exponent). Prices and
 * sizes travel this way so both ends see the same exact number; doubles
 * appear only at the edges that still use them (the market-data bus).
 *
 * <p>The null value of the mantissa is {@link #NULL_MANTISSA}
 * ({@code Long.MIN_VALUE}, the SBE int64 null); the codecs use it for
 * "no price" (market order, pulled quote side).</p>
 */
public final class Decimal64 {

    /** SBE null for int64: no value. */
    public static final long NULL_MANTISSA = Long.MIN_VALUE;

    private static final double[] POW10 = new double[37];

    static {
        for (int i = 0; i < POW10.length; i++) {
            POW10[i] = Double.parseDouble("1e" + (i - 18));
        }
    }

    private Decimal64() {
    }

    /** 10^e as a double for e in [-18, 18]. */
    static double pow10(int exponent) {
        if (exponent < -18 || exponent > 18) {
            throw new IllegalArgumentException("exponent out of [-18, 18]: " + exponent);
        }
        return POW10[exponent + 18];
    }

    /**
     * The mantissa of {@code value} on the {@code 10^exponent} grid.
     * {@code NaN} maps to {@link #NULL_MANTISSA}. Throws when the value is
     * infinite, does not fit, or is not on the grid (more than 1e-6 of a
     * grid step away from it), since rounding silently would change a price.
     */
    public static long toMantissa(double value, int exponent) {
        if (Double.isNaN(value)) {
            return NULL_MANTISSA;
        }
        double scaled = exponent < 0 ? value * pow10(-exponent) : value / pow10(exponent);
        if (Double.isInfinite(scaled) || Math.abs(scaled) >= 0x1p62) {
            throw new IllegalArgumentException(value + " does not fit at exponent " + exponent);
        }
        long mantissa = Math.round(scaled);
        if (Math.abs(scaled - mantissa) > 1e-6) {
            throw new IllegalArgumentException(value + " is not on the 1e" + exponent + " grid");
        }
        return mantissa;
    }

    /** {@code mantissa × 10^exponent} as a double; NaN for {@link #NULL_MANTISSA}. */
    public static double toDouble(long mantissa, int exponent) {
        if (mantissa == NULL_MANTISSA) {
            return Double.NaN;
        }
        // Dividing by an exact power of ten (exact up to 1e22) rounds once,
        // where multiplying by an inexact 1e-k would round twice.
        return exponent < 0 ? mantissa / pow10(-exponent) : mantissa * pow10(exponent);
    }
}
