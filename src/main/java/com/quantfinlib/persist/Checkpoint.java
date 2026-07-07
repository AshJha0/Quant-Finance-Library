package com.quantfinlib.persist;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Multi-day persistence of learned state: everything the models learn
 * across sessions — volume/vol/spread baselines, alpha weights and their
 * out-of-sample IC evidence, venue and LP scorecards — is exactly what a
 * desk does NOT want to relearn from zero every morning. A checkpoint is
 * one binary file of named sections, written at end of day and restored at
 * the next session start:
 *
 * <pre>{@code
 * try (var w = Checkpoint.writer(path)) {           // end of day
 *     w.section("volume.AAPL", volumeCurve::writeState)
 *      .section("alpha.AAPL", learner::writeState)
 *      .section("venues", scorecard::writeState);
 * }                                                  // commits atomically
 *
 * var r = Checkpoint.reader(path);                   // next morning
 * r.section("volume.AAPL", volumeCurve::readState);  // false if absent
 * }</pre>
 *
 * <p><b>Contract.</b> Each model persists its <em>learned</em> (cross-day)
 * state only; intraday state resets on read — you restore at session
 * start, not mid-stream. The reading instance must be constructed with the
 * same configuration (bucket count, venue count, …): a mismatch throws
 * {@link IOException} rather than silently misaligning arrays. Each
 * section payload carries its own version byte so models can evolve their
 * format independently of the file format.</p>
 *
 * <p><b>Durability.</b> The writer buffers sections in memory and commits
 * in {@link Writer#close()}: temp file in the target directory, then an
 * atomic rename over the old checkpoint — a crash mid-save leaves
 * yesterday's file intact, never a torn one. (On the rare filesystem
 * without atomic rename — some network mounts — the commit degrades to a
 * plain replace, and a crash in that narrow window can lose the old file;
 * keep checkpoints on a local disk if that guarantee matters.) If any
 * section writer threw, nothing is committed. The reader loads the whole file up front (these
 * files are kilobytes), skips unknown sections (forward compatibility),
 * and rejects a section the model did not fully consume — the loudest
 * possible signal of a writer/reader format drift.</p>
 *
 * <p>Everything here is cold-path (end of day / session start); the hot
 * lanes never see it. Naming convention: {@code model.symbol}
 * ("volume.EURUSD", "venues"). For {@code DayTypeProfiles}, write one
 * section per day type ({@code "volume.AAPL.day0"} …).</p>
 */
public final class Checkpoint {

    /** "QFLC" — identifies a quantfinlib checkpoint file. */
    private static final int MAGIC = 0x51464C43;
    private static final int FORMAT_VERSION = 1;

    private Checkpoint() {
    }

    /** A model's state serializer — typically a {@code writeState} reference. */
    @FunctionalInterface
    public interface StateWriter {
        void write(DataOutput out) throws IOException;
    }

    /** A model's state deserializer — typically a {@code readState} reference. */
    @FunctionalInterface
    public interface StateReader {
        void read(DataInput in) throws IOException;
    }

    /** Opens a writer; nothing touches {@code path} until {@link Writer#close()}. */
    public static Writer writer(Path path) {
        return new Writer(path);
    }

    /** Loads a checkpoint fully into memory and validates the header. */
    public static Reader reader(Path path) throws IOException {
        return new Reader(Files.readAllBytes(path), path);
    }

    // ------------------------------------------------------------------
    // Writer
    // ------------------------------------------------------------------

    /** Collects named sections and commits them atomically on close. */
    public static final class Writer implements Closeable {

        private final Path target;
        private final Map<String, byte[]> sections = new LinkedHashMap<>();
        private boolean failed;

        private Writer(Path target) {
            this.target = target;
        }

