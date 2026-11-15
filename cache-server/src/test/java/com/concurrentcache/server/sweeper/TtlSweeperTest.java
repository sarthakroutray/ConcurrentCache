package com.concurrentcache.server.sweeper;

import com.concurrentcache.core.GlobalLockCache;
import com.concurrentcache.core.SegmentSweepable;
import com.concurrentcache.server.support.MutableNanoClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sweeper behaviour with a deterministic clock: only expired entries go, and
 * each tick touches exactly one segment.
 */
class TtlSweeperTest {

    private final MutableNanoClock clock = new MutableNanoClock();
    private final GlobalLockCache<String, String> cache =
            new GlobalLockCache<>(10, Duration.ofSeconds(60), clock);

    @Test
    void sweepRemovesOnlyExpiredEntries() {
        cache.put("short", "1", Duration.ofSeconds(5));
        cache.put("long", "2", Duration.ofSeconds(60));
        clock.advance(Duration.ofSeconds(6));
        TtlSweeper sweeper = new TtlSweeper(cache, Duration.ofSeconds(1));

        assertEquals(1, sweeper.sweepOnce());
        assertEquals(1, cache.size());
        assertEquals(1, cache.stats().expirations());
    }

    @Test
    void sweepsOneSegmentPerTickRoundRobin() {
        List<Integer> swept = new ArrayList<>();
        SegmentSweepable counted = new SegmentSweepable() {
            @Override
            public int segmentCount() {
                return 3;
            }

            @Override
            public int sweepSegment(int index) {
                swept.add(index);
                return 0;
            }
        };
        TtlSweeper sweeper = new TtlSweeper(counted, Duration.ofSeconds(1));

        sweeper.sweepOnce();
        sweeper.sweepOnce();
        sweeper.sweepOnce();
        sweeper.sweepOnce();

        assertEquals(List.of(0, 1, 2, 0), swept);
    }

    @Test
    void lifecycleStartsAndStopsTheSweeper() {
        TtlSweeper sweeper = new TtlSweeper(cache, Duration.ofMillis(50));
        assertFalse(sweeper.isRunning());
        sweeper.start();
        assertTrue(sweeper.isRunning());
        sweeper.stop();
        assertFalse(sweeper.isRunning());
    }
}
