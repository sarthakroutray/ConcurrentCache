package com.concurrentcache.server.support;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Deterministic clock for cache-server tests: time only moves when a test calls
 * {@link #advance(Duration)}, so TTL behaviour is checked without sleeping.
 */
public final class MutableNanoClock implements LongSupplier {

    private long nanos;

    @Override
    public long getAsLong() {
        return nanos;
    }

    public void advance(Duration duration) {
        nanos += duration.toNanos();
    }
}
