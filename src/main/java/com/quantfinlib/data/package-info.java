/**
 * Data in, out, and preparation — the bridge between real-world files/feeds
 * and the analytics stack:
 * {@link com.quantfinlib.data.CsvBarLoader} (RFC-4180-tolerant CSV bars),
 * {@link com.quantfinlib.data.HttpBarFetcher} (CSV over HTTP),
 * {@link com.quantfinlib.data.TickFileWriter}/{@link com.quantfinlib.data.TickFileReader}
 * (QFLT binary tick format with as-fast-as-possible or paced replay),
 * {@link com.quantfinlib.data.TickCapture} (record the live bus for
 * deterministic replay), {@link com.quantfinlib.data.SeriesAligner}
 * (timestamp intersection / union+forward-fill for ragged multi-asset data)
 * and {@link com.quantfinlib.data.CorporateActions} (split/dividend
 * back-adjustment).
 */
package com.quantfinlib.data;
