package com.quantfinlib.internal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class whose instances cannot change after construction: all fields
 * are final, referenced arrays and collections are private copies that never
 * escape, and every method that sounds like a mutator ({@code add...},
 * {@code with...}) returns a new value instead.
 *
 * <p>SpotBugs reads any annotation named {@code Immutable} and then stops
 * treating references to the class as exposed internal state. Put it only on
 * classes that meet the contract above. Not {@code @Documented}: the package
 * is not exported, so the marker stays out of the published javadoc.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Immutable {
}
