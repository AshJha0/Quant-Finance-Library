# Appendix, the formula reference

[Index: LEARN.md](../LEARN.md) | [Previous: How to use this guide](16-how-to-use-this-guide.md)

---

## Appendix -- The formula reference

The library's main formulas, each with one line of math and one line of
meaning, in the conventions the code implements. Each entry names the
class (and method where useful) and a published source. Where the code's
convention differs from a textbook's, the code's convention is the one
written here. `N` is the standard normal CDF and `phi` its density
throughout.

**Options and exotics**

- `d1 = (ln(S/K) + (r - q + sigma^2/2) T) / (sigma sqrt(T))`,
  `d2 = d1 - sigma sqrt(T)`;
  `call = S e^{-qT} N(d1) - K e^{-rT} N(d2)`. The Black-Scholes vanilla
  with a continuous yield. The code's `carry` parameter is the yield q
  (dividend yield for equities, foreign rate for FX), not the net
  cost of carry. `pricing/BlackScholes.java`; Black and Scholes (1973),
  Merton (1973) for the yield.
- `call = e^{-rT} (F N(d1) - K N(d2))`,
  `d1 = (ln(F/K) + sigma^2 T/2) / (sigma sqrt(T))`. Black-76 on a
  forward: discounting and carry separate cleanly, which makes it the
  quoting standard for futures options, caps and swaptions.
  `pricing/Black76.java`; Black (1976).
- `call - put = S e^{-qT} - K e^{-rT} = e^{-rT} (F - K)`. Put-call
  parity, model-free; the swaption version `payer - receiver =
  annuity * (F - K)` is pinned in `rates/RatesOptions.java`. Stoll (1969).
- `sigma^2 = sigma1^2 + sigma2^2 - 2 rho sigma1 sigma2`. Margrabe: an
  option to exchange one asset for another is a vanilla on the ratio of
  the two, priced with the ratio's variance and zero rate.
  `pricing/ExchangeOption.java`; Margrabe (1978).
- `sigma_K^2 = sigma1^2 - 2 rho sigma1 sigma2 f + sigma2^2 f^2`,
  `f = F2 / (F2 + K)`. Kirk's approximation for a spread option: treat
  `F2 + K` as one lognormal leg, then apply Margrabe; exact when K = 0.
  `pricing/ExchangeOption.java`; Kirk (1995).
- `F_quanto = S exp((r_f - q - rho sigma_S sigma_X) T)`, price
  `= e^{-r_d T} [w F_quanto N(w d1) - w K N(w d2)]` with vol sigma_S. The
  quanto forward: the foreign asset drifts at its own rate r_f, and paying
  the foreign payoff at a fixed conversion rate shifts the drift by the
  asset-FX covariance (rho is the correlation of the asset with the FX rate
  quoted as domestic per foreign). Discounting is at the domestic rate r_d.
  `pricing/QuantoOption.java` (`quantoForward(spot, foreignRate,
  assetYield, ...)`, `price`); Reiner (1992); Hull, *Options, Futures and
  Other Derivatives*, quanto section.
- `sigma^2 = (2/T) sum_i (dK_i / K_i^2) e^{rT} Q(K_i) - (1/T) (F/K0 - 1)^2`.
  The variance-swap and VIX replication: a 1/K^2-weighted strip of
  out-of-the-money options replicates realised variance with no model.
  `volatility/VolatilityIndex.java`, `pricing/VarianceSwap.java`;
  Demeterfi, Derman, Kamal and Zou (1999); Cboe VIX white paper.
- `K_vol ~ sqrt(E[V]) - Var(V) / (8 E[V]^{3/2})`. Brockhaus-Long: a vol
  swap strikes below the square root of the variance strike (Jensen), by
  an amount that needs a vol-of-vol model, which is why vol swaps are not
  model-free. `pricing/VarianceSwap.java`; Brockhaus and Long (2000).
- Garman-Kohlhagen is Black-Scholes with `q = r_foreign`: an FX option is
  an option on an asset whose "dividend" is the foreign interest rate.
  `pricing/BlackScholes.java` (carry = foreign rate); Garman and Kohlhagen
  (1983).
- `call = df [(F - K) N(d) + sigma_N sqrt(T) phi(d)]`,
  `put = df [(K - F) N(-d) + sigma_N sqrt(T) phi(d)]`,
  `d = (F - K) / (sigma_N sqrt(T))`. Bachelier (normal) model: the forward
  follows `dF = sigma_N dW`, so forwards and strikes may be negative and
  sigma_N is in absolute rate units (0.0080 = 80 bp a year).
  `pricing/Bachelier.java` (price, delta, vega, impliedVol); Bachelier
  (1900); Andersen and Piterbarg (2010), vol. 1, section 7.1.
- `sigma_impl(f, k) ~ alpha / (fk)^{(1-beta)/2} * z/x(z) * (1 +
  correction * T)`. Hagan's SABR lognormal implied-vol expansion: beta
  fixed by convention, (alpha, rho, nu) calibrated to the smile by
  Levenberg-Marquardt; `calibrateToAtm` takes alpha from the ATM cubic.
  `pricing/SabrModel.java`; Hagan, Kumar, Lesniewski and Woodward (2002);
  West (2005) for the ATM cubic.
- `dv = kappa (theta - v) dt + sigma_v sqrt(v) dW2`,
  `d<W1,W2> = rho dt`. Heston stochastic variance, priced by integrating
  the "little trap" form of the characteristic function, which is stable
  at long maturities. The Feller ratio `2 kappa theta / sigma_v^2 >= 1`
  keeps v strictly positive. `pricing/Heston.java`; Heston (1993),
  Albrecher, Mayer, Schoutens and Tistaert (2007).
- `u = e^{sigma sqrt(dt)}`, `d = 1/u`,
  `p = (e^{(r-q) dt} - d) / (u - d)`. The CRR binomial tree's
  risk-neutral up-probability; it converges to Black-Scholes as dt
  goes to 0. Zero volatility throws rather than returning NaN.
  `pricing/BinomialTree.java`; Cox, Ross and Rubinstein (1979).

**Volatility models**

- `h_t = omega + alpha r_{t-1}^2 + beta h_{t-1}` on demeaned returns.
  GARCH(1,1): persistence `alpha + beta`, unconditional variance
  `omega / (1 - alpha - beta)`. Fitted by full maximum likelihood over
  (omega, alpha, beta), with omega estimated rather than targeted:
  the Gaussian negative log-likelihood
  `(1/2) sum_t (ln 2pi + ln h_t + r_t^2 / h_t)` is minimised by
  Nelder-Mead on parameters transformed so that omega > 0 and
  alpha + beta < 1, from several starts, with `h_0` the sample variance.
  `estimate(returns, Innovation)` adds Student-t innovations and
  Bollerslev-Wooldridge robust standard errors.
  `volatility/Garch11.java`; Bollerslev (1986), Bollerslev and Wooldridge
  (1992).
- `h_t = omega + (alpha + gamma 1{r_{t-1} < 0}) r_{t-1}^2 +
  beta h_{t-1}`. GJR-GARCH: down moves add gamma extra variance (the
  leverage effect); persistence `alpha + gamma/2 + beta` for symmetric
  innovations. Fitted by maximum likelihood as above.
  `volatility/GjrGarch11.java`; Glosten, Jagannathan and Runkle (1993).
- `ln h_t = omega + beta ln h_{t-1} + alpha (|z_{t-1}| - sqrt(2/pi)) +
  gamma z_{t-1}`, `z = r / sqrt(h)`. EGARCH: log variance needs no
  positivity constraints, and gamma carries the asymmetry.
  `volatility/Egarch11.java`; Nelson (1991).
- `h_t = lambda h_{t-1} + (1 - lambda) r_{t-1}^2`. RiskMetrics EWMA:
  GARCH with no mean reversion and one parameter. The recursion is seeded
  causally with bias-corrected weights rather than with the full-sample
  variance, which would leak the future into early values; an overload
  takes an explicit seed. `volatility/EwmaVolatility.java`;
  J.P. Morgan/Reuters, *RiskMetrics Technical Document* (1996).
- `RV_{t+1} = c + b_d RV_t + b_w RV_{t-4:t} + b_m RV_{t-21:t}`. HAR-RV:
  tomorrow's realised variance regressed on daily, weekly and monthly
  averages, fitted by Householder QR; `fitWithErrors` adds Newey-West
  standard errors and `fitLog` fits the log form.
  `volatility/HarRv.java`; Corsi (2009).
- `BV = (pi/2) sum |r_t| |r_{t-1}|`. Bipower variation: products of
  consecutive absolute returns are robust to jumps (a jump enters one
  factor, not a square), so RV minus BV isolates the jump part.
  `microstructure/JumpRobustVolatility.java`; Barndorff-Nielsen and
  Shephard (2004).
- `beta = Cov(r_a, r_m) / Var(r_m)`;
  `sigma_a^2 = beta^2 sigma_m^2 + sigma_eps^2`. Realised beta and the
  single-factor variance decomposition; R^2 is the systematic share.
  `volatility/VolatilityDecomposition.java`, `risk/RiskMetrics.java`;
  Sharpe (1964).

- `w(k) = a + b (rho (k - m) + sqrt((k - m)^2 + sigma^2))`, `k = ln(K/F)`,
  `w = sigma_BS^2 T`. Raw SVI. `pricing/SviSlice.java`; Gatheral (2004);
  Gatheral and Jacquier (2014), eq. 3.1.
- `g(k) = (1 - k w'/(2w))^2 - (w'^2/4)(1/w + 1/4) + w''/2 >= 0`. No
  butterfly arbitrage in a slice, if and only if.
  `SviSlice.densityFactor`; Gatheral and Jacquier (2014), Lemma 2.2.
- `w = theta/2 (1 + rho phi k + sqrt((phi k + rho)^2 + 1 - rho^2))`,
  `phi = eta / (theta^gamma (1 + theta)^(1 - gamma))`; free of static
  arbitrage for `gamma` in [0, 1/2], `eta (1 + |rho|) <= 2` and
  non-decreasing `theta_t`. SSVI with a power-law `phi`.
  `pricing/SsviSurface.java`; Gatheral and Jacquier (2014), Theorems 4.1
  and 4.2, Remark 4.4.
- `sigma_loc^2(y, T) = (dw/dT) / g(y)`, `y = ln(K/F)`. Dupire local
  variance in total-variance form. `pricing/DupireLocalVol.java`;
  Dupire (1994); Gatheral (2006), eq. 1.10.
- `V = K e^{-rT} sum'_{k<N} Re{phi(k pi/(b-a)) e^{i k pi (x-a)/(b-a)}} U_k`,
  `[a, b] = [x + c1 - L sqrt(c2), x + c1 + L sqrt(c2)]`. COS pricing of a
  European put under Heston; the call by parity; `N = 1024`, `L = 32` by
  default. `pricing/HestonCos.java`; Fang and Oosterlee (2008), eqs. 30
  to 33 and Table 11.

**Risk**

- `VaR = z_c * sqrt(w' Sigma w)`. Delta-normal VaR: the Gaussian loss
  quantile of a linear book. `risk/VarEngine.java`; *RiskMetrics
  Technical Document* (1996).
- `q_loss(p) = -mu + sigma (z_p + (z_p^2 - 1) s/6)`. The Cornish-Fisher
  quantile (s = loss skew) for delta-gamma books: short gamma fattens the
  loss tail and the expansion prices that in. `risk/VarEngine.java`
  (deltaGammaVar); Cornish and Fisher (1937), Zangari (1996).
