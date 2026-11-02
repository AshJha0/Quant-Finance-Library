package com.quantfinlib.risk.simm;

import java.util.Objects;

/**
 * SIMM with the standardised additional initial margin of the methodology's
 * section L (fixed add-on, notional add-on and product-class multipliers).
 *
 * @param simm                    the plain SIMM result
 * @param additionalInitialMargin {@code AddOnIM + sum_p (MS_p - 1) SIMM_p}
 * @param total                   {@code simm.total() + additionalInitialMargin}
 */
public record SimmAddOnResult(SimmResult simm, double additionalInitialMargin, double total) {

    /** Validates the parts. */
    public SimmAddOnResult {
        Objects.requireNonNull(simm, "simm");
    }
}
