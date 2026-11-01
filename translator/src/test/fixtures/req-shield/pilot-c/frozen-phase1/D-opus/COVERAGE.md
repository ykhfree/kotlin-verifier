# Coverage: SOURCES.md blocks and the DRAFT.kv clauses that encode them

Scope: the local lock `KeyLocalLock`. This means its `tryLock`, its `unLock`, its cleanup monitor (`sweep`), its map
size cap, its tokens and its keys.

Terms used in this file:
- "Skeleton" is the fixed part of DRAFT.kv (copied from SKELETON.kv). I did not write it.
- A "live" entry has `now < expiresAt`. An "expired" entry has `now >= expiresAt` (see QUESTIONS.md, Q2).
- An "unbacked token" is the README's "permit that no map entry backs" (`gBacked == false`).
- `issue` rule is the issue rule of `tryLock`. It has no ID.
- "Probe step `x`" is the probe line that assigns `x`.

## Blocks that state required behavior of the local lock

| Anchor | What the block requires | Clauses that encode it |
|---|---|---|
| README.md#cache-key-layout/2 | Lock keys come from the namespaced cache key with the prefix `reqshield:lock:`, so two caches never share a lock entry. | Not encoded: out of the model. Key derivation happens before `tryLock` runs, and the skeleton makes `Key` opaque. |
| README.md#global-lock-guidance/2 | Each acquisition carries an ownership token; only the acquirer can release the lock; a stale holder cannot release the next holder's lock. | `issue` rule (the tool checks that no token is issued twice), `unLock_ends_only_presented_token`, `unLock_non_owner_returns_false`, `unLock_non_owner_keeps_map`, `progress_tryLock_takeover` (the new token differs from the old one), `tryLock_ends_only_expired`; probe steps `t3` and `u1`. Whether this block binds the local lock: see Q15. |
| README.md#local-lock-map-size/1 | The local lock keeps one entry per (cache key, lock type) that it is locking, in one map for the whole JVM. | Skeleton `state m` (one map per JVM; a map has at most one entry per key). `progress_tryLock_free_key` and `progress_tryLock_takeover` (an acquisition creates or refills the entry), `inv_held_backed_token_carried_with_lease` (a held backed lock has its entry), `sweep_adds_no_entry` and `unLock_non_owner_keeps_map` (no other step adds an entry). Not encoded: "up to two entries" per cache key (out of the model: the lock type is inside the opaque `Key`). |
| README.md#local-lock-map-size/2 | A release drops the entry at once; the monitor drops an unreleased entry once `lockTimeoutMillis` has passed; the monitor runs every 1 s and can lag by 1 s; so the map size follows the number of concurrent locks. | `progress_unLock_owner_releases`; `issue` rule (`gLease = now + timeout`); `progress_sweep_drops_expired`, `sweep_keeps_live_entry`, `sweep_ends_only_expired`; `inv_held_backed_token_carried_with_lease`. Not encoded: the 1 s interval and the lag (the language cannot state timing or liveness); the map size and the 500-lock example (the language has no map size, and the example is informative). |
| README.md#local-lock-map-size/3 | The map is uncapped by default; `req-shield.lock.max-entries` sets a cap; `0` means uncapped. | "`0` (the default) means uncapped": the skeleton contract `rej ==> limit != 0` together with `progress_tryLock_free_key`. Not encoded: the property name, the default, the yml and `-D` sources and their order (configuration parsing is out of the model). |
| README.md#local-lock-map-size/4 | When the map is full, a request for a new key gets a token that no entry backs and runs as if it held the lock; keys already in the map ignore the cap. | `progress_tryLock_cap_refusal`, `tryLock_unbacked_only_for_refused_new_key`, `issue` rule (`gBacked`), `inv_unbacked_token_never_carried`; `tryLock_live_entry_returns_null` and `progress_tryLock_takeover` (they do not read `rej`); probe steps `t2`, `t3` and `t4`. Not encoded: "no added latency" (timing) and "the cache still gets populated" (the caller `ReqShield` is out of the model). |
| README.md#local-lock-map-size/5 | The cap is soft: the size check is an estimate and is not atomic with the insertion, so the map can grow slightly past the cap. | Partly encoded: the skeleton takes `rej` as an input that the step does not compute, and `progress_tryLock_free_key` adds the entry whenever `rej` is false, whatever the map size. Not encoded: a bound on the map size (the language has no map size). |
| README.md#local-lock-map-size/6 | The first refusal and every 1000th refusal after it are logged at WARN. | Not encoded: out of the model (logging; the skeleton gives `rejectsNewEntry` the line `effects none`). |
| README.md#local-lock-map-size/7 | A cap value that is not a non-negative number is logged and ignored; `LocalLockLimit.maxEntries = -1` fails fast. | Not encoded: out of the model (configuration parsing in `LocalLockLimit`, which is another class). |
| CLAUDE.md#project-overview/1 | Only one request for the same cache key is processed at a time; the other requests wait for the result. | `tryLock_live_entry_returns_null`, `lemma_one_backed_holder_per_key`; probe step `t2`. Not encoded: the waiting (the polling in `ReqShield` is out of the model). Unbacked tokens are not exclusive: see Q10. |
| CLAUDE.md#key-components/2 | `tryLock` returns an ownership token; `unLock` releases the lock only when the token matches. | `issue` rule, `progress_tryLock_free_key`, `progress_unLock_owner_releases`, `unLock_ends_only_presented_token`, `unLock_non_owner_returns_false`, `unLock_non_owner_keeps_map`. |
| CLAUDE.md#key-components/5 | The Spring aspects namespace cache keys and lock keys as `{cacheName}::{key}`. | Not encoded: out of the model (key derivation; `Key` is opaque). |
| CLAUDE.md#reqshieldconfiguration-parameters/4 | `lockTimeoutMillis` is the lock timeout; its default is 3000 ms. | `issue` rule (`gLease = now + timeout`), `progress_tryLock_free_key` and `progress_tryLock_takeover` (`S.m[k].expiresAt == now + timeout`). Not encoded: the default 3000 ms (configuration; the language treats `timeout` as an arbitrary value). The word "acquisition": see Q4. Non-positive values: see Q5. |
| KeyLock.kt(core)#KeyLock | The lock collapses concurrent requests for one key; only the acquirer can release it; a holder whose lock expired and was taken over cannot release the new owner's lock. | `lemma_one_backed_holder_per_key`, `tryLock_live_entry_returns_null`, `progress_tryLock_takeover`, `tryLock_ends_only_expired`, `unLock_ends_only_presented_token`, `unLock_non_owner_keeps_map`; probe steps `t3` and `u1`. Not encoded: "opaque" (it restricts callers; the skeleton fixes the token format). |
| KeyLock.kt(core)#tryLock | `tryLock` acquires the lock for key + lock type and returns a token, or null when another holder owns the lock. | `issue` rule (`gKey = k`), `progress_tryLock_free_key`, `progress_tryLock_cap_refusal`, `progress_tryLock_takeover`, `tryLock_live_entry_returns_null`. |
| KeyLock.kt(core)#unLock | `unLock` releases the lock only if the token matches the current owner; it returns false when the lock is not held or the token does not match. | `unLock_ends_only_presented_token`, `progress_unLock_owner_releases`, `unLock_non_owner_returns_false`, `unLock_non_owner_keeps_map`; probe steps `u1`, `u3` and `u4`. |
| KeyLock.kt(reactor)#tryLock | As the core `tryLock`; the result completes empty instead of being null. | As KeyLock.kt(core)#tryLock. The reactor's "completes EMPTY" is `res == NULL` in the model. |
| KeyLock.kt(reactor)#unLock | As the core `unLock`; a stale owner can never release a lock that was handed to another holder. | As KeyLock.kt(core)#unLock, plus `progress_tryLock_takeover`; probe step `u1`. |
| KeyLock.kt(coroutine)#tryLock | As the core `tryLock`. | As KeyLock.kt(core)#tryLock. |
| KeyLock.kt(coroutine)#unLock | As the core `unLock`. | As KeyLock.kt(core)#unLock. |
| KeyLock.kt(coroutine)#lockKeyOf | Lock keys have a prefix, so a lock entry never collides with a cache entry. | Not encoded: out of the model (key derivation; `Key` is opaque). |