- `ES = -mu + sigma * phi(z_c) / (1 - c)`. Gaussian expected shortfall in
  closed form; the code's Cornish-Fisher version multiplies the tail term
  by `(1 + z_c s/6)` and reduces to the Gaussian value at zero skew.
  `risk/VarEngine.java` (deltaGammaEs); McNeil, Frey and Embrechts (2015),
  section 2.3.
- `VaR_p = u + (beta/xi) (((1-p) / (N_u/n))^{-xi} - 1)`;
  `ES = (VaR + beta - xi u) / (1 - xi)`. The generalised Pareto tail over
  threshold u (peaks over threshold): extrapolate quantiles beyond the
  sample along the fitted tail. For xi >= 1 the tail mean does not exist
  and the code refuses. `fitPotMle` fits by maximum likelihood with
  standard errors. `risk/ExtremeValueTheory.java`; McNeil and Frey (2000);
  McNeil, Frey and Embrechts (2015), section 5.2.
- `marginal_i = (Sigma w)_i / sigma_p`;
  `component_i = w_i * marginal_i`, `sum_i component_i = sigma_p`. Euler
  risk allocation: components sum exactly to portfolio risk, and a
  hedge's component is negative. `risk/ComponentVar.java`; Tasche (1999).
- `Sigma* = delta * mu I + (1 - delta) S`, `delta = bbar^2 / d^2`.
  Ledoit-Wolf shrinkage: pull the noisy sample covariance toward the
  scaled identity, with intensity estimated from the data;
  `constantCorrelation` shrinks toward the constant-correlation target
  instead. `risk/CovarianceShrinkage.java`; Ledoit and Wolf (2004, 2003).
- `f* = mu / sigma^2`. The continuous-time Kelly fraction; `halfKelly` is
  the usual practice, since the estimate of mu is noisy.
  `backtest/portfolio/PositionSizing.java`; Kelly (1956), Thorp (2006).

**Statistics**

- `h = (n - 1) p`, `Q(p) = x[floor h] + (h - floor h) (x[floor h + 1] - x[floor h])`
  on the sorted sample, zero-based. The type 7 quantile: linear interpolation between
  order statistics, the default in R and NumPy. `stats/Stats.java` (`quantile`,
  `quantileSorted`; `median` is type 7 at p = 0.5); Hyndman and Fan (1996), The
  American Statistician 50(4), 361-365, definition 7. For 1, 2, 3, 4 and p = 0.25,
  h = 0.75 and Q = 1 + 0.75 (2 - 1) = 1.75.
- `Q(p) = x[ceil(n p) - 1]` (zero-based). The type 1 quantile, the inverse of the
  empirical CDF, with no interpolation. `stats/Stats.java`
  (`quantileType1Sorted`); Hyndman and Fan (1996), definition 1. For 1, 2, 3, 4 and
  p = 0.25 it is 1.

**Performance and selection bias**

- `Sharpe = (mean(r) * periods - rf) / (stdev(r) * sqrt(periods))`;
  Sortino replaces stdev with downside deviation below the MAR;
  `IR = active return / tracking error`. The ratio family.
  `risk/RiskMetrics.java`, `backtest/BenchmarkComparison.java`; Sharpe
  (1966, 1994), Sortino and Price (1994).
- `PSR = N((SR - SR*) sqrt(n-1) / sqrt(1 - g3 SR + (g4-1)/4 SR^2))`.
  Probabilistic Sharpe: the probability that the true Sharpe exceeds SR*,
  with the estimator's variance widened by skew g3 and kurtosis g4 (raw,
  not excess). `backtest/validation/SharpeValidation.java`; Bailey and
  López de Prado (2012).
- `SR* = sd * ((1-gamma_E) z_{1-1/K} + gamma_E z_{1-1/(K e)})`. The
  expected best Sharpe of K zero-skill trials (gamma_E is Euler's
  constant); the deflated Sharpe is the PSR against this benchmark
  instead of zero. `GridSearchOptimizer.effectiveTrials` supplies K for a
  grid of correlated trials. `backtest/validation/SharpeValidation.java`;
  Bailey and López de Prado (2014).
- `n* = 1 + (1 - g3 SR + (g4-1)/4 SR^2) (z_conf / (SR - SR*))^2`.
  Minimum track record length: how many periods of this performance are
  needed before the PSR clears the confidence level.
  `backtest/validation/SharpeValidation.java`; Bailey and López de Prado
  (2012).

**Execution and microstructure**

- `x_j = X sinh(kappa (T - t_j)) / sinh(kappa T)`,
  `cosh(kappa tau) = 1 + lambda sigma^2 tau^2 / (2 etaTilde)` with
  `etaTilde = eta - gamma tau/2`. Almgren-Chriss: the urgency kappa
  balances temporary-impact cost against variance risk; as kappa goes to
  0 the schedule becomes TWAP. `microstructure/AlmgrenChriss.java`;
  Almgren and Chriss (2000), section 3.
- `executed fraction(f) = 1 - sinh(kT (1 - f)) / sinh(kT)`, f the elapsed
  fraction of the horizon. The same trajectory as cumulative progress,
  used by `BenchmarkExecutor.IMPLEMENTATION_SHORTFALL` with urgency kT
  from `withShortfallUrgency` (default 2). `execution/BenchmarkExecutor.java`;
  Almgren and Chriss (2000).
- `impact = Y * sigma_daily * sqrt(Q / ADV)`. The square-root impact law:
  cost scales with the square root of size, in daily-vol units.
  `MarketImpactModel.estimate` uses only bars before the trade bar.
  `microstructure/MarketImpactModel.java`; Grinold and Kahn (2000),
  Almgren, Thum, Hauptmann and Li (2005).
- `dp = lambda q + noise`, `lambda = E[q dp] / E[q^2]`. Kyle's lambda:
  price impact per unit of signed net flow over one aggregation window,
  estimated from the tape by regression through the origin.
  `microstructure/KylesLambda.java`; Kyle (1985).
- `target = p / (1 - p) * othersVolume`, so that
  `executed / (executed + others) = p`. POV as a share of total volume.
  Targeting `p * totalVolume` with your own fills included realises only
  `p / (1 + p)` (9.1% at p = 10%). `realizedParticipation()` reports
  `executed / (executed + others)`, and p must be below 1.
  `execution/PovTracker.java`, `execution/BenchmarkExecutor.java`.

- `V(p) = min(D(p), S(p))`; uncross at the price maximising `V`, then
  minimising `|D - S|`, then nearest the reference price. Call auction
  price determination (maximum executable volume). `orderbook/AuctionBook.java`;
  Xetra and Euronext auction rules without their market-pressure step.
- `a' = (n a + q p)/(n + q)` on an increase, realised `q (p - a) sgn(n) m`
  on a close; FIFO realised `sum_k q_k (p - p_k) sgn(n) m`; both satisfy
  `realised + unrealised = m (n M - sum_i s_i q_i p_i)`. Average-cost and
  FIFO P&L. `trading/PositionLedger.java`; IAS 2, para. 25.
- A FIX session period starts at each occurrence of the start time in the
  schedule's zone and ends at the next end time; equal times mean always
  open with a roll only. `fix/FixSessionSchedule.java`; FIX 4.4 errata
  and FIXT 1.1 for tag 789.

**Market making and hedging**

- `r = mid - q gamma sigma^2 tau`;
  `delta_total = gamma sigma^2 tau + (2/gamma) ln(1 + gamma/kappa)`.
  Avellaneda-Stoikov: the reservation price shades against inventory q,
  and the optimal spread adds a liquidity term from the fill-intensity
  decay kappa. `trading/AvellanedaStoikov.java`; Avellaneda and Stoikov
  (2008).
- `band = ((3/2) e^{-r(T-t)} k S Gamma^2 / lambda)^{1/3}`. Whalley-Wilmott:
  the no-trade band around delta under proportional cost k and risk
  aversion lambda. The cube root makes the band insensitive to its
  inputs, and you rehedge to the band edge, not the centre. The
  short overload omits the discount factor (r(T-t) = 0).
  `hedging/WhalleyWilmott.java` (bandHalfWidth); Whalley and Wilmott (1997).

**Rates**

- `D_mod = D_mac / (1 + y/f)`; `DV01 = D_mod * price * 1e-4`; convexity
  is the second-order term that makes duration hedges drift.
  `rates/BondPricer.java`; Macaulay (1938), Fabozzi, *Bond Markets,
  Analysis and Strategies*.
- `y(t) = b0 + b1 (1 - e^{-x})/x + b2 ((1 - e^{-x})/x - e^{-x})`,
  `x = t / lambda`. Nelson-Siegel: level, slope and curvature loadings.
  Lambda here is a time constant; some texts write x = lambda t.
  `rates/NelsonSiegel.java`; Nelson and Siegel (1987).
- `parRate = (1 - DF(T)) / annuity`, `annuity = sum tau_i DF(t_i)`;
  `payerPv = annuity * (parRate - K)`. The single-curve swap identities;
  the float leg is worth `1 - DF(T)` with no forecasting.
  `rates/SwapPricer.java`; Hull, *Options, Futures and Other
  Derivatives*, chapter on swaps.
- `F = (DF(start) - DF(end)) / annuity`;
  `swaption = annuity * Black76(F, K, sigma, T_expiry, rate = 0)`. The
  annuity-numeraire form: all discounting sits in the annuity, so the
  Black leg is called with rate zero. `RatesOptions.VolModel` selects
  lognormal, shifted lognormal or normal (Bachelier) vol, so negative
  rates and any accrual frequency are supported.
  `rates/RatesOptions.java`; Black (1976), Andersen and Piterbarg (2010).

- `K_OIS = (P(s) - P(e)) / sum_i tau_i P(t_i)`. OIS par rate: the
  compounded overnight leg telescopes to `P(s) - P(e)`. The tests check
  the strip against the recursion
  `P_n = (1 - K sum_{i<n} tau_i P_i) / (1 + K tau_n)` to 1e-13.
  `rates/OisSwap.java`, `rates/MultiCurveBootstrap.java`; Ametrano and
  Bianchetti (2013).
- `K_IRS = sum_j tau_j F_j P_ois(t_j) / sum_i tau_i P_ois(t_i)`,
  `F_j = (P_3m(t_{j-1})/P_3m(t_j) - 1)/tau_j`. Two-curve par swap rate:
  forwards from the projection curve, discounting on OIS.
  `rates/InterestRateSwap.java`, `rates/SwapPricer.java`; Bianchetti
  (2010).
- `CA = B(t1,t2)/(t2-t1) [B(t1,t2)(1 - e^{-2a t1}) + 2a B(0,t1)^2] sigma^2/(4a)`,
  `B(s,t) = (1 - e^{-a(t-s)})/a`; Ho-Lee limit `sigma^2 t1 t2 / 2`. The
  futures-over-forward convexity adjustment in Hull-White.
  `rates/RateFuture.java` (`hullWhiteConvexity`); Hull, *Options, Futures
  and Other Derivatives*, Technical Note 1; Kirikos and Novak (1997).
- Monotone-convex interpolation of `-ln P`: a piecewise quadratic in each interval
  for the instantaneous forward, matched to the discrete forward's
  interval average and continuous at the nodes, with the Hagan-West zone
  rules keeping monotone inputs monotone. No positivity amendment.
  `rates/DiscountCurve.java`, `rates/CurveInterpolation.MONOTONE_CONVEX`;
  Hagan and West (2006), Applied Mathematical Finance 13(2).

