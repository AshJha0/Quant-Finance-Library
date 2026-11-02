/**
 * Zero-dependency FIX engine: FIX 4.4 by default, FIX 4.2 and FIX 5.0 SP2
 * over FIXT.1.1 through {@link com.quantfinlib.fix.FixVersion}, with
 * repeating groups defined and validated by
 * {@link com.quantfinlib.fix.FixDictionary}.
 * {@link com.quantfinlib.fix.FixMessage} (validated codec: BodyLength and
 * CheckSum framing, length-prefixed data fields, repeated tags kept in order)
 * and {@link com.quantfinlib.fix.FixSession} (initiator and acceptor): Logon
 * first with an acceptor-side {@code onLogonRequest} check, CompID and
 * SendingTime validation, negotiated HeartBtInt, TestRequest probing with
 * TestReqID matching, ResendRequest recovery (PossDup replays with
 * OrigSendingTime, SequenceReset-GapFill, duplicate suppression), session
 * Reject for malformed messages, Logout. Timing runs on an injectable
 * {@link com.quantfinlib.fix.FixClock}.
 *
 * <p>{@link com.quantfinlib.fix.FileSessionStore} is a CRC-checked journal
 * forced to disk before each message is sent, recovered past a torn tail,
 * and bounded by compaction, so a resend after a crash replays the real
 * orders.</p>
 *
 * <p>Garbage-free path (flyweights over framed bytes, scaled-long prices):
 * {@link com.quantfinlib.fix.FixOrderEncoder} (new, cancel and replace out),
 * {@link com.quantfinlib.fix.FixExecReportView} (fills in, with ExecID
 * de-duplication) and {@link com.quantfinlib.fix.FixMarketDataView} (35=W/X
 * quotes in).</p>
 */
package com.quantfinlib.fix;
