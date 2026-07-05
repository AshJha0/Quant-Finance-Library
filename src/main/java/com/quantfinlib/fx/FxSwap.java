package com.quantfinlib.fx;

import com.quantfinlib.rates.YieldCurve;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * An FX swap: two offsetting FX exchanges — buy (sell) base currency on the
 * near date, sell (buy) it back on the far date — the instrument behind
 * funding, position rolls, and most of the daily FX forward volume.
 *
 * <p>An at-market swap has zero value at inception: both legs trade at the
 * prevailing outrights, so only the <em>points differential</em> between the
 * legs is economically exchanged. Value appears as the points move; this
 * class marks each leg against a current {@link SwapPointsCurve} and
 * (optionally) discounts the leg P&amp;Ls with a quote-currency
 * {@link YieldCurve}.</p>
 *
 * <p>Sign convention: {@code baseNotional > 0} means the near leg <b>buys</b>
 * base currency (and the far leg sells it back). All results are in quote
 * currency per the pair's quotation.</p>
 */
public final class FxSwap {

    private final CurrencyPair pair;
    private final double baseNotional;   // > 0: buy base near / sell base far
    private final LocalDate nearDate;
    private final double nearRate;
    private final LocalDate farDate;
    private final double farRate;

    private FxSwap(CurrencyPair pair, double baseNotional,
                   LocalDate nearDate, double nearRate, LocalDate farDate, double farRate) {
        if (!farDate.isAfter(nearDate)) {
            throw new IllegalArgumentException("far date must be after near date");
        }
        if (nearRate <= 0 || farRate <= 0 || baseNotional == 0) {
            throw new IllegalArgumentException("rates must be > 0 and notional non-zero");
        }
        this.pair = pair;
        this.baseNotional = baseNotional;
        this.nearDate = nearDate;
        this.nearRate = nearRate;
        this.farDate = farDate;
        this.farRate = farRate;
    }

    /** Explicit legs (off-market swaps, historical bookings). */
    public static FxSwap of(CurrencyPair pair, double baseNotional,
                            LocalDate nearDate, double nearRate,
                            LocalDate farDate, double farRate) {
        return new FxSwap(pair, baseNotional, nearDate, nearRate, farDate, farRate);
    }

    /**
     * At-market swap struck off a points curve: both legs at the curve's
     * outrights, so inception value is zero by construction. {@code "SPOT"}
     * is accepted as the near tenor for the classic spot-against-forward swap.
     */
    public static FxSwap atMarket(SwapPointsCurve curve, String nearTenor, String farTenor,
                                  double baseNotional) {
        LocalDate near = "SPOT".equalsIgnoreCase(nearTenor)
                ? curve.spotDate()
                : dateOf(curve, nearTenor);
        double nearRate = "SPOT".equalsIgnoreCase(nearTenor)
                ? curve.spotRate()
                : curve.outright(near);
        LocalDate far = dateOf(curve, farTenor);
        return new FxSwap(curve.pair(), baseNotional, near, nearRate, far, curve.outright(far));
    }

    private static LocalDate dateOf(SwapPointsCurve curve, String tenor) {
        // Anchor tenor dates at the curve's own spot (same convention the
        // curve builder used), by resolving through the pair from a trade
        // date that maps to this spot.
        CurrencyPair pair = curve.pair();
        LocalDate trade = curve.spotDate();
        for (int i = 0; i < pair.spotLagDays(); i++) {
            do {
                trade = trade.minusDays(1);
            } while (!pair.isJointBusinessDay(trade));
        }
        return pair.tenorDate(trade, tenor);
    }

    // ------------------------------------------------------------------
    // Valuation
    // ------------------------------------------------------------------

    /**
     * Undiscounted mark-to-market in quote currency against a current curve:
     * each leg's (current forward − traded rate) × signed base notional.
     * The near leg is long base when {@code baseNotional > 0}, the far leg
     * short — so an at-market swap marks to ~zero on its own curve.
     */
    public double markToMarket(SwapPointsCurve current) {
        double nearFwd = nearDate.equals(current.spotDate())
                ? current.spotRate()
                : current.outright(nearDate);
        double nearPnl = baseNotional * (nearFwd - nearRate);
        double farPnl = -baseNotional * (current.outright(farDate) - farRate);
        return nearPnl + farPnl;
    }

    /**
     * Discounted mark-to-market: leg P&amp;Ls discounted off a quote-currency
     * zero curve (ACT/365 from the valuation curve's spot date).
     */
    public double markToMarket(SwapPointsCurve current, YieldCurve quoteDiscount) {
        double nearFwd = nearDate.equals(current.spotDate())
                ? current.spotRate()
                : current.outright(nearDate);
        double tNear = ChronoUnit.DAYS.between(current.spotDate(), nearDate) / 365.0;
        double tFar = ChronoUnit.DAYS.between(current.spotDate(), farDate) / 365.0;
        double nearPnl = baseNotional * (nearFwd - nearRate)
                * (tNear > 0 ? quoteDiscount.discountFactor(tNear) : 1.0);
        double farPnl = -baseNotional * (current.outright(farDate) - farRate)
                * quoteDiscount.discountFactor(tFar);
        return nearPnl + farPnl;
    }

    /**
     * The swap's traded points differential (far − near) in pips — what the
     * two counterparties actually negotiated.
     */
    public double swapPointsPips() {
        return pair.pips(farRate - nearRate);
    }

    /**
     * Cost in quote currency of rolling a base position one day at a quoted
     * tom-next rate: what a position holder pays (or earns, when negative)
     * to push settlement from tomorrow to the next day.
     */
    public static double rollCost(CurrencyPair pair, double baseNotional, double tomNextPips) {
        return baseNotional * pair.priceFromPips(tomNextPips);
    }

    public CurrencyPair pair() {
        return pair;
    }

    public double baseNotional() {
        return baseNotional;
    }

    public LocalDate nearDate() {
        return nearDate;
    }

    public double nearRate() {
        return nearRate;
    }

    public LocalDate farDate() {
        return farDate;
    }

    public double farRate() {
        return farRate;
    }

    @Override
    public String toString() {
        return pair.symbol() + " swap " + baseNotional + " " + pair.base()
                + " " + nearDate + "@" + nearRate + " / " + farDate + "@" + farRate;
    }
}
