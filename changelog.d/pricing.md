### Fixed
- `QuantoOption` drifted the foreign asset at the domestic rate. It now takes a `foreignRate`: forward `S·exp((r_f − q − ρσ_Sσ_X)T)`, discounted at `r_d`. The test that pinned the old `r_f = r_d` identity was replaced by a closed-form pin with `r_f ≠ r_d` and a Monte Carlo check under the foreign measure.
- `MathUtils.normCdf` was the Abramowitz-Stegun approximation (7.5e-8 absolute error). It is now Cody's rational erfc algorithm, accurate to about 1e-15 relative down to x = −37.5. `normInv` gains a Halley refinement step and is accurate to double precision.
- `BlackScholes.impliedVol` and `Black76.impliedVol` used 100-200 step bisection. They now use a safeguarded Newton solver on the normalised Black function (`ImpliedVolatility`) that inverts the out-of-the-money option, and recover σ to near machine precision in deep wings and at very short and long expiries. A price equal to intrinsic now gives 0, not NaN.
- `BlackScholes` delta, gamma, vega, theta and rho returned NaN or ±Inf at zero volatility. They now return the deterministic limits.
- `BinomialTree` returned NaN silently when volatility was zero; it now throws.
- `FxVolSurface` treated the broker (market strangle) butterfly as the smile butterfly. By default it now solves for the smile butterfly that reprices the market strangle (Clark 2011), for 25-delta and 10-delta jointly. Use `ButterflyQuote.SMILE_STRANGLE` for the old reading.
- `BusinessCalendar` schedules drifted to the 28th after a February coupon because each date was stepped from the previous one. Dates are now computed from a fixed anchor.
- `BondPricer` date-based pricing paid day-count-weighted coupons (2.975/3.025 on a 6% ACT/ACT bond) and discounted with year fractions. It now uses the street convention: round coupons `c/f`, ACT/ACT ICMA accrual and fractional-period exponents. It reproduces Microsoft's published PRICE and YIELD examples.
- `VannaVolga.impliedVol` inverted a call at every strike, losing accuracy on the put wing. It now inverts the out-of-the-money option, and beyond the 10-delta strikes it holds the vol flat or continues it smoothly instead of using the unbounded quadratic weights.
- `SabrModel.Params.rmse` was documented in vol points but returned in decimal vol; the documentation now says decimal vol.
- `ForwardCurve.forward` threw a NullPointerException on a curve with no points, and `impliedRateDifferential(0)` returned NaN. Both now throw clear exceptions.
- `MathUtils.variance`, `stdDev`, `stdDevSample` and `stdDevP` returned 0 for too few observations. They now return NaN.

### Changed
- `QuantoOption.price` and `quantoForward` signatures now include `foreignRate` (breaking).
- `BondPricer.dirtyPrice`, `cleanPrice` and `accruedInterest` drop the calendar and roll arguments, since the street convention discounts on unadjusted dates. They gain an optional end-of-month flag (breaking).
- `BarrierOption` regular-barrier methods now price reverse barriers instead of throwing. Breached barriers still throw.
- `SabrModel.calibrate` is now Levenberg-Marquardt, replacing random search plus coordinate descent. It recovers parameters to about 1e-8 instead of 1e-2.
- `VolSurface` interpolates across expiries at fixed log-moneyness when forwards are supplied.
- `AsianOption.arithmeticPrice` computes the Turnbull-Wakeman second moment in O(n) instead of O(n²).
- `Heston` Monte Carlo and `Autocallable` use `SplittableRandom` instead of `java.util.Random`, so seeded results differ from v1.
- The `Ndf` default fixing-lag table (BRL and CLP in particular) is documented as unverified against the EMTA templates. A new overload takes an explicit lag.

### Added
- `ImpliedVolatility`, a Black implied-vol solver and normalised Black function.
- `Bachelier`, the normal model, with price, delta, vega and implied normal vol.
- `RatesOptions.VolModel` (lognormal, shifted lognormal, normal). Swaptions and caps/floors now support any accrual frequency and negative rates, and `swaptionImpliedVol` returns implied vol for each model.
- `BarrierOption.price` covers all eight barrier types with cash rebates (Reiner-Rubinstein, pinned to Haug table 4-13).
- `FxVolSurface` gains `DeltaConvention` (forward or spot, with or without premium adjustment), `AtmConvention` (DNS or ATM forward), `ButterflyQuote`, spot-delta strike solving and `smileButterfly25/10`.
- `BusinessCalendar.StubType` (short or long, front or back) and an end-of-month rule. New day counts: `DayCount.THIRTY_E_360`, `THIRTY_E_360_ISDA` (with termination-date form), `ACT_ACT_ICMA`, `actActIcma`, and `bus252`.
- `BondPricer.yieldFromCleanPrice`, a street-convention yield.
- `SabrModel.calibrate` with weights, `calibrateToAtm` (alpha from West's ATM cubic), `alphaFromAtmVol` and the `minimumDensity` butterfly check.
- `VolSurface.Builder.forward`, `forwardAt`, `calendarViolations` and `minimumDensity`.
- `DigitalOption.cashOrNothingSkewAdjusted`, a smile-consistent digital (call-spread limit).
- `IncrementalGreeks.onTick(spot, yearsSinceAnchor)` includes theta decay between anchors.
- `DividendSchedule.bosVandermarkPrice`, a dividend split between spot and strike.
- `SwapPointsCurve.Builder.preSpot` for today and tomorrow outrights, and `impliedCarry` with a day count.
- `MathUtils.logNormPdf`.
