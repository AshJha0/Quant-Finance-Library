package com.quantfinlib.fix;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Durable {@link FixSessionStore} backed by two files in a directory:
 * {@code seqnums.dat} (16 bytes, synchronous writes) and an append-only
 * {@code messages.dat} replayed into memory on open. Reopening the store
 * after a process restart resumes the FIX session with sequence-number
 * continuation and a fully serviceable resend history.
 */
public final class FileSessionStore implements FixSessionStore, AutoCloseable {

    private final RandomAccessFile seqFile;
    private final DataOutputStream messageAppender;
    private final Map<Long, StoredMessage> messages = new HashMap<>();
    private long nextOutgoing = 1;
    private long expectedIncoming = 1;
    private boolean closed;

    public FileSessionStore(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path seqPath = directory.resolve("seqnums.dat");
        Path messagesPath = directory.resolve("messages.dat");

        this.seqFile = new RandomAccessFile(seqPath.toFile(), "rwd");
        if (seqFile.length() >= 16) {
            seqFile.seek(0);
            nextOutgoing = seqFile.readLong();
            expectedIncoming = seqFile.readLong();
        } else {
            persistSeqs();
        }

        if (Files.exists(messagesPath)) {
            try (DataInputStream in = new DataInputStream(
                    new java.io.BufferedInputStream(Files.newInputStream(messagesPath)))) {
                while (true) {
                    long seq;
                    try {
                        seq = in.readLong();
                    } catch (EOFException eof) {
                        break;
                    }
                    String msgType = in.readUTF();
                    String sendingTime = in.readUTF();
                    byte[] body = new byte[in.readInt()];
                    in.readFully(body);
                    messages.put(seq, new StoredMessage(msgType,
                            new String(body, StandardCharsets.ISO_8859_1), sendingTime));
                }
            }
        }
        this.messageAppender = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(messagesPath,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND)));
    }

    @Override
    public synchronized long nextOutgoingSeq() {
        return nextOutgoing;
    }

    @Override
    public synchronized long expectedIncomingSeq() {
        return expectedIncoming;
    }

    @Override
    public synchronized void saveOutgoingSeq(long nextSeq) {
        this.nextOutgoing = nextSeq;
        persistSeqs();
    }

    @Override
    public synchronized void saveIncomingSeq(long expectedSeq) {
        this.expectedIncoming = expectedSeq;
        persistSeqs();
    }

    @Override
    public synchronized void storeMessage(long seq, StoredMessage message) {
        messages.put(seq, message);
        try {
            messageAppender.writeLong(seq);
            messageAppender.writeUTF(message.msgType());
            messageAppender.writeUTF(message.sendingTime());
            byte[] body = message.body().getBytes(StandardCharsets.ISO_8859_1);
            messageAppender.writeInt(body.length);
            messageAppender.write(body);
            messageAppender.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist FIX message " + seq, e);
        }
    }

    @Override
    public synchronized StoredMessage retrieve(long seq) {
        return messages.get(seq);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        messageAppender.close();
        seqFile.close();
    }

    private void persistSeqs() {
        try {
            seqFile.seek(0);
            seqFile.writeLong(nextOutgoing);
            seqFile.writeLong(expectedIncoming);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist FIX sequence numbers", e);
        }
    }
}
