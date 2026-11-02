package com.quantfinlib.risk;

/**
 * FRTB residual risk add-on, styled after BCBS d457 chapter MAR23. Not
 * certified. The add-on is a flat percentage of gross notional: 1.0%
 * for instruments with an exotic underlying, 0.1% for instruments
 * bearing other residual risks (per MAR23). Classifying an instrument
 * is the caller's job.
 *
 * <p>Worked example (own test): exotic notional 1000 and other 5000
 * give 10 + 5 = 15.</p>
 */
public final class FrtbRrao {

    private FrtbRrao() {
    }

    public static final double EXOTIC_RW = 0.01;
    public static final double OTHER_RW = 0.001;

    /** RRAO from gross (absolute) notionals. */
    public static double charge(double exoticGrossNotional, double otherGrossNotional) {
        if (!(exoticGrossNotional >= 0) || !(otherGrossNotional >= 0)) {
            throw new IllegalArgumentException("gross notionals must be >= 0");
        }
        return EXOTIC_RW * exoticGrossNotional + OTHER_RW * otherGrossNotional;
    }
}
