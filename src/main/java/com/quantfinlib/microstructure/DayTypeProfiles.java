package com.quantfinlib.microstructure;

import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Day-type-aware seasonality: not every trading day has the same shape.
 * Options-expiry days trade 2-3x normal volume with a violent close;
 * half days compress the whole U-curve into a morning; FX fixing days
 * (month-end, the 4pm London WM/R window) concentrate flow around the
 * fix. A single averaged profile is wrong on exactly the days that
 * matter most, so this container holds one independently-learned curve
 * per day type — one {@link VolumeCurve}, {@link VolatilityCurve}, or
 * {@link SpreadForecaster} each — and the caller selects today's profile
 * once at session start.
 *
 * <pre>{@code
 * // 0=regular, 1=expiry, 2=half day  (the caller owns the taxonomy)
 * var volume = new DayTypeProfiles<>(3, () -> new VolumeCurve(78, 0.1));
 * VolumeCurve today = volume.profile(calendar.isExpiry(date) ? 1 : 0);
 * today.onVolume(bucket, qty);   // learns ONLY the expiry-day shape
 * }</pre>
 *
 * <p>The trade-off is honest and unavoidable: a per-type profile learns
 * from only that type's sessions, so rare types (12 expiries a year)
 * converge slowly. Seed a new type from the regular-day profile via the
 * curve's own seeding method when one exists, or accept the slower
 * ramp. All profiles are constructed eagerly up front — selection is
 * allocation-free and hot-path safe; the curves themselves keep their
 * own single-writer contracts.</p>
 *
 * @param <T> the per-day-type profile, typically {@link VolumeCurve},
 *            {@link VolatilityCurve} or {@link SpreadForecaster}
 */
public final class DayTypeProfiles<T> {

    private final T[] profiles;

    /**
     * @param dayTypes number of day types in the caller's taxonomy, e.g. 3
     *                 for regular / expiry / half-day (equities) or
     *                 regular / month-end-fixing (FX)
     * @param factory  builds one fresh, identically-configured profile per
     *                 day type
     */
    public DayTypeProfiles(int dayTypes, Supplier<T> factory) {
        this(dayTypes, i -> factory.get());
    }

    /** Variant whose factory sees the day-type index it is building for. */
    @SuppressWarnings("unchecked")
    public DayTypeProfiles(int dayTypes, IntFunction<T> factory) {
        if (dayTypes < 1) {
            throw new IllegalArgumentException("need dayTypes >= 1");
        }
        this.profiles = (T[]) new Object[dayTypes];
        for (int i = 0; i < dayTypes; i++) {
            profiles[i] = factory.apply(i);
        }
    }

    /** The independently-learned profile for {@code dayType}. */
    public T profile(int dayType) {
        return profiles[dayType];
    }

    public int dayTypes() {
        return profiles.length;
    }
}
