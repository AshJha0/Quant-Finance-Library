package com.quantfinlib.risk.simm;

/**
 * The six SIMM risk classes. The {@link #key()} is the prefix used for this
 * class in {@link SimmParameters} keys.
 */
public enum SimmRiskClass {
    /** Interest rate. Bucket is the currency. */
    INTEREST_RATE("IR"),
    /** Credit qualifying (issuers, indices). Carries base correlation. */
    CREDIT_QUALIFYING("CreditQ"),
    /** Credit non-qualifying (e.g. securitisations). */
    CREDIT_NON_QUALIFYING("CreditNonQ"),
    /** Equity. */
    EQUITY("Equity"),
    /** Commodity. */
    COMMODITY("Commodity"),
    /** Foreign exchange. Qualifier is the currency (delta) or pair (vega). */
    FX("FX");

    private final String key;

    SimmRiskClass(String key) {
        this.key = key;
    }

    /** @return the parameter-key prefix, e.g. {@code IR} */
    public String key() {
        return key;
    }
}
