package com.quantfinlib.regreporting.emir;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A {@link ThirdCountryMarkets} held in memory, filled by the caller with
 * {@link #add(String, LocalDate)} and {@link #add(String, LocalDate, LocalDate)}. A MIC may carry several periods (an
 * equivalence withdrawn and granted again). Not thread-safe while being filled; read-only
 * use after filling is safe.
 *
 * <p>Worked example (own test): {@code new InMemoryThirdCountryMarkets().add("XNYS",
 * LocalDate.of(2020, 1, 1))} answers true for XNYS on 2026-03-02 and false on
 * 2019-12-31.</p>
 *
 * @since 4.0.0
 */
public final class InMemoryThirdCountryMarkets implements ThirdCountryMarkets {
    /** One period of equivalence; {@code to} null means still in force. */
    private record Period(LocalDate from, @Nullable LocalDate to) { }

    private final Map<String, List<Period>> periods = new HashMap<>();

    /** An empty list: no third-country market is equivalent. */
    public InMemoryThirdCountryMarkets() {
        // empty
    }

    /**
     * Adds an equivalence still in force from a date.
     *
     * @param mic the ISO 10383 MIC
     * @param from the first day of equivalence (inclusive)
     * @return this list
     */
    public InMemoryThirdCountryMarkets add(String mic, LocalDate from) {
        return put(mic, from, null);
    }

    /**
     * Adds a closed period of equivalence for a MIC.
     *
     * @param mic the ISO 10383 MIC
     * @param from the first day of equivalence (inclusive)
     * @param to the last day (inclusive)
     * @return this list
     * @throws IllegalArgumentException if {@code to} is before {@code from}
     */
    public InMemoryThirdCountryMarkets add(String mic, LocalDate from, LocalDate to) {
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to " + to + " is before from " + from);
        }
        return put(mic, from, to);
    }

    private InMemoryThirdCountryMarkets put(String mic, LocalDate from, @Nullable LocalDate to) {
        Objects.requireNonNull(mic, "mic");
        Objects.requireNonNull(from, "from");
        periods.computeIfAbsent(mic, k -> new ArrayList<>()).add(new Period(from, to));
        return this;
    }

    @Override
    public boolean isEquivalent(String mic, LocalDate onDate) {
        for (Period p : periods.getOrDefault(mic, List.of())) {
            LocalDate to = p.to();
            if (!onDate.isBefore(p.from()) && (to == null || !onDate.isAfter(to))) {
                return true;
            }
        }
        return false;
    }
}
