package com.concurrentcache.bench;

import com.concurrentcache.core.Cache;
import com.concurrentcache.core.ConcurrentMapCache;
import com.concurrentcache.core.GlobalLockCache;
import com.concurrentcache.core.SegmentedLockCache;
import com.concurrentcache.core.SegmentedRwLockCache;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Read and mixed read/write throughput for the four implementations under the
 * same load. Every benchmark runs with 8 threads against a shared cache, which
 * is the configuration where the locking design actually matters: with one
 * thread all four implementations would look identical.
 *
 * <p>Two working sets are prepared so contention can be compared directly:
 * a 16-key hot set (nearly every operation touches the same few keys, so lock
 * contention dominates) and a 1024-key set (operations rarely collide, so
 * per-operation overhead dominates). TTLs are one hour, so no benchmark measures
 * the clock advancing; TTL cost is the lazy check on the hot path, not expiry.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@Threads(8)
@State(Scope.Benchmark)
public class CacheBenchmark {

    private static final int SMALL_KEYS = 16;
    private static final int LARGE_KEYS = 1024;
    private static final int CAPACITY = 4096;
    private static final Duration TTL = Duration.ofHours(1);

    @Param({"global", "segmented_a", "segmented_b", "concurrent_map"})
    private String implementation;

    private Cache<String, String> small;
    private Cache<String, String> large;
    private String[] smallKeys;
    private String[] largeKeys;

    @Setup(Level.Trial)
    public void setUp() {
        small = newCache(CAPACITY);
        large = newCache(CAPACITY);
        smallKeys = keys(SMALL_KEYS);
        largeKeys = keys(LARGE_KEYS);
        for (String key : smallKeys) {
            small.put(key, "value-" + key, TTL);
        }
        for (String key : largeKeys) {
            large.put(key, "value-" + key, TTL);
        }
    }

    @Benchmark
    public void readHotSet(Blackhole blackhole) {
        blackhole.consume(small.get(randomOf(smallKeys)));
    }

    @Benchmark
    public void readWideSet(Blackhole blackhole) {
        blackhole.consume(large.get(randomOf(largeKeys)));
    }

    /**
     * 90% reads, 10% writes against the hot set: the shape of a cache in front
     * of a read-heavy service, where the write path takes the exclusive lock.
     */
    @Benchmark
    public void mixedReadWriteHotSet() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (random.nextInt(10) == 0) {
            small.put(randomOf(smallKeys), "updated", TTL);
        } else {
            small.get(randomOf(smallKeys));
        }
    }

    private Cache<String, String> newCache(int capacity) {
        return switch (implementation) {
            case "global" -> new GlobalLockCache<>(capacity, TTL);
            case "segmented_a" -> new SegmentedLockCache<>(capacity, 16, TTL);
            case "segmented_b" -> new SegmentedRwLockCache<>(capacity, 16, TTL);
            case "concurrent_map" -> new ConcurrentMapCache<>(capacity, TTL);
            default -> throw new IllegalArgumentException("unknown implementation: " + implementation);
        };
    }

    private static String[] keys(int count) {
        String[] keys = new String[count];
        for (int i = 0; i < count; i++) {
            keys[i] = "key-" + i;
        }
        return keys;
    }

    private static String randomOf(String[] keys) {
        return keys[ThreadLocalRandom.current().nextInt(keys.length)];
    }
}
