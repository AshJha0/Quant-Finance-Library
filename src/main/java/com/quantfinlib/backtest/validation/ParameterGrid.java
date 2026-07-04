package com.quantfinlib.backtest.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A named parameter grid for strategy optimization; {@link #combinations()}
 * enumerates the cartesian product in deterministic order.
 */
public final class ParameterGrid {

    private final LinkedHashMap<String, double[]> params = new LinkedHashMap<>();

    public ParameterGrid add(String name, double... values) {
        if (values.length == 0) {
            throw new IllegalArgumentException("no values for parameter " + name);
        }
        params.put(name, values.clone());
        return this;
    }

    public int size() {
        int n = 1;
        for (double[] v : params.values()) {
            n *= v.length;
        }
        return params.isEmpty() ? 0 : n;
    }

    /** All parameter combinations, insertion-ordered and deterministic. */
    public List<Map<String, Double>> combinations() {
        List<Map<String, Double>> out = new ArrayList<>();
        out.add(new LinkedHashMap<>());
        for (Map.Entry<String, double[]> e : params.entrySet()) {
            List<Map<String, Double>> next = new ArrayList<>(out.size() * e.getValue().length);
            for (Map<String, Double> base : out) {
                for (double v : e.getValue()) {
                    Map<String, Double> combo = new LinkedHashMap<>(base);
                    combo.put(e.getKey(), v);
                    next.add(combo);
                }
            }
            out = next;
        }
        return out;
    }
}
