package com.quantfinlib.reporting.registry;

import java.time.LocalDate;
import java.util.Optional;

/** Looks up instruments admitted to trading or traded on an EU venue. */
public interface InstrumentRegistry {
    /**
     * Finds an instrument.
     *
     * @param isin the ISIN
     * @return the record, or empty
     */
    Optional<InstrumentRecord> lookup(String isin);

    /**
     * Whether the registry knows the ISIN at all.
     *
     * @param isin the ISIN
     * @return true if known
     */
    default boolean isKnown(String isin) {
        return lookup(isin).isPresent();
    }

    /**
     * Whether the ISIN is listed for the given venue MIC.
     *
     * @param isin the ISIN
     * @param mic the venue
     * @return true if listed there
     */
    default boolean isAdmittedOn(String isin, String mic) {
        return lookup(isin).map(r -> r.tradingVenues().contains(mic)).orElse(false);
    }

    /**
     * Whether the ISIN is in the reference data on a date, on any venue. The default
     * ignores the date, since a single FIRDS snapshot has no history. A registry that
     * keeps admission and termination dates should override it.
     *
     * @param isin the ISIN
     * @param date the trading date
     * @return true if known on that date
     * @since 2.4.0
     */
    default boolean isKnownOn(String isin, LocalDate date) {
        return isKnown(isin);
    }

    /**
     * Whether the ISIN is listed for the venue on a date. The default ignores the date.
     *
     * @param isin the ISIN
     * @param mic the venue (segment) MIC
     * @param date the trading date
     * @return true if listed there on that date
     * @since 2.4.0
     */
    default boolean isAdmittedOn(String isin, String mic, LocalDate date) {
        return isAdmittedOn(isin, mic);
    }

    /**
     * The FIRDS commodity derivative indicator (instrument reference data field 4), read
     * by ESMA rule CON-640. The default returns empty (not known) and the rule is skipped.
     *
     * @param isin the ISIN
     * @return the indicator, or empty when not known
     * @since 2.4.0
     */
    default Optional<Boolean> isCommodityDerivative(String isin) {
        return Optional.empty();
    }
}
