package com.quantfinlib.reporting.registry;

import com.quantfinlib.reporting.registry.CodeRegistry.Window;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One instrument from FIRDS reference data.
 *
 * @param isin the ISIN
 * @param fullName instrument full name ({@code FullNm})
 * @param cfi CFI code ({@code ClssfctnTp}), empty if absent
 * @param tradingVenues MICs of the venues the instrument is admitted to or traded on
 * @param admissions per venue MIC, the window in which the instrument is in the reference
 *        data for that venue; a venue in {@code tradingVenues} without an entry is treated
 *        as always present (the 2.4 behaviour)
 * @param commodityDerivative the FIRDS commodity derivative indicator (RTS 23 field 4),
 *        or null when not known
 */
public record InstrumentRecord(String isin, String fullName, String cfi, Set<String> tradingVenues,
        Map<String, Window> admissions, Boolean commodityDerivative) {
    /** Validates and copies; every venue with a window is added to {@code tradingVenues}. */
    public InstrumentRecord {
        Objects.requireNonNull(isin, "isin");
        Objects.requireNonNull(fullName, "fullName");
        Objects.requireNonNull(cfi, "cfi");
        admissions = Map.copyOf(admissions);
        if (tradingVenues.containsAll(admissions.keySet())) {
            tradingVenues = Set.copyOf(tradingVenues);
        } else {
            HashSet<String> all = new HashSet<>(tradingVenues);
            all.addAll(admissions.keySet());
            tradingVenues = Set.copyOf(all);
        }
    }

    /**
     * A record without admission dates or commodity flag, the 2.4 shape.
     *
     * @param isin the ISIN
     * @param fullName full name
     * @param cfi CFI code, empty if absent
     * @param tradingVenues venue MICs
     */
    public InstrumentRecord(String isin, String fullName, String cfi, Set<String> tradingVenues) {
        this(isin, fullName, cfi, tradingVenues, Map.of(), null);
    }

    /**
     * Whether the instrument is in the reference data for a venue on a date.
     *
     * @param mic the venue MIC
     * @param date the date
     * @return true if listed for that venue and inside its window (or no window is known)
     */
    public boolean isAdmittedOn(String mic, LocalDate date) {
        Objects.requireNonNull(date, "date");
        if (!tradingVenues.contains(mic)) {
            return false;
        }
        Window w = admissions.get(mic);
        return w == null || w.contains(date);
    }

    /**
     * Whether the instrument is in the reference data for at least one venue on a date.
     *
     * @param date the date
     * @return true if admitted on any venue on that date
     */
    public boolean isKnownOn(LocalDate date) {
        for (String mic : tradingVenues) {
            if (isAdmittedOn(mic, date)) {
                return true;
            }
        }
        return tradingVenues.isEmpty();
    }

    /** Merges two windows for the same venue into the smallest window covering both. */
    static Window widen(Window a, Window b) {
        if (a == null) {
            return b;
        }
        LocalDate from = a.from() == null || b.from() == null ? null
                : a.from().isBefore(b.from()) ? a.from() : b.from();
        LocalDate to = a.to() == null || b.to() == null ? null : a.to().isAfter(b.to()) ? a.to() : b.to();
        return new Window(from, to);
    }
}
