# Docs notes for the exotic workstream (W2.4-W2.7)

## Pay-at-hit and double touches (W2.4), `pricing.TouchOption`
- `oneTouchPayAtHit(spot, barrier, rate, carry, vol, T, payout)`: Reiner-Rubinstein (1991) rebate term as in Haug (2007) section 4.17.3; it is the Laplace transform E[e^{-r tau} 1{tau <= T}]. FX: rate = domestic, carry = foreign. Equals `oneTouch` when rate = 0.
  Example: `TouchOption.oneTouchPayAtHit(1.10, 1.20, 0.03, 0.045, 0.12, 0.5, 1_000_000)`.
- `doubleNoTouch(spot, lower, upper, rate, carry, vol, T, payout)` and `doubleOneTouch`: Ikeda-Kunitomo (1992) image series with flat barriers, n = -50..50 (`DOUBLE_TOUCH_SERIES_TERMS`); tail negligible for ln(U/L)/(vol sqrt T) >= 0.1. Tests cross-check the eigenfunction series to 1e-10.
- LEARN: pay-at-hit is worth more than pay-at-expiry by the discounting from hit time to expiry.

## Heston COS and calibration (W2.5), `pricing.HestonCos`, `pricing.HestonCalibrator`
- COS: Fang-Oosterlee (2008) eq. 30-33; put priced, call by parity; interval from cumulants c1, c2 (Table 11). Defaults N = 1024, L = 32: the paper's L = 12 leaves 4.5e-5 error on its own test case (far from Feller).
- Reference: S=K=100, r=q=0, T=1, kappa 1.5768, theta 0.0398, sigmaV 0.5751, rho -0.5711, v0 0.0175 gives 5.7851554344; the paper prints 5.785155450 (1.6e-8 apart); the Simpson pricer in `Heston` agrees to 2e-9.
- Greeks: `delta`, `gamma`, `vegaV0`, `parameterGradient` are exact derivatives of the truncated series (forward-mode complex dual numbers through the characteristic function), interval held fixed.
- Calibration: LM on vega-weighted price residuals (approximate implied-vol error), analytic Jacobian, box constraints, Feller not imposed. Example:
  `HestonCalibrator.calibrate(spot, r, q, strikes, maturities, vols, new Heston.Params(1, 0.04, 0.5, -0.5, 0.04))` returns params, implied-vol RMSE, iterations, converged.
- COOKBOOK: "fit Heston to a surface, then price an exotic by `Heston.callMonteCarlo`".

## Seasoned Asians (W2.6), `pricing.AsianOption`
- `turnbullWakemanSeasoned`, `curranPrice(type, S, K, r, q, vol, remainingFixingTimes, T, observedSum, observedCount)`; reduction K* = (nK - A_obs)/m, price = (m/n) * option on remaining average (Haug 2007, 4.20.1). K* <= 0: call is the discounted forward, put 0.
- Curran (1994): conditioning on the geometric mean; error well below 0.2% at 30% vol in the tests against a geometric control-variate Monte Carlo.
- `levyPrice(type, S, K, r, q, vol, timeLeft, averagingPeriod, realisedAverage)`: continuous average, Haug 4.20.2; continuous limit of Turnbull-Wakeman (tested).

## Discrete dividends on a tree (W2.7), `pricing.DividendTree`
- Vellekoop-Nieuwenhuis (2006): recombining CRR lattice of stock levels; at an ex-date V_cum(S) = max(exercise(S), V_ex(S - D)) with linear interpolation in S. Ex-dates snap to the nearest step (error <= dt/2).
- Tests: equals `BinomialTree` with no dividends; exact one-dividend integral for European and American calls (within 6e-3 at 2000 steps); American call early exercise just before a large ex-date.
- Example: `DividendTree.price(CALL, AMERICAN, 100, 90, 0.05, 0, 0.25, 0.5, DividendSchedule.of(new double[]{0.46}, new double[]{6}), 2000)`.
- `DividendSchedule` javadoc now points to `DividendTree` for American exercise.
