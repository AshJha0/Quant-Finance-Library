package com.quantfinlib.rates;

import com.quantfinlib.internal.Immutable;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.TreeMap;
import java.util.function.DoubleSupplier;

/**
 * Published fixings of one index (for example SOFR), by fixing date. A
 * seasoned trade needs them: a coupon whose fixings are already in the past
 * is not forecast off a curve, it is computed from what was published.
 *
 * <p>Rule used by the legs: a fixing dated before the valuation date must be
 * in the history (else the valuation throws, because silently forecasting a
 * past fixing would misprice a seasoned trade); a fixing dated on the
 * valuation date is taken from the history when present and forecast
 * otherwise; later fixings are always forecast.</p>
 */
@Immutable
public final class FixingHistory {

    private static final FixingHistory EMPTY = new FixingHistory(Map.of());

    private final TreeMap<LocalDate, Double> fixings;

    private FixingHistory(Map<LocalDate, Double> fixings) {
        this.fixings = new TreeMap<>(fixings);
    }

    /**
     * A history with no fixings (a forward-starting or unseasoned trade).
     *
     * @return the empty history
     */
    public static FixingHistory empty() {
        return EMPTY;
    }

    /**
     * A history from a date-to-rate map (decimal rates, finite). The map is copied.
     *
     * @param fixings fixing date to published rate
     * @return the history
     */
    public static FixingHistory of(Map<LocalDate, Double> fixings) {
        Objects.requireNonNull(fixings, "fixings");
        for (Map.Entry<LocalDate, Double> e : fixings.entrySet()) {
            Objects.requireNonNull(e.getKey(), "fixing date");
            if (e.getValue() == null || !Double.isFinite(e.getValue())) {
                throw new IllegalArgumentException("fixing on " + e.getKey() + " must be finite");
            }
        }
        return new FixingHistory(fixings);
    }

    /**
     * The fixing published for {@code date}, if any.
     *
     * @param date fixing date
     * @return the fixing or empty
     */
    public OptionalDouble fixing(LocalDate date) {
        Double v = fixings.get(date);
        return v == null ? OptionalDouble.empty() : OptionalDouble.of(v);
    }

    /**
     * Number of fixings held.
     *
     * @return the count
     */
    public int size() {
        return fixings.size();
    }

    /** The published fixing, or the forecast when allowed; throws for a missing past fixing. */
    double rate(LocalDate fixingDate, LocalDate valuationDate, DoubleSupplier forecast) {
        Double v = fixings.get(fixingDate);
        if (v != null) {
            return v;
        }
        if (fixingDate.isBefore(valuationDate)) {
            throw new IllegalStateException("missing fixing for " + fixingDate
                    + " (before valuation date " + valuationDate + ")");
        }
        return forecast.getAsDouble();
    }
}
