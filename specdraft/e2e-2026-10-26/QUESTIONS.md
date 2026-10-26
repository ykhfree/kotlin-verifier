# Open points in the text, and the option DRAFT.kv encodes

Clause IDs refer to DRAFT.kv. `issue(tryLock)` is the issue rule of tryLock. "gLease" of a token is
`now + timeout` of the tryLock call that issued it (see Q3).

## Q1. May tryLock take over an expired entry that the monitor has not dropped yet?

- Quote: [KeyLock.kt(core)#KeyLock] "release it, so a holder whose lock already expired and was taken over by someone else"
- Also: [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed, which runs on a 1 s interval and so can lag by up to that much."
- Options:
  1. tryLock returns null while any entry for the key exists. Only the cleanup monitor (or unLock) removes an
     expired entry; "taken over" means: the monitor dropped it, then another tryLock acquired the key.
  2. tryLock may take over an expired entry (its lease has passed): it may end the old acquisition and issue a new
     backed token, or it may return null and leave the entry to the monitor.
  3. tryLock must take over an expired entry (it must return a new token when the lease has passed).
- DRAFT.kv encodes option 2: tl_ends_only_expired allows tryLock to end an acquisition only when
  `now >= gLease`; tl_null_when_owned forces null only while the lease has not passed; no progress clause forces a
  takeover.
- Confidence: low. The KeyLock text is shared with the global (Redis) lock, where expiry frees the key by itself.
  For the local lock, the README names only the cleanup monitor as the remover of expired entries. Option 1 is
  equally plausible. If the team picks option 1, replace tl_ends_only_expired with nothing (the default forbids any
  end in tryLock).

## Q2. Is the lease over at exactly `lockTimeoutMillis`, or only after it?

- Quote: [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed, which runs on a 1 s interval and so can lag by up to that much."
- Options:
  1. The lease is over when `now - acquiredAt >= lockTimeoutMillis` (`now >= gLease`).
  2. The lease is over only when `now - acquiredAt > lockTimeoutMillis` (`now > gLease`).
- DRAFT.kv allows both at the boundary point: sw_ends_only_expired and tl_ends_only_expired allow an end when
  `now >= gLease`; progress_sw_drops_expired forces the drop only when `now > gLease`. So at `now == gLease` the
  monitor may drop the entry or keep it.
- Confidence: medium that the boundary does not matter to the team (the monitor can lag by up to 1 s anyway). The
  team should still pick one, because an implementation could differ at the boundary.

## Q3. From which clock reading, and with whose timeout, does the lease run?

- Quote: [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed, which runs on a 1 s interval and so can lag by up to that much."
- Also: [README.md#local-lock-map-size/1] "locking, in a map shared by the whole JVM. A cache key that is both created and refreshed therefore uses up to two"
- Options:
  1. From the clock reading of the tryLock call that acquired the lock, with that acquiring instance's
     `lockTimeoutMillis` (`gLease = now + timeout`).
  2. From the moment the entry was first created (an older reading, if an entry can outlive one acquisition), or
     with one JVM-wide timeout shared by all instances (the map is shared, but each instance has its own
     `lockTimeoutMillis`).
- DRAFT.kv encodes option 1: issue(tryLock) sets `gLease = now + timeout`, used by tl_null_when_owned,
  tl_ends_only_expired, sw_ends_only_expired and progress_sw_drops_expired.
- Confidence: medium. The text says "has passed" without a start point. Option 1 is the natural reading for a
  per-acquisition lease. The draft does not assume that the clock moves forward: a sweep with an earlier `now` may
  not drop an entry.

## Q4. Is `lockTimeoutMillis` a lease length or a wait time for acquiring?

- Quote: [CLAUDE.md#reqshieldconfiguration-parameters/4] "`lockTimeoutMillis`: Lock acquisition timeout (default: 3000ms)"
- Options:
  1. A lease length: how long an acquired lock stays valid before the monitor may drop it.
  2. A wait time: how long tryLock may wait to acquire the lock before it gives up.
- DRAFT.kv encodes option 1 (issue(tryLock) gLease, sw_ends_only_expired, progress_sw_drops_expired). tryLock is
  one atomic step and never waits.
- Confidence: high for the local lock. README.md#local-lock-map-size/2 uses it as the expiry of an unreleased entry,
  and KeyLock tryLock returns null at once when the lock is owned. The CLAUDE.md wording "acquisition timeout" is the
  only conflicting text.

## Q5. What does unLock return for an unbacked permit (a token issued under the cap)?

- Quote: [KeyLock.kt(core)#unLock] "@return false when the lock is not held or the token does not match the current owner."
- Also: [README.md#local-lock-map-size/4] "Once the map is full, a request for a **new** key is handed a permit that no map entry backs. It then runs exactly as"
- Options:
  1. false, and the map does not change (no entry carries the token, so "the lock is not held").
  2. true (the caller did hold a permit and gives it back), and the map does not change.
- DRAFT.kv encodes option 1: ul_false_when_not_owner and ul_mismatch_keeps; probe pr_unbacked_unlock.
- Confidence: medium. "Runs exactly as if it had taken the lock" could suggest that its release also succeeds.
  If callers log or act on a false result, option 1 makes every capped request look like a failed release.

## Q6. Under the cap, must tryLock on a new key return a permit, and must that permit be unbacked?

- Quote: [README.md#local-lock-map-size/4] "Once the map is full, a request for a **new** key is handed a permit that no map entry backs. It then runs exactly as"
- Options:
  1. When `rej` is true and the key has no entry, tryLock must return a token, must not add an entry, and that token
     is not counted by mutual exclusion.
  2. tryLock may return null instead (the request then waits like a loser of the lock).
  3. tryLock may still add an entry (a backed token) when the cap refuses.
- DRAFT.kv encodes option 1: progress_tl_capped_new_key, tl_capped_no_entry, tl_unbacked_only_capped_new_key;
  lem_mutual_exclusion_backed covers backed tokens only; probe pr_cap_new_key.
- Confidence: high. The block says the request "is handed a permit" and that the cost is "request collapsing for
  that key, and nothing else".

## Q7. Does the cap answer matter for a key that has an entry, including an expired one?

- Quote: [README.md#local-lock-map-size/4] "the map are not subject to the cap at all."
- Options:
  1. When the key has an entry, tryLock behaves exactly as with `rej` false: null while the lease runs; after the
     lease, whatever Q1 decides. It never returns an unbacked token for such a key.
  2. An expired entry counts as "no entry", so a capped tryLock on it may return an unbacked token.
- DRAFT.kv encodes option 1: tl_unbacked_only_capped_new_key requires `!old(has(S, k))`; tl_null_when_owned does
  not read `rej`; probe pr_cap_existing_key.
- Confidence: high for an unexpired entry, medium for an expired one (the text does not mention expired entries
  here).

## Q8. Must every monitor step drop an expired entry, or may it skip one?

- Quote: [README.md#local-lock-map-size/2] "An entry is dropped as soon as the lock is released; an entry whose holder never released it is dropped by the"
- Options:
  1. Each monitor step on a key whose lease has passed must drop that key's entry.
  2. The monitor may drop it in some later pass (only "eventually").
- DRAFT.kv encodes option 1: progress_sw_drops_expired. The 1 s interval and the lag cannot be stated (liveness).
- Confidence: medium-high. "Is dropped by the cleanup monitor once `lockTimeoutMillis` has passed" reads as a rule
  for each pass. Option 2 cannot be stated in the language, so the only alternative is to omit the clause.

## Q9. May the map hold an entry that does not carry a live acquisition?

- Quote: [README.md#local-lock-map-size/1] "keeps one entry per `(cache key, lock type)` it is currently"
- Options:
  1. Every entry carries the token of a live, backed acquisition of that key (no idle, pre-created or released
     entries stay in the map after a step).
  2. An implementation may keep idle entries (for example a released entry with `isHeld == false` and no token),
     as long as the map size stays bounded by concurrency.
- DRAFT.kv encodes option 1: tl_entry_is_lock, ul_entry_is_lock, sw_entry_is_lock; also progress_ul_release
  (the entry is gone after a release).
- Confidence: medium-high. "As soon as the lock is released" and "tracks how many keys are being locked at the same
  time" support option 1.

## Q10. Is there any bound on the map size?

- Quote: [README.md#local-lock-map-size/5] "- The cap is a soft one. The size check is an estimate and is not atomic with the insertion it guards, so concurrent"
- Options:
  1. No bound in the specification: the cap is only an input (`rej`) that tryLock must respect for new keys.
  2. A bound such as "map size <= limit + number of concurrent callers".
- DRAFT.kv encodes option 1: no clause on the map size.
- Confidence: high. The block says the map can pass the configured number, and the language has no map-size term
  in its expression list.

## Q11. May the owner still release after its lease has passed but before the monitor dropped the entry?

- Quote: [README.md#global-lock-guidance/2] "holder whose lock already expired can no longer release the lock of the next holder."
- Options:
  1. Yes: while the entry still carries the token, unLock drops it and returns true.
  2. No: after the lease, unLock returns false and leaves the entry to the monitor.
- DRAFT.kv encodes option 1: progress_ul_release depends only on `carried(S, k, t)`. unLock has no clock input, so
  option 2 cannot be stated anyway.
- Confidence: medium-high. The text forbids only releasing "the lock of the next holder".

## Q12. Is the lock re-entrant for the same caller?

- Quote: [KeyLock.kt(core)#tryLock] "@return an opaque ownership token, or null when another holder owns the lock."
- Options:
  1. Not re-entrant: a second tryLock on a key whose lease runs returns null, even from the same caller.
  2. Re-entrant: the current holder gets a token again.
- DRAFT.kv encodes option 1: tl_null_when_owned; probe pr_contend. The model has no caller identity, and ghost
  rule 1 forbids handing out the same token twice.
- Confidence: high. A token is the only notion of ownership in the text, and request collapsing needs the second
  request to wait.
