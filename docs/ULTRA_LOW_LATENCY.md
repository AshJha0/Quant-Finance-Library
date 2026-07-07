# Ultra-Low-Latency Reference: What's In the Library, What Needs the Platform, What Needs Hardware

This document is the honest map of the latency stack. Tier 1 is implemented and measured
in this repository. Tiers 2–4 are **outside what a zero-dependency JDK library can do** —
they are documented here so the path beyond is clear.

---

## Tier 1 — In this library (implemented, measured, tested)

| Technique | Where | Why it matters |
|---|---|---|
| Zero allocation on hot paths | `TickRingBuffer`, `OrderRingBuffer`, `HftRiskGate`, `HftOrderGateway`, `StreamingIndicators`, `sbe.*` flyweights | No garbage → no GC pressure → no collector pauses on the trading path. **Proven by per-thread allocation-counter tests**, not just claimed. |
| Lock-free SPSC rings with padded sequences | `marketdata.TickRingBuffer`, `trading.OrderRingBuffer` | No locks/CAS on the hot path; cache-line padding prevents false sharing between producer and consumer cores; acquire/release publication is the minimal memory-ordering cost. |
| Sequence caching | both rings | Each side caches the other's counter and re-reads the volatile only when apparently blocked — removes most cross-core cache traffic. |
| Dense int symbol ids, array-indexed dispatch | `SymbolRegistry`, `HftMarketDataBus`, `HftRiskGate` | The hot path never hashes a `String` or boxes a number. |
| Binary flyweight codecs (SBE-style) | `sbe.TradeFlyweight`, `sbe.OrderFlyweight`, `sbe.QuoteFlyweight` + channel adapters | Fixed-offset primitive reads/writes over a reused buffer: no parsing, no copying, no per-message objects — the wire technique of ITCH/SBE feeds, replacing the text edges (JSON WebSocket, FIX tag-value) where a counterparty offers binary. |
| Streaming quoting/hedging on the fast lane | `trading.HftQuoter` (mid + inventory skew + grid snap + conflation), `trading.AutoHedger` (position-band hedging) | The market-making loop as hot-path code: two-sided quotes and hedge orders reuse the same zero-alloc gate→ring→venue machinery — no separate "slow" quoting stack to fall out of. |
| Multi-venue aggregation without objects | `fx.AggregatedBook` (composite BBO), `fx.CrossRateEngine` (synthetic crosses) | e-FX structures as primitive arrays with linear rescan: zero allocation per quote, venue attribution preserved, crossed composites reported to the strategy rather than hidden. |
| Tick-fresh Greeks without tick repricing | `pricing.IncrementalGreeks` | Delta-gamma Taylor updates per tick (two multiplies); the full Black-Scholes re-anchor runs off the hot path when spot drifts — how live options risk actually stays current. |
| Busy-spin wait strategy (`Thread.onSpinWait`) | bus and gateway consumer threads (optional) | Sub-µs hand-off instead of park/unpark scheduling latency; trades a core for latency. |
| Platform stall attribution | `util.HiccupMonitor` (jHiccup-style) | Separates GC/safepoint/scheduler pauses from code latency; all three benchmarks print a hiccup summary so tail outliers can be attributed correctly. |
| Zero-allocation histograms | `util.LatencyRecorder` | Measurement that doesn't perturb the measured. |
| Deterministic replay | `data.TickCapture` / `TickFileReader` | Identical input across experiments removes market noise from performance comparisons. |

**Measured on a Windows desktop, stock JVM** (reproduce with `HftLatencyBenchmark` /
`HftOrderBenchmark` / `HftQuoterBenchmark`): publish→strategy p50 204 ns; risk check ≈3 ns
(with VarHandle acquire/release cross-thread visibility — the honest 2 ns: a plain-array
gate benchmarks at ≈1 ns only because the JIT hoists loads a concurrent gate must not);
tick→order end-to-end p50 504 ns, p99 1 µs; tick→two-sided-quote p50 592 ns, p99 912 ns
(skew + grid snap + 2× risk gate + both sides at the venue); 21M orders/s
sustained on the v1.4.1 run (15-21M across runs — throughput varies more with
thermal/scheduler state than the latency percentiles do).

---

## Tier 2 — JVM tuning (flags, not code — apply when running)

The library is zero-alloc steady-state, so GC choice matters little **until something else
allocates**. For production-like runs:

