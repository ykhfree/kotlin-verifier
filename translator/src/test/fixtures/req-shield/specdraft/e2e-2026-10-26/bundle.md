# Questions for review

Each question is one decision. The options come from the data: each draft's verdict and, if given, the reference sidecar. A verdict `open` means the clauses do not decide the cell. Record each answer as a named probe with `reviewed` and `source` (DESIGN-C.md 3.5).

## Q-D1 Expiry boundary

At exactly the lease end (clock = lease), is a lock that nobody released still held, for tryLock and for the cleanup monitor?

Cells: H2r (rules 1, 5), H2s (rules 1, 5), H5r (rules 1, 5), H5s (rules 1, 5), H8r (rules 1, 5), H8s (rules 1, 5), H13s (rules 1, 5)

| Cell | Scenario | Outcomes |
|---|---|---|
| H2r | Set-up: t1 := tryLock(k, now = 0, timeout = 10, rej = false, limit = 0) returned a token k's entry backs (lease 10). tryLock(k, now = 10, timeout = 10, rej = false, limit = 0) (at the lease): the result | null: `r == NULL`; backed: `r != NULL && tok(r).gBacked`; unbacked: `r != NULL && !tok(r).gBacked` |
| H2s | Set-up: t1 := tryLock(k, now = 0, timeout = 10, rej = false, limit = 0) returned a token k's entry backs (lease 10). tryLock(k, now = 10, timeout = 10, rej = false, limit = 0) (at the lease): k's entry | no_entry: `!has(S, k)`; holds_t1: `carried(S, k, t1)`; holds_r: `r != NULL && r != t1 && carried(S, k, r)`; other: `has(S, k) && !carried(S, k, t1) && !(r != NULL && r != t1 && carried(S, k, r))` |
| H5r | Set-up: t1 := tryLock(k, now = 0, timeout = 10, rej = false, limit = 0) returned a token k's entry backs (lease 10). tryLock(k, now = 10, timeout = 10, rej = false, limit = 5) (at the lease): the result | null: `r == NULL`; backed: `r != NULL && tok(r).gBacked`; unbacked: `r != NULL && !tok(r).gBacked` |
| H5s | Set-up: t1 := tryLock(k, now = 0, timeout = 10, rej = false, limit = 0) returned a token k's entry backs (lease 10). tryLock(k, now = 10, timeout = 10, rej = false, limit = 5) (at the lease): k's entry | no_entry: `!has(S, k)`; holds_t1: `carried(S, k, t1)`; holds_r: `r != NULL && r != t1 && carried(S, k, r)`; other: `has(S, k) && !carried(S, k, t1) && !(r != NULL && r != t1 && carried(S, k, r))` |
| H8r | Set-up: t1 := tryLock(k, now = 0, timeout = 10, rej = false, limit = 0) returned a token k's entry backs (lease 10). tryLock(k, now = 10, timeout = 10, rej = true, limit = 5) (at the lease): the result | null: `r == NULL`; backed: `r != NULL && tok(r).gBacked`; unbacked: `r != NULL && !tok(r).gBacked` |
| H8s | Set-up: t1 := tryLock(k, now = 0, timeout = 10, rej = false, limit = 0) returned a token k's entry backs (lease 10). tryLock(k, now = 10, timeout = 10, rej = true, limit = 5) (at the lease): k's entry | no_entry: `!has(S, k)`; holds_t1: `carried(S, k, t1)`; holds_r: `r != NULL && r != t1 && carried(S, k, r)`; other: `has(S, k) && !carried(S, k, t1) && !(r != NULL && r != t1 && carried(S, k, r))` |
| H13s | Set-up: t1 := tryLock(k, now = 0, timeout = 10, rej = false, limit = 0) returned a token k's entry backs (lease 10). sweep(k, now = 10) (at the lease): k's entry | no_entry: `!has(S, k)`; holds_t1: `carried(S, k, t1)`; other: `has(S, k) && !carried(S, k, t1)` |