## Blocks not in the table

These blocks mention locks or keys, but they state no required behavior of the local lock:
- The global (Redis) lock only: README.md#global-lock-guidance/1, README.md#global-lock-guidance/3,
  README.md#global-lock-guidance/4, CLAUDE.md#key-components/6, CLAUDE.md#reqshieldconfiguration-parameters/2.
- The choice between the local lock and the global lock (configuration): CLAUDE.md#reqshieldconfiguration-parameters/1,
  CLAUDE.md#design-patterns/2, CLAUDE.md#design-patterns/4.
- What the caller `ReqShield` does around the lock: CLAUDE.md#key-components/1, CLAUDE.md#work-modes/1,
  CLAUDE.md#work-modes/2, CLAUDE.md#work-modes/3 (when it takes the lock);
  README.md#waiting-for-another-request-lock-not-acquired/1, README.md#waiting-for-another-request-lock-not-acquired/2,
  README.md#waiting-for-another-request-lock-not-acquired/3 and CLAUDE.md#reqshieldconfiguration-parameters/6 (what a
  request does after `tryLock` returns null).
- Cache keys, not lock keys: README.md#cache-key-layout/1, README.md#cache-key-layout/3.
- Package layout: CLAUDE.md#core-modules/2.

All other blocks are about builds, releases, tests, toolchains, frameworks or adapters.

