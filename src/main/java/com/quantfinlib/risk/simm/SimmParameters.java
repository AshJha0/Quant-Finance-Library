package com.quantfinlib.risk.simm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A SIMM calibration: risk weights, correlations and concentration
 * thresholds, held as named numbers.
 *
 * <p>Plain idea: SIMM's formulas are fixed but its numbers are re-calibrated
 * by ISDA for each version. Those numbers are ISDA data, so this class does
 * not hard-code any version. It stores {@code key -> value} pairs that a
 * caller loads from a file ({@link #parse(Reader)}) or sets in code
 * ({@link #builder()}). {@link #illustrative()} ships round numbers that let
 * the calculator run end to end. <b>They are illustrative only and are not
 * the values of any ISDA SIMM version.</b> ISDA SIMM is licensed by ISDA for
 * production use; production calibrations must come from ISDA.</p>
 *
 * <p>Keys, with {@code RC} the {@link SimmRiskClass#key()} (IR, CreditQ,
 * CreditNonQ, Equity, Commodity, FX), {@code b} a bucket, {@code t} a label1
 * tenor and {@code l2} a label2 value. A lookup tries the listed forms left
 * to right and uses the first present.</p>
 * <ul>
 *   <li>{@code RC.delta.rw.b.t}, {@code RC.delta.rw.l2}, {@code RC.delta.rw.b},
 *       {@code RC.delta.rw.t}, {@code RC.delta.rw}: delta risk weight.</li>
 *   <li>{@code RC.vega.rw.b}, {@code RC.vega.rw}: vega risk weight.</li>
 *   <li>{@code RC.delta.threshold.b}, {@code RC.delta.threshold} and the
 *       same for {@code vega}: concentration thresholds (absent means no
 *       concentration add-on).</li>
 *   <li>{@code RC.rho.sameQualifier[.b]}, {@code RC.rho.diffQualifier[.b]}:
 *       intra-bucket correlation for non-IR classes.</li>
 *   <li>{@code IR.tenorRho.t1.t2} (either order), {@code IR.tenorRho}:
 *       IR tenor correlation; {@code IR.phi}: sub-curve correlation;
 *       {@code IR.rho.inflation}: inflation against any curve tenor.</li>
 *   <li>{@code RC.gamma.b.c} (either order), {@code RC.gamma}:
 *       inter-bucket correlation.</li>
 *   <li>{@code RC.curvature.multiplier}: scale on curvature margin
 *       (default 1).</li>
 *   <li>{@code CreditQ.baseCorr.rw}, {@code CreditQ.baseCorr.rho}.</li>
 *   <li>{@code psi.RC1.RC2} (either order), {@code psi}: risk-class
 *       correlation within a product class.</li>
 *   <li>{@code label}: free text naming the calibration.</li>
 * </ul>
 * <p>The bucket named {@code Residual} is never correlated with other
 * buckets; its margin is added on top, as in SIMM.</p>
 */
public final class SimmParameters {

    /** Bucket name that SIMM treats as residual. */
    public static final String RESIDUAL = "Residual";

    /** IR tenors used by {@link #illustrative()}, shortest first. */
    private static final String[] IR_TENORS =
            {"2w", "1m", "3m", "6m", "1y", "2y", "3y", "5y", "10y", "15y", "20y", "30y"};

    private final Map<String, Double> values;
    private final String label;

    private SimmParameters(Map<String, Double> values, String label) {
        this.values = Collections.unmodifiableMap(new TreeMap<>(values));
        this.label = label;
    }

    /** @return a new empty builder */
    public static Builder builder() {
        return new Builder();
    }

    /** @return the calibration label */
    public String label() {
        return label;
    }

    /** @return all numeric entries, sorted by key (unmodifiable) */
    public Map<String, Double> values() {
        return values;
    }

    /**
     * @param key a key
     * @return the value, if present
     */
    public Optional<Double> find(String key) {
        return Optional.ofNullable(values.get(key));
    }

    double first(String... candidates) {
        Double v = firstOrNull(candidates);
        if (v == null) {
            throw new IllegalArgumentException("SIMM parameter missing: " + String.join(" | ", candidates));
        }
        return v;
    }

    Double firstOrNull(String... candidates) {
        for (String c : candidates) {
            if (c == null) {
                continue;
            }
            Double v = values.get(c);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /**
     * Reads {@code key = value} lines. Blank lines and lines starting with
     * {@code #} are skipped. The key {@code label} takes free text; every
     * other value must parse as a double.
     *
     * @param in the text
     * @return the parameters
     * @throws IOException on read failure
     * @throws IllegalArgumentException on a malformed line
     */
    public static SimmParameters parse(Reader in) throws IOException {
        Builder b = builder();
        BufferedReader r = in instanceof BufferedReader br ? br : new BufferedReader(in);
        String line;
        int n = 0;
        while ((line = r.readLine()) != null) {
            n++;
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            int eq = s.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("line " + n + ": expected key = value");
            }
            String k = s.substring(0, eq).trim();
            String v = s.substring(eq + 1).trim();
            if ("label".equals(k)) {
                b.label(v);
            } else {
                try {
                    b.set(k, Double.parseDouble(v));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("line " + n + ": bad number " + v, e);
                }
            }
        }
        return b.build();
    }

    /**
     * Writes this set in the {@link #parse(Reader)} format.
     *
     * @return the text
     */
    public String format() {
        StringBuilder sb = new StringBuilder("label = ").append(label).append('\n');
        values.forEach((k, v) -> sb.append(k).append(" = ").append(v).append('\n'));
        return sb.toString();
    }

    /**
     * An ILLUSTRATIVE calibration of round numbers. It is NOT any official
     * ISDA SIMM version and must not be used to compute real margin; it
     * exists so that the calculator and its tests run end to end. IR tenor
     * correlations are generated by {@code max(0.3, exp(-0.25 |ln(ti/tj)|))}
     * rounded to two places, a smooth made-up shape.
     *
     * @return the illustrative set, labelled accordingly
     */
    public static SimmParameters illustrative() {
        Builder b = builder().label("ILLUSTRATIVE - not an ISDA SIMM calibration");
        double[] irRw = {110, 100, 90, 70, 60, 55, 52, 50, 50, 50, 52, 55};
        for (int i = 0; i < IR_TENORS.length; i++) {
            b.set("IR.delta.rw." + IR_TENORS[i], irRw[i]);
            for (int j = i + 1; j < IR_TENORS.length; j++) {
                double x = Math.abs(Math.log(tenorYears(IR_TENORS[i]) / tenorYears(IR_TENORS[j])));
                double rho = Math.max(0.3, Math.exp(-0.25 * x));
                b.set("IR.tenorRho." + IR_TENORS[i] + "." + IR_TENORS[j], Math.round(rho * 100) / 100.0);
            }
        }
        b.set("IR.delta.rw.Inflation", 50).set("IR.delta.rw", 60)
                .set("IR.tenorRho", 0.5).set("IR.phi", 0.98).set("IR.rho.inflation", 0.3)
                .set("IR.gamma", 0.3).set("IR.vega.rw", 0.2)
                .set("IR.delta.threshold", 1.0e9).set("IR.vega.threshold", 1.0e10);
        cls(b, "CreditQ", 80, 200, 0.95, 0.4, 0.4, 0.3);
        b.set("CreditQ.baseCorr.rw", 10).set("CreditQ.baseCorr.rho", 0.3);
        cls(b, "CreditNonQ", 300, 600, 0.6, 0.2, 0.2, 0.3);
        cls(b, "Equity", 30, 60, 0.99, 0.2, 0.15, 0.3);
        cls(b, "Commodity", 25, 50, 0.99, 0.3, 0.2, 0.4);
        cls(b, "FX", 8, 8, 1.0, 0.5, 0.0, 0.3);
        b.set("psi", 0.2).set("psi.IR.FX", 0.3).set("psi.CreditQ.CreditNonQ", 0.3);
        return b.build();
    }

    private static void cls(Builder b, String rc, double rw, double residualRw, double same,
                            double diff, double gamma, double vegaRw) {
        b.set(rc + ".delta.rw", rw).set(rc + ".delta.rw." + RESIDUAL, residualRw)
                .set(rc + ".rho.sameQualifier", same).set(rc + ".rho.diffQualifier", diff)
                .set(rc + ".gamma", gamma).set(rc + ".vega.rw", vegaRw)
                .set(rc + ".delta.threshold", 1.0e9).set(rc + ".vega.threshold", 1.0e10);
    }

    /**
     * Converts a tenor label ({@code 5d}, {@code 2w}, {@code 3m},
     * {@code 10y}) to years, with a month as 1/12 and a week as 7/365.
     *
     * @param t the label
     * @return years
     * @throws IllegalArgumentException if unparseable
     */
    public static double tenorYears(String t) {
        String s = t.trim().toLowerCase(Locale.ROOT);
        if (s.length() < 2) {
            throw new IllegalArgumentException("bad tenor: " + t);
        }
        double n;
        try {
            n = Double.parseDouble(s.substring(0, s.length() - 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad tenor: " + t, e);
        }
        return switch (s.charAt(s.length() - 1)) {
            case 'd' -> n / 365.0;
            case 'w' -> n * 7.0 / 365.0;
            case 'm' -> n / 12.0;
            case 'y' -> n;
            default -> throw new IllegalArgumentException("bad tenor: " + t);
        };
    }

    /** Mutable builder for {@link SimmParameters}. */
    public static final class Builder {
        private final Map<String, Double> values = new LinkedHashMap<>();
        private String label = "unlabelled";

        private Builder() {
        }

        /**
         * @param key   parameter key
         * @param value finite value
         * @return this
         */
        public Builder set(String key, double value) {
            Objects.requireNonNull(key, "key");
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(key + " must be finite");
            }
            values.put(key, value);
            return this;
        }

        /**
         * Copies every entry and the label of an existing set.
         *
         * @param p the source
         * @return this
         */
        public Builder from(SimmParameters p) {
            values.putAll(p.values);
            label = p.label;
            return this;
        }

        /**
         * @param text free-text label
         * @return this
         */
        public Builder label(String text) {
            label = Objects.requireNonNull(text, "label");
            return this;
        }

        /** @return the immutable parameter set */
        public SimmParameters build() {
            return new SimmParameters(values, label);
        }
    }
}
