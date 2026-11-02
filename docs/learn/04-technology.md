# Part II, The Technology (sections 12 to 20)

[Index: LEARN.md](../LEARN.md) | [Previous: Part I, The Finance: backtesting and the desk playbooks (sections 11, 11b)](03-backtesting-and-desk-playbooks.md) | [Next: Part III, Your learning path](05-learning-path.md)

---

## Part II — The Technology

### 12. The latency ladder, or: what a nanosecond buys

- 1 **ns**: light travels about 30 cm; a modern CPU core executes a few
  instructions.
- About 100 ns: a main-memory access (this is why cache locality matters).
- 1 **µs** (1,000 ns): the scale this library's tick-to-order path is
  designed for. Measured figures, with the machine and JVM they came
  from, are in [BENCHMARKS.md](../BENCHMARKS.md).
- Tens of µs: a short Java garbage-collection pause even with a
  low-pause collector such as ZGC; older collectors pause for
  *milliseconds*. Either is far longer than the whole hot path.
- About 1 **ms**: network round trip within a city; an OS deciding to
  run another thread for "a moment."

Two lessons fall out. First, at these scales *the enemy is anything
irregular*: a code path that's usually 500ns but occasionally 50µs is worse
than one that's always 800ns, which is why we obsess over **tail latency**
(p99, p99.9) rather than averages. Second, some slowness you cannot code
away (the OS preempting your thread, the GC) — you can only *avoid
triggering it*, measure it, and attribute it correctly.

*In this repo:* `docs/ULTRA_LOW_LATENCY.md` maps four tiers of latency work,
from pure-JDK (this library) down to kernel bypass and FPGAs, and says
where each tier's boundary lies.

### 13. Garbage collection, and why we allocate nothing

Java normally manages memory for you: you create objects, the **garbage
collector** (GC) later finds the dead ones and reclaims them — occasionally
pausing your program to do so. For a web app, a few milliseconds of pause
is invisible. For us it's catastrophe (see the ladder above).

The cleanest solution: **never give the GC anything to do**. If the hot
path allocates zero objects after startup, the GC never runs. So hot-path
code here pre-allocates everything up front (arrays, buffers, pools) and
then only *reuses*: no `new`, no autoboxing (`Long` vs `long` — the
capital-L one is an object!), no Strings, no iterators, no lambdas that
capture variables.

The claim is checked two ways. (1) Hot classes in the trading,
market-data, order-book and execution packages have tests that read the
JVM's per-thread allocation counter
(`ThreadMXBean.getThreadAllocatedBytes`) around a long run of operations
after warm-up and require exactly zero bytes (the smallest of a few
runs, so a stray JIT allocation does not fail the build). (2) The
benchmarks can run under **Epsilon GC** (`-XX:+UnlockExperimentalVMOptions
-XX:+UseEpsilonGC`), a JVM collector that *never collects* and stops the
JVM when memory runs out. A long session under Epsilon that does not run
out of memory shows the path never needed the collector.

*Try it:* search the tests for `getThreadAllocatedBytes`
(`execution/HftSorTest.java` is one). The pattern is about ten lines and
you will recognise it everywhere afterwards.

### 13b. Proving an allocation-free claim (3.1)

**The plain idea.** A hot-path method that allocates makes garbage, and garbage
eventually makes a collector pause at a time you did not choose. So the library's
javadoc says which methods allocate nothing. A sentence in a javadoc is only a
promise, though. From 3.1 each such sentence has a test that counts the bytes the
method allocates and fails if the count is not zero in steady state.

**The formal statement.** HotSpot keeps, for every thread, the number of heap bytes
that thread has allocated since it started. `com.sun.management.ThreadMXBean`
exposes it as `getCurrentThreadAllocatedBytes()`. For an operation `op`, the test:

1. calls `op` W = 50,000 times, so the JIT compiles it and escape analysis has run;
2. reads the counter, calls `op` N = 10,000 times, reads the counter again;
3. repeats step 2 up to five times and keeps the smallest difference B;
4. passes when B <= 256 bytes.

The tolerance is safe because the smallest Java object is 16 bytes (a 12-byte header
rounded up to 8-byte alignment). An `op` that allocates even once per call adds at
least 16 * N = 160,000 bytes to every batch, more than 600 times the tolerance. What
the tolerance absorbs is a one-off event in a batch: a deoptimisation that turns
scalar-replaced objects back into heap objects, or a late compilation. Because the
test counts bytes and not time, a slow or busy machine changes how long it takes but
not whether it passes, so it runs in the default build.

Two cautions. First, the test measures one thread. For a component that does its
work on its own thread (`BinaryMarketDataClient`'s reader, `HiccupMonitor`'s
sampler), the test finds that thread and reads its counter by id. Second, the test
proves "allocation-free after warm-up under C2". It says nothing about the first
calls, which run in the interpreter, where escape analysis does not exist.

**Worked example.** `HftOrderBook` buffers the trades of one incoming order before it
delivers them, so a sink never sees a half-updated book. In 3.0 the buffer started at
256 fills and doubled with `Arrays.copyOf` when a sweep needed more. A market order
that swept 1,000 resting orders therefore copied five arrays twice: four `long[]`
and one `int[]` of 512 entries (about 18 KB) when it passed 256 fills, and about 36 KB
more at 512. The javadoc still
said "zero-allocation".

3.1 sizes the buffer to `maxOrders` in the constructor. One incoming order cannot
produce more fills than there are resting orders. Every fill either empties a resting
order, whose slot is only reused after matching ends, or partly fills it, and then
the incoming order is used up. So `maxOrders` is an upper bound and the buffer never
has to grow. The test `hftOrderBookSweepOfOneThousandRestingOrders` rests 1,000 sell
orders on 50 levels, sends one market buy that fills all of them, and refills the
book on every call. Over 10,000 such sweeps it stays within the 256-byte tolerance, so no sweep allocates. The cost is 36 bytes of memory
per order of capacity, paid once.

The same exercise found one false claim. `feed.BinanceTradeParser` said "no allocation
beyond the result", but it builds substrings and lookup keys for every message. The
javadoc now says it is not garbage-free and should stay off the hot path, and its
test checks that it does allocate. A second claim held only by luck:
`ShortRateModels` said "allocation-free per call", but its Hull-White functions read a
`YieldCurve` whose `TreeMap<Double, Double>` lookup boxes the tenor. C2 removed the
boxes in a plain test run and kept them once JaCoCo instrumented the code, about 400
bytes per call. The claim now covers only the Vasicek and CIR functions. The lesson:
an allocation-free claim that depends on escape analysis is not a claim, and the
coverage build is a useful second JIT environment to test it in.

Recipe: COOKBOOK 450 (running the tests, testing your own code) and 451 (sizing the
book).

### 14. Data structures for speed

**Arrays of primitives beat objects.** A `double[]` is one contiguous block
of memory the CPU prefetches beautifully. An array of `Quote` objects is an
array of *pointers* to objects scattered across the heap — every access a
potential cache miss. So hot classes here store "an order" as *slot i
across six parallel arrays* (`nodeId[i]`, `nodeQty[i]`, `nodePrev[i]`…)
rather than as an Order object. Less pretty; measurably faster.

**Ring buffers** pass data between threads. A ring is a fixed-size array
with a write cursor and a read cursor that wrap around. The producer writes
and advances; the consumer reads and advances; power-of-two sizes make the
wrap a bit-mask. No locks, no allocation, and if the consumer falls behind
the ring fills up and the producer **drops and counts** — in trading you'd
rather skip a stale tick than block the feed (LMAX's "Disruptor" made this
pattern famous).

**SPSC and single-writer.** Every ring here is single-producer,
single-consumer, and every hot structure has exactly one writing thread.
The moment two threads write shared data you need coordination
(locks/atomics), and coordination costs. Architecture beats cleverness:
give each thread its own data.

**Open-addressing hash maps.** `HashMap<Long,Integer>` allocates a node per
entry and boxes every key. The primitive alternative: two flat arrays
(`long[] keys`, `int[] values`); hash the key, and if that slot is taken,
try the next one (**linear probing**). Deletion has a classic subtlety
(you can't just null a slot mid-probe-chain — this library uses
**backward-shift deletion**, worth reading with a coffee).

