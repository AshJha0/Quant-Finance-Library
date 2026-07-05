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
 */
package com.quantfinlib.fix;
