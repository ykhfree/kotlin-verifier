# Answers to SCENARIOS.md

Source: SOURCES.md only. DRAFT.kv was not used for these answers.
Conventions from SCENARIOS.md: `lockTimeoutMillis = 10`, one key `k`, "acquired at time 0" means `tryLock(k)` at time 0 on an empty map with no cap, returning `t1`.

Status values:
- `settled`: a sentence of the text decides the outcome.
- `assumed`: the text suggests the outcome, but does not decide it.
- `open`: the text does not decide the outcome. The outcome column shows the lean.

| ID | Outcome | Status | Anchor and exact quote |
|---|---|---|---|
| C01 | a token that an entry backs | settled | [README.md#local-lock-map-size/1] "keeps one entry per `(cache key, lock type)` it is currently" |
| C02 | a token no entry backs | settled | [README.md#local-lock-map-size/4] "a request for a **new** key is handed a permit that no map entry backs" |
| C03 | null | assumed | [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed" (note 1) |
| C04 | null | open | [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed" (note 2) |
| C05 | null | open | [KeyLock.kt(core)#KeyLock] "a holder whose lock already expired and was taken over by someone else" (note 3) |
| C06 | false | settled | [KeyLock.kt(core)#unLock] "@return false when the lock is not held or the token does not match the current owner." (note 8) |
| C07 | true | settled | [KeyLock.kt(core)#unLock] "Releases the lock for [key] + [lockType] only if [token] matches the current owner." (note 8) |
| C08 | true | settled | [KeyLock.kt(core)#unLock] "Releases the lock for [key] + [lockType] only if [token] matches the current owner." |
| C09 | true | assumed | [KeyLock.kt(core)#KeyLock] "only the holder that acquired the lock can" (note 4) |
| C10 | false | settled | [README.md#local-lock-map-size/2] "An entry is dropped as soon as the lock is released" |
| C11 | false | settled | [KeyLock.kt(core)#unLock] "@return false when the lock is not held or the token does not match the current owner." |
| C12 | false | assumed | [README.md#local-lock-map-size/4] "a request for a **new** key is handed a permit that no map entry backs" (note 5) |
| C13 | a token no entry backs | settled | [README.md#local-lock-map-size/4] "a request for a **new** key is handed a permit that no map entry backs" |
| C14 | the entry stays | assumed | [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed" (note 1) |
| C15 | the entry is dropped | open | [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed" (note 6) |
| C16 | the entry is dropped | assumed | [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed" (note 1) |
| C17 | false | settled | [KeyLock.kt(core)#unLock] "@return false when the lock is not held or the token does not match the current owner." (note 8) |
| C18 | a token that an entry backs | settled | [README.md#local-lock-map-size/1] "keeps one entry per `(cache key, lock type)` it is currently"; [README.md#local-lock-map-size/4] "Once the map is full" |
| C19 | null | open | [README.md#local-lock-map-size/4] "Keys whose entry is already in" (note 7) |
| C20 | a token | settled | [README.md#local-lock-map-size/1] "keeps one entry per `(cache key, lock type)` it is currently" |

Counts: 11 settled, 5 assumed, 4 open (20 rows).

## Notes on assumed and open rows

1. C03, C14 and C16 (start point of the time): The text drops an unreleased entry "once `lockTimeoutMillis` has passed". It does not say that the time counts from acquisition. These rows assume that it does. At time 5 the entry is still in the map, and at time 11 it has passed the 10 ms limit.
2. C04 (open): Two points are open. The text does not say whether time 10 counts as "passed". It also does not say whether `tryLock` treats an expired entry as free before the monitor drops it. The lean is null, because the entry is still in the map.
3. C05 (open): The text says an expired lock can be "taken over by someone else". It does not say when the takeover can happen: before the monitor drops the entry, or only after. The lean is null, because the entry is still in the map.
4. C09 (assumed): The text says only the holder that acquired the lock can release it. It does not say whether a lock still counts as held after its time has passed. This row assumes true.
5. C12 (assumed): The text says no map entry backs the permit. It does not say what `unLock` returns for it. This row assumes false, because the map has no entry to release.
6. C15 (open): The text does not say whether time 10 counts as "passed". The lean is that the entry is dropped.
7. C19 (open): The map is full, but `k` still has an entry, so the cap does not apply ("Keys whose entry is already in" the map). The outcome is therefore not an unbacked token. Whether the expired entry still owns the lock is open (note 3). The outcome is null or a new backed token. The lean is null.
8. C06, C07 and C17 (settled, with a condition): C06 and C07 assume that C05 returned `t2`. C17 assumes that C16 dropped the entry. If C05 is decided differently, these rows change.