Sources (the quotes in the authors' answers):
- [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed, which runs on a 1 s interval and so can lag by up to that much." (d1 H2r, d1 H2s, d1 H5r, d1 H5s, d1 H8r, d1 H8s, d1 H13s)
- [KeyLock.kt(core)#KeyLock] "release it, so a holder whose lock already expired and was taken over by someone else" (d1 H2r)
- [README.md#local-lock-map-size/4] "the map are not subject to the cap at all." (d1 H8r)

Options (the outcome of each cell; from the verdicts):
A. H2r null, H2s holds_t1, H5r null, H5s holds_t1, H8r null, H8s holds_t1, H13s holds_t1: reference.
B. H2r open, H2s open, H5r open, H5s open, H8r open, H8s open, H13s open: draft d1.

Results per option on implementations (measured: `kv verify --draft` of a draft fixed to each option; DESIGN-C.md 3.6):

| Implementation | A | B |
|---|---|---|
| reference | PROVEN | FAILED (init FAILED) |
| dm:D1-both | FAILED (sweep ends_only_if sw_ends_only_expired; tryLock ends_only_if tl_ends_only_expired) | FAILED (init FAILED) |
| dm:D2-expired-unlock-false | FAILED (unLock progress_ul_release) | FAILED (unLock progress_ul_release; init FAILED) |
| dm:D4-no-takeover | FAILED (tryLock progress_tl_takeover_expired) | FAILED (init FAILED) |
| dm:D9-sweep-never | FAILED (sweep progress_sw_drops_expired) | FAILED (sweep progress_sw_drops_expired; init FAILED) |
| dm:D3-cap-null | FAILED (tryLock progress_tl_capped_new_key) | FAILED (tryLock progress_tl_capped_new_key; init FAILED) |
| impl:T-haiku-2 | FAILED (sweep ends_only_if sw_ends_only_expired; tryLock UNSUPPORTED; probe pr_contend UNSUPPORTED; probe pr_release_reacquire UNSUPPORTED; probe pr_stale_holder UNSUPPORTED; probe pr_cap_new_key UNSUPPORTED; probe pr_cap_existing_key UNSUPPORTED; probe pr_unbacked_unlock UNSUPPORTED; probe decision_D1 UNSUPPORTED; probe decision_D4 UNSUPPORTED; probe decision_D7 UNSUPPORTED; probe decision_D5 UNSUPPORTED) | FAILED (tryLock UNSUPPORTED; init FAILED; probe pr_contend UNSUPPORTED; probe pr_release_reacquire UNSUPPORTED; probe pr_stale_holder UNSUPPORTED; probe pr_cap_new_key UNSUPPORTED; probe pr_cap_existing_key UNSUPPORTED; probe pr_unbacked_unlock UNSUPPORTED) |
| impl:T-haiku-4 | FAILED (sweep ends_only_if sw_ends_only_expired; tryLock UNSUPPORTED; probe pr_contend UNSUPPORTED; probe pr_release_reacquire UNSUPPORTED; probe pr_stale_holder UNSUPPORTED; probe pr_cap_new_key UNSUPPORTED; probe pr_cap_existing_key UNSUPPORTED; probe pr_unbacked_unlock UNSUPPORTED; probe decision_D1 UNSUPPORTED; probe decision_D4 UNSUPPORTED; probe decision_D7 UNSUPPORTED; probe decision_D5 UNSUPPORTED) | FAILED (tryLock UNSUPPORTED; init FAILED; probe pr_contend UNSUPPORTED; probe pr_release_reacquire UNSUPPORTED; probe pr_stale_holder UNSUPPORTED; probe pr_cap_new_key UNSUPPORTED; probe pr_cap_existing_key UNSUPPORTED; probe pr_unbacked_unlock UNSUPPORTED) |
| impl:T-sonnet-1 | FAILED (sweep ends_only_if sw_ends_only_expired; tryLock ends_only_if tl_ends_only_expired) | FAILED (init FAILED) |
| impl:T-sonnet-3 | FAILED (sweep ends_only_if sw_ends_only_expired; tryLock UNSUPPORTED; unLock UNSUPPORTED; probe pr_contend UNSUPPORTED; probe pr_release_reacquire UNSUPPORTED; probe pr_stale_holder UNSUPPORTED; probe pr_cap_new_key UNSUPPORTED; probe pr_cap_existing_key UNSUPPORTED; probe pr_unbacked_unlock UNSUPPORTED; probe decision_D1 UNSUPPORTED; probe decision_D4 UNSUPPORTED; probe decision_D7 UNSUPPORTED; probe decision_D5 UNSUPPORTED) | FAILED (tryLock UNSUPPORTED; unLock UNSUPPORTED; init FAILED; probe pr_contend UNSUPPORTED; probe pr_release_reacquire UNSUPPORTED; probe pr_stale_holder UNSUPPORTED; probe pr_cap_new_key UNSUPPORTED; probe pr_cap_existing_key UNSUPPORTED; probe pr_unbacked_unlock UNSUPPORTED) |
| impl:T-sonnet-4 | FAILED (sweep ends_only_if sw_ends_only_expired; tryLock ends_only_if tl_ends_only_expired) | FAILED (init FAILED) |
| impl:T-opus-1 | FAILED (sweep ends_only_if sw_ends_only_expired; tryLock UNSUPPORTED; unLock UNSUPPORTED; probe pr_contend UNSUPPORTED; probe pr_release_reacquire UNSUPPORTED; probe pr_stale_holder UNSUPPORTED; probe pr_cap_new_key UNSUPPORTED; probe pr_cap_existing_key UNSUPPORTED; probe pr_unbacked_unlock UNSUPPORTED; probe decision_D1 UNSUPPORTED; probe decision_D4 UNSUPPORTED; probe decision_D7 UNSUPPORTED; probe decision_D5 UNSUPPORTED) | FAILED (tryLock UNSUPPORTED; unLock UNSUPPORTED; init FAILED; probe pr_contend UNSUPPORTED; probe pr_release_reacquire UNSUPPORTED; probe pr_stale_holder UNSUPPORTED; probe pr_cap_new_key UNSUPPORTED; probe pr_cap_existing_key UNSUPPORTED; probe pr_unbacked_unlock UNSUPPORTED) |
| impl:T-opus-2 | FAILED (sweep ends_only_if sw_ends_only_expired; tryLock UNSUPPORTED; probe pr_contend UNSUPPORTED; probe pr_release_reacquire UNSUPPORTED; probe pr_stale_holder UNSUPPORTED; probe pr_cap_new_key UNSUPPORTED; probe pr_cap_existing_key UNSUPPORTED; probe pr_unbacked_unlock UNSUPPORTED; probe decision_D1 UNSUPPORTED; probe decision_D4 UNSUPPORTED; probe decision_D7 UNSUPPORTED; probe decision_D5 UNSUPPORTED) | FAILED (tryLock UNSUPPORTED; init FAILED; probe pr_contend UNSUPPORTED; probe pr_release_reacquire UNSUPPORTED; probe pr_stale_holder UNSUPPORTED; probe pr_cap_new_key UNSUPPORTED; probe pr_cap_existing_key UNSUPPORTED; probe pr_unbacked_unlock UNSUPPORTED) |
| impl:T-sonnet-2 | FAILED (sweep ends_only_if sw_ends_only_expired; tryLock ends_only_if tl_ends_only_expired) | FAILED (init FAILED) |
| impl:T-opus-3 | FAILED (sweep ends_only_if sw_ends_only_expired; tryLock ends_only_if tl_ends_only_expired) | FAILED (init FAILED) |
| impl:T-opus-4 | FAILED (sweep ends_only_if sw_ends_only_expired; tryLock UNSUPPORTED; probe pr_contend UNSUPPORTED; probe pr_release_reacquire UNSUPPORTED; probe pr_stale_holder UNSUPPORTED; probe pr_cap_new_key UNSUPPORTED; probe pr_cap_existing_key UNSUPPORTED; probe pr_unbacked_unlock UNSUPPORTED; probe decision_D1 UNSUPPORTED; probe decision_D4 UNSUPPORTED; probe decision_D7 UNSUPPORTED; probe decision_D5 UNSUPPORTED) | FAILED (tryLock UNSUPPORTED; init FAILED; probe pr_contend UNSUPPORTED; probe pr_release_reacquire UNSUPPORTED; probe pr_stale_holder UNSUPPORTED; probe pr_cap_new_key UNSUPPORTED; probe pr_cap_existing_key UNSUPPORTED; probe pr_unbacked_unlock UNSUPPORTED) |

| Draft | H2r verdict | H2r answer | H2s verdict | H2s answer | H5r verdict | H5r answer | H5s verdict | H5s answer | H8r verdict | H8r answer | H8s verdict | H8s answer | H13s verdict | H13s answer |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| d1 | open | null (open) | open | holds_t1 (open) | open | null (open) | open | holds_t1 (open) | open | null (open) | open | holds_t1 (open) | open | no_entry (open) |

## Q-D4 Takeover of an expired lock

After the lease end, does tryLock take over an expired lock before the cleanup monitor drops it?

Cells: H3r (rules 1), H3s (rules 1), H6r (rules 1), H6s (rules 1), H9r (rules 1), H9s (rules 1)

| Cell | Scenario | Outcomes |
|---|---|---|
| H3r | Set-up: t1 := tryLock(k, now = 0, timeout = 10, rej = false, limit = 0) returned a token k's entry backs (lease 10). tryLock(k, now = 11, timeout = 10, rej = false, limit = 0) (after the lease): the result | null: `r == NULL`; backed: `r != NULL && tok(r).gBacked`; unbacked: `r != NULL && !tok(r).gBacked` |
| H3s | Set-up: t1 := tryLock(k, now = 0, timeout = 10, rej = false, limit = 0) returned a token k's entry backs (lease 10). tryLock(k, now = 11, timeout = 10, rej = false, limit = 0) (after the lease): k's entry | no_entry: `!has(S, k)`; holds_t1: `carried(S, k, t1)`; holds_r: `r != NULL && r != t1 && carried(S, k, r)`; other: `has(S, k) && !carried(S, k, t1) && !(r != NULL && r != t1 && carried(S, k, r))` |
| H6r | Set-up: t1 := tryLock(k, now = 0, timeout = 10, rej = false, limit = 0) returned a token k's entry backs (lease 10). tryLock(k, now = 11, timeout = 10, rej = false, limit = 5) (after the lease): the result | null: `r == NULL`; backed: `r != NULL && tok(r).gBacked`; unbacked: `r != NULL && !tok(r).gBacked` |
| H6s | Set-up: t1 := tryLock(k, now = 0, timeout = 10, rej = false, limit = 0) returned a token k's entry backs (lease 10). tryLock(k, now = 11, timeout = 10, rej = false, limit = 5) (after the lease): k's entry | no_entry: `!has(S, k)`; holds_t1: `carried(S, k, t1)`; holds_r: `r != NULL && r != t1 && carried(S, k, r)`; other: `has(S, k) && !carried(S, k, t1) && !(r != NULL && r != t1 && carried(S, k, r))` |
| H9r | Set-up: t1 := tryLock(k, now = 0, timeout = 10, rej = false, limit = 0) returned a token k's entry backs (lease 10). tryLock(k, now = 11, timeout = 10, rej = true, limit = 5) (after the lease): the result | null: `r == NULL`; backed: `r != NULL && tok(r).gBacked`; unbacked: `r != NULL && !tok(r).gBacked` |
| H9s | Set-up: t1 := tryLock(k, now = 0, timeout = 10, rej = false, limit = 0) returned a token k's entry backs (lease 10). tryLock(k, now = 11, timeout = 10, rej = true, limit = 5) (after the lease): k's entry | no_entry: `!has(S, k)`; holds_t1: `carried(S, k, t1)`; holds_r: `r != NULL && r != t1 && carried(S, k, r)`; other: `has(S, k) && !carried(S, k, t1) && !(r != NULL && r != t1 && carried(S, k, r))` |

Sources (the quotes in the authors' answers):
- [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed, which runs on a 1 s interval and so can lag by up to that much." (d1 H3r, d1 H3s, d1 H6r, d1 H6s, d1 H9s)
- [KeyLock.kt(core)#KeyLock] "release it, so a holder whose lock already expired and was taken over by someone else" (d1 H3r, d1 H6r, d1 H9r)
- [README.md#local-lock-map-size/4] "the map are not subject to the cap at all." (d1 H9r)

Options (the outcome of each cell; from the verdicts):
A. H3r backed, H3s holds_r, H6r backed, H6s holds_r, H9r backed, H9s holds_r: reference.
B. H3r open, H3s open, H6r open, H6s open, H9r open, H9s open: draft d1.

Results per option on implementations (decision mutants, AI code): not measured.

| Draft | H3r verdict | H3r answer | H3s verdict | H3s answer | H6r verdict | H6r answer | H6s verdict | H6s answer | H9r verdict | H9r answer | H9s verdict | H9s answer |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| d1 | open | null (open) | open | holds_t1 (open) | open | null (open) | open | holds_t1 (open) | open | null (open) | open | holds_t1 (open) |

## Q-D7 Lease start after a takeover

After tryLock takes over an expired lock, does the new holder's lease start at its own acquisition?

Cells: O4s (rules 1)

| Cell | Scenario | Outcomes |
|---|---|---|
| O4s | Set-up: t1 := tryLock(k, now = 0, timeout = 10, rej = false, limit = 0) returned a token k's entry backs (lease 10); then tryLock(k, now = 11, timeout = 10, rej = false, limit = 0) returned t2 and k's entry no longer holds t1. sweep(k, now = 11) (after the lease): k's entry | no_entry: `!has(S, k)`; holds_t1: `carried(S, k, t1)`; holds_t2: `carried(S, k, t2)`; other: `has(S, k) && !carried(S, k, t1) && !carried(S, k, t2)` |

Sources (the quotes in the authors' answers):
- [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed, which runs on a 1 s interval and so can lag by up to that much." (d1 O4s)

Options (the outcome of each cell; from the verdicts):
A. O4s holds_t2: reference, draft d1.

Results per option on implementations (decision mutants, AI code): not measured.

| Draft | O4s verdict | O4s answer |
|---|---|---|
| d1 | holds_t2 | holds_t2 (assumed) |

## Q-D5 unLock of an unbacked permit

Does unLock of a permit that no map entry backs (the cap path) return false?

Cells: U2r (rules 1)

| Cell | Scenario | Outcomes |
|---|---|---|
| U2r | u := tryLock(k, now = 0, timeout = 10, rej = rej0, limit = limit0) returned a token no entry backs (for some rej0, limit0). unLock(k, t = u): the result | true: `r`; false: `!r` |

Sources (the quotes in the authors' answers):
- [KeyLock.kt(core)#unLock] "@return false when the lock is not held or the token does not match the current owner." (d1 U2r)
- [README.md#local-lock-map-size/4] "Once the map is full, a request for a **new** key is handed a permit that no map entry backs." (d1 U2r)

Options (the outcome of each cell; from the verdicts):
A. U2r false: reference, draft d1.

Results per option on implementations (decision mutants, AI code): not measured.

| Draft | U2r verdict | U2r answer |
|---|---|---|
| d1 | false | false (assumed) |
