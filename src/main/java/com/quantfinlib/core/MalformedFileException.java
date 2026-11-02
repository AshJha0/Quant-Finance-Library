package com.quantfinlib.core;

import java.io.IOException;

/**
 * A file or saved state that this library cannot read because its contents
 * are wrong, not because the read failed: a bad magic number, an unknown
 * format version, a duplicate checkpoint section, a journal that is not a
 * FIX journal.
 *
 * <p>Extends {@link IOException}, which these sites threw before 2.2, so
 * existing {@code catch (IOException e)} handlers still catch it. A genuine
 * read failure (disk error, closed stream) stays a plain
 * {@link IOException}; test {@code e instanceof QuantFinException} to tell
 * the two apart.</p>
 *
 * @since 2.2
 */
public class MalformedFileException extends IOException implements QuantFinException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what was wrong with the file
     */
    public MalformedFileException(String message) {
        super(message);
    }

    /**
     * Creates the exception with the underlying cause.
     *
     * @param message what was wrong with the file
     * @param cause   the lower-level failure, for example an {@code EOFException}
     */
    public MalformedFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
