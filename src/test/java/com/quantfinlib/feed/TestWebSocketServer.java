package com.quantfinlib.feed;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal RFC 6455 WebSocket server for tests: performs the HTTP upgrade
 * handshake, sends unmasked text frames to the client, records (unmasked)
 * text frames received from it, and can drop connections abruptly to
 * exercise reconnect logic. Accepts sequential connections.
 */
final class TestWebSocketServer implements AutoCloseable {

    private static final Pattern KEY = Pattern.compile("Sec-WebSocket-Key: (.+)\r");
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket server;
    private final Thread acceptThread;
    final List<String> receivedTexts = new CopyOnWriteArrayList<>();
    private final AtomicInteger connections = new AtomicInteger();
    private volatile Socket client;
    private volatile OutputStream out;
    private volatile boolean running = true;

    TestWebSocketServer() throws IOException {
        this.server = new ServerSocket(0);
        this.acceptThread = new Thread(this::acceptLoop, "test-ws-server");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    int port() {
        return server.getLocalPort();
    }

    int connectionCount() {
        return connections.get();
    }

    /** Sends one text frame to the current client. */
    synchronized void sendText(String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        OutputStream o = out;
        if (o == null) {
            throw new IOException("no client connected");
        }
        if (payload.length <= 125) {
            o.write(new byte[]{(byte) 0x81, (byte) payload.length});
        } else {
            o.write(new byte[]{(byte) 0x81, 126,
                    (byte) (payload.length >> 8), (byte) payload.length});
        }
        o.write(payload);
        o.flush();
    }

    /** Abruptly kills the current connection (no close frame). */
    void dropConnection() throws IOException {
        Socket c = client;
        if (c != null) {
            c.close();
        }
    }

    void awaitConnections(int n, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000;
        while (connections.get() < n) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("expected " + n + " connections, got " + connections.get());
            }
            Thread.sleep(10);
        }
    }

    @Override
    public void close() throws IOException {
        running = false;
        server.close();
        dropConnection();
    }

    // ------------------------------------------------------------------

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                handshake(socket);
                client = socket;
                out = socket.getOutputStream();
                connections.incrementAndGet();
                Thread drainer = new Thread(() -> drain(socket), "test-ws-drain");
                drainer.setDaemon(true);
                drainer.start();
            } catch (Exception e) {
                if (running) {
                    continue;
                }
                return;
            }
        }
    }

    private static void handshake(Socket socket) throws Exception {
        InputStream in = socket.getInputStream();
        StringBuilder request = new StringBuilder();
        int prev = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("client closed during handshake");
            }
            request.append((char) b);
            if (b == '\n' && prev == '\n') {
                // blank line reached (\r\n\r\n leaves consecutive \n after strip)
            }
            if (request.length() >= 4
                    && request.substring(request.length() - 4).equals("\r\n\r\n")) {
                break;
            }
            prev = b;
        }
        Matcher m = KEY.matcher(request);
        if (!m.find()) {
            throw new IOException("no Sec-WebSocket-Key in handshake");
        }
        String accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                        .digest((m.group(1).trim() + GUID).getBytes(StandardCharsets.ISO_8859_1)));
        socket.getOutputStream().write((
                "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
    }

    /** Reads client frames: records text, honors close, ignores the rest. */
    private void drain(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            while (true) {
                int b1 = in.read();
                if (b1 < 0) {
                    return;
                }
                int b2 = in.read();
                boolean masked = (b2 & 0x80) != 0;
                long length = b2 & 0x7F;
                if (length == 126) {
                    length = ((long) in.read() << 8) | in.read();
                } else if (length == 127) {
                    length = 0;
                    for (int i = 0; i < 8; i++) {
                        length = (length << 8) | in.read();
                    }
                }
                byte[] mask = new byte[4];
                if (masked) {
                    for (int i = 0; i < 4; i++) {
                        mask[i] = (byte) in.read();
                    }
                }
                byte[] payload = new byte[(int) length];
                int read = 0;
                while (read < length) {
                    int n = in.read(payload, read, (int) length - read);
                    if (n < 0) {
                        return;
                    }
                    read += n;
                }
                if (masked) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i & 3];
                    }
                }
                int opcode = b1 & 0x0F;
                if (opcode == 1) {
                    receivedTexts.add(new String(payload, StandardCharsets.UTF_8));
                } else if (opcode == 8) {
                    socket.close();
                    return;
                }
            }
        } catch (IOException e) {
            // connection dropped: fine
        }
    }
}
