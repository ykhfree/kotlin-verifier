# Open questions in DRAFT.kv

Each question gives four things:
1. The text that leaves the point open (anchor and exact quote from SOURCES.md).
2. The options, as concrete behavior.
3. The option that DRAFT.kv encodes, and the clause IDs that encode it.
4. How sure I am that the text means that option.

Terms:
- A "live" entry has `now < expiresAt`. An "expired" entry has `now >= expiresAt` (see Q2).
- An "unbacked token" is the README's "permit that no map entry backs" (`gBacked == false`).
- "Probe step `x`" is the probe line that assigns `x`.

Number of questions: 16.

| Q | Topic | Encoded option | Confidence |
|---|---|---|---|
| Q1 | `tryLock` on an expired entry | A: take over | medium |
| Q2 | The instant of expiry | A: `now >= expiresAt` | medium |
| Q3 | Start of the lease, and later changes | A: `now + timeout`, never changed | high |
| Q4 | Meaning of `lockTimeoutMillis` | A: a lease, no waiting | high |
| Q5 | Non-positive timeout, clock readings out of order | A: no special case | medium |
| Q6 | Re-entrancy | A: not re-entrant | high |
| Q7 | Cap refusal for a new key | A: a token, no entry | high |
| Q8 | An expired entry and the cap | A: exempt from the cap | high |
| Q9 | Value of each unbacked token | A: a fresh token | high |
| Q10 | Mutual exclusion and unbacked tokens | A: backed tokens only | high |
| Q11 | `unLock` with the matching token | A: remove the entry, return true | high |
| Q12 | `unLock` with an unbacked token | A: return false | medium |
| Q13 | One monitor step on an expired entry | A: must drop it | medium |
| Q14 | Entry fields `permits` and `isHeld` | A: not constrained | medium |
| Q15 | Blocks that bind the local lock | A: all ownership texts | medium |
| Q16 | Scope of the probe | A: no `sweep` step | high |

## Q1. Does `tryLock` take over an expired entry that the monitor has not dropped yet?

