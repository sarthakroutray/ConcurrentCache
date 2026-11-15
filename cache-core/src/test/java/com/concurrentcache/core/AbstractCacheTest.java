package com.concurrentcache.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests every {@link Cache} implementation must pass. Subclasses supply
 * the implementation under test via {@link #newCache(int, Duration, LongSupplier)}.
 * Exact-LRU behaviour is deliberately not tested here because not every
 * implementation promises it.
 */
abstract class AbstractCacheTest {

    protected FakeTimeSource time;

    protected abstract <K, V> Cache<K, V> newCache(int capacity, Duration defaultTtl, LongSupplier timeSource);

    protected <K, V> Cache<K, V> newCache(int capacity) {
        return this.<K, V>newCache(capacity, Duration.ofSeconds(60), time);
    }

    @BeforeEach
    void setUp() {
        time = new FakeTimeSource(0L);
    }

    @Test
    void putThenGetReturnsValue() {
        Cache<String, String> cache = newCache(10);
        cache.put("a", "1", Duration.ofSeconds(10));
        assertEquals(Optional.of("1"), cache.get("a"));
    }

    @Test
    void getMissingKeyReturnsEmptyAndCountsMiss() {
        Cache<String, String> cache = newCache(10);
        assertEquals(Optional.empty(), cache.get("missing"));
        assertEquals(0, cache.stats().hits());
        assertEquals(1, cache.stats().misses());
    }

    @Test
    void deleteReportsWhetherMappingWasRemoved() {
        Cache<String, String> cache = newCache(10);
        cache.put("a", "1", Duration.ofSeconds(10));
        assertTrue(cache.delete("a"));
        assertFalse(cache.delete("a"));
        assertEquals(Optional.empty(), cache.get("a"));
    }

    @Test
    void putIfAbsentCreatesOnlyWhenNoLiveMappingExists() {
        Cache<String, String> cache = newCache(10);
        assertTrue(cache.putIfAbsent("a", "1", Duration.ofSeconds(10)));
        assertFalse(cache.putIfAbsent("a", "2", Duration.ofSeconds(10)));
        assertEquals(Optional.of("1"), cache.get("a"));
    }

    @Test
    void putIfAbsentReplacesExpiredMapping() {
        Cache<String, String> cache = newCache(10);
        cache.put("a", "old", Duration.ofSeconds(5));
        time.advance(Duration.ofSeconds(6));
        assertTrue(cache.putIfAbsent("a", "new", Duration.ofSeconds(60)));
        assertEquals(Optional.of("new"), cache.get("a"));
        assertEquals(1, cache.stats().expirations());
    }

    @Test
    void sweepSegmentRemovesExpiredEntries() {
        Cache<String, String> cache = newCache(10);
        SegmentSweepable sweepable = (SegmentSweepable) cache;
        cache.put("a", "1", Duration.ofSeconds(5));
        cache.put("b", "2", Duration.ofSeconds(60));
        time.advance(Duration.ofSeconds(6));
        int removed = 0;
        for (int i = 0; i < sweepable.segmentCount(); i++) {
            removed += sweepable.sweepSegment(i);
        }
        assertEquals(1, removed);
        assertEquals(1, cache.size());
        assertEquals(Optional.of("2"), cache.get("b"));
    }

    @Test
    void entryIsLiveJustBeforeTtlAndGoneAtBoundary() {
        Cache<String, String> cache = newCache(10);
        cache.put("a", "1", Duration.ofSeconds(10));
        time.advance(Duration.ofMillis(9_999));
        assertEquals(Optional.of("1"), cache.get("a"));
        time.advance(Duration.ofMillis(1));
        assertEquals(Optional.empty(), cache.get("a"));
    }

    @Test
    void expiredEntryCountsOneExpirationAndOneMiss() {
        Cache<String, String> cache = newCache(10);
        cache.put("a", "1", Duration.ofSeconds(10));
        time.advance(Duration.ofSeconds(11));
        assertEquals(Optional.empty(), cache.get("a"));
        assertEquals(1, cache.stats().expirations());
        assertEquals(1, cache.stats().misses());
        assertEquals(0, cache.stats().hits());
    }

    @Test
    void putReplacesValueAndRestartsTtl() {
        Cache<String, String> cache = newCache(10);
        cache.put("a", "old", Duration.ofSeconds(10));
        time.advance(Duration.ofSeconds(5));
        cache.put("a", "new", Duration.ofSeconds(2));
        time.advance(Duration.ofSeconds(3));
        assertEquals(Optional.empty(), cache.get("a"));
    }

    @Test
    void sizeCountsOnlyLiveEntries() {
        Cache<String, String> cache = newCache(10);
        cache.put("short", "1", Duration.ofSeconds(5));
        cache.put("long", "2", Duration.ofSeconds(60));
        time.advance(Duration.ofSeconds(6));
        assertEquals(1, cache.size());
    }

    @Test
    void sizeNeverExceedsCapacity() {
        Cache<String, String> cache = newCache(4);
        for (int i = 0; i < 40; i++) {
            cache.put("key-" + i, "v", Duration.ofSeconds(60));
            assertTrue(cache.size() <= 4);
        }
    }

    @Test
    void computeCreatesMissingKeyWithDefaultTtl() {
        Cache<String, Long> cache = newCache(10, Duration.ofSeconds(30), time);
        assertEquals(1L, cache.compute("counter", (k, v) -> v == null ? 1L : v + 1));
        time.advance(Duration.ofSeconds(31));
        assertEquals(Optional.empty(), cache.get("counter"));
    }

    @Test
    void computeUpdateKeepsRemainingTtl() {
        Cache<String, Long> cache = newCache(10, Duration.ofSeconds(1), time);
        cache.put("counter", 5L, Duration.ofSeconds(10));
        time.advance(Duration.ofSeconds(5));
        cache.compute("counter", (k, v) -> v + 1);
        time.advance(Duration.ofSeconds(6));
        assertEquals(Optional.empty(), cache.get("counter"));
    }

    @Test
    void computeNullResultRemovesMapping() {
        Cache<String, String> cache = newCache(10);
        cache.put("a", "1", Duration.ofSeconds(10));
        assertNull(cache.compute("a", (k, v) -> null));
        assertEquals(Optional.empty(), cache.get("a"));
    }

    @Test
    void computeSeesExpiredEntryAsAbsent() {
        Cache<String, Long> cache = newCache(10, Duration.ofSeconds(60), time);
        cache.put("counter", 5L, Duration.ofSeconds(5));
        time.advance(Duration.ofSeconds(6));
        assertEquals(1L, cache.compute("counter", (k, v) -> v == null ? 1L : v + 1));
        assertEquals(1, cache.stats().expirations());
    }

    @Test
    void computeAppliesFunctionToCurrentValue() {
        Cache<String, Long> cache = newCache(10);
        for (int i = 0; i < 100; i++) {
            cache.compute("counter", (k, v) -> v == null ? 1L : v + 1);
        }
        assertEquals(Optional.of(100L), cache.get("counter"));
    }

    @Test
    void statsTrackHitsMissesAndHitRatio() {
        Cache<String, String> cache = newCache(10);
        cache.get("a");
        cache.put("a", "1", Duration.ofSeconds(10));
        cache.get("a");
        cache.get("a");
        cache.put("b", "2", Duration.ofSeconds(1));
        time.advance(Duration.ofSeconds(2));
        cache.get("b");
        CacheStats stats = cache.stats();
        assertEquals(2, stats.hits());
        assertEquals(2, stats.misses());
        assertEquals(1, stats.expirations());
        assertEquals(0.5, stats.hitRatio());
    }

    @Test
    void rejectsNullAndNonPositiveTtl() {
        Cache<String, String> cache = newCache(10);
        assertThrows(NullPointerException.class, () -> cache.put(null, "v", Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> cache.put("k", null, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> cache.put("k", "v", null));
        assertThrows(IllegalArgumentException.class, () -> cache.put("k", "v", Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> cache.put("k", "v", Duration.ofSeconds(-1)));
        assertThrows(NullPointerException.class, () -> cache.get(null));
        assertThrows(NullPointerException.class, () -> cache.putIfAbsent(null, "v", Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> cache.putIfAbsent("k", null, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> cache.putIfAbsent("k", "v", Duration.ZERO));
    }
}
