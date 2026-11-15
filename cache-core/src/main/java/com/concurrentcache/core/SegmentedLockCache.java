package com.concurrentcache.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/**
 * Variant A: the single-lock engine replicated per segment. Each segment is a
 * {@link LockedLruStore}, so eviction is exact LRU inside a segment but only
 * approximate globally, and the configured capacity is split evenly across the
 * segments. Independent keys that land in different segments are never blocked
 * by each other.
 */
public final class SegmentedLockCache<K, V> implements Cache<K, V>, SegmentSweepable {

    private final List<LockedLruStore<K, V>> segments;
    private final int mask;
    private final CacheStats stats = new CacheStats();

    public SegmentedLockCache(int capacity, int segments, Duration defaultTtl) {
        this(capacity, segments, defaultTtl, System::nanoTime);
    }

    public SegmentedLockCache(int capacity, int segments, Duration defaultTtl, LongSupplier timeSource) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (segments <= 0) {
            throw new IllegalArgumentException("segments must be positive");
        }
        Objects.requireNonNull(timeSource, "timeSource");
        int count = Segments.count(segments, capacity);
        int perSegmentCapacity = capacity / count;
        List<LockedLruStore<K, V>> stores = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            stores.add(new LockedLruStore<>(perSegmentCapacity, defaultTtl, timeSource, stats));
        }
        this.segments = List.copyOf(stores);
        this.mask = count - 1;
    }

    @Override
    public Optional<V> get(K key) {
        return segmentFor(key).get(key);
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        segmentFor(key).put(key, value, ttl);
    }

    @Override
    public boolean putIfAbsent(K key, V value, Duration ttl) {
        return segmentFor(key).putIfAbsent(key, value, ttl);
    }

    @Override
    public boolean delete(K key) {
        return segmentFor(key).delete(key);
    }

    @Override
    public V compute(K key, BiFunction<K, V, V> remappingFunction) {
        return segmentFor(key).compute(key, remappingFunction);
    }

    @Override
    public long size() {
        long live = 0;
        for (LockedLruStore<K, V> segment : segments) {
            live += segment.liveSize();
        }
        return live;
    }

    @Override
    public CacheStats stats() {
        return stats;
    }

    @Override
    public int segmentCount() {
        return segments.size();
    }

    @Override
    public int sweepSegment(int index) {
        if (index < 0 || index >= segments.size()) {
            throw new IndexOutOfBoundsException("segment index out of range: " + index);
        }
        return segments.get(index).sweepExpired();
    }

    private LockedLruStore<K, V> segmentFor(K key) {
        Objects.requireNonNull(key, "key");
        return segments.get(Segments.index(key, mask));
    }
}
