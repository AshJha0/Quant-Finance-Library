package com.quantfinlib.fix;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32C;

/**
 * Durable {@link FixSessionStore}: an append-only journal of every outbound
 * message plus a small sequence page, in one directory.
 *
 * <h2>Files</h2>
 * <ul>
 *   <li>{@code messages.journal}: 16-byte header (magic, base sequence
 *       number) followed by records
 *       {@code [int len][long seq][byte app][short typeLen][type][short timeLen][time][int bodyLen][body][int crc32c]},
 *       where {@code len} counts the bytes after itself and the CRC-32C
 *       covers everything from {@code seq} to the end of {@code body}.
 *       Administrative messages are recorded with an empty body so the
 *       outgoing counter is recovered from the same stream as the messages:
 *       next outgoing = last record's seq + 1.</li>
 *   <li>{@code session.seq}: a 64-byte page with two
 *       alternating slots {@code (generation, expectedIncoming, outgoingFloor, crc)};
 *       the valid slot with the higher generation wins, so a write torn by
 *       power loss falls back to the previous value.</li>
 * </ul>
 *
 * <h2>Durability</h2>
 * {@link #recordSent} appends without forcing; {@link #sync()} calls
 * {@code FileChannel.force(false)} once for everything appended since the last
 * sync. The session calls {@code sync()} before the bytes reach the socket,
 * so a message is on disk before the peer can have seen its sequence number.
 * Several messages sent through {@link FixSession#sendBatch} share one force
 * (group commit). The incoming sequence number is written to the sequence page
 * with a positional write and no force: it survives a process crash (the page
 * cache belongs to the
 * kernel) and, after power loss, may lag, in which case the peer's messages
 * are re-requested and arrive again as PossDup replays (at-least-once; dedupe
 * fills on ExecID, see {@link FixExecReportView.ExecIdDeduplicator}).
 *
 * <h2>Recovery</h2>
 * Opening scans the journal and truncates at the first record that is short,
 * fails its CRC or goes backwards in sequence (a write torn by a crash);
 * {@link #truncatedBytes()} reports how much was dropped.
 *
 * <h2>Bounds</h2>
 * At most {@code maxRetained} records are kept: when the journal holds twice
 * that, it is compacted to the newest {@code maxRetained} by writing a new
 * file, forcing it, atomically replacing the old one and forcing the
 * directory. {@link #reset()} (ResetSeqNumFlag) rolls the journal to empty.
 * Only an index (seq to file offset) of application messages is kept in
 * memory; bodies are read from the file on resend.
 *
 * <h2>Migration</h2>
 * A directory written by the v1 store ({@code seqnums.dat} and
 * {@code messages.dat}) is imported on first open and the old files are
 * renamed with a {@code .migrated} suffix.
 */
public final class FileSessionStore implements FixSessionStore, AutoCloseable {

    private static final long JOURNAL_MAGIC = 0x5146_4958_4A52_4E31L; // "QFIXJRN1"
    private static final int HEADER_BYTES = 16;
    private static final int MIN_RECORD = 8 + 1 + 2 + 2 + 4 + 4;
    private static final int MAX_RECORD = 16 << 20;
    private static final int SLOT_BYTES = 32;
    private static final int DEFAULT_MAX_RETAINED = 1_000_000;

    private final Path directory;
    private final Path journalPath;
    private final int maxRetained;
    private final FileChannel seqChannel;
    private final ByteBuffer seqPage = ByteBuffer.allocate(2 * SLOT_BYTES);
    private FileChannel journal;
    private long journalEnd;
    private long baseSeq;
    private long lastSeq;              // last journal record's seq, baseSeq - 1 when empty
    private long recordCount;
    private long outgoingFloor = 1;
    private long expectedIncoming = 1;
    private long generation;
    private boolean dirty;
    private boolean closed;
    private final long truncatedBytes;
    private final Map<Long, Long> appOffsets = new HashMap<>();

    /** Opens (or creates) a store keeping up to one million records. */
    public FileSessionStore(Path directory) throws IOException {
        this(directory, DEFAULT_MAX_RETAINED);
    }