**Bitmaps.** "Which of my 65,536 price levels have orders?" One bit each =
1,024 longs. "Next occupied level above X" becomes a hardware instruction
(`Long.numberOfTrailingZeros`) instead of a loop.

**Flyweights.** To read a binary message, don't *parse* it into an object —
lay a "view" over the bytes and decode fields on demand, directly from the
buffer. Zero copies, zero allocation.

*In this library:* `marketdata/TickRingBuffer.java` (ring),
`orderbook/BookPrimitives.java` (bitmap scans + open addressing, shared by
the two hot-lane books, `HftOrderBook` and `L3BookBuilder`; the readable
`OrderBook` uses none of this so that it stays readable), `sbe/*Flyweight.java` and
`fix/FixMarketDataView.java` (flyweights), `orderbook/HftOrderBook.java`
(all of it at once).

### 15. Threads and the memory model, in plain words

Modern CPUs and compilers reorder and cache aggressively. If thread A
writes `position = 5` and thread B reads it, B may see the *old* value,
possibly indefinitely, unless the code establishes ordering. Java's rules for this
are the **Java Memory Model**.

The idiom used here is **release/acquire**: the writer publishes with a
*release* store ("everything I wrote before this is visible to whoever
acquires"), the reader reads with an *acquire* load. It's cheaper than
locks and expresses exactly what a ring buffer needs: *the cursor moved,
therefore the data behind it is ready.* In Java you spell this with
**VarHandles** (`setRelease`/`getAcquire`).

The docs record one such trade-off. The risk gate's checks were faster
single-threaded when the JIT could hoist reads out of loops, but that is
unsafe once other threads write fills concurrently. Per-element
acquire/release made the gate correct and somewhat slower; the library
kept the correct version and documented the cost (figures in
[BENCHMARKS.md](../BENCHMARKS.md)).

### 16. Measuring latency

Averages hide disasters: one 40 ms pause in a million 400 ns operations
barely moves the mean. Latency work uses **percentiles**: p50 (median),
p99, p99.9, max. Collecting them must itself be allocation-free, so
`util/LatencyRecorder.java` implements a log-scaled histogram (HdrHistogram
style) with 128 sub-buckets per power of two, exact below 128 ns, in
fixed memory. A reported percentile is the upper bound of its bucket, so
it never understates the true quantile.

Java has a warm-up quirk: code starts interpreted and gets compiled by the
**JIT** only after thousands of executions. Benchmarks must warm up first
or they measure the interpreter. And when a benchmark *does* show a
1.6 ms spike, whose fault is it: your code or the OS?
`util/HiccupMonitor.java` answers by running a thread that does
*nothing* but wait a fixed interval and record how late it wakes up. If
the do-nothing thread also stalled 1.6 ms, the *platform* owns that
tail, not your code. The default mode parks the thread, which on
Windows is bound to the system timer tick, so read Windows figures from
it as an upper bound; `HiccupMonitor.spinning(...)` busy-reads
`nanoTime` instead and measures only time the thread was not running.
The latency benchmarks run a hiccup monitor alongside.

*Try it:* `examples/HftLatencyBenchmark.java` and friends — each is a
single-file, readable benchmark you can run from the CLI.

### 17. Protocols: how an order reaches the exchange

**FIX** is finance's venerable text protocol: `35=D|55=EURUSD|54=1|38=1000000`
(tag=value, delimited by an invisible byte-1 character). Human-debuggable,
universally supported, verbose. Tag 35 is the message type, and three of
them carry most of trading: `35=D` (NewOrderSingle, "I want to trade"),
`35=8` (ExecutionReport, the venue's answer), `35=W`/`35=X` (market data
snapshot and incremental, the prices).

**The life of an order, end to end.** Follow one BUY from decision to
position:

1. *The strategy decides.* A signal crosses a threshold (§7); the
   pre-trade risk gate (§9) approves it.
2. *Encode.* The order becomes a `35=D` NewOrderSingle. Session lane:
   `FixSession.sendNewOrderSingle(clOrdId, symbol, side, qty,
   limitPrice, tif)` builds it with the String-based `FixMessage`,
   which is fine for its purpose. Hot lane: `fix/FixOrderEncoder.java` writes
   the same message with zero allocation: one reusable byte buffer
   (body first, then the `8=FIX.4.4|9=len|` prefix written *backwards*
   in front of it), digits rendered directly (no `Long.toString`),
   symbols pre-registered as bytes, the timestamp's date part cached
   until the UTC day rolls. Every encoded message is round-tripped
   through the validated parser in tests, so speed and correctness are
   checked together.
3. *The wire.* TCP to the venue's gateway, over a session (below).
4. *The venue matches.* Your order meets someone's resting order in
   the venue's book, with exactly the mechanics of §2, run by them
   instead of you.
5. *The answer comes back.* An ExecutionReport (`35=8`): `execType`
   `'0'` = accepted, `'F'` = fill, `'8'` = rejected. The session
   surfaces it via `Listener.onExecutionReport` as a typed
   `fix/ExecutionReport.java` (cumQty, leavesQty, avgPrice); the hot
   lane reads the same bytes through the `fix/FixExecReportView.java`
   flyweight without materializing anything.
6. *Position updated.* The **ClOrdID** you chose in step 2 is the
   thread that ties every report back to its order. Correlation is
   yours to manage, not the venue's.

**The session layer** is where the unglamorous reliability lives, and
`fix/FixSession.java` implements it. A session starts with a
**Logon handshake** and stays alive by **heartbeats**: send one after
each idle heartbeat interval, and if nothing arrives for 1.5 intervals,
send a **TestRequest** ("are you alive? prove it"). A peer that stays
silent to 2.5 intervals is declared dead and disconnected; only a
Heartbeat that echoes the TestReqID clears the request. That
escalation matters because TCP won't tell you about a dead peer for
minutes; a trading system needs to know in seconds, because *its
orders may still be alive at the venue*.

Every message carries a **sequence number**. When the receiver sees
seq 4,835 while expecting 4,833, it sends a **ResendRequest** for the
gap; the peer replays application messages flagged
`PossDupFlag(43)` (so a fill is never booked twice) and coalesces
admin messages into a `SequenceReset-GapFill(4)`, since nobody needs old
heartbeats back. `FileSessionStore` writes every sent message to a
CRC-32C journal that is forced to disk before the message reaches the
socket, so after a crash a ResendRequest replays the real orders rather
than covering them with a GapFill. A torn record at the end of the
journal is truncated on open. This dance is the part that pages you at 3am if done
wrong: a desync at reconnect means you don't know which of your
orders the venue thinks exist.

**The inbound side: market data.** Three dialects, one trade-off:

- **ITCH** (`marketdata/ItchCodec.java`): the L3 binary style, with
  fixed-offset messages, binary integers and millions of messages per
  second (§3).
- **FIX market data** (`fix/FixMarketDataView.java`): `35=W` full
  snapshots and `35=X` incremental updates, the common language of
  e-FX bank streams. It's still tag=value text, so this library reads
  it with a flyweight: one pass over the framed bytes into
  preallocated primitive arrays, entry order preserved (for tiered LP
  streams, position is the tier).
- **SBE-style binary** (`sbe/`: `QuoteFlyweight`, `TradeFlyweight`,
  `OrderFlyweight`, with `BinaryMarketDataClient` feeding the bus):
  every message starts with the standard 8-byte SBE header (block
  length, template id, schema id, version), fields sit at fixed offsets
  in little-endian order, and prices and sizes are int64 mantissas with
  an int8 exponent (`sbe/Decimal64.java`). Reading a price is a load,
  not a parse.

Why binary wins on the hot path: tag=value must scan every byte to
find delimiters, branch per tag, and convert ASCII digits to numbers;
a fixed-layout binary message needs none of that, since field offsets
are compile-time constants. Same information, a fraction of the work; and
since a flyweight makes even the text path allocation-free, the
difference binary buys is work, not garbage.

Everywhere on these paths, prices travel as **scaled longs**: 1.08505
becomes mantissa 108505 with 5 decimals, because `double` cannot
represent most decimal fractions exactly, and rounding errors in
prices are how you fail audits. Feed-to-order never touches a double.

*Real-life: the path after your strategy says BUY.* Signal update on
the incoming tick. Risk gate. FIX encode via `FixOrderEncoder`,
digit-writing into a buffer that already exists. Handoff to the venue
thread through a ring buffer (§14): one release store. Socket write:
the kernel takes over. Measured costs per stage are in
[BENCHMARKS.md](../BENCHMARKS.md). Notice what is
*absent* from that path: no String, no parsing, no allocation, no
lock, no syscall until the very last step. Every section of Part II
was about removing exactly those.

### 17b. FIX session schedules and tag 789

A FIX session is not one endless connection. It has a schedule, usually
daily (an FX venue might roll at 17:00 New York) or weekly (Sunday
evening to Friday evening). At each new session period both sides reset
their sequence numbers to 1 and discard the old message history; inside
a period a reconnect resumes where it left off and gaps are filled by
resend. Getting the period boundary wrong makes one side resend
yesterday's orders or reject today's Logon.

The second problem is the Logon handshake after a drop. Classic FIX
learns about a gap only when the next message arrives with a higher
sequence number than expected, then asks for a resend. Tag 789,
NextExpectedMsgSeqNum, puts that number in the Logon itself, so each
side knows immediately what the other has missed.

Formally, a period starts at each occurrence of the start time in the
schedule's time zone and ends at the next occurrence of the end time.
Equal start and end mean the session is always open and only rolls. The
store rolls (both counters to 1, history discarded) when the session is
created, logs on or ticks in a new period, and the period is saved with
the store so a process restarted the next morning resets instead of
resuming. With 789 on the Logon: if the peer's 789 is above our next
outgoing number, the peer claims to expect messages we never sent, which
is fatal, so we log out. If it is below, we replay the gap right after
the Logon exchange, as for a ResendRequest (FIX 4.4 errata on Logon tag
789; FIXT 1.1 session protocol, NextExpectedMsgSeqNum).

Worked example. A daily schedule from 17:00 to 17:00 America/New_York
rolls at 21:00 UTC in summer and 22:00 UTC in winter. US daylight saving
ends on 1 November 2026, so the period that opens at 17:00 on 31 October
runs from 21:00Z to 22:00Z the next day: 25 hours.

*In this library:* `fix/FixSessionSchedule.java` (`daily`, `weekly`,
`sessionStartMillis`, `sessionEndMillis`, `isInSession`),
`fix/FixSessionOptions.java` (`withSchedule`,
`withNextExpectedMsgSeqNum`), passed to
`FixSession.overStream(config, listener, acceptor, store, out, clock, options)`.
`fix/FileSessionStore.java` keeps the period in its `session.period`
file. A Logon outside the window is refused, and an established session
logs out when the window closes. Recipes 325 and 326.

### 17c. FIX versions, repeating groups, and what happens after a fill

Counterparties do not all speak FIX 4.4. Older brokers still run 4.2,
and newer venues run FIX 5.0 SP2, where the session layer (FIXT.1.1) is
split from the application version. Most real messages also carry
repeating groups: several parties on one order, several legs, several
market-data entries.

Formally, a repeating group is a count tag `N` followed by `N` instances,
each starting with the group's delimiter tag and using only the group's
member tags, possibly with nested groups. A receiver validates three
things: the first tag of each instance is the delimiter, no tag outside
the group appears inside it, and the number of instances equals the
count. Failures are session rejects with reason 16 (incorrect NumInGroup)
or 15 (fields out of order).

Worked example. Two parties on a NewOrderSingle:
`453=2|448=TRADER1|447=D|452=11|448=FIRM1|447=D|452=1|`. Tag 453 says
two instances, 448 opens each one, and 447 and 452 are members. Writing
`453=3` with the same body is a reason 16 reject.

After the fill, three controls in the library sit on either side of the
gateway. Before the order leaves, `EntitlementPreTradeCheck` asks
whether this user may trade this instrument on this venue for this
account: default deny, a matching deny beats any allow, every decision
logged. When a fill comes back, the gateway hands it, with the venue's
execution id, to every registered `FillListener`; `LedgerFillListener`
books it into a `PositionLedger`, which refuses a repeated execution id.
After the day, `Rts22ReportBuilder` turns each fill into a MiFIR RTS 22
transaction report and checks the LEI, ISIN and MIC check digits.

*In this library:* `fix/FixVersion.java`, `FixDictionary.java`,
`FixGroupDef.java`, `FixGroup.java`, `FixSessionOptions.withVersion`;
`entitlements/`; `trading/FillListener.java`, `LedgerFillListener.java`;
`regreporting/`. Section 17f covers the hot-path encoder in all three
versions, 17d busts and corrections, and 17e the auth.016 message and the
reference-data lookups. Recipes 338, 339, 340 and 342.

### 17d. Trade busts and corrections

A venue sometimes cancels a trade after the fact (a bust, for example a
clearly erroneous trade) or changes its quantity or price (a correction).
Every system downstream of the fill must undo or redo what it booked.

Formally, an execution report with ExecType H (trade cancel) or G (trade
correct) names the original execution in ExecRefID(19). After the
adjustment the effective fill set is the original set without busted
executions and with corrected ones replaced; positions, average cost and
realised P&L must equal what booking only the effective fills would give.

Worked example (own hand computation). Buy 100 at 10.00 and 100 at 12.00:
position 200, average cost 11.00. Bust the second: position 100, average
cost 10.00. Then correct the first to 80 at 10.02: position 80, average
cost 10.02. The ledger keeps all three log entries and rebuilds the book
from the surviving fills.

*In this library:* `trading/HftOrderGateway.java` (`onTradeBust`,
`onTradeCorrect`), `trading/FillListener.java` (`onBust`, `onCorrect`,
default no-op), `trading/PositionLedger.java` (`onBust`, `onCorrect`,
`effectiveFills`, `adjustments`) and `trading/DropCopyReconciler.java`
(`Adjustment`, and a `reconcile` overload that takes the drop copy's
adjustments). A FIX 4.2 report with ExecTransType 1 or 2 is read as H or
G. The gateway does not reopen an order after a bust. Since 2.4
`PaperTradingGateway.bustFill` and `correctFill` simulate busts and
corrections; cash and positions are replayed from the surviving fills, and
`setReopenOnBust(true)` also reduces the order's filled quantity (default off).
Recipes 344 and 350.

### 17e. Regulatory reports and reference data

A transaction report is only as good as its identifiers. A check digit
proves an LEI is well formed, not that the firm exists or that its
registration is current. That needs the public registries.

Formally, a MiFIR report is sent as ISO 20022 auth.016.001.01 and must pass
the ESMA schema and ESMA's content rules (conditional fields, code lists,
identifier shapes), and its LEIs must be in GLEIF with a maintained status,
its ISINs in FIRDS, and its MICs active in ISO 10383. CAT (US) and EMIR
Refit (EU derivatives) have their own field sets and action/event codes.