Text that leaves it open:
- [KeyLock.kt(core)#tryLock] "@return an opaque ownership token, or null when another holder owns the lock."
- [KeyLock.kt(reactor)#tryLock] "when another holder currently owns the lock."
- [KeyLock.kt(core)#KeyLock] "release it, so a holder whose lock already expired and was taken over by someone else"
- [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed, which runs on a 1 s interval and so can lag by up to that much."

Options:
- A. Take over. If `now >= expiresAt`, `tryLock` puts a new token and `expiresAt = now + timeout` into the entry and
  returns the new token. The old acquisition ends.
- B. Wait for the monitor. `tryLock` returns NULL and does not change the expired entry. The key becomes free only
  when a monitor step drops the entry, which can be up to 1 s after the expiry.
- C. Clean up only. `tryLock` drops the expired entry and returns NULL. The next `tryLock` creates a new entry.
- D. Any of A, B or C.

Encoded: A, in `progress_tryLock_takeover`, `tryLock_ends_only_expired` and the live condition
(`now < old(S.m[k].expiresAt)`) of `tryLock_live_entry_returns_null`. Probe step `t3` shows it.
- To encode B: delete `progress_tryLock_takeover` and `tryLock_ends_only_expired`, change the condition of
  `tryLock_live_entry_returns_null` to `old(has(S, k))`, and rewrite probe steps `t3` and `u1`.
- To encode C: replace `progress_tryLock_takeover` with an ensures that gives `res == NULL && !has(S, k)` for an
  expired entry, keep `tryLock_ends_only_expired`, and rewrite probe steps `t3` and `u1`.
- To encode D: delete `progress_tryLock_takeover`, keep `tryLock_ends_only_expired`, and rewrite probe steps `t3`
  and `u1`.

Confidence: medium. The KDoc returns null only when another holder "owns" the lock (reactor: "currently owns"). A
holder whose lease has ended can be read as no longer an owner. The KDoc also names a lock that "already expired and
was taken over". Against A: the README names only the monitor as the way an unreleased entry leaves the map. A reader
can take the 1 s lag as time in which the key stays locked. Under A, the lag affects only the map size.

## Q2. At which instant does an entry expire?

Text that leaves it open:
- [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed"

Options:
- A. The entry is expired when `now >= expiresAt`. The lease covers the instants from the acquisition up to
  `now + timeout - 1`.
- B. The entry is expired only when `now > expiresAt`.
- C. Tolerant. An operation must treat the entry as expired when `now > expiresAt`, and may do so when
  `now == expiresAt`.

Encoded: A, with one boundary for `tryLock` and for the monitor. Clauses: `tryLock_live_entry_returns_null`,
`progress_tryLock_takeover`, `tryLock_ends_only_expired`, `progress_sweep_drops_expired`, `sweep_keeps_live_entry`,
`sweep_ends_only_expired`. Probe step `t2` (`now = a + d - 1`) sees a live entry, and probe step `t3`
(`now = a + d`) sees an expired entry.

Confidence: medium. The phrase "once `lockTimeoutMillis` has passed" reads as: the elapsed time is at least
`lockTimeoutMillis`. A field named `expiresAt` reads as: the entry is expired from that instant on. But the text
names no comparison. Under A, an implementation that compares with `>` fails at exactly one millisecond.

## Q3. When does the lease start, and can an operation change it later?

Text that leaves it open:
- [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed"

Options:
- A. The lease ends at `now + timeout`, where `now` is the clock reading that `tryLock` takes before its step. No
  operation changes `expiresAt` later: a failed `tryLock` does not extend it, and there is no renewal.
- B. A failed `tryLock` on a live entry extends or refreshes the lease.
- C. The lease starts at another instant, for example the first creation of the entry, and a takeover keeps it.

Encoded: A. Clauses: the `issue` rule (`gLease = now + timeout`), `progress_tryLock_free_key`,
`progress_tryLock_takeover`, `tryLock_live_entry_returns_null`, `unLock_non_owner_keeps_map`, `sweep_keeps_live_entry`,
`inv_held_backed_token_carried_with_lease`.
The `issue` rule also gives an unbacked token `gLease = now + timeout`. This value has no effect: rule 2 never ends
an unbacked token, and no clause reads its lease.

Confidence: high. The README makes the monitor drop an unreleased entry once `lockTimeoutMillis` has passed, and the
plain start of that time is the acquisition. No block mentions a renewal.

## Q4. Is `lockTimeoutMillis` a lease or a wait time?

Text that leaves it open:
- [CLAUDE.md#reqshieldconfiguration-parameters/4] "`lockTimeoutMillis`: Lock acquisition timeout (default: 3000ms)"

Options:
- A. A lease: the time that an acquired lock stays valid. `tryLock` never waits; it answers in one step.
- B. A wait time: `tryLock` blocks for up to `lockTimeoutMillis` until the lock is free.

Encoded: A. Clauses: the `issue` rule (`gLease`), `progress_tryLock_free_key`, `progress_tryLock_takeover`, and the
expiry conditions of `tryLock` and `sweep`. The language cannot state B, because each operation is one atomic step.

Confidence: high. The README uses `lockTimeoutMillis` as the time after which the monitor drops an unreleased entry.
All three `tryLock` KDoc texts return null (or complete empty) when another holder owns the lock, and none of them
mentions a wait.

## Q5. What happens with a non-positive timeout, or with clock readings that go back?

Text that leaves it open:
- [CLAUDE.md#reqshieldconfiguration-parameters/4] "`lockTimeoutMillis`: Lock acquisition timeout (default: 3000ms)"

Options:
- A. No special case. The lease ends at `now + timeout` for every value. If `timeout <= 0`, the new entry is expired
  at once: the next `tryLock` takes it over, and the next monitor step drops it. Each step compares only its own
  `now` with `expiresAt`, so a step with an earlier clock reading sees a later entry as live.
- B. A non-positive timeout is replaced, for example by the default 3000 ms.
- C. A non-positive timeout means that the entry never expires.

Encoded: A. No clause assumes `timeout > 0` or a clock that moves forward. Clauses: the `issue` rule and all expiry
conditions. The probe has `requires d > 0` only to make its scenario realistic; its steps do not need it.

Confidence: medium. The text gives only the default value. Validation of the value is configuration, which is
outside the model. A is the plain reading of "once `lockTimeoutMillis` has passed" for every value, but under A a
timeout of 0 removes all mutual exclusion.

## Q6. Is the local lock re-entrant?

Text that leaves it open:
- [KeyLock.kt(core)#tryLock] "@return an opaque ownership token, or null when another holder owns the lock."

Options:
- A. Not re-entrant. While the entry is live, `tryLock` returns NULL to every caller, also to the current holder.
- B. Re-entrant. The current holder gets its token again, or a second token.

Encoded: A, in `tryLock_live_entry_returns_null`. Probe step `t2` shows it.

Confidence: high. The word "another" can suggest B. But `tryLock` gets no caller identity (the model has no such
input), and a token names an acquisition, not a caller.

## Q7. What does `tryLock` do for a new key when the cap refuses it?

Text that leaves it open:
- [README.md#local-lock-map-size/4] "Once the map is full, a request for a **new** key is handed a permit that no map entry backs."

Options:
- A. Return a token (not NULL), and add no entry.
- B. Return NULL, so that the caller polls like a request that lost the lock.
- C. Add the entry anyway, or add an entry that carries no token.

Encoded: A. Clauses: `progress_tryLock_cap_refusal`, `tryLock_unbacked_only_for_refused_new_key`,
`inv_unbacked_token_never_carried`. Probe step `t4` shows it.

Confidence: high. The block says that the request "is handed a permit" and then runs exactly as if it had taken the
lock. The cap exists to bound the map, so the refused key must not add an entry.

## Q8. Does an expired entry make its key "new" for the cap?

Text that leaves it open:
- [README.md#local-lock-map-size/4] "Keys whose entry is already in"
- [README.md#local-lock-map-size/4] "the map are not subject to the cap at all."

Options:
- A. No. Any entry in the map, live or expired, exempts its key from the cap. With an expired entry, `tryLock` takes
  over (Q1) also when `rej` is true, and the map does not grow.
- B. Yes. An expired entry counts as no entry. If `rej` is true, `tryLock` returns an unbacked token, and the expired
  entry stays until the monitor drops it.

Encoded: A. Clauses: `tryLock_unbacked_only_for_refused_new_key` (its condition is `!old(has(S, k))`), and
`progress_tryLock_takeover` and `tryLock_live_entry_returns_null`, which do not read `rej`. Probe steps `t2` and `t3`
use `rej = true`.

Confidence: high. An expired entry is still in the map until a step removes it.

## Q9. Does each unbacked token have its own value?

Text that leaves it open:
- [README.md#local-lock-map-size/4] "Once the map is full, a request for a **new** key is handed a permit that no map entry backs."
- [README.md#global-lock-guidance/2] "Every lock acquisition carries an ownership token."

Options:
- A. Each unbacked token is a fresh token that `tryLock` never returned before.
- B. All refused requests get one shared constant token.

Encoded: A. The tool's issue rule checks that no token is handed out twice, so the language allows only A. Clauses:
the `issue` rule, `progress_tryLock_cap_refusal`.

Confidence: high. The text does not ask for B.

## Q10. Does mutual exclusion cover unbacked tokens?

Text that leaves it open:
- [CLAUDE.md#project-overview/1] "ensuring only one request for the same cache key is processed at a time"
- [README.md#local-lock-map-size/4] "if it had taken the lock - it calls the supplier and writes the cache - so what the cap costs is request collapsing"

Options:
- A. Only backed acquisitions exclude each other. An unbacked token can run beside other unbacked tokens, and beside
  a backed holder of the same key.
- B. No two acquisitions of one key, backed or unbacked, are held at the same time.

Encoded: A, in `lemma_one_backed_holder_per_key` (backed tokens only).

Confidence: high. The README says that the cap costs request collapsing. B cannot hold, because an unbacked token
leaves no entry that a later caller can see.

## Q11. What does `unLock` do when the presented token matches the entry?

Text that leaves it open:
- [README.md#local-lock-map-size/2] "An entry is dropped as soon as the lock is released"
- [KeyLock.kt(core)#unLock] "@return false when the lock is not held or the token does not match the current owner."

Options:
- A. Remove the entry in the same step, and return true. This also applies when the entry has expired, but nobody
  took it over and the monitor did not drop it yet.
- B. Keep the entry and only mark it as free (for example token NULL or `isHeld` false). The monitor removes it later.
- C. When the entry has expired, return false (and keep or drop the entry).

Encoded: A, in `progress_unLock_owner_releases` and `unLock_ends_only_presented_token`.

Confidence: high. The README's "dropped as soon as the lock is released" excludes B. The KDoc texts protect only the next holder
("taken over by someone else", "already handed to another holder"), so C is not required. Also, `unLock` has no
clock input in the model. The text gives only the cases that return false; the draft returns true in all other cases.

## Q12. What does `unLock` return for an unbacked token?

Text that leaves it open:
- [KeyLock.kt(core)#unLock] "@return false when the lock is not held or the token does not match the current owner."
- [README.md#local-lock-map-size/4] "if it had taken the lock - it calls the supplier and writes the cache - so what the cap costs is request collapsing"

Options:
- A. False. No entry carries the token, so the lock is not held with it. The map does not change.
- B. True, as for a normal release. The map does not change.

Encoded: A, in `unLock_non_owner_returns_false` and `unLock_non_owner_keeps_map`. Probe step `u4` shows it.

Confidence: medium. The KDoc rule gives false. But a reader can take "runs exactly as if it had taken the lock" to
include a successful `unLock`. B also needs the implementation to tell an unbacked token from a stale backed token.
The map records neither, so only the token value itself could carry that difference.

## Q13. Must one monitor step drop an expired entry?

Text that leaves it open:
- [README.md#local-lock-map-size/2] "an entry whose holder never released it is dropped by the"
- [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed, which runs on a 1 s interval and so can lag by up to that much."

Options:
- A. A monitor step on an expired entry must drop it.
- B. A monitor step may keep an expired entry and drop it in a later pass.

Encoded: A, in `progress_sweep_drops_expired`. The draft also forbids a monitor step to drop or change a live entry
(`sweep_keeps_live_entry`, `sweep_ends_only_expired`) and to add an entry (`sweep_adds_no_entry`). Those parts follow
from "only the holder that acquired the lock can release it" and are not in doubt.

Confidence: medium. The README puts the lag on the 1 s interval, not on skipped entries, and B would make the lag
unbounded. But the text describes the monitor as a whole, and the language cannot state the interval or the lag. So
the text does not say directly what one step on one key must do.

## Q14. Should the clauses constrain the entry fields `permits` and `isHeld`?

Text that leaves it open:
- [README.md#local-lock-map-size/1] "keeps one entry per `(cache key, lock type)` it is currently"

Options:
- A. Leave them free. The clauses specify only `has`, `token` and `expiresAt`.
- B. Tie them to the lock state, for example `has(S, k) ==> S.m[k].isHeld && S.m[k].permits == 0` in each operation.

Encoded: A. No clause reads `permits` or `isHeld`.

Confidence: medium. The text describes entries, owners and tokens, and never mentions a semaphore or a held flag. The
skeleton binds the two fields, so the team may want them fixed. An entry exists only while its key is locked, so B
adds no behavior that a caller can see.

## Q15. Which blocks bind the local lock?

Text that leaves it open:
- [README.md#global-lock-guidance/2] "Every lock acquisition carries an ownership token." (This block is in the section "Global lock guidance".)
- [KeyLock.kt(reactor)#tryLock] "when another holder currently owns the lock."

Options:
- A. The ownership sentences of the global-lock section, and the KDoc of the reactor and coroutine `KeyLock`, also
  bind this specification. The model maps "completes EMPTY" to NULL.
- B. Only the core KDoc and the section "Local lock map size" bind it.

Encoded: A. DRAFT.kv cites these blocks as sources, for example of `unLock_ends_only_presented_token`,
`unLock_non_owner_returns_false`, `progress_tryLock_takeover` and `tryLock_ends_only_expired`.

Confidence: medium. Under B the clauses stay the same, because the core KDoc says the same things. Only the
citations change, and Q1 loses the word "currently".

## Q16. Which scenario does the probe cover?

Text that leaves it open: no block of SOURCES.md. LANGUAGE.md shows probe calls only in the form
`x := OP(KEY, NAME = VALUE, ...)`, and `sweep` has no result.

Options:
- A. The probe calls only `tryLock` and `unLock`.
- B. The probe also calls `sweep`: for example, after a new entry expires, a `sweep` step with a `now` at or past its
  `expiresAt`, followed by `assert !has(S, k1)`.

Encoded: A, in the probe. As a result, no probe step shows that `progress_sweep_drops_expired` can be met.

Confidence: high that the choice does not change the meaning of the text. This is a question about the probe syntax
for an operation without a result. If the language accepts such a call, B is better.
