package com.quantfinlib.fix;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSessionStoreTest {

    private static FixSessionStore.StoredMessage msg(String id) {
        return new FixSessionStore.StoredMessage("D", "11=" + id + "\u0001", "20260710-12:00:00.000");
    }

    @Test
    void outgoingSequenceIsRecoveredFromTheJournalNotFromASeparateCounter(@TempDir Path dir)
            throws Exception {
        try (FileSessionStore store = new FileSessionStore(dir)) {
            store.recordSent(1, new FixSessionStore.StoredMessage("A", "", "t"), false);
            store.recordSent(2, msg("a"), true);
            store.recordSent(3, msg("b"), true);
            store.sync();
        }
        // Even with the sequence page deleted, the journal says 3 was sent.
        Files.delete(dir.resolve("session.seq"));
        try (FileSessionStore reopened = new FileSessionStore(dir)) {
            assertEquals(4, reopened.nextOutgoingSeq());
            assertEquals("11=b\u0001", reopened.retrieve(3).body());
            assertNull(reopened.retrieve(1), "admin records are not replayable");
        }
    }

    @Test
    void everyTornTailIsCutBackToTheLastWholeRecord(@TempDir Path dir) throws Exception {
        try (FileSessionStore store = new FileSessionStore(dir)) {
            for (int i = 1; i <= 3; i++) {
                store.storeMessage(i, msg("m" + i));
            }
        }
        Path journal = dir.resolve("messages.journal");
        long whole = Files.size(journal);
        long lastRecord;
        try (FileSessionStore store = new FileSessionStore(dir)) {
            store.storeMessage(4, msg("m4"));
        }
        lastRecord = Files.size(journal) - whole;
        // Cut the fourth record at every possible byte: always recover 1..3.
        byte[] full = Files.readAllBytes(journal);
        for (long cut = 1; cut < lastRecord; cut++) {
            Files.write(journal, java.util.Arrays.copyOf(full, (int) (whole + cut)));
            try (FileSessionStore store = new FileSessionStore(dir)) {
                assertEquals(cut, store.truncatedBytes());
                assertEquals(4, store.nextOutgoingSeq());
                assertNotNull(store.retrieve(3));
                assertNull(store.retrieve(4));
            }
            assertEquals(whole, Files.size(journal));
        }
    }

    @Test
    void aFlippedBitInTheLastRecordFailsItsCrc(@TempDir Path dir) throws Exception {
        try (FileSessionStore store = new FileSessionStore(dir)) {
            store.storeMessage(1, msg("x"));
            store.storeMessage(2, msg("y"));
        }
        Path journal = dir.resolve("messages.journal");
        try (RandomAccessFile f = new RandomAccessFile(journal.toFile(), "rw")) {
            f.seek(f.length() - 6);
            int b = f.read();
            f.seek(f.length() - 6);
            f.write(b ^ 0x01);
        }
        try (FileSessionStore store = new FileSessionStore(dir)) {
            assertTrue(store.truncatedBytes() > 0);
            assertNotNull(store.retrieve(1));
            assertNull(store.retrieve(2));
            assertEquals(2, store.nextOutgoingSeq());
        }
    }

    @Test
    void journalIsCompactedToTheRetentionWindow(@TempDir Path dir) throws Exception {
        try (FileSessionStore store = new FileSessionStore(dir, 10)) {
            for (int i = 1; i <= 100; i++) {
                store.recordSent(i, msg("m" + i), true);
            }
            store.sync();
            assertTrue(store.firstRetainedSeq() > 80, "first retained " + store.firstRetainedSeq());
            assertNull(store.retrieve(50));
            assertEquals("11=m100\u0001", store.retrieve(100).body());
            assertEquals(101, store.nextOutgoingSeq());
        }
        long size = Files.size(dir.resolve("messages.journal"));
        assertTrue(size < 20 * 64, "journal stays bounded: " + size + " bytes");
        try (FileSessionStore reopened = new FileSessionStore(dir, 10)) {
            assertEquals(101, reopened.nextOutgoingSeq());
            assertEquals("11=m95\u0001", reopened.retrieve(95).body());
        }
    }

    @Test
    void resetRollsTheJournalToEmpty(@TempDir Path dir) throws Exception {
        try (FileSessionStore store = new FileSessionStore(dir)) {
            store.storeMessage(5, msg("old"));
            store.saveIncomingSeq(9);
            store.reset();
            assertEquals(1, store.nextOutgoingSeq());
            assertEquals(1, store.expectedIncomingSeq());
            assertNull(store.retrieve(5));
        }
        try (FileSessionStore reopened = new FileSessionStore(dir)) {
            assertEquals(1, reopened.nextOutgoingSeq());
            assertEquals(1, reopened.expectedIncomingSeq());
        }
    }

    @Test
    void versionOneDirectoryIsNotReadIn30(@TempDir Path dir) throws Exception {
        // Decision D8 (docs/MIGRATION_3.0.md section 10): 3.0 no longer
        // imports the v1 layout. The store starts fresh and leaves the v1
        // files exactly as they were.
        try (DataOutputStream seq = new DataOutputStream(Files.newOutputStream(dir.resolve("seqnums.dat")))) {
            seq.writeLong(7);
            seq.writeLong(4);
        }
        try (DataOutputStream m = new DataOutputStream(Files.newOutputStream(dir.resolve("messages.dat")))) {
            m.writeLong(5);
            m.writeUTF("D");
            m.writeUTF("20260704-10:00:01.000");
            byte[] body = "11=legacy\u0001".getBytes(StandardCharsets.ISO_8859_1);
            m.writeInt(body.length);
            m.write(body);
        }
        byte[] seqBefore = Files.readAllBytes(dir.resolve("seqnums.dat"));
        byte[] msgBefore = Files.readAllBytes(dir.resolve("messages.dat"));
        try (FileSessionStore store = new FileSessionStore(dir)) {
            assertEquals(1, store.nextOutgoingSeq());
            assertEquals(1, store.expectedIncomingSeq());
        }
        assertArrayEquals(seqBefore, Files.readAllBytes(dir.resolve("seqnums.dat")));
        assertArrayEquals(msgBefore, Files.readAllBytes(dir.resolve("messages.dat")));
        assertFalse(Files.exists(dir.resolve("seqnums.dat.migrated")));
        assertFalse(Files.exists(dir.resolve("messages.dat.migrated")));
    }

    @Test
    void incomingSequenceSurvivesATornSequencePageSlot(@TempDir Path dir) throws Exception {
        try (FileSessionStore store = new FileSessionStore(dir)) {
            store.saveIncomingSeq(10);
            store.saveIncomingSeq(11);
        }
        // Corrupt the slot holding the newest value: the other slot (10) wins.
        byte[] page = Files.readAllBytes(dir.resolve("session.seq"));
        // find the slot with incoming 11 and break its CRC
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(page);
        long gen0 = b.getLong(0);
        long gen1 = b.getLong(32);
        int newest = gen0 > gen1 ? 0 : 32;
        page[newest + 24] ^= 0x7F;
        Files.write(dir.resolve("session.seq"), page, StandardOpenOption.TRUNCATE_EXISTING);
        try (FileSessionStore store = new FileSessionStore(dir)) {
            assertEquals(10, store.expectedIncomingSeq(), "falls back to the previous valid slot");
        }
    }
}
