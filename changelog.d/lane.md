### Fixed
- `HftRiskGate` rejects NaN, zero, negative and infinite prices (`REJECT_PRICE`); before, a NaN order passed every check.
- The position limit in `HftRiskGate` now counts working orders: the gateway reserves each order's quantity and releases it on fill, cancel or reject, so resting orders can no longer exceed the cap.
- Engaging the kill switch now cancels: every `HftOrderGateway` on the gate sends a cancel-all to its venue listeners and drops new orders still queued in the ring.
- A throwing venue listener or bus listener no longer kills its thread silently: the exception is contained and counted, the gate is killed, and `isHealthy()` turns false; a venue thread that dies anyway makes `submit` return `-REJECT_GATEWAY_DOWN`.
- `HftQuoter` keeps one working bid and one working offer per symbol and requotes by cancel/replace, waiting for the venue's answer; before, it stacked a new pair of orders on every tick.
- `AutoHedger` sends one IOC hedge at a time, priced at the opposite touch, and waits until it is finished before sending another; before, it re-sent the full excess after a time cooldown while the first hedge could still be working.
- POV targets in `PovTracker` and `BenchmarkExecutor` are now a share of total volume, `p / (1 - p)` times others' volume; before, a 10 % target realised 9.1 %. Tests that pinned the old targets were changed (for example 500 to 555 shares on 5,000 of volume); `realizedParticipation()` now reports executed / (executed + others) and the rate must be below 1.
- `BenchmarkExecutor`, `PovTracker` and `LiquiditySeekingAlgo` count sent-but-unfilled children against the schedule (`onChildSent`, `onChildDone`), so a resting child is not sent again.
- `BenchmarkExecutor` finishes time benchmarks at the horizon: the depth cap relaxes over the last 10 % of the horizon to all of the displayed depth, so the remainder is worked as far as the book allows.
- `ExecutionAlgoBacktester` feeds POV runs `(1 - p)` of each bar's volume as other traders' volume, so a 10 % POV still trades 10 % of the bar under the share-of-total definition.
- `GlobalRiskAggregator` fails closed: an exposed symbol without a reference price trips the breaker, and working orders count in the worst-case gross notional.
- `HftOrderBook` delivers trades only after the book is consistent, so a throwing trade sink can no longer leave a half-updated book; id-map probes are bounded.
- `LatencyRecorder` percentiles are upper bounds on the true quantile, never bucket midpoints; resolution raised to 128 sub-buckets per power of two (exact below 128 ns).
- `HftLatencyBenchmark` reports the measured tick loss instead of a hard-coded zero.
- `ShardedTradingEngine.publish` before `start()` throws `IllegalStateException` instead of a `NullPointerException`.
- `TradingDashboard` binds to the loopback interface by default.
- Observability counters in the bus, gateway, quoter and hedger are published in opaque mode, so monitoring threads see current values.

### Changed
- `OrderListener` gains default methods `onNewOrder` (with IOC flag), `onCancel`, `onReplace` and `onCancelAll`; existing adapters still compile but must override the cancel methods to honour cancels.
- `OrderRingBuffer` slots carry a message type (new, cancel, replace, cancel-all).
- `OrderStatus` gains `PENDING_NEW`; `PaperTradingGateway` follows the shared `OrderStateMachine`, fills at most the displayed size (new `onQuote` overload with sizes), cancels the unfilled part of market orders and calls execution listeners outside its lock.
- `HftRiskGate` limits can be changed from any thread at any time; new controls: daily order count, daily notional, daily loss (`updateDailyPnl`), duplicate-order window, optional fail-closed collar without a reference price.
- `HftQuoter` takes a BBO through `onQuote`, quotes off the mid, ignores trade prints once a book exists, pulls quotes on a crossed or locked book and never sends a side that would cross the BBO.
- `GlobalRiskAggregator` is created with `GlobalRiskAggregator.start(...)`; the constructor no longer starts a thread.
- `BenchmarkExecutor.IMPLEMENTATION_SHORTFALL` follows the Almgren-Chriss trajectory `1 - sinh(kT(1-f))/sinh(kT)` (urgency `withShortfallUrgency`, default 2) instead of the fixed curve still used by `ARRIVAL_PRICE`.
- `TickRingBuffer`, `OrderRingBuffer` and `RingBuffer` keep their sequences in a strided array (one cache line pair each); `RingBuffer` now uses acquire/release publication and sequence caching.
- `HiccupMonitor.spinning(...)` samples with a busy `nanoTime` loop; the default park mode is documented as timer-bound on Windows.
- Benchmarks print the clock step, run open-loop fixed-rate passes with intended-start timestamps next to the closed-loop ones, measure the risk gate over 1,024 random symbols with an independent collar reference, and time order-book operations in 128-op batches. JMH `CoreBenchmarks` run 3 forks, 5 warm-up and 10 measurement iterations and add `riskGateCheckRandomSymbols`.
- Zero-allocation tests in the trading, market-data, order-book and execution packages assert exactly zero bytes after warm-up, with a bounded retry.

### Added
- `HftOrderGateway.cancel`, `replace`, `cancelAll`, execution-report methods (`onAck`, `onReject`, `onFill`, `onCanceled`, `onReplaced`, `onCancelReject`), order queries, ack-timeout scan and an optional `OrderThrottle`.
- `OrderStateMachine`, `OrderStateTable`, `LoopbackVenue` (in-process venue for tests and benchmarks), `marketdata.QuoteListener`, `marketdata.StridedSequences`.
- `HftOrderBook` owner ids with self-trade prevention (cancel resting, cancel incoming, cancel both, decrement), `modify` (size-down keeps priority), market-order protection band, trade sequence numbers and `SequencedTradeSink`.
- `Nbbo` per-venue update times and `expireStale` with a maximum quote age.
- `ShardedTradingEngine.publishMissMask` reporting which shards missed a tick; each shard's bus kills its gate on a listener failure.
- `HftMarketDataBus.onListenerFailure`, `isHealthy`, `failure`, `listenerErrorCount`.
