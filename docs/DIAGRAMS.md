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

## 7. The equities participant stack — L3 feed in, routed orders out

The consumer's side of an exchange: rebuild the venue's book from its raw
event stream, know exactly where your own order queues, read the pressure,
route. Every stage is hot-lane (zero allocation, proven by test).

```mermaid
flowchart TD
    WIRE["ITCH-style wire bytes<br/>A/F add · E execute · X cancel ·<br/>D delete · U replace · P trade"] --> CODEC["ItchCodec.View<br/>flyweight: no parse step,<br/>packed-long symbols, tick ints"]
    CODEC --> L3["L3BookBuilder<br/>tick ladder + bitmaps + pooled nodes<br/>(BookPrimitives, shared with HftOrderBook)"]
    L3 -->|"per-venue top of book"| NBBO["Nbbo<br/>inside price/size + venue bitmask,<br/>fires only on inside changes"]
    L3 -->|"track(myRef)"| QPOS["sharesAhead(ref)<br/>exact queue position:<br/>1 walk to init, O(1)/event"]
    QPOS --> QM["QueueModel<br/>position → fill probability"]
    NBBO --> SIG["SignalEngine / FlowSignals<br/>OFI · queue &amp; trade imbalance ·<br/>vol · liquidity · momentum · alpha"]
    NBBO --> LULD["CircuitBreakers.Luld<br/>bands → limit state →<br/>5-min pause (pollable)"]
    SIG & QM --> DECIDE{"strategy decision"}
    LULD -.->|"PAUSED: stand down"| DECIDE
    DECIDE --> SOR["HftSor<br/>all-in price sweep, fees in ticks,<br/>zero allocation"]
    SOR --> THR["OrderThrottle<br/>token bucket vs venue<br/>message-rate limits"]
    THR --> GATE7["HftRiskGate → OrderRingBuffer<br/>(the shared fast lane out)"]
```

Own-order queue tracking rests on two price-time facts: executions always
consume the queue head, and a cancel is ahead of you iff it entered the
queue before you — which is what makes O(1) maintenance sound.

---

## 8. The FX participant stack — quotes, last look, and routing around it

FX is the mirror image: no tape, no central book. Liquidity is private
quotes subject to last look, so the stack measures LP *behavior* and routes
on expected all-in cost, not displayed price.

```mermaid
flowchart TD
    MD["FIX 35=W / 35=X<br/>(bank streams, ECNs)"] --> MDV["FixMarketDataView<br/>garbage-free flyweight,<br/>scaled-long prices,<br/>entry position = tier"]
    MDV --> TIER["FxTierBook<br/>per-LP size-tier ladders:<br/>sweep cost · full-amount price"]
    MDV --> AGG8["AggregatedBook<br/>composite BBO + mid"]

    subgraph LOOP["The last-look loop (per clip)"]
        ROUTE["LpRouter.route(buy, size)<br/>expected = quoted +<br/>rejectRate × adverse markout<br/>(+ reject-rate veto)"]
        REQ["deal request → LP"]
        HOLD{"LP holds,<br/>price-checks"}
        FILL["fill"]
        REJ["reject"]
    end

    TIER --> ROUTE --> REQ --> HOLD
    HOLD -->|within tolerance| FILL
    HOLD -->|moved| REJ
    FILL -->|"onFill: eff. spread, hold"| CARD["LpScorecard<br/>EWMA reject rate · hold time ·<br/>post-reject markout (4-slot ring)"]
    REJ -->|"onReject: starts markout clock"| CARD
    AGG8 -->|"onMid: matures markouts"| CARD
    CARD -->|"behavior feeds the next decision"| ROUTE

    AGG8 --> SYN["SyntheticCross<br/>direct vs legs, both spreads<br/>composed, unpriced never wins"]
    SYN -.->|"cheaper route"| ROUTE
    GATE8["LastLookGate (maker side)<br/>SYMMETRIC per FX Global Code:<br/>rejects both directions 50/50"] -.->|"the mechanism being measured"| HOLD
```

The feedback loop is the point: an LP's tight display means nothing if its
rejects cluster on the flow that was about to pay you — the scorecard
measures exactly that, and the router prices it in.

---

## 9. Scaling out — shared-nothing shards under one risk umbrella

Throughput scales by running independent engine stacks; safety stays global
through a slow observer that only ever asks the hot path to read one
boolean.

