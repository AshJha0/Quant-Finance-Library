package com.quantfinlib.marketdata;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interns instrument symbols to dense int ids so the hot path never touches
 * {@code String} hashing or map lookups: resolve the id once at subscription
 * time, then publish and dispatch with primitive ints only.
 */
public final class SymbolRegistry {

    private final ConcurrentHashMap<String, Integer> ids = new ConcurrentHashMap<>();
    private volatile String[] symbols = new String[0];

    /** Registers (or looks up) a symbol; returns its stable dense id. */
    public synchronized int register(String symbol) {
        Integer existing = ids.get(symbol);
        if (existing != null) {
            return existing;
        }
        int id = symbols.length;
        String[] copy = Arrays.copyOf(symbols, id + 1);
        copy[id] = symbol;
        symbols = copy;
        ids.put(symbol, id);
        return id;
    }

    /** Id of an already-registered symbol. */
    public int id(String symbol) {
        Integer id = ids.get(symbol);
        if (id == null) {
            throw new IllegalArgumentException("unregistered symbol: " + symbol);
        }
        return id;
    }

    public String symbol(int id) {
        return symbols[id];
    }

    public int size() {
        return symbols.length;
    }
}
