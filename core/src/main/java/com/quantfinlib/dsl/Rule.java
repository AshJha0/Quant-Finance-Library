package com.quantfinlib.dsl;

/**
 * A boolean condition over a bar index, typically closing over precomputed
 * indicator arrays. Rules compose with {@link #and}, {@link #or}, {@link #not}.
 */
@FunctionalInterface
public interface Rule {

    boolean isSatisfied(int index);

    default Rule and(Rule other) {
        return i -> isSatisfied(i) && other.isSatisfied(i);
    }

    default Rule or(Rule other) {
        return i -> isSatisfied(i) || other.isSatisfied(i);
    }

    default Rule not() {
        return i -> !isSatisfied(i);
    }
}