```mermaid
flowchart TD
    PROD["market data producer(s)"] --> S1 & S2 & SN

    subgraph S1["Shard 0 (own thread pair)"]
        B1["bus"] --> G1["gate"] --> W1["gateway → venue"]
    end
    subgraph S2["Shard 1"]
        B2["bus"] --> G2["gate"] --> W2["gateway → venue"]
    end
    subgraph SN["Shard N-1"]
        BN["bus"] --> GN["gate"] --> WN["gateway → venue"]
    end

    ENG["ShardedTradingEngine<br/>symbol → shard routing (frozen at start);<br/>a symbol may live on several shards<br/>(cross co-location)"] --- S1 & S2 & SN

    AGGR["GlobalRiskAggregator (monitor thread, ~ms)<br/>Σ |position| × refPrice across ALL gates"] -->|"breach: kill(true) on every gate<br/>resume below cap × resumeFraction"| G1 & G2 & GN
    G1 & G2 & GN -.->|"hot path reads ONE<br/>acquire-loaded boolean"| KILL(["kill switch"])
```

Measured on a 12-core desktop, 300 symbols quoted two-sided: 1 shard =
4.3M ticks/s → 2 shards = 6.2M (+46%) → 4 shards plateau at 6.7M (core
oversubscription + single producer, not contention). War story in
[ULTRA_LOW_LATENCY.md](ULTRA_LOW_LATENCY.md): one shared synchronized
counter across shards made sharding measure as a *slowdown*.

---

## 10. Choosing an execution algorithm — the decision map

The parent-order question is "what am I being measured against?" — the
benchmark picks the algorithm, and TCA closes the loop.

```mermaid
flowchart TD
    PARENT(["parent order"]) --> Q{"benchmark?"}
    Q -->|"one dynamic executor<br/>for all 7 benchmarks"| BE["BenchmarkExecutor<br/>VWAP·TWAP·Arrival·IS·Close·Open·POV<br/>completion curve + live shaping;<br/>re-decides every interval"]
    Q -.->|"or a fixed slice list<br/>(precompute, no live state)"| STATIC["TwapScheduler · VwapScheduler ·<br/>ImplementationShortfallScheduler ·<br/>PovTracker · WmrFixingScheduler"]

    subgraph MODELS["live inputs → MarketState (normalized)"]
        VC["VolumeCurve<br/>live VWAP curve"]
        SF["SpreadForecaster<br/>spread (cost)"]
        SE["SignalEngine<br/>vol · alpha"]
        HLD["HiddenLiquidityDetector<br/>true depth"]
        OAL["OnlineAlphaLearner<br/>learned alpha (IC-gated)"]
        KL["KylesLambda<br/>learned impact (bps)"]
    end
    DTP["DayTypeProfiles<br/>expiry · half day · fixing day"] -.->|"selects today's curves"| MODELS
    MODELS --> BE

    BE & STATIC --> CHILD["child orders"]
    CHILD --> WHERE{"venue choice"}
    WHERE -->|"full checklist<br/>(lit + dark, learned)"| ASOR["AdaptiveSor + VenueScorecard<br/>expected cost: fill rate ·<br/>latency · hidden liquidity"]
    WHERE -->|research lane| SOR10["SmartOrderRouter<br/>(readable, dark-first option)"]
    WHERE -->|tick path| HSOR["HftSor (zero-alloc)"]
    WHERE -->|FX| LPR["LpRouter<br/>(last-look-aware, full-amount)"]
    ASOR & SOR10 & HSOR & LPR --> FILLS["fills"]
    FILLS -->|"onFill: schedule self-corrects"| BE
    FILLS --> TCA["TransactionCostAnalyzer<br/>slippage vs arrival/VWAP ·<br/>venue quality · markouts"]
    TCA -.->|"recalibrate impact params,<br/>scorecards, participation"| Q
```

The two lanes coexist by design: **`BenchmarkExecutor`** when you re-decide
on live state (the usual case), the **static schedulers** when you want a
fixed slice list computed once up front.

---

## 11. Portfolio-level execution — one basket, one schedule

A two-sided transition run as N independent algos carries a risk none of
them can see: the filled legs drift apart and the basket holds an
unintended net market bet mid-flight. `PortfolioExecutor` layers the two
basket-level rules over untouched per-symbol executors — and both rules
only ever *reduce* a child's own due, so per-symbol benchmark integrity
holds by construction.

