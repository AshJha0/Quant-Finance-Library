# Migrating to 3.1

Status: implemented for 3.1.0. 3.1 is additive: no public member is removed,
no signature changes and no existing result changes (release policy: no
breaking changes in 3.x). Two things are deprecated. Compiling with
`-Xlint:deprecation` lists every line this note concerns.

## 1. `rates.SwapPricer` is deprecated

The plain idea: `SwapPricer`'s `YieldCurve` methods price a swap as "n
annual periods of length 1 from spot". Real swaps have dates, day counts, a
calendar, stubs, payment lags and, for overnight indices, lookback, shift or
lockout. The 3.1 leg model carries all of that.

Formally, `SwapPricer` is `@Deprecated(since = "3.1.0")`, not
`forRemoval`. It stays, with unchanged results, through 3.x. The earliest
release that may remove it is 4.0, and 4.0 will not ship before three
months after 3.2.0 (a deprecation lives at least one minor release plus
three months).

| 3.0 call | 3.1 replacement |
|---|---|
| `SwapPricer.parRate(YieldCurve, tenor)` | `new VanillaSwap(fixed, floating).parRate(discount, projection)` |
| `SwapPricer.payerPv(YieldCurve, tenor, k)` | `VanillaSwap.payerPv(discount, projection)` (`receiverPv` for the other side) |
| `SwapPricer.annuity(YieldCurve, tenor)` | `VanillaSwap.annuity(discount)` or `FixedLeg.annuity(discount)` |
| `SwapPricer.dv01(YieldCurve, tenor, k)` | reprice on a bumped curve, or per quote with `BootstrapJacobian.bucketedDv01` |
| `SwapPricer.parRate / payerPv / annuity(DiscountCurve, ..., InterestRateSwap)` | the same methods on `InterestRateSwap` itself (`swap.parRate(d, p)`, ...) |
| `SwapPricer.dv01(DiscountCurve, DiscountCurve, InterestRateSwap)` | `MultiCurveRisk.bucketedDv01` / `BootstrapJacobian.bucketedDv01` |

Worked example (COOKBOOK recipe 104 in full):

```java
// 3.0
double par = SwapPricer.parRate(yieldCurve, 5);

// 3.1
LegConvention annual = LegConvention.of(1, DayCount.ACT_360, cal, BusinessCalendar.Roll.MODIFIED_FOLLOWING);
VanillaSwap swap = new VanillaSwap(new FixedLeg(spot, spot.plusYears(5), 0.04, annual),
        new OvernightLeg(spot, spot.plusYears(5), 0, annual, OvernightConvention.IN_ARREARS, FixingHistory.empty()));
double par = swap.parRate(curve, curve);   // curve: a DiscountCurve
```

The results are not the same number: the old method uses `tau = 1` and
annual tenors off a `YieldCurve`, the new one the actual accrual fractions
and business-day schedule. In the special case of no lag and no lookback,
`VanillaSwap` equals `OisSwap` (and, with a `TermRateLeg`,
`InterestRateSwap`) to 1e-14 (`VanillaSwapTest`). Lookback, observation
shift, lockout and payment lag are explained in [LEARN 10d.1](learn/02-risk-derivatives-rates-credit.md#10d1-lookback-observation-shift-lockout-and-payment-lag).

## 2. `BondPricer` with non-integral maturities

`priceFromYield`, `macaulayDuration`, `modifiedDuration`, `convexity`, `dv01` and
`yieldToMaturity` round the maturity to whole coupon periods. They are not
annotated `@Deprecated` (they are correct for whole periods), but their use
with a non-integral maturity is deprecated in the javadoc since 3.1.0. Check
with `BondPricer.isWholePeriods(frequency, years)` and use the `...Exact`
functions otherwise (`priceFromYieldExact`, `accruedInterestExact`,
`macaulayDurationExact`, `modifiedDurationExact`, `convexityExact`,
`yieldToMaturityExact`). On whole periods they equal the old functions to
1e-12. COOKBOOK recipe 361.

## 3. Small behaviour notes from 3.1 part 2

Nothing here needs a code change, but a test that pins exact values may notice.

- `LeiRecord` has a new `entityCategory` component. The 3-argument constructor
  still works and gives `""`. Equality and `toString` now include the category,
  so two records that differ only in category are no longer equal.
- `CatEvent.rules()` has 21 entries, `CatLinkageChecker.rules()` 5 and
  `EmirStatefulValidator.rules()` 28. A test that asserts the size of a rule
  catalogue needs its number updated; results for the earlier event types and
  rules are unchanged.
- `CatEventType.isOption()` is true for the new option types MONOS and MOOA to
  MOOF.
- The FIRDS parser reports malformed bytes and an unknown encoding name as an
  `IOException` and no longer prints to stderr.
- `HftOrderBook` allocates its trade buffer up front (36 bytes per order of
  capacity), so a very large `maxOrders` costs that memory at construction.