Worked example. An RTS 22 report whose buyer LEI passes the ISO 17442
check digits but has status LAPSED in the GLEIF golden copy passes
`validate()` and is refused by `ReferenceDataChecker`. An EMIR report with
action TERM and event TRAD fails `validate()` with EMIR-VR-2152-04, because the
ESMA rules let TERM carry only NOVA, COMP, ETRM, CLRG, EXER, ALOC, CREV, CORP or
INCP. The ESMA workbook's own example: a notional of 25 nines (its default
value) passes EMIR-VR-2055-01; 26 digits fails.

*In this library:* `Rts22TransactionReport.toAuth016Xml` and
`validateEsmaRules` (about 45 data-only rules; the ESMA XSD is checked in
tests only when `QFL_ESMA_XSD` is set, since it cannot be redistributed),
`regreporting/registry/` (GLEIF golden copy and API, FIRDS FULINS, ISO 10383,
`ReferenceDataChecker`), `regreporting/cat/` and `regreporting/emir/`. CAT and
EMIR are not certified. From 3.0 EMIR is checked against the ESMA Refit
validation rules (ESMA74-362-2683) and CAT against the IM Technical
Specifications v4.1.0 r14, single-record rules only; `EmirRefitReport.rules()`
and `CatEvent.rules()` list each check with its source and evidence class
(`RuleProvenance`). EMIR field numbers and formats are checked against the
Annex of Regulation 2022/1860. Section 17g covers the ESMA rules that
need reference data and the submission envelope (2.4); section 17i the EMIR
rules that need state and the CAT linkage keys (3.1). Swap legs are not
implemented. Recipes 347 to 349.

