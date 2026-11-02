package com.quantfinlib.rates;

import java.util.Objects;

/**
 * A discount curve (OIS) and the projection curve of one index, as built by
 * {@link MultiCurveRisk#buildCurves}. The two may be the same object
 * (single-curve pricing).
 */
public record CurvePair(DiscountCurve discount, DiscountCurve projection) {

    public CurvePair {
        Objects.requireNonNull(discount, "discount");
        Objects.requireNonNull(projection, "projection");
        if (!discount.valuationDate().equals(projection.valuationDate())) {
            throw new IllegalArgumentException("curves have different valuation dates");
        }
    }
}
