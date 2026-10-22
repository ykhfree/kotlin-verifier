# Spike results: KeyLocalLock in Viper

Date: 2026-10-23. Toolchain: ViperTools `v.26.08-release` (Silicon 1.1-SNAPSHOT `6b540aa4`, Z3 4.16.0), Corretto JDK 21.0.10, macOS 26.6.2 on Apple Silicon (arm64, 12 cores).
Target: req-shield `core-kotlin-coroutine` `KeyLocalLock.kt` and `ReqShield.kt` at commit `91962c7`.

## Summary

| Criterion | Result | Evidence |
|---|---|---|
| E1 encoding works, ≤ 60 s per method | PASS (slowest method 1.84 s) | `keylocallock/baseline.vpr` |
| E2 real bugs rejected, controls accepted | PASS (5/5 rejected, 2/2 controls accepted) | `keylocallock/mutants.report` |
| E3 LLM writes the invariant (≥ 3 of 5 runs within 20 calls) | PASS (5/5; 4 runs in 1 call, 1 run in 2 calls) | `e3/runs/` |
| E4 non-vacuity: removing atomicity breaks the proof | PASS | mutant `E4_split_trylock` |
| L3 release obligations (added after review) | PASS (3/3 mutants rejected, 2/2 assumption toggles fail as expected) | `reqshield/l3_client.vpr`, `reqshield/mutants.report` |
| E5 effort (≤ 2 × estimate) | PASS, but not comparable (see below) | |

Not tested by this spike:

| Item | Status |
|---|---|
| Frontend (K2 FIR → verification IR → Viper, SnaKt reuse) | Not touched. All Viper code here is hand-written. The Phase 1 estimate has no empirical input yet. |
| Backend discrimination | The spike shows Viper is sufficient for both layers. It does not show that Dafny or Ivy would fail: the L1 proof uses nothing a transition-system backend lacks. Only the L3 case uses permission logic (abstract `Lease` predicates, fractional permissions). |
| Link from L3 `Lease` to the L1 ghost record | Not modeled. |
| Trusted models against the real JDK (Lincheck differential tests) | Not run. |

The runtime directories of E3 (`/private/tmp/claude-501/kv-e3`) were session-local; everything needed to re-check E3
is copied to `e3/`.

## E1: encoding and performance

Per-method times from one Silicon run (`TIMES=1 ./silicon.sh keylocallock/baseline.vpr`):
`init` 0.41 s, `lemma_P3c` 0.52 s, `sweep` 0.83 s, `unLock` 0.85 s, `tryLock` 1.21 s, `probe` 1.84 s. Total 3.96 s.

The encoding of ghost state decided the result:

| Ghost token records | `tryLock` |
|---|---|
| Four `Map[Int, _]` fields with equal domains (`baseline_v1_mapghost.vpr`) | did not finish in 300 s |
| One ghost object per token value, `tok(t)` with fields, quantified permissions over all `Int` | 1.21 s |

Bisection showed every postcondition conjunct verifies alone in under 30 s; only the full set did not finish.
The lock map itself stays `Map[Key, Ref]` with allocated `LockInfo` objects, which is what a translator would emit.
Lesson for the translator: emit ghost state as per-key or per-token ghost objects, not as `Map` values with set equalities.

A non-inductive invariant passed `init` and `probe`: the first version lacked `0 <= S.seq`, so a fresh token could equal
the null sentinel `-1`. Only the action proofs exposed it. Vacuity probes check satisfiability and reachability,
not inductiveness.

## E2 and E4: mutants

| Mutant | Expected | Result | Failing obligations |
|---|---|---|---|
| SB1 `unLock` without the token comparison | reject | rejected | P1 |
| SB2 TOCTOU `unLock` (token compared in an earlier atomic step) | reject | rejected | P1 |
| SB3 takeover without refreshing `expiresAt` | reject | rejected | entry ↔ ghost lease link |
| SB4 `nextToken` without increment | reject | rejected | entry ↔ token record link, ghost history, P1a (fresh token) |
| SB6 live entry acquired without a permit | reject | rejected | INV-E |
| SB5 `tryLock` expiry path without the `isHeld` CAS | accept | accepted | — |
| SB5b sweep expiry path without the `isHeld` CAS | accept | accepted | — |
| E4 `tryLock` check and insert in separate atomic steps | reject | rejected | token → entry link (old holder displaced), P3b |

SB5 and SB5b confirm the design report's hypothesis: under INV-E every map entry is held, so the CAS always succeeds
and is redundant. They are equivalent mutants, used as false-alarm controls.

