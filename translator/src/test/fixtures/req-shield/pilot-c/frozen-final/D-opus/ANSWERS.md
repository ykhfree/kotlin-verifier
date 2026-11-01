# Answers to SCENARIOS.md (phase 2)

I answered every scenario from SOURCES.md alone. I did not use DRAFT.kv, COVERAGE.md or QUESTIONS.md.
Status: `settled` means that a sentence of the text decides the outcome. `assumed` means that the text suggests the
outcome but does not decide it. `open` means that the text does not decide it. For an `open` row, the outcome cell
names the outcomes that the text leaves possible.

Counts: 20 rows. 14 settled, 3 assumed, 3 open.

| ID | Outcome | Status | Anchor and exact quote |
|---|---|---|---|
| C01 | a token that an entry backs | settled | [KeyLock.kt(core)#tryLock] "@return an opaque ownership token, or null when another holder owns the lock."; [README.md#local-lock-map-size/1] "keeps one entry per `(cache key, lock type)` it is currently" |
| C02 | a token no entry backs | settled | [README.md#local-lock-map-size/4] "Once the map is full, a request for a **new** key is handed a permit that no map entry backs." |
| C03 | null | settled | [KeyLock.kt(core)#tryLock] "@return an opaque ownership token, or null when another holder owns the lock."; [CLAUDE.md#project-overview/1] "ensuring only one request for the same cache key is processed at a time" |
| C04 | not decided: null or a new token | open | [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed"; [KeyLock.kt(core)#KeyLock] "release it, so a holder whose lock already expired and was taken over by someone else" |
| C05 | not decided: null or a new token | open | [KeyLock.kt(core)#KeyLock] "release it, so a holder whose lock already expired and was taken over by someone else"; [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed, which runs on a 1 s interval and so can lag by up to that much." |
| C06 | false | settled | [README.md#global-lock-guidance/2] "holder whose lock already expired can no longer release the lock of the next holder."; [KeyLock.kt(core)#KeyLock] "cannot release the new owner's lock." |
| C07 | true | settled | [KeyLock.kt(core)#unLock] "Releases the lock for [key] + [lockType] only if [token] matches the current owner."; [KeyLock.kt(core)#unLock] "@return false when the lock is not held or the token does not match the current owner." |
| C08 | true | settled | [KeyLock.kt(core)#unLock] "Releases the lock for [key] + [lockType] only if [token] matches the current owner."; [README.md#local-lock-map-size/2] "An entry is dropped as soon as the lock is released" |
| C09 | true | assumed | [KeyLock.kt(core)#KeyLock] "release it, so a holder whose lock already expired and was taken over by someone else"; [KeyLock.kt(core)#unLock] "@return false when the lock is not held or the token does not match the current owner." |
| C10 | false | settled | [README.md#local-lock-map-size/2] "An entry is dropped as soon as the lock is released"; [KeyLock.kt(core)#unLock] "@return false when the lock is not held or the token does not match the current owner." |
| C11 | false | settled | [KeyLock.kt(core)#unLock] "@return false when the lock is not held or the token does not match the current owner." |
| C12 | false | assumed | [KeyLock.kt(core)#unLock] "@return false when the lock is not held or the token does not match the current owner."; [README.md#local-lock-map-size/4] "Once the map is full, a request for a **new** key is handed a permit that no map entry backs." |
| C13 | a token no entry backs | settled | [README.md#local-lock-map-size/4] "Once the map is full, a request for a **new** key is handed a permit that no map entry backs."; [README.md#local-lock-map-size/4] "if it had taken the lock - it calls the supplier and writes the cache - so what the cap costs is request collapsing" |
| C14 | the entry stays | settled | [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed"; [README.md#global-lock-guidance/2] "Only the holder that acquired the lock can release it" |
| C15 | the entry is dropped | assumed | [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed" |
| C16 | the entry is dropped | settled | [README.md#local-lock-map-size/2] "an entry whose holder never released it is dropped by the"; [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed" |
| C17 | false | settled | [KeyLock.kt(core)#unLock] "@return false when the lock is not held or the token does not match the current owner." |
| C18 | a token that an entry backs | settled | [README.md#local-lock-map-size/1] "keeps one entry per `(cache key, lock type)` it is currently"; [README.md#local-lock-map-size/4] "Once the map is full, a request for a **new** key is handed a permit that no map entry backs." |
| C19 | not decided: null or a new token that an entry backs (not a token no entry backs, while the entry of `k` is in the map) | open | [README.md#local-lock-map-size/4] "Keys whose entry is already in"; [README.md#local-lock-map-size/4] "the map are not subject to the cap at all."; [KeyLock.kt(core)#KeyLock] "release it, so a holder whose lock already expired and was taken over by someone else" |
| C20 | a token | settled | [KeyLock.kt(core)#KeyLock] "Lock used to collapse concurrent requests for the same cache key."; [README.md#local-lock-map-size/1] "keeps one entry per `(cache key, lock type)` it is currently" |

## Notes

- C01: No cap is set, so the cap sentence does not apply. The lock keeps an entry for the key that it locks, so the
  entry backs the token.
- C03: I read the caller at time 5 as another request. If the caller is the first holder itself, the text does not
  say what happens (it does not say whether the lock is re-entrant).
- C04: Two points are open.
  1. The boundary. "once `lockTimeoutMillis` has passed" suggests that the lock is expired at exactly time 10, but the
     text does not decide the exact instant.
  2. The behavior after expiry. Even with an expired lock, the text does not say whether `tryLock` takes over the
     entry or returns null until the monitor drops it (see C05).

  The scenario also does not say whether a monitor pass ran at time 10.
- C05: Two readings remain.
  - New token: `tryLock` returns null only when another holder "owns" the lock (reactor: "currently owns"). The KDoc
    also speaks of a lock that "already expired and was taken over by someone else".
  - Null: the README names only the cleanup monitor as the way an unreleased entry leaves the map, and the monitor
    can lag by up to 1 s.

  No sentence decides between the two readings.
- C06: The premise says that `t2` is now the owner. Both cited sentences forbid the stale holder to release the new
  owner's lock, and the KDoc returns false when the token does not match the current owner.
- C07, C08: The text names only the cases in which `unLock` returns false. "true" is the complement of those cases. No
  sentence says "true" in words.
- C09: The protecting sentences name only a lock that a next holder has taken over. Here nobody took over, so the text
  suggests that the holder can still release its lock (true). But the KDoc also returns false when the lock is not
  held. The text does not say whether an expired lock that nobody took over is still held.
- C10, C17: The entry is gone, so the lock is not held, and `unLock` returns false.
- C12: No sentence covers `unLock` of a permit that no entry backs. The general rule ("false when the lock is not
  held") suggests false: no entry holds the lock for `k`, and the request only runs "as if" it had taken the lock.
  A reader can also take "runs exactly as if it had taken the lock" to include a successful `unLock` (true).
- C13: The permit `u` added no entry, so `k` is still a new key. The cap costs request collapsing for that key, so the
  second request also gets a permit that no entry backs.
- C14: The monitor drops an unreleased entry once `lockTimeoutMillis` has passed. At time 5 it has not passed, and
  only the holder can release the lock. So the entry stays.
- C15: At time 10, exactly 10 ms have passed. The phrase "once `lockTimeoutMillis` has passed" suggests that the
  entry is dropped. The text does not state the comparison at the exact boundary.
- C16: At time 11, the timeout has passed under every reading of the boundary.
- C18: The map is not full, so the cap sentence does not apply. The lock keeps an entry for the key that it locks.
- C19: The cap does not apply, because the entry of `k` is still in the map. So the outcome is the same open point as
  C05: null or a new token that an entry backs. The scenario does not say that no monitor pass ran. If a monitor pass
  dropped the entry before time 11, `k` would be a new key, and the full map would give a token that no entry backs.
- C20: The lock collapses requests for the same key only. Each key has its own entry, so the entry of `k` does not
  block `k2`.
