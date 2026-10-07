package com.concurrentcache.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract suite plus the approximate-LRU behaviour of {@link SegmentedRwLockCache}:
 * the entry with the oldest last-access timestamp is evicted.
 */
class SegmentedRwLockCacheTest extends AbstractCacheTest {

    private static final int SEGMENTS = 16;

    @Override
    protected <K, V> Cache<K, V> newCache(int capacity, Duration defaultTtl, LongSupplier timeSource) {
        return new SegmentedRwLockCache<>(capacity, SEGMENTS, defaultTtl, timeSource);
    }

    @Test
    void readStampProtectsKeyFromEviction() {
        SegmentedRwLockCache<String, String> cache =
                new SegmentedRwLockCache<>(4, 2, Duration.ofSeconds(600), time);
        List<String> keys = keysInSegment(0, 1, 3);
        cache.put(keys.get(0), "v0", Duration.ofSeconds(600));
        time.advance(Duration.ofSeconds(1));
        cache.put(keys.get(1), "v1", Duration.ofSeconds(600));
        time.advance(Duration.ofSeconds(1));
        cache.get(keys.get(0));
        time.advance(Duration.ofSeconds(1));
        cache.put(keys.get(2), "v2", Duration.ofSeconds(600));
        assertTrue(cache.get(keys.get(0)).isPresent());
        assertFalse(cache.get(keys.get(1)).isPresent());
        assertTrue(cache.get(keys.get(2)).isPresent());
    }

    private static List<String> keysInSegment(int target, int mask, int howMany) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; keys.size() < howMany; i++) {
            String candidate = "key" + i;
            if (Segments.index(candidate, mask) == target) {
                keys.add(candidate);
            }
        }
        return keys;
    }
}