## L3: release obligations in ReqShield

`Lease(k, t)` is an abstract predicate created by `tryLock` and consumed by `unLock`. Each method states per outcome
whether it returns the obligation or holds none (`[true, perm(Lease(k, t)) == none]`, because Viper permissions are affine).

| Mutant or assumption toggle | Result | Failing obligation |
|---|---|---|
| L3M1 `executeCallable` without release in `catch` | rejected | `executeCallable` leak check |
| L3M2 creation path releases in `finally` and in the background write | rejected | `createReqShieldData` precondition (no `Lease` left) |
| L3M3 caller writes the returned `ReqShieldData` (F5, data-race check demo) | rejected | write permission |
| F4 off: a `java.lang.Error` is not process-fatal | rejected | `executeCallable` leak check (release review #8) |
| F6 off: `dispatch()` may throw | rejected | `createReqShieldData` and `updateReqShieldData` leak checks |

L3M3 demonstrates the data-race-freedom check; the library's own code never writes those fields.
Not modeled: the link between `Lease(k, t)` and the L1 ghost record `tok(t).gHeld`.

## Assumptions added during the spike

In addition to the list in `PLAN.md`:

7. `init` allocates the ghost token records by `inhale` (one record per `Int`, none issued). This is ghost allocation,
   not program behavior. Because the inhaled state is trivially consistent, `init` is a weak vacuity guard for the
   ghost layer. The evidence against vacuity rests on `probe` (actions reachable and not inert) and on the rejected
   mutants (SB1 to SB6, E4).
8. The L3 model treats `unLock` as non-throwing and `tryLock` as non-throwing (F4b and F4c are not modeled).

## E3: LLM-written invariants

Read this result with its limits first:
1. The Kotlin source states the core invariant in a comment (`KeyLocalLock.kt:63`: "every entry in the map is held"),
   and run2 cited it. The skeleton also says "tokens issued by nextToken() are >= 1" next to `NULL`.
   Code without such comments is likely harder.
2. The LLM wrote only the inductive conjuncts. The ghost instrumentation (records, updates, properties, encoding choice)
   was written by the main session, and that was where the hard problems were: the map-based ghost encoding that did not
   finish, and the missing `0 <= seq` conjunct in the first baseline.
3. Five runs on one 234-line module are a small sample.

Setup: five fresh-context subagents (the session's model), each in its own directory with `TASK.md`, the skeleton
(`MI_INV` = `true`), `verify.sh` (hole check + Silicon, budget 20 calls), and a copy of `KeyLocalLock.kt`.
The ghost updates, properties, `init`, `probe`, and permission conjuncts were fixed.
Criterion: at least 3 of 5 runs verify within 20 calls without human help.

| Run | `verify.sh` calls | Invariant attempts | Result |
|---|---|---|---|
| run1 | 1 | 1 | verified |
| run2 | 2 | 1 (call 1 checked the unedited skeleton after a failed edit; `attempts/1.vpr` equals the pristine skeleton) | verified |
| run3 | 1 | 1 | verified |
| run4 | 1 | 1 | verified |
| run5 | 1 | 1 | verified |

Result: PASS, 5 of 5. Checks by the main session:
- Each final file passes the hole checker and verifies with Silicon when re-run outside the subagent.
- A tool-call audit of each transcript shows reads of only `TASK.md`, `skeleton.vpr`, `verify.sh` and `KeyLocalLock.kt`
  in the run's own directory, edits of `skeleton.vpr` only, and Silicon runs through `verify.sh` only.
- run1 and run2 stopped on an API rate limit (HTTP 429) and were resumed about seven hours later with their context intact.

All five runs found the same structure: the counter bound (`seq >= 0`, issued tokens in `1..seq`), an entry → token record
conjunct (INV-E plus the ghost link), and a token → entry conjunct. Four runs omitted the explicit injectivity conjunct,
because it follows from the entry → token `gKey` link; run5 kept it.

## E5: effort

The design report estimated 8 to 12 working days for a human engineer with Viper experience, plus 2 to 4 days for the
L3 case. This spike was done by an AI agent session: about 80 minutes of wall-clock time from tool download (23:29) to
the last E3 run, excluding the seven-hour rate-limit pause. The criterion "actual effort at most twice the estimate"
passes, but the numbers are not comparable: the estimate was for human engineers, and the spike reused a design report
that had already fixed the encoding approach and the properties.
For the roadmap, this measures one thing: hand-translating and proving a small module is not the bottleneck.
The translator, the trusted models, and the human review of specifications remain unmeasured.
