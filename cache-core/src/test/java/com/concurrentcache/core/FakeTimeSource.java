package com.concurrentcache.core;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Deterministic stand-in for {@link System#nanoTime()} used by tests: time only
 * moves when a test calls {@link #advance(Duration)}, so TTL behaviour can be
 * checked at exact boundaries without sleeping.
 */
final class FakeTimeSource implements LongSupplier {

    private long nanos;

    FakeTimeSource(long startNanos) {
        this.nanos = startNanos;
    }

    @Override
    public long getAsLong() {
        return nanos;
    }

    void advance(Duration duration) {
        nanos += duration.toNanos();
    }
}
