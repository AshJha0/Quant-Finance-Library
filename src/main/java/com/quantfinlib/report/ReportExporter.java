package com.quantfinlib.report;

import java.io.IOException;
import java.nio.file.Path;

/** Renders a {@link Report} to a file. Implement to add custom formats. */
public interface ReportExporter {

    void export(Report report, Path path) throws IOException;
}
