package com.quantfinlib.core;

/**
 * A data file or text record that does not follow its format: a CSV with no
 * header or missing required columns, a universe file line with the wrong
 * column count, an unparseable timestamp. The message names the line where
 * the loader knows it.
 *
 * <p>Extends {@link IllegalArgumentException}, which these sites threw
 * before 2.1, so existing handlers still catch it.</p>
 */
public class MalformedDataException extends IllegalArgumentException implements QuantFinException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what was malformed, and where
     */
    public MalformedDataException(String message) {
        super(message);
    }

    /**
     * Creates the exception with the parse failure that caused it.
     *
     * @param message what was malformed, and where
     * @param cause the underlying parse failure
     */
    public MalformedDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