```mermaid
flowchart TD
    BASKET(["transition basket<br/>sell the old book · buy the new one"]) --> PE["PortfolioExecutor.decide<br/>(every interval, zero-alloc)"]

    subgraph CHILDREN["per-symbol children — own benchmark, own curve"]
        C1["BenchmarkExecutor<br/>SELL 80k · VWAP"]
        C2["BenchmarkExecutor<br/>BUY 60k · VWAP"]
        C3["BenchmarkExecutor<br/>BUY 40k · IS"]
    end
    PE -->|"1 — each child's own dueQuantity"| CHILDREN
    CHILDREN --> OV1["2 — leg-balance band:<br/>projected net notional inside maxNet?<br/>ahead leg throttles, lagging leg never pushed"]
    OV1 --> OV2["3 — interval budget:<br/>total notional over the cap?<br/>capacity goes risk-weighted:<br/>(1 + vol regime) × due notional — or with<br/>useRiskModel, (1 + marginal basket risk)<br/>from the streaming EwmaCovariance"]
    OV2 --> OV3["4 — band re-checked<br/>(asymmetric risk cuts can re-tilt the legs)"]
    OV3 --> ROUTE["dues → venue choice<br/>AdaptiveSor (equities) · LpRouter (FX)"]
    ROUTE --> FILLS["fills"]
    FILLS -->|"PortfolioExecutor.onFill:<br/>child schedule + net ledger together"| PE
```

The fills edge is the one discipline to keep: report fills through
`PortfolioExecutor.onFill` only — going straight to a child advances its
schedule but blinds the net-exposure ledger the band reads.

---

## 12. Surviving the overnight — the checkpoint lifecycle

