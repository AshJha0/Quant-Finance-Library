# Architecture & Flow Diagrams

Visual companions to [ARCHITECTURE.md](ARCHITECTURE.md). All diagrams are
Mermaid — GitHub renders them inline; measured numbers come from the
committed, re-runnable benchmarks.

---

## 1. The big picture — two lanes, one library

The design splits everything into a **hot lane** (zero-allocation,
single-producer/single-consumer, nanosecond-budgeted) and a **research lane**
(clarity first, allocation allowed). Knowing which lane a class is in tells
you what to expect from it.

```mermaid
flowchart LR
    subgraph IN["Data in"]
        CSV["CSV / HTTP bars<br/>(data)"]
        WS["WebSocket live<br/>(feed)"]
        SBE_IN["Binary SBE stream<br/>(sbe)"]
        QFLT["QFLT tick files<br/>(data)"]
        UNIV["Universe CSV<br/>(data.UniverseCsvLoader)"]
    end

    subgraph HOT["HOT LANE — zero allocation, measured in ns"]
        BUS["HftMarketDataBus<br/>SPSC ring, dense ids"]
        STRAT["StreamingIndicators /<br/>HftQuoter / AutoHedger /<br/>CrossRateEngine / AggregatedBook"]
        GATE["HftRiskGate<br/>~3 ns/check"]
        ORING["OrderRingBuffer<br/>SPSC"]
        VENUE_A["Venue adapters:<br/>sbe.BinaryOrderPublisher /<br/>fix.FixSession"]
    end

    subgraph RESEARCH["RESEARCH LANE — clarity first"]
        ALPHA["alpha: factors → IC →<br/>validation → construction"]
        BT["backtest: bar / execution-aware /<br/>tick / portfolio+survivorship"]
        PRICE["pricing • fx • rates •<br/>volatility • hedging"]
        RISK["risk • optimization •<br/>ml • screener"]
        REPORT["report: HTML/PDF/XLSX/CSV<br/>+ SVG charts • cli • dashboard"]
    end

    subgraph VENUEBOX["Venue side (both lanes)"]
        OB["OrderBook<br/>(readable reference)"]
        HOB["HftOrderBook<br/>204 ns/op, 10M+ fills/s"]
    end

    WS --> BUS
    SBE_IN --> BUS
    QFLT -->|replay| BUS
    BUS --> STRAT --> GATE --> ORING --> VENUE_A
    CSV --> ALPHA & BT & PRICE
    UNIV --> BT & ALPHA
    BUS -.->|TickCapture| QFLT
    ALPHA --> BT --> REPORT
    PRICE --> RISK --> REPORT
    VENUE_A -.->|orders| HOB
    OB <-->|equivalence test| HOB
```

---

## 2. The hot path, end to end — with measured latencies

Every arrow is on the measured path; the numbers are medians from the
benchmark family (`HftLatencyBenchmark`, `HftOrderBenchmark`,
`HftQuoterBenchmark`, `HftBookBenchmark`) on a stock Windows desktop.

```mermaid
flowchart LR
    TICK(["market tick"]) -->|"publish()"| RING["TickRingBuffer<br/>padded seqs,<br/>acquire/release"]
    RING -->|"204 ns p50<br/>publish→strategy"| LSTN["TickListener<br/>(bus consumer thread)"]
    LSTN --> QUOTER["HftQuoter<br/>mid + inventory skew<br/>+ clamped grid snap"]
    LSTN --> STRAT2["strategy<br/>(streaming EMA/RSI...)"]
    QUOTER & STRAT2 --> GATE["HftRiskGate ~3 ns<br/>qty · notional · position ·<br/>collar · halt"]
    GATE -->|accept| ORING["OrderRingBuffer"]
    GATE -->|"reject = int code"| X(["counted, not thrown"])
    ORING -->|"venue thread"| OUT["OrderListener"]
    OUT --> SBEOUT["BinaryOrderPublisher<br/>(44-byte flyweight)"]
    OUT --> FIXOUT["FixSession<br/>(FIX 4.4 + resend recovery)"]

    TICK -.->|"504 ns p50 tick→order<br/>592 ns p50 tick→two-sided quote"| OUT
```

Key disciplines, in one line each:

| Discipline | Where | Proof |
|---|---|---|
| Zero allocation steady-state | rings, gate, quoter, hedger, book, codecs | per-thread allocation-counter tests |
| No locks/CAS on the hot path | SPSC rings, acquire/release only | FIFO stress tests |
| No String/boxing on the hot path | dense int symbol ids everywhere | design + tests |
| Tails attributed, not guessed | `HiccupMonitor` in every benchmark | printed with every run |
| Zero GC, literally | whole sessions under Epsilon GC | benchmark runs committed |

---

## 3. The alpha research pipeline

Scores flow as `double[]` aligned to a frozen symbol panel; `NaN` = "not in
the cross-section" at every stage. Attaching a `PointInTimeUniverse` makes
the *whole* pipeline survivorship-honest.

