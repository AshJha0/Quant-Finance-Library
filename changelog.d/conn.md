### Fixed
- FIX: an application message received before Logon now disconnects the session instead of reaching the listener; acceptors can refuse a Logon (credentials, permissions) through `Listener.onLogonRequest`, answered with a Logout carrying the reason.
- FIX: the acceptor's Logon reply is now sent before its ResendRequest when the initiator's sequence number is ahead, so conformant peers no longer treat the reconnect as a failed logon.
- FIX: `FileSessionStore` rewritten as a CRC-32C journal forced to disk before the message reaches the socket; after a crash a ResendRequest replays the real orders instead of covering them with a GapFill.
- FIX: a torn record at the end of the journal (crash mid-write) is truncated on open instead of making the store unopenable; `truncatedBytes()` reports what was dropped.
- FIX: session stores are bounded (`FileSessionStore(dir, maxRetained)` compacts the journal; the in-memory store keeps the last 100 000 messages) and roll on ResetSeqNumFlag; resend requests below the retention window are reported through `Listener.onResendUnavailable`.
- FIX: incoming SenderCompID/TargetCompID and SendingTime (default tolerance 120 s) are validated; the acceptor adopts the initiator's HeartBtInt; a heartbeat clears the TestRequest only when it echoes its TestReqID; PossDup messages need OrigSendingTime no later than SendingTime; SequenceReset-GapFill must move forward and SequenceReset-Reset rejects a NewSeqNo below the expected one; a too-low MsgSeqNum is answered with Logout before disconnecting.
- FIX: a well-framed message with a bad field gets a session Reject (35=3, SessionRejectReason 373) instead of a disconnect; a garbled frame (CheckSum, BodyLength) is ignored without consuming its sequence number.
- FIX: the parser reads length-prefixed data fields (RawData, XmlData, EncodedText and others) by their length, so embedded SOH no longer splits them, and keeps every occurrence of repeated tags (`getAll`).
- FIX: `FixParse.parseLong` and `priceMantissa` reject non-digits and overflow instead of producing garbage (e.g. "1e3"); `FixDecoder.poll` no longer reads bytes past the filled length.
- FIX: `FixSession.send` writes the store and the socket under one sync per call; `sendBatch` shares one disk force across several messages, and inbound sequence numbers are no longer written synchronously per message.
- WebSocketFeed: close and error callbacks of one connection schedule at most one reconnect, the previous socket is aborted first, and callbacks from a superseded socket are ignored, so two sockets can no longer publish into the single-producer bus.
- WebSocketFeed and BinaryMarketDataClient: a publish into a full bus ring is retried for a bounded time, then the tick is dropped, counted (`droppedTicks`) and a gap flag is raised (`consumeGap`); a reconnect also raises it.
- WebSocketFeed: symbols can be registered up front (`withSymbols`); unknown or over-capacity symbols are dropped and counted instead of throwing inside the WebSocket callback and cycling the connection.
- WebSocketFeed: `close()` waits up to two seconds for the close handshake before aborting.
- ITCH: `L3BookBuilder.onMessage` checks that the whole message is inside the buffer before decoding; a short datagram is skipped and counted (`truncatedCount`) instead of throwing on the feed thread.
- ITCH: Order Executed With Price (C) now removes the executed shares from the book (previously ignored, leaving phantom liquidity) and records the print.
- SBE: 64-bit fields of the order, trade and quote layouts are now 8-byte aligned (the order layout had them at offsets 4 mod 8 while documenting them as aligned).
- BinaryOrderPublisher no longer blocks the venue thread or throws from `onOrder` on a slow or failed peer.
- Checkpoint: the temporary file is forced to the device before the rename and the directory is forced after it, so a power loss cannot leave an empty or partial checkpoint.
- AsyncTickCapture and TickCapture: a writer failure is kept and surfaced (`isHealthy`, `failure`, rethrown by `close`) instead of killing the writer thread silently or throwing into the bus consumer.
- QFLT replay: a truncated or corrupt file now reports how many ticks were delivered (`TruncatedTickFileException.ticksDelivered`).

### Changed
- FIX: `FixSessionStore` gained `recordSent`, `sync`, `firstRetainedSeq` and `reset` (default methods); administrative messages are journalled too, and the next outgoing sequence number is recovered from the journal. The on-disk format changed (`messages.journal`, `session.seq`); a version-1 directory (`seqnums.dat`, `messages.dat`) is migrated on first open.
- FIX: `FixSession.Config` has a seventh component, `sendingTimeToleranceSeconds` (existing constructors keep working; `withSendingTimeTolerance`, 0 disables).
- FIX: `ExecutionReport` has a `possDup` component (true for PossDupFlag or PossResend); the 12-argument constructor remains.
- FIX: `FixMessage.Builder.field(int, String)` refuses a value containing SOH; use `dataField`.
- FIX: the in-memory session store default keeps the last 100 000 application messages instead of all of them.
- SBE schema version 2: every message starts with the standard 8-byte SBE header (blockLength, templateId, schemaId, version), prices and sizes are int64 mantissas with an int8 exponent (`Decimal64`), and the codecs' `encode` methods take the exponent. `MESSAGE_TYPE`/`typeAt` are replaced by `TEMPLATE_ID`/`MessageHeader`; `BLOCK_LENGTH` is now the body length and `ENCODED_LENGTH` the full message. Readers skip unknown templates by block length, stop on a foreign schema id, and still decode version-1 frames.
- BinaryOrderPublisher: non-blocking writes through an outbound buffer with a circuit breaker (buffer full, stall timeout, write failure, off-grid price); the constructor now declares `IOException`; prices go out on a per-symbol `10^exponent` grid (default 1e-8).
- QFLT tick files are written as version 2: header with clock domain (`TickFileWriter.ClockDomain`) and an epoch/monotonic anchor, records grouped in CRC-32C blocks, file forced on close. Version-1 files are still read.
- Tests that measured allocation now require exactly zero bytes (smallest of five runs) instead of under 100 KB.
- FIX session tests that slept or depended on the wall clock were replaced by deterministic scenarios on a manual clock.

### Added
- `FixClock` and `FixSession.overStream(...)`: a thread-free session over any transport, driven by `onBytes` and `onTimer`, for custom transports and deterministic tests.
- `FixSession.Listener` callbacks: `onLogonRequest`, `onOrderCancelReject` (35=9), `onBusinessMessageReject` (35=j), `onGarbledMessage`, `onResendUnavailable`.
- `FixExecReportView`: ExecID, OrderID, PossDupFlag, PossResend, OrdRejReason, Text and AvgPx, plus `ExecIdDeduplicator` to drop fills replayed after a resend.
- `FixOrderEncoder.encodeCancel` (35=F) and `encodeReplace` (35=G), garbage-free.
- ITCH: System Event (S), Stock Trading Action (H, `L3BookBuilder.tradingState`/`isTrading`), Cross Trade (Q) and Broken Trade (B) decoding and encoding; wire lengths for every ITCH 5.0 message; `ItchCodec.complete`; `L3BookBuilder.clear()` for snapshot rebuilds.
- `feed.MoldUdp64Receiver`: MoldUDP64 framing with sequence gap detection, retransmit requests and a snapshot fallback.
- `sbe.MessageHeader`, `sbe.Decimal64`.
- `TickFileReader.info` (version, clock domain, anchor) and `FileInfo.toEpochNanos`.
