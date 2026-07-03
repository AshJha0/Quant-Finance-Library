package com.quantfinlib.execution;

/** One child slice of an execution schedule. */
public record Slice(long offsetMillis, long quantity) {
}
