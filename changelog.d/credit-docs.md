# Docs notes for the credit worker (W1.3, W1.4, W1.5)

## ISDA CDS Standard Model (`credit.IsdaCdsModel`, `credit.IsdaCurve`)
- LEARN: why standard CDS trade on fixed coupons plus upfront; the IMM calendar and the semi-annual roll (20 Jun / 20 Dec maturities); step-in T+1, cash settle T+3.
- Formula: on each segment where hazard lambda and forward f are constant, protection = lambda Q0 P0 (1 - e^{-k dt}) / k with k = lambda + f; accrual on default adds a (t0 - tS)(1 - e^{-k dt})/k + a (1 - e^{-k dt}(1 + k dt))/k^2 times lambda Q0 P0. Sources: ISDA CDS Standard Model v1.8.2 (JpmcdsCdsContingentLegPV, JpmcdsFeeLegPV), Markit 2009 numerical-fix note, O'Kane 2008 ch. 6.
- Example: `var c = IsdaCdsModel.standardContract(LocalDate.of(2026,11,2), 5, IsdaCdsModel.COUPON_100BP, IsdaCdsModel.STANDARD_RECOVERY); double up = IsdaCdsModel.quotedSpreadToUpfront(c, 0.03, IsdaCurve.flat(0.035));` gives the points upfront for a 300bp quote.
- Say plainly: the Markit test grid is not reproduced (not verifiable offline); legs are checked against quadrature to 1e-10; holidays are not applied (weekends only); no half-day accrual-on-default adjustment.

## Exposure and CVA (`credit.ExposureSimulator`, `credit.ExposureProfile`, `CvaApproximator`)
- LEARN: EE, ENE, PFE, EPE; why discounted EE at a swap reset date equals a swaption price; how a CSA's threshold, MTA and margin period of risk leave residual exposure.
- Formulas: Hull-White bond price P(t,T) = P(0,T)/P(0,t) exp((V(t,T) - V(0,T) + V(0,t))/2 - B(t,T) x(t)) (Brigo-Mercurio 2006 eq. 3.39); D(0,t) = P(0,t) exp(-V(0,t)/2 - I(t)); FX S(t) = S0 P_f(0,t)/D(0,t) exp(-sigma^2 t/2 + sigma W(t)). Wrong-way: default bucket probability given driver X is Phi((Phi^-1(PD_i) + rho X)/sqrt(1-rho^2)) minus the same at i-1 (Gaussian copula, Gregory 2015 ch. 17).
- COOKBOOK example: simulate a 5y payer swap with `ExposureSimulator.simulate(Market.ratesOnly(curve, 0.05, 0.01), List.of(new Swap(100, 0.032, true, 0, 5, 1)), List.of(), new Csa(0, 0, 10/250.0), quarterlyDates, 10_000, 42L)`, then `CvaApproximator.cva(profile, cptyCurve, 0.6)` and `cvaWrongWay(profile, cptyCurve, 0.6, 0.5, ExposureProfile.Factor.RATES)`.
- Limits to state: single-curve swaps, one FX pair, deterministic foreign rates, unilateral DVA (no first-to-default), terminal driver for wrong-way risk.

## SA-CCR (`risk.SaCcr`, `risk.SaCcrTrade`)
- LEARN: EAD = 1.4 (RC + multiplier x AddOn); supervisory duration (e^{-0.05 S} - e^{-0.05 E})/0.05; IR bucket aggregation with 1.4/0.6 cross terms; systematic plus idiosyncratic aggregation for credit, equity and commodities; multiplier floor 5%. Source: BCBS 279 (March 2014) Annex 4, Table 2.
- Example (BCBS 279 Annex 4a Example 1): two USD swaps and a EUR swaption, `SaCcr.unmargined(trades, 0).ead()` = 569.
- Not covered: basis and volatility hedging sets, multiple margin agreements per netting set, collateral haircuts.
