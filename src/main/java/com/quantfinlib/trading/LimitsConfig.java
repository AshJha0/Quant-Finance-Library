package com.quantfinlib.trading;

import com.quantfinlib.core.ConfigurationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One immutable set of pre-trade limits for {@link HftRiskGate}, with a
 * plain-text form that operations can review, diff and keep under version
 * control.
 *
 * <h2>Text format</h2>
 * <p>One {@code key = value} per line; blank lines and lines starting with
 * {@code #} are ignored. Every key below must appear exactly once, so a
 * deleted line is an error rather than a silently removed limit. Numbers use
 * Java syntax ({@code 5e6}, {@code 0.02}); {@code unlimited} stands for no
 * cap.</p>
 * <pre>
 * maxOrderQuantity      = 10000         # shares/contracts per order, &gt; 0
 * maxOrderNotional      = 5e6           # price x quantity per order, &gt; 0
 * maxPositionQuantity   = 50000         # |position + open same-side|, &gt; 0
 * priceCollarPct        = 0.02          # fraction of reference price, (0, 1] or unlimited
 * maxDailyNotional      = 1e9           # &gt; 0
 * maxDailyOrders        = 200000        # &gt; 0
 * maxDailyLoss          = 250000        # positive amount, &gt; 0
 * duplicateWindowNanos  = 1000000       # &gt;= 0, 0 disables
 * requireReferencePrice = true          # true or false
 * </pre>
 *
 * <p>Ordering: a config is "no looser" than another when each limit is at
 * most the other's (and {@code requireReferencePrice} is set if the other's
 * is). {@link #tightest} gives the field-wise tightest of two configs, for
 * example to merge a desk limit with a firm-wide one.</p>
 */
public record LimitsConfig(long maxOrderQuantity, double maxOrderNotional, long maxPositionQuantity,
                           double priceCollarPct, double maxDailyNotional, long maxDailyOrders,
                           double maxDailyLoss, long duplicateWindowNanos,
                           boolean requireReferencePrice) {

    /** Keys of the text format, in canonical order. */
    public static final List<String> KEYS = List.of("maxOrderQuantity", "maxOrderNotional",
            "maxPositionQuantity", "priceCollarPct", "maxDailyNotional", "maxDailyOrders",
            "maxDailyLoss", "duplicateWindowNanos", "requireReferencePrice");

    /** No caps at all: the state of a freshly constructed {@link HftRiskGate}. */
    public static final LimitsConfig UNLIMITED = new LimitsConfig(Long.MAX_VALUE, Double.MAX_VALUE,
            Long.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, Long.MAX_VALUE, Double.MAX_VALUE,
            0, false);

    /** Validates every limit; see the class description for the ranges. */
    public LimitsConfig {
        positive("maxOrderQuantity", maxOrderQuantity);
        positive("maxOrderNotional", maxOrderNotional);
        positive("maxPositionQuantity", maxPositionQuantity);
        if (priceCollarPct != Double.MAX_VALUE && !(priceCollarPct > 0 && priceCollarPct <= 1)) {
            throw new ConfigurationException("priceCollarPct must be in (0, 1] or unlimited: "
                    + priceCollarPct);
        }
        positive("maxDailyNotional", maxDailyNotional);
        positive("maxDailyOrders", maxDailyOrders);
        positive("maxDailyLoss", maxDailyLoss);
        if (duplicateWindowNanos < 0) {
            throw new ConfigurationException("duplicateWindowNanos must be >= 0: " + duplicateWindowNanos);
        }
    }

    private static void positive(String key, long v) {
        if (v <= 0) {
            throw new ConfigurationException(key + " must be > 0: " + v);
        }
    }

    private static void positive(String key, double v) {
        if (!(v > 0) || Double.isInfinite(v)) {
            throw new ConfigurationException(key + " must be finite and > 0 (or unlimited): " + v);
        }
    }

    /** Reads the limits currently set on a gate. */
    public static LimitsConfig of(HftRiskGate gate) {
        return new LimitsConfig(gate.maxOrderQuantity(), gate.maxOrderNotional(),
                gate.maxPositionQuantity(), gate.priceCollarPct(), gate.maxDailyNotional(),
                gate.maxDailyOrders(), gate.maxDailyLoss(), gate.duplicateWindowNanos(),
                gate.requireReferencePrice());
    }

    /** Writes every limit to {@code gate} in one atomic swap ({@link HftRiskGate#limits(LimitsConfig)}). */
    public void applyTo(HftRiskGate gate) {
        gate.limits(this);
    }

    /** Field-wise tightest of two configs. */
    public static LimitsConfig tightest(LimitsConfig a, LimitsConfig b) {
        return new LimitsConfig(Math.min(a.maxOrderQuantity, b.maxOrderQuantity),
                Math.min(a.maxOrderNotional, b.maxOrderNotional),
                Math.min(a.maxPositionQuantity, b.maxPositionQuantity),
                Math.min(a.priceCollarPct, b.priceCollarPct),
                Math.min(a.maxDailyNotional, b.maxDailyNotional),
                Math.min(a.maxDailyOrders, b.maxDailyOrders),
                Math.min(a.maxDailyLoss, b.maxDailyLoss),
                Math.max(a.duplicateWindowNanos, b.duplicateWindowNanos),
                a.requireReferencePrice || b.requireReferencePrice);
    }

    /** True when no limit of this config is looser than {@code other}'s. */
    public boolean isNoLooserThan(LimitsConfig other) {
        return equals(tightest(this, other));
    }

    /** Values by key in canonical order, as the text format writes them. */
    public Map<String, String> asMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("maxOrderQuantity", fmt(maxOrderQuantity));
        m.put("maxOrderNotional", fmt(maxOrderNotional));
        m.put("maxPositionQuantity", fmt(maxPositionQuantity));
        m.put("priceCollarPct", fmt(priceCollarPct));
        m.put("maxDailyNotional", fmt(maxDailyNotional));
        m.put("maxDailyOrders", fmt(maxDailyOrders));
        m.put("maxDailyLoss", fmt(maxDailyLoss));
        m.put("duplicateWindowNanos", Long.toString(duplicateWindowNanos));
        m.put("requireReferencePrice", Boolean.toString(requireReferencePrice));
        return m;
    }

    /** Lines {@code key: old -> new} for every limit that differs from {@code before}. */
    public List<String> changesFrom(LimitsConfig before) {
        List<String> out = new ArrayList<>();
        Map<String, String> old = before.asMap();
        asMap().forEach((k, v) -> {
            if (!v.equals(old.get(k))) {
                out.add(k + ": " + old.get(k) + " -> " + v);
            }
        });
        return out;
    }

    /** The text form; {@link #parse} of it returns an equal config. */
    public String format() {
        StringBuilder sb = new StringBuilder();
        asMap().forEach((k, v) -> sb.append(k).append(" = ").append(v).append('\n'));
        return sb.toString();
    }

    private static String fmt(long v) {
        return v == Long.MAX_VALUE ? "unlimited" : Long.toString(v);
    }

    private static String fmt(double v) {
        return v == Double.MAX_VALUE ? "unlimited" : Double.toString(v);
    }

    /**
     * Parses the text format.
     *
     * @throws ConfigurationException (an IllegalArgumentException) naming the line and key for a syntax
     *         error, an unknown, repeated or missing key, or a value out of range
     */
    public static LimitsConfig parse(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new ConfigurationException("line " + (i + 1) + ": expected key = value");
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (!KEYS.contains(key)) {
                throw new ConfigurationException("line " + (i + 1) + ": unknown key " + key);
            }
            if (values.putIfAbsent(key, value) != null) {
                throw new ConfigurationException("line " + (i + 1) + ": repeated key " + key);
            }
        }
        for (String k : KEYS) {
            if (!values.containsKey(k)) {
                throw new ConfigurationException("missing key " + k);
            }
        }
        return new LimitsConfig(
                parseLong(values, "maxOrderQuantity"),
                parseDouble(values, "maxOrderNotional"),
                parseLong(values, "maxPositionQuantity"),
                parseDouble(values, "priceCollarPct"),
                parseDouble(values, "maxDailyNotional"),
                parseLong(values, "maxDailyOrders"),
                parseDouble(values, "maxDailyLoss"),
                parseLong(values, "duplicateWindowNanos"),
                parseBoolean(values, "requireReferencePrice"));
    }

    private static long parseLong(Map<String, String> m, String key) {
        String v = m.get(key);
        if ("unlimited".equalsIgnoreCase(v) && !"duplicateWindowNanos".equals(key)) {
            return Long.MAX_VALUE;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new ConfigurationException(key + ": not an integer: " + v, e);
        }
    }

    private static double parseDouble(Map<String, String> m, String key) {
        String v = m.get(key);
        if ("unlimited".equalsIgnoreCase(v)) {
            return Double.MAX_VALUE;
        }
        try {
            double d = Double.parseDouble(v);
            if (!Double.isFinite(d)) {
                throw new ConfigurationException(key + ": not a finite number: " + v);
            }
            return d;
        } catch (NumberFormatException e) {
            throw new ConfigurationException(key + ": not a number: " + v, e);
        }
    }

    private static boolean parseBoolean(Map<String, String> m, String key) {
        String v = m.get(key).toLowerCase(Locale.ROOT);
        return switch (v) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new ConfigurationException(key + ": expected true or false: " + v);
        };
    }
}
