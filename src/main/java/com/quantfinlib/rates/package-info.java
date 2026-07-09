/**
 * Fixed income with real market conventions:
 * {@link com.quantfinlib.rates.YieldCurve} (zero curve, discount factors,
 * implied forwards, bootstrap from annual par swaps),
 * {@link com.quantfinlib.rates.BondPricer} (price/yield, duration, convexity,
 * DV01 — both whole-period and date-based with accrued interest),
 * {@link com.quantfinlib.rates.DayCount} (ACT/360, ACT/365, 30/360,
 * ACT/ACT ISDA), {@link com.quantfinlib.rates.BusinessCalendar}
 * (holidays, roll conventions, T+n settlement, coupon schedules),
 * {@link com.quantfinlib.rates.ShortRateModels} (Vasicek, CIR and
 * curve-fitted Hull-White: closed-form zero-coupon bonds plus the
 * simulation steps a rates-factor Monte Carlo needs) and
 * {@link com.quantfinlib.rates.KeyRateDurations} (WHERE on the curve a
 * bond's DV01 lives — per-node bumps whose slices sum back to the
 * parallel move, tested).
 */
package com.quantfinlib.rates;
