package com.quantfinlib.core;

/**
 * A byte stream that breaks its wire protocol's framing: a FIX BodyLength
 * that is missing or not a number, an SBE header with an unknown schema or
 * template. The stream position can no longer be trusted, so the usual
 * response is to drop the connection and recover from the sequence number.
 *
 * <p>Extends {@link IllegalStateException}, which these sites threw before
 * 2.1, so existing handlers still catch it.</p>
 */
public class WireProtocolException extends IllegalStateException implements QuantFinException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what was wrong with the frame
     */
    public WireProtocolException(String message) {
        super(message);
    }
}