**Credit**

- `Q(t) = exp(-integral_0^t h(s) ds)`. Survival from the hazard rate;
  piecewise-constant h makes it a product of exponentials, exact.
  `credit/CreditCurve.java`; O'Kane (2008), chapter 3.
- `parSpread = protectionPv / riskyAnnuity`;
  `upfront = protectionPv - coupon * riskyAnnuity`. The CDS two-leg
  identities; the risky annuity is the desk's spread DV01.
  `credit/CdsPricer.java`; O'Kane (2008), chapter 6.
- `S ~ h (1 - R)`. The credit triangle: on a flat curve spread, hazard
  and recovery determine each other; 300 bp at 40% recovery means h of
  about 5%. `credit/CreditCurve.java`; O'Kane (2008).
- `price = sum cf_i exp(-(z(t_i) + z) t_i)`. The Z-spread z: one constant
  shift of the whole risk-free curve that reprices the bond;
  `zSpread - cdsParSpread` is the CDS-bond basis.
  `credit/CreditSpreads.java`; O'Kane (2008), chapter 5.

**Commodities, indexes and private markets**

- `rollYield = ln(F_near / F_far) / (t_far - t_near)`. Annualised roll
  yield, positive in backwardation: the return component paid by the
  curve shape, not by the spot view. `commodities/CommodityCurve.java`
  (annualizedRollYield); Erb and Harvey (2006).
- `F = S exp((r + u - y) t)`. Cash and carry: storage cost u and
  convenience yield y set the curve; `impliedCarry` reads `u - y` off the
  quotes as `ln(F/S)/t - r`. `commodities/CommodityCurve.java`; Hull,
  chapter on commodity futures.
- `sum_t cf_t / (1 + irr)^t = 0`. IRR, the money-weighted return
  (contributions negative, distributions positive). Without a sign change
  there is no IRR, and the code throws rather than returning one.
  `markets/PrivateMarketAnalytics.java`.
- `PME = (FV(distributions) + NAV) / FV(contributions)`. Kaplan-Schoar
  public-market equivalent, with every flow grown at the index; above 1
  the fund beat buying the index on its own dates.
  `markets/PrivateMarketAnalytics.java`; Kaplan and Schoar (2005).
- `r_true_t = (r_obs_t - phi r_obs_{t-1}) / (1 - phi)`. Geltner
  desmoothing: invert the AR(1) appraisal filter and the smoothed-away
  volatility comes back. `markets/PrivateMarketAnalytics.java`; Geltner
  (1993).
- `level = sum(price * shares * float) / divisor`;
  `newDivisor = oldDivisor * newAgg / oldAgg`;
  `turnover = 0.5 sum |w1 - w2|`. Index continuity and the cost of a
  weighting scheme. `markets/IndexConstruction.java`; S&P Dow Jones
  Indices, *Index Mathematics Methodology*.

**Black-Scholes greeks**

All greeks below are from `pricing/BlackScholes.java` or
`pricing/HigherOrderGreeks.java`; the reference is Haug, *The Complete
Guide to Option Pricing Formulas* (2007), chapter 2. At zero volatility
the code returns the deterministic limits rather than NaN.

- `delta_call = e^{-qT} N(d1)`, `delta_put = e^{-qT} (N(d1) - 1)`. The
  hedge ratio, shares held per option. The `e^{-qT}` factor matters: on
  FX and dividend-paying books a plain `N(d1)` hedge is systematically
  too large. (delta)
- `gamma = e^{-qT} phi(d1) / (S sigma sqrt(T))`, the same for calls and
  puts. Gamma is the rate at which delta drifts as spot moves, the
  rebalancing bill of a delta hedge. It explodes at the money near
  expiry, which is why pin risk is a gamma problem. (gamma)
- `vega = S e^{-qT} phi(d1) sqrt(T)`: price change per 1.00 of vol
  (divide by 100 for a vol point), the same for calls and puts, largest
  at the money at long maturity. (vega)
- `theta_call = -S e^{-qT} phi(d1) sigma / (2 sqrt(T))
  - r K e^{-rT} N(d2) + q S e^{-qT} N(d1)`: time decay per year (divide
  by 365 for a day). The first term is the cost of carrying gamma; the
  rate terms can make theta positive for deep in-the-money puts. (theta)
- `rho_call = K T e^{-rT} N(d2)`, `rho_put = -K T e^{-rT} N(-d2)`:
  sensitivity to the domestic rate per 1.00. It grows linearly with
  maturity, which is why it is ignored on weeklies and hedged on long
  dated options. (rho)
- `vanna = -e^{-qT} phi(d1) d2 / sigma`: d(delta)/d(sigma) =
  d(vega)/d(S), the same for calls and puts. The skew greek: a
  delta-hedged book with vanna loses on the down-spot, up-vol move that
  equity markets tend to make. (HigherOrderGreeks.vanna)
- `volga = vega * d1 d2 / sigma`: vega convexity (vomma), the same for
  calls and puts, positive in the wings and near zero at the money. A
  vega-hedged book with volga is exposed again as soon as vol moves.
  (HigherOrderGreeks.volga)
- `crossGamma = -phi(d1) / (S2 sigmaHat sqrt(T))`,
  `sigmaHat^2 = sigma1^2 + sigma2^2 - 2 rho sigma1 sigma2`: the
  two-asset second-order term d2V/dS1 dS2 of a Margrabe exchange option.
  It is negative because the option loses convexity when the legs move
  together; per-asset gammas miss it. (HigherOrderGreeks.exchangeCrossGamma)

**Digitals, barriers and touches**

- `cashOrNothing = payout * e^{-rT} N(s d2)`, s = +1 call, -1 put: a fixed
  payout if spot finishes beyond the strike. Near expiry and near the
  strike its delta is a spike, so desks risk-manage digitals as tight call
  spreads; `cashOrNothingSkewAdjusted` prices the call-spread limit on the
  smile. `pricing/DigitalOption.java`; Reiner and Rubinstein (1991b).
- `assetOrNothing = S e^{-qT} N(s d1)`, with the identity
  `vanilla = assetOrNothing - K * cashOrNothing(payout = 1)`. Every
  vanilla is a pair of digitals, which is why digital risk sits inside
  every option book. `pricing/DigitalOption.java`; Reiner and Rubinstein
  (1991b).
- `c_di = S e^{-qT} (H/S)^{2 lambda} N(y)
  - K e^{-rT} (H/S)^{2 lambda - 2} N(y - sigma sqrt(T))`,
  `lambda = (r - q + sigma^2/2)/sigma^2`,
  `y = ln(H^2/(S K))/(sigma sqrt(T)) + lambda sigma sqrt(T)`. The
  down-and-in call for H <= K, one of the Reiner-Rubinstein building
  blocks. `BarrierOption.price` covers all eight single-barrier types,
  including reverse barriers (barrier in the money), with cash rebates,
  and is pinned to Haug's table 4-13; a barrier already breached throws.
  `pricing/BarrierOption.java`; Reiner and Rubinstein (1991a), Haug (2007)
  section 4.17.
- `KO = vanilla - KI`. In-out parity: knock-in plus knock-out replicates
  the vanilla, so one formula gives the other. It holds without rebates;
  with a rebate the identity needs the rebate terms.
  `pricing/BarrierOption.java`; Haug (2007).
- `P(touch) = N((-h + mT)/(sigma sqrt(T)))
  + e^{2mh/sigma^2} N((-h - mT)/(sigma sqrt(T)))`, with
  `m = r - q - sigma^2/2` the log drift and `h = ln(H/S)` (upper barrier;
  the lower barrier mirrors the signs). The probability that GBM touches
  the barrier before T, by the reflection principle.
  `pricing/TouchOption.java` (hitProbability); Shreve, *Stochastic
  Calculus for Finance II* (2004), section 7.2.
- `oneTouch = payout e^{-rT} P(touch)`,
  `noTouch = payout e^{-rT} (1 - P(touch))`. The pay-at-expiry
  convention; pay-at-hit differs by discounting to the hitting time. Desks
  quote one-touches as discounted hit probabilities.
  `pricing/TouchOption.java`; Wystup, *FX Options and Structured
  Products* (2006).

- `payout [(H/S)^{mu+lambda} N(eta z) + (H/S)^{mu-lambda} N(eta z - 2 eta lambda sigma sqrt T)]`,
  `mu = (b - sigma^2/2)/sigma^2`, `lambda = sqrt(mu^2 + 2r/sigma^2)`,
  `z = ln(H/S)/(sigma sqrt T) + lambda sigma sqrt T`, `b = r - q`. One-touch
  paid at hit, `E[e^{-r tau} 1{tau <= T}]`; equal to the pay-at-expiry
  touch when `r = 0`. `pricing/TouchOption.oneTouchPayAtHit`; Reiner and
  Rubinstein (1991); Haug (2007), section 4.17.3.
- Double no-touch `= payout e^{-rT} P(L < min S, max S < U)`, with the
  survival probability from the image series over reflections in both
  barriers, `n = -50..50`. Double one-touch `= payout e^{-rT} - DNT`.
  `TouchOption.doubleNoTouch`, `doubleOneTouch`; Ikeda and Kunitomo
  (1992).
- `V = V_BS(sigma_ATM) + sum_i x_i (C_mkt,i - C_BS,i)` with `x` solving
  the 3 by 3 vega, vanna, volga match; for a one-touch the smile cost is
  multiplied by the survival probability. Exact vanna-volga.
  `pricing/VannaVolga.priceExact`, `fx/VannaVolgaTouch.java`; Castagna and
  Mercurio (2007); Wystup (2006).

**Asian options and structured notes**

- `E[ln G] = ln S + (r - q - sigma^2/2) T (n+1)/(2n)`,
  `Var[ln G] = sigma^2 T (n+1)(2n+1)/(6 n^2)`. Kemna-Vorst: the geometric
  average of lognormals is lognormal, so the geometric Asian has an exact
  closed form on these two moments. As n grows, Var tends to
  `sigma^2 T / 3`, which is why Asians are cheap. At n = 1 the formula is
  vanilla Black-Scholes. `pricing/AsianOption.java` (geometricPrice);
  Kemna and Vorst (1990).
- `M1 = (S/n) sum_i e^{g t_i}`,
  `M2 = (S/n)^2 sum_i sum_j e^{g(t_i + t_j) + sigma^2 min(t_i, t_j)}`,
  `Var[ln A] = ln(M2 / M1^2)`, `g = r - q`. Turnbull-Wakeman: the
  arithmetic average's first two moments are exact under GBM; price on a
  lognormal matched to them. The arithmetic call is always worth at least
  the geometric one, which makes the geometric price a good Monte Carlo
  control variate. The approximation error grows with sigma^2 T. The code
  evaluates the double sum in O(n). `pricing/AsianOption.java`
  (arithmeticPrice); Turnbull and Wakeman (1991).
- `price(K) = BS(K; sigma_atm)
  + sum_i w_i(K) [BS(K_i; sigma_i) - BS(K_i; sigma_atm)]`,
  `w_1(K) = vega(K)/vega(K_1)
  * ln(K_2/K) ln(K_3/K) / (ln(K_2/K_1) ln(K_3/K_1))` (cyclic). The
  vanna-volga smile adjustment from three pillars (25-delta put, ATM,
  25-delta call). It is exact at the pillars and an interpolation between
  them. `impliedVol` inverts the out-of-the-money option, and beyond the
  10-delta strikes it holds the vol flat or continues it smoothly instead
  of using the unbounded quadratic weights. `pricing/VannaVolga.java`;
  Castagna and Mercurio (2007).
