package com.concurrentcache.core;

import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe cache activity counters: hits, misses, evictions, expirations.
 *
 * <p>Uses {@link LongAdder} so that contended increments stay cheap: concurrent
 * updates spread across stripes instead of competing for one atomic long.
 */
public final class CacheStats {

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    private final LongAdder expirations = new LongAdder();

    public void recordHit() {
        hits.increment();
    }

    public void recordMiss() {
        misses.increment();
    }

    public void recordEviction() {
        evictions.increment();
    }

    public void recordExpiration() {
        expirations.increment();
    }

    public long hits() {
        return hits.sum();
    }

    public long misses() {
        return misses.sum();
    }

    public long evictions() {
        return evictions.sum();
    }

    public long expirations() {
        return expirations.sum();
    }

    /**
     * Returns hits divided by (hits + misses), or 0.0 if no lookup has happened yet.
     */
    public double hitRatio() {
        long h = hits.sum();
        long total = h + misses.sum();
        return total == 0 ? 0.0 : (double) h / total;
    }
}
