package com.quantfinlib.fx;

/**
 * How two leg prices compose into a cross rate. Used by {@link SyntheticCross}
 * and by the streaming {@code marketdata.CrossRateEngine} of the low-latency
 * artifact. Until 2.4 this was the nested enum {@code CrossRateEngine.Op}; it
 * became a stable top-level type in 3.0 when the engine moved out of this
 * package (docs/MIGRATION_3.0.md, section 8).
 *
 * @since 3.0.0
 */
public enum CrossOp {
    /** A/B × B/C = A/C (shared middle currency). */
    MULTIPLY,
    /** A/C ÷ B/C = A/B (shared quote currency). */
    DIVIDE
}
