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
        // curve builder used) via the pair's spot inversion.
        CurrencyPair pair = curve.pair();
        return pair.tenorDate(pair.tradeDateForSpot(curve.spotDate()), tenor);
    }

    // ------------------------------------------------------------------
    // Valuation
    // ------------------------------------------------------------------

    /**
     * Undiscounted mark-to-market in quote currency against a current curve:
     * each leg's (current forward − traded rate) × signed base notional.
     * The near leg is long base when {@code baseNotional > 0}, the far leg
     * short — so an at-market swap marks to ~zero on its own curve.
     *
     * <p><b>Aged swaps</b>: a leg whose settlement date lies before the
     * marking curve's spot has already settled — its P&amp;L is realized
     * cash in the books, not mark-to-market — so it contributes zero here.
     * Routine daily marking of a seasoned swap therefore values only the
     * remaining live leg(s).</p>
     */
    public double markToMarket(SwapPointsCurve current) {
        return legPnl(nearDate, nearRate, +1, current)
                + legPnl(farDate, farRate, -1, current);
    }

    /**
     * Discounted mark-to-market: live-leg P&amp;Ls discounted off a
     * quote-currency zero curve (ACT/365 from the valuation curve's spot
     * date). Settled legs contribute zero, as in the undiscounted form.
     */
    public double markToMarket(SwapPointsCurve current, YieldCurve quoteDiscount) {
        double mtm = 0;
        if (!nearDate.isBefore(current.spotDate())) {
            double tNear = ChronoUnit.DAYS.between(current.spotDate(), nearDate) / 365.0;
            mtm += legPnl(nearDate, nearRate, +1, current)
                    * (tNear > 0 ? quoteDiscount.discountFactor(tNear) : 1.0);
        }
        if (!farDate.isBefore(current.spotDate())) {
            double tFar = ChronoUnit.DAYS.between(current.spotDate(), farDate) / 365.0;
            mtm += legPnl(farDate, farRate, -1, current)
                    * (tFar > 0 ? quoteDiscount.discountFactor(tFar) : 1.0);
        }
        return mtm;
    }

    /** One leg's undiscounted P&L: zero once settled, spot at spot, else the outright. */
    private double legPnl(LocalDate legDate, double legRate, int sign, SwapPointsCurve current) {
        if (legDate.isBefore(current.spotDate())) {
            return 0; // settled: realized cash, not MTM
        }
        double forward = legDate.equals(current.spotDate())
                ? current.spotRate()
                : current.outright(legDate);
        return sign * baseNotional * (forward - legRate);
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
