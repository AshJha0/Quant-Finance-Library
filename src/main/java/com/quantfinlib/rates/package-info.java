/**
 * Fixed income with real market conventions:
 * {@link com.quantfinlib.rates.YieldCurve} (zero curve, discount factors,
 * implied forwards, bootstrap from annual par swaps),
 * {@link com.quantfinlib.rates.BondPricer} (price/yield, duration, convexity,
 * DV01 — both whole-period and date-based with accrued interest),
 * {@link com.quantfinlib.rates.DayCount} (ACT/360, ACT/365, 30/360,
 * ACT/ACT ISDA) and {@link com.quantfinlib.rates.BusinessCalendar}
 * (holidays, roll conventions, T+n settlement, coupon schedules).
 */
package com.quantfinlib.rates;
