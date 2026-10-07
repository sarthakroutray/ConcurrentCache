# Design Decisions

Short decision records. Format: Context, Options, Choice, Consequences.

## DR-001: compute() semantics when the remapping function returns null

- **Context:** `Cache.compute` is the atomic read-modify-write used for counters
  (the Phase 4 increment endpoint). Its null contract must be fixed before
  implementations exist.
- **Options:** (a) ConcurrentMap semantics: null removes the mapping; (b) null
  aborts the update and keeps the previous value and TTL; (c) null is rejected
  with NullPointerException.
- **Choice:** (a), matching `java.util.concurrent.ConcurrentMap.compute`.
- **Consequences:** Anyone reading this code can reuse ConcurrentHashMap
  intuitions; a caller that must keep a key always returns a value. `compute`
  returns null when the key is absent, expired, or removed by the function.

## DR-002: TTL for keys created by compute()

- **Context:** `compute` has a fixed 2-argument signature, so a newly created key
  has no caller-supplied TTL.
- **Options:** (a) use a per-instance default TTL; (b) never expire, rely on LRU
  eviction; (c) reset to the default TTL on every compute call ("touch").
- **Choice:** (a). Updates to existing keys keep their remaining TTL.
- **Consequences:** Every cache instance is constructed with a default TTL; the
  server maps its `default-ttl` configuration onto it. Compute on a hot key does
  not silently extend its life (which option (c) would cause).

## DR-003: size() counts live entries only

- **Context:** Expiry is lazy, so an entry can be resident but already expired.
  The Phase 1 test "size never exceeds capacity" needs an exact definition.
- **Options:** (a) count only non-expired entries, dropping expired ones while
  scanning, O(n); (b) return the raw map size in O(1), possibly including
  expired-but-unswept entries.
- **Choice:** (a).
- **Consequences:** `size()` is exact with respect to TTL but can cost O(capacity)
  and mutates (drops expired entries). That is acceptable because size is not on
  the hot path.

## DR-004: Injectable nanoTime-based time source for TTL

- **Context:** TTL checks need "now"; tests must be fast and deterministic (no
  sleeping), and wall-clock time can jump backwards.
- **Options:** (a) call `System.currentTimeMillis()` inside implementations;
  (b) inject a `java.util.function.LongSupplier` returning nanoseconds,
  defaulting to `System::nanoTime`; (c) inject `java.time.Clock`.
- **Choice:** (b).
- **Consequences:** `System.nanoTime` is monotonic, so TTL math cannot be broken
  by clock adjustments. Tests inject a fake clock and advance it manually, making
  TTL boundaries exact. The cost is one extra constructor parameter and the rule
  that all internal deadlines are nanoseconds, never wall-clock instants.

## DR-005: One shared single-lock store, composed differently per implementation

- **Context:** `GlobalLockCache` and Variant A of the segmented cache need
  identical storage behaviour (access-ordered map, lazy TTL, LRU eviction); only
  the number of locks and the key routing differ. Writing it twice would mean
  fixing every future bug twice.
- **Options:** (a) duplicate the ~80 lines of logic per implementation;
  (b) extract a package-private `LockedLruStore` and have both public classes
  compose one or many of them; (c) make `SegmentedLockCache` extend
  `GlobalLockCache`.
- **Choice:** (b). The store takes an injected `CacheStats` so several stores can
  share one set of counters.
- **Consequences:** `GlobalLockCache` is one store; `SegmentedLockCache` is a list
  of stores plus a mask. Inheritance (c) was rejected because a segmented cache
  is not a kind of global-lock cache. The existing contract tests cover the store
  through both public classes, so the extraction was safe to verify.

## DR-006: Capacity is split across segments, so global LRU is only approximate

- **Context:** With N segments each holding its own LRU list, eviction happens per
  segment. A key can be evicted from a crowded segment while another segment sits
  half empty.
- **Options:** (a) keep one global LRU list (which re-introduces a shared lock and
  destroys the point of segmenting); (b) split the capacity evenly and accept
  approximate global LRU.
- **Choice:** (b), with `perSegmentCapacity = capacity / segmentCount` and the
  segment count clamped to a power of two no larger than the capacity
  (`Segments.count`). Each segment keeps exact LRU internally.