### 17f. One order encoder, three FIX versions

The allocation-free order encoder used to write FIX 4.4 only, so a 4.2
broker or a 5.0 venue needed the slower general builder. In 2.3 the same
encoder writes all three, and the readers on the other side translate
version differences away.

Formally, 4.2 and 4.4 differ in the header (BeginString) and in execution
reports: 4.2 signals a fill with ExecType 1 or 2 and a bust or correction
with ExecTransType(20) 1 or 2, while 4.4 uses ExecType F, H and G. Under
FIX 5.0 SP2 the session is FIXT.1.1 and each application message may carry
ApplVerID(1128); otherwise DefaultApplVerID(1137) from Logon applies.

Worked example. A 4.2 report with `150=2|20=0` reads as `execType() == 'F'`
and `rawExecType() == '2'`; with `20=1` it reads as `'H'`, a bust. A 5.0 SP2
encoder with `applVerId("9")` writes `1128=9` in every header.

*In this library:* `fix/FixOrderEncoder.java` (version constructor,
`applVerId`), `fix/FixExecReportView.java` (`execType`, `rawExecType`) and
`fix/FixMarketDataView.java`, which ends the NoMDEntries group at the first
tag that is not a member. Recipe 343.

### 17g. ESMA rules that need reference data, and the file you send

Section 17e checked that an LEI exists today. Many ESMA rules ask a sharper
question: was it valid on the trading date, was the instrument admitted on that
venue that day, and is the file being sent within the deadline? Those need dated
reference data.

Formally, ESMA's validation rules (CON-xxx) are applied by the competent
authority to each auth.016 record. The file itself goes in a head.003.001.01
envelope that holds a head.001.001.01 business application header (sender LEI,
receiving authority, message definition `auth.016.001.01`, creation time) and the
auth.016 document (ESMA65-8-2356, paragraphs 124 to 132; published reference).

Worked example (own test, `Rts22ReferenceRulesTest`). A buy on XPAR whose
counterparty LEI has status RETIRED in the fixture registry fails CON-160 for
field 16; with status LAPSED it passes, because CON-160 accepts a lapsed LEI.

*In this library:* `Rts22TransactionReport.validateWithReferenceData` (36 CON
rules), `toSubmissionXml`, and in `regreporting/registry/` the types
`ReferenceData`, `LeiValidity` and `CodeRegistry`, plus dated default methods on
the three registries. Since 3.0 the shipped GLEIF, FIRDS and ISO 10383 loaders
also read dates, country, SI category and the commodity flag when the file has
those columns, so every rule runs on the public files. CON-430 needs the ISO
10962 table: `CfiCodeRegistry.fromSixXlsx` reads your copy of the SIX code list
and checks the four attribute letters group by group. Recipe 353. The size
and entry caps every loader applies are listed in [INPUT_LIMITS.md](../INPUT_LIMITS.md).

### 17h. Pending reports, field 2 history and the feedback file

A competent authority remembers. It knows which transaction references a firm
has used, whether each was cancelled, and which reports are waiting for an
instrument that FIRDS has not published yet. A report is not simply right or
wrong on arrival; it can be pending for a week.

Formally (ESMA65-8-2356 and ESMA65-8-2594 Annex 1 v1.7; published reference):
the record key is field 4 followed by field 2 (para 70). A new report is
rejected under CON-023 while the last accepted report with that key is not a
cancellation; a cancellation is rejected under CON-024 when there is nothing to
cancel and CON-025 when it was already cancelled. When the only errors are
CON-411 or CON-471 the authority answers PDNG and repeats instrument validation
daily until the seventh calendar day after reception, then accepts or rejects
(para 20(c)); any other error rejects at once (para 20(e)). Cancelling a
pending report aborts it (para 72). The answer comes back as an auth.031 status
advice with ACPT, PART or RJCT for the file and an entry per record, whose
business application header points at the submitted file through `Rltd`
(paras 101 to 106).

Worked example (scenario 3 of para 79, own test `Rts22StatusTrackerTest`).
TXN162 arrives on T+1 for an ISIN not yet in FIRDS: PDNG with CON-411. Its
cancellation on T+2 is accepted and the queue empties; TXN162 can then be sent
again. Had the instrument stayed missing, revalidation on day 8 after reception
would reject it with CON-411.

*In this library:* `Rts22StatusTracker` (`submit`, `revalidate`, `restore`),
`StatusAdvice` (`of`, `toXml`, `toFeedbackXml`, `parse`), `BusinessHeader`,
`RecordOutcome`, `RecordStatus`. That a pending report blocks reuse of its
reference under CON-023 is the library's reading of para 71. Recipe 358.

### 17i. EMIR rules that need state, and CAT linkage keys (3.1)

A trade repository and the CAT processor both remember. A repository refuses a
second "new" report for a trade it already holds and a report stamped after it
arrived; CAT refuses a route whose order it cannot find. None of this is visible
in one report: the repository needs its own history and clock, CAT needs the
other events in the batch.

Formally, for EMIR (ESMA74-362-2683, sheet Validations_Cpty_derivative, columns
AC and AD; published reference) the action types of one (field 1.4, UTI) pair
follow a small state machine (row 184). The first report must be NEWT or POSC
(EMIR-VR-2151-02); there is one NEWT per pair (-03); after EROR only REVI is
accepted (-04); a trade that is no longer outstanding takes only CORR, EROR or
REVI (-05), and REVI is refused while it is outstanding (-06). The library reads
"outstanding" as: the last of TERM or REVI was not TERM, and the expiration or
early-termination date (2.44, 2.45) is not before the date of field 1.1. That
reading is the library's, not the workbook's. Row 5 compares the reporting
timestamp with the repository's receipt time (-1001-02: stamped after receipt;
-1001-03: the date is earlier than the day before receipt), rows 6 to 13 check
GLEIF status on the date of field 1.1, and rows 47 and 82 the official ISO 4217
list.

For CAT (IM Technical Specifications v4.1.0 r14, section 2.6.1, Table 5;
published reference) events link by keys, not by time or file order: the Order
Key of MENO, MEOR, MONO and MOOR is (orderKeyDate, CATReporterIMID, symbol or
optionID, orderID). Two NEW events may not share a key (CAT-LK01, and every
event with the key is reported), a route needs a new-order event with its key
(CAT-LK02), and a keyed event must carry its key fields (CAT-LK03). COR and RPR
records reuse the key of the record they replace and are not keyed again.

Worked example (own tests). A NEWT for one UTI is accepted and recorded with
`InMemoryUtiHistory.accept`; a second NEWT from the same counterparty fails
EMIR-VR-2151-03, while the other counterparty's own NEWT passes. After an EROR, a
VALU fails -2151-04 and a REVI passes. A reporting timestamp of
2026-03-02T10:00:00Z received at 09:59:59Z fails -1001-02; received at
2026-03-04T00:00:00Z it fails -1001-03. On the CAT side, a MENO and a MEOR with
orderID O-1 link; a MOOR with O-1 next to that MENO does not (an option route
never links to an equity order) and fails CAT-LK02; two MENOs with O-1 both fail
CAT-LK01.

