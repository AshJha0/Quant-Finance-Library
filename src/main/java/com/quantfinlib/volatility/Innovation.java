package com.quantfinlib.volatility;

/**
 * Distribution of the standardised innovation {@code z_t = r_t / √h_t} in
 * the GARCH-family likelihoods.
 */
public enum Innovation {
    /** Standard normal. */
    GAUSSIAN,
    /**
     * Student-t rescaled to unit variance, with ν &gt; 2 degrees of freedom
     * estimated with the other parameters (Bollerslev 1987, Rev. Econ. Stat.
     * 69, 542-547).
     */
    STUDENT_T
}
