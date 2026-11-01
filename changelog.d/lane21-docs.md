# Docs notes for lane21 (W4 trading lane)

## FIX session schedule and 789 (`fix`)
- Classes: `FixSessionSchedule` (`daily(start, end, zone)`, `weekly(startDay, start, endDay, end, zone)`), `FixSessionOptions` (`withSchedule`, `withNextExpectedMsgSeqNum`), `FixSession.overStream(..., clock, options)`.
- Rule: a period starts at each start occurrence in the zone and ends at the next end occurrence; equal start and end means a full day or week (always open, roll only). The store rolls when the session is created, logs on or ticks in a new period; `FileSessionStore` keeps the period in `session.period`.
- 789: Logon carries the next MsgSeqNum expected. Peer 789 above our next number: Logout. Lower: replay the gap after the Logon exchange. Source: FIX 4.4 errata and FIXT 1.1 session protocol.
- Example: `FixSessionSchedule.daily(LocalTime.of(17,0), LocalTime.of(17,0), ZoneId.of("America/New_York"))` rolls at 21:00Z in summer and 22:00Z in winter; the 2026-10-31 period is 25 hours long.
- STABILITY.md "Reference implementations" lists session schedules as missing: no longer true for schedules (still no entitlements or regulatory reporting).

## Position and P&L (`trading`)
- `PositionLedger(symbols, strategies, accounts, maxLotsPerBook, fillLogCapacity)`, `onFill(execId, account, strategy, symbol, side, qty, price[, fee])`, `realised(CostMethod, a, t, s)`, `unrealised(..., PriceSource)`, `ANY` wildcard.
- Formulas: average cost `a' = (n a + q p)/(n + q)`, realised on close `q (p - a) sgn(n) m`; FIFO realised `sum q_k (p - p_k) sgn(n) m`. Both satisfy realised + unrealised = `m (n M - sum s_i q_i p_i)` (cash-flow identity). Sources: IAS 2 para 25; CFA curriculum on inventory cost flow.
- Example: buy 100@10, buy 100@12, sell 150@11: average-cost realised 0, FIFO realised 50; marked at 13 both totals are 100.
- `DropCopyReconciler.reconcile(ledger, dropCopyFills, priceTolerance)`: break types MISSING, EXTRA, MISMATCH, DUPLICATE; `Report.format()` gives the break report.

## Versioned limits (`trading`)
- `LimitsConfig.parse(text)` with all nine keys required (`unlimited` allowed), `LimitsManager(gates, initial, author, reason, clock)`, `apply(text, author, reason)`, `rollback(version, author, reason)`, `auditTrail()`.
- Atomicity: `HftRiskGate.limits(config)` swaps one immutable limits object; checks read it once. Cite MiFID II RTS 6 Art. 15(2).
- Example for COOKBOOK: load a desk file at start of day, tighten `maxOrderQuantity` after an alert, roll back, print `auditTrail()`.

## Auctions (`orderbook`)
- `AuctionBook(minTick, maxTick, maxOrders)`: `openCall`, `submitLimit`, `submitMarket`, `publishIndicative`, `uncross`, `transferResidual(HftOrderBook, sink, ts)`.
- Uncross rule: max `V(p) = min(D(p), S(p))`, then min `|D - S|`, then nearest reference price; tied prices form one contiguous range so the result is unique. Xetra and Euronext insert a market-pressure step, left out here.
- Worked example: buys 100@10, 200@9; sells 150@8, 100@9 -> price 9, volume 250, imbalance +50.
- HftOrderBook javadoc and README should no longer say auctions are missing.

## P3 fixes (`execution`, `orderbook`)
- AdaptiveSor expected cost: `allIn + s (px (missBps + min(latencyBps, cap)) / 1e4 + adverse)`, all in price units; total dark probe budget `maxDarkFraction x quantity`.
- AntiGamingJitter: neighbours keep at least `g (1 - 2 f) - 1` ns apart.
- OrderBook cancel is O(1) in the level (still keyed by double prices; HftOrderBook remains the venue-grade engine).
