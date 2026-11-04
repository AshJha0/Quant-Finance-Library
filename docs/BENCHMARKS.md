# Benchmarks

This page holds every performance result. The README quotes a short summary of it; other documents link here.

## What these numbers are, and what they are not

The numbers below come from one Windows laptop. They show orders of magnitude and the shape of the latency distribution. They are not a statement of what the library achieves on a tuned trading server.

Three properties of the machine limit what the numbers mean:

- **Clock resolution.** `System.nanoTime()` on Windows advances in steps of about 100 ns. Every latency below about 1 µs is therefore quantised to multiples of 100 ns. `LatencyRecorder` reports each percentile as the upper bound of its histogram bucket (`p50<=501ns` means the median sample was at most 501 ns), never as a bucket midpoint.
- **Scheduler and timer.** Windows has no core isolation, and its timer tick is 1 to 15.6 ms. The platform hiccup monitor in every run shows stalls of up to about 20 ms. These stalls dominate the open-loop tails.
- **Hybrid CPU.** The Core 5 120U mixes performance and efficiency cores and was running on its default power plan. Threads that land on an efficiency core run slower, which is the main reason the three runs differ by up to 2x.

A Linux machine with isolated cores, the `performance` governor and the settings in [ULTRA_LOW_LATENCY.md](ULTRA_LOW_LATENCY.md) section 9 would give much tighter tails. No such machine was available for this release.

## Closed loop and open loop

Each latency benchmark reports two measurements.

- **Closed loop:** one message in flight. The next message is sent only after the previous one is consumed. This shows the cost of the path itself, but it hides queueing: if the consumer stalls, the producer stops sending, so the stall delays one sample instead of many. This is coordinated omission.
- **Open loop:** messages are sent at a fixed rate, and each latency is measured from the time the message was due to be sent, not from when it was actually sent. A stall then shows up in every message that queued behind it. This is the number that matters for a system that cannot pause its inputs.

The gap between the two, for example a 301 ns closed-loop p99 against a 287 µs open-loop p99 in the market-data benchmark at 4.0.0, is the effect of coordinated omission on this machine.

## Environment (2.0.0 release candidate run)

| Item | Value |
|---|---|
| Date | 2026-11-01 |
| Code | branch `v2-hardening`, commit `cff580b` (the 2.0.0 release candidate) |
| CPU | Intel Core 5 120U, 10 cores / 12 threads (hybrid P and E cores) |
| Memory | 16 GB |
| OS | Windows 11 Home, build 26200, default power plan |
| JDK | Temurin 25.0.5+8 LTS |
| JVM flags | `-Xms1g -Xmx1g -XX:+UseZGC -XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+PerfDisableSharedMem` |
| Runs | 3 runs of each benchmark, machine otherwise idle |
| Clock step | about 100 ns, measured by `LatencyRecorder` |

## Results for 4.0.0

Tag `v4.0.0`, 2026-11-04, the same laptop and JVM flags on Oracle JDK 27.0.1, machine otherwise idle. Each cell shows min / median / max across three runs. The JDK differs from the run below, so a change between the two is not a measure of the code alone. Latencies are quantised by the clock step of about 100 ns.

### Market data bus: `HftLatencyBenchmark`

| Measure | Min | Median | Max |
|---|---|---|---|
| Throughput, million ticks/s | 21.4 | 21.7 | 23.7 |
| Closed loop p50 | 200 ns | 200 ns | 200 ns |
| Closed loop p99 | 200 ns | 301 ns | 301 ns |
| Closed loop p99.9 | 301 ns | 401 ns | 501 ns |
| Open loop at 500,000 ticks/s, p50 | 200 ns | 200 ns | 200 ns |
| Open loop at 500,000 ticks/s, p99 | 81.41 µs | 286.72 µs | 313.34 µs |
| Open loop at 500,000 ticks/s, p99.9 | 1.76 ms | 2.28 ms | 2.38 ms |

### Order path: `HftOrderBenchmark`

