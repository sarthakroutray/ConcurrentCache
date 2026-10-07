package com.concurrentcache.core;

import java.time.Duration;
import java.util.function.LongSupplier;

class ConcurrentMapConcurrencyTest extends AbstractConcurrentCacheTest {

    @Override
    protected <K, V> Cache<K, V> newCache(int capacity, Duration defaultTtl, LongSupplier timeSource) {
        return new ConcurrentMapCache<>(capacity, defaultTtl, timeSource);
    }
}
