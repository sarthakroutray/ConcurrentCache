# Benchmarks: raw results

Every number in the README comes from these runs. Nothing here is estimated.

## Test machine

```
CPU:      AMD Ryzen 7 260 w/ Radeon 780M Graphics
          CPU(s): 16, Thread(s) per core: 2, Core(s) per socket: 8, Socket(s): 1
Memory:   14 GiB total
OS:       Linux 7.0.0-43-generic x86_64
JVM:      OpenJDK 25.0.5 (build 25.0.5.0.3, Ubuntu)
Maven:    3.9.12
Compiler: Maven 3.13.0 plugin, --release 17
```

## JMH throughput

Command:

```bash
mvn -B -pl cache-bench -am package -DskipTests
java -jar cache-bench/target/benchmarks.jar -wi 3 -i 5 -w 1 -r 1 -f 2 -rf json -rff bench-results.json
```

Parameters: JMH 1.37, `Mode.Throughput`, 8 threads, 3 warmup + 5 measurement
iterations of 1 second, 2 forks. Cache capacity 4096, TTL 1 hour (so no expiry
occurs mid-run), 16 segments for the segmented implementations.

Raw output:

```
Benchmark                            (implementation)   Mode  Cnt          Score          Error  Units
CacheBenchmark.mixedReadWriteHotSet            global  thrpt   10   16432790.053 ±   503327.005  ops/s
CacheBenchmark.mixedReadWriteHotSet       segmented_a  thrpt   10   22521494.077 ±   507337.188  ops/s
CacheBenchmark.mixedReadWriteHotSet       segmented_b  thrpt   10   21477218.020 ±   332997.871  ops/s
CacheBenchmark.mixedReadWriteHotSet    concurrent_map  thrpt   10  151502894.540 ±  6051377.961  ops/s
CacheBenchmark.readHotSet                      global  thrpt   10   17486315.230 ±   620151.814  ops/s
CacheBenchmark.readHotSet                 segmented_a  thrpt   10   25272894.202 ±   814718.618  ops/s
CacheBenchmark.readHotSet                 segmented_b  thrpt   10   52588166.517 ±  1048695.648  ops/s
CacheBenchmark.readHotSet              concurrent_map  thrpt   10  266047906.747 ± 13293893.338  ops/s
CacheBenchmark.readWideSet                     global  thrpt   10    8645633.790 ±   375057.974  ops/s
CacheBenchmark.readWideSet                segmented_a  thrpt   10   16708173.992 ±   192830.287  ops/s
CacheBenchmark.readWideSet                segmented_b  thrpt   10   51746806.035 ±  1228375.983  ops/s
CacheBenchmark.readWideSet             concurrent_map  thrpt   10  188110760.506 ± 16053909.331  ops/s
```

### Derived ratios (from the table above)

| Comparison | Hot read set | Wide read set |
|---|---:|---:|
| `segmented_a` / `global` | 25.27M / 17.49M = **1.45×** | 16.71M / 8.65M = **1.93×** |
| `segmented_b` / `global` | 52.59M / 17.49M = **3.01×** | 51.75M / 8.65M = **5.99×** |
| `segmented_b` / `segmented_a` | 52.59M / 25.27M = **2.08×** | 51.75M / 16.71M = **3.10×** |
| `concurrent_map` / `segmented_b` | 266.05M / 52.59M = **5.06×** | 188.11M / 51.75M = **3.64×** |

Under the mixed 90/10 workload the picture changes:

| Comparison | Result |
|---|---:|
| `segmented_a` / `global` | 22.52M / 16.43M = **1.37×** |
| `segmented_b` / `global` | 21.48M / 16.43M = **1.31×** |
| `segmented_b` / `segmented_a` | 21.48M / 22.52M = **0.95×** |

Note the last row: with 10% writes mixed in, the read/write-lock variant is 5%
*slower* than the plain segmented variant. Its advantage is read-only parallelism,
and it disappears as soon as the workload writes.

## Eviction quality

Throughput does not tell you whether eviction is any good. This is a deterministic
hit-ratio measurement, not a timing benchmark, so it is a plain JUnit test.

Command:

```bash
mvn -B -pl cache-bench -am test -Dtest=EvictionQualityTest
```

Workload: capacity 64, key space 512, 200,000 get-then-put operations, seed 42,
half the traffic aimed at the first 10% of the key space (skewed Zipf-like access,
the shape a real cache sees).

Raw output:

```
global           hitRatio=0.3056
segmented_a      hitRatio=0.3086
segmented_b      hitRatio=0.3086
concurrent_map   hitRatio=0.0865
```

### Honest interpretation

The result contradicts the naive expectation that global LRU should win, so it is
worth being precise about it:

- **The baseline loses badly, as predicted** — 0.0865 vs 0.3056, a 3.5× gap. With
  no ordering bookkeeping, `ConcurrentMapCache` throws away entries in iteration
  order and destroys its own hot set. DR-008 says eviction order is a deliberate
  non-goal for the baseline; this quantifies the price.
- **Segmenting did *not* measurably hurt LRU quality here.** `segmented_a` and
  `segmented_b` came out at 0.3086, marginally *above* `global` at 0.3056. DR-006
  predicted that segmenting makes global LRU approximate and therefore worse. On
  this particular workload it did not: with capacity 64 over 16 segments each
  segment holds 4 slots, and the skew is shallow enough that each segment's local
  LRU catches most of its own reuse. A harsher skew (or a key distribution that
  concentrates into few segments) would be needed to expose the penalty.
- Because the difference between the LRU variants is within noise and the
  direction is not what DR-006 predicted, `EvictionQualityTest` asserts **only**
  the robust claim — every LRU variant beats the unordered baseline — and prints
  the rest instead of pretending to a margin the data does not support.

## Reproducing

```bash
mvn -B clean verify                        # all tests
mvn -B -pl cache-bench -am package -DskipTests
java -jar cache-bench/target/benchmarks.jar -l    # list benchmarks
```

Warning: these numbers are wildly sensitive to machine state. Running inside a VM,
under thermal throttling, or alongside other work will shift them substantially.
`lscpu` above reports `CPU(s) scaling MHz: 66%` on the test machine, so the
absolute figures are conservative.
