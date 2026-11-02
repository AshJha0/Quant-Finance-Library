package com.quantfinlib.examples;

import com.quantfinlib.fix.FileSessionStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One-off migration of a v1 FIX session store directory ({@code seqnums.dat}
 * and {@code messages.dat}) to the journal layout, for users about to move to
 * 3.0, which no longer reads the v1 files.
 *
 * <p>It opens each directory with a 2.x {@link FileSessionStore}, which imports
 * the v1 files on open and renames them with a {@code .migrated} suffix, then
 * closes it. Run it with a 2.4 jar on the class path:
 * {@code java -cp quant-finance-library-2.4.0.jar:quant-finance-library-2.4.0-examples.jar
 * com.quantfinlib.examples.FixJournalMigration /var/fix/session1 /var/fix/session2}.
 * Running it twice is harmless: the second open finds no v1 files.</p>
 *
 * @since 2.4.0
 */
public final class FixJournalMigration {

    private FixJournalMigration() {
    }

    /**
     * Migrates every directory given on the command line.
     *
     * @param args store directories
     * @throws IOException if a directory cannot be read or written
     */
    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("usage: FixJournalMigration <store-dir>...");
            return;
        }
        for (String a : args) {
            Path dir = Path.of(a);
            if (!Files.isDirectory(dir)) {
                System.err.println("not a directory, skipped: " + dir);
                continue;
            }
            boolean legacy = Files.exists(dir.resolve("seqnums.dat"))
                    || Files.exists(dir.resolve("messages.dat"));
            try (FileSessionStore store = new FileSessionStore(dir)) {
                System.out.println((legacy ? "migrated: " : "already current: ") + dir
                        + " (next outgoing seq " + store.nextOutgoingSeq() + ")");
            }
        }
    }
}
