/**
 * Binary wire codecs in the FIX Simple Binary Encoding (SBE 1.0) style and
 * their channel adapters. Every message starts with the standard 8-byte
 * {@link com.quantfinlib.sbe.MessageHeader} (blockLength, templateId,
 * schemaId, version); prices and sizes are integer
 * {@link com.quantfinlib.sbe.Decimal64} mantissas with an exponent; 64-bit
 * fields are 8-byte aligned. {@link com.quantfinlib.sbe.TradeFlyweight},
 * {@link com.quantfinlib.sbe.OrderFlyweight} and
 * {@link com.quantfinlib.sbe.QuoteFlyweight} encode and decode at fixed
 * offsets without allocation;
 * {@link com.quantfinlib.sbe.BinaryMarketDataClient} feeds the
 * {@code HftMarketDataBus} from a binary stream, and
 * {@link com.quantfinlib.sbe.BinaryOrderPublisher} (non-blocking, with a
 * circuit breaker) and {@link com.quantfinlib.sbe.BinaryOrderReceiver} carry
 * gateway orders. The readers still decode schema-version-1 frames (no
 * header, double prices). Symbol ids are part of the wire contract.
 *
 * <p>Since 3.0 this package is in the artifact
 * {@code quant-finance-library-lowlatency} (module {@code com.quantfinlib.lowlatency});
 * the package name did not change, so a 2.x user changes the dependency, not the
 * imports. See docs/MIGRATION_3.0.md.</p>
 */
package com.quantfinlib.sbe;
