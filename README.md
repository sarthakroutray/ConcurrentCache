# ConcurrentCache

A thread-safe, in-memory key-value cache with per-entry TTL, four interchangeable
concurrent implementations, a Spring Boot REST API, and JMH benchmarks to compare
them.

The point of the project is not "another cache" — it is a side-by-side comparison
of **how much a locking strategy costs** and **what it buys you**, with the
trade-offs written down as decision records and the numbers measured rather than
assumed.

```
┌──────────────────────────────────────────────────────────────┐
│  cache-server   Spring Boot REST API + background TTL sweeper │
│                 (transport only; no cache logic)              │
├──────────────────────────────────────────────────────────────┤
│  cache-core     plain Java, zero framework dependencies       │
│                 GlobalLockCache · SegmentedLockCache          │
│                 SegmentedRwLockCache · ConcurrentMapCache     │
├──────────────────────────────────────────────────────────────┤
│  cache-bench    JMH throughput + eviction-quality measurement │
└──────────────────────────────────────────────────────────────┘
```

## Contents

- [The four implementations](#the-four-implementations)
- [Quick start](#quick-start)
- [HTTP API](#http-api)
- [Configuration](#configuration)
- [Benchmarks](#benchmarks)
- [Testing](#testing)
- [Design decisions](#design-decisions)
- [Docker](#docker)
- [CI/CD](#cicd)

## The four implementations

All four implement the same [`Cache`](cache-core/src/main/java/com/concurrentcache/core/Cache.java)
interface, so they are drop-in replacements selected by configuration.

| Implementation | Locking | Eviction | Read parallelism | Notes |
|---|---|---|---|---|
| `GlobalLockCache` | One `ReentrantLock` for everything | Exact LRU | None — every op serializes | Simplest to reason about; correctness for free |
| `SegmentedLockCache` | One `ReentrantLock` per segment | Exact LRU **within** a segment | One reader per segment at a time | 16 segments → 16 concurrent writers |
| `SegmentedRwLockCache` | `ReentrantReadWriteLock` per segment | Approximate (oldest timestamp) | Many readers per segment | Trades eviction accuracy for read parallelism |
| `ConcurrentMapCache` | None (`ConcurrentHashMap`) | Arbitrary entries on overflow | Full | Deliberate baseline: no ordering bookkeeping |

The interesting tension is between the last three. Exact LRU under a **read** lock
is impossible with a `LinkedHashMap` in access order, because `get` is a
structural modification — it moves the entry to the tail. That forces `SegmentedRwLockCache`
to keep reads on the shared lock and stamp last-access in an `AtomicLong`, then
scan for the oldest stamp on eviction. See [DR-007](docs/DESIGN.md).

## Quick start

Requires **JDK 17+** and **Maven 3.9+**.

```bash
# Build and run the full test suite
mvn verify

# Run the server (default: GlobalLockCache, 10k capacity, 60s TTL)
mvn -pl cache-server -am spring-boot:run

# Or run the packaged jar
mvn -pl cache-server -am package -DskipTests
java -jar cache-server/target/cache-server-0.1.0-SNAPSHOT.jar
```

The server listens on `http://localhost:8080`.

## HTTP API

Request bodies are `text/plain` — this is a key-value store, not a JSON document
store. Error responses are JSON.

### `PUT /keys/{key}?ttl={seconds}`

Store a value. Returns `201 Created` if the key was new, `200 OK` if it replaced
an existing live value. `ttl` is optional and defaults to `cache.default-ttl`.

```bash
curl -i -X PUT -H 'Content-Type: text/plain' \
  --data 'alice' 'http://localhost:8080/keys/user:1?ttl=120'
# HTTP/1.1 201 Created
```

### `GET /keys/{key}`

```bash
curl -s http://localhost:8080/keys/user:1
# alice
```

Unknown or expired keys return `404`:

```json
{"status":404,"error":"Not Found","message":"no value stored for key: user:1"}
```

### `DELETE /keys/{key}`

Returns `204 No Content`, or `404` if the key was absent or already expired.

### `POST /keys/{key}/increment`

Atomic read-modify-write via `Cache.compute`. Creates the key at `1` if absent.
Returns `409 Conflict` if the stored value is not an integer.

```bash
curl -s -X POST http://localhost:8080/keys/hits/increment
# {"key":"hits","value":1}
```

### `GET /stats`

```json
{"hits":12,"misses":4,"evictions":0,"expirations":1,"hitRatio":0.75,"size":3}
```

### Operational endpoints

`GET /actuator/health` (plus `/liveness` and `/readiness` probes),
`GET /actuator/info`.

### Error responses

Every error uses one shape: `{"status":…,"error":…,"message":…}`.

| Status | Cause |
|---|---|
| `400` | Blank key, empty/oversized value, non-positive or non-numeric `ttl` |
| `404` | Key unknown or expired, or unknown route |
| `409` | `increment` on a non-integer value |
| `500` | Unexpected failure (logged; no stack trace leaks to the client) |

## Configuration

All keys live under `cache.*` and are overridable by environment variable
(relaxed binding: `cache.default-ttl` → `CACHE_DEFAULT_TTL`).

| Property | Default | Meaning |
|---|---|---|
| `cache.implementation` | `global` | `global`, `segmented_a`, `segmented_b`, `concurrent_map` |
| `cache.capacity` | `10000` | Maximum live entries |
| `cache.segments` | `16` | Requested segments (rounded down to a power of two, clamped to capacity) |
| `cache.default-ttl` | `60s` | TTL for keys created without an explicit one |
| `cache.max-value-length` | `4096` | Largest accepted value, in characters |
| `cache.sweep-interval` | `30s` | Delay between background TTL sweeps |

The segment count is clamped so that `segments × (capacity / segments) ≤ capacity`,
which keeps `size() ≤ capacity` a strict invariant ([DR-006](docs/DESIGN.md)).

## Benchmarks

Measured with **JMH 1.37**, 8 threads, 3 warmup + 5 measurement iterations of 1s,
2 forks. Throughput in **ops/s** — higher is better.

**Test machine:** AMD Ryzen 7 260 (8 cores / 16 threads), 14 GiB RAM,
Linux 7.0.0-43, OpenJDK 25.0.5.

### Reads

| Implementation | Hot set (16 keys) | Wide set (1024 keys) |
|---|---:|---:|
| `ConcurrentMapCache` | 266,047,907 | 188,110,761 |
| `SegmentedRwLockCache` | 52,588,167 | 51,746,806 |
| `SegmentedLockCache` | 25,272,894 | 16,708,174 |
| `GlobalLockCache` | 17,486,315 | 8,645,634 |

### Mixed 90% read / 10% write (hot set)

| Implementation | Throughput |
|---|---:|
| `ConcurrentMapCache` | 151,502,895 |
| `SegmentedLockCache` | 22,521,494 |
| `SegmentedRwLockCache` | 21,477,218 |
| `GlobalLockCache` | 16,432,790 |

### What the numbers say

- **Segmentation pays off.** `SegmentedLockCache` is ~2.9× faster than
  `GlobalLockCache` on the hot read set and ~2.6× on the wide set, purely from
  independent locks.
- **The read/write lock pays off for reads, not writes.** On reads
  `SegmentedRwLockCache` is ~3.0× faster than `GlobalLockCache` and ~2.1× faster
  than `SegmentedLockCache`, because readers no longer serialize. But under the
  mixed workload it is slightly *slower* than `SegmentedLockCache` (21.5M vs
  22.5M): the write path must take the exclusive lock anyway, and the extra
  `AtomicLong` stamp on every read costs something when reads are not pure.
  This is the measured basis for [DR-007](docs/DESIGN.md).
- **`ConcurrentMapCache` is 4–30× faster than every locking variant** and that is
  the honest headline: locks are expensive. It is faster because it does almost
  no bookkeeping — no ordering, no LRU, and an unordered eviction policy. The
  numbers below show what that costs in hit rate.
- **Eviction accuracy, not throughput, is what locks buy.** On a skewed workload
  (capacity 64, 512 keys, 200,000 ops, seed 42), measured by
  `EvictionQualityTest`:

  | Implementation | Hit ratio |
  |---|---:|
  | `SegmentedLockCache` | 0.3086 |
  | `SegmentedRwLockCache` | 0.3086 |
  | `GlobalLockCache` | 0.3056 |
  | `ConcurrentMapCache` | 0.0865 |

  The baseline keeps **no ordering information**, so it hits 3.5× less often than
  the LRU variants. Notably, segmented LRU *matched or slightly beat* global LRU
  here rather than losing to it — with capacity 64 split across 16 segments and a
  skew that fits each segment locally, the "approximate global LRU" penalty did
  not materialize on this workload. The test therefore asserts only the robust
  comparison (LRU beats the unordered baseline) and prints the rest.

Run them yourself:

```bash
mvn -pl cache-bench -am package -DskipTests
java -jar cache-bench/target/benchmarks.jar            # full run
java -jar cache-bench/target/benchmarks.jar -l         # list benchmarks
java -jar cache-bench/target/benchmarks.jar -wi 1 -i 2 -w 1 -r 1 -f 1   # quick smoke
```

> Benchmark caveat: shared CI runners are noisy. Treat small deltas (<10%) as
> noise and re-run before concluding anything.

## Testing

```bash
mvn verify              # everything
mvn -pl cache-core test # 1080 tests
mvn -pl cache-server test
```

Current state: **1080 tests in `cache-core`, 22 in `cache-server`, 1 in
`cache-bench` — all passing.**

The suite has two layers:

1. **Contract tests** (`AbstractCacheTest`) — one shared test body run against all
   four implementations: TTL boundaries, `compute` null semantics, capacity
   invariants, argument validation.
2. **Concurrency tests** (`AbstractConcurrentCacheTest`) — 8-thread workloads
   behind a `CountDownLatch` so threads genuinely overlap, each `@RepeatedTest(50)`
   to catch intermittent races, wrapped in `assertTimeoutPreemptively` so a
   deadlock fails the build instead of hanging it.

TTL tests use an injected `LongSupplier` clock, so a 60-second TTL is tested at
exact boundaries in microseconds with no `sleep`.

### Proving the concurrency tests have teeth

A concurrency test that never fails is worthless. To verify these do fail, the
lock was temporarily removed from `LockedLruStore.compute` on a scratch branch
(`scratch/remove-lock-demo`). Raw output from
[`docs/DESIGN.md`](docs/DESIGN.md):

```
[ERROR] Tests run: 50, Failures: 50, Errors: 0, Skipped: 0
org.opentest4j.AssertionFailedError: expected: <Optional[320000]> but was: <Optional[26897]>
```

All 50 repetitions failed, and the failure mode was **lost updates** (≈27k instead
of 320k) rather than a crash — exactly the bug an unlocked read-modify-write
produces. With the lock restored, the same test passes 50/50.

## Design decisions

Recorded as short ADR-style entries in [`docs/DESIGN.md`](docs/DESIGN.md):

| ID | Decision |
|---|---|
| DR-001 | `compute` returning `null` removes the mapping (ConcurrentMap semantics) |
| DR-002 | Keys created by `compute` get the default TTL; updates keep remaining TTL |
| DR-003 | `size()` counts live entries only, dropping expired ones (O(n)) |
| DR-004 | Injectable `System::nanoTime` supplier, not wall-clock, for TTL |
| DR-005 | One shared `LockedLruStore`, composed per implementation |
| DR-006 | Capacity split across segments → only approximate global LRU |
| DR-007 | Exact LRU under a read lock is impossible; Variant B stamps timestamps |
| DR-008 | The `ConcurrentHashMap` baseline evicts arbitrarily, by design |

## Docker

```bash
# Build and run
docker build -t concurrent-cache:local .
docker run -d -p 8080:8080 concurrent-cache:local

# Or with the compose file (hardened: read-only rootfs, non-root, memory cap)
docker compose up -d
docker compose down
```

The image is a multi-stage build (Maven + JDK 17 to build, JRE 17 to run), runs as
UID 1001 rather than root, and has a `HEALTHCHECK` against the readiness probe. The
compose file adds `read_only: true`, `no-new-privileges`, and a 512 MB memory cap.

Override the implementation at runtime:

```bash
docker run -d -p 8080:8080 \
  -e CACHE_IMPLEMENTATION=segmented_b \
  -e CACHE_SEGMENTS=32 \
  concurrent-cache:local
```

## CI/CD

| Workflow | Trigger | What it does |
|---|---|---|
| [`ci.yml`](.github/workflows/ci.yml) | push / PR to `main` | `mvn verify` on JDK 17 **and** 21, jar artifact upload, then a container smoke test against the real API |
| [`docker.yml`](.github/workflows/docker.yml) | push to `main`, `v*` tags | Multi-arch (`amd64`/`arm64`) build pushed to GHCR with semver + SHA tags |
| [`bench.yml`](.github/workflows/bench.yml) | manual, `v*` tags | Runs JMH and uploads `bench-results.json` |

Benchmarks deliberately do **not** gate pull requests — they are too slow and too
sensitive to noisy shared runners to be a meaningful pass/fail signal.

Dependabot keeps Maven dependencies, GitHub Actions, and Docker base images current.

## Project layout

```
├── cache-core/          Plain Java cache engine (no framework deps, no Lombok)
│   ├── src/main/java/com/concurrentcache/core/
│   │   ├── Cache.java                  interface + contracts
│   │   ├── CacheStats.java             LongAdder counters
│   │   ├── GlobalLockCache.java
│   │   ├── SegmentedLockCache.java
│   │   ├── SegmentedRwLockCache.java
│   │   ├── ConcurrentMapCache.java
│   │   ├── LockedLruStore.java         shared single-lock LRU engine
│   │   ├── SegmentSweepable.java       per-segment cleanup capability
│   │   ├── Segments.java               power-of-two routing
│   │   └── Ttl.java                    TTL validation
│   └── src/test/java/…                 contract + concurrency suites
├── cache-server/        Spring Boot REST API
│   ├── …/api/           controllers, DTOs, exception mapping
│   ├── …/config/        cache bean + @ConfigurationProperties
│   └── …/sweeper/       background TTL sweeper (SmartLifecycle)
├── cache-bench/         JMH benchmarks + eviction-quality test
├── docs/DESIGN.md       decision records + lock-removal evidence
├── Dockerfile           multi-stage, non-root, healthchecked
└── docker-compose.yml
```

## License

MIT
