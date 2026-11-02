#!/usr/bin/env python3
"""Generate the golden SOFR curve, swap and bond set used by
core/src/test/java/com/quantfinlib/rates/QuantLibGoldenTest.java.

Run once, offline, with QuantLib-Python (the version used is written into
the output header). The Java test reads the file and recomputes every
number with the library; nothing in the file is computed by the library.

    python -m venv ql && ql/bin/pip install QuantLib
    ql/bin/python scripts/quantlib-golden-sofr.py > core/src/test/resources/golden/quantlib-sofr.csv

Conventions are chosen so that both sides describe the same contract:
a weekends-only calendar (holiday calendars are tested elsewhere),
ACT/360 SOFR, modified following, log-linear discount factors on an
ACT/365F time axis, and an overnight index with no fixing lag.

All market data below are illustrative inputs, not market observations.
"""
import sys

import QuantLib as ql

VAL = ql.Date(2, 11, 2026)
CAL = ql.WeekendsOnly()
MF = ql.ModifiedFollowing
A360 = ql.Actual360()


def d(x):
    return x.ISO()


def out(key, value):
    print(f"{key},{value!r}" if isinstance(value, float) else f"{key},{value}")


def main():
    ql.Settings.instance().evaluationDate = VAL
    print(f"# QuantLib-Python {ql.__version__}; generator scripts/quantlib-golden-sofr.py")
    print(f"# valuation {d(VAL)}; calendar WeekendsOnly; index ACT/360 fixing lag 0")
    print("# key,value  (dates ISO; rates decimal; prices per 100 face; swap values per unit notional)")

    handle = ql.RelinkableYieldTermStructureHandle()
    index = ql.OvernightIndex("SOFR-W", 0, ql.USDCurrency(), CAL, A360, handle)

    # --- curve ---------------------------------------------------------
    helpers = [ql.DepositRateHelper(ql.QuoteHandle(ql.SimpleQuote(0.0380)), ql.Period(1, ql.Days),
                                    0, CAL, ql.Following, False, A360)]
    out("curve.deposit.on", 0.0380)
    quotes = [(3, 0.0378), (6, 0.0372), (12, 0.0360), (24, 0.0352), (36, 0.0349), (60, 0.0350),
              (84, 0.0355), (120, 0.0362), (360, 0.0371)]
    for months, q in quotes:
        helpers.append(ql.OISRateHelper(2, ql.Period(months, ql.Months), ql.QuoteHandle(ql.SimpleQuote(q)),
                                        index, ql.YieldTermStructureHandle(), False, 0, MF, ql.Annual, CAL))
        out(f"curve.ois.{months}m", q)
    curve = ql.PiecewiseLogLinearDiscount(VAL, helpers, ql.Actual365Fixed())
    curve.enableExtrapolation()
    handle.linkTo(curve)
    for i, (dt, df) in enumerate(curve.nodes()):
        out(f"curve.node.{i}.date", d(dt))
        out(f"curve.node.{i}.df", df)
    for probe in [ql.Date(17, 2, 2027), ql.Date(30, 9, 2029), ql.Date(4, 11, 2033), ql.Date(1, 6, 2045),
                  ql.Date(6, 11, 2058)]:
        out(f"curve.df.{d(probe)}", curve.discount(probe))

    # --- realised fixings ------------------------------------------------
    first = ql.Date(1, 6, 2026)
    dt = first
    k = 0
    while dt < VAL:
        if CAL.isBusinessDay(dt):
            rate = 0.0430 - 0.0001 * (k % 7) + 0.00002 * (k % 3)
            index.addFixing(dt, rate)
            out(f"fixing.{d(dt)}", rate)
            k += 1
        dt = dt + 1

    # --- swaps -----------------------------------------------------------
    swaps = [
        # name, start, end, fixed, lag, lookback, shift, lockout
        ("seasoned_lookback", ql.Date(15, 6, 2026), ql.Date(15, 6, 2031), 0.0360, 2, 2, False, 0),
        ("seasoned_shift", ql.Date(15, 6, 2026), ql.Date(15, 6, 2031), 0.0360, 2, 2, True, 0),
        ("seasoned_lockout", ql.Date(15, 6, 2026), ql.Date(15, 6, 2031), 0.0360, 2, 0, False, 2),
        ("forward_stub", ql.Date(4, 11, 2026), ql.Date(15, 8, 2029), 0.0350, 2, 0, False, 0),
        ("spot_5y_plain", ql.Date(4, 11, 2026), ql.Date(4, 11, 2031), 0.0350, 0, 0, False, 0),
    ]
    engine = ql.DiscountingSwapEngine(handle)
    for name, s, e, k_fixed, lag, lb, shift, lock in swaps:
        sched = ql.Schedule(s, e, ql.Period(ql.Annual), CAL, MF, MF, ql.DateGeneration.Backward, False)
        swap = ql.OvernightIndexedSwap(ql.Swap.Payer, 1.0, sched, k_fixed, A360, index, 0.0, lag, MF, CAL,
                                       False, ql.RateAveraging.Compound, lb, lock, shift)
        swap.setPricingEngine(engine)
        out(f"swap.{name}.start", d(s))
        out(f"swap.{name}.end", d(e))
        out(f"swap.{name}.fixed", k_fixed)
        out(f"swap.{name}.paymentLag", lag)
        out(f"swap.{name}.lookback", lb)
        out(f"swap.{name}.shift", shift)
        out(f"swap.{name}.lockout", lock)
        out(f"swap.{name}.npv", swap.NPV())
        out(f"swap.{name}.fixedLegNpv", swap.fixedLegNPV())
        out(f"swap.{name}.floatLegNpv", swap.overnightLegNPV())
        out(f"swap.{name}.fairRate", swap.fairRate())

    # --- bond ------------------------------------------------------------
    issue = ql.Date(15, 5, 2025)
    maturity = ql.Date(15, 5, 2035)
    settle = ql.Date(4, 11, 2026)
    coupon = 0.0425
    ylds = 0.0410
    bsched = ql.Schedule(issue, maturity, ql.Period(ql.Semiannual), ql.NullCalendar(), ql.Unadjusted,
                         ql.Unadjusted, ql.DateGeneration.Backward, False)
    dc = ql.ActualActual(ql.ActualActual.ISMA, bsched)
    bond = ql.FixedRateBond(0, 100.0, bsched, [coupon], dc)
    ql.Settings.instance().evaluationDate = settle
    out("bond.issue", d(issue))
    out("bond.maturity", d(maturity))
    out("bond.settlement", d(settle))
    out("bond.coupon", coupon)
    out("bond.frequency", 2)
    out("bond.yield", ylds)
    rate = ql.InterestRate(ylds, dc, ql.Compounded, ql.Semiannual)
    out("bond.clean", ql.BondFunctions.cleanPrice(bond, rate, settle))
    out("bond.dirty", bond.dirtyPrice(ylds, dc, ql.Compounded, ql.Semiannual, settle))
    out("bond.accrued", ql.BondFunctions.accruedAmount(bond, settle))
    out("bond.macaulay", ql.BondFunctions.duration(bond, rate, ql.Duration.Macaulay, settle))
    out("bond.modified", ql.BondFunctions.duration(bond, rate, ql.Duration.Modified, settle))
    out("bond.convexity", ql.BondFunctions.convexity(bond, rate, settle))
    out("bond.yieldFromClean.price", 101.25)
    out("bond.yieldFromClean.yield",
        ql.BondFunctions.bondYield(bond, ql.BondPrice(101.25, ql.BondPrice.Clean), dc, ql.Compounded,
                                   ql.Semiannual, settle, 1e-14, 200))
    return 0


if __name__ == "__main__":
    sys.exit(main())