- `reverseConvertible = (par + coupon) DF(T) - (par/K) put(K)`. A bond
  plus a sold put struck at K: below K at expiry the holder receives
  shares worth S_T/K of par, so the high coupon is put premium.
  `pricing/StructuredNotes.java` (reverseConvertible); Hull, chapter on
  exotic options.
- `capitalProtectedNote = protection * par * DF(T)
  + participation * (par/S0) * call(S0)`. A zero-coupon floor plus a
  participation slice of at-the-money calls; `participationFor` inverts
  the identity for the participation the option budget affords. Low rates
  or high vol mean thin participation. `pricing/StructuredNotes.java`.
- `discountCertificate = S e^{-qT} - call(cap)`. A covered call: buy the
  underlying at a discount and sell the upside beyond the cap. The
  discount to spot is the call premium; a smaller quoted discount is the
  issuer's margin. `pricing/StructuredNotes.java`.
- Autocallable = coupons + early redemption - a sold knock-in put, priced
  by antithetic Monte Carlo under GBM with observation-date monitoring and
  a fixed seed (`SplittableRandom`). The knock-in put is very
  smile-sensitive, so a flat-vol price is a first estimate; feed a
  downside-strike vol from the surface as the first correction.
  `pricing/Autocallable.java`; Bouzoubaa and Osseiran, *Exotic Options and
  Hybrids* (2010).

- `K* = (n K - A_obs) / m`, price `= (m/n) x option on the remaining
  average struck at K*`; if `K* <= 0` the call is `(m/n) e^{-rT} (E[A_rem] - K*)`
  with no optionality left and the put is 0. Seasoned Asian
  reduction. `pricing/AsianOption.java` (`turnbullWakemanSeasoned`,
  `curranPrice`); Haug (2007), section 4.20.1; Curran (1994) for the
  conditioning on the geometric average.
- Levy's continuous-average formula: a lognormal matched to the first two
  moments of the remaining continuous average, with the realised part
  folded into the strike. `AsianOption.levyPrice`; Levy (1992); Haug
  (2007), section 4.20.2.

**Dividends, forwards and FX conventions**

- `S* = S - sum_i d_i e^{-r t_i}` (ex-dates t_i <= T),
  `F = S* e^{(r - borrow) T}`. The escrowed-dividend model: strip the PV of
  dated cash dividends from spot and diffuse the remainder; borrow cost
  enters like a continuous yield. A continuous yield misprices single
  stocks around ex-dates. `bosVandermarkPrice` splits each dividend
  between spot and strike instead. `pricing/DividendSchedule.java`; Hull,
  chapter on dividends; Bos and Vandermark (2002).
- `F = S (1 + r_d T) / (1 + r_f T)`; `points = F - S`. Covered interest
  parity from simple deposit rates (the convention deposits are quoted
  in); FX forwards trade as points, not outrights. Inverting the points
  gives the implied carry `(1/T) ln(F/S)` in continuous form, so mixing
  the two conventions manufactures a spurious "basis" of about 12 bp at
  1y and 5%. The real gap to deposit rates is the cross-currency basis.
  `pricing/ForwardCurve.java` (theoreticalForward, forwardPoints,
  impliedRateDifferential), `fx/SwapPointsCurve.java` (impliedCarry with a
  day count; `preSpot` for today and tomorrow); Clark (2011), chapter 1.
- `A/B * B/C = A/C` and `A/C / B/C = A/B`. The two cross-rate
  compositions that cover every triangulation (EURUSD * USDJPY = EURJPY;
  EURUSD / GBPUSD = EURGBP). Compose bid with ask per leg direction: a
  mid-based cross hides the double spread you pay.
  `marketdata/CrossRateEngine.java`, `pricing/TriangularArbitrage.java`
  (impliedCrossMid); Clark (2011), chapter 1.
- `edge = max(ac.bid - ab.ask * bc.ask, ab.bid * bc.bid - ac.ask)` (each
  path in bp of its own buy-leg cost). Triangular arbitrage on dealable
  quotes: both round-trip paths are checked, and a positive value is
  executable edge before fees. Using mids instead of dealable sides
  produces false positives. `pricing/TriangularArbitrage.java`
  (arbitrageBps).
- `settlement = baseNotional * (fixing - contractRate) / fixing`. The NDF
  cash settlement to the buyer of the base currency, paid in the
  deliverable currency. The fixing date precedes settlement by the
  currency's fixing lag. The default lag table is documented as not yet
  verified against the EMTA templates; an overload takes an explicit lag.
  `fx/Ndf.java` (settlementAmount, fixingLagDays); EMTA NDF templates.
- `vol(25d call) = atm + bf_s + rr/2`, `vol(25d put) = atm + bf_s - rr/2`.
  FX smiles are quoted as ATM, risk reversal (call vol minus put vol) and
  butterfly. These identities hold for the smile butterfly bf_s. The
  market quote is usually the broker strangle bf_m: the 25-delta strangle
  priced at the single vol `atm + bf_m`. By default
  (`ButterflyQuote.BROKER_STRANGLE`) the builder solves for the bf_s whose
  smile reprices that strangle, jointly at 25 and 10 delta; with zero risk
  reversal the two coincide. `fx/FxVolSurface.java` (Builder,
  smileButterfly25); Clark (2011), sections 3.5 and 3.6.
- `K_dns = F e^{+sigma^2 T / 2}` (forward delta, no premium adjustment),
  `K_dns = F e^{-sigma^2 T / 2}` (premium-adjusted). The delta-neutral
  straddle "ATM" strike, where call and put deltas cancel. FX "ATM" is not
  the forward, and the premium convention flips the sign of the offset.
  `AtmConvention` selects DNS or ATM forward. `fx/FxVolSurface.java`
  (dnsStrike); Clark (2011), section 3.3; Reiswich and Wystup (2010).
- `K = F exp(-N^{-1}(delta) sigma sqrt(T) + sigma^2 T / 2)`. Strike from a
  forward call delta (`delta = N(d1)` inverted in closed form).
  Premium-adjusted deltas (`delta = (K/F) N(d2)`) are not monotone in
  strike and are solved numerically on the out-of-the-money branch; spot
  deltas carry the extra `e^{-r_f T}` factor. `DeltaConvention` selects
  forward or spot, with or without premium adjustment.
  `fx/FxVolSurface.java` (strikeForDelta); Reiswich and Wystup (2010).

- `V_cum(S) = max(exercise(S), V_ex(S - D))` at each ex-date on a
  recombining CRR lattice of stock levels, `V_ex` interpolated linearly
  in S; ex-dates snap to the nearest step. Discrete cash dividends for
  American options. `pricing/DividendTree.java`; Vellekoop and
  Nieuwenhuis (2006).

**Short-rate models and curve fitting**

- `P = e^{A(T) - B(T) r}`, `B(T) = (1 - e^{-aT})/a`,
  `A(T) = (B - T)(b - sigma^2/(2a^2)) - sigma^2 B^2/(4a)`. The Vasicek
  zero-coupon bond under `dr = a(b - r)dt + sigma dW`, with a the mean
  reversion speed and b the long-run rate. Gaussian and tractable; rates
  can go negative. `rates/ShortRateModels.java` (vasicekBond); Vasicek
  (1977).
- `r <- b + (r - b) e^{-a dt} + sigma sqrt((1 - e^{-2a dt})/(2a)) z`. The
  exact Vasicek transition (Gaussian, no discretisation error), so any
  step size is unbiased; Euler on the same SDE has O(dt) bias.
  `rates/ShortRateModels.java` (vasicekStep); Glasserman (2004), section
  3.3.
- `P = A(T) e^{-B(T) r}`, `h = sqrt(a^2 + 2 sigma^2)`,
  `B(T) = 2(e^{hT} - 1) / (2h + (a + h)(e^{hT} - 1))`,
  `A(T) = [2h e^{(a+h)T/2} / (2h + (a+h)(e^{hT}-1))]^{2ab/sigma^2}`. The
  CIR bond under `dr = a(b - r)dt + sigma sqrt(r) dW`; the square-root
  diffusion keeps rates non-negative, and strictly positive when the
  Feller condition `2ab >= sigma^2` holds. Simulation uses full-truncation
  Euler (vol taken from max(r, 0)), so a slightly negative discrete path
  never produces a NaN vol. `rates/ShortRateModels.java` (cirBond,
  cirFeller, cirStep); Cox, Ingersoll and Ross (1985); Lord, Koekkoek and
  van Dijk (2010) for full truncation.
- `P(t,T) = [P(0,T)/P(0,t)]
  * exp(B (f(0,t) - r) - sigma^2 B^2 (1 - e^{-2at})/(4a))`,
  `B = (1 - e^{-a(T-t)})/a`. Hull-White: Vasicek with the drift fitted so
  the model reprices today's curve exactly (at t = 0 with r = f(0,0) it
  returns the curve's own discount factor). No explicit theta(t) is needed
  for bond pricing. `rates/ShortRateModels.java` (hullWhiteBond,
  instantaneousForward); Hull and White (1990); Brigo and Mercurio (2006),
  section 3.3.
- `z(t) = b0 + b1 f1(t/l1) + b2 f2(t/l1) + b3 f2(t/l2)`,
  `f1(x) = (1 - e^{-x})/x`, `f2(x) = f1(x) - e^{-x}`. Svensson:
  Nelson-Siegel plus a second hump, the form several central banks
  publish. For fixed lambdas the model is linear in the betas, so each
  grid node is an exact least-squares solve; with b3 = 0 it is
  Nelson-Siegel, so it never fits worse in sample.
  `rates/Svensson.java` (fit); Svensson (1994).
- `cap = sum_i Black76call(f_i, K, sigma, t_{i-1})`,
  `f_i = (DF(t_{i-1})/DF(t_i) - 1)/tau`. A cap is a strip of caplets,
  each a call on that period's simple forward rate, fixing at the period
  start. The first period is already fixed at T = 0, so the code includes
  it at discounted intrinsic value. `RatesOptions.VolModel` switches the
  caplet model to shifted lognormal or normal. `rates/RatesOptions.java`
  (cap, floor); Black (1976), Brigo and Mercurio (2006), section 1.5.

**Day counts, curves and bonds**

- ACT/360 is `days / 360` and ACT/365F is `days / 365`. 30/360 (bond basis) is
  `[360 (Y2-Y1) + 30 (M2-M1) + (D2-D1)] / 360` with the US day-31 rules.
  Under 30E/360 both day-31s become 30, and 30E/360 ISDA also makes month-end February
  30, except a February termination date. ACT/ACT ISDA takes the days in
  each calendar year over that year's length. `rates/DayCount.java`
  (`THIRTY_E_360`, `THIRTY_E_360_ISDA`, `ACT_ACT_ISDA`); ISDA 2006
  Definitions, section 4.16.
- `ACT/ACT ICMA = days(start, end) / (frequency * days(periodStart,
  periodEnd))`, with [periodStart, periodEnd) the regular coupon period
  containing the accrual. A full regular period is exactly 1/frequency.
  `DayCount.actActIcma(start, end, periodStart, periodEnd, frequency)`;
  the two-date `yearFraction` throws for this convention because it needs
  the period. ICMA Rule 251; ISDA 2006 Definitions, section 4.16(c).
- `BUS/252 = business days in [start, end) / 252`. The Brazilian
  convention. `DayCount.bus252(calendar, start, end)`; ANBIMA.
