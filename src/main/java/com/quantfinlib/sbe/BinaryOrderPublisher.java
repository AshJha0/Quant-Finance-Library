package com.quantfinlib.sbe;

import com.quantfinlib.orderbook.Side;
import com.quantfinlib.trading.OrderListener;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SelectableChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Binary order-entry adapter: attach to {@code HftOrderGateway} as its
 * {@link OrderListener} and every accepted order is encoded as an
 * {@link OrderFlyweight} and written to the venue channel, with no
 * allocation or string formatting per order.
 *
 * <h2>Never blocks the venue thread</h2>
 * A {@link SelectableChannel} (socket, pipe) is switched to non-blocking
 * mode. Orders are encoded into an outbound buffer and written as far as the
 * peer's TCP window allows; the rest stays queued and goes out on the next
 * order or {@link #flush()} (call it from the venue thread's idle loop).
 * A circuit breaker trips, and from then on drops orders and counts them,
 * when
 * <ul>
 *   <li>the outbound buffer cannot take another order (peer not reading),</li>
 *   <li>queued bytes make no progress for the stall timeout, or</li>
 *   <li>a write fails, or a price is not on its symbol's grid.</li>
 * </ul>
 * The breaker listener is told once, with the reason, so the owner can pull
 * the kill switch or fail over. A non-selectable channel is written as is
 * and may block; give it a selectable channel for the guarantee above.
 *
 * <p>Prices travel as integer mantissas on a per-symbol {@code 10^exponent}
 * grid (default {@code 10^-8}); a NaN price is a market order. Single
 * threaded: the gateway's venue thread.</p>
 */
public final class BinaryOrderPublisher implements OrderListener, AutoCloseable {

    /** Default outbound buffer: 64 KiB, about 1300 orders. */
    public static final int DEFAULT_BUFFER_BYTES = 64 * 1024;
    /** Default stall timeout: 50 ms without write progress while bytes are queued. */
    public static final long DEFAULT_STALL_TIMEOUT_NANOS = 50_000_000L;
    /** Default price exponent: prices on a 1e-8 grid. */
    public static final int DEFAULT_PRICE_EXPONENT = -8;

    private final WritableByteChannel channel;
    private final ByteBuffer outbound;
    private final OrderFlyweight order = new OrderFlyweight();
    private final long stallTimeoutNanos;
    private final LongSupplier nanoClock;
    private int defaultPriceExponent = DEFAULT_PRICE_EXPONENT;
    private int[] priceExponents = new int[0];
    private Consumer<String> breakerListener = reason -> { };
    private long ordersSent;
    private long ordersDropped;
    private long lastProgressNanos;
    private volatile String tripReason;

    public BinaryOrderPublisher(WritableByteChannel channel) throws IOException {
        this(channel, DEFAULT_BUFFER_BYTES, DEFAULT_STALL_TIMEOUT_NANOS, System::nanoTime);
    }

    /**
     * @param outboundBytes     queued-bytes capacity
     * @param stallTimeoutNanos trip after this long without write progress
     * @param nanoClock         monotonic clock (injectable for tests)
     */
    public BinaryOrderPublisher(WritableByteChannel channel, int outboundBytes,
                                long stallTimeoutNanos, LongSupplier nanoClock)
            throws IOException {
        if (outboundBytes < OrderFlyweight.ENCODED_LENGTH) {
            throw new IllegalArgumentException("outbound buffer smaller than one order");
        }
        this.channel = channel;
        if (channel instanceof SelectableChannel selectable) {
            selectable.configureBlocking(false);
        }
        this.outbound = ByteBuffer.allocateDirect(outboundBytes).order(ByteOrder.LITTLE_ENDIAN);
        this.stallTimeoutNanos = stallTimeoutNanos;
        this.nanoClock = nanoClock;
        this.lastProgressNanos = nanoClock.getAsLong();
    }

    /** Price grid exponent for every symbol without its own. */
    public BinaryOrderPublisher withDefaultPriceExponent(int exponent) {
        Decimal64.pow10(exponent);
        this.defaultPriceExponent = exponent;
        return this;
    }

    /** Price grid exponent for one symbol (cold path, before trading). */
    public BinaryOrderPublisher withPriceExponent(int symbolId, int exponent) {
        Decimal64.pow10(exponent);
        if (symbolId >= priceExponents.length) {
            int old = priceExponents.length;
            priceExponents = Arrays.copyOf(priceExponents, symbolId + 1);
            Arrays.fill(priceExponents, old, priceExponents.length, Integer.MIN_VALUE);
        }
        priceExponents[symbolId] = exponent;
        return this;
    }

    /** Called once, on the venue thread, when the breaker trips. */
    public BinaryOrderPublisher withBreakerListener(Consumer<String> listener) {
        this.breakerListener = listener;
        return this;
    }

    @Override
    public void onOrder(long orderId, int symbolId, Side side, long quantity,
                        double price, long timestampNanos) {
        if (tripReason != null) {
            ordersDropped++;
            return;
        }
        flush();
        if (tripReason != null) {
            ordersDropped++;
            return;
        }
        if (outbound.remaining() < OrderFlyweight.ENCODED_LENGTH) {
            trip("outbound buffer full: venue is not reading");
            ordersDropped++;
            return;
        }
        int exponent = exponentFor(symbolId);
        long mantissa;
        try {
            mantissa = Decimal64.toMantissa(price, exponent);
        } catch (IllegalArgumentException e) {
            trip("order " + orderId + ": " + e.getMessage());
            ordersDropped++;
            return;
        }
        if (outbound.position() == 0) {
            lastProgressNanos = nanoClock.getAsLong();   // the stall clock starts with the queue
        }
        order.wrap(outbound, outbound.position())
                .encode(orderId, symbolId, side, quantity, mantissa, exponent, timestampNanos);
        outbound.position(outbound.position() + OrderFlyweight.ENCODED_LENGTH);
        ordersSent++;
        flush();
    }

    /**
     * Writes queued bytes as far as the channel accepts without blocking and
     * trips the breaker on a stall or a write failure.
     */
    public void flush() {
        if (outbound.position() == 0 || tripReason != null) {
            return;
        }
        long now = nanoClock.getAsLong();
        try {
            outbound.flip();
            int written = channel.write(outbound);
            outbound.compact();
            if (written > 0) {
                lastProgressNanos = now;
            } else if (now - lastProgressNanos > stallTimeoutNanos) {
                trip("venue stalled: " + outbound.position() + " bytes queued without progress");
            }
            if (outbound.position() == 0) {
                lastProgressNanos = now;
            }
        } catch (IOException e) {
            trip("write failed: " + e.getMessage());
        }
    }

    private int exponentFor(int symbolId) {
        if (symbolId >= 0 && symbolId < priceExponents.length
                && priceExponents[symbolId] != Integer.MIN_VALUE) {
            return priceExponents[symbolId];
        }
        return defaultPriceExponent;
    }

    private void trip(String reason) {
        if (tripReason == null) {
            tripReason = reason;
            breakerListener.accept(reason);
        }
    }

    /** Orders encoded and queued or written. */
    public long ordersSent() {
        return ordersSent;
    }

    /** Orders dropped because the breaker was open or tripped on them. */
    public long ordersDropped() {
        return ordersDropped;
    }

    /** Bytes queued but not yet accepted by the channel. */
    public int pendingBytes() {
        return outbound.position();
    }

    /** True once the breaker has tripped. */
    public boolean isTripped() {
        return tripReason != null;
    }

    /** Why the breaker tripped, or null. */
    public String tripReason() {
        return tripReason;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