## Clause index

| Clause | Kind | What it says |
|---|---|---|
| `issue` rule | tryLock issue | A new token records the key `k`, whether the entry of `k` carries it, and the lease end `now + timeout`. |
| `progress_tryLock_free_key` | ensures | No entry and no refusal: `tryLock` returns a token, and the entry of `k` carries it with `expiresAt = now + timeout`. |
| `progress_tryLock_cap_refusal` | ensures | No entry and a refusal: `tryLock` returns a token and adds no entry. |
| `tryLock_live_entry_returns_null` | ensures | Live entry: `tryLock` returns NULL and does not change the entry. |
| `progress_tryLock_takeover` | ensures | Expired entry: `tryLock` puts a new token and a new expiry into the entry and returns the new token. |
| `tryLock_unbacked_only_for_refused_new_key` | unbacked_only_if | `tryLock` returns an unbacked token only for a refused key that has no entry. |
| `tryLock_ends_only_expired` | ends_only_if | `tryLock` ends an acquisition only after its lease has ended. |
| `progress_unLock_owner_releases` | ensures | Matching token: `unLock` removes the entry and returns true. |
| `unLock_non_owner_returns_false` | ensures | Any other token: `unLock` returns false. |
| `unLock_non_owner_keeps_map` | ensures | Any other token: `unLock` does not add, remove or change the entry. |
| `unLock_ends_only_presented_token` | ends_only_if | `unLock` ends only the acquisition of the presented token. |
| `progress_sweep_drops_expired` | ensures | Expired entry: the monitor step removes it. |
| `sweep_keeps_live_entry` | ensures | Live entry: the monitor step does not change it. |
| `sweep_adds_no_entry` | ensures | The monitor step never adds an entry. |
| `sweep_ends_only_expired` | ends_only_if | The monitor step ends an acquisition only after its lease has ended. |
| `inv_held_backed_token_carried_with_lease` | invariant | The entry of its key carries each held backed token, and that entry's `expiresAt` equals the token's lease. |
| `inv_unbacked_token_never_carried` | invariant | The entry of its key never carries an unbacked token. |
| `lemma_one_backed_holder_per_key` | lemma | At most one backed token per key is held. |
| probe | probe | One key: acquire; fail while live (with `rej = true`); take over at expiry (with `rej = true`); the stale `unLock` fails; the owner's `unLock` succeeds; a refused new key gets an unbacked token; its `unLock` returns false. |
