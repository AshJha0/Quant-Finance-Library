package com.quantfinlib.screener;

/** Fundamental screening filters. NaN fundamentals never match. */
public final class FundamentalFilters {

    private FundamentalFilters() {
    }

    public static ScreenFilter marketCapAbove(double value) {
        return s -> s.fundamentals().marketCap() > value;
    }

    public static ScreenFilter peBelow(double value) {
        return s -> s.fundamentals().peRatio() < value;
    }

    public static ScreenFilter peBetween(double min, double max) {
        return s -> s.fundamentals().peRatio() >= min && s.fundamentals().peRatio() <= max;
    }

    public static ScreenFilter pbBelow(double value) {
        return s -> s.fundamentals().pbRatio() < value;
    }

    public static ScreenFilter epsAbove(double value) {
        return s -> s.fundamentals().eps() > value;
    }

    public static ScreenFilter roeAbove(double value) {
        return s -> s.fundamentals().roe() > value;
    }

    public static ScreenFilter dividendYieldAbove(double value) {
        return s -> s.fundamentals().dividendYield() > value;
    }

    public static ScreenFilter debtToEquityBelow(double value) {
        return s -> s.fundamentals().debtToEquity() < value;
    }
}