| Measure | Min | Median | Max |
|---|---|---|---|
| Risk gate check, ns (reciprocal throughput, 1,024 random symbols) | 12.8 | 13.1 | 13.2 |
| Submit to venue thread, closed loop p50 | 200 ns | 301 ns | 301 ns |
| Submit to venue thread, closed loop p99 | 301 ns | 401 ns | 401 ns |
| Submit to venue thread, closed loop p99.9 | 401 ns | 603 ns | 1.90 µs |
| Open loop at 200,000 orders/s, p50 | 301 ns | 803 ns | 1.21 µs |
| Open loop at 200,000 orders/s, p99 | 197.63 µs | 221.18 µs | 264.19 µs |
| Open loop at 200,000 orders/s, p99.9 | 565.25 µs | 2.79 ms | 3.16 ms |
| Tick to order end to end, closed loop p50 | 501 ns | 703 ns | 1.61 µs |
| Tick to order end to end, closed loop p99 | 603 ns | 3.31 µs | 5.41 µs |
| Tick to order end to end, closed loop p99.9 | 2.82 µs | 16.51 µs | 16.77 µs |
| Throughput, million orders/s | 2.9 | 2.9 | 7.6 |

### Market maker: `HftQuoterBenchmark`

| Measure | Min | Median | Max |
|---|---|---|---|
| Closed loop tick to quote p50 | 501 ns | 501 ns | 603 ns |
| Closed loop tick to quote p99 | 703 ns | 703 ns | 803 ns |
| Closed loop tick to quote p99.9 | 2.91 µs | 3.41 µs | 3.62 µs |
| Open loop at 100,000 ticks/s, p50 | 603 ns | 1.81 µs | 2.01 µs |
| Open loop at 100,000 ticks/s, p99 | 13.76 µs | 16.64 µs | 97.79 µs |
| Open loop at 100,000 ticks/s, p99.9 | 169.98 µs | 184.32 µs | 407.55 µs |

### Matching engine: `HftBookBenchmark`

| Measure | Min | Median | Max |
|---|---|---|---|
| Passive churn, million add/cancel ops/s | 7.9 | 9.3 | 10.1 |
| Matching, million ops/s | 12.1 | 13.1 | 13.2 |
| Per-op cost p50 (mean of 128-op batches) | 96 ns | 100 ns | 121 ns |
| Per-op cost p99 (mean of 128-op batches) | 221 ns | 225 ns | 337 ns |

Tick to order is the noisiest row: its p99 moved by almost a factor of ten across the three runs.

## Results for the 2.0.0 release candidate

Kept for the record; the Environment table above describes this run.

Each cell shows min / median / max across the three runs.

### Market data bus: `HftLatencyBenchmark`

Publish a tick into the SPSC ring and run a strategy (two EMAs and an RSI) on the consumer thread.

| Measure | Min | Median | Max |
|---|---|---|---|
| Throughput, million ticks/s | 7.9 | 8.2 | 15.0 |
| Closed loop p50 | 200 ns | 401 ns | 401 ns |
| Closed loop p99 | 401 ns | 803 ns | 2.42 µs |
| Closed loop p99.9 | 603 ns | 1.81 µs | 4.70 µs |
| Open loop at 500,000 ticks/s, p50 | 401 ns | 501 ns | 703 ns |
| Open loop at 500,000 ticks/s, p99 | 74.75 µs | 148.48 µs | 2.75 ms |
| Open loop at 500,000 ticks/s, p99.9 | 288.77 µs | 647.17 µs | 4.36 ms |

No ticks were lost in any run: the producer waits on a full ring instead of dropping.

### Order path: `HftOrderBenchmark`

| Measure | Min | Median | Max |
|---|---|---|---|
| Risk gate check, ns (reciprocal throughput, 1,024 random symbols) | 38.8 | 39.9 | 39.9 |
| Submit to venue thread, closed loop p50 | 501 ns | 501 ns | 501 ns |
| Submit to venue thread, closed loop p99 | 903 ns | 1.21 µs | 2.61 µs |
| Submit to venue thread, closed loop p99.9 | 2.11 µs | 9.86 µs | 12.61 µs |
| Open loop at 200,000 orders/s, p50 | 603 ns | 603 ns | 703 ns |
| Open loop at 200,000 orders/s, p99 | 692 µs | 2.39 ms | 3.92 ms |
| Tick to order end to end, closed loop p50 | 903 ns | 903 ns | 903 ns |
| Tick to order end to end, closed loop p99 | 1.50 µs | 1.61 µs | 1.61 µs |
| Tick to order end to end, closed loop p99.9 | 24.06 µs | 49.41 µs | 54.78 µs |
| Throughput, million orders/s | 2.5 | 2.6 | 3.1 |

The risk-gate figure is reciprocal throughput: many independent checks overlapped by the CPU, with symbol ids spread across 1,024 cache lines. It is not the latency of one isolated check. v1.x quoted about 3 ns for the same gate, measured on one symbol with constant arguments. The v2 gate also checks price validity, resting-order exposure, throttles and daily limits.

