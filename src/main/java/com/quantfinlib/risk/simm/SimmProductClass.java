package com.quantfinlib.risk.simm;

import java.util.Locale;

/**
 * The four SIMM product classes. Margin is computed separately for each and
 * the results are added (no diversification across product classes).
 */
public enum SimmProductClass {
    /** Interest rates and FX. */
    RATES_FX("RatesFX"),
    /** Credit. */
    CREDIT("Credit"),
    /** Equity. */
    EQUITY("Equity"),
    /** Commodity. */
    COMMODITY("Commodity");

    private final String crifName;

    SimmProductClass(String crifName) {
        this.crifName = crifName;
    }

    /** @return the name used in the CRIF ProductClass column */
    public String crifName() {
        return crifName;
    }

    /**
     * Parses a CRIF ProductClass value (case-insensitive).
     *
     * @param s the CRIF value, e.g. {@code RatesFX}
     * @return the product class
     * @throws IllegalArgumentException if unknown
     */
    public static SimmProductClass fromCrif(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        for (SimmProductClass p : values()) {
            if (p.crifName.toLowerCase(Locale.ROOT).equals(t)) {
                return p;
            }
        }
        throw new IllegalArgumentException("unknown SIMM product class: " + s);
    }
}
