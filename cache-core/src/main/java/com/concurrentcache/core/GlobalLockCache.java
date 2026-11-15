package com.concurrentcache.core;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/**
 * The baseline implementation: a single {@link LockedLruStore}, so exactly one
 * lock guards the whole cache and eviction is exact LRU. Every operation
 * serializes, which makes correctness easy to argue and gives atomicity for
 * {@link #compute} for free.
 */
public final class GlobalLockCache<K, V> implements Cache<K, V>, SegmentSweepable {

    private final LockedLruStore<K, V> store;
    private final CacheStats stats = new CacheStats();

    public GlobalLockCache(int capacity, Duration defaultTtl) {
        this(capacity, defaultTtl, System::nanoTime);
    }

    public GlobalLockCache(int capacity, Duration defaultTtl, LongSupplier timeSource) {
        this.store = new LockedLruStore<>(capacity, defaultTtl, Objects.requireNonNull(timeSource, "timeSource"), stats);
    }

    @Override
    public Optional<V> get(K key) {
        return store.get(key);
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        store.put(key, value, ttl);
    }

    @Override
    public boolean putIfAbsent(K key, V value, Duration ttl) {
        return store.putIfAbsent(key, value, ttl);
    }

    @Override
    public boolean delete(K key) {
        return store.delete(key);
    }

    @Override
    public V compute(K key, BiFunction<K, V, V> remappingFunction) {
        return store.compute(key, remappingFunction);
    }

    @Override
    public long size() {
        return store.liveSize();
    }

    @Override
    public CacheStats stats() {
        return stats;
    }

    @Override
    public int segmentCount() {
        return 1;
    }

    @Override
    public int sweepSegment(int index) {
        if (index != 0) {
            throw new IndexOutOfBoundsException("segment index out of range: " + index);
        }
        return store.sweepExpired();
    }
}
