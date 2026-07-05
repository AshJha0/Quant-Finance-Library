package com.quantfinlib.fx;

import com.quantfinlib.rates.YieldCurve;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * A non-deliverable forward: the FX forward for restricted currencies (INR,
 * KRW, TWD, BRL, CNY, ...) where the local currency never moves. The trade
 * cash-settles in the deliverable (usually USD, the base) currency against
 * an official <em>fixing</em> published before settlement.
 *
 * <p>Two dates matter, and they differ:</p>
 * <ul>
 *   <li><b>Fixing date</b> — when the official reference rate (e.g. the RBI
 *       INR reference rate, KFTC18 for KRW, PTAX for BRL) is observed. It
 *       precedes settlement by the restricted currency's fixing lag —
 *       typically two local business days, one for BRL/PTAX.</li>
 *   <li><b>Settlement date</b> — when the USD difference amount pays.</li>
 * </ul>
 *
 * <p>Settlement to the <b>buyer</b> of base currency (long USD in USDINR),
 * paid in base currency, is the market-standard formula</p>
 *
 * <pre>   amount = baseNotional × (fixing − contractRate) / fixing</pre>
 *
 * <p>— the division by the fixing converts the quote-currency difference
 * back into deliverable currency. Pricing is ordinary forward machinery
 * ({@link SwapPointsCurve}) plus these conventions, which is why NDF desks
 * quote points just like deliverable forwards.</p>
 */
public final class Ndf {

    /**
     * Fixing lags (local business days between fixing and settlement) for
     * the common NDF currencies; PTAX fixes one day before settlement,
     * the Asian fixings two. Unlisted currencies default to 2.
     */
    private static final Map<String, Integer> FIXING_LAG = Map.of(
            "INR", 2, "KRW", 2, "TWD", 2, "IDR", 2, "MYR", 2,
            "PHP", 1, "CNY", 2, "VND", 2, "BRL", 1, "CLP", 1);

    private final CurrencyPair pair;
    private final double baseNotional;    // > 0: long base (buyer)
    private final double contractRate;
    private final LocalDate fixingDate;
    private final LocalDate settlementDate;

    private Ndf(CurrencyPair pair, double baseNotional, double contractRate,
                LocalDate fixingDate, LocalDate settlementDate) {
        if (contractRate <= 0 || baseNotional == 0) {
            throw new IllegalArgumentException("contractRate must be > 0 and notional non-zero");
        }
        if (!fixingDate.isBefore(settlementDate)) {
            throw new IllegalArgumentException("fixing must precede settlement");
        }
        this.pair = pair;
        this.baseNotional = baseNotional;
        this.contractRate = contractRate;
        this.fixingDate = fixingDate;
        this.settlementDate = settlementDate;
    }

    /**
     * Books an NDF at a market tenor: settlement from the pair's tenor
     * arithmetic, fixing walked back by the restricted (quote) currency's
     * lag using the quote calendar — pass real holiday calendars via
     * {@link CurrencyPair#withCalendars} for production dates.
     */
    public static Ndf of(CurrencyPair pair, LocalDate tradeDate, String tenor,
                         double contractRate, double baseNotional) {
        LocalDate settlement = pair.tenorDate(tradeDate, tenor);
        LocalDate fixing = settlement;
        int lag = FIXING_LAG.getOrDefault(pair.quote(), 2);
        for (int i = 0; i < lag; i++) {
            do {
                fixing = fixing.minusDays(1);
            } while (!pair.isJointBusinessDay(fixing));
        }
        return new Ndf(pair, baseNotional, contractRate, fixing, settlement);
    }

    /** Explicit dates (broken dates, historical bookings). */
    public static Ndf of(CurrencyPair pair, double baseNotional, double contractRate,
                         LocalDate fixingDate, LocalDate settlementDate) {
        return new Ndf(pair, baseNotional, contractRate, fixingDate, settlementDate);
    }

    /** The fixing lag this library books for a restricted currency code. */
    public static int fixingLagDays(String currency) {
        return FIXING_LAG.getOrDefault(currency, 2);
    }

    // ------------------------------------------------------------------
    // Settlement and valuation
    // ------------------------------------------------------------------

    /**
     * Cash settlement in base (deliverable) currency once the official
     * fixing publishes. Positive pays the base buyer.
     */
    public double settlementAmount(double fixingRate) {
        if (fixingRate <= 0) {
            throw new IllegalArgumentException("fixingRate must be > 0: " + fixingRate);
        }
        return baseNotional * (fixingRate - contractRate) / fixingRate;
    }

    /**
     * Undiscounted mark-to-market in base currency: the settlement formula
     * evaluated at the curve's forward to the <b>fixing date</b> — the date
     * the payoff actually references.
     */
    public double markToMarket(SwapPointsCurve current) {
        return settlementAmount(current.outright(fixingDate));
    }

    /**
     * Discounted mark-to-market: the expected settlement discounted from the
     * settlement date on a base-currency (USD) zero curve, ACT/365 from the
     * curve's spot.
     */
    public double markToMarket(SwapPointsCurve current, YieldCurve baseDiscount) {
        double t = ChronoUnit.DAYS.between(current.spotDate(), settlementDate) / 365.0;
        return markToMarket(current) * (t > 0 ? baseDiscount.discountFactor(t) : 1.0);
    }

    public CurrencyPair pair() {
        return pair;
    }

    public double baseNotional() {
        return baseNotional;
    }

    public double contractRate() {
        return contractRate;
    }

    public LocalDate fixingDate() {
        return fixingDate;
    }

    public LocalDate settlementDate() {
        return settlementDate;
    }

    @Override
    public String toString() {
        return pair.symbol() + " NDF " + baseNotional + " " + pair.base() + " @" + contractRate
                + " fix " + fixingDate + " settle " + settlementDate;
    }
}
