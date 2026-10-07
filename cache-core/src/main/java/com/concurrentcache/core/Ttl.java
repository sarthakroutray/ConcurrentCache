package com.concurrentcache.core;

import java.time.Duration;
import java.util.Objects;

/**
 * TTL validation shared by all implementations: a TTL is a positive
 * {@link Duration} and is stored internally as nanoseconds.
 */
final class Ttl {

    private Ttl() {
    }

    static long positiveNanos(Duration ttl, String name) {
        Objects.requireNonNull(ttl, name);
        long nanos = ttl.toNanos();
        if (nanos <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return nanos;
    }
}
