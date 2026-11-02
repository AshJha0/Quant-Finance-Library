package com.quantfinlib.credit;

import org.junit.jupiter.api.Assumptions;

/**
 * Gate for tests that need reference files which are not in the repository.
 * Locally the test is skipped when the file is missing. When
 * {@code QFL_REQUIRE_REFERENCE_FILES=true} (the nightly CI job sets it after
 * fetching the files from their public URLs) a missing file fails the test,
 * so the reference check can never pass by skipping.
 */
final class ReferenceFiles {
    private ReferenceFiles() {
    }

    static boolean required() {
        return "true".equalsIgnoreCase(System.getenv("QFL_REQUIRE_REFERENCE_FILES"));
    }

    static void require(boolean present, String message) {
        if (!present && required()) {
            throw new AssertionError("QFL_REQUIRE_REFERENCE_FILES=true but " + message);
        }
        Assumptions.assumeTrue(present, message);
    }
}