        /**
         * Serializes one model's state under {@code name}. A duplicate name
         * or a throwing body marks the writer failed: close() then commits
         * nothing, so a half-written model can never replace a good file.
         */
        public Writer section(String name, StateWriter body) throws IOException {
            if (sections.containsKey(name)) {
                failed = true;
                throw new IOException("duplicate checkpoint section: " + name);
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try {
                body.write(new DataOutputStream(buf));
            } catch (IOException | RuntimeException e) {
                failed = true;
                throw e;
            }
            sections.put(name, buf.toByteArray());
            return this;
        }

        /**
         * Commits: temp file beside the target, then atomic rename over it
         * (plain replace on filesystems without atomic rename — see the
         * class javadoc's durability caveat).
         */
        @Override
        public void close() throws IOException {
            if (failed) {
                return;
            }
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            try (OutputStream raw = Files.newOutputStream(temp);
                 DataOutputStream out = new DataOutputStream(raw)) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT_VERSION);
                for (Map.Entry<String, byte[]> e : sections.entrySet()) {
                    out.writeUTF(e.getKey());
                    out.writeInt(e.getValue().length);
                    out.write(e.getValue());
                }
            } catch (IOException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
            try {
                Files.move(temp, target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Filesystem without atomic rename (some network mounts):
                // fall back to a plain replace rather than failing the save.
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    // ------------------------------------------------------------------
    // Reader
    // ------------------------------------------------------------------

    /** Random access to a loaded checkpoint's sections by name. */
    public static final class Reader {

        private final Map<String, byte[]> sections = new LinkedHashMap<>();

        private Reader(byte[] bytes, Path source) throws IOException {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (bytes.length < 8 || in.readInt() != MAGIC) {
                throw new IOException(source + " is not a quantfinlib checkpoint");
            }
            int version = in.readInt();
            if (version > FORMAT_VERSION) {
                throw new IOException(source + " uses checkpoint format " + version
                        + "; this build reads up to " + FORMAT_VERSION);
            }
            while (in.available() > 0) {
                String name = in.readUTF();
                int len = in.readInt();
                if (len < 0 || len > in.available()) {
                    throw new IOException(source + ": section '" + name
                            + "' declares " + len + " bytes, " + in.available() + " remain");
                }
                byte[] payload = new byte[len];
                in.readFully(payload);
                sections.put(name, payload);
            }
        }

        /**
         * Restores one model from the named section. Returns false when the
         * section is absent (model keeps its current state — the caller
         * decides whether that is a cold start or an error). Throws when
         * the model does not consume the payload exactly: leftover bytes
         * mean the writer and reader disagree about the format.
         */
        public boolean section(String name, StateReader reader) throws IOException {
            byte[] payload = sections.get(name);
            if (payload == null) {
                return false;
            }
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            reader.read(in);
            if (in.available() > 0) {
                throw new IOException("section '" + name + "': " + in.available()
                        + " bytes left unread — writer/reader format mismatch");
            }
            return true;
        }

        /** The section names present, in file order. */
        public Set<String> names() {
            return sections.keySet();
        }
    }

    // ------------------------------------------------------------------
    // Helpers shared by the models' state formats
    // ------------------------------------------------------------------

    /**
     * Reads and checks a model's leading state-version byte — the shared
     * first line of every {@code readState}. Throws when the checkpoint was
     * written by a build with a newer per-model format.
     */
    public static void requireVersion(DataInput in, int expected, String model)
            throws IOException {
        int v = in.readByte();
        if (v != expected) {
            throw new IOException(model + " state version " + v + " not supported"
                    + " (this build reads version " + expected + ")");
        }
    }

    /** Length-prefixed double array. */
    public static void writeDoubles(DataOutput out, double[] a) throws IOException {
        out.writeInt(a.length);
        for (double v : a) {
            out.writeDouble(v);
        }
    }

    /**
     * Reads a length-prefixed double array INTO {@code a} — a length
     * mismatch means the checkpoint was written by a differently-configured
     * instance (other bucket/venue count) and throws before touching it.
     */
    public static void readDoublesInto(DataInput in, double[] a) throws IOException {
        int n = in.readInt();
        if (n != a.length) {
            throw new IOException("checkpoint array has " + n + " entries, this instance has "
                    + a.length + " — incompatible configuration");
        }
        for (int i = 0; i < n; i++) {
            a[i] = in.readDouble();
        }
    }

    /** Length-prefixed long array. */
    public static void writeLongs(DataOutput out, long[] a) throws IOException {
        out.writeInt(a.length);
        for (long v : a) {
            out.writeLong(v);
        }
    }

    /** Long-array counterpart of {@link #readDoublesInto}. */
    public static void readLongsInto(DataInput in, long[] a) throws IOException {
        int n = in.readInt();
        if (n != a.length) {
            throw new IOException("checkpoint array has " + n + " entries, this instance has "
                    + a.length + " — incompatible configuration");
        }
        for (int i = 0; i < n; i++) {
            a[i] = in.readLong();
        }
    }
}
