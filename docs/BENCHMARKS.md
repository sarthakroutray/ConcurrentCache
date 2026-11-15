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
mvn -B -pl cache-bench -am test \
  -Dtest=EvictionQualityTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Workload: capacity 64, key space 512, 200,000 get-then-put operations, seed 42.
For each operation, half the time a key is chosen uniformly from the first 51
keys; otherwise a key is chosen uniformly from the remaining 461 keys. This is
a two-tier uniform distribution, not a Zipf distribution.

Raw output:

```
global           hitRatio=0.3056
segmented_a      hitRatio=0.3086
segmented_b      hitRatio=0.3086
concurrent_map   hitRatio=0.0865
```

### Honest interpretation

Interpretation is limited to this workload and seed:

- `ConcurrentMapCache` scored 0.0865, compared with 0.3056–0.3086 for the three
  LRU variants. This fixed test shows a lower hit ratio for the unordered
  baseline under this operation sequence; it does not estimate performance over
  a population of workloads.
- `segmented_a` and `segmented_b` scored 0.3086, while `global` scored 0.3056.
  The test uses a single seed and does not measure variation across seeds or key
  distributions, so it cannot establish whether this small difference is
  meaningful or explain its cause.
- `EvictionQualityTest` asserts only that all LRU implementations score higher
  than the baseline for this fixed input, and prints all scores. It does not
  assert an ordering among the LRU variants.

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
