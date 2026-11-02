# Moving to 2.2

Version 2.2 adds packages, classes, methods and overloads. It removes no 2.1 public
type or method and changes no 2.1 signature, and the API compatibility gate checks
this against the released 2.1.0 jar. Two things can still change what your code does
or whether it compiles: the `StrategyRunner` default sizing, and the extra component
of the `FixSessionOptions` record. Both are below, with the way back to 2.1
behaviour. [CHANGELOG.md](../CHANGELOG.md) has the full list.

## New APIs

| Area | Types (under `com.quantfinlib`) |
|---|---|
| Rates and credit | `rates.HolidayCalendars`; `credit.IsdaCdsModel.Conventions` and overloads taking it; `credit.MultiCurrencyExposureSimulator`; `CvaApproximator.bilateral`, `Bilateral` |
| Counterparty and market capital | `SaCcrTrade.asBasis`, `asVolatility`; `SaCcr.HedgingSetType`, `oneMarginAgreementManyNettingSets`, `subNettingSets`, `SubNettingSet`; `risk.SaCcrHaircuts`; `risk.FrtbSa`, `FrtbDrc`, `FrtbRrao`, `FrtbNmrf` |
| Initial margin | Removed in 2.3.0, see [MIGRATION_2.3.md](MIGRATION_2.3.md) |
| FIX | `fix.FixVersion`, `FixDictionary`, `FixGroupDef`, `FixGroup`; `FixSessionOptions.withVersion`; `FixMessage.Builder.group` |
| Controls and reporting | `entitlements` package (`EntitlementService`, `Grant`, `Permission`, `Action`, `Decision`, `EntitlementPreTradeCheck`); `reporting` package (`Rts22ReportBuilder`, `Rts22TransactionReport`, `Rts22Field`, `ReportingIdentifiers`, `TransactionReport`, `TransactionReportSink`) |
| Backtesting and trading | `data.CorporateEvents`, `CorporateEventPositions`; `PortfolioBacktester.run(..., corporateEvents)`; `StrategyRunner.Sizing` and `run(..., Sizing)`; `Backtester.runWholeLots`; `trading.FillListener`, `LedgerFillListener`, `HftOrderGateway.addFillListener`, `fillListenerErrorCount`, `onFill(orderId, execId, ...)` |
| Exceptions | `core.MalformedFileException` |

The module descriptor exports the three new packages, so module-path users need no
change beyond `requires com.quantfinlib;`.

## Behaviour changes

### StrategyRunner trades whole shares

In plain terms: the bar engine used to buy `cash / price` shares, fractions
included, while the tick and paper engines bought whole shares. The three engines
therefore disagreed whenever cash over price was not a whole number. In 2.2 the
default is whole shares on all three, so a difference between engines can only come
from an engine.

Formally, `StrategyRunner.run(engine, strategy, tape, capital, workDir)` now behaves
as `run(..., Sizing.wholeShares())`. The bar engine sizes an entry through
`Backtester.runWholeLots` as `q = lot * floor(cash / (f (1 + c) lot))`, with fill
price `f`, commission rate `c` and `lot = 1`; the runner's contract has no costs, so
`c = 0`.

Example: with 1,000,000 of cash and a close of 333.00, 2.1 bought 3,003.003 shares
in the bar engine and 3,003 in the other two. 2.2 buys 3,003 in all three, and the
remaining 1.00 stays in cash (arithmetic; the javadoc of `runWholeLots` has a
similar case from the library's own test: cash 1000, price 30, 33 shares, 10 left).

To keep the 2.1 result:

```java
StrategyRunner.Run r = StrategyRunner.run(engine, strategy, tape, capital, workDir,
    StrategyRunner.Sizing.fractionalShares());
```

`Sizing.lots(100)` sizes in round lots instead.

### FixSessionOptions has a third component

`FixSessionOptions` is a record, and it is now
`(FixSessionSchedule schedule, boolean nextExpectedMsgSeqNum, FixVersion version)`.
The two-argument constructor still exists and selects FIX 4.4, the accessor methods
are unchanged, and compiled 2.1 code links and runs. Only source that deconstructs
the record needs a change:

```java
// 2.1
if (o instanceof FixSessionOptions(var schedule, var next)) { ... }
// 2.2
if (o instanceof FixSessionOptions(var schedule, var next, var version)) { ... }
```

A `null` version means FIX 4.4, so sessions behave exactly as in 2.1 unless you call
`withVersion`.

### Initial margin package

Removed in 2.3.0; see [MIGRATION_2.3.md](MIGRATION_2.3.md).

## Behaviour notes

- Format faults in tick files, checkpoints, FIX session journals and venue or LP
  scorecard state now throw `core.MalformedFileException`. It extends `IOException`,
  so existing `catch (IOException e)` blocks still catch it.
- `IsdaCdsModel` keeps its 2.1 defaults (`Conventions.DEFAULT`: weekends only, no
  half-day accrual). Matching the 2022 ISDA RFR test grid needs
  `Conventions.of(HolidayCalendars.calendar(NEW_YORK_FED, ...)).withHalfDayAccrual(true)`.
- `ExposureSimulator` is unchanged. `MultiCurrencyExposureSimulator.Market.fromLegacy`
  converts its market and reproduces its profiles.
- `PortfolioBacktester.run` without a corporate-events map behaves as in 2.1.
- `HftOrderGateway` with no fill listener registered behaves as in 2.1. A listener
  that throws is counted in `fillListenerErrorCount` and does not stop the gateway.

## Build and test tooling

- `./mvnw -Papi-compat verify` compares the build with the released 2.1.0 jar
  (`scripts/fetch-api-baseline.sh` fetches it). See [STABILITY.md](STABILITY.md).
- The ISDA grid test runs only when `QFL_ISDA_GRID_DIR` points at grids converted
  by `scripts/isda-grid-to-csv.py`. The grids are not in the repository.
