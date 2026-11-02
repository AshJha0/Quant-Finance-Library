package com.quantfinlib.reporting;

/**
 * Destination for finished reports: a file writer, a queue to an approved
 * reporting mechanism (ARM), or an in-memory list in tests. The library ships
 * no network submission; that relationship is the firm's.
 */
@FunctionalInterface
public interface TransactionReportSink {
    /** Accepts one report. Implementations decide whether to reject invalid ones. */
    void submit(TransactionReport report);
}