*In this library:* `regreporting/emir/EmirStatefulValidator` (opt-in; it runs
`EmirRefitReport.validate()` first and then the stateful rules (28 catalogue entries in 3.1) for whichever
sources it was given, so a missing source skips its rules), `UtiHistory`,
`InMemoryUtiHistory`, `CurrencyRegistry`; `regreporting/cat/CatLinkageChecker`
and the event types MENQ, MONO, MOOR and MONQ (rules CAT-R13 to CAT-R16).
Since 3.2 the builder fills 2.2 to 2.4 and the validator checks 2001-04 (2.1 against 2.2 to
2.4, with its siblings 2002-02, 2003-05 and 2004-04); `withColumnAdCodes()` reports the row 13
client code prefix as 1009-08, column AD's number for it (recipes 470 and 471). Still not
checked for EMIR: third-country regulated markets in 1003-01. For CAT the option lifecycle, multi-leg and supplement events, linkage across
files and the schema gate came later in 3.1 (17j to 17l), and the other events of Tables 60
and 61 in 3.2 (17m); still not checked are their linkage and reference data. `check` is
batch-local, so pass a whole day's events for a firm, or use `checkFiles`. Recipes 364 and 365.

#### Branches, venues, a changed UTI, and dates the repository remembers (3.1)

**The plain idea.** Four more EMIR checks need something the single report does not hold.
Is the counterparty a branch rather than a legal entity? Was the trade done on a regulated
market, so that no one else is responsible for reporting it? Has the firm quietly given an
existing trade a new UTI? And does today's report agree with the dates the repository
already holds for that UTI: maturity, early termination, execution time? A branch is a
fact in GLEIF. A regulated market is a fact in ISO 10383. The dates live in the earlier
reports. A changed UTI needs one more thing: an identity for the trade other than the UTI
itself.

**Formal statement.** ESMA74-362-2683, sheet Validations_Cpty_derivative, columns AC
(conditions) and AD (codes). Evidence class: published reference for each rule text,
library reading where marked.

- Rows 6, 7, 8, 13 (EMIR-VR-1002-04, 1003-05, 1004-04, 1009-04): "The LEI shall pertain to a
  legal entity and not a branch." The test is `LeiRecord.entityCategory()` equal to `BRANCH`.
  That value comes from the LEI-CDF 3.1 XSD (EntityCategoryTypeEnum). The golden-copy loader
  reads `Entity.EntityCategory`. The column name, and the values GENERAL, SOLE_PROPRIETOR, FUND
  and RESIDENT_GOVERNMENT_ENTITY, were seen in the real intra-day file of 2026-11-02 08:00.
  The API loader reads `entity.category`. An unknown category skips the test.
- Row 7, condition 1 (EMIR-VR-1003-01): 1.3 is populated unless 2.41 is a regulated market
  (or an equivalent third-country market) at the time of conclusion. When 2.41 is such a
  market, 1.3 is blank. *Library reading:* we treat a venue as a regulated market when its
  ISO 10383 market category is `RMKT`, its country is in the EEA, and it is active on the date
  of 2.42. We cannot tell whether a non-EEA `RMKT` counts as equivalent from ISO 10383, so the
  rule is skipped for it.
- Row 26, condition 3 (EMIR-VR-2001-03): the UTI may not change once reported. *Library
  device:* the history is keyed by (1.4, UTI), so the caller supplies a trade key. The
  validator compares the report's UTI with the first UTI accepted under that key
  (`UtiHistory.firstUtiForTradeKey`).
- Rows 12, 67, 69, 70, 186: 1008-02 (1.8 unchanged), 2042-03 (MODI keeps 2.42), 2044-04 and
  2045-08 (REVI after a non-EROR report), 2045-02/03/06/07 (early termination against the
  history), 2153-06 (CORR event date not before 2.42), 2153-08 (event date not after the
  2.44 and 2.45 "reported for that UTI"). Where the current report leaves a date blank, it
  is taken from the latest accepted report that carried it. A REVI resets 2.45.
- Row 186's column AC numbers its last two conditions "6 7." and "7 8.". Column AD has
  2153-01 to -08, so we take the codes from AD (library reading, as for 1009-09).

**Worked example** (own test, `EmirStatefulRules31bTest.earlyTerminationDateRow70`). The
history holds a NEWT with 2.42 = 2026-03-02T10:00Z and 2.44 = 2031-03-04, then a TERM with
2.45 = 2026-03-02.

- A CORR that leaves 2.45 blank fails EMIR-VR-2045-07: the trade was terminated and not
  revived, so the correction must restate the termination date.
- A REVI that carries 2.45 = 2026-03-02 fails EMIR-VR-2045-08: the previous report was a
  TERM, not an EROR.
- Once a REVI is accepted, the same CORR passes.
- Before the TERM, a MODI with 2.45 = 2026-03-02 fails EMIR-VR-2045-06: 2.45 may first
  appear only on a TERM.

**What it does not prove.** The rule texts are transcribed, and every case is our own
construction. No trade repository's response has been compared. The EEA reading of
"regulated market" is ours. A firm whose trade key is not stable across systems will see
false EMIR-VR-2001-03 failures.

*In this library:* `EmirStatefulValidator.withLeiRegistry`, `withMicRegistry` and
`withTradeKey`; `LeiRecord.entityCategory()` and `isBranch()`;
`InMemoryUtiHistory(Function)`; `UtiHistory.firstUtiForTradeKey`. The builder key for 2.41
is `venueOfExecution`. Recipes 400 to 403.

### 17j. An option order's life after the new-order event (3.1)

An option order does not stop at "new" and "routed". A desk can receive it from another
firm instead of creating it, change its price, cancel part of it, and fill a customer from
it. CAT wants each of those steps as its own event, and it wants every step to say which
order it belongs to. The order is named by its Order Key, and an event that changes the
order either keeps the key or moves the order to a new key and says which key it came
from.

Formally (published reference):

- **Accepted (MOOA, section 5.1.4, Table 70).** This is the event a firm reports when the
  order came from another CAT reporter. A MONO is for orders the firm received from a
  customer or originated itself (section 5.1.1). Like a MONO, a MOOA opens an Order Key:
  orderKeyDate, CATReporterIMID, optionID, orderID (the Table 70 linkage list). Its
  required fields include receiverIMID, senderIMID and senderType (seq 15 to 17). For order
  events, senderType is E, F or O (Appendix G).
- **Modified (MOOM, section 5.1.7, Table 79).** The required fields add initiator (seq 22;
  C for the customer, F for the firm, Appendix G) and leavesQty (seq 27, Real Quantity).
  The firm may give the modified order a new key. If it does, seq 9 and 10
  (priorOrderKeyDate, priorOrderID) name the key it replaces.
- **Cancelled (MOOC, section 5.1.9, Table 83).** cancelQty and leavesQty (seq 13, 14) are
  Real Quantity; initiator is required (seq 15).
- **Trade (MOOT, Table 90) and fulfillment (MOOF, Table 92).** These open a Trade Key or
  Fulfillment Key on optionID. A MOOF uses the option values of fulfillmentLinkType: O,
  OS or OE. The equity values are FOR, Y, YE, YP and YS (Appendix G).
- **Prices.** A price on MOOA, MOOM, MOOT and MOOF must not be negative (Table 70 seq 21,
  Table 79 seq 24, Table 90 seq 15, Table 92 seq 13). On MOOA and MOOM it is required
  with LMT and blank with MKT.

The linkage rule the library adds is CAT-LK05: a MOOM or MOOC must find the order it acts
on. A MOOM with a prior key is looked up under the prior key, and its own key is then open
for later events. That last point is the library's reading of the Table 79 linkage list.

Worked example (own test, `CatLifecycleMultiLegTest.modifiedWithNewOrderKeyTable79`):

1. A MONO opens O-1 at 09:30:00 Eastern.
2. A MOOM at 09:31 has priorOrderKeyDate "20260302 093000.000000000" and priorOrderID O-1,
   and opens O-2.
3. A MOOR keyed O-2 at 09:31 links to the MOOM.

Checked together, the three events give no issue. Drop the MONO and the MOOM fails
CAT-LK05. The route still links, because the MOOM opened O-2.

