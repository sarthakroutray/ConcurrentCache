package com.concurrentcache.server.sweeper;

import com.concurrentcache.core.SegmentSweepable;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Background cleaner for lazily expired entries. One daemon thread wakes on a
 * fixed delay and sweeps exactly one segment per tick, round robin, so each tick
 * holds one segment lock for a short time instead of pausing the whole cache.
 * Spring calls {@link #start()} and {@link #stop()} as part of the application
 * lifecycle.
 */
public final class TtlSweeper implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TtlSweeper.class);

    private final SegmentSweepable cache;
    private final long intervalMillis;
    private final AtomicInteger nextSegment = new AtomicInteger();
    private volatile ScheduledExecutorService executor;
    private volatile boolean running;

    public TtlSweeper(SegmentSweepable cache, Duration interval) {
        this.cache = Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(interval, "interval");
        this.intervalMillis = interval.toMillis();
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("sweep interval must be positive");
        }
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cache-ttl-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::sweepOnce, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        ScheduledExecutorService current = executor;
        if (current != null) {
            current.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Sweeps one segment and returns how many expired entries were removed. Also
     * called directly by tests.
     */
    public int sweepOnce() {
        try {
            int segmentCount = cache.segmentCount();
            int index = Math.floorMod(nextSegment.getAndIncrement(), segmentCount);
            return cache.sweepSegment(index);
        } catch (RuntimeException e) {
            log.warn("TTL sweep failed, continuing", e);
            return 0;
        }
    }
}
