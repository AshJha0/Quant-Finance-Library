package com.quantfinlib.fix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One instance of a repeating group: its plain fields in wire order and the
 * instances of any nested groups. Build instances with {@link #builder} and
 * write them with {@link FixMessage.Builder#group}; read them with
 * {@link FixDictionary#readGroup}.
 */
public final class FixGroup {

    private final Map<Integer, String> fields;
    private final Map<Integer, List<FixGroup>> nested;

    private FixGroup(Map<Integer, String> fields, Map<Integer, List<FixGroup>> nested) {
        this.fields = Collections.unmodifiableMap(fields);
        this.nested = Collections.unmodifiableMap(nested);
    }

    /** Plain fields of this instance, delimiter first. */
    public Map<Integer, String> fields() {
        return fields;
    }

    /** Value of {@code tag} in this instance, or null. */
    public String get(int tag) {
        return fields.get(tag);
    }

    /** Instances of the nested group counted by {@code countTag}; empty when absent. */
    public List<FixGroup> group(int countTag) {
        return nested.getOrDefault(countTag, List.of());
    }

    /** Nested groups by count tag. */
    public Map<Integer, List<FixGroup>> groups() {
        return nested;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FixGroup g && fields.equals(g.fields) && nested.equals(g.nested);
    }

    @Override
    public int hashCode() {
        return fields.hashCode() * 31 + nested.hashCode();
    }

    @Override
    public String toString() {
        return nested.isEmpty() ? fields.toString() : fields + " " + nested;
    }

    /** Builder for one instance. */
    public static final class Builder {
        private final Map<Integer, String> fields = new LinkedHashMap<>();
        private final Map<Integer, List<FixGroup>> nested = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder field(int tag, String value) {
            if (fields.putIfAbsent(tag, value) != null) {
                throw new IllegalArgumentException("tag " + tag + " set twice in one instance");
            }
            return this;
        }

        public Builder group(int countTag, List<FixGroup> instances) {
            nested.put(countTag, List.copyOf(instances));
            return this;
        }

        public FixGroup build() {
            Map<Integer, List<FixGroup>> n = new LinkedHashMap<>();
            nested.forEach((k, v) -> n.put(k, new ArrayList<>(v)));
            n.replaceAll((k, v) -> List.copyOf(v));
            return new FixGroup(new LinkedHashMap<>(fields), n);
        }
    }
}