### Market maker: `HftQuoterBenchmark`

Tick in, two-sided quote out through cancel/replace, risk gate and gateway.

| Measure | Min | Median | Max |
|---|---|---|---|
| Closed loop tick to quote p50 | 903 ns | 1.10 µs | 1.10 µs |
| Closed loop tick to quote p99 | 1.30 µs | 1.50 µs | 1.61 µs |
| Closed loop tick to quote p99.9 | 21.38 µs | 26.37 µs | 60.93 µs |
| Open loop at 100,000 ticks/s, p50 | 1.21 µs | 1.30 µs | 1.30 µs |
| Open loop at 100,000 ticks/s, p99 | 36.61 µs | 1.73 ms | 2.67 ms |

### Matching engine: `HftBookBenchmark`

Price-time `HftOrderBook`, single thread. Per-operation latency is the mean of 128-operation batches, so the 100 ns clock step does not dominate it.

| Measure | Min | Median | Max |
|---|---|---|---|
| Passive add/cancel, million ops/s | 4.8 | 7.1 | 8.9 |
| Matching, million ops/s | 6.6 | 12.0 | 13.1 |
| Matching, million fills/s | 5.7 | 10.4 | 11.3 |
| Per-op p50 | 103 ns | 114 ns | 180 ns |
| Per-op p99 | 230 ns | 317 ns | 583 ns |
| Per-op p99.9 | 839 ns | 1.27 µs | 1.54 µs |

## 2.1.0 regression check

The tables above were measured on 2026-11-01 at the 2.0.0 release candidate. They were not re-measured for 2.1.0, because on 2026-11-02 the same laptop ran two to three times slower for both versions (long-running load and Windows timer stalls of 15 to 20 ms in the hiccup monitor). Numbers from that state would compare the machine, not the code.

Instead, 2.1.0 was checked against the released 2.0.0 jar, back to back on the same machine in the same state, with the flags above. The order-book code path is unchanged between the two versions.

| `HftBookBenchmark`, run | Matching, million ops/s | Per-op p50 | Per-op p99 |
|---|---|---|---|
| 2.0.0 release jar, run 1 | 3.0 | 317 ns | 1.28 µs |
| 2.1.0 build, run 1 | 3.8 | 271 ns | 1.02 µs |
| 2.0.0 release jar, run 2 | 3.6 | 317 ns | 879 ns |
| 2.1.0 build, run 2 | 3.9 | 323 ns | 859 ns |

Result: no regression; 2.1.0 is within run-to-run noise of 2.0.0, slightly faster in three of four comparisons.

## How to reproduce

```bash
./mvnw -DskipTests compile
JVM="-Xms1g -Xmx1g -XX:+UseZGC -XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+PerfDisableSharedMem"
java $JVM -cp core/target/classes:io/target/classes:research/target/classes:lowlatency/target/classes com.quantfinlib.examples.HftLatencyBenchmark
java $JVM -cp core/target/classes:io/target/classes:research/target/classes:lowlatency/target/classes com.quantfinlib.examples.HftOrderBenchmark
java $JVM -cp core/target/classes:io/target/classes:research/target/classes:lowlatency/target/classes com.quantfinlib.examples.HftQuoterBenchmark
java $JVM -cp core/target/classes:io/target/classes:research/target/classes:lowlatency/target/classes com.quantfinlib.examples.HftBookBenchmark
```

The JMH microbenchmarks (`src/test/java/com/quantfinlib/bench/CoreBenchmarks.java`, three forks, five warm-up and ten measurement iterations) run with:

```bash
./mvnw test-compile exec:java -Dexec.mainClass=com.quantfinlib.bench.BenchRunner \n    -Dexec.classpathScope=test -Dexec.args=CoreBenchmarks
```

They were not run for this release; their numbers are not quoted.

`scripts/run-benchmarks.sh` runs the four programs three times with these flags and prints the min / median / max table (`RUNS=N` changes the run count; a benchmark name runs one program).

The `Benchmarks (Linux)` workflow runs the same four programs on a GitHub-hosted runner. Shared runners have noisy neighbours, so treat those numbers as a smoke test.

## Updating this page

Re-run all four benchmarks three times on an idle machine before each release, and replace the tables, the environment block and the commit hash. Do not copy a number from this page into another document; link here instead.
