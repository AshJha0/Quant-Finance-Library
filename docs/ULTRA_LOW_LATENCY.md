# The low-latency trading lane

This page describes the trading lane: what it contains, how an order moves through it,
how it fails safe, how it is measured, and what lies outside a pure-JDK library. The
lane is a reference implementation (see [STABILITY.md](STABILITY.md)). Measured
results are in [BENCHMARKS.md](BENCHMARKS.md); this page has no latency figures of
its own.

## 1. What the library implements

| Technique | Where | Effect |
|---|---|---|
| No allocation on per-event paths | `TickRingBuffer`, `OrderRingBuffer`, `HftRiskGate`, `HftOrderGateway`, `StreamingIndicators`, `HftOrderBook`, `sbe` flyweights, `FixOrderEncoder`, `FixExecReportView` | No garbage, so no collector pauses caused by the trading path. Tests read the per-thread allocation counter and require exactly zero bytes after warm-up. |
| Single-producer, single-consumer rings | `marketdata.TickRingBuffer`, `trading.OrderRingBuffer`, `marketdata.RingBuffer` | No locks or compare-and-swap per event. Producer and consumer sequences sit in a strided array (`StridedSequences`) so they do not share a cache line, and are published with acquire/release ordering. |
| Sequence caching | the rings | Each side caches the other side's sequence and re-reads it only when the ring looks full or empty, which cuts cross-core traffic. |
| Dense integer symbol ids | `SymbolRegistry`, `HftMarketDataBus`, `HftRiskGate` | The per-event path indexes arrays; it does not hash strings or box numbers. |
| Binary codecs | `sbe.*` (schema 2), `marketdata.ItchCodec` | Fixed-offset reads and writes over a reused buffer, with integer prices. |
| Busy-spin waiting | bus and gateway threads, when constructed with `busySpin = true` | `Thread.onSpinWait` hand-off instead of park and unpark, at the cost of a core per thread. |
| Platform stall measurement | `util.HiccupMonitor` | Separates GC, safepoint and scheduler pauses from code latency. `HiccupMonitor.spinning(...)` samples with a busy `nanoTime` loop; the default park mode is limited by the OS timer, which is coarse on Windows. |
| Allocation-free histogram | `util.LatencyRecorder` | Log-linear buckets after HdrHistogram (Tene 2012): exact below 128 ns, then 128 sub-buckets per power of two. Percentiles are upper bounds of the bucket that holds the rank, clamped to the observed min and max. |
| Replay | `marketdata.TickCapture`, `AsyncTickCapture`, `marketdata.tick.TickFileReader` | The same input across runs, so performance comparisons are not confounded by market noise. |

## 2. Order lifecycle

`HftOrderGateway` is the order entry point. One trading thread submits, one venue
thread drains the order ring to the registered `OrderListener` adapters, and one
venue-report thread (often the venue thread itself) reports the venue's answers.

**Submit.** `submit(symbolId, side, quantity, price, timestampNanos)` runs the risk
gate, the optional `OrderThrottle`, claims a slot in the `OrderStateTable`, reserves
the order's quantity in the gate, and publishes into the ring. It returns the order id
(positive), `-reason` for a rejection (an `HftRiskGate.REJECT_*` code), or 0 when the
ring is full and nothing was reserved.

**States.** Every accepted order follows `OrderStateMachine`, after the order state
change matrices of the FIX 4.4 specification (Volume 4):

```
PENDING_NEW --ack--> NEW
PENDING_NEW --reject--> REJECTED
PENDING_NEW, NEW, PARTIALLY_FILLED --fill, leaves > 0--> PARTIALLY_FILLED
PENDING_NEW, NEW, PARTIALLY_FILLED --fill, leaves = 0--> FILLED
PENDING_NEW, NEW, PARTIALLY_FILLED --canceled--> CANCELED
FILLED, CANCELED, REJECTED are terminal
```

A fill before the ack is legal, because some venues send the execution instead of a
separate acknowledgement for marketable orders. Reports for unknown orders and illegal
transitions are counted (`unknownReportCount`, `illegalTransitionCount`) and ignored.

**Reports.** The venue adapter calls `onAck`, `onReject`, `onFill`, `onCanceled`,
`onReplaced` and `onCancelReject`. These update the gate's positions and release the
reserved quantity.

