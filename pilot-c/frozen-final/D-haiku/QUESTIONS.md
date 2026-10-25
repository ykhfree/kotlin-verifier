# Open questions for the KeyLocalLock specification

Each question lists the decision, the source quote, the options, the option that DRAFT.kv encodes,
and my confidence that the text means that option. A reviewer should decide each question.

| # | Decision | Confidence |
|---|---|---|
| Q1 | Is an expired entry free for tryLock at once? | medium |
| Q2 | What does lockTimeoutMillis measure? | medium |
| Q3 | Is an entry expired at the exact expiry instant? | low |
| Q4 | May the monitor drop a live entry? | high |
| Q5 | Must each monitor step drop an expired entry? | medium |
| Q6 | How far may the soft cap be exceeded? | high (no bound in the text) |
| Q7 | Does an unbacked permit exclude other requests for its key? | high |
| Q8 | What does unLock return for an unbacked token? | medium |
| Q9 | Does unLock release an expired lock that no one has taken over? | medium |
| Q10 | May an expired holder keep running beside the new owner? | medium |
| Q11 | Does an expired entry in the map skip the cap? | medium |
| Q12 | Which KeyLocalLock implementations does the spec bind? | low |
| Q13 | From which clock reading does the expiry count? | low |
| Q14 | Is a second tryLock by the same caller refused? | low |
| Q15 | Does an unbacked permit have a lease? | medium |
| Q16 | Does a refused tryLock renew the lease? | medium |

## Q1. Is an expired entry free for tryLock at once?

