package com.quantfinlib.core;

/**
 * Marks an exception raised by this library for a fault in what it was
 * given: a broken wire frame, a malformed data file or an invalid
 * configuration.
 *
 * <p>This is an interface, not a base class, because each library exception
 * extends the JDK type that the same site threw before it existed
 * ({@link IllegalArgumentException}, {@link IllegalStateException} or
 * {@link java.io.IOException}). Code that catches the JDK type keeps
 * working; code that wants to tell a library fault from any other failure
 * tests {@code e instanceof QuantFinException}. The implementations are
 * {@link WireProtocolException}, {@link MalformedDataException},
 * {@link ConfigurationException}, {@code fix.FixParseException},
 * {@code data.TickFileReader.TruncatedTickFileException} and
 * {@link MalformedFileException}.</p>
 *
 * <p>Plain argument checks (a negative size, a null factory) stay
 * {@link IllegalArgumentException}: they are programming errors at the
 * call site, not faults in external input.</p>
 */
public interface QuantFinException {

    /** The detail message, as {@link Throwable#getMessage()} returns it. */
    String getMessage();
}
