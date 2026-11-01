# Coverage of SOURCES.md by DRAFT.kv

Clause IDs are the IDs in `DRAFT.kv`. The `issue` rule and the `probe` have no ID in the language, so they are called
`issue` and `probe`. "Skeleton" means a line that was fixed before this draft (ghost rules, trusted contract, witness).
"Q" numbers point to `QUESTIONS.md`.

## Table

| Anchor | What the block requires | Clause IDs, or "not encoded" and why |
|---|---|---|
| README.md#cache-key-layout/2 | Lock keys are prefixed with `reqshield:lock:` and derived from the namespaced cache key. Two caches never share a lock or an entry. | Not encoded: out of the model. `Key` is an opaque type (skeleton). The key derivation is code outside `KeyLocalLock`. |
| README.md#global-lock-guidance/2 | Every acquisition carries an ownership token. Only the acquiring holder can release. A slow holder whose lock expired cannot release the next holder's lock. (The section is about the global lock, but the wording is general.) | `issue`, unLock_true_only_for_owner, unLock_failure_changes_nothing, unLock_ends_only_presented_token, inv_held_token_is_carried. Fresh tokens come from skeleton ghost rule 1 (Q13). See also Q1, Q6. |
| README.md#local-lock-map-size/1 | The local lock keeps one entry per (cache key, lock type) that it is locking, in one JVM-wide map. A key that is created and refreshed uses up to two entries. | progress_tryLock_free_key_acquires, progress_unLock_owner_releases, progress_sweep_drops_expired, sweep_never_adds_or_changes, inv_held_token_is_carried (Q12). The JVM-wide map is the skeleton `state m`. "Up to two entries": not encoded, out of the model (key derivation). |
| README.md#local-lock-map-size/2 | Release drops the entry at once. The monitor drops an entry whose holder never released it once `lockTimeoutMillis` has passed, up to 1 s late. The map size tracks the locks held at the same time (example: about 500). | `issue` (gLease), tryLock_sets_lease, inv_entry_expiry_is_lease, progress_unLock_owner_releases, progress_sweep_drops_expired, sweep_keeps_unexpired, sweep_ends_only_expired, sweep_never_adds_or_changes (Q2 to Q6, Q12, Q14). Not encoded: the 1 s interval and the lag (timing and liveness cannot be stated); "map size tracks the locks" (the language has no map size); the 500-lock example (an illustration, not a requirement). |
| README.md#local-lock-map-size/3 | The map is uncapped by default. `0` means uncapped. The cap is set by a property. | progress_tryLock_free_key_acquires, together with the skeleton contract of `rejectsNewEntry` (`rej ==> limit != 0`). Not encoded: property name, default value and reading of YAML, system property and environment variable (out of the model: configuration). |
| README.md#local-lock-map-size/4 | When the map is full, a new key gets a permit that no entry backs and runs without collapsing. Keys that have an entry are not subject to the cap. | progress_tryLock_cap_refusal_proceeds, tryLock_unbacked_only_on_cap_refusal, inv_unbacked_token_not_carried, `issue` (gBacked), tryLock_held_key_returns_null (Q8, Q9). Not encoded: that the caller then calls the supplier and writes the cache (ReqShield behavior, out of the model); "no added latency" (timing, cannot be stated; only the immediate permit is stated). |
| README.md#local-lock-map-size/5 | The cap is soft. The size check is an estimate and is not atomic with the insertion. The map can pass the cap slightly. | Modelled by the free input `rej` (skeleton): no clause ties `rej` to the real map size. Not encoded: a bound on "slightly" (the language has no map size and the text gives no number) (Q11). |
| README.md#local-lock-map-size/6 | The first refusal and every 1000th are logged at WARN. | Not encoded: out of the model (logging; `rejectsNewEntry` has `effects none` in the skeleton). |
| README.md#local-lock-map-size/7 | An unreadable cap value is logged at WARN and ignored. Setting `LocalLockLimit.maxEntries = -1` fails fast. | Not encoded: out of the model (configuration parsing and `LocalLockLimit`). |
| CLAUDE.md#project-overview/1 | Only one request for the same cache key is processed at a time. Other requests wait. | lem_one_backed_holder_per_key, tryLock_held_key_returns_null. The waiting is ReqShield behavior (out of the model). Unbacked permits are the allowed exception (Q8). |
| CLAUDE.md#key-components/2 | `tryLock` returns an ownership token. `unLock` releases only when the token matches. | `issue`, tryLock_held_key_returns_null, progress_tryLock_free_key_acquires, unLock_true_only_for_owner, progress_unLock_owner_releases, unLock_failure_changes_nothing, unLock_ends_only_presented_token (Q7, Q13). |
| CLAUDE.md#reqshieldconfiguration-parameters/4 | `lockTimeoutMillis` is the "lock acquisition timeout", default 3000 ms. | tryLock_sets_lease, `issue` (gLease), inv_entry_expiry_is_lease (Q3). The default 3000 is not encoded: `timeout` is an arbitrary input in the model. |
| KeyLock.kt(core)#KeyLock | The lock collapses concurrent requests per cache key. Ownership is an opaque token. Only the acquirer can release. A holder whose lock expired and was taken over cannot release the new owner's lock. | lem_one_backed_holder_per_key, inv_held_token_is_carried, unLock_true_only_for_owner, unLock_failure_changes_nothing, unLock_ends_only_presented_token (Q1, Q13). "Opaque": not encoded; the `Token` template in the skeleton fixes the format. |
| KeyLock.kt(core)#tryLock | `tryLock` acquires the lock for key and lock type. It returns an opaque token, or null when another holder owns the lock. | `issue`, tryLock_held_key_returns_null, tryLock_held_key_unchanged, progress_tryLock_free_key_acquires, tryLock_sets_lease (Q1, Q10, Q17). |
| KeyLock.kt(core)#unLock | `unLock` releases only if the token matches the current owner. It returns false when the lock is not held or the token does not match. | unLock_true_only_for_owner, progress_unLock_owner_releases, unLock_failure_changes_nothing, unLock_ends_only_presented_token (Q6, Q7). |
| KeyLock.kt(reactor)#tryLock | Same contract as core, as a Mono: it emits a token, or completes empty when another holder owns the lock. | Same clauses as KeyLock.kt(core)#tryLock. The Mono wrapper is out of the model (Q16). |
| KeyLock.kt(reactor)#unLock | Same as core `unLock`. It emits false when not held or on mismatch. An expired lock already handed to another holder cannot be released by a stale owner. | Same clauses as KeyLock.kt(core)#unLock. The stale-owner case needs fresh tokens (skeleton ghost rule 1; Q1, Q13). |
| KeyLock.kt(coroutine)#tryLock | Same contract, suspend variant: a token, or null when another holder owns the lock. | Same clauses as KeyLock.kt(core)#tryLock (Q16). |
| KeyLock.kt(coroutine)#unLock | Releases only if the token matches the current owner. False when not held or on mismatch. | Same clauses as KeyLock.kt(core)#unLock (Q16). |
| KeyLock.kt(coroutine)#lockKeyOf | Lock keys are prefixed so a lock entry never collides with a cache entry. | Not encoded: out of the model (`Key` is opaque; key derivation is outside `KeyLocalLock`). |

