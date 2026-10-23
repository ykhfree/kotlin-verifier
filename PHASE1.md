# Phase 1: translator v0 for the KeyLocalLock subset

Date: 2026-10-23. Status: v0 built (translator/). Done criteria 1-5 are met; evidence in DESIGN-v0.md 8.3 "v0 검증".

## Decision: own frontend, not a SnaKt fork

Evidence (both measured on req-shield `KeyLocalLock`, branch `feature/NOISSUE/upgrade-kotlin2-and-deps`):

| | SnaKt `93d55208` (Kotlin 2.3.0) | Own probe (`frontend-probe/`, Kotlin 2.4.21) |
|---|---|---|
| KeyLocalLock | 0 of 14 functions verified; 9 compiler crashes, 4 unsupported diagnostics | every fact `baseline.vpr` needs is in the FIR dump, 0 unresolved nodes |
| Soundness | `finally` dropped (reproduced: wrong postcondition accepted, control rejected), catch entry only before/after try, unbounded Int, Silicon abort reported as success | — |
| Encoding | `var` fields havoc on read, writes dropped; per-function programs; no quantified permissions, domains, ghost state or `perm()` | free to use the spike encoding |
| Kotlin 2.4.21 | 118 compile errors when built against 2.4.21 (port estimate 3.5-7.5 person-days) | built for 2.4.21 |

SnaKt's `viper` module (Viper AST, no compiler-API dependency) is the only part worth reusing; v0 emits Viper text
instead, as the spike harness already runs Silicon from the CLI. Source: `scratchpad/snakt-eval.md`, `frontend-probe/`.

This reverses the design report (2026-10-22), which recommended forking SnaKt for sequential translation and kept an
own FIR frontend as the exit. The own frontend is now the primary path. New exits, both with the same caveat that the
compiler APIs are unstable across Kotlin releases: (1) a Kotlin IR frontend, (2) the Analysis API standalone mode.
`frontend-probe/` is the seed of the real frontend: the visitor will build KVIR objects directly, not parse the dump.

## Decisions for v0

| Topic | Decision |
|---|---|
| `Long` arithmetic | Mathematical integers, as in the spike. The tool emits one diagnostic per arithmetic operation it treats as unbounded, so the report shows where the assumption applies. |
| Token strings `"local-$n"` | Trusted-model rule for strings built from an `AtomicLong` counter: the token is abstracted to `n`, with an injectivity axiom. Not a user specification. |
| `support` functions (`nowToEpochTime`, `rejectsNewEntry`) | Contracts in the trusted-model table, not translated bodies. The `rejectsNewEntry` contract was checked by reading its body. |

## Trusted models v0 encodes (not checked by Silicon)

1. A `ConcurrentHashMap.compute` lambda is one atomic action over the module state. Its result is a put (non-null)
   or a remove (null).
2. The lambda runs exactly once per `compute` call. The compiler gives no invocation kind for it, so this comes from
   the model, not from inference.
3. Cross-key reduction: actions on different keys share only the atomic token counter (see spike `PLAN.md`).

## Who writes what

| Part | Author |
|---|---|
| Module declaration (which fields are the shared state), trusted-model table, properties, ghost schema | human, sidecar spec |
| Atomic-section detection, free parameters (values read outside a section), Viper skeleton, permission conjuncts | tool |
| Inductive invariant (the `MI_INV` hole) | LLM, checked by the tool (as in spike E3) |
| Ghost updates bound to code points (token issued, acquisition ended) | open: decided by the KVIR design step |

## v0 goal

From the unmodified Kotlin source of `KeyLocalLock` (coroutine and core variants) plus a sidecar spec, the tool
generates the module-transition-system Viper model, runs Silicon, and reports per action: proven / unsupported /
failed, with Kotlin positions. Done when:
1. P1-P3c are proven automatically for both variants (the generated model may differ from `baseline.vpr` in form).
2. The spike mutants applied to the Kotlin source (SB1-SB4, SB6, E4) are reported as failed, and the CAS-removal
   controls (SB5, SB5b) as proven.
3. An unsupported construct is reported as unsupported, never silently dropped.
4. The same translator handles the core `KeyLocalLock` variant; the two inputs differ only in their sidecar specs.
5. A script applies the mutants to the `.kt` source, runs the tool, and asserts the verdicts.

## Orchestration

1. Design step (deep-reasoner): KVIR, the sidecar spec format, and the ghost-update binding, scoped to "reproduce
   `baseline.vpr` from `KeyLocalLock.kt`". Inputs: the two probe reports and the spike encoding.
2. Implementation (feature-developer, after the design is fixed): frontend lowering, emitter, Silicon runner, report,
   tests, mutant script.

## Pipeline

1. Frontend (K2 FIR checker plugin, Kotlin 2.4.21): builds KVIR objects directly in the visitor.
2. KVIR: module state, atomic actions (`ConcurrentHashMap.compute` lambdas), statements, expressions, source positions.
3. Trusted models: `ConcurrentHashMap.compute`, `Semaphore`, `AtomicBoolean`/`AtomicLong`/`AtomicReference`,
   clock reads, `LocalLockLimit.rejectsNewEntry`.
4. Spec: sidecar file with ghost state, ghost updates bound to code points, properties, and the module invariant.
5. Viper emitter (slot-encoded ghost records, per RESULTS.md) and Silicon runner; error positions mapped back to Kotlin.
