# Changelog

## v1.2.0 (2026-07-04)

Everything since v1.1.0 — the trading-system release:

- **Live market data** (`feed`): WebSocket adapter (pure JDK) with Binance
  reference parser, reconnect with backoff, exchange event-time stamps;
  tested against an in-repo RFC 6455 loopback server.
- **FIX 4.4 engine** (`fix`): validated codec, fragmentation-safe decoder,
  initiator/acceptor sessions (logon, heartbeats/TestRequest, logout),
  NewOrderSingle / ExecutionReport, and full ResendRequest recovery
  (message store, PossDup replays, SequenceReset-GapFill, duplicate
  suppression).
- **Tick capture & replay** (`data`): QFLT binary format, bus capture,
  as-fast-as-possible and paced replay; **tick-level backtesting**
  (`backtest.tick`) with queue-position-aware limit fills.
- **HFT order fast lane** (`trading`): zero-allocation HftRiskGate
  (~1 ns/check), OrderRingBuffer, HftOrderGateway — tick-to-order
  end-to-end p50 ≈ 504 ns, 15M orders/sec; allocation-free proven by test.
- **Research validation** (`backtest.validation`): grid search,
  walk-forward analysis with stitched OOS equity and efficiency ratio,
  probabilistic + deflated Sharpe.
- **Portfolio engine** (`backtest.portfolio`): multi-asset long/short
  backtester, position sizing (Kelly, fixed-fractional, inverse-vol,
  vol targeting); **portfolio construction** (`optimization`): risk
  parity, Black-Litterman, constrained optimizer with turnover penalty.
- **Quant models**: yield-curve bootstrap + bond analytics with real
  day-count/calendar conventions (`rates`); EWMA + GARCH(1,1) MLE
  (`volatility`); CRR American options + SABR calibration (`pricing`);
  Engle-Granger cointegration, options-book Greeks/scenarios/P&L-explain
  (`hedging`); 2-state Markov-switching regimes (`ml`); corporate-action
  back-adjustment (`data`).
- **Operations**: CLI (backtest / walkforward / report), live paper-trading
  dashboard (JDK httpserver), paper gateway with pre-trade risk gate,
  SVG charts in HTML reports, CSV/HTTP data loading.
- **Engineering**: model-based fuzz tests, ring-buffer stress tests, JMH
  microbenchmarks (test scope), GitHub Actions CI with JaCoCo coverage,
  runnable jar (`java -jar ... backtest ...`), sources + javadoc jars.

## v1.1.0 (2026-07-04)

- Initial GitHub release: 11 research capabilities (indicators, backtesting,
  DSL, risk, ML, optimization, Monte Carlo, screener, market data, reports),
  microstructure/execution/regulatory stack, hedging suite with
  Black-Scholes/vol surface, HFT market-data hot path (p50 ≈ 204 ns
  publish-to-strategy), execution-aware backtesting with TCA.