- **Consequences:** The invariant `size() <= capacity` holds strictly, because
  `segmentCount * (capacity / segmentCount) <= capacity`. When the capacity is not
  divisible by the segment count, a few slots go unused (e.g. capacity 10 with 8
  segments uses 8 slots of 1 each). Eviction quality is worse than a single LRU
  list — this is the price of independent locks and is measured in Phase 5.

## DR-007: Why Variant B cannot do exact LRU under a read lock

- **Context:** Variant B wants concurrent readers (shared lock) plus LRU eviction.
  A `LinkedHashMap` in access order implements exact LRU, but `get` on such a map
  is a *structural* modification: it unlinks the entry and re-links it at the tail
  of the list.
- **Options:** (a) use an access-ordered map and take the exclusive lock for
  reads as well; (b) keep reads on the shared lock and record last access in an
  `AtomicLong`, evicting by the oldest timestamp.
- **Choice:** (b) for Variant B, (a) implicitly for Variant A and the global-lock
  cache (which is exactly why they need a full lock on every operation).
- **Consequences:** Option (a) makes "read" a write: readers would serialize and
  the `ReentrantReadWriteLock` would degenerate into a mutex, so the read lock
  would be decoration. Option (b) keeps readers parallel at the cost of accuracy:
  timestamps come from a monotonic clock and are updated lazily
  (`accumulateAndGet(now, Math::max)`), so under heavy read contention a stamp can
  be slightly stale, and eviction scans the segment for the oldest stamp in O(n).
  Variant B therefore trades exactness and O(1) eviction for read parallelism.

## DR-008: The ConcurrentHashMap baseline evicts arbitrary entries

- **Context:** A baseline is only useful if it is simple and honest. Implementing
  LRU on top of `ConcurrentHashMap` requires extra bookkeeping (queues, stamps)
  that makes it a different design, not a baseline.
- **Options:** (a) purge expired entries and drop arbitrary ones on overflow;
  (b) add LRU bookkeeping so the baseline also approximates LRU.
- **Choice:** (a). Expired entries are purged first because that is free; the rest
  of the overflow is removed in iteration order.
- **Consequences:** `ConcurrentMapCache` respects `size() <= capacity` but makes no
  eviction-order promise, which is why the exact-LRU tests live in the
  implementation-specific test classes rather than in `AbstractCacheTest`.

## Evidence: do the concurrency tests have teeth?

Recorded from the scratch branch `scratch/remove-lock-demo`, where the only change
was deleting the lock acquisition and release in `LockedLruStore.compute`
(main is unaffected and still guarded). Command and raw output:

```
$ mvn -B -pl cache-core test -Dtest='GlobalLockConcurrencyTest#noLostUpdatesUnderContention'

[ERROR] Tests run: 50, Failures: 50, Errors: 0, Skipped: 0, Time elapsed: 1.523 s <<< FAILURE! -- in com.concurrentcache.core.GlobalLockConcurrencyTest
org.opentest4j.AssertionFailedError: expected: <Optional[320000]> but was: <Optional[26897]>
org.opentest4j.AssertionFailedError: expected: <Optional[320000]> but was: <Optional[60017]>
org.opentest4j.AssertionFailedError: expected: <Optional[320000]> but was: <Optional[40319]>
org.opentest4j.AssertionFailedError: expected: <Optional[320000]> but was: <Optional[36848]>
org.opentest4j.AssertionFailedError: expected: <Optional[320000]> but was: <Optional[41853]>
```

With the lock restored on `main`, the same test passes, raw output:

```
$ mvn -B -pl cache-core test -Dtest='GlobalLockConcurrencyTest#noLostUpdatesUnderContention'

[INFO] Tests run: 50, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.208 s -- in com.concurrentcache.core.GlobalLockConcurrencyTest
[INFO] Tests run: 50, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Two things this demonstrates: the assertion is strong enough to detect a missing
lock in 50 out of 50 repetitions, and the failure mode is lost updates (a value
near 27k instead of 320k), not a crash — which is exactly the bug type that
silent, unlocked read-modify-write code produces.
