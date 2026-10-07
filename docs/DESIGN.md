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
