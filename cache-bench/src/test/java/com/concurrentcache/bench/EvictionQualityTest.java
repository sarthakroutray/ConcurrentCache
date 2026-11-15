package com.concurrentcache.bench;

import com.concurrentcache.core.Cache;
import com.concurrentcache.core.ConcurrentMapCache;
import com.concurrentcache.core.GlobalLockCache;
import com.concurrentcache.core.SegmentedLockCache;
import com.concurrentcache.core.SegmentedRwLockCache;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures eviction quality as a hit ratio, and writes the numbers to
 * {@code target/eviction-quality.txt} as evidence.
 *
 * <p>This is a deterministic measurement rather than a timing benchmark: one
 * seeded workload runs against every implementation and only the hit ratio is
 * compared, so the result does not depend on machine speed.
 *
 * <p>The only directional claim asserted here is the one the data supports
 * robustly: LRU ordering beats the unordered baseline by a wide margin. Between
 * the LRU variants the numbers come out close to each other, so no inequality
 * is asserted there; the raw figures are reported instead (see
 * docs/BENCHMARKS.md).
 */
class EvictionQualityTest {

    private static final int CAPACITY = 64;
    private static final int KEY_SPACE = 512;
    private static final int OPERATIONS = 200_000;
    private static final long SEED = 42L;
    private static final Duration TTL = Duration.ofHours(1);

    @Test
    void everyLruImplementationBeatsTheUnorderedBaseline() throws IOException {
        Map<String, Double> ratios = new LinkedHashMap<>();
        ratios.put("global", hitRatio(new GlobalLockCache<>(CAPACITY, TTL)));
        ratios.put("segmented_a", hitRatio(new SegmentedLockCache<>(CAPACITY, 16, TTL)));
        ratios.put("segmented_b", hitRatio(new SegmentedRwLockCache<>(CAPACITY, 16, TTL)));
        ratios.put("concurrent_map", hitRatio(new ConcurrentMapCache<>(CAPACITY, TTL)));

        double baseline = ratios.get("concurrent_map");
        for (Map.Entry<String, Double> entry : ratios.entrySet()) {
            if (!entry.getKey().equals("concurrent_map")) {
                assertTrue(entry.getValue() > baseline,
                        () -> entry.getKey() + " should beat the unordered baseline: "
                                + entry.getValue() + " vs " + baseline);
            }
        }

        StringBuilder report = new StringBuilder();
        report.append("workload: capacity=").append(CAPACITY)
                .append(" keySpace=").append(KEY_SPACE)
                .append(" operations=").append(OPERATIONS)
                .append(" seed=").append(SEED).append(System.lineSeparator());
        ratios.forEach((name, ratio) -> {
            String line = String.format("%-16s hitRatio=%.4f%n", name, ratio);
            System.out.print(line);
            report.append(line);
        });
        Path out = Path.of("target", "eviction-quality.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
    }

    private static double hitRatio(Cache<String, String> cache) {
        Random random = new Random(SEED);
        for (int i = 0; i < OPERATIONS; i++) {
            String key = "key-" + skewedIndex(random);
            if (cache.get(key).isEmpty()) {
                cache.put(key, "value", TTL);
            }
        }
        return cache.stats().hitRatio();
    }

    /**
     * Skewed access: half the traffic lands in the first tenth of the key space,
     * the rest is spread over the remainder. Uniform random over more keys than
     * the capacity would make every implementation miss at the same rate.
     */
    private static int skewedIndex(Random random) {
        int hot = KEY_SPACE / 10;
        return random.nextInt(2) == 0 ? random.nextInt(hot) : hot + random.nextInt(KEY_SPACE - hot);
    }
}
