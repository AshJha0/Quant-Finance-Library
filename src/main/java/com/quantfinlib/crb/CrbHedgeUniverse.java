package com.quantfinlib.crb;

import java.util.ArrayList;
import java.util.List;

/**
 * The hedge-instrument universe, aligned to a book's factor registry —
 * because hand-assembling {@code loadings[factor][instrument]} is the
 * most error-prone step in the whole hedging workflow (one transposed
 * index and the optimizer confidently hedges the wrong thing).
 *
 * <p>Each instrument declares what ONE UNIT of its notional does to the
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
 * <p>Factors named here are REGISTERED on the shared registry if new
 * (a hedge-only factor simply has zero book exposure), and
 * {@link #loadings()} materializes the matrix at the registry's
 * CURRENT size — build it after all booking and adding is done, and
 * feed it straight to {@link HedgeOptimizer}/{@link CrbAutoHedger}
 * with {@link #costs()}. Research lane, single-threaded.</p>
 */
public final class CrbHedgeUniverse {

    private record Instrument(String name, double costPerUnit,
                              int[] factorIds, double[] perUnit) {
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
     * The loadings matrix [factor][instrument] at the registry's CURRENT
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