    /** Opens (or creates) a store keeping up to {@code maxRetained} records. */
    public FileSessionStore(Path directory, int maxRetained) throws IOException {
        if (maxRetained < 1) {
            throw new IllegalArgumentException("maxRetained must be >= 1");
        }
        this.directory = directory;
        this.maxRetained = maxRetained;
        Files.createDirectories(directory);
        this.journalPath = directory.resolve("messages.journal");
        Path seqPath = directory.resolve("session.seq");
        boolean freshSeq = !Files.exists(seqPath);

        this.seqChannel = FileChannel.open(seqPath, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        if (!freshSeq) {
            while (seqPage.hasRemaining() && seqChannel.read(seqPage, seqPage.position()) > 0) {
                // a short (torn) page leaves zeros, which fail the slot CRC
            }
            readSeqPage();
        }

        boolean freshJournal = !Files.exists(journalPath) || Files.size(journalPath) == 0;
        this.journal = FileChannel.open(journalPath, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        if (freshJournal) {
            writeHeader(journal, 1);
            journal.force(true);
            baseSeq = 1;
            lastSeq = 0;
            journalEnd = HEADER_BYTES;
            truncatedBytes = 0;
        } else {
            truncatedBytes = recover();
        }
        if (freshSeq && freshJournal) {
            migrateLegacy();
        }
        writeSeqPage();
    }

    // ------------------------------------------------------------------
    // FixSessionStore
    // ------------------------------------------------------------------

    @Override
    public synchronized long nextOutgoingSeq() {
        return Math.max(lastSeq + 1, outgoingFloor);
    }

    @Override
    public synchronized long expectedIncomingSeq() {
        return expectedIncoming;
    }

    @Override
    public synchronized void saveOutgoingSeq(long nextSeq) {
        try {
            if (nextSeq <= lastSeq) {
                roll(nextSeq);
            }
            outgoingFloor = nextSeq;
            writeSeqPage();
            seqChannel.force(false);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist FIX outgoing sequence", e);
        }
    }

    @Override
    public synchronized void saveIncomingSeq(long expectedSeq) {
        expectedIncoming = expectedSeq;
        writeSeqPage();
    }

    @Override
    public synchronized void storeMessage(long seq, StoredMessage message) {
        append(seq, message, true);
        sync();
    }

    @Override
    public synchronized void recordSent(long seq, StoredMessage message, boolean application) {
        append(seq, message, application);
    }

    @Override
    public synchronized void sync() {
        if (!dirty) {
            return;
        }
        try {
            journal.force(false);
            dirty = false;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to force FIX journal", e);
        }
    }

    @Override
    public synchronized StoredMessage retrieve(long seq) {
        Long offset = appOffsets.get(seq);
        if (offset == null) {
            return null;
        }
        try {
            ByteBuffer lenBuf = ByteBuffer.allocate(4);
            readFully(journal, lenBuf, offset);
            int len = lenBuf.getInt(0);
            ByteBuffer rec = ByteBuffer.allocate(len);
            readFully(journal, rec, offset + 4);
            return decode(rec.flip()).message;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read FIX message " + seq, e);
        }
    }

    @Override
    public synchronized long firstRetainedSeq() {
        return baseSeq;
    }

    @Override
    public synchronized void reset() {
        try {
            roll(1);
            outgoingFloor = 1;
            expectedIncoming = 1;
            writeSeqPage();
            seqChannel.force(false);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to reset FIX store", e);
        }
    }

    /** Bytes dropped from a torn or corrupt journal tail when the store was opened. */
    public long truncatedBytes() {
        return truncatedBytes;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            journal.force(false);
            seqChannel.force(false);
        } finally {
            journal.close();
            seqChannel.close();
        }
    }

    // ------------------------------------------------------------------
    // Journal
    // ------------------------------------------------------------------

    private void append(long seq, StoredMessage message, boolean application) {
        try {
            if (seq <= lastSeq) {
                // A sequence number reused below the journal tail (a hard
                // reset): the history above it is no longer valid.
                roll(seq);
            }
            ByteBuffer rec = encode(seq, message, application);
            long offset = journalEnd;
            writeFully(journal, rec, offset);
            journalEnd += rec.capacity();
            dirty = true;
            lastSeq = seq;
            recordCount++;
            if (application) {
                appOffsets.put(seq, offset);
            }
            if (recordCount > 2L * maxRetained) {
                sync();
                compact();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist FIX message " + seq, e);
        }
    }

    private static ByteBuffer encode(long seq, StoredMessage m, boolean application) {
        byte[] type = m.msgType().getBytes(StandardCharsets.ISO_8859_1);
        byte[] time = (m.sendingTime() == null ? "" : m.sendingTime())
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] body = application ? m.body().getBytes(StandardCharsets.ISO_8859_1) : new byte[0];
        int len = 8 + 1 + 2 + type.length + 2 + time.length + 4 + body.length + 4;
        ByteBuffer b = ByteBuffer.allocate(4 + len);
        b.putInt(len).putLong(seq).put((byte) (application ? 1 : 0))
                .putShort((short) type.length).put(type)
                .putShort((short) time.length).put(time)
                .putInt(body.length).put(body);
        CRC32C crc = new CRC32C();
        crc.update(b.array(), 4, len - 4);
        b.putInt((int) crc.getValue());
        return b.flip();
    }

    private record Decoded(long seq, boolean application, StoredMessage message) {
    }

    /** Decodes one record payload (after the length); null if inconsistent. */
    private static Decoded decode(ByteBuffer rec) {
        int len = rec.remaining();
        if (len < MIN_RECORD) {
            return null;
        }
        CRC32C crc = new CRC32C();
        crc.update(rec.array(), rec.arrayOffset() + rec.position(), len - 4);
        if ((int) crc.getValue() != rec.getInt(rec.position() + len - 4)) {
            return null;
        }
        long seq = rec.getLong();
        boolean app = rec.get() == 1;
        int typeLen = rec.getShort() & 0xFFFF;
        if (typeLen > rec.remaining()) {
            return null;
        }
        byte[] type = new byte[typeLen];
        rec.get(type);
        int timeLen = rec.getShort() & 0xFFFF;
        if (timeLen > rec.remaining()) {
            return null;
        }
        byte[] time = new byte[timeLen];
        rec.get(time);
        int bodyLen = rec.getInt();
        if (bodyLen < 0 || bodyLen != rec.remaining() - 4) {
            return null;
        }
        byte[] body = new byte[bodyLen];
        rec.get(body);
        return new Decoded(seq, app, new StoredMessage(
                new String(type, StandardCharsets.ISO_8859_1),
                new String(body, StandardCharsets.ISO_8859_1),
                new String(time, StandardCharsets.ISO_8859_1)));
    }

    /** Scans the journal, truncating at the first bad record; returns bytes dropped. */
    private long recover() throws IOException {
        long size = journal.size();
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        if (size < HEADER_BYTES) {
            // Torn before the header was complete: nothing was ever recorded.
            journal.truncate(0);
            writeHeader(journal, 1);
            journal.force(true);
            baseSeq = 1;
            lastSeq = 0;
            journalEnd = HEADER_BYTES;
            return size;
        }
        readFully(journal, header, 0);
        if (header.getLong(0) != JOURNAL_MAGIC) {
            throw new IOException(journalPath + " is not a FIX journal");
        }
        baseSeq = header.getLong(8);
        lastSeq = baseSeq - 1;
        long offset = HEADER_BYTES;
        ByteBuffer lenBuf = ByteBuffer.allocate(4);
        while (offset + 4 <= size) {
            lenBuf.clear();
            readFully(journal, lenBuf, offset);
            int len = lenBuf.getInt(0);
            if (len < MIN_RECORD || len > MAX_RECORD || offset + 4 + len > size) {
                break;
            }
            ByteBuffer rec = ByteBuffer.allocate(len);
            readFully(journal, rec, offset + 4);
            Decoded d = decode(rec.flip());
            if (d == null || d.seq <= lastSeq) {
                break;
            }
            lastSeq = d.seq;
            recordCount++;
            if (d.application) {
                appOffsets.put(d.seq, offset);
            }
            offset += 4 + len;
        }
        journalEnd = offset;
        long dropped = size - offset;
        if (dropped > 0) {
            journal.truncate(offset);
            journal.force(true);
        }
        return dropped;
    }

    /** Replaces the journal by an empty one starting at {@code newBase}. */
    private void roll(long newBase) throws IOException {
        replaceJournal(newBase, -1);
    }

    /** Keeps the newest {@code maxRetained} records. */
    private void compact() throws IOException {
        replaceJournal(-1, lastSeq - maxRetained + 1);
    }

    /**
     * Writes a new journal (base {@code newBase}, or keeping records with
     * seq at or above {@code keepFrom}), forces it, atomically moves it over
     * the old one and forces the directory.
     */
    private void replaceJournal(long newBase, long keepFrom) throws IOException {
        Path tmp = directory.resolve("messages.journal.tmp");
        Map<Long, Long> newOffsets = new HashMap<>();
        long newLast;
        long count = 0;
        long end;
        long base;
        try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            if (keepFrom < 0) {
                base = newBase;
                newLast = newBase - 1;
                writeHeader(out, base);
                end = HEADER_BYTES;
            } else {
                base = Math.max(baseSeq, keepFrom);
                newLast = base - 1;
                writeHeader(out, base);
                end = HEADER_BYTES;
                long offset = HEADER_BYTES;
                ByteBuffer lenBuf = ByteBuffer.allocate(4);
                while (offset < journalEnd) {
                    lenBuf.clear();
                    readFully(journal, lenBuf, offset);
                    int len = lenBuf.getInt(0);
                    ByteBuffer rec = ByteBuffer.allocate(4 + len);
                    readFully(journal, rec, offset);
                    long seq = rec.getLong(4);
                    if (seq >= base) {
                        if (rec.get(12) == 1) {
                            newOffsets.put(seq, end);
                        }
                        rec.rewind();
                        writeFully(out, rec, end);
                        end += 4 + len;
                        newLast = seq;
                        count++;
                    }
                    offset += 4 + len;
                }
            }
            out.force(true);
        }
        journal.close();
        try {
            Files.move(tmp, journalPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, journalPath, StandardCopyOption.REPLACE_EXISTING);
        }
        forceDirectory(directory);
        journal = FileChannel.open(journalPath, StandardOpenOption.READ, StandardOpenOption.WRITE);
        baseSeq = base;
        lastSeq = newLast;
        journalEnd = end;
        recordCount = count;
        appOffsets.clear();
        appOffsets.putAll(newOffsets);
        dirty = false;
    }