- `DF(t) = e^{-z(t) t}`;
  `f(t1, t2) = (z(t2) t2 - z(t1) t1) / (t2 - t1)`. Discount factor and the
  implied continuously compounded forward between two tenors, the
  market's own break-even rate for the future period. Linear
  interpolation on zero rates leaves small forward kinks at the pillars.
  `rates/YieldCurve.java` (discountFactor, forwardRate); Hagan and West
  (2006).
- `DF_n = (1 - parRate_n * A_{n-1}) / (1 + parRate_n)`,
  `A_{n-1} = sum_{i<n} DF_i`. The annual par-swap bootstrap step: walk the
  quotes shortest to longest, each pillar solving the one discount factor
  that reprices its quote given the factors already solved. The result
  reprices every input exactly. `rates/YieldCurve.java`
  (bootstrapAnnualParSwaps); Hagan and West (2006).
- `price = sum_{i=1..n} c/(1 + y/f)^i + F/(1 + y/f)^n`. The bond price at
  a coupon date from a yield (c the periodic coupon, F face, y the annual
  yield, f the frequency). The map is monotone in y, so the yield
  `yieldToMaturity` finds by root search is unique.
  `rates/BondPricer.java` (priceFromYield, yieldToMaturity); Fabozzi.
- `P_dirty = sum_{k=0}^{N-1} C/(1 + y/f)^{k + w} + F/(1 + y/f)^{N-1+w}`,
  `C = F c / f`, `w = 1 - elapsed fraction of the current period`. The
  street convention between coupon dates: round coupons, the elapsed
  fraction by ACT/ACT ICMA (or the bond's 30/360 variant), and
  discounting on unadjusted coupon dates. It reproduces Microsoft Excel's
  published PRICE and YIELD examples. `BondPricer.dirtyPrice(face,
  couponRate, frequency, settlement, maturity, yield, dayCount[,
  endOfMonth])`, `yieldFromCleanPrice`; SIFMA *Standard Formulas*,
  section A.
- `dirty = clean + accrued`, `accrued = F (c/f) * elapsed fraction of the
  period`. Bonds quote clean and settle dirty; comparing a clean quote
  with a curve-discounted (dirty) PV creates a phantom arbitrage every
  coupon period. `rates/BondPricer.java` (dirtyPrice, cleanPrice,
  accruedInterest); Fabozzi.
- `KRD_i = [P(z_i - 1bp) - P(z_i + 1bp)] / 2`;
  `sum_i KRD_i ~ parallel DV01`. Key-rate DV01s: bump one curve node,
  reprice, take the central difference. The vector is the hedging recipe
  and its sum recovers the parallel risk, a consistency check the tests
  pin. `rates/KeyRateDurations.java` (keyRateDv01s, parallelDv01); Ho
  (1992).

**Credit and counterparty**

- `riskyAnnuity = sum_i dt DF(t_i) Q(t_i) + accrual-on-default`. The PV
  of 1 a year paid while the name survives (quarterly grid, half-period
  accrual on default): the credit desk's spread DV01.
  `credit/CdsPricer.java` (riskyAnnuity); O'Kane (2008), chapter 6.
- Hazard bootstrap: per pillar, solve h in `upfront(h) = 0` by bisection
  on [1e-9, 10] given all shorter pillars. The credit analogue of the
  curve bootstrap, producing piecewise-constant hazards that reprice every
  quoted CDS. A quote no hazard can explain throws instead of returning
  the bracket bound. `credit/CreditCurve.java` (bootstrap); O'Kane (2008),
  chapter 7.
- `CVA = LGD * sum_i EE(t_i) [Q(t_{i-1}) - Q(t_i)] DF(t_i)`. Expected
  exposure per bucket, times the probability of default in that bucket,
  discounted, times loss given default. It assumes exposure and default
  are independent, so it has no wrong-way risk and understates CVA when
  exposure grows as the counterparty weakens.
  `credit/CvaApproximator.java` (cva); Gregory, *Counterparty Credit Risk
  and Credit Value Adjustment* (2012).
- `exposure = max(0, netMtm) + (0.4 + 0.6 NGR) sum |notional| addOn(tenor)`.
  The current-exposure-method total is net current exposure per netting set
  plus NGR-scaled notional add-ons by tenor bucket (for FX, 1% under 1y, 5%
  for 1 to 5y, 7.5% beyond). An add-on table cannot see the correlation
  of offsetting trades. `risk/CounterpartyExposureTracker.java`
  (currentExposure, potentialFutureExposure, addOnFactor); Basel
  Committee, current exposure method, BCBS 128 (June 2006), Annex 4
  section VII: add-on table para 92(i), 0.4 + 0.6 NGR netting rule
  para 96(iv). The NGR rule first appeared in the April 1995 amendment
  on potential exposure (BCBS 18), which BCBS 128 compiles.

- On a segment with constant hazard `lambda` and forward `f`,
  `k = lambda + f`: protection `= (1 - R) lambda Q0 P0 (1 - e^{-k dt}) / k`
  per unit notional; accrual on default adds
  `lambda Q0 P0 [a (t0 - tS)(1 - e^{-k dt})/k + a (1 - e^{-k dt}(1 + k dt))/k^2]`.
  The ISDA standard CDS model's closed-form legs.
  `credit/IsdaCdsModel.java`; ISDA CDS Standard Model v1.8.2
  (`JpmcdsCdsContingentLegPV`, `JpmcdsFeeLegPV`); O'Kane (2008), ch. 6.
- `P(t,T) = P(0,T)/P(0,t) exp((V(t,T) - V(0,T) + V(0,t))/2 - B(t,T) x(t))`.
  Hull-White bond price on a simulated path, used to revalue swaps for
  exposure. `credit/MultiCurrencyExposureSimulator.java`; Brigo and Mercurio (2006),
  eq. 3.39.
- `CVA = LGD sum_i E[D(0,t_i) E(t_i)] (PD(t_i) - PD(t_{i-1}))`;
  `DVA` the same with own default and negative exposure. Unilateral;
  `CvaApproximator.bilateral` weights each by the other party's survival.
  `credit/CvaApproximator.java`; Gregory, *The xVA Challenge* (2015).
- `P(bucket i | X_i) = Phi((Phi^-1(PD_i) + rho X_i)/sqrt(1-rho^2)) - Phi((Phi^-1(PD_{i-1}) + rho X_i)/sqrt(1-rho^2))`,
  `X_i = W(t_i)/sqrt(t_i)` without a CSA, `(W(t_i) - W(t_i - MPR))/sqrt(MPR)` with one.
  Wrong-way risk through a per-bucket Gaussian copula on path drivers (Hull and White 2012);
  `rho = 0` gives back the independent CVA.
  `CvaApproximator.cvaWrongWay`; Gregory (2015), ch. 17.
- `EAD = 1.4 (RC + multiplier x AddOn)`,
  `multiplier = min(1, 0.05 + 0.95 exp((V - C)/(2 x 0.95 x AddOn)))`,
  `SD = (e^{-0.05 S} - e^{-0.05 E})/0.05`, interest-rate effective notional
  `sqrt(D1^2 + D2^2 + D3^2 + 1.4 D1 D2 + 1.4 D2 D3 + 0.6 D1 D3)`. SA-CCR.
  `risk/SaCcr.java`; BCBS 279 (March 2014), Annex 4 and Table 2.

**Risk engines**

- `VaR = L_(ceil(c n))`, the lower empirical quantile of the loss sample
  `L_(1) <= ... <= L_(n)`. Historical VaR: replay actual factor-return
  rows through the book's exposures and read the empirical loss quantile.
  It cannot see a loss larger than the window contained.
  `risk/TailRisk.java`, `risk/VarEngine.java` (historicalVar);
  `risk/RiskMetrics.historicalVar` uses the interpolated return
  percentile, floored at 0. Jorion, *Value at Risk* (2007), chapter 10.
- `ES = (L_(n) + ... + L_(n-k+1) + (w - k) L_(n-k)) / w`,
  `w = (1 - c) n`, `k = floor(w)`. The Acerbi-Tasche estimator of
  expected shortfall: the mean of the worst `(1-c) n` losses, with a
  fractional weight on the boundary observation. It never falls below
  VaR; losses 1 to 1000 at 97.5% give ES = 988.0. One estimator is shared
  by `VarEngine`, `FrtbEs.es975`, `RiskMetrics.expectedShortfall` and
  `conditionalVar`, `SimulationResult` and `HedgingErrorDistribution`.
  `risk/TailRisk.java`; Acerbi and Tasche (2002), Proposition 4.1.
- `dx = L z`, `L L' = Sigma`, `P&L = delta' dx`. Monte Carlo VaR:
  correlated Gaussian factor draws through the exposure map. It converges
  to delta-normal for a linear book, and the same harness revalues
  non-linear books in full. The Cholesky factor is
  `PsdMatrices.choleskyPsd`, which accepts rank-deficient matrices and
  rejects indefinite ones. `risk/VarEngine.java` (monteCarloVar,
  fullRevaluationVar); Glasserman (2004), section 9.1.
- Filtered historical simulation: divide each historical return by its
  EWMA volatility on that day, multiply by today's volatility, then take
  the empirical quantile. `VarEngine.filteredHistoricalVar`; Barone-Adesi,
  Giannopoulos and Vosper (1999); Hull and White (1998).
- `P&L = delta' dx + (1/2) dx' Gamma dx`. The delta-gamma expansion: the
  quadratic term makes a short-gamma book's loss tail fatter than a
  Gaussian, and its skew feeds the Cornish-Fisher quantile. It is still a
  local expansion; a barrier book needs full revaluation.
  `CentralRiskBook.report` uses it for its VaR and ES.
  `risk/VarEngine.java` (deltaGammaVar, deltaGammaEs),
  `risk/StressTester.java` (scenarioPnl); Jorion (2007), chapter 15.
- `incrVaR = VaR(book + position) - VaR(book)`. Incremental VaR, the
  cost of adding a position, which differs from its component VaR (the
  Euler share of what is already there). `risk/ComponentVar.java`
  (incremental, allocate); Jorion (2007), chapter 7.
- `ES = sqrt( sum_j [ES_j sqrt((LH_j - LH_{j-1})/10)]^2 )`. The FRTB
  liquidity-horizon cascade: base 10-day ES scaled up by how long each
  factor class takes to exit under stress (10 to 120 days), aggregated
  in quadrature over the nested factor subsets. `risk/FrtbEs.java`
  (liquidityHorizonEs, es975); BCBS MAR33.4.
- `ES = ES_R,S * max(1, ES_F,C / ES_R,C)`, with the check
  `ES_R,C >= 0.75 ES_F,C`. FRTB stressed calibration: ES of the reduced
  factor set in the stress period, scaled up by the ratio of full-set to
  reduced-set ES in the current period, never scaled down. A reduced set
  that explains less than 75% of current full-set ES is rejected.
  `FrtbEs.stressCalibratedEs`, `FrtbEs.MIN_REDUCED_SET_COVERAGE`; BCBS
  MAR33.5 (stress period, 75% test in MAR33.5(2)), MAR33.6 (formula and
  floor at 1), MAR33.7 (choice of the stress period).
- `dx* = -(L / (delta' Sigma delta)) Sigma delta`;
  `sigmas = L / sqrt(delta' Sigma delta)`. Reverse stress: the most
  probable factor move producing target loss L on a linear book lies
  along `Sigma delta`, and its Mahalanobis distance says how implausible
  that move is. `risk/StressTester.java` (reverseStress); Breuer,
  Jandačka, Rheinberger and Summer (2009).

- `r_it = f_mkt + f_sector(i) + sum_k x_ik f_k + u_it`, `V = X F X' + D`.
  Styles standardised to cap-weighted mean 0 and equal-weighted sd 1,
  clipped at 3; factor returns by WLS with `sqrt(cap)` weights under
  `sum_s c_s f_s = 0`. Fundamental factor model.
  `risk/FactorRiskModel.java`; Menchero, Orr and Wang (2011), USE4
  methodology, section 3; Menchero (2010).
- `F = C0 + sum_{l=1}^{L} (1 - l/(L+1)) (C_l + C_l')`, then
  `F <- lambda_F^2 F` with `lambda_F^2` an EWMA of
  `B_t^2 = mean_k (f_kt / sigma_k,t-1)^2`. Newey-West and volatility regime
  adjustment of the factor covariance. Newey and West (1987); USE4
  section 4.3.
- `sigma_SH = v mean_n + (1 - v) sigma`,
  `v = q |sigma - mean_n| / (Delta_n + q |sigma - mean_n|)` within size
  buckets. Bayesian shrinkage of specific risk. USE4 section 5.3.
- Marginal `(Vw)_i / sigma`, percent `w_i (Vw)_i / sigma^2`, factor
  `x_k (F x)_k / sigma^2` with `x = X'w`; the percent shares and the
  factor plus specific shares each sum to 1. Euler risk allocation.
  `FactorRiskModel.decompose`; Litterman (1996).

**Model validation and dependence**

- `LR_pof = -2 ln[ (1-p)^{n-x} p^x / ((1-x/n)^{n-x} (x/n)^x) ]`. Kupiec
  proportion of failures: does the count of VaR exceptions x in n days
  match the promised rate p = 1 - c? Chi-squared(1), two-sided, so too few
  exceptions also rejects. `risk/VarBacktest.java` (test); Kupiec (1995).
- Christoffersen independence: an LR test that exceptions do not follow
  exceptions (first-order Markov), chi-squared(1); conditional coverage
  adds it to Kupiec, chi-squared(2). A model right on average but wrong in
  crises passes the first test and fails this one. `risk/VarBacktest.java`
  (test); Christoffersen (1998).
- `Z2 = 1 - sum_t L_t 1{L_t > VaR_t} / (T (1-c) ES_t)`. The
  Acerbi-Szekely unconditional ES backtest: E[Z2] = 0 under a correct ES
  forecast, and negative values mean realised tail losses exceed it. For
  T = 250 at 97.5% the published thresholds are -0.70 (5%) and -1.80
  (0.01%). `VarBacktest.acerbiSzekelyZ2`; Acerbi and Szekely (2014).
- PLAT: Spearman correlation and Kolmogorov-Smirnov statistic between
  hypothetical and risk-theoretical daily P&L; green needs correlation
  above 0.80 and KS below 0.09. `risk/PnlAttribution.java` (test,
  ksStatistic); BCBS MAR32.
- `HHI = sum_i share_i^2`, `effectiveN = 1 / HHI`. Concentration: equal
  weights give HHI = 1/N, a single name gives 1. A 50-name book with HHI
  0.10 behaves like 10 equal names. `risk/ConcentrationRisk.java`
  (herfindahlIndex, effectivePositions); Herfindahl (1950).
- `Sigma = V L V'`. PCA: eigenvectors of the covariance matrix ordered by
  explained variance (cyclic Jacobi). On a yield curve the first three
  components are level, slope and curvature. Components are statistical,
  not causal, and rotate when the sample changes. `risk/Pca.java`,
  `PsdMatrices.symmetricEigen`; Litterman and Scheinkman (1991); Golub and
  Van Loan (2013), section 8.5.
- Nearest correlation matrix: alternating projections between the PSD
  cone and the unit-diagonal set, with Dykstra's correction, for a
  pairwise-estimated matrix that is not positive semidefinite.
  `PsdMatrices.nearestCorrelation`; `nearestPsd` is the Frobenius-nearest
  PSD matrix. Higham (2002); Higham (1988).
- `rho_S = Pearson(ranks)`; `tau = P(concordant) - P(discordant)`;
  `rho = sin(pi tau / 2)`. The rank-dependence family, unaffected by
  outliers and monotone transforms; the sine identity maps Kendall's tau
  to the correlation parameter of an elliptical copula.
  `risk/Dependence.java` (spearman, kendallTau, pearsonFromKendall);
  Lindskog, McNeil and Schmock (2003).
- `u = N(L z)` componentwise, `L L' = correlation`. The Gaussian copula
  sampler: correlated uniforms to feed through any marginals' inverse
  CDFs. It has no tail dependence (joint extremes are asymptotically
  independent); the Student-t copula with few degrees of freedom restores
  clustered extremes. `sample` and `sampleT` take a
  `java.util.random.RandomGenerator`. `risk/GaussianCopula.java`; Li
  (2000); McNeil, Frey and Embrechts (2015), chapter 7.
- `z_robust = (x - median) / (1.4826 * MAD)`. The robust z-score: median
  and MAD tolerate up to half the sample being contaminated, and 1.4826
  rescales MAD to standard-deviation units under normality.
  `ml/AnomalyDetector.java` (detectQuoteStuffing, detectPriceSpikes);
  Rousseeuw and Croux (1993).

**Portfolio construction**

- `min_w w' Sigma w` (optionally `- w' mu / riskAversion`) subject to
  `1'w = 1`, `mu'w = target` and bounds. Mean-variance as a convex
  quadratic program, solved exactly by an active-set method, so frontier
  points hit their target return exactly. `maxSharpe` throws when no
  asset's expected return exceeds the risk-free rate.
  `optimization/PortfolioOptimizer.java`,
  `optimization/ConstrainedPortfolioOptimizer.java`,
  `optimization/QuadraticProgram.java`; Markowitz (1952); Nocedal and
  Wright, *Numerical Optimization* (2006), section 16.5.
- `w_i (Sigma w)_i / w' Sigma w = b_i`. Risk budgeting (equal risk
  contribution when every b_i = 1/n), solved through Spinu's convex
  problem `min_x (1/2) x' Sigma x - sum_i b_i ln x_i` over x > 0, whose
  minimiser satisfies `x_i (Sigma x)_i = b_i`; then `w = x / 1'x`. Damped
  Newton steps converge in a few iterations, including on nearly
  collinear assets. Risk parity says nothing about return.
  `optimization/RiskParityOptimizer.java` (equalRiskContribution,
  riskBudget, riskContributions); Spinu (2013); Maillard, Roncalli and
  Teiletche (2010).
- `Pi = deltaRA * Sigma w_mkt`. Black-Litterman equilibrium returns:
  reverse-optimise the market portfolio under risk aversion deltaRA, so
  the starting point is the returns the market already implies.
  `optimization/BlackLitterman.java` (impliedEquilibriumReturns); Black
  and Litterman (1992).
- `mu = Pi + tau Sigma P' (P tau Sigma P' + Om)^{-1} (Q - P Pi)`. The
  Black-Litterman posterior mean in view space (pick matrix P, views Q,
  view covariance Om; smaller Om means more confidence). This form is
  algebraically equal to the textbook precision-weighted form but does not
  need Sigma to be invertible. `posterior` also returns the covariance of
  the mean and `Sigma + M`; `heLittermanOmega` sets
  `Om = diag(P tau Sigma P')`. Tau and Om are in the same units, so
  scaling one against the other reweights every view.
  `optimization/BlackLitterman.java` (posteriorReturns, posterior); He and
  Litterman (1999); Meucci (2010).
- `min_w -alpha'w + gamma w' Sigma w + lambda ||w - w0||_1` subject to
  position and trade boxes and neutrality equalities. Turnover-aware
  optimisation, solved by ADMM. `alpha/TurnoverAwareOptimizer.java`; Boyd,
  Parikh, Chu, Peleato and Eckstein (2011).
- `w_i = (1/sigma_i) / sum_j (1/sigma_j)`;
  `leverage = targetVol / currentVol`. Inverse-volatility weights and
  volatility targeting, the two simplest risk-based sizing rules. Vol
  targeting sells into vol spikes by construction.
  `backtest/portfolio/PositionSizing.java` (inverseVolatilityWeights,
  volatilityTargetLeverage); Moreira and Muir (2017).
- `qty = equity * riskFraction / |entry - stop|`. Fixed-fractional
  sizing: hitting the stop loses exactly riskFraction of equity.
  `backtest/portfolio/PositionSizing.java` (fixedFractionalQuantity);
  Vince (1990).
- `f* = argmax_{f in [0,1]} sum_i ln(1 + f r_i)` over per-trade returns
  r_i. The empirical Kelly fraction of a trade record: the fraction of
  equity per trade that maximises realised log growth. The fixed-odds form
  `W - (1 - W)/R` is not used, because currency P&L of a compounding book
  is not a fixed-odds bet. A Kelly above about 0.25 usually means the
  sample is too small or the wins too lucky. `backtest/TradeAnalytics.java`
  (analyze); Kelly (1956), Thorp (2006), section 2.

- `alpha_i = IC x sigma_i x sqrt(H) x z_i`; each rebalance maximises
  `alpha'w - lambda w'(H V)w - kappa |w - w0|_1` under a per-name trade cap
  of `advTradeFraction x ADV x price / (capital x equity)`. Risk-aware
  alpha portfolio. `alpha/AlphaBacktester.java` (`RiskOptions`);
  Grinold (1994).
- `IC_k(t) = (1/H) sum_{l<H} corr(z'_k(t), r_{t+l -> t+l+1})` on
  Gram-Schmidt orthogonalised scores `z'`;
  `w_k = (1 - delta) IC_k / sum_j |IC_j| + delta / K`. Signal combination.
  `alpha/AlphaCombiner.java`; Grinold and Kahn (2000), chs. 10 and 13.

**Performance and trade analytics**

- `CAGR = (end/start)^{periodsPerYear/(n-1)} - 1`. Compound annual growth
  off an equity curve of n points. CAGR and the arithmetic mean return
  differ by roughly the variance drag `sigma^2/2`, so quoting the mean
  flatters volatile strategies. `backtest/PerformanceAnalytics.java`
  (compute); Bacon, *Practical Portfolio Performance Measurement and
  Attribution* (2008).
- `Calmar = CAGR / maxDrawdown`. Return per unit of worst drawdown; max
  drawdown is a single order statistic, so on a short record Calmar is
  mostly noise. `backtest/PerformanceAnalytics.java`; Young (1991).
- `profitFactor = grossProfit / grossLoss`. Currency won per unit lost on
  realised trades. A 90% win rate with profit factor 0.9 is a losing
  strategy. `backtest/PerformanceAnalytics.java`; Bacon (2008).
- `expectancy = W * avgWin - L * avgLoss` (L the loss rate; scratch trades
  count in neither); `payoff = avgWin / avgLoss`. The per-trade edge: a
  40% win rate needs a payoff above 1.5 for positive expectancy.
  `backtest/TradeAnalytics.java` (analyze); Tharp (1998).
- `downsideDev = sqrt( mean( min(r - MAR, 0)^2 ) )`. The Sortino
  denominator: only returns below the minimum acceptable return count as
  risk. With few observations below MAR the estimate is fragile.
  `risk/RiskMetrics.java` (downsideDeviation, sortinoRatio); Sortino and
  Price (1994).
- `alpha = (mean(r_s) - beta * mean(r_b)) * periods`;
  `TE = stdev(r_s - r_b) * sqrt(periods)`;
  `upCapture = mean(r_s | r_b > 0) / mean(r_b | r_b > 0)`. Jensen alpha,
  tracking error and capture ratios.
  `backtest/BenchmarkComparison.java` (compare); Jensen (1968).
- `depth = 1 - trough/peak` per episode; `timeUnderWater` is the fraction
  of periods below the running peak. Drawdown structure: duration as well
  as depth. An episode still open at the end of a backtest has
  `recoveryIndex = -1`. `backtest/DrawdownAnalytics.java` (analyze),
  `risk/RiskMetrics.java` (maxDrawdown); Magdon-Ismail and Atiya (2004).

- `factor_k = (sum_i h_i X_ik) f_k`, `specific = sum_i h_i u_i`,
  `trading = sum_i pnl_i - sum_k factor_k - specific`,
  `net = sum_k factor_k + specific + trading + cost + financing` exactly.
  Holdings-based P&L attribution. `alpha/BacktestAttribution.java`.
- Impact drag per unit return grows as `sqrt(C)`, so net Sharpe falls with
  capital; capacity at a floor is the log-capital interpolation of the
  crossing. `alpha/CapacityCurve.java`; Ratcliffe, Miranda and Ang
  (2017).

**Strategy validation and factor research**

- `lambda = ln(w / (1 - w))`, `w = OOS rank of the IS winner / (N + 1)`;
  `PBO = fraction of combinations with lambda <= 0`. CSCV probability of
  backtest overfitting: over every symmetric in-sample/out-of-sample block
  split, how often does the in-sample winner land below the out-of-sample
  median? Above about 0.5 the selection process is picking noise.
  `backtest/validation/OverfitProbability.java` (cscv, cscvSharpe);
  Bailey, Borwein, López de Prado and Zhu (2017).
- `train = [0, t0 - h) union [t1 + h + embargo, n)` for test fold
  [t0, t1) and label horizon h. Purged K-fold with embargo: drop every
  training sample whose label window overlaps a test label window, plus
  an embargo buffer for serial correlation.
  `backtest/validation/PurgedKFold.java` (splits); López de Prado,
  *Advances in Financial Machine Learning* (2018), chapter 7.
- Stationary block bootstrap: resample blocks of geometric mean length L
  (restart probability 1/L, circular wrap), with `L ~ n^{1/3}` as a rule
  of thumb. An iid bootstrap destroys autocorrelation and understates the
  uncertainty. `backtest/validation/BlockBootstrap.java` (sharpeSamples,
  resample); Politis and Romano (1994).
- `VR(q) = Var(q-period returns) / (q * Var(1-period returns))`. The
  Lo-MacKinlay variance ratio: 1 under a random walk, above 1 trending,
  below 1 mean-reverting, with a z-statistic.
  `microstructure/VarianceRatio.java` (test); Lo and MacKinlay (1988).
- `Omega = gamma_0 + 2 sum_{l=1..L} (1 - l/(L+1)) gamma_l`,
  `gamma_l = (1/n) sum_t (x_t - xbar)(x_{t-l} - xbar)`,
  `SE(mean) = sqrt(Omega / n)`. Newey-West HAC variance with Bartlett
  weights. `automaticLag` is the Andrews AR(1) plug-in bandwidth, and
  `lagFor(x, h)` takes at least `h - 1` for h-bar overlapping windows.
  `alpha/NeweyWest.java`; Newey and West (1987), Andrews (1991).
- `IC_t = SpearmanCorr(scores_t, forwardReturns_t)`;
  `IR = mean(IC)/std(IC)`; `t = mean(IC) / SE_NW`. The factor-quality
  trio: per-date rank information coefficient, its consistency, and its
  significance. The IC is computed at every bar, so h-bar forward windows
  overlap; the t-stat uses the Newey-West standard error at lag
  `max(h - 1, Andrews)`, and the iid `mean/(std/sqrt(n))` is reported as
  `naiveTStat` for comparison. `alpha/SignalEvaluator.java` (evaluate);
  Grinold and Kahn (2000), Newey and West (1987).
- Fama-MacBeth: in each period regress the cross-section of forward
  returns on exposures (one premium lambda_k per factor per period);
  `premium = mean_t(lambda_t)`, with a Newey-West t-stat on the time
  series of lambda_t. Each period contributes one observation, which
  absorbs the cross-sectional correlation that breaks pooled regressions.
  `alpha/FamaMacBeth.java` (fit); Fama and MacBeth (1973).
- `AIC = 2k - 2 ln L`; `BIC = k ln n - 2 ln L`. Penalties for parameters
  (lower is better); only comparable across models fitted to the same
  data on the same likelihood scale. `volatility/InformationCriteria.java`
  (aic, bic); Akaike (1974), Schwarz (1978).
- Calendar anomalies: per-day-of-week and turn-of-month mean returns,
  each with a Newey-West t-stat (`naiveTStat` keeps the iid version). Most
  published calendar effects weakened after publication.
  `alpha/CalendarAnomalies.java` (dayOfWeek, turnOfMonth); McLean and
  Pontiff (2016).

**Mean reversion and pairs machinery**

- `dx = kappa (theta - x) dt + sigma dW`; AR(1) map
  `x_{t+1} = a + b x_t + eps` with `b = e^{-kappa dt}`;
  `halfLife = ln(2)/kappa`; `stationary sd = sigma / sqrt(2 kappa)`. The
  Ornstein-Uhlenbeck fit behind a pairs trade: half-life estimates the
  holding period, and `(x - theta)/sd_stationary` is the entry signal. A
  fitted b >= 1 throws. Small-sample OLS bias makes short-sample
  half-lives optimistic. `microstructure/OrnsteinUhlenbeck.java` (fit,
  Params.zScore); Uhlenbeck and Ornstein (1930).
- Engle-Granger: regress `A = a + b B + e`, then run an ADF test (no
  constant) on the residual. The two-argument form uses the asymptotic
  two-variable critical values (-3.34 at 5%, -3.90 at 1%);
  `engleGranger(a, b, lags)` adds ADF lags and MacKinnon's finite-sample
  critical values (`criticalValue(level, T)`). High return correlation is
  not enough; the spread must be stationary.
  `hedging/CointegrationTest.java` (engleGranger, adfTStatistic); Engle
  and Granger (1987), MacKinnon (2010).
- State `[alpha, beta]_t = [alpha, beta]_{t-1} + noise(q)`, observation
  `y = alpha + beta x + noise(r)`. The Kalman-filter time-varying hedge
  ratio: q is how fast the relationship drifts (0 gives recursive least
  squares), r how noisy each print is, and only their ratio matters.
  `microstructure/KalmanBeta.java` (onObservation); Kalman (1960).
- `h* = cov(asset, hedge) / var(hedge)`; `effectiveness = rho^2`. The
  minimum-variance hedge ratio and the share of variance it removes. A
  ratio fitted on returns hedges returns; a price-level hedge
  (cointegration weights) is a different object.
  `hedging/MinimumVarianceHedge.java` (hedgeRatio, hedgeEffectiveness);
  Ederington (1979).
- `N = (beta_target - beta) * V / (F * multiplier)`. Futures contracts to
  move a portfolio of value V from beta to beta_target (F the futures
  price); target 0 for a full hedge, and negative N means sell.
  `hedging/MinimumVarianceHedge.java` (betaAdjustmentContracts,
  fullHedgeContracts); Hull, chapter on hedging with futures.
- `cov_ij <- lambda cov_ij + (1 - lambda) r_i r_j`. The streaming EWMA
  covariance (zero-mean convention), kept positive semidefinite by
  full-vector rank-1 updates; a sample with any non-finite return is
  dropped whole. `microstructure/EwmaCovariance.java` (covariance,
  minVarianceHedgeRatio, portfolioVariance); *RiskMetrics Technical
  Document* (1996).

**Microstructure signals**

- `microprice = I * ask + (1 - I) * bid`,
  `I = bidSize / (bidSize + askSize)`. The size-weighted mid: a heavy bid
  pushes fair value toward the ask. `orderbook/BookAnalytics.java`
  (microprice), `pricing/FairValueEngine.java` (microprice); Stoikov
  (2018) discusses this weighted mid and a refined microprice.
- `QI = (bidSize - askSize) / (bidSize + askSize)`; trade imbalance =
  decayed signed aggressor volume / decayed total volume. Two next-tick
  predictors in [-1, 1]. Queue imbalance at the inside says nothing about
  depth behind it. `microstructure/FlowSignals.java` (queueImbalance,
  tradeImbalance); Gould and Bonart (2016).
- OFI at the best level: a bid price or size increase, or an ask
  decrease, is buying pressure; the mirror is selling pressure;
  exponentially time-decayed. A one-sided or non-dealable quote is a gap
  in the signal, not flow. `microstructure/FlowSignals.java` (ofi,
  onQuote); Cont, Kukanov and Stoikov (2014).
- `VPIN = (1/n) sum |buyVol - sellVol| / bucketVolume`. Flow toxicity in
  volume time: trades fill fixed-volume buckets, each scores its absolute
  imbalance, and VPIN averages the last n buckets.
  `microstructure/Vpin.java` (vpin); Easley, López de Prado and O'Hara
  (2012).
- `lambda(t) = mu + sum_{t_i < t} alpha e^{-beta (t - t_i)}`. The
  exponential Hawkes self-exciting intensity. The branching ratio
  `alpha/beta` is the expected number of children per event; at 1 or
  above the process is explosive and the constructor refuses it. `fit`
  calibrates (mu, alpha, beta) by maximum likelihood.
  `microstructure/HawkesIntensity.java` (intensity, burstScore, fit);
  Hawkes (1971), Ozaki (1979).
- Lee-Ready: at or above the ask is a buy, at or below the bid a sell; at
  the mid, an uptick is a buy and a downtick a sell, and an unchanged
  price repeats the last call. About 85% accuracy in the literature.
  `microstructure/TradeClassifier.java` (classify); Lee and Ready (1991).
- `s = 2 sqrt(-cov(dp_t, dp_{t-1}))`. Roll's implied effective spread:
  bid-ask bounce creates negative autocovariance in price changes.
  Positive autocovariance returns NaN rather than a zero spread.
  `microstructure/LiquidityMeasures.java` (rollSpread); Roll (1984).
- Corwin-Schultz: the spread from two consecutive high-low ranges.
  Variance grows with time but the spread does not, so one 2-day range
  against two 1-day ranges isolates the spread; negative estimates are
  set to 0. `microstructure/LiquidityMeasures.java`
  (corwinSchultzSpread); Corwin and Schultz (2012).
- `Amihud = mean( |r_t| / dollarVolume_t )`. Price impact per currency
  unit traded, a cross-sectional illiquidity ranking (multiply by 1e6 for
  the usual per-million quote). `microstructure/LiquidityMeasures.java`
  (amihudIlliquidity); Amihud (2002).
- `P(fill) = exp(-(qtyAhead + orderQty) / expectedTradedQty)`.
  Queue-position fill probability under exponentially distributed level
  volume: you fill when executions consume everyone ahead of you plus
  your own size. `microstructure/QueueModel.java` (fillProbability).
- `P(touch) = 2 N(-d / (sigma sqrt(T)))`. The driftless reflection
  probability that price travels distance d within T; multiplied by the
  queue-clearing probability (an independence approximation that slightly
  underestimates) it scores passive placement away from the touch.
  `microstructure/FillProbabilityModel.java` (touchProbability,
  passiveFillProbability); Shreve (2004), section 3.7.

**Execution and transaction cost analysis**

- `IS_bps = sign * (avgExecPrice - arrivalMid) / arrivalMid * 1e4`.
  Implementation shortfall against the arrival mid; positive is a cost
  for buys and sells alike. `BestExecutionAnalyzer.implementationShortfall`
  splits it into delay, execution and opportunity cost.
  `microstructure/TransactionCostAnalyzer.java` (analyze); Perold (1988).
- `effective = 2 sign (price - mid_exec) / mid_exec`;
  `realized = 2 sign (price - mid_later) / mid_later`;
  `impact = 2 sign (mid_later - mid_exec) / mid_exec`, so
  `effective ~ realized + impact`. What the taker paid, what the maker
  kept after adverse selection, and the information in the trade.
  `regulatory/MarketQualityMetrics.java` (effectiveSpreadBps,
  realizedSpreadBps, priceImpactBps); SEC Rule 605; Bessembinder (2003).
- VWAP slice allocation: `qty_i = totalQty * v_i / sum(v)` with
  largest-remainder integer rounding so slices sum exactly to the parent;
  TWAP is the flat-profile case. `execution/VwapScheduler.java` (schedule,
  allocateProportionally), `execution/TwapScheduler.java` (schedule);
  Berkowitz, Logue and Noser (1988) for the VWAP benchmark.
- `pick the arm maximising mean_i + sqrt(2 ln N / n_i)`. UCB1: the
  optimism bonus shrinks as an arm is tried, giving logarithmic regret.
  Rewards must lie in [0, 1], which the code enforces.
  `execution/Ucb1Selector.java` (select, record); Auer, Cesa-Bianchi and
  Fischer (2002).
- `temporary = eta * participationRate`;
  `permanent = gamma * sigma_daily * (Q/ADV)`;
  `expectedCost = permanent/2 + temporary`. The two-part impact model
  behind scheduling: temporary impact prices speed, permanent impact
  prices information, and the schedule average pays half the permanent
  part. `microstructure/MarketImpactModel.java` (temporaryImpactBps,
  permanentImpactBps, expectedCostBps); Almgren, Thum, Hauptmann and Li
  (2005).
- `skew = -(inventory/limit) * skewFraction * halfSpread`;
  `bid = mid (1 + (-halfSpread + skew)/1e4)`,
  `ask = mid (1 + (+halfSpread + skew)/1e4)`. Inventory-skewed quoting:
  long inventory shades both quotes down, capped so the quote never
  crosses itself. `crb/SkewedQuoter.java` (quote); Avellaneda and Stoikov
  (2008) for the underlying idea.
- Call-auction uncross: choose the price with (1) maximum executable
  volume, (2) minimum residual imbalance among ties, (3) closest to the
  reference price among remaining ties. `microstructure/Auction.java`
  (uncross, indicative); exchange rulebooks, for example the Nasdaq Cross
  rules.

**Indexes, corporate actions and private markets**

- `w_i = p_i s_i f_i / sum_j p_j s_j f_j`. Float-adjusted cap weights
  (price, shares outstanding, float factor): price moves change weights
  without trading, which keeps cap-weight turnover low.
  `markets/IndexConstruction.java` (capWeights, priceWeights,
  equalWeights); S&P Dow Jones Indices, *Index Mathematics Methodology*.
- `TVPI = (D + NAV) / C`, `DPI = D / C`, `RVPI = NAV / C`. Private-fund
  multiples (C contributions, D distributions). Early in a fund's life
  TVPI is mostly RVPI, an appraisal; DPI is the part already paid out.
  `markets/PrivateMarketAnalytics.java` (tvpi, dpi, rvpi); ILPA reporting
  guidelines.
- Split r-for-1 (prior prices / r, volume * r) and cash dividend d (prior
  prices * `(prevClose - d) / prevClose`). CRSP-style back-adjustment so
  returns across ex-dates reflect economics, not mechanical drops.
  `data/CorporateActions.java` (adjust); CRSP data definitions.

**Range and regime volatility**

- `sigma^2 = mean( ln(H/L)^2 ) / (4 ln 2)`. Parkinson: the high-low range
  carries about 4.9 times the information of close-to-close returns
  under driftless GBM; biased up by drift and down by discrete sampling.
  `volatility/RangeVolatility.java` (parkinson); Parkinson (1980).
- `sigma^2 = mean( 0.5 ln(H/L)^2 - (2 ln 2 - 1) ln(C/O)^2 )`. Garman-Klass:
  adds open and close to the range, about 7.4 times as efficient; still
  assumes zero drift. `volatility/RangeVolatility.java` (garmanKlass);
  Garman and Klass (1980).
- `sigma^2 = mean( ln(H/C) ln(H/O) + ln(L/C) ln(L/O) )`. Rogers-Satchell:
  independent of drift by construction. `volatility/RangeVolatility.java`
  (rogersSatchell); Rogers and Satchell (1991).
- `sigma^2 = sigma_o^2 + k sigma_c^2 + (1 - k) sigma_rs^2`,
  `k = 0.34 / (1.34 + (m+1)/(m-1))`. Yang-Zhang: overnight-gap variance
  plus open-to-close variance plus the Rogers-Satchell term, with the
  variance-minimising weight k over m periods. Drift-independent and
  gap-aware. `volatility/RangeVolatility.java` (yangZhang); Yang and Zhang
  (2000).
- Two-state Gaussian HMM fitted by Baum-Welch EM from five starts, with
  emission densities in log space; `expectedDuration = 1 / (1 - p_stay)`.
  State 1 is the high-vol regime. The filtered probability
  (`filteredHighVolProbability`) uses data up to each date and is the
  real-time signal; the smoothed probability uses the whole sample and is
  for ex-post labelling only; `viterbi` gives the most likely path. EM
  finds a local maximum. `ml/RegimeDetector.java` (fit, viterbi,
  RegimeModel.expectedDuration); Hamilton (1989), Rabiner (1989).

**Technical indicators**

- `EMA_t = alpha p_t + (1 - alpha) EMA_{t-1}`, `alpha = 2/(n + 1)`. The
  exponential moving average: n sets the decay rather than a window,
  and about 86% of the weight sits in the last n bars.
  `indicators/Indicators.java` (ema); Murphy, *Technical Analysis of the
  Financial Markets* (1999).
- `RSI = 100 - 100/(1 + RS)`, `RS = avgGain / avgLoss` with Wilder
  smoothing `avg <- (avg (n-1) + x)/n`. The momentum oscillator, with 70
  and 30 as the usual bands; in a strong trend it stays "overbought" for
  weeks. Pinned to the published StockCharts example.
  `indicators/Indicators.java` (rsi); Wilder (1978).
- `MACD = EMA_12 - EMA_26`; `signal = EMA_9(MACD)`;
  `histogram = MACD - signal`. Trend-following via the difference of two
  smoothings; all three lines lag by construction.
  `indicators/Indicators.java` (macd); Appel (2005).
- `upper/lower = SMA_n +- k * stdev_n`. Bollinger bands, a rolling
  z-score drawn as a channel (k = 2 by convention). Band width is itself
  a volatility signal. `indicators/Indicators.java` (bollinger); Bollinger
  (2001).
- `TR = max(H - L, |H - C_prev|, |L - C_prev|)`; ATR is TR smoothed with
  Wilder's method. True range counts the overnight gap the plain range
  misses. `indicators/Indicators.java` (trueRange, atr); Wilder (1978).
- `VWAP_t = sum_{i<=t} tp_i v_i / sum_{i<=t} v_i`, `tp = (H + L + C)/3`.
  The cumulative volume-weighted average price anchored at the first bar
  of the series; late in a session it barely moves.
  `TechnicalFilters.priceAboveVwap` uses a rolling 20-bar VWAP instead.
  `indicators/Indicators.java` (vwap); Berkowitz, Logue and Noser (1988).

**Numerical machinery**

- `S_{t+dt} = S_t exp((mu - sigma^2/2) dt + sigma sqrt(dt) z)`. The exact
  GBM log-Euler step: exact in distribution for constant coefficients.
  Leaving out the `-sigma^2/2` correction inflates every simulated drift.
  `simulation/MonteCarloSimulator.java` (simulate draws the exact terminal
  value; simulatePaths gives correlated multi-asset paths); Glasserman
  (2004), section 3.2.
- Antithetic variates: run each path with z and -z and average. The part
  of the payoff that is monotone in z cancels, which reduces variance at
  no extra cost. `pricing/Autocallable.java` (price) and
  `MonteCarloSimulator.simulatePaths`/`price` with `antithetic = true`,
  which also report standard errors; seeded `SplittableRandom` streams
  make results reproducible. Glasserman (2004), section 4.2.
- `A = L L'` (lower-triangular L). The Cholesky factorisation that turns
  independent Gaussians into correlated ones (`x = L z`).
  `LinearAlgebra.cholesky` fails on a matrix that is not positive definite;
  `PsdMatrices.choleskyPsd` accepts positive semidefinite (rank-deficient)
  matrices and rejects indefinite ones, which should be repaired
  explicitly with `nearestCorrelation`. `stats/LinearAlgebra.java`,
  `risk/PsdMatrices.java`; Golub and Van Loan (2013), section 4.2.
- `N(x) = erfc(-x/sqrt(2)) / 2` with erfc from Cody's rational Chebyshev
  approximations, accurate to about 1e-15 relative error down to
  x = -37.5. `N^{-1}(p)`: Acklam's rational approximation (absolute error
  below 1.15e-9) refined by one Halley step against the double-precision
  CDF, which brings it to double precision. `stats/SpecialFunctions.java`
  (normCdf, normInv, logNormPdf); Cody (1969), Acklam (2003).