*In this library:* `regreporting/cat/CatEventBuilder` (`optionAccepted`, `optionModified`,
`optionCancelled`, `optionTrade`, `optionFulfillment`), rules CAT-R17, CAT-R18 and CAT-R20 in
`CatEvent.rules()`, and CAT-LK05 in `CatLinkageChecker.rules()`. Recipes 420 and 421.

### 17k. Multi-leg orders and supplements (3.1)

A spread, such as buying one call and selling another, is one order with several legs. CAT
reports it as one multi-leg event, with the legs listed inside it. The event as a whole has
no single instrument, so it has no symbol. Its price is the net price of the package, and
that can be a credit. Sometimes the list does not fit, or a detail such as the account's
FDID is only known later. The firm then sends a supplement: a small event with the same
Order Key that adds what was missing.

Formally (published reference):

- **Multi-leg new order (MLNO, Table 100).** It has no symbol or optionID field. Seq 8,
  underlying, is optional. numberOfLegs (seq 30, Whole Quantity) and legDetails (seq 32)
  are required.
- **Legs (Table 101).** Each leg gives exactly one of symbol (32.n.2) and optionID
  (32.n.3), a side (32.n.5) and a legRatioQuantity (32.n.6, Real Quantity).
  openCloseIndicator (32.n.4) must be blank on an equity leg. The side is B or S on an
  option leg and B, SL, SS or SX on an equity leg (Appendix G).
- **Price.** The net price may be positive, negative or zero; debits are positive and
  credits negative (seq 16). priceType (PU, TC or TS) is required when orderType is LMT
  (seq 31).
- **The multi-leg Order Key** is orderKeyDate, CATReporterIMID, orderID, with no symbol
  (the Table 100 linkage list). MLOR (Table 102) restates the legs (Table 103). MLOC
  (Table 126) cancels the order. MLOS (Table 139) carries the legs that did not fit. All
  three link to the MLNO through that key.
- **Supplements.** MENOS (section 4.2, Table 17) supplements a MENO. MONOS (section 5.1.2,
  Table 63) supplements a MONO; the library also accepts a MOOA as its parent. MLOS
  supplements an MLNO. Section 4.2 gives a common case: an order on an account whose FDID
  does not exist yet is reported with firmDesignatedID "PENDING", and the FDID follows in
  the supplement.

Two readings are the library's own. The first: legDetails may hold fewer legs than
numberOfLegs, because the rest can come on an MLOS (the note under Table 101), but never
more. The second: a supplement with no parent is an error (CAT-LK04). The specification
says what a supplement is for; it does not print the rejection.

Worked example (own test, `CatLifecycleMultiLegTest.multiLegTables100To102And126`):

1. An MLNO with a long AAPL 150 call and a short AAPL 140 put, ratio 1:1, priceType PU and
   price -0.35 (a credit) validates clean. The builder sets numberOfLegs to 2.
2. Add an equity leg with side SL and numberOfLegs 3, and it still validates.
3. Set numberOfLegs to 1 instead, and the event fails: three legs is more than one.
4. A leg that names both a symbol and an optionID fails "exactly one of symbol and
   optionID".

*In this library:* `CatEventBuilder.multiLegNewOrder`, `multiLegRoute`,
`multiLegCancelled`, `multiLegSupplement`, `newOrderSupplement`, `optionOrderSupplement`,
`optionLeg` and `equityLeg`; `CatEventType.isMultiLeg()` and `isSupplement()`; rules
CAT-R19 and CAT-R21, and CAT-LK04. Recipe 422.

### 17l. Linkage across files, and the schema gate (3.1)

CAT checks a submission in two passes. The first pass looks at each record on its own: is
it one well-formed JSON line, does it name a known event, are its fields known, present and
of the right type? The second pass links records by key. Keys belong to the firm, not to
a file, so the second pass sees every file the firm has sent. An order sent this morning
and its route sent this afternoon in another file still link. The same key used in two
files is still a duplicate.

Formally:

- **The schema (sections 6.1.5 to 6.1.5.3).** The schema is a JSON file. Its
  eventDefinitions give each field a name, dataType, JSONDataType, required flag and CSV
  position, as in the printed MECO example. It also lists the data types and the choice
  values.
- **The record rules (sections 6.1.2.1 and 6.1.2.2).** A record is one line of at most
  8190 bytes, holding standard JSON with no new-line inside. Conditional and optional
  fields without a value are omitted.
- **Data types (Table 3).** Table 3 sets the value rules and prints examples that pass and
  fail. Numeric (6,4) accepts 0999999.99990, because leading zeros of the whole part and
  trailing zeros of the fraction do not count, and refuses 1.12345 and 10. (a trailing
  point). Real Quantity must not have trailing decimal zeros, so 500.0 fails. A timestamp
  is a string 'YYYYMMDD HHMMSS.CCCNNNNNN', with a space or T in the middle, or Unsigned
  nanoseconds since the epoch.
- **Key scope (section 2.6.1, Table 5).** Keys are unique per CATReporterIMID, which is
  the scope of the cross-file check. These are published references.

The library's readings are three:

- The names of the dataTypes and choices members of the schema file are not printed.
- An unknown field is refused (CAT-S04).
- Table 3 prints minutes and seconds as 01 to 59, but its own examples use 00, so 00 to
  59 is accepted.

Worked examples:

1. **The section 6.1.2.2 record.** Section 6.1.2.2 prints a one-line MENO repair record.
   Against the library's transcription of Table 16 it has one problem: it leaves out
   representativeInd, which seq 33 marks R (CAT-S05). This is a printed example
   disagreeing with the table it illustrates. The library reports it rather than relax
   the rule.
2. **Fixing it.** Add `"representativeInd":"N"` and the record passes. Write
   `"price":"10.01"` as a string and it fails CAT-S06, because a Price is a JSON NUMBER.
3. **Two files** (own test, `CatLifecycleMultiLegTest.crossFileLinkage`). File A holds a
   MONO and file B holds its MOOR and a MOOC. File B checked alone gives CAT-LK02 and
   CAT-LK05. `checkFiles(List.of(a, b))` gives none. Adding a file C with a second MONO
   of the same key gives CAT-LK01 in files A and C.

*In this library:* `regreporting/cat/CatSchema`:

- `bundledSubset()` returns the library's transcription of the nineteen supported event
  tables. It is not the official file.
- `load(Path)` reads the official file.
- `validateLine` and `validateLines` run the record checks, rules CAT-S01 to CAT-S09.
- `toJson(CatEvent)` writes typed JSON. `CatEvent.toJson()` still writes every value as
  a string.

`CatLinkageChecker.checkFiles` with `Batch` and `FileIssue` is the cross-file check. The
JSON reader is package-private and has no dependency. Recipes 423 and 424.

### 17m. The rest of Tables 60 and 61, and the official schema (3.2)

Tables 60 and 61 list every option event CAT knows: 35 simple option types and 25
multi-leg types. By 3.1 the library had the ones a plain order life needs (new, route,
accept, modify, cancel, trade, fill, supplements). The other 47 follow the same order
through the firm: internal routes between desks, child orders, route modifications and
cancels, modification and cancel requests, adjustments, the quote lifecycle (routed,
received, modified, cancelled), fulfillment amendments, allocations and effective events.
Each one is a table of fields, so adding it is mostly careful transcription.

Formally:

- **Required fields.** For each type, the required fields are the non-Boolean fields its
  table marks R, the same reading as in 3.1 (a missing Boolean is read as false, Table 3).
  For example, Table 76 (MOCO, child option order) marks parentOrderKeyDate,
  parentOrderID and openCloseIndicator R.
- **Price (CAT-R22).** Each table words its price rule. Option order events say "must be
  greater than or equal to zero", required with LMT and blank with MKT (Table 65 seq 20).
  Multi-leg events give a net price that may be negative, a credit (Table 104 seq 20).
  Allocations and fulfillment amendments have the non-negative rule only (Table 96
  seq 17). The library applies to each type exactly the sentences its table prints, and
  the message names the table and seq.
- **Choices (CAT-R23, Appendix G).** initiator is C or F. senderType has two lists:
  E, F or O on order events, and F, N or S on quote events (MOQR, MLQR). priceType is PU,
  TC or TS and is required with LMT on multi-leg orders.
