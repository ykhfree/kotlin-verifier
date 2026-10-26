# Questions for review

Each question is one decision. The options come from the data: each draft's verdict and, if given, the reference sidecar. A verdict `open` means the clauses do not decide the cell. Record each answer as a named probe with `reviewed` and `source` (DESIGN-C.md 3.5).

## Q-D1 Expiry boundary

At exactly the lease end (clock = lease), is a lock that nobody released still held?

Cells: C04 (rules 1, 3, 4, 5), C15 (rules 1, 4, 5)

| Cell | Scenario | Outcomes |
|---|---|---|
| C04 | Acquired at time 0, not released: tryLock(k) at time 10 (the lease end) | null: `r == NULL`; token: `r != NULL` |
| C15 | Acquired at time 0: a monitor pass at time 10 looks at k | stays: `carried(S, k, t1)`; dropped: `!has(S, k)` |

Sources (the quotes in the authors' answers):
- [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed" (opus C04, opus C15)
- [KeyLock.kt(core)#KeyLock] "release it, so a holder whose lock already expired and was taken over by someone else" (opus C04)

Options (the outcome of each cell; from the verdicts):
A. C04 null, C15 stays: reference.
B. C04 token, C15 dropped: draft opus.

Results per option on implementations (measured: `kv verify --draft` of a draft fixed to each option; DESIGN-C.md 3.6):

| Implementation | A | B |
|---|---|---|
| reference | PROVEN | FAILED (sweep progress_sweep_drops_expired; tryLock progress_tryLock_takeover) |
| dm:D1-both | FAILED (sweep ends_only_if sweep_ends_only_expired; tryLock ends_only_if tryLock_ends_only_expired) | PROVEN |
| dm:D2-expired-unlock-false | FAILED (unLock progress_unLock_owner_releases) | FAILED (sweep progress_sweep_drops_expired; tryLock progress_tryLock_takeover; unLock progress_unLock_owner_releases) |
| dm:D4-no-takeover | FAILED (tryLock progress_tryLock_takeover) | FAILED (sweep progress_sweep_drops_expired; tryLock progress_tryLock_takeover) |
| dm:D9-sweep-never | FAILED (sweep progress_sweep_drops_expired) | FAILED (sweep progress_sweep_drops_expired; tryLock progress_tryLock_takeover) |
| dm:D3-cap-null | FAILED (tryLock progress_tryLock_cap_refusal) | FAILED (sweep progress_sweep_drops_expired; tryLock progress_tryLock_cap_refusal, progress_tryLock_takeover) |
| impl:T-haiku-2 | FAILED (sweep ends_only_if sweep_ends_only_expired; tryLock UNSUPPORTED; probe UNSUPPORTED) | INCOMPLETE (tryLock UNSUPPORTED; probe UNSUPPORTED) |
| impl:T-haiku-4 | FAILED (sweep ends_only_if sweep_ends_only_expired; tryLock UNSUPPORTED; probe UNSUPPORTED) | INCOMPLETE (tryLock UNSUPPORTED; probe UNSUPPORTED) |
| impl:T-sonnet-1 | FAILED (sweep ends_only_if sweep_ends_only_expired; tryLock ends_only_if tryLock_ends_only_expired) | PROVEN |
| impl:T-sonnet-3 | FAILED (sweep ends_only_if sweep_ends_only_expired; tryLock UNSUPPORTED; unLock UNSUPPORTED; probe UNSUPPORTED) | INCOMPLETE (tryLock UNSUPPORTED; unLock UNSUPPORTED; probe UNSUPPORTED) |
| impl:T-sonnet-4 | FAILED (sweep ends_only_if sweep_ends_only_expired; tryLock ends_only_if tryLock_ends_only_expired) | PROVEN |
| impl:T-opus-1 | FAILED (sweep ends_only_if sweep_ends_only_expired; tryLock UNSUPPORTED; unLock UNSUPPORTED; probe UNSUPPORTED) | INCOMPLETE (tryLock UNSUPPORTED; unLock UNSUPPORTED; probe UNSUPPORTED) |
| impl:T-opus-2 | FAILED (sweep ends_only_if sweep_ends_only_expired; tryLock UNSUPPORTED; probe UNSUPPORTED) | INCOMPLETE (tryLock UNSUPPORTED; probe UNSUPPORTED) |

| Draft | C04 verdict | C04 answer | C15 verdict | C15 answer |
|---|---|---|---|---|
| opus | token | none (open) | dropped | dropped (assumed) |

## Q-D4 Takeover of an expired lock

Does tryLock take over an expired lock before the cleanup monitor drops it?

Cells: C05 (rules 1, 3), C19 (rules 1, 3)

| Cell | Scenario | Outcomes |
|---|---|---|
| C05 | Acquired at time 0, not released, no monitor pass since: tryLock(k) at time 11 | null: `r == NULL`; token: `r != NULL` |
| C19 | Acquired at time 0, not released; the map is full: tryLock(k) at time 11 | null: `r == NULL`; backed: `r != NULL && tok(r).gBacked`; unbacked: `r != NULL && !tok(r).gBacked` |

Sources (the quotes in the authors' answers):
- [KeyLock.kt(core)#KeyLock] "release it, so a holder whose lock already expired and was taken over by someone else" (opus C05, opus C19)
- [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed, which runs on a 1 s interval and so can lag by up to that much." (opus C05)
- [README.md#local-lock-map-size/4] "Keys whose entry is already in" (opus C19)
- [README.md#local-lock-map-size/4] "the map are not subject to the cap at all." (opus C19)

Options (the outcome of each cell; from the verdicts):
A. C05 token, C19 backed: reference, draft opus.

Results per option on implementations (decision mutants, AI code): not measured.

| Draft | C05 verdict | C05 answer | C19 verdict | C19 answer |
|---|---|---|---|---|
| opus | token | none (open) | backed | none (open) |

## Q-D2 unLock by the holder after expiry

Can the holder still release its lock after the lease ended, if nobody took it over?

Cells: C09 (rules 1)

| Cell | Scenario | Outcomes |
|---|---|---|
| C09 | Acquired at time 0: unLock(k, t1) at time 5 (the steps of C08) | true: `b`; false: `!b` |

Sources (the quotes in the authors' answers):
- [KeyLock.kt(core)#KeyLock] "release it, so a holder whose lock already expired and was taken over by someone else" (opus C09)
- [KeyLock.kt(core)#unLock] "@return false when the lock is not held or the token does not match the current owner." (opus C09)

Options (the outcome of each cell; from the verdicts):
A. C09 true: reference, draft opus.

Results per option on implementations (decision mutants, AI code): not measured.

| Draft | C09 verdict | C09 answer |
|---|---|---|
| opus | true | true (assumed) |

## Q-D5 unLock of a token no entry backs

Does unLock release a permit that no map entry backs?

Cells: C12 (rules 1)

| Cell | Scenario | Outcomes |
|---|---|---|
| C12 | After C02 returned a token u: unLock(k, u) | true: `b`; false: `!b` |

Sources (the quotes in the authors' answers):
- [KeyLock.kt(core)#unLock] "@return false when the lock is not held or the token does not match the current owner." (opus C12)
- [README.md#local-lock-map-size/4] "Once the map is full, a request for a **new** key is handed a permit that no map entry backs." (opus C12)

Options (the outcome of each cell; from the verdicts):
A. C12 false: reference, draft opus.

Results per option on implementations (decision mutants, AI code): not measured.

| Draft | C12 verdict | C12 answer |
|---|---|---|
| opus | false | false (assumed) |

## Q-D9 Monitor duty after the lease end

Must the cleanup monitor drop the entry of a lock that nobody released, one step after the lease end?

Cells: C16 (rules 6)

| Cell | Scenario | Outcomes |
|---|---|---|
| C16 | Acquired at time 0: a monitor pass at time 11 looks at k | stays: `carried(S, k, t1)`; dropped: `!has(S, k)` |

Sources (the quotes in the authors' answers):
- [README.md#local-lock-map-size/2] "an entry whose holder never released it is dropped by the" (opus C16)
- [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed" (opus C16)

Options (the outcome of each cell; from the verdicts):
A. C16 open: reference.
B. C16 dropped: draft opus.

Results per option on implementations (decision mutants, AI code): not measured.

| Draft | C16 verdict | C16 answer |
|---|---|---|
| opus | dropped | dropped (settled) |
