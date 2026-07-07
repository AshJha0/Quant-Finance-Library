/**
 * Zero-dependency FIX 4.4 engine.
 * {@link com.quantfinlib.fix.FixMessage} (validated codec: BodyLength +
 * CheckSum framing) and {@link com.quantfinlib.fix.FixSession} (initiator
 * and acceptor roles): Logon with optional Username/Password, heartbeats
 * with TestRequest probing, sequence tracking with full ResendRequest
 * recovery (PossDup replays, SequenceReset-GapFill, duplicate suppression),
 * session Reject handling, Logout handshake, and optional durable
 * {@link com.quantfinlib.fix.FileSessionStore} for seqnum continuation
 * across restarts. Application flow: NewOrderSingle, cancel/replace (35=F/G)
 * and typed {@link com.quantfinlib.fix.ExecutionReport}s.
 *
 * <p>Garbage-free hot path (flyweights over framed bytes, scaled-long
 * prices): {@link com.quantfinlib.fix.FixOrderEncoder} (orders out),
 * {@link com.quantfinlib.fix.FixExecReportView} (fills in) and
 * {@link com.quantfinlib.fix.FixMarketDataView} (35=W/X tiered quotes in)
 * — feed to order without touching a String or a double.</p>
 */
package com.quantfinlib.fix;
