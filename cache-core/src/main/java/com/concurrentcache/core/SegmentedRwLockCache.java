package com.concurrentcache.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/**
 * Variant B: one {@link ReentrantReadWriteLock} per segment. Reads take the
 * shared lock and only stamp a last-access timestamp; writes take the exclusive
 * lock and evict the entry with the oldest timestamp. Because reads never mutate
 * the map structure, readers run in parallel, but eviction is only approximate
 * LRU (see docs/DESIGN.md DR-007 for why exact LRU cannot be done under a read
 * lock).
 */
public final class SegmentedRwLockCache<K, V> implements Cache<K, V> {

    private static final class Entry<V> {
        private final V value;
        private final long expiresAtNanos;
        private final AtomicLong lastAccessNanos;

        Entry(V value, long expiresAtNanos, long lastAccessNanos) {
            this.value = value;
            this.expiresAtNanos = expiresAtNanos;
            this.lastAccessNanos = new AtomicLong(lastAccessNanos);
        }
    }

    private static final class Segment<K, V> {
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private final HashMap<K, Entry<V>> map = new HashMap<>();
    }

    private final List<Segment<K, V>> segments;
    private final int mask;
    private final int capacityPerSegment;
    private final long defaultTtlNanos;
    private final LongSupplier timeSource;
    private final CacheStats stats = new CacheStats();

    public SegmentedRwLockCache(int capacity, int segments, Duration defaultTtl) {
        this(capacity, segments, defaultTtl, System::nanoTime);
    }

    public SegmentedRwLockCache(int capacity, int segments, Duration defaultTtl, LongSupplier timeSource) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (segments <= 0) {
            throw new IllegalArgumentException("segments must be positive");
        }
        this.defaultTtlNanos = Ttl.positiveNanos(defaultTtl, "defaultTtl");
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        int count = Segments.count(segments, capacity);
        this.capacityPerSegment = capacity / count;
        List<Segment<K, V>> created = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            created.add(new Segment<>());
        }
        this.segments = List.copyOf(created);
        this.mask = count - 1;
    }

    @Override
    public Optional<V> get(K key) {
        Segment<K, V> segment = segmentFor(key);
        long now = timeSource.getAsLong();
        segment.lock.readLock().lock();
        try {
            Entry<V> entry = segment.map.get(key);
            if (entry != null && now < entry.expiresAtNanos) {
                entry.lastAccessNanos.accumulateAndGet(now, Math::max);
                stats.recordHit();
                return Optional.of(entry.value);
            }
        } finally {
            segment.lock.readLock().unlock();
        }
        return expireIfPresentAndRecordMiss(segment, key, now);
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        long ttlNanos = Ttl.positiveNanos(ttl, "ttl");
        Segment<K, V> segment = segmentFor(key);
        segment.lock.writeLock().lock();
        try {
            long now = timeSource.getAsLong();
            segment.map.put(key, new Entry<>(value, now + ttlNanos, now));
            evictOverflow(segment);
        } finally {
            segment.lock.writeLock().unlock();
        }
    }

    @Override
    public boolean delete(K key) {
        Segment<K, V> segment = segmentFor(key);
        long now = timeSource.getAsLong();
        segment.lock.writeLock().lock();
        try {
            Entry<V> entry = segment.map.remove(key);
            if (entry == null) {
                return false;
            }
            if (now >= entry.expiresAtNanos) {
                stats.recordExpiration();
                return false;
            }
            return true;
        } finally {
            segment.lock.writeLock().unlock();
        }
    }

    @Override
    public V compute(K key, BiFunction<K, V, V> remappingFunction) {
        Objects.requireNonNull(remappingFunction, "remappingFunction");
        Segment<K, V> segment = segmentFor(key);
        segment.lock.writeLock().lock();
        try {
            long now = timeSource.getAsLong();
            Entry<V> entry = segment.map.get(key);
            if (entry != null && now >= entry.expiresAtNanos) {
                segment.map.remove(key);
                stats.recordExpiration();
                entry = null;
            }
            V updated = remappingFunction.apply(key, entry == null ? null : entry.value);
            if (updated == null) {
                segment.map.remove(key);
                return null;
            }
            long expiresAtNanos = entry == null ? now + defaultTtlNanos : entry.expiresAtNanos;
            segment.map.put(key, new Entry<>(updated, expiresAtNanos, now));
            evictOverflow(segment);
            return updated;
        } finally {
            segment.lock.writeLock().unlock();
        }
    }

    @Override
    public long size() {
        long live = 0;
        for (Segment<K, V> segment : segments) {
            segment.lock.writeLock().lock();
            try {
                long now = timeSource.getAsLong();
                Iterator<Map.Entry<K, Entry<V>>> it = segment.map.entrySet().iterator();
                while (it.hasNext()) {
                    if (now >= it.next().getValue().expiresAtNanos) {
                        it.remove();
                        stats.recordExpiration();
                    } else {
                        live++;
                    }
                }
            } finally {
                segment.lock.writeLock().unlock();
            }
        }
        return live;
    }

    @Override
    public CacheStats stats() {
        return stats;
    }

    private Optional<V> expireIfPresentAndRecordMiss(Segment<K, V> segment, K key, long now) {
        segment.lock.writeLock().lock();
        try {
            Entry<V> entry = segment.map.get(key);
            if (entry != null && now >= entry.expiresAtNanos) {
                segment.map.remove(key);
                stats.recordExpiration();
            }
            stats.recordMiss();
            return Optional.empty();
        } finally {
            segment.lock.writeLock().unlock();
        }
    }

    private void evictOverflow(Segment<K, V> segment) {
        while (segment.map.size() > capacityPerSegment) {
            K victim = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<K, Entry<V>> candidate : segment.map.entrySet()) {
                long lastAccess = candidate.getValue().lastAccessNanos.get();
                if (lastAccess < oldest) {
                    oldest = lastAccess;
                    victim = candidate.getKey();
                }
            }
            segment.map.remove(victim);
            stats.recordEviction();
        }
    }

    private Segment<K, V> segmentFor(K key) {
        Objects.requireNonNull(key, "key");
        return segments.get(Segments.index(key, mask));
    }
}
