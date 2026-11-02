# Quant-Finance-Library (quantfinlib)

[![CI](https://github.com/AshJha0/Quant-Finance-Library/actions/workflows/ci.yml/badge.svg)](https://github.com/AshJha0/Quant-Finance-Library/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Docs](https://img.shields.io/badge/Docs-GitHub%20Pages-blue.svg)](https://ashjha0.github.io/Quant-Finance-Library/)

quantfinlib is a Java library for quantitative finance research and risk, with no
runtime dependencies beyond the JDK. It covers pricing, rates and credit analytics,
volatility models, market risk (VaR, expected shortfall, stress, FRTB-style ES),
portfolio construction, backtesting and factor research. Alongside the research code
it ships a reference low-latency trading lane: a market-data bus, a pre-trade risk
gate, an order gateway with an order state machine, a matching engine, and FIX, SBE
and ITCH codecs. The trading lane is a worked reference design, not a certified
connectivity stack; see [docs/STABILITY.md](docs/STABILITY.md).

## Status

- Version 2.2.0. Requires Java 25 (LTS). The full suite (`./mvnw verify -Pfull`) runs
  1,745 tests.
- Version 2.2 is additive over 2.1 apart from one default change:
  `StrategyRunner` now trades whole shares. New: holiday calendars and ISDA conventions,
  multi-currency exposure and bilateral CVA, more of SA-CCR, the FRTB standardised
  approach, FIX 4.2 and 5.0 SP2 with
  repeating groups, entitlements, MiFIR RTS 22 reports and corporate events in the
  portfolio backtester. [docs/MIGRATION_2.2.md](docs/MIGRATION_2.2.md) explains how
  to keep 2.1 behaviour.
- Version 2.1 is additive over 2.0: multi-curve rates, the ISDA CDS model, exposure
  simulation and SA-CCR, SVI/SSVI and local volatility, a factor risk model, FIX
  session schedules, call auctions, a position ledger and versioned limits. Code
  written for 2.0 compiles unchanged; [docs/MIGRATION_2.1.md](docs/MIGRATION_2.1.md)
  lists the new APIs and the few behaviour notes.
- Version 2.0 changes several APIs and default behaviours (next-open backtest fills,
  Acerbi-Tasche expected shortfall, the MAR33 stressed ES formula, SBE schema 2, the
  FIX session journal). Read [docs/MIGRATION_2.0.md](docs/MIGRATION_2.0.md) before
  upgrading from 1.x.
- From 2.0.0 the library follows semantic versioning for the packages listed as
  stable in [docs/STABILITY.md](docs/STABILITY.md).

## Install

### JitPack

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.AshJha0</groupId>
  <artifactId>Quant-Finance-Library</artifactId>
  <version>v2.2.0</version>
</dependency>
```

Gradle: `implementation("com.github.AshJha0:Quant-Finance-Library:v2.2.0")` with
`maven("https://jitpack.io")` in `repositories`.

The jars attached to each GitHub release can also be put on a classpath directly.
Publishing to Maven Central is not set up yet; see [docs/PUBLISHING.md](docs/PUBLISHING.md).

### Module path

The jar is the named module `com.quantfinlib`. Add `requires com.quantfinlib;` to
your `module-info.java`, or use the jar on the class path as before. Faults in
external input throw types that implement `core.QuantFinException`
(`WireProtocolException`, `MalformedDataException`, `ConfigurationException`); each
extends the JDK exception the same code threw in 2.0. Descriptive statistics have one
home, `stats.Stats`. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

### Build from source

```bash
git clone https://github.com/AshJha0/Quant-Finance-Library.git
cd Quant-Finance-Library
./mvnw verify            # compile, test, coverage check, package
```

On Windows use `mvnw.cmd`. The Maven wrapper downloads Maven itself; you only need a
JDK 25 on `JAVA_HOME`. [docs/GETTING_STARTED.md](docs/GETTING_STARTED.md) walks through
the build, the command-line backtester, the paper-trading demo and the benchmarks.

## Quick start

```java
import com.quantfinlib.backtest.BacktestConfig;
import com.quantfinlib.backtest.BacktestResult;
import com.quantfinlib.backtest.Backtester;
import com.quantfinlib.backtest.strategies.SmaCrossStrategy;
import com.quantfinlib.core.BarSeries;
import com.quantfinlib.data.CsvBarLoader;
import com.quantfinlib.pricing.BlackScholes;
import com.quantfinlib.pricing.BlackScholes.OptionType;
import com.quantfinlib.risk.TailRisk;

import java.nio.file.Path;

public class QuickStart {
    public static void main(String[] args) throws Exception {
        // Black-Scholes-Merton call: spot 100, strike 105, r 3%, carry (dividend) 1%,
        // vol 20%, six months. Then invert the price back to the volatility.
        double call = BlackScholes.price(OptionType.CALL, 100, 105, 0.03, 0.01, 0.20, 0.5);
        double vol = BlackScholes.impliedVol(OptionType.CALL, call, 100, 105, 0.03, 0.01, 0.5);
        System.out.printf("call %.4f, implied vol %.6f%n", call, vol);

        // 97.5% VaR and Acerbi-Tasche expected shortfall of a loss sample (positive = loss).
        double[] losses = new double[1000];
        for (int i = 0; i < losses.length; i++) {
            losses[i] = i + 1;
        }
        TailRisk.Tail tail = TailRisk.of(losses, 0.975);
        System.out.printf("VaR %.1f, ES %.1f%n", tail.var(), tail.expectedShortfall()); // 975.0, 988.0

        // Moving-average crossover on daily bars from a CSV file
        // (columns date,open,high,low,close[,volume]). Orders fill at the next bar's open,
        // with 1 bp commission and 3 bp slippage by default.
        BarSeries bars = CsvBarLoader.load(Path.of("bars.csv"), "SPY");
        BacktestResult result = Backtester.run(new SmaCrossStrategy(10, 30), bars,
                BacktestConfig.defaults());
        System.out.printf("Sharpe %.2f, max drawdown %.1f%%%n",
                result.metrics().sharpeRatio(), result.metrics().maxDrawdown() * 100);
    }
}
```

The same backtest runs without writing Java through the bundled command line:

```bash
java -jar target/quant-finance-library-2.2.0.jar backtest \
    --csv bars.csv --symbol SPY --strategy sma --fast 10 --slow 30 --out report.html
```

## What is in it

Package names are under `com.quantfinlib`. Each package has a `package-info.java`
with a summary; the javadoc is published at
<https://ashjha0.github.io/Quant-Finance-Library/api/>.

**Pricing and instruments**
- `pricing`: Black-Scholes-Merton with Greeks, Black-76, Bachelier (normal model),
  an implied-volatility solver on the normalised Black function, binomial trees,
  Heston, SABR (Hagan 2002, Levenberg-Marquardt calibration), barrier options (all
  eight Reiner-Rubinstein types with rebates), digitals, touches, Asian, quanto,
  exchange (Margrabe) options, variance swaps, exact vanna-volga and a vanna-volga one-touch,
  autocallables, simple structured notes, an equity volatility surface; Heston by
  the COS method with calibration; raw SVI and SSVI surfaces with arbitrage checks;
  Dupire local volatility with Monte Carlo and PDE pricers; pay-at-hit and
  double-barrier touches; seasoned Asians (Turnbull-Wakeman, Curran, Levy); American
  options with discrete cash dividends on a tree.
- `rates`: single yield curve and a multi-curve bootstrap (OIS discounting with
  projection curves from deposits, FRAs, futures with convexity adjustment and swaps;
  log-linear or monotone-convex interpolation) with bucketed and key-rate DV01; bond pricing on street conventions, day counts
  (including ACT/ACT ICMA and 30E/360), business calendars with stubs and end-of-month
  rules, swaps, swaptions and caps/floors (lognormal, shifted lognormal, normal),
  Nelson-Siegel and Svensson fits, short-rate models, key-rate durations.
- `credit`: hazard-rate curve bootstrapped from CDS par spreads, CDS legs and par
  spread, the ISDA CDS Standard Model (IMM dates, upfront and quoted-spread
  conversion, holiday calendars and half-day accrual as options, checked against the
  ISDA RFR test grids), bond Z-spread, Monte Carlo netting-set exposure (EE, PFE, EPE)
  with a CSA, single-currency or multi-currency (several FX pairs, multi-curve swaps),
  CVA, DVA, bilateral CVA and wrong-way CVA.
- `fx`: currency pair conventions, swap points, FX swaps, NDFs, a delta-quoted FX
  volatility surface (forward or spot delta, premium adjustment, broker strangle
  butterflies), fixing risk, aggregated LP books and cross rates.
- `commodities`, `markets`: futures curves and roll yield; index construction and
  private-market (IRR, PME, desmoothing) analytics.

**Risk**
- `risk`: VaR and expected shortfall (delta-normal, delta-gamma, Monte Carlo,
  historical, filtered historical, full revaluation), one shared empirical tail
  definition (`TailRisk`), extreme value theory, copulas, PCA, stress testing,
  component VaR, VaR backtests (Kupiec, Christoffersen, Acerbi-Szekely), FRTB-style ES
  and P&L attribution, PSD repair and nearest correlation matrix, limits,
  counterparty exposure, SA-CCR (BCBS 279, with basis and volatility hedging sets,
  shared margin agreements and collateral haircuts), the FRTB standardised approach
  (sensitivities-based method, default risk charge, residual risk add-on) and NMRF
  stress capital, and a market, sector and style factor
  risk model with risk decomposition. See [docs/MARKET_RISK.md](docs/MARKET_RISK.md).
- `entitlements`, `reporting`: deny-overrides entitlements with time-bounded grants,
  an audit log and a pre-trade check; a MiFIR RTS 22 transaction report builder with
  LEI, ISIN and MIC check-digit validation.
- `crb`: a central risk book that nets equity and FX products onto one factor space,
  with internalisation, cost-aware hedging and routing. See
  [docs/CENTRAL_RISK_BOOK.md](docs/CENTRAL_RISK_BOOK.md).
- `volatility`: EWMA, GARCH(1,1), GJR-GARCH and EGARCH fitted by maximum likelihood,
  HAR-RV, range estimators, a VIX-style index.
- `hedging`: delta hedging simulation, Greek hedging, minimum-variance and pairs
  hedges, Whalley-Wilmott bands, cointegration tests.

**Portfolio, research and backtesting**
- `optimization`: mean-variance and constrained portfolios by an exact active-set QP,
  risk parity, Black-Litterman.
- `alpha`: factor research (IC with Newey-West t-stats, Fama-MacBeth, quantile
  spreads, permutation tests), point-in-time fundamentals, portfolio construction and
  an alpha backtester with financing and participation caps, optional risk-model
  construction by a turnover-aware optimiser, alpha combination, P&L attribution and
  capacity curves.
- `backtest`: bar, portfolio, tick-level and execution-algorithm backtesters, cost
  models, walk-forward analysis, purged k-fold, deflated Sharpe ratio, bootstrap
  tools, corporate events (spin-offs, rights, reverse splits, mergers and more) in the
  portfolio backtester, and `StrategyRunner`, which runs one strategy on the bar, tick
  and paper-trading engines in whole shares.
- `indicators`, `screener`, `dsl`, `ml`, `simulation`, `report`, `cli`: technical
  indicators (batch and streaming), a stock screener, a rule-based strategy builder,
  regime and volatility forecasting models, Monte Carlo simulation, HTML/PDF/XLSX/CSV
  reports and the command line.

**Execution and the trading lane (reference implementations)**
- `microstructure`, `execution`: impact and queue models, Almgren-Chriss, TCA,
  benchmark algorithms (VWAP, TWAP, POV, implementation shortfall), smart order
  routing.
- `marketdata`, `orderbook`, `trading`: single-producer ring buffers, a market-data
  bus, a price-ladder matching engine, opening and closing call auctions, a pre-trade
  risk gate with a kill switch and versioned, audited limits, a position and P&L
  ledger with drop-copy reconciliation, fed from gateway fills by `LedgerFillListener`, an
  order gateway with cancel/replace and an order state machine, quoting and auto
  hedging, a paper-trading venue. See [docs/ULTRA_LOW_LATENCY.md](docs/ULTRA_LOW_LATENCY.md).
- `fix`, `sbe`, `feed`, `data`: a FIX session (4.4 by default, 4.2 and 5.0 SP2 over
  FIXT.1.1 as options, repeating groups from a dictionary) with a CRC-checked journal, session schedules and
  NextExpectedMsgSeqNum, SBE-style
  binary codecs (schema 2), ITCH 5.0 decoding and MoldUDP64 framing, a WebSocket feed,
  tick capture and replay files.

[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) has the full package map.

## Not implemented

These are common requirements that the library does not cover, or covers only in
part. Code that needs them should use a specialised library or add them.

- ISDA SIMM: not included; ISDA licenses SIMM for use.
- FRTB SA certification, securitisation, and the inputs left to the caller (CSR
  inter-bucket correlations, GIRR inflation and basis correlations, equity repo).
- The CRE22 (2017) SA-CCR collateral haircut table; haircuts follow Basel II para 151.
- Exposure for foreign-currency swaps, a stochastic basis, and wrong-way risk
  together with bilateral CVA.
- Trade busts and corrections in `PositionLedger` and the fill listeners.
- The hot-path FIX order encoder writes FIX 4.4 only; message views are not
  dictionary-driven; no per-message ApplVerID.
- MiFIR XSD validation and ESMA auth.016 output, GLEIF, FIRDS and ISO 10383 lookups,
  and CAT or EMIR reports.
- Corporate-action elections and cost basis inside the portfolio backtester
  (`CorporateEventPositions` handles them for single holdings).
- Live broker or exchange connectivity, certification, and kernel-bypass networking.
- A smaller public API surface is planned for 3.0; 2.x keeps every 2.0 public type.

## Performance

Benchmark method, environment and results are in [docs/BENCHMARKS.md](docs/BENCHMARKS.md).

## Documentation

| Document | Contents |
|---|---|
| [GETTING_STARTED.md](docs/GETTING_STARTED.md) | Build, first backtest, paper trading, benchmarks, troubleshooting |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Package map, data flow, design rules |
| [STABILITY.md](docs/STABILITY.md) | Stable APIs, reference implementations, versioning policy |
| [MIGRATION_2.2.md](docs/MIGRATION_2.2.md) | The two 2.2 default changes and how to keep 2.1 behaviour |
| [MIGRATION_2.1.md](docs/MIGRATION_2.1.md) | What 2.1 adds and how to adopt it |
| [MIGRATION_2.0.md](docs/MIGRATION_2.0.md) | Breaking changes from 1.x |
| [LEARN.md](docs/LEARN.md) | Tutorial from first principles, with exercises |
| [COOKBOOK.md](docs/COOKBOOK.md) | Runnable recipes |
| [DIAGRAMS.md](docs/DIAGRAMS.md) | Mermaid diagrams of the main flows |
| [MARKET_RISK.md](docs/MARKET_RISK.md) | The market-risk workflow mapped to classes |
| [CENTRAL_RISK_BOOK.md](docs/CENTRAL_RISK_BOOK.md) | Central risk book design |
| [ULTRA_LOW_LATENCY.md](docs/ULTRA_LOW_LATENCY.md) | The trading lane, its order lifecycle and tuning |
| [BENCHMARKS.md](docs/BENCHMARKS.md) | How performance is measured, and results |
| [PUBLISHING.md](docs/PUBLISHING.md) | Release process |
| [CHANGELOG.md](CHANGELOG.md) | Release history |
| [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md) | Contributing and reporting vulnerabilities |

## Licence

MIT. See [LICENSE](LICENSE).
