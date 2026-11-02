# Moving to 2.4

2.4.0 is additive over 2.3.0. No public signature in a stable package is removed or
changed (japicmp against 2.3.0), and no result changes unless you turn on a new
option. What 2.4 does change is the warnings you see: it deprecates for removal
everything that 3.0 takes out. [MIGRATION_3.0.md](MIGRATION_3.0.md) is the draft
plan for 3.0 and gives the replacement for each item.

## 1. Deprecation warnings

Compile with `-Xlint:removal` (or look for `[removal]` warnings in your build). Each
warning is a line that 3.0 will break. The items are:

| Deprecated in 2.4.0 | Use instead | Result change when you switch |
|---|---|---|
| `util.MathUtils` (whole class) | `stats.Stats`, `SpecialFunctions`, `LinearAlgebra`, `Numerics` | none: `MathUtils` already delegates |
| `PortfolioOptimizer(mu, cov, seed)`, `ConstrainedPortfolioOptimizer(mu, cov, seed)` | the two-argument constructors | none: the seed was ignored |
| 8-argument `VenueQuote` constructor | the 9-argument constructor with the feed timestamp | the quote can now go stale; pass `Long.MAX_VALUE` for the old behaviour |
| `ExposureSimulator.Market`, `Market.ratesOnly`, `ExposureSimulator.simulate`, `MultiCurrencyExposureSimulator.Market.fromLegacy` | `MultiCurrencyExposureSimulator.Market` and `simulate` | single-currency results may differ in the last digits once 3.0 folds the two simulators |
| `backtest.strategies.EmaCrossStrategy`, `RsiStrategy`, `MacdStrategy`, `BollingerBandsStrategy` | copy from `examples.strategies` | none |
| `fix.FileSessionStore` reading the v1 layout | run `examples.FixJournalMigration` once | none |

Worked example. Before:

```java
double z = MathUtils.normInv(0.99);
double vol = MathUtils.stdDev(returns);
```

After, with the same IEEE-754 result:

```java
double z = SpecialFunctions.normInv(0.99);   // 2.3263478740408408
double vol = Stats.sampleStdDev(returns);
```

The one method whose name says less than it does is `MathUtils.correlation`: it
returns 0, not NaN, when either input has zero dispersion. Its replacement is
`Stats.pearsonOrZero`; `Stats.pearson` returns NaN.

## 2. New behaviour you can opt into

### Busts and corrections in the paper gateway

`PaperTradingGateway.bustFill(execId)` and `correctFill(execId, qty, price)` act
like a venue's ExecType H and G. Cash and positions are rebuilt from the surviving
fills. Whether the order's filled quantity moves is a switch:

- `setReopenOnBust(false)`, the default, leaves the order as it was. This is the 2.3
  behaviour and matches `HftOrderGateway`.
- `setReopenOnBust(true)` reduces the filled quantity on a bust or a downward
  correction, which can reopen a filled order.

Listeners registered with `addTradeListener` receive `onFill`, `onBust` and
`onCorrect` with execution ids; `LedgerFillListener.forPaperTrades` forwards them
to a `PositionLedger`. Existing `ExecutionListener`s are unchanged and see no busts.

### Securitisation DRC and haircuts

`risk.FrtbDrcSecuritisation` and the new rows of `risk.SaCcrHaircuts` are new code
paths; existing calls return what they did. Two notes on evidence:

- The DRC risk weight is the banking-book securitisation weight times 8%
  (`drcRiskWeight`). MAR22.34(1) and MAR22.42 say to use the banking-book weight
  but do not print the conversion. This is the library's reading (unconfirmed).
  Under it a 1250% weight gives a charge equal to the market value, which is the
  cap in MAR22.34(3).
- `CollateralKind.SECURITISATION` throws under the default `Table.BASEL_II_2006`,
  because paragraph 151 of the 2006 text has no securitisation row. Pass
  `Table.CRE22` for the CRE22.49 rows.

### Reference-data rules and the submission envelope for RTS 22

`validateWithReferenceData(refs, submissionDate)` adds 36 ESMA content rules that
need reference data. With the loaders that ship (GLEIF golden copy, FIRDS FULINS,
ISO 10383) only the status checks can run, because those loaders do not read
registration dates, country, or the systematic-internaliser and commodity flags.
To run the dated rules, build a `ReferenceData` from your own richer source:
`LeiRegistry.validityOn`, the dated `InstrumentRegistry` methods and `CodeRegistry`
are the hooks, and their defaults ignore the date.

`toSubmissionXml(reports, submittingLei, ncaCountry, fileId, created)` wraps
auth.016 in the head.001 header and head.003 envelope. `toAuth016Xml` is unchanged.

## 3. What stays the same

- Every 2.3 result with default options.
- The module descriptor and its `requires` clauses.
- `HftOrderGateway` still does not reopen an order after a bust.
