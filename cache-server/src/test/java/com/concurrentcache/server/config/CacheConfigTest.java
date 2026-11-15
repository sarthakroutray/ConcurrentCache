package com.concurrentcache.server.config;

import com.concurrentcache.core.Cache;
import com.concurrentcache.core.ConcurrentMapCache;
import com.concurrentcache.core.GlobalLockCache;
import com.concurrentcache.core.SegmentSweepable;
import com.concurrentcache.core.SegmentedLockCache;
import com.concurrentcache.core.SegmentedRwLockCache;
import com.concurrentcache.server.config.CacheProperties.Implementation;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Verifies the implementation switch and the wiring of capacity and segments
 * without starting a Spring context.
 */
class CacheConfigTest {

    private final CacheConfig config = new CacheConfig();

    @Test
    void buildsTheImplementationSelectedByConfiguration() {
        assertInstanceOf(GlobalLockCache.class, config.cache(properties(Implementation.GLOBAL)));
        assertInstanceOf(SegmentedLockCache.class, config.cache(properties(Implementation.SEGMENTED_A)));
        assertInstanceOf(SegmentedRwLockCache.class, config.cache(properties(Implementation.SEGMENTED_B)));
        assertInstanceOf(ConcurrentMapCache.class, config.cache(properties(Implementation.CONCURRENT_MAP)));
    }

    @Test
    void everyImplementationCanBeSweptSegmentBySegment() {
        for (Implementation implementation : Implementation.values()) {
            Cache<String, String> cache = config.cache(properties(implementation));
            assertInstanceOf(SegmentSweepable.class, cache);
            assertEquals(expectedSegmentCount(implementation), ((SegmentSweepable) cache).segmentCount());
        }
    }

    private static int expectedSegmentCount(Implementation implementation) {
        return switch (implementation) {
            case SEGMENTED_A, SEGMENTED_B -> 4;
            case GLOBAL, CONCURRENT_MAP -> 1;
        };
    }

    private static CacheProperties properties(Implementation implementation) {
        return new CacheProperties(implementation, 100, 4, Duration.ofSeconds(60), 4096, Duration.ofSeconds(30));
    }
}
