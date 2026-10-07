package com.concurrentcache.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract suite plus the segment-level guarantees of {@link SegmentedLockCache}:
 * LRU is exact within a segment, and capacity is split evenly.
 */
class SegmentedLockCacheTest extends AbstractCacheTest {

    private static final int SEGMENTS = 16;

    @Override
    protected <K, V> Cache<K, V> newCache(int capacity, Duration defaultTtl, LongSupplier timeSource) {
        return new SegmentedLockCache<>(capacity, SEGMENTS, defaultTtl, timeSource);
    }

    @Test
    void evictsLeastRecentlyUsedWithinASegment() {
        SegmentedLockCache<String, String> cache =
                new SegmentedLockCache<>(4, 2, Duration.ofSeconds(60), time);
        List<String> keys = keysInSegment(0, 1, 3);
        cache.put(keys.get(0), "v0", Duration.ofSeconds(60));
        cache.put(keys.get(1), "v1", Duration.ofSeconds(60));
        cache.put(keys.get(2), "v2", Duration.ofSeconds(60));
        assertEquals(Optional.empty(), cache.get(keys.get(0)));
        assertTrue(cache.get(keys.get(1)).isPresent());
        assertTrue(cache.get(keys.get(2)).isPresent());
    }

    @Test
    void splitsCapacityAcrossSegments() {
        SegmentedLockCache<String, String> cache =
                new SegmentedLockCache<>(8, 2, Duration.ofSeconds(60), time);
        List<String> keys = keysInSegment(0, 1, 5);
        for (int i = 0; i < keys.size(); i++) {
            cache.put(keys.get(i), "v" + i, Duration.ofSeconds(60));
        }
        assertEquals(4, cache.size());
        assertEquals(1, cache.stats().evictions());
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
