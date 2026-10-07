package com.concurrentcache.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract suite plus the size-bound behaviour of the {@link ConcurrentMapCache}
 * baseline: overflow is bounded, but eviction order is not defined.
 */
class ConcurrentMapCacheTest extends AbstractCacheTest {

    @Override
    protected <K, V> Cache<K, V> newCache(int capacity, Duration defaultTtl, LongSupplier timeSource) {
        return new ConcurrentMapCache<>(capacity, defaultTtl, timeSource);
    }

    @Test
    void boundsSizeByDroppingEntries() {
        Cache<String, String> cache = newCache(2);
        for (int i = 0; i < 5; i++) {
            cache.put("key" + i, "v" + i, Duration.ofSeconds(60));
        }
        assertEquals(2, cache.size());
        assertTrue(cache.stats().evictions() >= 1);
    }

    @Test
    void expiredEntriesArePurgedOnWriteOverflow() {
        Cache<String, String> cache = newCache(2);
        cache.put("a", "1", Duration.ofSeconds(1));
        cache.put("b", "2", Duration.ofSeconds(1));
        time.advance(Duration.ofSeconds(2));
        cache.put("c", "3", Duration.ofSeconds(60));
        assertEquals(1, cache.size());
        assertEquals(2, cache.stats().expirations());
    }
}