- **The official schema.** CAT publishes the schema JSON on its public website, with no
  login. The site's legal notice reserves copying and redistribution, so the library does
  not commit it. The nightly job fetches it with a pinned SHA-256 and compares it, field
  by field, with the library's transcription. These are published references.

The library's readings are two:

- The new types are not linkage-checked yet. `CatLinkageChecker` skips them, so its
  results for the 3.1 types are unchanged.
- Each type has a family (`CatEventFamily`), taken from the columns of Tables 60 and 61.
  It is there to group the work, and it does not change any check.

Worked examples (own tests, `CatTable6061Test`):

1. **A route modified with a negative price.** An MOMR with price -1 gives "price must be
   greater than or equal to zero (Table 65 seq 20)". The same price on an MLMR (multi-leg)
   passes, because a net credit is negative.
2. **A quote received from an exchange.** An MOQR with senderType E fails, because E is an
   order-event value; N (foreign) passes.
3. **Schema drift.** The schema published in 2026 is version 4.2.0, while the library
   transcribes the v4.1.0 r14 tables. The nightly comparison lists every field whose
   dataType, JSONDataType or required flag differs. Each one is then either fixed or
   accepted with a reason in `cat-official-schema-deltas.txt`.

*In this library:* `CatEventBuilder.start(type)` starts any type. `CatEventType.family()`
groups them. The bundled schema now covers all 66 types. Recipes 480 to 483.


### 17n. Maintaining the CAT transcription (3.2)

The library's CAT checks rest on a transcription: the field tables of a PDF
specification turned into a JSON file (`cat-im-schema-subset-4.1.0r14.json`) and a
row of rules per event type. A transcription can be wrong in two ways. The script can
misread the PDF, or CAT can publish a new version. Two tools tell those apart: the
script that regenerates the file, and the nightly comparison with the official schema.

Formally:

- **Regenerating.** `scripts/cat-transcription/gen2.py` reads `cat-raw.txt` (the
  output of `pdftotext -raw` on the specification, kept out of the repo) and writes
  `schema.json` (the bundled file) and `meta.json` (per-type required fields, price
  sentences and choice seqs). `genjava.py` reads `meta.json` and writes the text of the
  `CatEventType` constants and the `CatTableRules` rows, to paste into the source. The output is diffed against the committed
  file; a regenerated file that differs is reviewed line by line before it replaces it.
- **The pin.** `scripts/reference-files.sha256` holds the SHA-256 of the official
  file (`cat-im-schema-4.2.0.json`, from catnmsplan.com). The nightly job downloads
  it, `sha256sum -c` fails the job on any change, and only then is `QFL_CAT_SCHEMA`
  set. A changed hash means CAT republished the file: look at it before updating the
  line. The file is never committed, because the site reserves redistribution.
- **The comparison.** `CatOfficialSchemaTest` checks every event in both files: each
  transcribed field must exist with the same dataType, JSONDataType and required value.
  A difference is either fixed in the transcription or listed, with a reason, in
  `cat-official-schema-deltas.txt`. An unlisted difference fails the job.

Triaging a new CAT version, step by step:

1. Download the new file locally, record its hash, and run the test with
   `QFL_CAT_SCHEMA=<file>` and `QFL_REQUIRE_REFERENCE_FILES=true`. The failure message
   lists every difference.
2. For each one, open the table in the specification the library transcribes. If the
   table says what the official file says, the transcription is wrong: fix the script,
   regenerate, and the difference goes away.
3. If the table says something else, the new version changed the field. Keep the
   transcription (it stays tied to the PDF it cites) and add a line to the deltas file
   naming the version and the change.
4. Pin the new hash in the same commit, so the nightly job and the deltas file agree.

Worked example (3.2.0, own triage of the official 4.2.0 file against the v4.1.0 r14
tables): the comparison listed 33 differences.

- 17 were transcription errors. destination and senderIMID print "Industry Member ID /
  Exchange ID" in a cell that wraps over two lines, and pdftotext split it, so the 3.1
  script kept only the first type. The script now reads the wrapped cell, and the
  bundled file gives both types as an array.
- Regenerating with the fixed script found one more: MLQS destination (Table 132
  seq 12). 4.2.0 no longer defines MLQS, so the comparison could not have flagged it.
  This is why the fix goes into the script, not into the JSON by hand.
- 16 were version changes. 4.2.0 drops the eleven option and multi-leg quote events
  (MONQ, MORQ, MOQR, MOQC, MOQM, MLNQ, MLRQ, MLQS, MLQR, MLQC, MLQM) and retires five
  MENQ fields (RFQID, RFQFlag, bidRelativePrice, askRelativePrice, validUntilDuration).
  These are the 16 lines of the deltas file. The library keeps those types at v4.1.0 r14
  until a 4.2 specification is transcribed.

