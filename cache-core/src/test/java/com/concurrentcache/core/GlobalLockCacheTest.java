package com.concurrentcache.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract suite plus the exact-LRU promises specific to {@link GlobalLockCache}:
 * eviction order follows access order, and a read protects a key from eviction.
 */
class GlobalLockCacheTest extends AbstractCacheTest {

    @Override
    protected <K, V> Cache<K, V> newCache(int capacity, Duration defaultTtl, LongSupplier timeSource) {
        return new GlobalLockCache<>(capacity, defaultTtl, timeSource);
    }

    @Test
    void evictsLeastRecentlyUsedKey() {
        Cache<String, String> cache = newCache(3);
        cache.put("a", "1", Duration.ofSeconds(60));
        cache.put("b", "2", Duration.ofSeconds(60));
        cache.put("c", "3", Duration.ofSeconds(60));
        cache.get("a");
        cache.put("d", "4", Duration.ofSeconds(60));
        assertEquals(Optional.empty(), cache.get("b"));
        assertEquals(Optional.of("1"), cache.get("a"));
        assertEquals(Optional.of("3"), cache.get("c"));
        assertEquals(Optional.of("4"), cache.get("d"));
    }

    @Test
    void readProtectsKeyFromEviction() {
        Cache<String, String> cache = newCache(3);
        cache.put("a", "1", Duration.ofSeconds(60));
        cache.put("b", "2", Duration.ofSeconds(60));
        cache.put("c", "3", Duration.ofSeconds(60));
        cache.get("a");
        cache.get("b");
        cache.put("d", "4", Duration.ofSeconds(60));
        assertTrue(cache.get("a").isPresent());
        assertTrue(cache.get("b").isPresent());
        assertFalse(cache.get("c").isPresent());
    }

    @Test
    void evictionsAreCounted() {
        Cache<String, String> cache = newCache(2);
        cache.put("a", "1", Duration.ofSeconds(60));
        cache.put("b", "2", Duration.ofSeconds(60));
        cache.put("c", "3", Duration.ofSeconds(60));
        assertEquals(1, cache.stats().evictions());
    }
}
