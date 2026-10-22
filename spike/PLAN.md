# Spike plan: KeyLocalLock in Viper

Target: `req-shield` `core-kotlin-coroutine/.../KeyLocalLock.kt` (checkout `91962c7`, same as `origin/main` for this file).
Toolchain: ViperTools `v.26.08-release` (Silicon in `viperserver.jar`, Z3 4.16.0), run with `silicon.sh`.

## Encoding (L1: module transition system)

- One module object `S` holds the shared state: `m: Map[Key, Ref]` (the lock map), `seq: Int` (token counter),
  and `created: Set[Ref]` (every `LockInfo` ever allocated).
- Ghost token records: one ghost object `tok(t)` per token value with fields `gIssued`, `gKey`, `gBacked`, `gHeld`,
  `gLease`, and quantified permissions over all `Int`. (The first plan used four `Map[Int, _]` fields;
  that version did not verify `tryLock` in 300 s. See `RESULTS.md`.)
- `LockInfo` is a `Ref` with fields `permits`, `isHeld`, `token` (`-1` = null, so a reused token `0` stays a valid token), `expiresAt`.
- `init()` builds the empty module and must establish `MI`. Without it, an unsatisfiable `MI` would "prove" everything.
- `probe()` runs `init → tryLock → tryLock → unLock → unLock` and asserts the expected results,
  so an `MI` or a precondition that makes the actions unreachable or inert is caught.
- Each `lockMap.compute(k) { ... }` body is one Viper method (one atomic action):
  `requires MI(S)`, body, `ensures MI(S)` plus the action's property postconditions.
  Interleavings are covered because every action preserves `MI` from any `MI` state,
  and every value read outside `compute` (the clock `now`, `mappingCount`, the cap) is a free parameter.
- Actions: `tryLock(k, now, rej, limit)`, `unLock(k, t)`, `sweep(k, now)`.
- `MI(S)`: permissions to all fields of `S` and of every `r in created`; map values are in `created`
  and injective; INV-E (every entry: `permits == 0`, `isHeld`, `token > 0`); every issued token `<= seq`;
  each entry's token is backed, held, keyed to its entry, and its ghost lease equals the entry's `expiresAt`;
  every held backed token is the token of its key's entry.

## Properties

| ID | Property | Encoded as |
|---|---|---|
| P1 | `unLock(k, t)` returns true only if `t` was the current owner token and held; afterwards `t` is not held | `unLock` postcondition over `old(...)` |
| P2 | every `LockInfo` ever created has `0 <= permits <= 1` | `MI` conjunct |
| P3a | a held backed token stops being held only by `unLock(k, t)` with that token, or by an expiry action whose clock reading is `> tLease(t)` | postcondition of each action over all tokens |
| P3b | an unbacked token is issued only when `k` has no entry and `rejectsNewEntry` returned true (so the cap is not 0) | `tryLock` postcondition |
| P3c | at most one held backed token per key | lemma-style assertion from `MI` |

Leases are clock-relative (user decision): the real-time form is not provable (`now` is read outside `compute`).
`Long` overflow of `now + lockTimeoutMillis` is excluded by a precondition and listed as an assumption.

## Mutants (E2) and non-vacuity (E4)

| ID | Change | Expected |
|---|---|---|
| SB1 | `unLock` without the token comparison | P1 fails |
| SB2 | TOCTOU `unLock`: token read in a separate atomic step, no re-check inside `compute` | P1 fails |
| SB3 | takeover without refreshing `expiresAt` | P3a / `MI` fails |
| SB4 | `nextToken` without increment | fresh-token fails |
| SB6 | non-expired entry: `if (true)` instead of `tryAcquire()` | P3c / P3a fails |
| SB5 (control) | expiry path without the `isHeld` CAS | must verify (equivalent under INV-E) |
| E4 | `tryLock` split into check and insert actions (no atomicity) | P3c fails |

## L3 case (added after review)

A simplified `ReqShield` client: `tryLock` produces a linear `Lease(k, t)` predicate instance; `unLock` consumes it.
Exceptional exits are explicit paths. Every path must end with no `Lease` left (explicit leak check).
Background write: `launch` transfers the `Lease` to a child method that must release it on every path.
`ReqShieldData` is shared with the child by a fractional permission, so a caller write must be rejected (F5).
Mutants: missing release in the supplier `catch` (leak), double release, caller write.

## E3 (LLM invariants)

Fresh-context subagents get a skeleton: code actions, ghost declarations and ghost updates, properties,
`init`, `probe`, and the permission conjuncts of `MI`.
Removed: only the inductive conjuncts of `MI` (one hole). The ghost updates stay fixed, because they define what
`tHeld`/`tLease` mean; letting the LLM write them would let it make the properties vacuous.
5 independent runs, budget 20 Silicon calls each.
A checker rejects any change outside the hole and any `assume`/`inhale`/`exhale`/`refute` inside it.
The subagents work in a directory without the baseline file. After each run, a grep of its transcript
counts mentions of the baseline path.

## Assumptions Silicon does not check in this encoding

1. Cross-key reduction: each `compute(k)` body is modeled as one atomic action over the whole module state.
   In the JVM, `compute(k1)` and `compute(k2)` run in parallel. The only state they share is
   `tokenSequence.incrementAndGet()`, which is itself atomic, so the reduction is sound by argument, not by Silicon.
2. Stale clock: `now` is read outside `compute` (`tryLock` at `KeyLocalLock.kt:150`, the sweep once per pass at
   `:90`). Modeling `now` as a free parameter of each action covers any staleness.
3. Mathematical integers: `now + lockTimeoutMillis` overflow and the `Long` range of the token counter are not modeled.
4. `"local-" + n` is injective, so distinct counter values give distinct token strings.
5. `rejectsNewEntry(size)` returns true only if the cap it read is not 0 (proved by reading its body, not by Silicon).
6. Logging, `mappingCount()` (an estimate) and the rejection counter do not affect the modeled state.
