# Answers (phase 2)

Source: SOURCES.md only. DRAFT.kv, COVERAGE.md and QUESTIONS.md were not used to decide any answer.
The status words are the ones in SCENARIOS.md: `settled` (a sentence decides it), `assumed` (the text suggests it, but
does not decide it), `open` (the text does not decide it).
For `open` rows the outcome cell lists the possible outcomes, and the quote cell is "none". The notes below the table give the nearest sentences.

| ID | Outcome | Status | Anchor and exact quote |
|---|---|---|---|
| C01 | a token that an entry backs | settled | [KeyLock.kt(core)#tryLock] "@return an opaque ownership token, or null when another holder owns the lock." ; [README.md#local-lock-map-size/1] "keeps one entry per `(cache key, lock type)` it is currently" |
| C02 | a token no entry backs | settled | [README.md#local-lock-map-size/4] "Once the map is full, a request for a **new** key is handed a permit that no map entry backs." |
| C03 | null | settled | [KeyLock.kt(core)#tryLock] "@return an opaque ownership token, or null when another holder owns the lock." ; [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed" |
| C04 | undetermined: null or a new token | open | none |
| C05 | undetermined: null or a new token | open | none |
| C06 | false | settled | [KeyLock.kt(core)#KeyLock] "cannot release the new owner's lock." ; [KeyLock.kt(core)#unLock] "@return false when the lock is not held or the token does not match the current owner." |
| C07 | true | settled | [KeyLock.kt(core)#unLock] "Releases the lock for [key] + [lockType] only if [token] matches the current owner." ; [KeyLock.kt(core)#unLock] "@return false when the lock is not held or the token does not match the current owner." |
| C08 | true | settled | [KeyLock.kt(core)#unLock] "Releases the lock for [key] + [lockType] only if [token] matches the current owner." ; [README.md#local-lock-map-size/2] "An entry is dropped as soon as the lock is released" |
| C09 | true | assumed | [KeyLock.kt(reactor)#unLock] "that was already handed to another holder can never be released by a stale owner." ; [KeyLock.kt(core)#unLock] "@return false when the lock is not held or the token does not match the current owner." |
| C10 | false | settled | [KeyLock.kt(core)#unLock] "@return false when the lock is not held or the token does not match the current owner." ; [README.md#local-lock-map-size/2] "An entry is dropped as soon as the lock is released" |
| C11 | false | settled | [KeyLock.kt(core)#unLock] "@return false when the lock is not held or the token does not match the current owner." ; [CLAUDE.md#key-components/2] "`unLock` only releases when the token matches" |
| C12 | false | assumed | [KeyLock.kt(core)#unLock] "@return false when the lock is not held or the token does not match the current owner." ; [README.md#local-lock-map-size/4] "Once the map is full, a request for a **new** key is handed a permit that no map entry backs." |
| C13 | a token no entry backs | settled | [README.md#local-lock-map-size/4] "Once the map is full, a request for a **new** key is handed a permit that no map entry backs." |
| C14 | the entry stays | settled | [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed" |
| C15 | undetermined: the entry stays or is dropped | open | none |
| C16 | the entry is dropped | settled | [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed" |
| C17 | false | settled | [KeyLock.kt(core)#unLock] "@return false when the lock is not held or the token does not match the current owner." |
| C18 | a token that an entry backs | settled | [README.md#local-lock-map-size/4] "Once the map is full, a request for a **new** key is handed a permit that no map entry backs." ; [README.md#local-lock-map-size/1] "keeps one entry per `(cache key, lock type)` it is currently" |
| C19 | undetermined: null or a new token that an entry backs (never a new token no entry backs) | open | [README.md#local-lock-map-size/4] "are not subject to the cap at all." |
| C20 | a token | settled | [KeyLock.kt(core)#KeyLock] "Lock used to collapse concurrent requests for the same cache key." ; [README.md#local-lock-map-size/1] "keeps one entry per `(cache key, lock type)` it is currently" |

## Summary

- Rows: 20.
- settled: 14 (C01, C02, C03, C06, C07, C08, C10, C11, C13, C14, C16, C17, C18, C20).
- assumed: 2 (C09, C12).
- open: 4 (C04, C05, C15, C19).

## Notes on the open and assumed rows, and on two settled rows

C04 and C15 (time 10, the boundary).
- The only sentence is [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed". It does not say if the instant `lockTimeoutMillis` ms after the acquisition counts as "has passed".
- C04 has a second gap, the one of C05. A new token needs two facts: time 10 counts as expired, and `tryLock` may take over an expired entry. Null is the more likely outcome, but no sentence decides it.

C05 (time 11, no monitor pass, expired entry still in the map).
- The text points both ways, so the row is `open`, not `assumed`.
- For null (the entry stays until the monitor drops it): [README.md#local-lock-map-size/2] "which runs on a 1 s interval and so can lag by up to that much". Also, [KeyLock.kt(core)#tryLock] "@return an opaque ownership token, or null when another holder owns the lock." names no expiry.
- For a new token (`tryLock` takes the expired lock over): [KeyLock.kt(core)#KeyLock] "so a holder whose lock already expired and was taken over by someone else" and [KeyLock.kt(reactor)#unLock] "so an expired lock".
- No sentence says who takes the lock over, `tryLock` or only a `tryLock` that runs after the monitor dropped the entry.

C06 and C07 (both follow the branch where C05 returned `t2`).
- The premise comes from C05, which is `open`. Under the premise the text decides: the first holder gets false, the new owner gets true.

C07 and C08 (true).
- The text states the guard with "only if". It lists false only for "not held" and "mismatch". It does not write "returns true". The row is `settled` because no reasonable reader expects a matching owner to be refused. A reviewer who wants a literal reading can change both rows to `assumed`.

C09 (the holder releases at time 11, entry untouched).
- The reactor sentence limits the stale-owner rule to a lock "that was already handed to another holder". This suggests that an expired lock that nobody took can still be released by its holder.
- The text never says it. The row is `assumed`. An "expired means not held" reading gives false.

C12 (`unLock` with the permit that no entry backs).
- No sentence names `unLock` for such a permit. The false result needs the reading that a lock with no map entry "is not held", and that no owner exists to match.
- The row is `assumed`. No reading in the text gives true.

C19 (the map is full, the lease has passed, the entry is still in the map).
- Settled part: [README.md#local-lock-map-size/4] "are not subject to the cap at all." So the outcome is never "a new token no entry backs".
- Open part: null or a new token that an entry backs. This is the same gap as C05.
