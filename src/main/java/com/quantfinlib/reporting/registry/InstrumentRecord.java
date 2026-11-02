package com.quantfinlib.reporting.registry;

import java.util.Objects;
import java.util.Set;

/**
 * One instrument from FIRDS reference data.
 *
 * @param isin the ISIN
 * @param fullName instrument full name ({@code FullNm})
 * @param cfi CFI code ({@code ClssfctnTp}), empty if absent
 * @param tradingVenues MICs of the venues the instrument is admitted to or traded on
 */
public record InstrumentRecord(String isin, String fullName, String cfi, Set<String> tradingVenues) {
    /** Validates and copies. */
    public InstrumentRecord {
        Objects.requireNonNull(isin, "isin");
        Objects.requireNonNull(fullName, "fullName");
        Objects.requireNonNull(cfi, "cfi");
        tradingVenues = Set.copyOf(tradingVenues);
    }
}
