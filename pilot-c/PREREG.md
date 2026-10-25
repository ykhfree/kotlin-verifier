# Pilot pre-registration (written 2026-10-25, before any draft exists)

Pilot of DESIGN-C: natural-language spec -> sidecar clauses, on req-shield KeyLocalLock (2e792d2).
Tool: frozen copy of translator/build/install/kv (translator.jar sha256 722e63e8...), run from scratch copies only.

## 1. Inputs of a drafter (packet/)

- SOURCES.md: README.md, CLAUDE.md and the KDoc of the three KeyLock.kt files, split into 128 anchored blocks
  (tools/snapshot.py). The aicode SPEC.md packet is a verbatim subset of README.md and CLAUDE.md, so it is covered.
- LANGUAGE.md: the language reference (model, operations, ghost rules, clause kinds, expressions, limits).
- SKELETON.kv: the binding part of keylocallock.common.kv with every intent clause removed (issue rule, ensures,
  allowance clauses, invariant, lemma, probe) and the hole replaced by `true`. Ghost field comments are neutral.
- TASK.md (phase 1). SCENARIOS.md is sent only after phase 1 (phase 2), so QUESTIONS.md is unprompted.
- No code, no signed sidecar, no tool. Drafters: three general-purpose agents, models haiku, sonnet, opus.

## 2. Decision catalog (the known decisions; scoring key for "surfaced")

| ID | Decision | Signed choice | Kind |
|---|---|---|---|
| D1 | Expiry boundary: at now == expiry, is the lock still held? (tryLock and sweep) | held (`now > lease` ends it) | ambiguity |
| D2 | unLock by the holder after expiry, before any takeover or sweep | true (progress_owner) | ambiguity |
| D3 | Cap path: a new key at the cap gets an unbacked token, not null | unbacked token (cap_unbacked) | coverage |
| D4 | tryLock on an expired entry takes it over at once (no wait for the monitor) | takeover (expired_takeover) | coverage |
| D5 | unLock of an unbacked token | false (P1 needs gBacked) | ambiguity |
| D6 | Unbacked tokens only at the cap and only with a cap set | P3b | coverage |
| D7 | Lease value: clock reading before the step + lockTimeoutMillis | issue gLease = now + timeout | ambiguity (small) |
| D8 | Soft cap: no invariant bounds the map size | no size clause | coverage (negative) |
| D9 | The monitor drops an expired entry when it looks at it | not encoded (no sweep progress clause) | coverage |
| D10 | Mutual exclusion holds only among backed tokens (the cap loses collapsing) | P3c over gBacked | coverage |
| D11 | Token freshness: a token is never handed out twice | issue set-once (tool) + P1a | coverage |
| D12 | Long overflow of now + lockTimeoutMillis | outside the model (assumption) | ambiguity, out of model |

A method "surfaces" Di when it gives the person an explicit item that names Di: a listed question, an `open`
answer, a disagreement between drafts, or a failed check that points at the clause that encodes Di.

## 3. Scenario cells and the signed answers (SCENARIOS.md)

C01 backed; C02 unbacked; C03 null; C04 null (D1); C05 backed token (D4); C06 false; C07 true; C08 true;
C09 true (D2; formally the same as C08: unLock has no clock); C10 false; C11 false; C12 false (D5);
C13 unbacked; C14 stays; C15 stays (D1); C16 dropped by the text, OPEN in the signed sidecar (D9); C17 false;
C18 backed; C19 backed token (D4 with the cap); C20 token.

## 4. Checks per draft

1. Parse and type-check (`kv verify` exit code 4 with a sidecar or Viper type error). A drafter gets back only these
   error lines, at most 2 rounds. Pilot `reviewed` notes (text "PILOT, not signed by the user") are added by the
   harness to issue and allowance clauses, because the tool rejects unreviewed ones (U7).
2. Consistency: `kv verify` on the reference coroutine KeyLocalLock with the signed hole spliced in.
3. Discrimination: the 11 hand-picked Kotlin mutants (translator/scripts/mutants.py) and the coroutine mechanical
   mutants of mutation/rerun/results.jsonl whose verdict under the signed sidecar was PROVEN or FAILED (stillborn,
   INCOMPLETE and support-module mutants are left out: no draft can change them), plus one new mutant
   SWEEP-NEVER (the monitor never drops). The signed sidecar is re-run on the same set as the baseline.
4. AI code: the 7 aicode round-2 implementations whose sweep the tool judged (T-haiku-2, T-haiku-4, T-sonnet-1,
   T-sonnet-3, T-sonnet-4, T-opus-1, T-opus-2), with their recorded binding adaptations.
5. Determinacy: each scenario cell as Viper probes against the draft's emitted contracts (action bodies removed):
   `assert O` / `assert !O` / `refute false` -> DETERMINED(O), DETERMINED(not O), OPEN, INFEASIBLE.
   Mode a: with the signed hole in MI. Mode b: MI_INV = true.

## 5. Predictions (before the drafts)

1. Each draft parses and type-checks after at most 2 fix rounds.
2. D1: at least 2 of 3 drafts do not encode the strict boundary (they encode `>=` or leave C04 undetermined).
3. D1: at least 2 of 3 QUESTIONS.md files list the expiry boundary.
4. D2: the drafts' C09 answers (ANSWERS.md) are not all the same, or at least one is `open`.
5. D3: at least 2 of 3 drafts encode the cap path as an obligation (C02 determined as unbacked).
6. P2 (permits <= 1) appears in 0 drafts: the text says nothing about semaphores.
7. D4: at least 2 of 3 drafts encode the expired takeover as an obligation (C05 determined).
8. D9: at least 2 of 3 drafts encode a monitor obligation that the signed sidecar lacks; then SWEEP-NEVER is
   PROVEN under the signed sidecar and FAILED under those drafts.
9. At least 1 of 3 drafts fails on the reference implementation, and each such failure traces to a catalog decision.
10. Under the signed sidecar (mode a), every cell is DETERMINED except C16 (OPEN).
11. Cross-draft disagreement surfaces at least one catalog decision that no drafter listed in QUESTIONS.md.
