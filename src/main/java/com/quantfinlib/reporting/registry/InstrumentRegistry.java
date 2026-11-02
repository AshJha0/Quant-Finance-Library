package com.quantfinlib.reporting.registry;

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
}