```
-Xms2g -Xmx2g                      # fixed heap: no resize pauses
-XX:+AlwaysPreTouch                # fault pages in at startup, not on the hot path
-XX:+UseZGC                        # if anything allocates: sub-ms concurrent pauses
-XX:+UseLargePages                 # fewer TLB misses (needs OS hugepage reservation)
-Xlog:safepoint*:file=sp.log       # correlate stalls with HiccupMonitor output
-XX:GuaranteedSafepointInterval=0  # diagnostic: disable periodic safepoints
```

**Zero garbage collection, literally**: because the hot paths are proven
allocation-free (the tests assert it with the JVM's per-thread allocation counter),
the strongest configuration is the no-op collector —

```
-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xms8g -Xmx8g
```

Epsilon never collects: GC pauses are zero **by construction**, and an unexpected
allocation storm kills the process instead of stalling it — for a trading system,
dying loudly beats trading slowly. The discipline it demands is exactly what this
library provides: startup/setup allocation only, a sized-up heap as the allowance
for the non-hot research/reporting code, and (in some shops) a scheduled daily
restart inside the maintenance window. Run the benchmarks under Epsilon to verify:
steady-state heap usage should be flat after warmup.

Remaining JVM realities no flag removes: **safepoints** (all threads stop for some VM
operations), **JIT deoptimization** (a hot method can be re-profiled mid-session — warm up
every code path first, as the benchmarks do), and **no thread-to-core pinning from pure
Java** (use `taskset`/`Thread affinity` native libs; outside zero-dep scope).

Off-heap notes: this library already uses direct `ByteBuffer`s where it matters (`sbe.*`,
tick I/O buffers). Going further — `MemorySegment`/`Arena` (FFM API) for huge off-heap
order books, `mmap`-ed persistent queues (Chronicle-style) — is the standard next step
when state must outgrow the heap or survive process death without write syscalls.

---

## Tier 3 — Kernel & CPU (OS configuration; see `scripts/linux-tune.sh`)

None of this is code — it is machine configuration, and it is where the p99.9 and max
outliers actually come from:

| Knob | Setting | Effect |
|---|---|---|
| Core isolation | `isolcpus=… nohz_full=… rcu_nocbs=…` boot params + `taskset` | The scheduler never preempts the trading threads; no timer ticks, no RCU callbacks on those cores. **The single biggest tail improvement.** |
| CPU frequency | governor `performance`, disable turbo variance | No ramp-up latency after idle; consistent cycle time. |
| C-states | `max_cstate=1` / `idle=poll` (or `/dev/cpu_dma_latency`) | Waking a core from deep sleep costs 10–100+ µs. |
| Transparent huge pages | `never` | `khugepaged` compaction stalls arbitrary threads for ms. |
| Swap | off | A page-in on the hot path is milliseconds-to-forever. |
| IRQ affinity | route NIC/other IRQs away from isolated cores | Interrupt handlers steal the trading core mid-message otherwise. |
| NUMA | pin memory and threads to the NIC's socket | Cross-socket memory access roughly doubles latency. |
| Network stack | `busy_poll`/`busy_read`, RSS/flow steering, interrupt coalescing off | Kernel receive path tuned for latency over throughput. |

Windows (the dev box here) exposes none of the isolation knobs properly — hence the
observed 100–500 µs max outliers. **A tuned Linux box is the first real step beyond this
repo's numbers**; run `scripts/linux-tune.sh` and the two benchmarks under `taskset`.
The `Benchmarks (Linux)` GitHub workflow runs them on a shared runner — useful for
"works on Linux," meaningless for tails (no isolation on shared runners).

---

## Tier 4 — Kernel bypass & hardware (beyond the JVM entirely)

The frontier, in ascending order of commitment:

1. **Kernel-bypass networking** — DPDK, AMD/Solarflare Onload & `ef_vi`, Mellanox VMA:
   the NIC DMA-writes into user-space rings; no syscalls, no kernel stack, no interrupts.
   Wire-to-app drops from ~5–15 µs (tuned kernel TCP) to ~1–2 µs. Java can reach it via
   JNI/FFM bindings; at this point most shops move the I/O layer to C++.
2. **Hardware timestamps & PTP** — NIC-stamped packets (`SO_TIMESTAMPING`), PTP-
   disciplined clocks: measurement at wire precision instead of `System.nanoTime` at
   syscall precision. Prerequisite for honest wire-to-wire numbers.
3. **RDMA / shared-memory transports** — for intra-colo hops: single-digit µs
   machine-to-machine without either kernel.
4. **FPGA / ASIC** — feed handler + risk check + order gen in silicon: wire-to-wire in
   **tens to hundreds of nanoseconds**. The software stack (this library's tier) becomes
   the control plane that configures the hardware data plane.
5. **Colocation & physical layer** — rack position, switch hops, cut-through switching,
   microwave/shortwave inter-exchange links. At the top of the game the speed of light
   is the budget line.

### Measurement discipline at every tier

- Beware **coordinated omission**: pacing requests (as our paced benchmarks do) or
  correcting for it; blasting-then-averaging hides stalls.
- Report **percentiles including max**, never means alone.
- Run a **hiccup monitor** (in-library: `HiccupMonitor`) alongside every measurement and
  attribute tails to platform vs code before "optimizing" the wrong one.
- Change **one variable at a time**; keep the replayable tick sessions (`TickCapture`)
  as the fixed workload.

---

## Where this repository deliberately stops

**The venue side — two books, two lanes.** The headline sub-microsecond numbers are
*participant-side*: tick in → strategy/quoter decision → risk gate → order ring →
venue adapter out; in that architecture matching happens at the exchange. The library
ships both venue representations explicitly:

- `orderbook.OrderBook` — the *research-grade* model (fuzz tests, queue analytics,
  simulation): deliberately written for clarity (`TreeMap`, per-order objects,
  iterators), and on no measured path;
- `orderbook.HftOrderBook` — the *venue-grade* core: dense integer-tick price ladder
  with per-side occupancy bitmaps, pooled intrusive order nodes, a primitive
  open-addressing id map with backward-shift deletion, zero iterators/boxing, and
  zero steady-state allocation (allocation-counter test, like every hot-path claim).
  Correctness is pinned by a model-based equivalence test that drives both books
  with identical random operation streams — the readable book is the executable
  specification of the fast one. Measured (`HftBookBenchmark`, same Windows box):
  ~204 ns median per operation, 10M+ fills/sec, 7M+ add/cancel ops/sec across a
  20,001-level band; the full session also completes under Epsilon GC (5.6M orders,
  5.3M trades, collector never ran).

**"Why not make EVERYTHING ultra-low-latency?"** Because for most of the library that
question doesn't parse: a PDF exporter, a Monte Carlo engine or a walk-forward
validator is never on a tick's critical path — rewriting them allocation-free would
cost clarity and buy nothing. What matters is that every component has a DECLARED
lane, the hot lane is proven (allocation-counter tests, benchmarks, Epsilon GC runs),
and nothing latency-relevant hides in the research lane. The lane map:

| Lane | Packages / components | Contract |
|---|---|---|
| **Hot** (zero-alloc, proven) | `marketdata` HFT path incl. `ItchCodec`/`L3BookBuilder`/`Nbbo`, `trading` fast lane (gate, gateway, quoter, hedger, `OrderThrottle`), `orderbook.HftOrderBook` (limit/market/IOC/FOK/post-only), `execution.HftSor`, `microstructure.FlowSignals` + `CircuitBreakers.Luld`, `sbe.*`, `fx.AggregatedBook`/`CrossRateEngine`, `pricing.IncrementalGreeks`, `microstructure.TickSizeSchedule` lookups, `fix.FixOrderEncoder` + `fix.FixExecReportView`, `data.AsyncTickCapture` (ring handoff), `util.LatencyRecorder` | no allocation, no locks, no String/boxing per event |
| **Edge** (I/O-bound adapters) | `fix.FixSession` session management, `feed.WebSocketFeed`, `data.TickFileWriter/Reader` | buffered, off the decision loop or explicitly documented when not |
| **Research** (clarity first) | `alpha`, `backtest.*`, `pricing`/`fx` analytics, `rates`, `volatility`, `risk`, `ml`, `optimization`, `screener`, `simulation`, `hedging`, `report`, `cli`, `dsl`, `orderbook.OrderBook` | correctness + readability; allocation is fine because no tick waits on it |

Three components graduated to the hot lane when auditing this boundary:
`fix.FixOrderEncoder` (garbage-free NewOrderSingle out — for venues that only speak
FIX, order entry IS the FIX edge; scaled-long prices, cached date prefix,
backwards-written BodyLength, round-trip-verified against the validated parser),
`fix.FixExecReportView` (garbage-free ExecutionReport in — the fill leg of the same
round trip: a flyweight over the framed bytes, primitive getters, scaled-long prices,
in-place symbol comparison, so orders out AND fills in never allocate or touch a
double), and `data.AsyncTickCapture` (file I/O moved off the bus consumer thread
through a private ring — a disk stall now hits the writer thread, and backpressure
drops-and-counts rather than ever blocking the trading loop).

## The equities participant stack — L3 in, routed orders out

The venue side (`HftOrderBook`) answers "how does an exchange match"; the
participant side answers the harder practical question: <em>what do I do with a
venue's raw feed</em>. That path is hot-lane end to end:

- **`marketdata.ItchCodec`** — an ITCH 5.0-style binary codec (add/execute/
  cancel/delete/replace/trade, exact big-endian layouts). Decoding is a flyweight
  over the wire bytes; symbols travel as packed 8-byte longs, prices as 0.0001-tick
  ints. Encoders exist for simulators and replay only.
- **`marketdata.L3BookBuilder`** — full-depth book reconstruction with the same
  disciplines as the matching engine (tick ladder, occupancy bitmaps, pooled
  intrusive nodes, backward-shift ref map), plus the participant-only feature a
  venue book cannot have: **exact own-order queue position**. Initialization is one
  FIFO walk; maintenance is O(1) per event, resting on two price-time facts —
  executions consume the head, and a cancel is ahead of you iff it queued before
  you. `sharesAhead(ref)` is then a constant-time read, and
  `microstructure.QueueModel` converts it to fill probability.
- **`marketdata.Nbbo`** — per-venue tops consolidated into the national best
  bid/offer with inside size, venue bitmasks, and locked/crossed detection; the
  listener fires only on inside changes, so downstream work is naturally conflated.
- **`microstructure.FlowSignals`** — streaming order-flow imbalance
  (Cont-Kukanov best-level OFI, exponentially time-decayed), queue imbalance and
  signed trade-flow imbalance: the classic short-horizon direction signals,
  allocation-free.
- **`execution.HftSor`** — the routing decision without the routing objects:
  greedy all-in-price sweep (fees/rebates in ticks) over parallel venue arrays,
  child quantities written into a caller array. The research-lane
  `SmartOrderRouter` remains for readable plans; this one is for the tick path.
- **`trading.OrderThrottle`** — a nanosecond token bucket for venue message-rate
  limits; `microstructure.CircuitBreakers` supplies LULD bands/limit-state/pause
  logic and market-wide halt levels so the strategy can see a halt coming rather
  than discover it as rejects.

`HftOrderBook` itself gained the equities time-in-force set — `submitIoc`,
`submitFok` (bitmap-walk liquidity probe, kill emits nothing), `submitPostOnly`
(`REJECT_WOULD_CROSS`) — so simulators can exercise real order-type behavior.

## Scaling out — sharding as shipped machinery

Horizontal scale is shared-nothing sharding: `trading.ShardedTradingEngine` runs N
independent bus→gate→gateway stacks behind one symbol-routing facade (symbols may be
registered on multiple shards — the cross co-location tool: duplicating a leg's feed
costs one extra ring publish). `trading.GlobalRiskAggregator` supplies the one thing
sharding can't: a firm-wide gross-notional circuit breaker across every shard's gate
(monitor thread over the gates' acquire-readable positions; breach flips each gate's
kill switch — a single released boolean the hot path reads as one acquire load; hysteretic
resume so the firm doesn't flap at the limit).

Measured on a 12-logical-core desktop, 300 symbols all quoted two-sided per tick:
1 shard = 4.3M ticks/s; 2 shards = 6.2M (+46%); 4 shards plateau at 6.7M — the plateau
is core oversubscription (8 spinning threads + producer) and the single producer, not
contention: cross-shard drops stayed ≈ 0. On a tuned box with a pinned core pair per
shard (Tier 3 below), scaling approaches k×. One measurement war story worth keeping:
the first version of this probe put ONE synchronized counter across all shards'
venue threads and measured sharding as a slowdown — the instrumentation itself was the
shared state sharding exists to eliminate. Shared-nothing means nothing.

**The platform.** Zero runtime dependencies and pure JDK are design constraints of this
library. Kernel bypass, affinity pinning, FFM-based NIC access, and hardware are all
incompatible with those constraints or with portability — they belong to a deployment,
not to this codebase. The seams to attach them are already in place:

- market data in: publish into `HftMarketDataBus` from any source (see `sbe.BinaryMarketDataClient`),
- orders out: implement `trading.OrderListener` (see `sbe.BinaryOrderPublisher`),
- measurement: `LatencyRecorder` + `HiccupMonitor` work unchanged on any platform.
