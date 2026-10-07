package com.concurrentcache.core;

import org.junit.jupiter.api.RepeatedTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Concurrency contract shared by every implementation. Each test starts its
 * workers behind a {@link CountDownLatch} so the threads really overlap, and is
 * wrapped in {@link org.junit.jupiter.api.Assertions#assertTimeoutPreemptively}
 * so a deadlock or livelock fails the test instead of hanging the build. Tests
 * run 50 times each to catch races that only appear occasionally.
 *
 * <p>TTLs are long and the clock is the real one here: these tests are about
 * locking, not about time.
 */
abstract class AbstractConcurrentCacheTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    protected abstract <K, V> Cache<K, V> newCache(int capacity, Duration defaultTtl, LongSupplier timeSource);

    protected <K, V> Cache<K, V> newCache(int capacity) {
        return newCache(capacity, Duration.ofMinutes(10), System::nanoTime);
    }

    @RepeatedTest(50)
    void noLostUpdatesUnderContention() {
        assertTimeoutPreemptively(TIMEOUT, () -> {
            Cache<String, Long> cache = newCache(100);
            List<Throwable> failures = runWorkers(32, workerId -> {
                for (int i = 0; i < 10_000; i++) {
                    cache.compute("counter", (key, value) -> value == null ? 1L : value + 1);
                }
            });
            assertTrue(failures.isEmpty(), () -> "worker failures: " + failures);
            assertEquals(java.util.Optional.of(320_000L), cache.get("counter"));
        });
    }

    @RepeatedTest(50)
    void capacityInvariantHoldsAfterMixedLoad() {
        assertTimeoutPreemptively(TIMEOUT, () -> {
            int capacity = 200;
            Cache<String, Long> cache = newCache(capacity);
            List<Throwable> failures = runWorkers(16, workerId -> {
                Random random = new Random(workerId);
                for (int i = 0; i < 5_000; i++) {
                    String key = "key-" + random.nextInt(1_000);
                    switch (random.nextInt(3)) {
                        case 0 -> cache.put(key, (long) i, Duration.ofMinutes(10));
                        case 1 -> cache.get(key);
                        default -> cache.delete(key);
                    }
                }
            });
            assertTrue(failures.isEmpty(), () -> "worker failures: " + failures);
            assertTrue(cache.size() <= capacity, () -> "size exceeded capacity: " + cache.size());
        });
    }

    @RepeatedTest(50)
    void everyGetIsAccountedAsHitOrMiss() {
        assertTimeoutPreemptively(TIMEOUT, () -> {
            Cache<String, Long> cache = newCache(1_000);
            for (int i = 0; i < 500; i++) {
                cache.put("present-" + i, (long) i, Duration.ofMinutes(10));
            }
            LongAdder getsIssued = new LongAdder();
            List<Throwable> failures = runWorkers(16, workerId -> {
                Random random = new Random(workerId);
                for (int i = 0; i < 20_000; i++) {
                    String key = random.nextBoolean()
                            ? "present-" + random.nextInt(500)
                            : "absent-" + random.nextInt(500);
                    getsIssued.increment();
                    cache.get(key);
                }
            });
            assertTrue(failures.isEmpty(), () -> "worker failures: " + failures);
            CacheStats stats = cache.stats();
            assertEquals(getsIssued.sum(), stats.hits() + stats.misses());
        });
    }

    @RepeatedTest(50)
    void mixedLoadOnSmallHotKeyRangeCompletes() {
        assertTimeoutPreemptively(TIMEOUT, () -> {
            int capacity = 64;
            Cache<String, Long> cache = newCache(capacity);
            List<Throwable> failures = runWorkers(24, workerId -> {
                Random random = new Random(workerId);
                for (int i = 0; i < 20_000; i++) {
                    String key = "hot-" + random.nextInt(16);
                    switch (random.nextInt(4)) {
                        case 0 -> cache.put(key, (long) i, Duration.ofMinutes(10));
                        case 1 -> cache.get(key);
                        case 2 -> cache.delete(key);
                        default -> cache.compute(key, (k, v) -> v == null ? 1L : v + 1);
                    }
                }
            });
            assertTrue(failures.isEmpty(), () -> "worker failures: " + failures);
            assertTrue(cache.size() <= capacity, () -> "size exceeded capacity: " + cache.size());
        });
    }

    @RepeatedTest(50)
    void workersNeverThrowUnderMixedOperations() {
        assertTimeoutPreemptively(TIMEOUT, () -> {
            Cache<String, Long> cache = newCache(128);
            List<Throwable> failures = runWorkers(16, workerId -> {
                Random random = new Random(workerId);
                for (int i = 0; i < 10_000; i++) {
                    String key = "key-" + random.nextInt(200);
                    switch (random.nextInt(6)) {
                        case 0 -> cache.put(key, (long) i, Duration.ofMinutes(10));
                        case 1 -> cache.get(key);
                        case 2 -> cache.delete(key);
                        case 3 -> cache.compute(key, (k, v) -> v == null ? 1L : v + 1);
                        case 4 -> cache.size();
                        default -> cache.stats().hitRatio();
                    }
                }
            });
            assertTrue(failures.isEmpty(), () -> "worker failures: " + failures);
        });
    }

    private static List<Throwable> runWorkers(int threadCount, Consumer<Integer> worker)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threadCount);
        try {
            for (int i = 0; i < threadCount; i++) {
                int workerId = i;
                pool.execute(() -> {
                    try {
                        startGate.await();
                        worker.accept(workerId);
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(finished.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "workers did not finish in time");
        } finally {
            pool.shutdownNow();
        }
        return new ArrayList<>(failures);
    }
}