```mermaid
flowchart TD
    DATA["aligned BarSeries panel<br/>+ optional Fundamentals"] --> CTX["AlphaContext<br/>frozen sorted symbol axis"]
    PIT["PointInTimeUniverse<br/>(membership + delistings + mergers)"] -.->|"withUniverse():<br/>dead names → NaN per bar"| CTX
    CTX --> F["Factors (×9)<br/>trend • reversal • value •<br/>quality • low-vol"]
    F --> EVAL["SignalEvaluator<br/>rank IC · IR · t-stat ·<br/>hit rate · turnover"]
    EVAL --> VAL["AlphaValidation<br/>walk-forward (leak-proof, cached grid) ·<br/>blocked k-fold · permutation MC ·<br/>parameter sensitivity"]
    VAL -->|"signal survives"| CONS["PortfolioConstruction<br/>z-score + caps → inverse-vol budget →<br/>sector demean → beta projection"]
    VAL -->|"signal dies"| DEAD(["cheap death:<br/>no backtest wasted"])
    CONS --> ABT["AlphaBacktester<br/>commission + spread + slippage +<br/>√-impact; gross vs net decomposition"]
    CONS --> PBT["PortfolioBacktester + TradeCostModel<br/>lifecycle events AND institutional costs<br/>in ONE run"]
    ABT & PBT --> REP["AlphaReport<br/>decay half-life · OLS attribution ·<br/>drawdowns · rolling Sharpe"]
```

---

## 4. Survivorship-aware backtest — per-bar event ordering

The order of operations inside each bar is a correctness contract (a
dividend on a delisting's ex-date still pays the holder of record; a
merger's shares flow into a same-bar-dying acquirer at *its* terms):

```mermaid
sequenceDiagram
    participant Bar as bar i (timestamp t)
    participant Div as 1. Dividends
    participant Mrg as 2. Mergers
    participant Del as 3. Delistings
    participant Drop as 4. Index drops
    participant Reb as 5. Rebalance

    Bar->>Div: ex-dates ≤ t: position × amount<br/>(holder of record at prior close; shorts pay)
    Bar->>Mrg: target → cash + acquirer shares<br/>(before delistings, so conversions land first)
    Bar->>Del: position × lastClose × (1 + delistingReturn)<br/>(−100% = wiped out; Shumway −30% default)
    Bar->>Drop: still listed but out of the index →<br/>forced sale at this bar's close (fee charged)
    Bar->>Reb: strategy weights (non-members capped at 0,<br/>dead names untradeable) via TradeCostModel
    Note over Div,Reb: symbols processed in sorted order —<br/>same inputs, same result, any JVM
```

---

## 5. Inside the venue-grade matching engine (`HftOrderBook`)

Everything is a primitive array; the diagram shows what happens to a
crossing buy limit.

```mermaid
flowchart TD
    SUB["submitLimit(BUY, tick, qty)"] --> VALID{"qty > 0?<br/>tick in band?"}
    VALID -->|no| REJ(["negative reject code<br/>(never an exception)"])
    VALID -->|yes| MATCH["match loop:<br/>bestAskIdx ≤ limit?"]

    subgraph STATE["Book state (all preallocated)"]
        LADDER["per-side tick ladder<br/>head/tail/qty per level"]
        BITS["occupancy bitmaps<br/>best advance = word scan"]
        POOL["intrusive node pool<br/>id · qty · prev · next"]
        MAP["open-addressing id→node map<br/>backward-shift deletion"]
    end

    MATCH -->|"FIFO fills at level head"| SINK["TradeSink<br/>(maker, taker, tick, qty)"]
    MATCH -->|"level empties"| BITS
    MATCH -->|"maker filled: recycle node"| POOL
    MATCH -->|"remainder > 0"| REST["rest at limit level tail<br/>+ index in id map"]
    REST --> LADDER & MAP
    CXL["cancel(id)"] --> MAP -->|"unlink + recycle"| POOL

    STATE -.->|"equivalence test:<br/>identical random ops ⇒ identical books"| REFBOOK["reference OrderBook<br/>(the executable spec)"]
```

Measured: **204 ns/op p50** (70/20/10 add/cancel/aggress), **10M+
fills/sec**, zero allocation, full sessions under Epsilon GC.

---

## 6. FX instruments — how the pieces compose

Conventions flow downward; everything date-related delegates to ONE joint
calendar.

```mermaid
flowchart TD
    CAL["rates.BusinessCalendar<br/>(+ union of two centers)"] --> PAIR["fx.CurrencyPair<br/>pips · precision · T+1/T+2 ·<br/>tenor dates (mod-follow, end-end)"]
    PAIR --> SPC["SwapPointsCurve<br/>quoted points → outrights,<br/>broken dates, CIP carry"]
    SPC --> SWAP["FxSwap<br/>near/far legs, points MTM,<br/>settled legs → 0"]
    SPC --> NDF["Ndf<br/>local-calendar fixing lags,<br/>USD-settled difference"]
    PAIR --> VS["FxVolSurface<br/>ATM DNS + RR/BF → strikes<br/>(premium-adjusted solving)"]
    VS --> VV["pricing.VannaVolga<br/>smile-consistent, exact at pillars"]
    VS --> EXO["pricing: digitals ·<br/>one-touch/no-touch ·<br/>regular barriers (MC cross-checked)"]
    BUS2["HftMarketDataBus"] --> AGG["AggregatedBook<br/>multi-venue BBO, zero-alloc"]
    BUS2 --> XRATE["CrossRateEngine<br/>EURJPY = EURUSD × USDJPY, streaming"]
    NDF --> FIXR["FixingRisk<br/>σ²T/3 window tracking error"]
```

---

## Where to go next

- [ARCHITECTURE.md](ARCHITECTURE.md) — the package → classes → tests map and design invariants
- [ULTRA_LOW_LATENCY.md](ULTRA_LOW_LATENCY.md) — the four-tier latency stack, honestly bounded
- `README.md` — capability tour with runnable examples and all measured numbers