Everything the models learn lives in memory; `persist.Checkpoint` is how
it outlives the session. Two properties carry the design: the save is
atomic (a crash mid-save cannot corrupt yesterday's file), and the restore
is honest about time (learned state returns, intraday state deliberately
does not).

```mermaid
flowchart LR
    subgraph SESSION["trading session (in memory)"]
        MODELS["learned state<br/>VolumeCurve · VolatilityCurve · SpreadForecaster<br/>OnlineAlphaLearner (weights + IC evidence)<br/>AlphaEnsemble · LeadLagEstimator · EwmaCovariance<br/>KylesLambda · ClosingAuctionModel<br/>VenueScorecard · LpScorecard · RfqDealerScorecard"]
    end

    MODELS -->|"end of day:<br/>writeState per model"| W["Checkpoint.Writer<br/>named sections, buffered in memory;<br/>a throwing section commits NOTHING"]
    W -->|"temp file, then<br/>atomic rename"| FILE[("eod.qflc<br/>one binary file,<br/>versioned sections")]
    FILE -->|"session start:<br/>readState per model"| R["Checkpoint.Reader<br/>missing section = cold start ·<br/>config mismatch / format drift = throws ·<br/>unknown sections skipped (forward compat)"]
    R -->|"learned state restored,<br/>intraday state reset"| MODELS
```

Deliberately NOT persisted: `HiddenLiquidityDetector` (its state is keyed
by price level, and overnight the ladder moves — restoring it would pin
yesterday's icebergs onto today's unrelated prices).

---

## 13. The central risk book — one netted view, four decisions

Every product decomposes into ONE factor space at booking (currency-level
FX legs, per-symbol equity deltas, gamma/vega per underlying), and every
downstream decision runs on the netted residual. The commercial loop:
capture spread by internalizing, spend as little of it as possible on
hedging, and answer one question at the close.

```mermaid
flowchart TB
    FLOWS["client flows, all desks<br/>cash equity · equity options · FX spot<br/>FX swaps · NDFs · FX options"]
    FLOWS -->|"quote with inventory skew<br/>SkewedQuoter"| DECIDE{"InternalizationEngine<br/>risk-reducing? warehouse room?"}
    DECIDE -->|"internalize<br/>(+ price improvement)"| BOOK["CentralRiskBook<br/>ONE factor space:<br/>EQ:sym · CCY:ccy · FXPOINTS:pair<br/>gamma · vega · pending fixings"]
    DECIDE -->|"route out"| STREET["the street"]
    BOOK -->|"band breach?<br/>hedge the EXCESS only"| HEDGE["CrbAutoHedger →<br/>HedgeOptimizer<br/>(min variance + L1 cost;<br/>uneconomic hedges = exactly 0)<br/>over CrbHedgeUniverse"]
    HEDGE --> ROUTE["CrbRouter:<br/>1. internal cross (free, silent)<br/>2. dark pools by adverse-selection bps<br/>3. lit (spread + impact, but fills)"]
    ROUTE --> STREET
    BOOK -->|"report(cov, 0.99)"| RISK["VarEngine VaR/ES +<br/>diversification benefit:<br/>standalone desk VaRs − netted VaR"]
    BOOK & HEDGE & ROUTE -->|"realized economics"| PNL["CrbPnlLedger:<br/>spread captured − improvement paid<br/>− hedge cost − routing cost<br/>= did netting pay for its risk management?"]
    BOOK <-->|"writeState / readState<br/>overnight"| CKPT[("Checkpoint")]
```

The whole loop at realistic sizes and costs is
`crb/CrbRealWorldScenarioTest` (quiet day, one-way institutional day,
COVID-template stress day, NDF fixing day); recipe 14 is the runnable
version and [CENTRAL_RISK_BOOK.md](CENTRAL_RISK_BOOK.md) the guided tour.

---

## 14. The market-risk workflow — data to Basel, fourteen steps

The map `docs/MARKET_RISK.md` maintains, as a pipeline. Every box is
implemented and tested; the regulatory boxes are styled after BCBS, not
certified — stated, not hidden.

```mermaid
flowchart LR
    subgraph INPUTS["1-3 data → factors"]
        DATA["collection + cleaning<br/>CorporateActions · SeriesAligner<br/>YieldCurve bootstrap ·<br/>LiquidityMeasures (bars only)"]
        DATA --> FACTORS["risk factors<br/>Pca (how many are REAL?)<br/>crb.CentralRiskBook<br/>(the worked decomposition)"]
    end
    subgraph MODELS["4-7 models"]
        PRICE["pricing<br/>BlackScholes · Black76 · Heston<br/>Vasicek/CIR/Hull-White · trees · MC"]
        GREEKS["sensitivities<br/>delta/gamma/vega/theta/rho<br/>vanna · volga · cross-gamma<br/>DV01 · key-rate durations"]
        VOL["volatility<br/>EWMA · GARCH · GJR · EGARCH<br/>HAR-RV · bipower · Heston"]
        DEP["dependence<br/>Spearman/Kendall · PCA<br/>Gaussian + t copulas"]
        PRICE --> GREEKS
    end
    subgraph RISK["8-11 risk numbers"]
        VAR["VaR/ES, five flavors<br/>delta-normal · MC · delta-gamma<br/>historical · FULL REVALUATION"]
        TAIL["tail: EVT/POT/GPD<br/>(refuses infinite means)"]
        STRESS["stress + REVERSE stress<br/>('what breaks us, at how many σ?')"]
        BT["backtests: Kupiec ·<br/>Christoffersen · traffic light"]
        VAR --> TAIL
        VAR --> STRESS
        VAR --> BT
    end
    subgraph REG["12-14 the wrap"]
        FRTB["FRTB: ES 97.5 liquidity cascade<br/>+ PLAT zones (MAR32/33-styled)"]
        PROD["production: dashboards ·<br/>Checkpoint · reports"]
        FRTB --> PROD
    end
    FACTORS --> PRICE
    FACTORS --> VOL
    FACTORS --> DEP
    GREEKS & VOL & DEP --> VAR
    TAIL & STRESS & BT --> FRTB
```

---

## 15. How an order reaches the exchange — and how the market comes back

Two lanes meet at the strategy: outbound, a decision survives the risk
gate, becomes a FIX message, and rides a sequenced session to the venue's
book; inbound, the venue's raw feed is rebuilt into signals that shape the
next decision. The `ExecutionReport` closes the loop through position and
P&L.

```mermaid
flowchart LR
    subgraph OUTLANE["outbound — the order's journey"]
        STRAT["strategy decision<br/>HftQuoter · BenchmarkExecutor ·<br/>your logic"]
        GATE15["HftRiskGate<br/>qty · notional · position ·<br/>collar · halt (~3 ns)"]
        ENC["FIX encode<br/>NewOrderSingle 35=D<br/>(FixSession.sendNewOrderSingle)"]
        SESS["FixSession<br/>seqnums · heartbeats · TestRequest ·<br/>ResendRequest recovery · session store"]
    end
    subgraph VENUE15["the venue"]
        VBOOK["venue book<br/>(HftOrderBook plays this<br/>role in the tests)"]
        ER["ExecutionReport 35=8<br/>ack · fill · cancel · reject"]
    end
    subgraph INLANE["inbound — the market's journey"]
        WIRE15["ITCH / SBE wire bytes<br/>(ItchCodec · sbe flyweights)"]
        L315["L3BookBuilder → Nbbo<br/>book rebuild + own-queue position"]
        SIGS15["SignalEngine / FlowSignals<br/>OFI · imbalance · vol · alpha"]
    end
    PNL["position & P&L update<br/>ledger · TCA markouts"]

    STRAT --> GATE15 --> ENC --> SESS
    SESS -->|"TCP, MsgSeqNum n"| VBOOK
    VBOOK --> ER
    ER -->|"onExecutionReport"| PNL
    ER -.->|"gap? ResendRequest(2),<br/>PossDup replay heals it"| SESS
    WIRE15 --> L315 --> SIGS15
    SIGS15 --> STRAT
    PNL -.->|"inventory feeds the<br/>next quote's skew"| STRAT
```

The sequenced session is the part people underestimate: the venue's book
only ever sees messages in order, so every gap is either healed by a
resend (application messages replayed with PossDup, admin runs coalesced
into GapFill) or the session refuses to continue. Recipe 21 is the
runnable version; diagram 2 shows the nanosecond budget of the left lane.

---

## 16. The rates stack — quotes to simulated curves

One curve object underneath everything: quotes bootstrap into zeros, the
zeros price cash flows, node bumps locate the risk, and the short-rate
models animate the same curve through time.

```mermaid
flowchart TD
    QUOTES16["market quotes<br/>deposits (already zeros) ·<br/>annual par swap rates"]
    BOOT16["bootstrap<br/>YieldCurve.bootstrapAnnualParSwaps<br/>DF_n = (1 − s_n·A_{n−1}) / (1 + s_n)<br/>· YieldCurve.ofZeroRates"]
    CURVE16["YieldCurve<br/>zeroRate (linear, flat extrap) ·<br/>discountFactor · forwardRate"]
    BOND16["BondPricer<br/>priceFromCurve / priceFromYield ·<br/>YTM (bisection) · Macaulay/modified<br/>duration · convexity · DV01<br/>+ date-true dirty/clean price<br/>(DayCount · BusinessCalendar rolls)"]
    KRD16["KeyRateDurations<br/>bump ONE node ±1bp, reprice:<br/>keyRateDv01s ladder ·<br/>Σ KRD = parallelDv01 (pinned by test)"]
    HEDGE16["hedge ladder<br/>per-bucket pay-fixed swap notionals<br/>(recipe 18)"]
    SRM16["ShortRateModels<br/>Vasicek · CIR (Feller check) ·<br/>Hull-White θ(t) fitted TO the curve"]
    SIM16["simulation<br/>vasicekStep / cirStep paths →<br/>scenario repricing, rate VaR inputs"]

    QUOTES16 --> BOOT16 --> CURVE16
    CURVE16 --> BOND16 --> KRD16 --> HEDGE16
    CURVE16 --> SRM16 --> SIM16
```

The stack splits statics from dynamics. Everything down the left column
is today's curve interrogated harder and harder — price, then slope
(DV01), then slope *per node* (the KRD ladder that recipe 18 turns into
hedge notionals). The right branch is the same curve given a stochastic
engine: Vasicek and CIR bring their own equilibrium, Hull-White is
calibrated so today's curve is reproduced exactly — which is why its
simulations are the ones you can use for curve-consistent scenario P&L.

---

## 17. Portfolio construction — one input set, three optimizers

All three consume the same expected returns and covariance; they disagree
about how much the return estimates deserve to be trusted, and the weight
vectors show it.

```mermaid
flowchart TD
    INPUTS17["expected returns μ + covariance Σ<br/>same periodicity (both annualized<br/>or both daily) — e.g. EwmaCovariance"]
    subgraph OPT17["three philosophies about trusting μ"]
        MS17["PortfolioOptimizer<br/>maxSharpe / minVolatility /<br/>efficientFrontier<br/>trusts μ FULLY — concentrates"]
        RP17["RiskParityOptimizer<br/>equalRiskContribution<br/>ignores μ — every asset the same<br/>share of portfolio variance"]
        BL17["BlackLitterman<br/>impliedEquilibriumReturns (market prior)<br/>+ posteriorReturns (views, confidences)<br/>→ PortfolioOptimizer on the posterior"]
    end
    W17["weights<br/>(Allocation: w · return · vol · Sharpe)"]
    BT17["PortfolioBacktester<br/>survivorship-aware, TradeCostModel,<br/>rebalance schedule"]
    EX17["PortfolioExecutor<br/>the transition INTO the new weights:<br/>leg-balance band + risk-weighted budget"]

    INPUTS17 --> MS17
    INPUTS17 --> RP17
    INPUTS17 --> BL17
    MS17 --> W17
    RP17 --> W17
    BL17 --> W17
    W17 -->|"did the weights survive<br/>history honestly?"| BT17
    W17 -->|"trade current book → target<br/>as ONE basket schedule"| EX17
```

The fork at the bottom is the point of the diagram: a weight vector is a
research artifact until it survives a survivorship-honest backtest
(diagram 3's pipeline) *and* can be reached from the current book without
the transition itself destroying the alpha — which is what
`PortfolioExecutor` (diagram 11) exists to protect. Recipe 19 prints the
three weight vectors side by side.

---

## 18. The overfitting defense stack — from grid winner to deploy-or-reject

A parameter grid produces N backtests and reports the maximum — which is
a selection effect, not evidence. Four defenses interrogate the same
winner from different angles before any capital moves.

```mermaid
flowchart TD
    GRID18["one strategy grid<br/>ParameterGrid × StrategyFactory<br/>GridSearchOptimizer.search: every<br/>combination backtested and ranked"]
    WINNER18["in-sample winner<br/>the best of N Sharpes —<br/>a MAXIMUM of N draws,<br/>not an expectation"]
    subgraph DEFENSES["the four defenses (recipe 20)"]
        WF18["WalkForwardAnalyzer<br/>re-optimize per train window,<br/>score unseen test bars, stitched<br/>OOS equity → efficiency = OOS/IS"]
        PKF18["PurgedKFold<br/>label-horizon purge + embargo:<br/>the ML-fold leak fix"]
        CSCV18["OverfitProbability.cscvSharpe<br/>C(S,S/2) symmetric IS/OOS splits:<br/>PBO = P(IS winner below<br/>OOS median)"]
        DSR18["GridSearchOptimizer<br/>.deflatedSharpeOfWinner<br/>winner's Sharpe vs the best of<br/>N zero-skill trials"]
    end
    DECIDE18{"deploy or reject?"}
    DEPLOY18(["deploy — sized by the<br/>OOS numbers, not the IS ones"])
    REJECT18(["reject: the grid found<br/>noise, not signal"])

    GRID18 --> WINNER18
    WINNER18 --> WF18
    WINNER18 --> PKF18
    WINNER18 --> CSCV18
    WINNER18 --> DSR18
    WF18 -->|"efficiency → 1 robust,<br/>→ 0 curve-fit"| DECIDE18
    PKF18 -->|"fold scores hold<br/>without the leak"| DECIDE18
    CSCV18 -->|"PBO < 0.1 real ·<br/>PBO ≥ 0.5 noise-mining"| DECIDE18
    DSR18 -->|"< ~0.95: indistinguishable<br/>from the luckiest of N"| DECIDE18
    DECIDE18 -->|"all four pass"| DEPLOY18
    DECIDE18 -->|"any one fails"| REJECT18
```

Each defense catches a different lie: walk-forward catches parameters
that only fit the past arrangement of regimes; the purged K-fold catches
label leakage that ordinary cross-validation invites; CSCV catches a
broken *selection process* (the winner keeps flipping under resampling);
the deflated Sharpe catches the multiple-testing haircut everyone forgets
to apply. Passing one is easy. Passing all four is what "not overfit"
means here — and PBO ≥ 0.5 is an unconditional stop.

---

## Where to go next

- [LEARN.md](LEARN.md) — the from-zero tutorial: every concept in these diagrams, explained for beginners
- [ARCHITECTURE.md](ARCHITECTURE.md) — the package → classes → tests map and design invariants
- [ULTRA_LOW_LATENCY.md](ULTRA_LOW_LATENCY.md) — the four-tier latency stack, honestly bounded
- [COOKBOOK.md](COOKBOOK.md) — twenty-two runnable recipes across these flows
- `README.md` — capability tour with runnable examples and all measured numbers
