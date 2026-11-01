# Docs notes for the multi-curve work (worker `rates`, plan W1.1 and W1.2)

## What to say

- Multi-curve: since 2008 the index (3M) forwards and the discount rate (OIS, for collateralised trades) come from different curves. `MultiCurveBootstrap.bootstrap` builds the OIS curve from overnight deposits and `OisSwap`s; `MultiCurveBootstrap.bootstrapProjection` builds a projection curve from `Deposit`, `Fra`, `RateFuture` and `InterestRateSwap`, discounting on the OIS curve. Sources: Bianchetti 2010 (Risk 23(8)); Ametrano and Bianchetti 2013 (SSRN 2219548).
- Formulas:
  - OIS par: `K = (P(s) - P(e)) / sum tau_i P(t_i)` (compounded overnight leg telescopes).
  - IRS par: `K = sum tau_j F_j P_ois(t_j) / sum tau_i P_ois(t_i)`, `F_j = (P_3m(t_{j-1})/P_3m(t_j) - 1)/tau_j`.
  - Futures convexity (Hull-White, Hull OFOD Technical Note 1; Kirikos-Novak 1997): `CA = B(t1,t2)/(t2-t1) [B(t1,t2)(1-e^{-2a t1}) + 2a B(0,t1)^2] sigma^2/(4a)`, Ho-Lee limit `sigma^2 t1 t2 / 2`.
  - Interpolation on `-ln P`: log-linear (piecewise-flat forwards) or monotone-convex (Hagan and West 2006, Applied Mathematical Finance 13(2)): continuous forwards, interval averages kept, monotone discrete forwards stay monotone. No positivity amendment, negative rates allowed.
- Risk: `MultiCurveRisk.bucketedDv01`, `keyRateDv01` (Ho 1992 hat weights), `parallelDv01`; central difference of a 1bp quote bump with full rebuild. Note for LEARN: monotone-convex is not differentiable in its inputs at zone changes, so buckets add to the parallel DV01 only to a few percent at 1bp (log-linear: 1e-7).
- Pricing: `SwapPricer.parRate/payerPv/annuity/dv01(discount, projection, swap)`, `RatesOptions.swaption(discount, projection, swap, K, vol, payer, model)` and `RatesOptions.capFloor(discount, projection, start, end, freq, dayCount, calendar, roll, K, vol, cap, model)`. The single-curve methods are unchanged.

## One example (COOKBOOK)

```java
LocalDate val = LocalDate.of(2026, 11, 2);
BusinessCalendar cal = BusinessCalendar.weekendsOnly();
LocalDate spot = cal.addBusinessDays(val, 2);
List<CurveInstrument> ois = List.of(
    new Deposit(val, cal.addBusinessDays(val, 1), 0.038, DayCount.ACT_360),
    new OisSwap(spot, spot.plusYears(1), 0.036, 1, DayCount.ACT_360, cal, BusinessCalendar.Roll.MODIFIED_FOLLOWING),
    new OisSwap(spot, spot.plusYears(5), 0.035, 1, DayCount.ACT_360, cal, BusinessCalendar.Roll.MODIFIED_FOLLOWING));
List<CurveInstrument> threeM = List.of(
    new Deposit(spot, spot.plusMonths(3), 0.0395, DayCount.ACT_360),
    RateFuture.ofPrice(spot.plusMonths(9), spot.plusMonths(12), 96.25, DayCount.ACT_360, 0.03, 0.01),
    new InterestRateSwap(spot, spot.plusYears(5), 0.0369, 1, DayCount.THIRTY_360, 4, DayCount.ACT_360,
        cal, BusinessCalendar.Roll.MODIFIED_FOLLOWING));
CurvePair c = MultiCurveRisk.buildCurves(val, ois, threeM, CurveInterpolation.MONOTONE_CONVEX);
InterestRateSwap swap = (InterestRateSwap) threeM.get(2);
double pv = SwapPricer.payerPv(c.discount(), c.projection(), swap);            // 0 to 1e-12
double[] buckets = MultiCurveRisk.bucketedDv01(val, ois, threeM,
    CurveInterpolation.MONOTONE_CONVEX, swap::payerPv);
```

## Tests to cite
`MultiCurveTest`: repricing to 1e-10 for both interpolations; OIS strip against the closed-form recursion `P_n = (1 - K sum_{i<n} tau_i P_i)/(1 + K tau_n)` to 1e-13; monotone-convex continuity, derivative and interval-average checks; Ho-Lee limit; a par input swap's bucketed DV01 is `A_ois * 1bp` in its own bucket and zero elsewhere.

No published bootstrap example was verified offline; the independent reference is the closed-form recursion above.
