# SIMM initial margin (`com.quantfinlib.risk.simm`)

## Licence first

ISDA SIMM is ISDA's intellectual property. Using it to compute margin needs a
licence from ISDA (a signed agreement). This library does not hold one, so:

- The repository contains no ISDA calibration: no risk weights, correlations,
  concentration thresholds, HVRs or psi values from any SIMM version.
- `SimmParameters.illustrative()` is a set of round numbers we made up so the
  code runs end to end. Its label starts with `ILLUSTRATIVE`. It is not any
  SIMM version and its output is not a margin figure.
- The formulas and the aggregation structure were checked against the public
  methodology document *ISDA SIMM Methodology, version 2.9 calibrated to June
  2026 (v2.9+2606)*. That check is about structure. It is not an ISDA
  certification, and the code has not been run against ISDA's unit tests,
  which are also licensed.

A licensed user loads their own calibration file (format below) and is
responsible for its contents.

## Supplying a licensed calibration

The plain idea: the calculator never contains numbers. It asks a
`SimmParameters` object for named values, and you build that object from a
text file you keep outside this repository.

Format, read by `SimmParameters.parse(Reader)`:

- one `key = value` per line; blank lines and lines starting with `#` are
  skipped;
- `label = <free text>` names the calibration (it is copied into every
  `SimmResult`);
- every other value must parse as a Java `double`. Percentages are written as
  fractions (a 45% correlation is `0.45`);
- lookups try a specific key first and then fall back to a more general one,
  so a uniform value can be given once (`Equity.vega.rw`) and overridden for a
  bucket (`Equity.vega.rw.12`).

```java
SimmParameters cal;
try (Reader r = Files.newBufferedReader(Path.of("/secure/simm-2.9-2606.properties"))) {
    cal = SimmParameters.parse(r);
}
SimmResult im = new SimmCalculator(cal).calculate(CrifSensitivity.parseCsv(crifReader));
```

The full key list is in the Javadoc of `SimmParameters`. In summary, with
`RC` one of `IR`, `CreditQ`, `CreditNonQ`, `Equity`, `Commodity`, `FX`:

| What | Keys |
|---|---|
| Delta risk weight | `RC.delta.rw.<bucket>.<tenor>`, `RC.delta.rw.<label2>`, `RC.delta.rw.<bucket>`, `RC.delta.rw.<tenor>`, `RC.delta.rw` |
| Vega risk weight | `RC.vega.rw.<bucket>`, `RC.vega.rw` |
| Concentration thresholds | `RC.delta.threshold[.<bucket>]`, `RC.vega.threshold[.<bucket>]` (absent means no concentration) |
| Intra-bucket correlation | `RC.rho.sameQualifier[.<bucket>]`, `RC.rho.diffQualifier[.<bucket>]` |
| IR correlations | `IR.tenorRho.<t1>.<t2>`, `IR.phi`, `IR.rho.inflation`, `IR.rho.xccy` |
| Inter-bucket correlation | `RC.gamma.<b>.<c>`, `RC.gamma` |
| Historical volatility ratio | `RC.hvr` (default 1) |
| Curvature | `RC.curvature.zeroBucket.<bucket> = 1`, `RC.curvature.multiplier` |
| FX groups | `FX.calculationCurrency.<CCY> = 1`, `FX.highVolGroup.<CCY> = 1`, `FX.category.<CCY> = <n>` |
| FX weights, correlations, thresholds | `FX.delta.rw.<G1>.<G2>`, `FX.rho.<Gcalc>.<G1>.<G2>`, `FX.rho.vega`, `FX.delta.threshold.cat<n>`, `FX.vega.threshold.cat<i>.cat<j>` |
| Base correlation | `CreditQ.baseCorr.rw[.<family>]`, `CreditQ.baseCorr.rho` |
| Risk-class correlation | `psi.<RC1>.<RC2>`, `psi` |
| Additional margin | `addon.fixed`, `addon.factor.<product>`, `multiplier.<RatesFX\|Credit\|Equity\|Commodity>` |

`G` is `Regular` or `High`. Currencies not flagged high are regular. A
currency with no `FX.category` is in category 3.

### Input conventions

- Delta amounts follow methodology section C.2 (PV01/CS01 per 1bp, or the
  value change for a 1% relative move).
- IR and credit vega amounts are already volatility-weighted
  (`sigma * dV/dsigma`, paragraph 10(a)).
- Equity, commodity and FX vega amounts are the raw vega `dV/dsigma` with
  sigma in volatility points (section C.3). The calculator applies
  `sigma_k = RW_k sqrt(365/14) / Phi^-1(0.99)` and the HVR. If your feed
  already includes that factor, set `RC.vega.amountIsVolWeighted = 1`.
