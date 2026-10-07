package com.concurrentcache.core;

import java.time.Duration;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * A thread-safe in-memory key-value cache with per-entry time-to-live (TTL).
 *
 * <p>Contracts shared by every implementation:
 * <ul>
 *   <li>Keys and values are never null.</li>
 *   <li>An entry past its TTL is invisible to every operation; the first operation
 *       that encounters it removes it and counts one expiration.</li>
 *   <li>Every method is thread-safe; {@link #compute} is atomic per key.</li>
 * </ul>
 */
public interface Cache<K, V> {

    /**
     * Returns the value for {@code key} if present and not expired, otherwise empty.
     * A miss caused by an expired entry counts one expiration and one miss.
     */
    Optional<V> get(K key);

    /**
     * Stores {@code value} under {@code key} with the given TTL measured from now,
     * replacing any previous mapping. The TTL must be positive.
     */
    void put(K key, V value, Duration ttl);

    /**
     * Removes the mapping for {@code key}; returns {@code true} if one was removed.
     */
    boolean delete(K key);

    /**
     * Atomically applies {@code remappingFunction} to the mapping for {@code key}.
     * The function receives the current value, or {@code null} if the key is absent
     * or expired, and returns the new value. A {@code null} return removes the
     * mapping (ConcurrentMap semantics). A newly created mapping gets the cache's
     * default TTL; an updated mapping keeps its remaining TTL.
     *
     * @return the new value, or {@code null} if no mapping exists afterwards
     */
    V compute(K key, BiFunction<K, V, V> remappingFunction);

    /**
     * Returns the number of non-expired entries; expired entries encountered while
     * counting are dropped.
     */
    long size();

    /**
     * Returns this cache's statistics.
     */
    CacheStats stats();
}
