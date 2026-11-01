### Added
- `fix.FixSessionSchedule`: daily, overnight and weekly FIX session windows in a time zone, DST-aware.
- `fix.FixSessionOptions` and new `FixSession.overStream`/`initiate`/`accept` overloads: Logon refused outside the session window, Logout at the end of the window, and an end-of-period sequence reset (store roll) that also survives a restart.
- `FixSessionStore.sessionStartMillis`/`saveSessionStartMillis` (default no-op); `FileSessionStore` persists the period in `session.period`.
- NextExpectedMsgSeqNum(789) on Logon: sent when enabled, answered when the peer sends it, gaps recovered by replay instead of a ResendRequest exchange; `FixMessage.NEXT_EXPECTED_MSG_SEQ_NUM`.
- `trading.PositionLedger`: allocation-free fill ingestion over dense account/strategy/symbol ids, average-cost and FIFO realised P&L, mark-to-market unrealised P&L, contract multipliers, fees and aggregates.
- `trading.DropCopyReconciler`: compares booked fills with a drop copy and reports missing, extra, mismatched and duplicate executions plus the per-symbol position difference.
- `trading.LimitsConfig` (validated immutable limits with a plain-text format) and `trading.LimitsManager` (versioned changes to running `HftRiskGate`s with an audit trail and rollback).
- `HftRiskGate.limits(LimitsConfig)` and getters for every limit.
- `orderbook.AuctionBook`: opening and closing call auctions with indicative price, volume and imbalance, uncross by maximum volume, then minimum imbalance, then nearest reference price, price-time allocation and transfer of the residual into `HftOrderBook`.
- `AdaptiveSor.maxLatencyPenaltyBps`: optional cap on the latency term.

### Changed
- `HftRiskGate` keeps its limits in one immutable object read once per check, so a check never sees a half-applied change; the existing setters keep their behaviour.
- `OrderBook.cancel` unlinks in O(1) instead of scanning the price level.

### Fixed
- `AdaptiveSor`: every cost term is now in price units (the adverse-selection markout was converted to a fraction and scaled by the fee-adjusted price), and dark probes across all dark venues share one `maxDarkFraction` budget instead of each taking it.
- `AntiGamingJitter.jitterTimes`: bounds come from the original schedule only, so two neighbours can no longer end up 1 ns apart; schedules for a given seed differ from 2.0.0.