- FX vega qualifiers are currency pairs, `EURUSD` or `EUR/USD`.
- New CRIF risk types: `Risk_XCcyBasis` (IR delta, label2 `XCcy`) and
  `Risk_InflationVol` (IR vega, label2 `Inflation`).

## What was checked, against which section

References are to the public v2.9+2606 methodology. "Fixed" means the code
did not match the formula before this round.

| Methodology item | Section | Status |
|---|---|---|
| Risk-class margin = delta + vega + curvature + base correlation | B, para 5 | matched |
| Product-class aggregation with psi, SIMM = sum of four product classes | B, para 6; K | matched |
| IR weighted sensitivity and per-currency concentration | B, para 7(b) | fixed: cross-currency basis now excluded from the concentration sum and never scaled |
| IR intra-currency aggregation with phi and tenor rho, no f_kl | B, para 7(c) | fixed: f_kl forced to 1 in IR (it used per-qualifier ratios) |
| IR across currencies with g_bc = min(CR)/max(CR) | B, para 7(d) | matched |
| Non-IR concentration (credit summed over issuer/seniority, others per risk factor) and f_kl | B, para 8(b,c) | matched |
| Residual bucket added outside the gamma sum | B, para 8(d) | matched |
| Equity/commodity/FX vega volatility sigma = RW sqrt(365/14)/Phi^-1(0.99) and HVR | B, para 10(b,c) | fixed: was missing, the raw amount was used |
| Vega risk factor for equity/commodity/FX is the qualifier (expiries summed) | B, para 10(b,c); C.1 | fixed: each expiry was a separate risk factor |
| FX vega weight from the FX delta table, row group of first currency, column of second | B, para 10(b) | fixed (new) |
| Vega concentration VCR, f_kl = 1 for IR, g_bc from VCR for IR | B, para 10(d,e,f) | fixed with the IR change above |
| Curvature CVR = SF(t) sigma dV/dsigma, SF = 0.5 min(1, 14/days), 365-day year | B, para 11(a) | fixed: sigma now applied for equity/commodity/FX (no HVR, as the formula has none) |
| Curvature netting across expiries only through SF weights | B, para 11(a) | fixed with the vega risk-factor change |
| Equity volatility-index bucket has zero curvature | B, para 11(b) | fixed: `Equity.curvature.zeroBucket.<b>` |
| Curvature K_b with rho squared, theta, lambda, gamma squared; residual separately | B, para 11(c,d) | matched |
| IR curvature scaled by HVR_IR^-2 | B, para 11(d) | fixed: was missing (only a free multiplier existed) |
| Base correlation margin, no concentration, one factor per index family | B, para 13; C.1 para 15 | matched; per-family weight key added |
| IR risk factors include inflation and cross-currency basis | C.1, para 14 | fixed: `Risk_XCcyBasis` and `Risk_InflationVol` added |
| Inflation and xccy correlations with yields; inflation vol against IR vol | D.2 | fixed: xccy rho added; inflation vol now uses tenor rho across expiries |
| FX single bucket, curvature still applied on it | I, para 66 | matched |
| FX delta weight by vol group of currency and calculation currency; none for the calculation currency | I, para 67-69 | fixed (new) |
| FX delta correlation by calculation-currency group and both currencies' groups | I, para 72 | fixed (new) |
| FX vega and curvature flat correlation | I, para 73 | fixed (new) |
| FX delta thresholds by category, vega thresholds by category pair | J.5, J.10 | fixed (new) |
| Additional initial margin: fixed, notional factors, product multipliers at least 1 | L | fixed: `calculateWithAddOns` |

### Not covered

- Within a non-IR bucket, correlation depends only on whether the qualifier
  matches. Credit "same issuer, different payment currency" is handled only if
  the currency is part of the netting key (label2), which is how CRIF carries it.
- Index-volatility special cases rely on the caller putting index vega in the
  right bucket, as section C.1 asks.
- No ISDA unit-test portfolio has been run (licensed material).

## Worked example (illustrative numbers)

One USD 5y OIS delta of 1,000 and a USD cross-currency basis delta of 100,
with the illustrative calibration (rw 50 and 20, xccy correlation -0.1) and a
threshold of 250. The yield sum 1,000 gives `CR = sqrt(1000/250) = 2`; the
basis row is outside that sum and keeps `CR = 1`. So
`WS = 100,000` and `2,000`, and
`K = sqrt(100,000^2 + 2,000^2 - 2 * 0.1 * 100,000 * 2,000) = 99,819.8`.
Evidence class: own test (`SimmStructureTest`).
