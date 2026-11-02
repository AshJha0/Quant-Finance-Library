package com.quantfinlib.rates;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A term-rate floating leg (IBOR-style, or a term SOFR leg): each coupon is
 * the index rate fixed {@code fixingLagDays} business days before the
 * accrual start, plus a spread, paid with the leg's payment lag.
 *
 * <p>The index period is taken to be the accrual period (no index-tenor
 * mismatch, the same simplification as {@link InterestRateSwap}). A coupon
 * whose fixing date is before the valuation date uses the published fixing
 * from {@link FixingHistory}; later ones use the projection forward
 * {@code (P_p(s)/P_p(e) - 1)/tau}.</p>
 *
 * <p>Worked example: with no fixing lag, no payment lag and the same
 * schedule and day count as an {@link InterestRateSwap} floating leg, the PV
 * equals {@link InterestRateSwap#floatLegPv} (pinned in the tests).</p>
 *
 * @param start         unadjusted effective date
 * @param end           unadjusted maturity
 * @param spread        spread over the index (simple)
 * @param convention    schedule, day count, calendar and payment lag
 * @param fixingLagDays business days from fixing to accrual start, {@code >= 0}
 * @param fixings       published fixings of the index
 */
public record TermRateLeg(LocalDate start, LocalDate end, double spread, LegConvention convention,
                          int fixingLagDays, FixingHistory fixings) implements FloatLeg {

    /** Validates the fields. */
    public TermRateLeg {
        Legs.requirePeriod(start, end);
        Legs.requireFinite(spread, "spread");
        Objects.requireNonNull(convention, "convention");
        Objects.requireNonNull(fixings, "fixings");
        if (fixingLagDays < 0) {
            throw new IllegalArgumentException("fixingLagDays must be >= 0");
        }
        convention.periods(start, end);
    }

    @Override
    public List<LegConvention.Period> periods() {
        return convention.periods(start, end);
    }

    /**
     * The fixing date of a period: {@code fixingLagDays} business days before its accrual start.
     *
     * @param period one of {@link #periods()}
     * @return the fixing date
     */
    public LocalDate fixingDate(LegConvention.Period period) {
        return convention.calendar().subtractBusinessDays(period.accrualStart(), fixingLagDays);
    }

    @Override
    public double couponRate(LegConvention.Period period, DiscountCurve projection) {
        return fixings.rate(fixingDate(period), projection.valuationDate(),
                () -> projection.forwardRate(period.accrualStart(), period.accrualEnd(),
                        convention.dayCount())) + spread;
    }
}
