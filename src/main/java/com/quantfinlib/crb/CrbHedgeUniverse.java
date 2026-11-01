package com.quantfinlib.crb;

import java.util.ArrayList;
import java.util.List;

/**
 * The hedge-instrument universe, aligned to a book's factor registry —
 * because hand-assembling {@code loadings[factor][instrument]} is the
 * most error-prone step in the whole hedging workflow (one transposed
 * index and the optimizer confidently hedges the wrong thing).
 *
 * <p>Each instrument declares what one unit of its notional does to the
 * factor space, in the same conventions {@link CentralRiskBook} books
 * with:
 * <ul>
 *   <li>{@link #addSingleFactor} — an instrument that is 1-for-1 one
 *       factor: an index future onto {@code EQ:<index>} (the covariance
 *       carries its correlation to the single names — the regression
 *       hedge falls out of the optimizer, not out of a beta table), a
 *       variance swap onto {@code EQVEGA:<sym>}, an FX vol trade onto
 *       {@code FXVEGA:<pair>};</li>
 *   <li>{@link #addFxForward} — one unit of base notional loads
 *       {@code CCY:<base>} +1 and {@code CCY:<quote>} −rate, exactly
 *       like a booked spot/forward — the natural hedge for the
 *       currency legs that spot, swaps, NDFs and option deltas net
 *       into;</li>
 *   <li>{@link #add} — anything else, factor names and per-unit
 *       loadings side by side.</li>
 * </ul>
 *
 * <p>Factors named here are registered on the shared registry if new
 * (a hedge-only factor simply has zero book exposure), and
 * {@link #loadings()} materializes the matrix at the registry's
 * current size — build it after all booking and adding is done, and
 * feed it straight to {@link HedgeOptimizer}/{@link CrbAutoHedger}
 * with {@link #costs()}. Research lane, single-threaded.</p>
 */
public final class CrbHedgeUniverse {

    private static final class Instrument {
        private final String name;
        private final double costPerUnit;
        private final int[] factorIds;
        private final double[] perUnit;

        /**
         * Creates the value; array and collection arguments are copied.
         */
        Instrument(String name, double costPerUnit, int[] factorIds, double[] perUnit) {
            this.name = name;
            this.costPerUnit = costPerUnit;
            this.factorIds = factorIds;
            this.perUnit = perUnit;
        }

        /** {@code name}. */
        String name() {
            return name;
        }

        /** {@code costPerUnit}. */
        double costPerUnit() {
            return costPerUnit;
        }

        /** {@code factorIds}. */
        int[] factorIds() {
            return factorIds;
        }

        /** {@code perUnit}. */
        double[] perUnit() {
            return perUnit;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Instrument o)) {
                return false;
            }
            return java.util.Objects.equals(name, o.name)
                    && Double.compare(costPerUnit, o.costPerUnit) == 0
                    && java.util.Arrays.equals(factorIds, o.factorIds)
                    && java.util.Arrays.equals(perUnit, o.perUnit);
        }

        @Override
        public int hashCode() {
            int h = 0;
            h = 31 * h + java.util.Objects.hashCode(name);
            h = 31 * h + Double.hashCode(costPerUnit);
            h = 31 * h + java.util.Arrays.hashCode(factorIds);
            h = 31 * h + java.util.Arrays.hashCode(perUnit);
            return h;
        }

        @Override
        public String toString() {
            return "Instrument[" + "name=" + name + ", " + "costPerUnit=" + costPerUnit + ", " + "factorIds=" + java.util.Arrays.toString(factorIds) + ", " + "perUnit=" + java.util.Arrays.toString(perUnit) + "]";
        }
    }

    private final FactorRegistry registry;
    private final List<Instrument> instruments = new ArrayList<>();

    /** @param registry the book's registry — {@code book.factors()} */
    public CrbHedgeUniverse(FactorRegistry registry) {
        this.registry = registry;
    }

    /** An instrument that is one unit of exactly one factor. */
    public CrbHedgeUniverse addSingleFactor(String name, String factor, double costPerUnit) {
        return add(name, costPerUnit, new String[]{factor}, new double[]{1});
    }

    /**
     * An FX forward/spot hedge on {@code pair}: one unit of base
     * notional loads the two currency legs exactly as a booked trade
     * would.
     */
    public CrbHedgeUniverse addFxForward(String name, String pair, double rate,
                                         double costPerUnit) {
        if (pair == null || pair.length() != 6) {
            throw new IllegalArgumentException("pair must be 6 chars like EURUSD: " + pair);
        }
        if (!(rate > 0) || rate == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("rate must be positive and finite");
        }
        return add(name, costPerUnit,
                new String[]{"CCY:" + pair.substring(0, 3), "CCY:" + pair.substring(3, 6)},
                new double[]{1, -rate});
    }

    /** A general instrument: per-unit loadings onto named factors. */
    public CrbHedgeUniverse add(String name, double costPerUnit,
                                String[] factors, double[] perUnit) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("instrument must be named");
        }
        if (!(costPerUnit >= 0) || costPerUnit == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("costPerUnit must be >= 0 and finite");
        }
        if (factors.length != perUnit.length || factors.length == 0) {
            throw new IllegalArgumentException("need aligned, non-empty factor/loading arrays");
        }
        int[] ids = new int[factors.length];
        for (int i = 0; i < factors.length; i++) {
            if (!Double.isFinite(perUnit[i])) {
                throw new IllegalArgumentException("loadings must be finite");
            }
            ids[i] = registry.id(factors[i]);       // registers hedge-only factors
        }
        instruments.add(new Instrument(name, costPerUnit, ids, perUnit.clone()));
        return this;
    }

    /**
     * The loadings matrix [factor][instrument] at the registry's current
     * size — call after all booking/adding, alongside {@link #costs()}.
     */
    public double[][] loadings() {
        int n = registry.size();
        int m = instruments.size();
        double[][] out = new double[n][m];
        for (int i = 0; i < m; i++) {
            Instrument inst = instruments.get(i);
            for (int k = 0; k < inst.factorIds().length; k++) {
                out[inst.factorIds()[k]][i] += inst.perUnit()[k];
            }
        }
        return out;
    }

    /** Per-unit costs aligned with {@link #loadings()} columns. */
    public double[] costs() {
        double[] out = new double[instruments.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = instruments.get(i).costPerUnit();
        }
        return out;
    }

    /** Instrument name for a {@link CrbAutoHedger.HedgeOrder#instrument()} index. */
    public String name(int instrument) {
        return instruments.get(instrument).name();
    }

    public int size() {
        return instruments.size();
    }
}