## Clauses that have no sentence of the text as source

- `inv_held_token_is_carried`: a consistency fact. It restates the witness and ghost rule 2. The lemma needs it.
- `probe`: it shows that the `ensures` clauses are satisfiable. It starts with a cap refusal, then does acquire, a refused second acquire, release and a new acquire.

## Blocks that mention locks but state no behavior of the local lock (no row)

- Global lock: README.md#global-lock-guidance/1, /3, /4; CLAUDE.md#key-components/6; CLAUDE.md#reqshieldconfiguration-parameters/2.
- What a caller does after `tryLock` returns null: README.md#waiting-for-another-request-lock-not-acquired/1 to /3; CLAUDE.md#reqshieldconfiguration-parameters/6.
- Whether ReqShield calls the lock: CLAUDE.md#reqshieldconfiguration-parameters/1; CLAUDE.md#work-modes/2 and /3.
- Cache key layout (not lock keys): README.md#cache-key-layout/1 and /3.
- All other blocks (build, tests, versions, adapters, thread pools, eviction): no link to `KeyLocalLock`.

## Notes on the language (risks for the person who runs the tool)

1. No clause quantifies over keys. LANGUAGE.md documents quantifiers over `Int` only. State facts about entries are written as `ensures` on the step key `k`. Every step changes only key `k`, so these `ensures` give the same facts by induction.
2. `lem_one_backed_holder_per_key` uses two nested `forall` over `Int`. If the tool accepts only one bound variable per clause, rewrite it as the single-variable form `tok(x).gIssued && tok(x).gBacked && tok(x).gHeld ==> S.m[tok(x).gKey].token == x`.
3. `unLock_ends_only_presented_token` uses `res` inside `ends_only_if`. `old(...)` is used only inside `ensures`.
4. The probe has no `sweep` step. LANGUAGE.md does not show how to call an operation that has no result. The probe also cannot show the stale-owner case, because that needs the fact that two issued tokens differ. That fact comes from ghost rule 1 and is not an `ensures` clause.
5. The probe key is named `pk`, because `k` is a reserved name.
6. No clause mentions `permits` or `isHeld` (Q15).
