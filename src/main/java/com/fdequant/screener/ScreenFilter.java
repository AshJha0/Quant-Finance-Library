package com.fdequant.screener;

/** Composable screening predicate over a {@link StockSnapshot}. */
@FunctionalInterface
public interface ScreenFilter {

    boolean matches(StockSnapshot stock);

    default ScreenFilter and(ScreenFilter other) {
        return s -> matches(s) && other.matches(s);
    }

    default ScreenFilter or(ScreenFilter other) {
        return s -> matches(s) || other.matches(s);
    }

    default ScreenFilter negate() {
        return s -> !matches(s);
    }
}
