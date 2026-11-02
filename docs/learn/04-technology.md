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
`EmirRefitReport.validate()` first and then the 15 stateful rules for whichever
sources it was given, so a missing source skips its rules), `UtiHistory`,
`InMemoryUtiHistory`, `CurrencyRegistry`; `regreporting/cat/CatLinkageChecker`
and the event types MENQ, MONO, MOOR and MONQ (rules CAT-R13 to CAT-R16). Still
not checked: the GLEIF branch tests (`LeiRecord` has no entity category),
EMIR-VR-1003-01 (venue MIC), 1008-02, 1009-08, 2001-03/04, 2042-03,
2045-03/06/07/08 and 2153-06/08; for CAT the other option lifecycle events,
multi-leg and supplement events, linkage across files, reference data and the
JSON schema. The checker is batch-local, so pass a whole day's events for a
firm. Recipes 364 and 365.

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

### 20. Modules and exceptions

A Java module is a jar that says what it offers and what it needs. Since 3.0 the
library is two modules, and the split is the stability promise. The stable one,
`com.quantfinlib` (artifact `quant-finance-library`), holds pricing, risk, research
and backtesting. The reference one, `com.quantfinlib.lowlatency` (artifact
`quant-finance-library-lowlatency`), holds the trading lane: order books, FIX and
SBE, feeds, the market-data bus, gateways and regulatory reporting.

Formally: the low-latency module `requires transitive com.quantfinlib`, so anything
that reads it also reads the stable module, and dependencies run one way only. A
package belongs to exactly one module, which is why a few types changed package in
3.0: the order side became `core.Side` because stable `execution` and `backtest`
signatures use it, while `StrategyRunner` moved to `trading` because its paper
engine is a lane class. The stable module exports a helper package, `internal`, to
the lane module alone (a qualified export); to everyone else it does not exist. The
stable module needs only `java.net.http`; `jdk.httpserver` and `java.xml` are lane
needs, and a test runs `jdeps` on the stable classes to keep it that way.

Worked example. A research project writes `requires com.quantfinlib;` and gets
`pricing`, `risk` and `backtest`; trying to import `com.quantfinlib.trading` fails
at compile time, because that package is not in the module it reads. A trading
project writes `requires com.quantfinlib.lowlatency;` and can import both
`trading.HftOrderGateway` and `core.Side` with no second line. On the class path
neither descriptor matters; put both jars on it. The demos in
`com.quantfinlib.examples` are not exported and ship in the low-latency artifact's
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

---

[Index: LEARN.md](../LEARN.md) | [Previous: Part I, The Finance: backtesting and the desk playbooks (sections 11, 11b)](03-backtesting-and-desk-playbooks.md) | [Next: Part III, Your learning path](05-learning-path.md)
