/**
 * Implementation helpers shared across packages: {@code ValueArrays}
 * (defensive copies for result types) and the {@code Immutable} marker that
 * SpotBugs reads. Not API: the module exports this package only to
 * {@code com.quantfinlib.lowlatency}. Until 2.4 both types sat in the public
 * {@code util} package (docs/MIGRATION_3.0.md, section 8).
 */
@NullMarked
package com.quantfinlib.internal;

import org.jspecify.annotations.NullMarked;
