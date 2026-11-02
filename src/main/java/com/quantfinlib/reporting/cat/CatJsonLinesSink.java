package com.quantfinlib.reporting.cat;

import com.quantfinlib.reporting.TransactionReport;
import com.quantfinlib.reporting.TransactionReportSink;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.List;
import java.util.Objects;

/**
 * Writes {@link CatEvent}s as newline-delimited JSON, one event per line, after
 * validating each. CAT accepts JSON-lines files, but the file naming, metadata file and
 * submission transport are out of scope. Rejects any report that is not a {@link CatEvent}
 * or fails {@link CatEvent#validate()} with {@link IllegalArgumentException}.
 */
public final class CatJsonLinesSink implements TransactionReportSink {
    private final Writer out;
    private int written;

    /**
     * Creates the sink. The caller owns and closes the writer.
     *
     * @param out the destination
     */
    public CatJsonLinesSink(Writer out) {
        this.out = Objects.requireNonNull(out, "out");
    }

    @Override
    public void submit(TransactionReport report) {
        if (!(report instanceof CatEvent e)) {
            throw new IllegalArgumentException("not a CAT event: " + report.regime());
        }
        List<String> errs = e.validate();
        if (!errs.isEmpty()) {
            throw new IllegalArgumentException("invalid CAT event " + e.reference() + ": " + errs);
        }
        try {
            out.write(e.toJson());
            out.write('\n');
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        written++;
    }

    /**
     * Number of events written.
     *
     * @return the count
     */
    public int written() {
        return written;
    }
}