**Cancel and replace.** `cancel(orderId, ts)` marks a live order pending cancel and
sends `OrderListener.onCancel`. `replace(orderId, newQuantity, newPrice, ts)` checks
the new terms with `HftRiskGate.checkReplace`, reserves any extra working quantity at
once, and sends `onReplace`. It returns 0 on success, `-reason` on a risk or throttle
rejection, `REPLACE_NOT_LIVE` when the order is unknown, terminal or already has a
cancel or replace pending, and `REPLACE_RING_FULL` on backpressure. `cancelAll(symbolId,
ts)` sends a mass cancel for one symbol or for `OrderListener.ALL_SYMBOLS`.

**Ack timeouts.** The trading thread should call `checkAckTimeouts(nowNanos)`
periodically. An order still `PENDING_NEW` after the timeout is counted and sent a
cancel; its exposure stays reserved until the venue answers.

**Venue adapters.** `OrderListener` has default methods `onNewOrder` (with the IOC
flag), `onCancel`, `onReplace` and `onCancelAll`. A real adapter must override the
cancel methods; the defaults ignore them. `LoopbackVenue` is an in-process venue for
tests and benchmarks. `PaperTradingGateway` (simulation lane) follows the same state
machine.

## 3. Pre-trade risk and the kill switch

`HftRiskGate` checks each order against per-order quantity and notional limits, a
position limit, a price collar around a reference price, per-symbol halts, daily order
count, daily notional, daily loss (`updateDailyPnl`), and a duplicate-order window.

- **Exposure.** The position limit counts working orders as well as fills: the gateway
  calls `reserve` on submit and `release` on fill, cancel or reject, so resting orders
  cannot take the book past the cap.
- **Prices.** NaN, infinite, zero and negative prices are rejected (`REJECT_PRICE`).
  With `requireReferencePrice(true)` an order without a reference price is rejected
  (`REJECT_NO_REFERENCE`) instead of skipping the collar.
- **Limits at runtime.** Limits can be changed from any thread at any time.
- **Kill switch.** `kill(true)` makes every check fail with `REJECT_KILLED`. Each
  gateway on the gate notices the change on its venue thread, sends `onCancelAll` for
  every symbol, and marks any new order still in the ring as rejected, so no new order
  reaches the venue after the cancel-all.
- **Failures.** A listener that throws is contained and counted, the first exception is
  kept (`failure()`), and the gate is killed. If the venue thread dies anyway, the
  gateway is marked down and every later submit returns `-REJECT_GATEWAY_DOWN`.
  `isHealthy()` turns false in both cases.

Across shards, `GlobalRiskAggregator.start(gates, maxGrossNotional, resumeFraction,
pollIntervalNanos)` runs a monitor thread that sums gross notional, including working
orders, over every gate. On a breach it engages each gate's kill switch, and it also
trips when an exposed symbol has no reference price.

## 4. Strategies on the lane

- `HftQuoter` keeps one working bid and one working offer per symbol. It takes the
  best bid and offer through `onQuote`, quotes around the mid with inventory skew,
  requotes by cancel/replace and waits for the venue's answer before the next change.
  It pulls its quotes on a crossed or locked book and never sends a side that would
  cross the BBO.
- `AutoHedger` sends one IOC hedge at a time, priced at the opposite touch, and waits
  for it to finish before sending another.
- `AvellanedaStoikov` gives the reservation price and optimal spread of Avellaneda and
  Stoikov (2008).

## 5. Venue side: matching engines

- `orderbook.OrderBook` is the readable reference: `TreeMap` price levels and order
  objects.
- `orderbook.HftOrderBook` is a price-ladder engine with occupancy bitmaps, pooled
  order nodes and an open-addressing id map. It supports limit, market (with a
  protection band), IOC, FOK and post-only orders, `modify` (a size reduction keeps
  priority), owner ids with self-trade prevention (cancel resting, cancel incoming,
  cancel both, decrement), and sequenced trade output. Trades are delivered only after
  the book is consistent. A model-based test drives both books with the same random
  operations and requires identical results.

## 6. Protocol edges

