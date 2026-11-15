package com.concurrentcache.core;

/**
 * Capability of caches that can be cleaned segment by segment. A background
 * sweeper uses it to hold one segment lock for a short time per tick instead of
 * locking the whole cache, which keeps write latency spikes small.
 */
public interface SegmentSweepable {

    /**
     * Number of independently locked parts of the cache; always at least 1.
     */
    int segmentCount();

    /**
     * Removes every expired entry in one part and returns how many were removed.
     */
    int sweepSegment(int index);
}