*In this library:* `scripts/cat-transcription/`, `scripts/reference-files.sha256`,
`CatOfficialSchemaTest`, `cat-official-schema-deltas.txt`. Diagram:
[DIAGRAMS 115](../DIAGRAMS.md#115-the-nightly-reference-files-job-fetch-pin-test-32).


### 18. Scaling out and staying safe

One core, one engine has a throughput ceiling. The scaling answer here is
**shared-nothing sharding**: run N independent engine stacks (own bus, own
gate, own venue thread), partition symbols across them, and let *nothing*
be shared on the hot path, because one shared `synchronized` counter makes
N shards slower than one (that experiment is written up in
ULTRA_LOW_LATENCY.md; the fix was per-shard, cache-line-spaced counters).

What legitimately *must* span shards (firm-wide risk) runs as a slow
observer: a monitor thread (`GlobalRiskAggregator.start(...)`) sums
positions and working orders across all gates every few milliseconds
and flips per-gate **kill switches** on breach. Engaging a kill switch
also sends a cancel-all to the venue. The hot path only ever
*reads one boolean*. Fast paths stay fast; safety stays global.

*In this library:* `trading/ShardedTradingEngine.java`,
`trading/GlobalRiskAggregator.java`.

**Surviving the overnight.** Everything the models *learn* (volume
curves, venue fill rates, alpha weights) lives in memory, and a desk
that relearns it from zero every morning trades half-blind until lunch.
The fix is a **checkpoint**: at end of day, write every model's learned
state into one file; at the next session start, restore it. Two details
matter. The save is **atomic**: the state is written to a temp file
that is forced to the device, renamed over the old one, and the
directory is forced after the rename, so a crash or power loss leaves
either yesterday's file or today's, never a partial one. And the
restore is **selective about time**: learned state comes back, but
intraday state (today's running totals, a pending spread spike) does
not, because it belonged to yesterday. *In this library:*
`persist/Checkpoint.java`, with `writeState`/`readState` on every model
that learns across days.

### 19. How this codebase tests itself

Worth studying as a checklist for your own projects:

- **Allocation-counter tests**: every zero-allocation claim is tested (§13).
- **Model-based equivalence**: the fast order book vs the readable one,
  identical random inputs, demand identical state. The readable version is
  the spec.
- **Property tests**: for example "a FOK either fills entirely or changes
  nothing," asserted over thousands of randomized books; "symmetric last
  look rejects both directions 50/50" asserted over randomized moves. Since
  3.1 `PricingInvariantPropertiesTest` checks put-call parity (Black-Scholes,
  Black-76), Merton's no-arbitrage bounds, monotonicity in strike, spot and
  volatility, convexity in strike, American >= European and >= intrinsic, and
  implied-volatility round trips. These are plain JUnit tests driven by a
  `SplittableRandom` with a fixed seed per test; the number of cases is the
  system property `qfl.cases` (default 1000), and a failure names the seed and
  case index, so it reproduces exactly. No property-testing library is used.
- **Mutation fuzzing of parsers**: `FixParserFuzzTest` and
  `ReferenceParsersFuzzTest` feed `FixMessage.parse`, `FixDecoder` and the
  FIRDS XML, CFI XLSX, GLEIF CSV and ISO 10383 CSV loaders with seed inputs
  edited 1 to 8 times (bit flips, interesting bytes, inserts, deletes,
  duplicates, truncation) and with random bytes. The only exceptions allowed
  out are the parser's own (`FixParseException`, `WireProtocolException`,
  `IOException`). The default build runs 1000 cases per test; `-Pfuzz` runs
  only the fuzz tests, at 200,000 cases each (`-Dfuzz.tries=N`), and the
  nightly `fuzz` job runs that. There is no coverage feedback, so this is
  mutation fuzzing, not Jazzer or AFL.
- **Tables as data**: `FrtbSaTableTest` compares the FRTB parameters with
  hand-transcribed MAR21/MAR22 tables, one cited row per value (section 10o).
- **Differential tests**: the optimized NBBO fast path vs brute-force
  recomputation, 20,000 random updates.
- **Load and timing tests**: throughput tripwires set far below measured
  desktop numbers, so they catch a dramatic slowdown without flaking on
  slow runners. They are tagged `load` or `timing` and skipped by the
  default build; `./mvnw -Pfull test` and the nightly workflow run them.
- **Reference checks that cannot skip**: tests against files we may not
  commit (the ISDA CDS Standard Model test grids, the ESMA XSDs) skip on a
  laptop without them. The nightly `reference-files` job downloads them with
  `scripts/fetch-reference-files.sh`, checks each against the SHA-256 pinned
  in `scripts/reference-files.sha256`, sets `QFL_REQUIRE_REFERENCE_FILES=true`
  so a missing file fails instead of skipping, and fails if any reference
  test skipped. Pull requests run two committed stand-ins: `IsdaGoldenSubsetTest`
  pins our upfront and cash settlement for 48 invented contracts, and
  `EsmaXsdGoldenTest` pins the SHA-256 of the three documents validated
  nightly.
- **Mutation floors**: the nightly PIT jobs (`mutation` for core,
  `mutation-lowlatency` for regreporting and the FIX codecs) fail when a
  package's score falls below its floor in `scripts/pit-thresholds.txt`
  (for example rates 83, risk 78, fix 81).
- **Bounded inputs**: every file loader caps what it reads (an XLSX is at
  most 10,000 zip entries and 256 MiB inflated); the list is in
  [INPUT_LIMITS.md](../INPUT_LIMITS.md).
- **Platform limits as tests**: the load test *budgets* dropped messages
  under OS preemption instead of pretending an unpinned desktop can
  promise zero drops. Tests encode what the platform can actually
  guarantee.

### 19b. Can I trust the jar I downloaded? (3.1)
Plain idea: if the same source always produces the same bytes, anyone can rebuild a
release and compare. A mismatch means the jar was not built from that source.

Formal statement: with `project.build.outputTimestamp` fixed, `./mvnw -DskipTests
package` on JDK 25 at tag `vX.Y.Z` yields jars whose SHA-256 equals the published
ones. `scripts/check-reproducible.sh` checks the weaker in-repo property (two clean
builds, same hashes) on every PR.

Worked example (2026-11-02, Windows, JDK 25.0.5): two clean builds gave identical
hashes for all 7 jars, e.g. core jar `5d1a6064...a0d9`. Evidence class:
self-consistency (same machine twice); a cross-OS rebuild is the next check.

The SBOM (`*-sbom.cdx.json` on each release) lists what the jar depends on at
runtime: for core only `jspecify` (annotations), for lowlatency core plus `jspecify`.

### 20. Modules and exceptions

A Java module is a jar that says what it offers and what it needs. Since 4.0 the
library is four modules, and the split is the stability promise. The stable one,
`com.quantfinlib` (artifact `quant-finance-library`), holds pricing, risk, research
and backtesting. Two incubating ones hold file and HTTP I/O (`com.quantfinlib.io`:
`io`, `report`) and the strategy DSL (`com.quantfinlib.research`). The reference one,
`com.quantfinlib.lowlatency` (artifact `quant-finance-library-lowlatency`), holds the
trading lane: order books, FIX and SBE, feeds, the market-data bus and tick files,
gateways, RFQ and regulatory reporting. (3.x had two modules; MIGRATION_4.0.md lists
what moved.)

Formally: each of the other three modules `requires transitive com.quantfinlib`, so
anything that reads one also reads the stable module, and dependencies run one way
only. A
package belongs to exactly one module, which is why a few types changed package in
3.0: the order side became `core.Side` because stable `execution` and `backtest`
signatures use it, while `StrategyRunner` moved to `trading` because its paper
engine is a lane class. The stable module exports a helper package, `internal`, to
the lane module alone (a qualified export); to everyone else it does not exist. Since
4.0 the stable module needs only `java.base`: `java.net.http` belongs to io and the
lane, `jdk.httpserver` and `java.xml` to the lane, and a test runs `jdeps` on the
stable classes to keep it that way.

Worked example. A research project writes `requires com.quantfinlib;` and gets
`pricing`, `risk` and `backtest`; trying to import `com.quantfinlib.trading` fails
at compile time, because that package is not in the module it reads. A trading
project writes `requires com.quantfinlib.lowlatency;` and can import both
`trading.HftOrderGateway` and `core.Side` with no second line. On the class path
no descriptor matters; put the jars you need on it. The demos, the command line and
the sample strategies are not exported and ship in the low-latency artifact's
`examples` jar.

Exceptions follow one rule: a fault in external input (a broken FIX or SBE frame, a
malformed CSV file, a bad limits file) throws a library type, and that type extends
whatever JDK exception the same code threw before. `WireProtocolException` is an
`IllegalStateException`; `MalformedDataException` and `ConfigurationException` are
`IllegalArgumentException`s. Because a class has only one superclass, the common
type is an interface, `core.QuantFinException`, and
`e instanceof QuantFinException` tells an input fault from a bug. A bad argument
from the caller, such as a negative size, stays a plain `IllegalArgumentException`.
See [ARCHITECTURE.md](../ARCHITECTURE.md#exception-policy).

### 20b. The 4.0 module split (designed in 3.1, revised in 3.2, shipped in 4.0)

`docs/DESIGN_4.0.md` explains the split. The plain idea: the stable jar should promise
only what one maintainer can check, and a pricing user should not get an HTTP client.
Formally, 4.0 moved the file and HTTP loaders (the `io` package) and `report` to the
incubating `-io` module, `dsl` to `-research`, hot-lane code (`AggregatedBook`,
`rfq`, `marketdata.tick`, `backtest.tick`) to the low-latency module, and `cli` and
the toy strategies to an examples jar. Most package names stayed the same, so most
users changed a dependency, not an import.

The 3.1 draft wanted to move more (`execution`, `microstructure`, `persist`,
`screener`, `ml`, all of `data`). A scan of imports showed stable code uses each of
them, for example `backtest` uses `microstructure.Execution` in nine files. Moving such
a package would make core require a module that requires core, and Java modules reject
a cycle. Worked example: `alpha.Factors` reads `screener.Fundamentals`, so `screener`
stays wherever `alpha` is, which is the stable jar.

A jar move cannot be `@Deprecated`: Java modules forbid one package in two jars, so
there was no new home to point at before 4.0 existed. Where 3.2 could add a replacement
type, it did and deprecated the old one for removal: `io.CsvBarLoader` and two others
replaced their `data` namesakes, and `marketdata.AggregatedBook` replaced
`fx.AggregatedBook`. 4.0 removed those and the single-curve swap pricer. japicmp against 3.0.0 reports
the release as MAJOR, and a second japicmp run excludes exactly the MIGRATION_4.0 rows
and fails on any other break.

Meanwhile 3.1 split the three largest classes internally. `Indicators`,
`MultiCurrencyExposureSimulator` and `FixSession` are now facades over package-private
classes, with identical public signatures (checked with japicmp against 3.0.0) and
the unchanged test suite passing.

---

[Index: LEARN.md](../LEARN.md) | [Previous: Part I, The Finance: backtesting and the desk playbooks (sections 11, 11b)](03-backtesting-and-desk-playbooks.md) | [Next: Part III, Your learning path](05-learning-path.md)