- Anchor and quote: [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed, which runs on a 1 s interval and so can lag by up to that much."
- Options:
  - (a) An entry whose expiry has passed is free. tryLock takes it over at once and returns a new token.
  - (b) An expired entry still blocks tryLock. tryLock returns null until the monitor drops the entry.
- Encoded: (a). Clauses: `progress_expired_takeover`, `tryLock_null_when_owned`, `tryLock_null_only_when_owned`, `tryLock_takeover`.
- Confidence: medium. The quote "a holder whose lock already expired and was taken over by someone else" ([KeyLock.kt(core)#KeyLock]) fits both options. The lag sentence talks only about the map size.

## Q2. What does lockTimeoutMillis measure?

- Anchor and quote: [CLAUDE.md#reqshieldconfiguration-parameters/4] "`lockTimeoutMillis`: Lock acquisition timeout (default: 3000ms)"
- Options:
  - (a) A lease. A granted lock expires lockTimeoutMillis after tryLock grants it.
  - (b) A wait limit. A caller waits up to lockTimeoutMillis for the lock. The model's tryLock never waits, so this option needs a wait that the language cannot state.
- Encoded: (a). Clauses: `grant_lease` (uses `timeout`) and `issue` (sets `gLease`).
- Confidence: medium. The README uses the value as the time after which an unreleased entry is dropped ([README.md#local-lock-map-size/2] "once `lockTimeoutMillis` has passed"). That reads as a lease. The name "Lock acquisition timeout" can also mean a wait.

## Q3. Is an entry expired at the exact expiry instant?

- Anchor and quote: [README.md#local-lock-map-size/2] "once `lockTimeoutMillis` has passed"
- Options:
  - (a) An entry is expired when its expiry is at or before now (`expiresAt <= now`).
  - (b) An entry is expired only when its expiry is before now (`expiresAt < now`).
- Encoded: (a). Clauses: `tryLock_null_when_owned` (an owner needs expiry after now), `progress_expired_takeover`, `progress_sweep_drops_expired`, `sweep_keeps_live`.
- Confidence: low. "has passed" does not say whether the exact instant counts.

## Q4. May the monitor drop a live entry?

- Anchor and quote: [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed"
- Options:
  - (a) The monitor drops only entries whose expiry has passed.
  - (b) The monitor may drop any entry, for example to limit memory.
- Encoded: (a). Clauses: `sweep_only_drops`, `sweep_keeps_live`.
- Confidence: high. Under option (b), a live holder could lose its entry, and a second request could run for the same key. The text rules that out: "ensuring only one request for the same cache key is processed at a time" ([CLAUDE.md#project-overview/1]).

## Q5. Must each monitor step drop an expired entry?

- Anchor and quote: [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed, which runs on a 1 s interval and so can lag by up to that much."
- Options:
  - (a) A monitor step at or after the expiry always drops the entry.
  - (b) A monitor step may skip an expired entry, for example when it samples keys.
- Encoded: (a). Clause: `progress_sweep_drops_expired`.
- Confidence: medium. "lag by up to that much" implies that a later pass drops the entry. The text does not say that every pass visits every key.

## Q6. How far may the soft cap be exceeded?

- Anchor and quote: [README.md#local-lock-map-size/5] "callers can push the map slightly past the configured number."
- Options:
  - (a) No bound. The model allows any overshoot.
  - (b) A numeric bound, for example the cap plus a fixed margin.
- Encoded: (a). No clause. `rej` stays an input.
- Confidence: high that the text sets no number. Whether the team wants a bound is open.

## Q7. Does an unbacked permit exclude other requests for its key?

- Anchor and quote: [README.md#local-lock-map-size/4] "so what the cap costs is request collapsing"
- Options:
  - (a) No. An unbacked permit does not exclude other requests for its key. Requests for that key may run together.
  - (b) Yes. Unbacked permits for one key exclude each other. The map has no entry for this state, so the team needs a second structure.
- Encoded: (a). Clauses: `ub_capped_new_key`, `capped_new_key_not_inserted`, `progress_new_key`. The lemma `backed_exclusive` covers backed tokens only.
- Confidence: high. The text says the cap costs "so what the cap costs is request collapsing", and it names no other cost ("and nothing else").

## Q8. What does unLock return for an unbacked token?

- Anchor and quote: [KeyLock.kt(core)#unLock] "@return false when the lock is not held or the token does not match the current owner."
- Options:
  - (a) false. No map entry carries the token.
  - (b) true. unLock accepts a token that the instance issued, including an unbacked one.
- Encoded: (a). Clause: `unLock_result`.
- Confidence: medium. The KDoc says "not held". The README says that the request runs "exactly as" if it had taken the lock ([README.md#local-lock-map-size/4] "It then runs exactly as").

## Q9. Does unLock release an expired lock that no one has taken over?

- Anchor and quote: [KeyLock.kt(core)#unLock] "Releases the lock for [key] + [lockType] only if [token] matches the current owner."
- Options:
  - (a) Yes. The release succeeds when the token matches, even after expiry and before a takeover.
  - (b) No. The release fails after expiry, even when the token matches.
- Encoded: (a). Clauses: `unLock_result`, `unLock_release`.
- Confidence: medium. The KDoc names only the token match and the takeover as failure cases. unLock has no clock input (LANGUAGE.md section 6), so option (b) needs a change to the operation's inputs.

## Q10. May an expired holder keep running beside the new owner?

- Anchor and quote: [KeyLock.kt(core)#KeyLock] "cannot release the new owner's lock."
- Options:
  - (a) Yes. The lock limits only the release. The draft has no clause that stops the old holder's work. The expired holder stays "held" in the ghost state until a takeover or a drop.
  - (b) No. The old holder must stop. The model has no supplier, so the spec cannot state this.
- Encoded: (a). Clauses: `unLock_refused_keeps_owner`, `backed_exclusive`.
- Confidence: medium. The text says the old holder cannot release the new owner's lock. It does not say that the old holder must stop.

## Q11. Does an expired entry in the map skip the cap?

- Anchor and quote: [README.md#local-lock-map-size/4] "Keys whose entry is already in"
- Options:
  - (a) Yes. An expired entry that is still in the map is taken over without the cap check.
  - (b) No. An expired entry counts as a new key, so a full map refuses it.
- Encoded: (a). Clause: `progress_expired_takeover`.
- Confidence: medium. The text says that keys whose entry is already in the map skip the cap. An expired entry that the monitor has not dropped is still in the map. This question depends on Q1. Under Q1 option (b), the entry blocks, and no takeover happens.

## Q12. Which KeyLocalLock implementations does the spec bind?

- Anchor and quote: [KeyLock.kt(coroutine)#tryLock] "Returns an opaque ownership token when acquired, or null when another holder owns the lock."
- Options:
  - (a) Only the core `KeyLocalLock`. The reactive and coroutine variants have no clauses (encoded).
  - (b) The core, reactive and coroutine local locks. Their empty or null result maps to `NULL`.
- Encoded: (a). The COVERAGE rows for the reactive and coroutine blocks say not encoded.
- Confidence: low. The text does not name the module of each `KeyLocalLock`. The TASK names only `KeyLocalLock`.

## Q13. From which clock reading does the expiry count?

- Anchor and quote: [README.md#local-lock-map-size/2] "once `lockTimeoutMillis` has passed"
- Options:
  - (a) The `now` input of tryLock, which is the clock reading taken before the atomic step (encoded).
  - (b) The moment the entry is stored.
- Encoded: (a). Clauses: `grant_lease`, `issue`.
- Confidence: low. The difference is at most the duration of one step. The text names no base time.

## Q14. Is a second tryLock by the same caller refused?

- Anchor and quote: [KeyLock.kt(core)#tryLock] "or null when another holder owns the lock."
- Options:
  - (a) Yes. A second tryLock on a live key returns null. The lock does not recognize callers (encoded).
  - (b) No. The same caller gets a token again (reentrant). The model has no caller identity, so this cannot be stated.
- Encoded: (a). Clause: `tryLock_null_when_owned`.
- Confidence: low. The text says "another holder". It does not say whether one caller can hold the lock twice.

## Q15. Does an unbacked permit have a lease?

- Anchor and quote: [README.md#local-lock-map-size/4] "It then runs exactly as"
- Options:
  - (a) No. The issue rule sets `gLease` for every token, but no clause reads it. Rule 2 never ends an unbacked token (encoded).
  - (b) Yes. An unbacked permit expires after lockTimeoutMillis. No map entry exists to drop, so the model needs a new rule.
- Encoded: (a). Clause: `issue` (sets `gLease`; no clause reads it).
- Confidence: medium. The text gives no lease for unbacked permits.

## Q16. Does a refused tryLock renew the lease?

- Anchor and quote: [README.md#local-lock-map-size/2] "once `lockTimeoutMillis` has passed"
- Options:
  - (a) No. A refused tryLock leaves the expiry unchanged (encoded).
  - (b) Yes. A refused tryLock sets the expiry to now plus lockTimeoutMillis.
- Encoded: (a). Clause: `tryLock_null_keeps_owner`.
- Confidence: medium. The text gives one expiry for each lock, counted from its acquisition. It mentions no renewal.

## Note: probe step removed

The probe no longer calls `sweep`. The team checker rejects a probe call to `sweep`, because the operation returns nothing (error[emit]). The probe therefore does not show the expiry drop. Its last assert changed from `assert !ok` to `assert ok`. This assert still follows from `unLock_result` and `tryLock_null_keeps_owner`. The drop is still stated by `progress_sweep_drops_expired` and `sweep_drops_expired`, but no probe checks it.
