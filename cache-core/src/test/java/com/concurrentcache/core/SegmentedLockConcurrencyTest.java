package com.concurrentcache.core;

import java.time.Duration;
import java.util.function.LongSupplier;

class SegmentedLockConcurrencyTest extends AbstractConcurrentCacheTest {

    private static final int SEGMENTS = 16;

    @Override
    protected <K, V> Cache<K, V> newCache(int capacity, Duration defaultTtl, LongSupplier timeSource) {
        return new SegmentedLockCache<>(capacity, SEGMENTS, defaultTtl, timeSource);
    }
}
