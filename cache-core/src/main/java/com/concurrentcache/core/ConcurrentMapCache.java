package com.concurrentcache.core;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/**
 * Thin {@link ConcurrentHashMap} baseline used as a comparison point for the
 * locking implementations. TTL is checked lazily like everywhere else, but size
 * bounding is deliberately simple: expired entries are purged first and any
 * remaining overflow is removed in iteration order, which is not LRU. That is
 * the point of a baseline: no access bookkeeping, no ordering guarantee.
 */
public final class ConcurrentMapCache<K, V> implements Cache<K, V> {

    private record Entry<V>(V value, long expiresAtNanos) {
    }

    private final int capacity;
    private final long defaultTtlNanos;
    private final LongSupplier timeSource;
    private final ConcurrentHashMap<K, Entry<V>> map = new ConcurrentHashMap<>();
    private final CacheStats stats = new CacheStats();

    public ConcurrentMapCache(int capacity, Duration defaultTtl) {
        this(capacity, defaultTtl, System::nanoTime);
    }

    public ConcurrentMapCache(int capacity, Duration defaultTtl, LongSupplier timeSource) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.defaultTtlNanos = Ttl.positiveNanos(defaultTtl, "defaultTtl");
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
    }

    @Override
    public Optional<V> get(K key) {
        Objects.requireNonNull(key, "key");
        long now = timeSource.getAsLong();
        Entry<V> entry = map.get(key);
        if (entry == null) {
            stats.recordMiss();
            return Optional.empty();
        }
        if (now >= entry.expiresAtNanos()) {
            map.remove(key, entry);
            stats.recordExpiration();
            stats.recordMiss();
            return Optional.empty();
        }
        stats.recordHit();
        return Optional.of(entry.value());
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        long ttlNanos = Ttl.positiveNanos(ttl, "ttl");
        long now = timeSource.getAsLong();
        map.put(key, new Entry<>(value, now + ttlNanos));
        enforceCapacity(now);
    }

    @Override
    public boolean delete(K key) {
        Objects.requireNonNull(key, "key");
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
    }

    @Override
    public V compute(K key, BiFunction<K, V, V> remappingFunction) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(remappingFunction, "remappingFunction");
        long now = timeSource.getAsLong();
        Entry<V> result = map.compute(key, (k, current) -> {
            Entry<V> existing = current;
            if (existing != null && now >= existing.expiresAtNanos()) {
                stats.recordExpiration();
                existing = null;
            }
            V updated = remappingFunction.apply(k, existing == null ? null : existing.value());
            if (updated == null) {
                return null;
            }
            long expiresAtNanos = existing == null ? now + defaultTtlNanos : existing.expiresAtNanos();
            return new Entry<>(updated, expiresAtNanos);
        });
        enforceCapacity(now);
        return result == null ? null : result.value();
    }

    @Override
    public long size() {
        long now = timeSource.getAsLong();
        long live = 0;
        Iterator<Map.Entry<K, Entry<V>>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            if (now >= it.next().getValue().expiresAtNanos()) {
                it.remove();
                stats.recordExpiration();
            } else {
                live++;
            }
        }
        return live;
    }

    @Override
    public CacheStats stats() {
        return stats;
    }

    private void enforceCapacity(long now) {
        if (map.size() <= capacity) {
            return;
        }
        map.entrySet().removeIf(entry -> {
            if (now >= entry.getValue().expiresAtNanos()) {
                stats.recordExpiration();
                return true;
            }
            return false;
        });
        Iterator<K> it = map.keySet().iterator();
        while (map.size() > capacity && it.hasNext()) {
            it.next();
            it.remove();
            stats.recordEviction();
        }
    }
}