**FIX (`fix`).** FIX 4.4 by default; `FixSessionOptions.withVersion` selects FIX 4.2
or FIX 5.0 SP2 over FIXT.1.1, and `FixDictionary` validates repeating groups on
receipt. From 2.3 the hot-path `FixOrderEncoder` writes all three versions, with a
per-message ApplVerID(1128) under FIXT.1.1, and `FixExecReportView` and
`FixMarketDataView` detect the version and read groups through `FixDictionary`
(`execType()` gives the 4.4 meaning for a 4.2 report; `rawExecType()` the wire
value). `FixSession` handles logon, heartbeats, test requests, resend
requests, sequence resets and logout, with validation of CompIDs, SendingTime
(default tolerance 120 s), PossDup and OrigSendingTime. An application message before
Logon disconnects; a well-framed message with a bad field gets a session Reject
(35=3). `FileSessionStore` is a CRC-32C journal: each outgoing message is written and
forced to disk before it reaches the socket, so after a crash a ResendRequest replays
the real orders instead of a GapFill. A torn record at the end of the journal is
truncated on open (`truncatedBytes()`). The journal can be bounded with
`FileSessionStore(dir, maxRetained)`. `FixSession.overStream(...)` runs a session
without its own threads over any transport. On the hot path, `FixOrderEncoder` writes
NewOrderSingle, cancel (35=F) and replace (35=G) without allocation, and
`FixExecReportView` reads execution reports in place; `ExecIdDeduplicator` drops
fills replayed after a resend.

**SBE (`sbe`).** Schema version 2. Every message starts with the standard 8-byte SBE
message header (blockLength, templateId, schemaId, version; `MessageHeader`). Prices
and sizes are int64 mantissas with an int8 exponent (`Decimal64`). 64-bit fields are
8-byte aligned. Readers skip unknown templates by block length, stop on a foreign
schema id, and still decode version-1 frames. `BinaryOrderPublisher` writes through a
non-blocking outbound buffer with a circuit breaker, so a slow peer cannot block the
venue thread.

**ITCH and MoldUDP64.** `ItchCodec` decodes and encodes ITCH 5.0 messages, including
system events, trading actions, cross and broken trades. `L3BookBuilder` rebuilds the
full-depth book and the exact queue position of your own orders, and skips truncated
datagrams (`truncatedCount`). `feed.MoldUdp64Receiver` handles MoldUDP64 framing,
detects sequence gaps, requests retransmission and falls back to a snapshot.

**WebSocket.** `WebSocketFeed` publishes into the bus. When the ring stays full after a
bounded retry, the tick is dropped and counted (`droppedTicks`) and a gap flag is
raised (`consumeGap`); a reconnect raises it too.

## 7. Measuring latency

The benchmarks are in `com.quantfinlib.examples` and `src/test/.../bench`.
[GETTING_STARTED.md](GETTING_STARTED.md) has the commands and
[BENCHMARKS.md](BENCHMARKS.md) the results.

- **Coordinated omission.** A closed-loop benchmark sends the next message only after
  the previous one is done, so a stall delays the senders and hides itself: one slow
  sample instead of many (Tene 2013, "How NOT to measure latency"). The hand-written
  benchmarks therefore also run open loop: messages are sent at a fixed rate, each
  stamped with its intended start time `t0 + i * period`, and latency is measured
  from that time. A stall then shows up in every message it delayed.
- **Clock resolution.** `System.nanoTime()` advances in 100 ns steps on Windows.
  `LatencyRecorder.clockResolutionNanos()` estimates the step and the benchmarks
  print it. A reported value of one step means "at most one tick of the clock".
- **Report percentiles and the maximum**, never only a mean, and run a hiccup monitor
  alongside so tail outliers can be attributed to the platform or to the code.
- **Change one thing at a time** and replay the same captured tick session.
- The JMH suite (`CoreBenchmarks`) runs three forks with warm-up and measurement
  iterations. `riskGateCheckRandomSymbols` draws symbols, sides and prices from a
  table over 1,024 symbols, which is closer to a real book than the single-symbol
  `riskGateCheck`.

## 8. JVM settings

The per-event paths do not allocate, so the collector matters little until other code
allocates. The recommended set for JDK 25, used by the benchmark workflow and the JMH
forks:

```
-Xms2g -Xmx2g                      # fixed heap, no resizing
-XX:+AlwaysPreTouch                # fault pages in at startup
-XX:+UseZGC                        # generational ZGC (the only mode on JDK 24+), short concurrent pauses
-XX:+UseCompactObjectHeaders       # JEP 519, product flag on JDK 25: 8-byte headers, denser rings and books
-XX:+PerfDisableSharedMem          # no hsperfdata mmap file, whose writes can stall on a busy disk
-Xlog:safepoint*:file=sp.log       # correlate stalls with HiccupMonitor output
```

`-XX:+ZGenerational` is not needed (and is obsolete) on JDK 24 and later, and
`-XX:-UseBiasedLocking` no longer exists (biased locking was removed in JDK 18), so
neither appears above.

The no-op collector, `-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC` with a
large fixed heap, never collects. A process that allocates more than the heap dies
instead of pausing. Running a benchmark under Epsilon checks that the steady state does
not allocate.

