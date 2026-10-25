# Questions for the reviewer

Each question has: the quote that leaves the point open, the options, the option that `DRAFT.kv` encodes with its
clause IDs, and a confidence level. "Skeleton" means a line that was fixed before this draft.
There are 17 questions.

## Q1. Does `tryLock` take over an expired entry that the monitor has not dropped yet?

Quotes:
- [KeyLock.kt(core)#tryLock] "@return an opaque ownership token, or null when another holder owns the lock."
- [KeyLock.kt(core)#KeyLock] "so a holder whose lock already expired and was taken over by someone else"
- [README.md#local-lock-map-size/2] "which runs on a 1 s interval and so can lag by up to that much"

Options:
- A (no take-over): While an entry exists for the key, `tryLock` returns NULL and changes nothing. Only the monitor or the owner's `unLock` ends the acquisition. The key stays locked until the monitor drops the entry, up to 1 s after the lease ends.
- B (take-over): If an entry exists and `now >= expiresAt`, `tryLock` replaces it. It returns a new backed token, sets `expiresAt = now + timeout`, and the old acquisition ends. If the entry is not expired, it returns NULL.

DRAFT encodes: A. Clauses: tryLock_held_key_returns_null, tryLock_held_key_unchanged. `tryLock` has no `ends_only_if`, so by the language default no acquisition can end in `tryLock`.

To switch to B: make the first two clauses hold only when `now < old(S.m[k].expiresAt)`. Add `progress_tryLock_expired_key_taken_over: old(has(S, k)) && old(S.m[k].expiresAt) <= now ==> res != NULL && carried(S, k, res)`. Add `ends_only_if tryLock_ends_only_expired: tok(qt).gLease <= now`. A taken-over key is already in the map, so the cap does not apply to it (Q9).

Confidence: low. No sentence says what `tryLock` does with an expired entry. A is the smallest reading and it matches the language default. "Taken over" and "handed to another holder" fit A (the monitor drops the entry, then a new `tryLock` wins) and they fit B.

## Q2. Is an entry expired when `now == expiresAt`?

Quote:
- [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed"

Options:
- A: Expired when `expiresAt <= now`. The lease ends exactly `timeout` ms after the clock reading.
- B: Expired when `expiresAt < now`. The entry still lives at the instant `now == expiresAt`.

DRAFT encodes: A. Clauses: progress_sweep_drops_expired, sweep_keeps_unexpired, sweep_ends_only_expired. For B, use `<` instead of `<=` in the first and third clause, and `now <= old(S.m[k].expiresAt)` in the second.

Confidence: low. "Has passed" does not say whether the instant of equality counts. The skeleton calls `gLease` "the time the acquisition expires", which supports A a little. The effect is one millisecond.

## Q3. What does `lockTimeoutMillis` measure, and which instance's value counts?

Quotes:
- [CLAUDE.md#reqshieldconfiguration-parameters/4] "`lockTimeoutMillis`: Lock acquisition timeout (default: 3000ms)"
- [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed"
- [README.md#waiting-for-another-request-lock-not-acquired/1] "A request that loses the lock polls the cache every 50 ms, up to `maxAttemptGetCache` times (default 60)."

Options:
- A (lease): An entry expires `timeout` ms after the clock reading of the `tryLock` that created it. `expiresAt = now + timeout`, with the `timeout` of the instance that acquired the lock. `tryLock` never waits.
- B (wait limit): `tryLock` waits up to `timeout` ms for the lock before it returns NULL. The entry has another lifetime.
- C (one global value): The monitor uses one timeout for all entries, not the value of the instance that acquired the lock.

DRAFT encodes: A. Clauses: `issue` (gLease = now + timeout), tryLock_sets_lease, inv_entry_expiry_is_lease.

Confidence: medium. The README ties the value to the drop of the entry. B does not fit "null when another holder owns the lock" (no waiting) and it does not fit the caller polling the cache. The skeleton gives each entry its own `expiresAt` and puts the monitor in a companion, and this fits A.

## Q4. Can a call other than the acquiring `tryLock` change an entry without dropping it?

Quotes:
- [README.md#local-lock-map-size/2] "an entry whose holder never released it"
- [KeyLock.kt(core)#tryLock] "@return an opaque ownership token, or null when another holder owns the lock."

Options:
- A (untouched): A `tryLock` that returns NULL, an `unLock` that returns false, and a `sweep` that keeps the entry all leave token and `expiresAt` as they were. The lease never changes. There is no renewal.
- B (touch allowed): Some call may extend or shorten the lease, or change the token, and keep the entry. For example, a failed `tryLock` extends the lease.

DRAFT encodes: A. Clauses: tryLock_held_key_unchanged, unLock_failure_changes_nothing, sweep_never_adds_or_changes, inv_entry_expiry_is_lease.

Confidence: medium. The text names no renewal, and every statement about expiry counts from the acquisition.

## Q5. Must a monitor step drop an expired entry, or may it?

Quotes:
- [README.md#local-lock-map-size/2] "an entry whose holder never released it"
- [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed"

Options:
- A (obligation): A `sweep` step on a key with an expired entry always drops the entry. When the passes happen is not modeled.
- B (permission only): A `sweep` step may drop an expired entry but it does not have to. A monitor that does nothing would meet the specification.

DRAFT encodes: A. Clauses: progress_sweep_drops_expired. sweep_ends_only_expired is the permission side.

Confidence: medium. The text says the entry "is dropped", not "may be dropped". Under B nothing would bound the map.

## Q6. Can the owner release after the lease has passed but before the monitor drops the entry?

Quotes:
- [KeyLock.kt(core)#unLock] "Releases the lock for [key] + [lockType] only if [token] matches the current owner."
- [KeyLock.kt(reactor)#unLock] "that was already handed to another holder can never be released by a stale owner."

Options:
- A: A matching token releases (result true, entry dropped), whatever the clock says. `unLock` has no clock input. The owner of record is the token in the entry.
- B: An expired holder is refused (result false) even if its entry is still in the map.

DRAFT encodes: A. Clauses: progress_unLock_owner_releases, unLock_true_only_for_owner. B cannot be stated, because `unLock` has no clock input.

Confidence: medium. The text blocks only a stale owner whose lock was "handed to another holder". It says nothing about an expired entry that nobody took.

## Q7. Does a matching token always release?

Quotes:
- [KeyLock.kt(core)#unLock] "Releases the lock for [key] + [lockType] only if [token] matches the current owner."
- [README.md#local-lock-map-size/2] "An entry is dropped as soon as the lock is released"

Options:
- A (if and only if): A matching token always releases. The result is true and the entry is dropped in the same step.
- B (only if): "Only if" is a necessary condition only. A matching call may still return false and keep the entry.

DRAFT encodes: A. Clauses: progress_unLock_owner_releases (the other direction is unLock_true_only_for_owner).

Confidence: medium. Under B an `unLock` that never releases would meet the clause. The KDoc lists false only for "not held" and "mismatch".

## Q8. What exactly is the "permit that no map entry backs"?

Quotes:
- [README.md#local-lock-map-size/4] "Once the map is full, a request for a **new** key is handed a permit that no map entry backs."
- [README.md#local-lock-map-size/4] "no added latency, and the cache still gets populated"

Options:
- A: `tryLock` returns a fresh non-NULL token. No entry is added, so the key stays free for other callers. A later `unLock` with this token returns false because nothing is held. It never releases the entry of another holder.
- B: `tryLock` returns one shared constant to all refused callers. The skeleton forbids this: a token is never handed out twice.
- C: `tryLock` returns NULL and the caller waits. This does not fit "no added latency".

DRAFT encodes: A. Clauses: progress_tryLock_cap_refusal_proceeds, tryLock_unbacked_only_on_cap_refusal, inv_unbacked_token_not_carried, `issue` (gBacked = carried(S, k, res)).

Confidence: high for "non-NULL token and no entry". Medium for "`unLock` with it returns false": the text does not say what the caller sees. A consequence: two refused callers can run at the same time, and an unbacked permit can run beside a later backed holder. lem_one_backed_holder_per_key speaks only of backed tokens.

## Q9. Is a key that already has an entry subject to the cap?

Quote:
- [README.md#local-lock-map-size/4] "are not subject to the cap at all."

Options:
- A: No. If an entry exists, `tryLock` returns NULL and changes nothing, whatever `rej` says.
- B: Yes. With `rej` true, `tryLock` returns an unbacked permit even for a held key.

DRAFT encodes: A. Clauses: tryLock_held_key_returns_null (it excludes B), tryLock_held_key_unchanged.

Confidence: high. The sentence says it. A key that another caller inserts between the size check and the step counts as "already in the map" in that step.

## Q10. Must `tryLock` succeed on a free key when the cap does not refuse?

Quote:
- [KeyLock.kt(core)#tryLock] "@return an opaque ownership token, or null when another holder owns the lock."

Options:
- A: Yes. If no entry exists and `rej` is false, `tryLock` returns a backed token and the key gets an entry. NULL is returned only when an entry exists.
- B: `tryLock` may return NULL even when nobody holds the lock (a spurious failure).

DRAFT encodes: A. Clauses: progress_tryLock_free_key_acquires. The case `limit == 0` (no cap) follows from the skeleton contract `rej ==> limit != 0`.

Confidence: high. The KDoc names one reason for NULL: another holder owns the lock.

## Q11. How far can the map pass the cap?

Quotes:
- [README.md#local-lock-map-size/5] "The cap is a soft one."
- [README.md#local-lock-map-size/5] "The size check is an estimate and is not atomic with the insertion it guards"

Options:
- A: `rej` is an arbitrary input. The draft states no relation between `rej` and the real map size. A caller can get a backed token while the map is over the cap.
- B: A bound such as "size <= limit + number of concurrent callers".
- C: A hard bound: size <= limit at all times. This contradicts "soft".

DRAFT encodes: A. Clauses: none (the skeleton makes `rej` a free input). B and C cannot be stated, because the language has no map size.

Confidence: high that C is wrong. The text gives no number for "slightly", so B stays open for the reviewer.

## Q12. Do entries exist only while a lock is held?

Quotes:
- [README.md#local-lock-map-size/1] "keeps one entry per `(cache key, lock type)` it is currently"
- [README.md#local-lock-map-size/2] "An entry is dropped as soon as the lock is released"

Options:
- A: An entry exists exactly while its lock is held. The acquiring `tryLock` creates it. The owner's `unLock` or the monitor removes it. No idle entry stays in the map.
- B: An entry may stay after the release (for example, kept free for reuse) until the monitor removes it.

DRAFT encodes: A. Clauses: progress_unLock_owner_releases, progress_sweep_drops_expired, sweep_never_adds_or_changes, tryLock_held_key_unchanged, progress_tryLock_cap_refusal_proceeds, inv_held_token_is_carried. No clause quantifies over all keys. The fact "every entry belongs to a held lock" follows by induction from the per-step `ensures`.

Confidence: high. "Dropped as soon as" and "currently" point to A.

## Q13. How unique must a token be?

Quotes:
- [KeyLock.kt(core)#KeyLock] "cannot release the new owner's lock."
- [README.md#global-lock-guidance/2] "Every lock acquisition carries an ownership token."

Options:
- A: A token is never handed out twice, for any key, at any time (skeleton ghost rule 1).
- B: A token must differ only from the tokens that are held now for the same key.

DRAFT encodes: A. No clause of mine: the skeleton fixes it.

Confidence: medium. The text needs only "a stale token is not the new owner's token", and B is enough for that. A is stricter and is the skeleton's choice. The reviewer should confirm that the stricter rule is wanted.

## Q14. What if `lockTimeoutMillis` is 0 or negative?

Quote:
- [CLAUDE.md#reqshieldconfiguration-parameters/4] "`lockTimeoutMillis`: Lock acquisition timeout (default: 3000ms)"

Options:
- A: No special rule. `tryLock` makes an entry with `expiresAt = now + timeout`, which is not after `now`. The next `sweep` step on the key can drop it (Q2). Until then `tryLock` returns NULL for that key (Q1, option A).
- B: `tryLock` does not lock when `timeout <= 0` (it returns an unbacked permit or NULL).
- C: The configuration rejects such a value before it reaches the lock (outside the model).

DRAFT encodes: A. No clause restricts `timeout`. Clauses at work: tryLock_sets_lease, progress_sweep_drops_expired.

Confidence: low. The text gives only the default value.

## Q15. Are `permits` and `isHeld` part of the specification?

Quote:
- [KeyLock.kt(core)#KeyLock] "Ownership is represented by an opaque token"

The text never names the semaphore or the held flag. This quote is the closest sentence: it says ownership is the token.

Options:
- A: Only tokens, entries and expiry are specified. `permits` and `isHeld` are left to the hole MI_INV, which a tool fills for each implementation.
- B: Add clauses such as "an entry that carries a token has `isHeld` true and `permits == 0`".

DRAFT encodes: A. No clause mentions `permits` or `isHeld`.

Confidence: medium. Without a sentence in the text, a clause on these fields would invent a rule.

## Q16. Do the reactor and coroutine KDocs describe `KeyLocalLock`?

Quotes:
- [KeyLock.kt(reactor)#tryLock] "Emits an opaque ownership token when the lock was acquired, and completes EMPTY"
- [KeyLock.kt(coroutine)#tryLock] "Returns an opaque ownership token when acquired, or null when another holder owns the lock."

Options:
- A: They state the same contract for the Mono and `suspend` variants of the same lock. They add no new rule.
- B: They describe other lock classes. They are not part of this specification.

DRAFT encodes: A, in COVERAGE.md only. No clause depends on these blocks alone. The reactor `unLock` block adds the sentence about a stale owner, which the core blocks also state.

Confidence: medium. The wording matches the core KDoc. The task says that most of the text is about other parts of the library.

## Q17. Is the lock re-entrant?

Quote:
- [KeyLock.kt(core)#tryLock] "@return an opaque ownership token, or null when another holder owns the lock."

Options:
- A: No. If an entry exists, `tryLock` returns NULL, even for a caller that already holds the lock. The model has no caller identity, so every second call is "another holder".
- B: Yes. A holder that calls `tryLock` again gets its token back, or a second token.

DRAFT encodes: A. Clauses: tryLock_held_key_returns_null.

Confidence: medium. B needs caller identity, which the model does not have. B also gives the same token twice, which skeleton ghost rule 1 forbids.
