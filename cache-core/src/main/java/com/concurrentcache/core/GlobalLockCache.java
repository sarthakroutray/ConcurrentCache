package com.concurrentcache.core;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/**
 * The baseline engine: one {@link ReentrantLock} guarding an access-ordered
 * {@link LinkedHashMap}, so eviction is exact LRU. Expiry is lazy: an entry is
 * dropped the first time an operation finds it past its TTL.
 *
 * <p>Every operation serializes on the single lock, which makes correctness easy
 * to argue and gives atomicity for {@link #compute} for free.
 */
public final class GlobalLockCache<K, V> implements Cache<K, V> {

    private record Entry<V>(V value, long expiresAtNanos) {
    }

    private final int capacity;
    private final long defaultTtlNanos;
    private final LongSupplier timeSource;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<K, Entry<V>> map = new LinkedHashMap<>(16, 0.75f, true);
    private final CacheStats stats = new CacheStats();

    public GlobalLockCache(int capacity, Duration defaultTtl) {
        this(capacity, defaultTtl, System::nanoTime);
    }

    public GlobalLockCache(int capacity, Duration defaultTtl, LongSupplier timeSource) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.defaultTtlNanos = positiveNanos(defaultTtl, "defaultTtl");
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
    }

    @Override
    public Optional<V> get(K key) {
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            long now = timeSource.getAsLong();
            Entry<V> entry = map.get(key);
            if (entry == null) {
                stats.recordMiss();
                return Optional.empty();
            }
            if (now >= entry.expiresAtNanos()) {
                map.remove(key);
                stats.recordExpiration();
                stats.recordMiss();
                return Optional.empty();
            }
            stats.recordHit();
            return Optional.of(entry.value());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        long ttlNanos = positiveNanos(ttl, "ttl");
        lock.lock();
        try {
            long now = timeSource.getAsLong();
            map.put(key, new Entry<>(value, now + ttlNanos));
            evictOverflow();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean delete(K key) {
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            long now = timeSource.getAsLong();
            Entry<V> entry = map.remove(key);
            if (entry == null) {
                return false;
            }
            if (now >= entry.expiresAtNanos()) {
                stats.recordExpiration();
                return false;
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public V compute(K key, BiFunction<K, V, V> remappingFunction) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(remappingFunction, "remappingFunction");
        lock.lock();
        try {
            long now = timeSource.getAsLong();
            Entry<V> entry = map.get(key);
            if (entry != null && now >= entry.expiresAtNanos()) {
                map.remove(key);
                stats.recordExpiration();
                entry = null;
            }
            V updated = remappingFunction.apply(key, entry == null ? null : entry.value());
            if (updated == null) {
                map.remove(key);
                return null;
            }
            long expiresAtNanos = entry == null ? now + defaultTtlNanos : entry.expiresAtNanos();
            map.put(key, new Entry<>(updated, expiresAtNanos));
            evictOverflow();
            return updated;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long size() {
        lock.lock();
        try {
            long now = timeSource.getAsLong();
            long live = 0;
            Iterator<Entry<V>> it = map.values().iterator();
            while (it.hasNext()) {
                if (now >= it.next().expiresAtNanos()) {
                    it.remove();
                    stats.recordExpiration();
                } else {
                    live++;
                }
            }
            return live;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public CacheStats stats() {
        return stats;
    }

    private void evictOverflow() {
        Iterator<K> it = map.keySet().iterator();
        while (map.size() > capacity && it.hasNext()) {
            it.next();
            it.remove();
            stats.recordEviction();
        }
    }

    private static long positiveNanos(Duration ttl, String name) {
        Objects.requireNonNull(ttl, name);
        long nanos = ttl.toNanos();
        if (nanos <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return nanos;
    }
}
