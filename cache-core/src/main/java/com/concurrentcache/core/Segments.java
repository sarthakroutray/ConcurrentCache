package com.concurrentcache.core;

/**
 * Segment routing shared by the segmented implementations: the segment count is
 * rounded down to a power of two, then a spread hash is masked into a segment
 * index. Mixing the high bits into the low ones matters because the mask only
 * looks at the low bits.
 */
final class Segments {

    private Segments() {
    }

    /**
     * Returns the largest power of two not exceeding both {@code requested} and
     * {@code capacity}, so every segment gets at least one slot and the sum of
     * the segment capacities never exceeds the configured capacity.
     */
    static int count(int requested, int capacity) {
        return Math.max(1, Integer.highestOneBit(Math.min(requested, capacity)));
    }

    static int index(Object key, int mask) {
        int hash = key.hashCode();
        hash ^= hash >>> 16;
        return hash & mask;
    }
}