- Implied volatility by a safeguarded Newton iteration on the normalised
  Black function `b(x, s) = e^{x/2} N(x/s + s/2) - e^{-x/2} N(x/s - s/2)`,
  `x = ln(F/K)`, `s = sigma sqrt(T)`. An in-the-money quote is first
  converted to the out-of-the-money one by parity, Newton runs on ln b
  below the inflection point and on b above it, and every step stays
  inside a shrinking bracket. A price equal to intrinsic gives 0; a price
  outside the attainable range gives NaN rather than a search bound.
  `pricing/ImpliedVolatility.java`, used by `BlackScholes.impliedVol` and
  `Black76.impliedVol`; Jäckel (2015) for the normalisation.
- `beta = cov(y, x) / var(x)`, `alpha = mean(y) - beta mean(x)`. The
  simple OLS pair behind hedge ratios, betas, AR(1) fits and factor
  loadings. OLS minimises vertical error, so when x is noisy the slope is
  biased toward zero. `alpha/Ols.java` solves the multivariate case by
  Householder QR with classical and HAC errors. `stats/Stats.java`
  (sampleCovariance, pearson, pearsonOrZero), `risk/RiskMetrics.java` (beta); Greene,
  *Econometric Analysis*, chapter 3; Golub and Van Loan (2013), section
  5.3.
- `variance = sum (x - mean)^2 / (n - 1)` for n >= 2. `Stats.sampleVariance`,
  `sampleStdDev` and `populationStdDev` return NaN when there are too
  few observations rather than 0, so a one-point window cannot pass as a
  zero-risk asset. `stats/Stats.java`.

---

[Index: LEARN.md](../LEARN.md) | [Previous: How to use this guide](16-how-to-use-this-guide.md)
