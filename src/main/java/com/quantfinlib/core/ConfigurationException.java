package com.quantfinlib.core;

/**
 * A configuration that is syntactically wrong or names a value outside its
 * allowed range: an unknown or repeated key in a limits file, a price collar
 * above 100 %, a non-positive order-size cap.
 *
 * <p>Extends {@link IllegalArgumentException}, which these sites threw
 * before 2.1, so existing handlers still catch it.</p>
 */
public class ConfigurationException extends IllegalArgumentException implements QuantFinException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message which setting is wrong and why
     */
    public ConfigurationException(String message) {
        super(message);
    }

    /**
     * Creates the exception with the parse failure that caused it.
     *
     * @param message which setting is wrong and why
     * @param cause the underlying parse failure
     */
    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