    private static void writeHeader(FileChannel ch, long base) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(HEADER_BYTES).putLong(JOURNAL_MAGIC).putLong(base);
        writeFully(ch, h.flip(), 0);
    }

    /**
     * Forces a directory entry change (rename) to disk. Opening a directory
     * for reading works on Linux and macOS; Windows refuses it and makes the
     * rename durable through NTFS metadata journalling instead, so the
     * failure is ignored there.
     */
    static void forceDirectory(Path dir) {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // not supported on this platform
        }
    }

    private static void readFully(FileChannel ch, ByteBuffer buf, long position) throws IOException {
        long p = position;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, p);
            if (n < 0) {
                throw new EOFException("journal ended at " + p);
            }
            p += n;
        }
    }

    private static void writeFully(FileChannel ch, ByteBuffer buf, long position) throws IOException {
        long p = position;
        while (buf.hasRemaining()) {
            p += ch.write(buf, p);
        }
    }

    // ------------------------------------------------------------------
    // Sequence page
    // ------------------------------------------------------------------

    private void readSeqPage() {
        long bestGen = -1;
        for (int slot = 0; slot < 2; slot++) {
            int at = slot * SLOT_BYTES;
            long gen = seqPage.getLong(at);
            long incoming = seqPage.getLong(at + 8);
            long floor = seqPage.getLong(at + 16);
            int crc = seqPage.getInt(at + 24);
            if (crc == slotCrc(gen, incoming, floor) && gen > bestGen && incoming >= 1 && floor >= 1) {
                bestGen = gen;
                expectedIncoming = incoming;
                outgoingFloor = floor;
            }
        }
        generation = Math.max(bestGen, 0);
    }

    private void writeSeqPage() {
        generation++;
        int at = (int) (generation & 1) * SLOT_BYTES;
        seqPage.putLong(at, generation);
        seqPage.putLong(at + 8, expectedIncoming);
        seqPage.putLong(at + 16, outgoingFloor);
        seqPage.putInt(at + 24, slotCrc(generation, expectedIncoming, outgoingFloor));
        try {
            writeFully(seqChannel, seqPage.slice(at, SLOT_BYTES), at);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write FIX sequence page", e);
        }
    }

    private static int slotCrc(long gen, long incoming, long floor) {
        ByteBuffer b = ByteBuffer.allocate(24).putLong(gen).putLong(incoming).putLong(floor);
        CRC32C crc = new CRC32C();
        crc.update(b.array());
        return (int) crc.getValue();
    }

    // ------------------------------------------------------------------
    // v1 migration
    // ------------------------------------------------------------------

    private void migrateLegacy() throws IOException {
        Path legacySeq = directory.resolve("seqnums.dat");
        Path legacyMessages = directory.resolve("messages.dat");
        long legacyOutgoing = 1;
        if (Files.exists(legacySeq) && Files.size(legacySeq) >= 16) {
            try (DataInputStream in = new DataInputStream(Files.newInputStream(legacySeq))) {
                legacyOutgoing = in.readLong();
                expectedIncoming = in.readLong();
            }
        }
        if (Files.exists(legacyMessages)) {
            try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(
                    Files.newInputStream(legacyMessages)))) {
                java.util.TreeMap<Long, StoredMessage> imported = new java.util.TreeMap<>();
                try {
                    while (true) {
                        long seq = in.readLong();
                        String type = in.readUTF();
                        String time = in.readUTF();
                        byte[] body = new byte[in.readInt()];
                        in.readFully(body);
                        imported.put(seq, new StoredMessage(type,
                                new String(body, StandardCharsets.ISO_8859_1), time));
                    }
                } catch (EOFException tornOrDone) {
                    // v1 had no record framing; a torn tail simply ends the import
                }
                for (Map.Entry<Long, StoredMessage> e : imported.entrySet()) {
                    if (e.getKey() < legacyOutgoing) {
                        append(e.getKey(), e.getValue(), true);
                    }
                }
                sync();
            }
            Files.move(legacyMessages, directory.resolve("messages.dat.migrated"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        outgoingFloor = Math.max(outgoingFloor, legacyOutgoing);
        if (Files.exists(legacySeq)) {
            writeSeqPage();
            seqChannel.force(false);
            Files.move(legacySeq, directory.resolve("seqnums.dat.migrated"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
