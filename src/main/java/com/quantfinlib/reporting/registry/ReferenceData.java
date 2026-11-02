package com.quantfinlib.reporting.registry;

import java.util.Set;

/**
 * The reference data the ESMA MiFIR validation rules need, bundled for
 * {@code Rts22TransactionReport.validateWithReferenceData}. Any registry may be null,
 * and the rules that need it are then skipped.
 *
 * @param leis GLEIF data (LEI rules CON-040 to CON-270)
 * @param instruments FIRDS data (CON-361, CON-411, CON-471, CON-351, CON-381, CON-640)
 * @param mics ISO 10383 data (CON-072, CON-162, CON-360, and the EEA venue tests)
 * @param currencies ISO 4217 list (CON-310, CON-340, CON-390, CON-440, CON-451, CON-520)
 * @param countries ISO 3166 list (CON-071, CON-080 and the other country rules)
 * @param preEuroCurrencies legacy currencies that the rules for fields 31, 44 and 45 also
 *        accept (null means none)
 * @since 2.4.0
 */
public record ReferenceData(LeiRegistry leis, InstrumentRegistry instruments, MicRegistry mics,
        CodeRegistry currencies, CodeRegistry countries, Set<String> preEuroCurrencies) {
    /** Copies the pre-euro set. */
    public ReferenceData {
        preEuroCurrencies = preEuroCurrencies == null ? Set.of() : Set.copyOf(preEuroCurrencies);
    }

    /**
     * Registries only, no currency or country lists.
     *
     * @param leis LEI registry, or null
     * @param instruments instrument registry, or null
     * @param mics MIC registry, or null
     * @return the bundle
     */
    public static ReferenceData of(LeiRegistry leis, InstrumentRegistry instruments, MicRegistry mics) {
        return new ReferenceData(leis, instruments, mics, null, null, Set.of());
    }
}
