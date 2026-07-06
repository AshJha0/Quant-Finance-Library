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

Zero runtime dependencies and pure JDK are design constraints of this library. Kernel
bypass, affinity pinning, FFM-based NIC access, and hardware are all incompatible with
those constraints or with portability — they belong to a deployment, not to this
codebase. The seams to attach them are already in place:

- market data in: publish into `HftMarketDataBus` from any source (see `sbe.BinaryMarketDataClient`),
- orders out: implement `trading.OrderListener` (see `sbe.BinaryOrderPublisher`),
- measurement: `LatencyRecorder` + `HiccupMonitor` work unchanged on any platform.
