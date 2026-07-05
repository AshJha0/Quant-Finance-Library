package com.quantfinlib.sbe;

import com.quantfinlib.marketdata.HftMarketDataBus;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;

/**
 * Binary market-data adapter: decodes {@link TradeFlyweight} frames from a
 * channel straight into the {@link HftMarketDataBus} — the professional-grade
 * counterpart of the text {@code feed.WebSocketFeed}. The read loop reuses
 * one direct buffer and one flyweight: <b>zero allocation per message</b>,
 * partial frames handled by compacting, so TCP segmentation can never split
 * a decode.
 *
 * <p>Symbol numbering is part of the wire contract: both ends must agree on
 * the dense symbol ids (register the same symbols in the same order on the
 * bus). This is exactly how binary exchange feeds work — ids are assigned in
 * the session/reference-data channel, not repeated per tick.</p>
 *
 * <p>Single reader thread per client (the bus's single-producer contract).</p>
 */
public final class BinaryMarketDataClient implements AutoCloseable {

    private final ReadableByteChannel channel;
    private final HftMarketDataBus bus;
    private final ByteBuffer buffer;
    private final TradeFlyweight trade = new TradeFlyweight();
    private final Thread readerThread;
    private volatile boolean running = true;
    private volatile long messagesDecoded;
    private volatile String failureReason;

    public BinaryMarketDataClient(ReadableByteChannel channel, HftMarketDataBus bus) {
        this(channel, bus, 1 << 16);
    }

    public BinaryMarketDataClient(ReadableByteChannel channel, HftMarketDataBus bus,
                                  int bufferBytes) {
        this.channel = channel;
        this.bus = bus;
        this.buffer = ByteBuffer.allocateDirect(bufferBytes).order(ByteOrder.LITTLE_ENDIAN);
        this.readerThread = new Thread(this::readLoop, "binary-md-client");
        readerThread.setDaemon(true);
    }

    public void start() {
        readerThread.start();
    }

    public long messagesDecoded() {
        return messagesDecoded;
    }

    /** Non-null once the reader stopped on a protocol or I/O error. */
    public String failureReason() {
        return failureReason;
    }

    @Override
    public void close() throws IOException {
        running = false;
        channel.close();
    }

    private void readLoop() {
        try {
            while (running) {
                int read = channel.read(buffer);
                if (read < 0) {
                    return;   // peer closed cleanly
                }
                buffer.flip();
                decodeFrames();
                buffer.compact();   // keep any partial trailing frame
            }
        } catch (IOException e) {
            if (running) {
                failureReason = "read failed: " + e.getMessage();
            }
        } catch (RuntimeException e) {
            failureReason = e.getMessage();
        }
    }

    private void decodeFrames() {
        while (buffer.remaining() >= 4) {
            int position = buffer.position();
            int type = TradeFlyweight.typeAt(buffer, position);
            if (type != TradeFlyweight.MESSAGE_TYPE) {
                throw new IllegalStateException("unknown message type " + type
                        + " on market-data stream");
            }
            if (buffer.remaining() < TradeFlyweight.BLOCK_LENGTH) {
                return;   // partial frame: wait for more bytes
            }
            trade.wrap(buffer, position);
            bus.publish(trade.symbolId(), trade.price(), trade.size(), trade.timestampNanos());
            messagesDecoded = messagesDecoded + 1;
            buffer.position(position + TradeFlyweight.BLOCK_LENGTH);
        }
    }
}
