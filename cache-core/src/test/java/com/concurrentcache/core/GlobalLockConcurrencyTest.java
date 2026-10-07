package com.concurrentcache.core;

import java.time.Duration;
import java.util.function.LongSupplier;

class GlobalLockConcurrencyTest extends AbstractConcurrentCacheTest {

    @Override
    protected <K, V> Cache<K, V> newCache(int capacity, Duration defaultTtl, LongSupplier timeSource) {
        return new GlobalLockCache<>(capacity, defaultTtl, timeSource);
    }
}
