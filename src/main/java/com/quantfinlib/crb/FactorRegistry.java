package com.quantfinlib.crb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dense integer ids for risk-factor names — the {@code SymbolRegistry}
 * pattern applied to the central risk book's factor space, so exposure
 * arithmetic runs over primitive arrays while the factor names stay
 * readable ({@code EQ:AAPL}, {@code CCY:EUR}, {@code FXVEGA:EURUSD}).
 * Grow-only; ids are registration order. Research/warm lane.
 */
public final class FactorRegistry {

    private final Map<String, Integer> ids = new HashMap<>();
    private final List<String> names = new ArrayList<>();

    /** Returns the id for {@code name}, registering it on first sight. */
    public int id(String name) {
        Integer existing = ids.get(name);
        if (existing != null) {
            return existing;
        }
        int id = names.size();
        ids.put(name, id);
        names.add(name);
        return id;
    }

    /** The id if registered, −1 otherwise (never registers). */
    public int idIfPresent(String name) {
        Integer existing = ids.get(name);
        return existing == null ? -1 : existing;
    }

    public String name(int id) {
        return names.get(id);
    }

    public int size() {
        return names.size();
    }
}
