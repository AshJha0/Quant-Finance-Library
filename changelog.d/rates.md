### Added
- `rates.DiscountCurve`: dated discount curve on ACT/365F time with log-linear discount or Hagan-West (2006) monotone-convex interpolation.
- `rates.MultiCurveBootstrap`: OIS discount curve and projection curves (discounted on OIS), pillar-by-pillar Newton then a global Newton solve; every input reprices to 1e-12.
- Curve instruments `Deposit`, `Fra`, `RateFuture` (Hull-White convexity adjustment), `OisSwap` and `InterestRateSwap` behind the sealed `CurveInstrument` interface, using `DayCount` and `BusinessCalendar` schedules.
- `rates.MultiCurveRisk`: bucketed, Ho (1992) key-rate and parallel DV01 by bumping quotes and rebuilding the curve pair; `CurvePair` record.
- `SwapPricer` overloads (annuity, par rate, payer PV, DV01) and `RatesOptions` overloads (swaption, cap/floor) taking a discount curve and a projection curve.