Remaining JVM effects: safepoints stop all threads for some VM operations; JIT
deoptimisation can recompile a hot method mid-run, so warm every path first; and pure
Java cannot pin a thread to a core. Every component that owns a polling thread takes a
`ThreadFactory` (`util.ThreadPlacement`), so a caller can pin threads with an affinity
library or rely on `taskset` and `isolcpus`, and an `IdleStrategy` (`util.IdleStrategy`:
busy spin, yield, fixed park, or spin-yield-park back-off) for what the thread does when
its ring is empty.

## 9. Operating system and hardware

None of this is library code. `scripts/linux-tune.sh` applies the common Linux
settings:

| Setting | Effect |
|---|---|
| `isolcpus`, `nohz_full`, `rcu_nocbs` and `taskset` | Keeps the scheduler, timer ticks and RCU callbacks off the trading cores. |
| `performance` governor | No frequency ramp-up after idle. |
| Shallow C-states | Avoids wake-up latency from deep sleep states. |
| Transparent huge pages `never` | Avoids compaction stalls. |
| Swap off | No page-ins on the hot path. |
| IRQ affinity | Keeps interrupts off the trading cores. |
| NUMA pinning | Keeps memory and threads on the NIC's socket. |

Windows offers none of these properly, and shared CI runners have no isolation. The
`Benchmarks (Linux)` workflow shows that the benchmarks run on Linux; its tails are
not meaningful.

Beyond the operating system: kernel-bypass networking (DPDK, Onload/ef_vi), NIC
hardware timestamps with PTP-disciplined clocks, RDMA, and FPGA feed handlers and risk
checks. These need native code or hardware and are out of scope.

## 10. Lanes

| Lane | Contents | Contract |
|---|---|---|
| Hot | `marketdata` bus, rings, `ItchCodec`, `L3BookBuilder`, `Nbbo`; `trading` gate, gateway, quoter, hedger, throttle, `LastLookGate`; `orderbook.HftOrderBook`; `execution.HftSor`; `microstructure` streaming signals and models; `sbe`; `marketdata.AggregatedBook`, `CrossRateEngine`, `FxTierBook`; `pricing.IncrementalGreeks`; `fix.FixOrderEncoder`, `FixExecReportView`, `FixMarketDataView`; `util.LatencyRecorder` | No allocation and no locks per event |
| Warm | `execution.BenchmarkExecutor`, `PortfolioExecutor`, `LiquiditySeekingAlgo`, `VenueScorecard`; `microstructure.EwmaCovariance` | Decides per interval (seconds), allocation-free by test |
| Edge | `fix.FixSession`, `feed.WebSocketFeed`, `data` tick files, `persist.Checkpoint` | I/O-bound; `FixSession` takes a lock per send to keep journal and socket in order |
| Research | Everything else | Clarity first; allocation is acceptable |

## 11. Scaling out

`ShardedTradingEngine` runs N independent bus, gate and gateway stacks behind one
symbol-routing facade. A symbol can be registered on several shards;
`publishMissMask` returns a bit mask of the shards that missed the tick. `publish` before
`start()` throws `IllegalStateException`. Firm-wide limits come from
`GlobalRiskAggregator` (section 3).

## Not implemented

- Kernel-bypass networking, thread affinity and NIC hardware timestamps (need native
  code).
- The hot-path views read only the repeating groups they need (NoMDEntries, a count of
  NoPartyIDs); they do not expose party or leg contents in place.
- Order audit trails for regulators. Entitlement checks (`entitlements`) and RTS 22
  reports (`regreporting`) exist; drop-copy reconciliation
  (`trading.DropCopyReconciler`) compares fills the caller supplies, and nothing
  connects a drop-copy session.
- Reopening an order after a bust in `HftOrderGateway` (`PaperTradingGateway` simulates
  busts and corrections from 2.4 and reopens only with `setReopenOnBust(true)`). From 2.3 `HftOrderGateway.onTradeBust` and `onTradeCorrect` (ExecType H and G) reach
  each `FillListener` and `trading.PositionLedger`, which rebuilds the book from the
  surviving fills; `DropCopyReconciler` compares effective fills.
- `orderbook.AuctionBook` leaves out the market-pressure step some venues apply
  before the reference-price tie-break.
- Venue certification; the adapters have only run against in-process venues and
  loopback sockets in tests.
- Off-heap order books (`MemorySegment`) and memory-mapped persistent queues.
- Market-data snapshot recovery for the WebSocket feed; a gap is flagged and left to
  the caller.
