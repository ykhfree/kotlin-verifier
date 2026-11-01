# Design E5: the L3 client protocol for the coroutine variant of req-shield `ReqShield`

Date: 2026-10-29. Status: design draft (no repository change). Scope: DESIGN-E.md 6.10 row E5 and 6.11 point 2.
Target: req-shield `core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine/ReqShield.kt`
(253 lines) in the pinned worktree `~/IdeaProjects/req-shield-verifier-pin` at `2e792d2` (read only).
Labels as in DESIGN-E: "measured" = run in this work; "estimate" = not run; "judgment" = argument, not run.
Environment: Silicon v.26.08-release, Z3 4.16.0 (`spike/silicon.sh`), installed `translator/build/install/kv`
(translator.jar of 2026-10-29 15:49), kotlinx-coroutines-core-jvm 1.8.1 sources jar from the Gradle cache.
All files of this work are in the scratch directory `e5/` (paths below are relative to it); nothing in the repository
was changed.

## 0. Summary

| Item | Result | Section |
|---|---|---|
| Semantics | One new exception kind CANCEL. A cancellation point (suspend trusted call, `ensureActive`, `delay`) raises CANCEL only in a cancellable context; `withContext(NonCancellable) { }` is inlined with no cancellation point inside and none on entry or exit; `launch(start = ATOMIC) { }` is a spawn whose block always starts (A3, A6); a cancelled `unLock` may not have released (A4 does not cover a cancellation) | 2 |
| New user decisions | UE7: the RET clauses do not apply to a call that ends with a cancellation (recommended (a)). UE8: a new clause RET_cancel, "a raised cancellation is the call's exception" (recommended (a)) | 3 |
| Hand model | `target-coroutine.vpr` (693 lines), written in the layout of the tool's core output. Silicon 2.54-2.61 s | 4 |
| Original code | **FAILED** under the signed assumptions plus A6: RET_hit, RET_recheck, RET_supplier_error (the core's three, same root cause) and RET_cancel (same root cause). All LD_*, MODE, SUP_once, POLL_bound, TERM, HOLE PROVEN (measured) | 4.2 |
| Fix | `fixed-coroutine.patch` (+36/-7): the core fix ported, with the cancellation rethrow kept. Model PROVEN; compiles with K2; the module's 57 tests pass on a patched copy (all measured). Not applied; it needs a user decision (UE9) | 4.3, 4.7 |
| Mutants | 79 files on two bases (measured): 11 ported core mutants and 11 coroutine mutants DETECTED (54 rows; K8 on the canonical base is masked by the original's own RET_cancel failure); 6 controls ACCEPTED on each base; 29 reachability probes per base all reachable | 4.4 |
| UE6 | A6 off: the fixed model FAILS LD_noleak, RET_hit, RET_supplier; so A6 is what keeps the fixed code PROVEN (the original fails anyway, for the UE3 reasons). A_F4 reproduced: with only a supplier Error possible, LD_noleak FAILS (A1 covers it) | 4.4 |
| Current tool | `kv discover --client`: 0 candidates. `kv resolve`: 2 errors (`SuspendFunction1`/`3` "not a Kotlin function type"). `kv lower`: **StackOverflowError** at `e is CancellationException` (250); without that line, 12 UNSUPPORTED sites of 9 construct kinds (measured) | 6.1 |
| Sidecar | A separate file `specs/reqshield/reqshield.coroutine.kv`, no `include` (judgment: < 15 lines would be shared, and the signed core file would move). New snapshot `specdraft/reqshield-coroutine/snapshot` (130 blocks; the 120 README/CLAUDE blocks equal the core's). Draft: `reqshield.coroutine.kv.draft` | 5 |
| Signing | 26 notes: 13 intent clauses, 5 assumptions (A1, A3, A4, A5, new A6), 6 trusted declarations, 1 vocabulary, 1 scope. One user round with UE7, UE8, UE9 | 5.4 |
| Tool steps | S0-S10 (6). Estimate 7-9.5 person-days for a FIR+Viper engineer (DESIGN-E 6.10 said 3-5; the measured construct list is longer) | 6 |
| Regression guard | Every new emitter text is gated on "the module uses CANCEL or a coroutine model", so the core Viper, the core KVIR golden and the 6 L1 modules stay byte-identical; mutants 33/30/42 with 0 mismatches | 7 |

## 1. Inputs and fixed decisions

1. User decisions (do not reopen): UE1 per-call discipline; UE2 (a) A1; UE3 (b) RET_* required; UE4 (a) A3; UE5 the
   fallback writes no cache; UE6 (a) (2026-10-29, `DESIGN-E.md` 2.3) the dispatcher's dispatch does not throw, so
   `scope.launch(start = CoroutineStart.ATOMIC)` does not throw and its block runs. UE6 becomes the named assumption A6.
2. The coroutine `ReqShield.kt` is the same at `2e792d2` and at the req-shield work branch head `1739f01`
   (measured: `git diff --stat 2e792d2 HEAD` lists only the core `ReqShield.kt`). The core fix of DESIGN-E 5.6 was not
   ported to the coroutine variant.
3. kotlinx.coroutines version: 1.8.1 (`libs.versions.toml:7`, measured), sources jar extracted to `src/kx181/`.

## 2. Semantics: coroutine constructs in the client model

Kotlin lines are the coroutine `ReqShield.kt` at `2e792d2`; kotlinx paths are in the 1.8.1 sources jar.

| # | Construct (lines) | Model | Source |
|---|---|---|---|
| 1 | `catch (e: CancellationException)` (83, 141, 170, 204, 220) | takes only CANCEL | `jvmMain/Exceptions.kt:11` `typealias CancellationException = java.util.concurrent.CancellationException`; JDK (Corretto 25.0.2 `src.zip`) `CancellationException.java:46` `extends IllegalStateException`, `IllegalStateException.java:37` `extends RuntimeException` |
| 2 | `catch (e: Exception)` (85, 143, 206, 222, 243) | takes CANCEL too (and the module's kinds, REE, RTE, CHK, INTR); not ERR (A1) | as 1 |
| 3 | `catch (e: ClientException)` (172) | the kinds the module throws of that class (CE_GET, CE_SET, CE_SUP); not CANCEL | `ClientException` is the module's own thrown class (DESIGN-E 3.5) |
| 4 | `if (e is CancellationException) throw e` (250) | a test of the caught kind: new KVIR expression `CaughtIs` | — |
| 5 | suspend call of a trusted function, value or parameter: `tryLock` (71, 99), `unLock` (112, 228, 247), `getFunction(key)` (203), `setFunction(...)` (219), `callable()` (242) | a cancellation point: outside NonCancellable it may raise CANCEL; a raised CANCEL has no result and creates no obligation | `CoroutineStart.kt:31-32` "Cancellability of a coroutine at suspension points depends on the particular implementation details of suspending functions" (so: may, not must) |
| 6 | `currentCoroutineContext().ensureActive()` (218, 241) | a cancellation point (CANCEL iff the job is cancelled; modeled as may) | `Job.kt:575-577` `if (!isActive) throw getCancellationException()`, `593-595` |
| 7 | `delay(GET_CACHE_INTERVAL_MILLIS)` (181) | a cancellation point | `Delay.kt:109-112` "This suspending function is cancellable ... prompt cancellation guarantee" |
| 8 | `withContext(NonCancellable) { unLock(...) }` (111, 227, 246) | inlined; every point inside has cx = false; no CANCEL on entry or exit; its value is the block's | `Builders.common.kt:134-136` "when using `withContext(NonCancellable) { ... }` there is no change in dispatcher and this call will not be cancelled neither on entry to the block inside `withContext` nor on exit from it"; `151` checks the new context, whose job is always active (`NonCancellable.kt:42-43`) |
| 9 | `reqShieldConfig.scope.launch(start = CoroutineStart.ATOMIC) { ... }` (75, 138) | a spawn: the block is a task; with ATOMIC the block starts even in a cancelled scope (A3: an accepted block runs); A6: launch does not throw | `CoroutineStart.kt:48-49` "the coroutine cannot be cancelled before it starts executing"; `89` `ATOMIC -> block.startCoroutine(...)`; `DispatchedContinuation.kt:190-196` dispatches without a catch |
| 10 | `launch { }` without `start = ATOMIC` (mutant K4) | a spawn whose block a cancelled scope may drop before it starts (`dropped` without an assumption) | `CoroutineStart.kt:28-29` (DEFAULT) "it will not start its execution at all" |
| 11 | `.also { consecutiveFailures = 0 }` (169) | an inline scope call like `let` (the lambda in place, value = receiver) | stdlib `also` (EXACTLY_ONCE) |
| 12 | `suspend (String) -> ReqShieldData<T>?` property, `suspend () -> T?` parameter | `trusted value` over `kotlin.coroutines.SuspendFunctionN` (measured class id); new `trusted param` for the supplier parameter | measured: `kv resolve` names `kotlin.coroutines.SuspendFunction1` |

### 2.1 Exception kind CANCEL

1. Kind order in Viper: the module's kinds, REE, RTE, CHK, INTR, CANCEL, ERR. `IS_EXCEPTION(e)` ends at the kind
   before ERR. Without CANCEL the order and the text are those of today (core byte-identical).
2. Catch mapping (`catchKinds`): `java/util/concurrent/CancellationException` -> [CANCEL]; `Throwable`, `Exception`,
   `RuntimeException` add CANCEL, but only in a module that uses CANCEL (else the core KVIR golden would change).
3. `catch (e: C)` for a class C the module throws (ClientException): its `Thrown` kinds. Soundness: a user function's
   RTE might itself be a ClientException; so the tool rejects such a catch (UNSUPPORTED) when its try body raises a
   library RTE or CHK that no inner catch converts. In this code the inner `executeGetCacheFunction` converts them, so
   only CE_GET and CANCEL reach line 172 (judgment, read the code).

### 2.2 Cancellation points and the cx parameter

1. Every model with CANCEL in its kinds gets a last parameter `cx: Bool` and the disjunct `(cx && exc == CANCEL)`.
   The lowering passes `true` outside `withContext(NonCancellable)` and `false` inside it. The tasks are cancellable
   contexts too (the launched job can be cancelled).
2. CANCEL is independent of A2: a cancelled lock call is no lock store failure.
3. Not sticky (judgment): each point decides alone. The real job state is sticky: once cancelled, every later
   cancellable point throws. Not sticky is an over-approximation, so it gives no false PROVEN. It flags code that
   swallows a cancellation and goes on (K9); that code breaks the module's own rule (it rethrows at 5 sites), so the
   flag is wanted. The original swallows none: 83, 141, 170, 204 and 220 rethrow, and 243 rethrows at 250.
4. Not modeled (judgment): a CancellationException that a user function throws by itself inside NonCancellable
   (e.g. `withTimeout` in `globalUnLockFunction`). It ends the obligation like an RTE (A4) and makes `exc == CANCEL`,
   which every RET clause excuses (UE7) and RET_cancel accepts; so no verdict changes.

### 2.3 Obligations under cancellation

1. `tryLock` raising CANCEL: as any throw, no token and no obligation; with A5 off a lost-token obligation stays (the
   store may have taken the lock before the cancellation arrived).
2. `unLock` raising CANCEL (only with cx): the release may not have run. The uninterpreted `cancelKeeps(k, lt, t)`
   decides whether the obligation stays. So code that releases in a cancellable context fails LD_noleak (no retry)
   or LD_own (a retry), and the only accepted pattern is NonCancellable.
3. A4 (signed for core: "an unLock that throws ends the obligation") does not cover a cancellation. Evidence in the
   coroutine module's `ReqShieldTest.kt` (two assertion messages and a comment): `:927` "Cancellation must release the
   lock before its TTL", `:1013` "Pre-start cancellation must release the lock before its TTL", `:274` "Cleanup must be
   able to suspend even when the caller has been cancelled." Measured (4.4, K1x): if A4 covered a cancellation,
   dropping NonCancellable at 111 would not be detected.

### 2.4 launch(ATOMIC), A3 and A6

1. Spawn as `runAsync` (DESIGN-E 3.4): the block is a task method with its own NO_LEASES; the spawn exhales the task
   precondition; `nspawn` counts an accepted launch.
2. Model `kotlinx_launch(atomic: Bool) returns (exc, dropped)`:
   `ensures exc == NONE || (!DISPATCH_NEVER_THROWS && (exc == REE || exc == RTE)) || (MAY_ERR && exc == ERR)` (A6) and
   `ensures dropped ==> exc == NONE && (!atomic || !ACCEPTED_TASKS_RUN)` (A3).
3. Why A6 is needed: `launch` -> `coroutine.start(start, ...)` -> `ATOMIC -> block.startCoroutine` (`CoroutineStart.kt:89`)
   -> `DispatchedContinuation.resumeWith` -> `dispatcher.dispatch(context, this)` with no catch (`:193-196`). The
   default scope's `Dispatchers.IO` throws `RejectedExecutionException` only after its scheduler was closed
   (`CoroutineScheduler.kt:400-403`); an Executor-based dispatcher catches the rejection and redispatches to IO
   (`Executors.kt:129-136`). There is no catch around 75 or 138, so a throwing dispatch would leak the token and cost
   the caller its hit (75) or its supplier value (138).
4. The block's own cancellation: its first statement is `ensureActive()` (executeCallable 241 or executeSetCacheFunction
   218), so a block of a cancelled scope raises CANCEL at once; the catch at 243 or the finally at 224 releases under
   NonCancellable. A task's exception reaches no caller (a launched coroutine), so tasks have no RET clause.
5. Other `start` values: absent or DEFAULT -> `atomic = false` (supported, item 10 of the table); LAZY and UNDISPATCHED
   -> UNSUPPORTED (out of scope). The receiver must be a stable path (`reqShieldConfig.scope`); the returned Job must
   be unused.

### 2.5 Trace and the RET clauses

1. New event form `NAME = raised KIND` (Bool): true once a call of the entry raised KIND. The tool sets it after each
   call whose flag can be KIND; tasks do not record it (no task clause reads `exc`).
2. UE7 (a): every RET clause gets `&& exc != CANCEL` in its antecedent. Not `|| exc == CANCEL` in the consequent:
   Viper rejects an `acc` under a disjunction (measured, `exp/disj.vpr`: "found incompatible type `<impure>`, expected
   `Bool`"), and RET_supplier frames `res.value` with `acc(res.value, wildcard)`.
3. UE8 (a): `RET_cancel: cancelled ==> exc == CANCEL`.
4. Hole: the polling loop needs a sixth conjunct `cancelled ==> exc == CANCEL` (measured: without it the fixed model
   FAILS RET_cancel, a false alarm, because the loop havocs `tr.cancelled`). The loop order differs from core (read,
   `attempts++`, `delay`); a hit or the third failure leaves without `attempts++`, so the hole bounds `npoll` directly.
   The tool's measure `maxAttempt - attempts` still works: Silicon does not check `decreases` on an iteration that sets
   an exit flag (measured, `exp/term.vpr`).

## 3. Properties and new user decisions

| ID | core clause (signed) | coroutine clause | change |
|---|---|---|---|
| LD_noleak, LD_own, LD_transfer, LD_wait, TERM | tool facts | same | CANCEL semantics of 2.3 |
| LD_write, LD_supplier | guard over `setCacheFunction`, `Callable.call` | same text over `setCacheFunction`, `callable` | call target |
| MODE_create, MODE_update, SUP_once, POLL_bound | | same text | sources |
| RET_hit, RET_get_error, RET_recheck, RET_poll, RET_supplier, RET_supplier_error | `<cond> ==> <result>` | `<cond> && exc != CANCEL ==> <result>` | UE7 |
| RET_cancel | — | `cancelled ==> exc == CANCEL` | new, UE8 |

**UE7. May a cancellation of the caller replace its result?**
- (a) Yes: a RET clause does not apply when the call ends with CANCEL (recommended; it is the coroutine contract, and
  the module rethrows every CancellationException).
- (b) No. Measured (4.4, UE7b): the fixed code then FAILS RET_hit, RET_get_error and RET_supplier_error, because a
  cancelled refresh `tryLock` (71), a cancelled first read (203) and a cancelled supplier (242) end the call with CANCEL.
  Every such failure is a false alarm under the coroutine contract.

**UE8. Must a raised cancellation reach the caller as a CancellationException (RET_cancel)?**
- (a) Yes (recommended). It catches wrapping (K7, K8), swallowing (K9) and a naive port of the core fix (K10), which the
  other clauses do not catch (measured, 4.4). The original fails it at the same two places that fail RET_recheck and
  RET_supplier_error (a throwing release replaces the pending exception); the fix covers it.
- (b) No: 13 intent clauses become 12, the hole loses its sixth conjunct, and K7, K8, K9, K10 are MISSED (on the
  fixed base their failing sets are RET_cancel and the sixth conjunct's HOLE only; measured with `run2/`).

**UE9. The original coroutine code FAILS (4.2). Apply `fixed-coroutine.patch` to req-shield?** Same situation as the
core after UE3 (DESIGN-E 5.6). The patch is ready (+36/-7, compiles with K2, the module's 57 tests pass on a patched
copy, 4.7); tests (a)-(d) of 4.7 are still to be written.

## 4. Hand model and measurements

### 4.1 Files

| File | Content |
|---|---|
| `target-coroutine.vpr` | 693 lines. The canonical model: the code at `2e792d2`; A1, A3, A4, A5, A6 on, A2 off; UE7 (a); UE8 (a). Layout and names follow the tool's core output (`out/core-generated.vpr`, `kv emit` of the signed core sidecar): kinds by class and code, `EV_<model>`, `GUARD_<id>`, `TASK_<line>_PRE`, locals `<name>__<line>`, flags `e_<line>_<col>[_at<line>]`, `ret_`, `brk_`, `caught_` |
| `fixed-coroutine.patch` | +36/-7 on the coroutine `ReqShield.kt` (4.3) |
| `tools/mutants_c.py` | FIX (the patch in Viper) and 41 entries; `tools/isolate_c.py`, `verdicts_c.py`, `reach_c.py`, `run_c.sh` (copies of the `spike/protocol2` scripts with RET_cancel added) |
| `verdicts.txt` | the measured verdicts and failing sets (copy of `run1/verdicts.txt` with a header) |
| `run1/` | `first-reports.txt` (one Silicon run per file), `isolation.txt`, `verdicts.txt`, the generated files |

### 4.2 The original coroutine code (canonical base)

Measured: FAILED; the full failing set is exactly **RET_hit, RET_recheck, RET_supplier_error, RET_cancel**. LD_noleak,
LD_own, LD_transfer, LD_wait, LD_write, LD_supplier, MODE_create, MODE_update, SUP_once, POLL_bound, TERM, HOLE,
RET_get_error, RET_poll and RET_supplier are PROVEN. Each cause is a reachable branch (probes, 4.6):

| Failing | Path | Core counterpart |
|---|---|---|
| RET_hit | the refresh `tryLock` (71) throws RTE; nothing catches it; the caller loses the hit | 66 |
| RET_recheck | the release in the recheck `finally` (112, inside NonCancellable) throws RTE and replaces the `return cachedData` | 101 |
| RET_supplier_error | the release after a failed supplier (247) throws RTE and replaces SUPPLIER_ERROR | 265 |
| RET_cancel | the same two releases (112, 247) throw RTE while a CANCEL is pending; the RTE replaces the CANCEL (probe "recheck cancelled, release threw RTE" is reachable) | — |

1. These are the core's three UE3 defects, unchanged in the coroutine variant; RET_cancel has the same root cause.
   With A2 on (lock calls return, the design-time baseline) the original is PROVEN (measured, A2_on).
2. So the original coroutine code needs a req-shield fix and a user decision (UE9), as the core did (DESIGN-E 5.6).

### 4.3 The fixed copy (`fixed-coroutine.patch`)

1. Changes: (a) the refresh `tryLock` (71) gets `catch (e: CancellationException) { throw e } catch (e: Exception) {
   log; return }`; (b) a helper `releaseQuietly(key, lockType, token, situation)` = `try { withContext(NonCancellable)
   { unLock } } catch (unlockError: Exception) { log }`, used at the recheck `finally` (110-113) and after a failed
   supplier (245-249). The task `finally` (227-229) stays, as core's 243 stayed.
2. Measured: the fixed model is PROVEN (Silicon 2.54-2.59 s, 3 runs); the patched `ReqShield.kt` compiles with K2
   (`kv discover` exit 0; a deliberately broken copy exits 1).
3. The cancellation rethrow in (a) matters: K10 (the core fix ported as is, `catch (e: Exception)` only) FAILS
   RET_cancel, because a cancelled refresh `tryLock` is swallowed and the hit is returned to a cancelled caller.
4. The req-shield test run of the patched copy: see 4.7.

### 4.4 Mutants, controls, toggles and probes (full failing sets, measured)

R = the canonical original's set (RET_cancel, RET_hit, RET_recheck, RET_supplier_error). The Kotlin edit of each
entry is in its comment in `tools/mutants_c.py`.

| Entry | Kotlin edit | canonical base | fixed base |
|---|---|---|---|
| M1 | no release in executeCallable's catch (245-249) | LD_noleak; RET_cancel, RET_hit, RET_recheck | LD_noleak |
| M3 | recheck `finally` always releases (110): double release | LD_own, LD_supplier, LD_transfer; RET_cancel, RET_hit, RET_recheck | LD_own, LD_supplier, LD_transfer |
| M4 | the fallback writes the cache (`.also { setCacheFunction }`) | LD_write, RET_supplier + R | LD_write, RET_supplier |
| M5 | release `lastToken ?: token` (228) | LD_own + R | LD_own |
| M6 | release with `LockType.CREATE` (247) | LD_own + R | LD_own |
| M7 | no early return (73): refresh without the lock | LD_supplier, LD_write + R | LD_supplier, LD_write |
| M8 | create lock in ONLY_UPDATE_CACHE (99) | MODE_create + R | MODE_create |
| M9 | null instead of SUPPLIER_ERROR (251) | LD_own, LD_transfer, LD_write + R | LD_own, LD_transfer, LD_write, RET_supplier_error |
| M10 | the fallback retries the supplier | SUP_once + R | SUP_once |
| M11 | `attempts++` (180) in the catch | HOLE, TERM + R | HOLE, TERM |
| M12 | release before the write task | LD_transfer, RET_supplier + R | LD_transfer, RET_supplier |
| C1, C2, C3 | release without finally / recheck without the flag / one finally in the refresh task | R (ACCEPTED) | none (ACCEPTED) |
| K1 | recheck release without NonCancellable (111) | LD_noleak + R | LD_noleak |
| K2 | supplier-failure release without NonCancellable (246) | LD_noleak + R | LD_noleak |
| K3 | write-task release without NonCancellable (227) | LD_noleak + R | LD_noleak |
| K4 | `launch { }` without `start = ATOMIC` (75, 138) | LD_noleak + R | LD_noleak |
| K5 | `catch (e: IOException)` at 243 (hand model: takes no modeled kind) | LD_noleak + R | LD_noleak, RET_supplier_error |
| K5b | `catch (e: RuntimeException)` at 243 | LD_noleak + R | LD_noleak, RET_supplier_error |
| K6 | write-task release after the try, not in `finally` | LD_noleak + R | LD_noleak |
| K7 | no `catch (e: CancellationException)` at 204: CANCEL becomes GET_CACHE_ERROR | HOLE + R | HOLE, RET_cancel |
| K8 | no `if (e is CancellationException) throw e` (250) | R (MASKED: its one defect is RET_cancel; expectation relabeled after the run, see observation 7) | RET_cancel |
| K9 | polling `catch (e: Exception)` without 170: a cancellation counts as a failed read | HOLE + R | HOLE |
| K10 | the core fix ported without the cancellation rethrow (fixed base only) | — | RET_cancel |
| KC1 | no `catch (e: CancellationException)` at 170 (ClientException does not take it) | R (ACCEPTED) | none (ACCEPTED) |
| KC2 | the refresh task swallows its own cancellation (83 deleted) | R (ACCEPTED) | none (ACCEPTED) |
| KC3 | `tryLock` cannot be cancelled (a KeyLocalLock does not suspend) | R (ACCEPTED) | none (ACCEPTED) |
| A1 off | Errors possible | LD_noleak, RET_get_error, RET_supplier + R | LD_noleak and all 7 RET clauses |
| A2 on | lock calls return | PROVEN | PROVEN |
| A3 off | an accepted block may be dropped | LD_noleak + R | LD_noleak |
| A4 off | a throwing unLock keeps its obligation | LD_noleak + R | LD_noleak |
| A5 off | a throwing tryLock may have taken the lock | LD_noleak + R | LD_noleak |
| **A6 off** | **dispatch may throw: launch throws** | **LD_noleak, RET_supplier + R** | **LD_noleak, RET_hit, RET_supplier** |
| AF4 | A1 off, only the supplier may throw an Error (Phase 0 A_F4) | LD_noleak + R | LD_noleak, RET_supplier_error |
| UE7b | no `&& exc != CANCEL` escape | RET_get_error + R | RET_get_error, RET_hit, RET_supplier_error |
| G1 | task precondition without the early-return fact of 73 | LD_supplier, LD_write + R | LD_supplier, LD_write |
| K1x | K1 with "A4 covers a cancellation" (no `cancelKeeps` line) | R (K1 not detected) | none (K1 not detected) |
| X1 | a cancelled release always keeps the obligation | R | none |
| K11 (extra run, `run3/`) | the recheck release without NonCancellable, retried under NonCancellable after a CANCEL | — | LD_own, RET_cancel |
| K11x (extra run) | K11 with the X1 encoding | — | RET_cancel (LD_own not detected) |

Observations:
1. The 11 ported mutants break the same obligations as in core (DESIGN-E 0 table), except M12: no LD_own here,
   because the coroutine code has no rejection path whose release would be a second release (core 152).
2. M2 (no release after a rejection) has no counterpart: the coroutine code handles no launch failure at all. A6 off
   shows what that costs: LD_noleak and the caller's value (RET_supplier at 138; RET_hit at 75 on the fixed base).
3. The three "drop NonCancellable" mutants (K1-K3) are detected only through the `cancelKeeps` semantics, and K4 only
   through the ATOMIC rule (`atomic = false` may drop the block); with A4 stretched to a cancellation (K1x) K1 is
   MISSED. X1 ("a cancelled release always keeps") gives the same verdicts on this code; `cancelKeeps` is kept because
   it also rejects a retry of a cancelled release, which may release twice: K11 FAILS LD_own with `cancelKeeps` and
   not with X1 (K11x) (measured, `run3/`).
4. K7 and K9 fail the sixth hole conjunct first (HOLE). Without that conjunct they fail RET_cancel (measured, `run2/`);
   in the tool flow an LLM that drops the conjunct to repair HOLE gets RET_cancel FAILED, so the defect stays reported.
5. A1 off fails RET_get_error in the coroutine variant but not in core: core reads the cache in `runCatching` (catches
   Throwable), the coroutine code in `catch (e: Exception)` (judgment from the code; consistent with the measurement).
6. KC3: a non-suspending KeyLock (KeyLocalLock: `compute()`, no suspension) changes no verdict.
7. Transparency: K8's canonical expectation was `fail` before the run; the run showed MISSED, and it was relabeled
   `masked` afterwards (K10 had been limited to the fixed base before the run for the same reason). The set rule
   cannot see a defect that the base original already fails. The consequence is real: on the unfixed coroutine code a
   defect that only breaks RET_cancel (here: wrapping a cancellation at 250) is invisible until the fix lands. That is
   one more argument for UE9.

### 4.5 Time (measured)

| Run | Time |
|---|---|
| canonical original alone, 3 runs | Silicon 2.58-2.61 s |
| fixed original alone, 3 runs | Silicon 2.54-2.59 s |
| fixed, per method (`TIMES=1`) | `getAndSetReqShieldData` 0.71 s, `task_75` 0.34 s, `task_138` 0.24 s, models 0.02-0.09 s |
| full suite (`run_c.sh`, 8 workers) | 1400.65 s wall clock (1,264 isolation copies; 7-9 s per file under contention) |

Core for comparison: 2.13-2.20 s, entry 0.51 s (DESIGN-E 5.3).

### 4.6 Non-vacuity and the hole

1. 29 `refute false` probes per base (`tools/reach_c.py`): all reachable on both bases (0 "Refute holds"). They cover
   each cancellation point (first read, refresh `tryLock`, recheck, `ensureActive` and supplier on the creation path,
   poll read, `delay`, the fallback, both tasks), each release after a cancellation, the RTE paths of 4.2, the
   ONLY_CREATE_CACHE and ONLY_UPDATE_CACHE lock-free paths, the poll hit, the three-failure break and the fallback.
2. The hole has 6 conjuncts (`reqshield.coroutine.kv.draft`). The first draft (the core's 5, re-derived for the
   coroutine loop order) verified at once except RET_cancel on the fixed base: the loop havocs `tr.cancelled`. One
   Silicon round added `cancelled ==> exc == CANCEL` (measured; without it the fixed original FAILS RET_cancel).

### 4.7 The req-shield tests of the patched copy

Measured: a copy of the pinned checkout (`rsfix/`, without `build`, `.gradle`, `.git`) with the patch;
`./gradlew :core-kotlin-coroutine:test --offline`: BUILD SUCCESSFUL in 31 s, 57 tests, 0 failures, 0 errors, 0 skipped
(`ReqShieldTest` 38, `KeyLocalLockTest` 14, `KeyGlobalLockTest` 3, `ReqShieldConfigurationTest` 2). As for core
(DESIGN-E 5.6), this shows no regression only: no existing test makes a lock call throw on the three paths of 4.2.
Tests to add with the fix (judgment), the core's three plus one: (a) a refresh `tryLock` that throws keeps the hit and
calls no supplier; (b) a release that throws after a recheck hit keeps the data; (c) a supplier and its release that
both throw give SUPPLIER_ERROR; (d) a cancelled recheck whose release throws ends with the CancellationException.

## 5. Sidecar plan

### 5.1 One more file, no include

Recommendation: a separate, self-contained `specs/reqshield/reqshield.coroutine.kv`; the signed `reqshield.core.kv`
stays as it is.

| Option | Shared text | Cost |
|---|---|---|
| A. Own file (recommended) | none; about 15 lines repeat (2 types, 2 enums, the class, the obligation, the LD_write guard and its sources) | none on core |
| B. `include "reqshield.common.kv"` from both | the 15 lines above. Everything else differs: the trusted declarations bind other Kotlin symbols (other fingerprints), the trace names other calls (`callable`, `kotlinx.launch`) and adds `cancelled`, the RET clauses differ (UE7), MODE and A3-A5 cite other comments, LD_supplier names another call | the signed core file is split: its line positions move (the `.vpr.positions` table changes, DESIGN-E 12.12 item 7), the vocabulary note covers include files at their position, `ClientCliTest` and the signing tests read the file, and the user must confirm that the moved text is what was signed |

1. The same enum text binds different classes: `enum LockType = LockType` resolves in the module's package
   (`com.linecorp.cse.reqshield.kotlin.coroutine.LockType`). A shared file would hide that the two sidecars bind
   different Kotlin types (judgment).
2. `keylocallock.*.kv` uses `include` because the three L1 modules share a ghost record and properties; the two client
   sidecars share almost no clause text.

### 5.2 New snapshot

`specdraft/reqshield-coroutine/snapshot`, made with (measured into `snapshot-coroutine/`, 130 blocks):

```
kv spec snapshot --out specdraft/reqshield-coroutine/snapshot README.md=<rs>/README.md CLAUDE.md=<rs>/CLAUDE.md \
  ReqShield.kt=<rs>/core-kotlin-coroutine/.../coroutine/ReqShield.kt ReqShieldConfiguration.kt=<rs>/.../coroutine/config/ReqShieldConfiguration.kt \
  KeyLock.kt=<rs>/.../coroutine/KeyLock.kt ClientException.kt=<rs>/support/.../exception/ClientException.kt
```

1. The 120 README and CLAUDE.md blocks equal the core snapshot's (same anchors and texts, measured), so their quote
   lines keep the core's block fingerprints.
2. The coroutine KDoc blocks: `ReqShield.kt#handleLockFailure` (`86fa18223492c680`),
   `ReqShieldConfiguration.kt#decl36`, `#decl43`, `#decl58`, `#decl81`, `#defaultKeyLock`, `KeyLock.kt#tryLock`,
   `#unLock`, `#lockKeyOf`; `ClientException.kt#ClientException` equals the core's (`a3f2f2b5d5f541a7`).
3. A new snapshot is needed: the core snapshot names the core files `ReqShield.kt` etc., and the coroutine KDoc differs.
   The coroutine `ReqShield.kt` has only one KDoc block (`handleLockFailure`); the core's `runInBackground` and
   `executeCallable` KDoc, which core RET_hit and RET_supplier cite, has no coroutine counterpart. Those clauses get
   `source none` lines (decision UE3, a code comment, or a test name).

### 5.3 The declarations (draft: `reqshield.coroutine.kv.draft`)

| Layer | Declarations | New against core |
|---|---|---|
| 1 binding | `module ...kotlin.coroutine.ReqShield client`, `source "core-kotlin-coroutine/src/main/kotlin"`, `scope`, `free mode`, `free maxAttempt`, `result res` | the module and source path |
| 2 trust | `KeyLock.tryLock ... throws RTE, CANCEL`; `KeyLock.unLock ... throws RTE, CANCEL`; `trusted value getCacheFunction ... throws RTE, CHK, CANCEL`; `trusted value setCacheFunction ... throws RTE, CHK, CANCEL`; **`trusted param ReqShield.getAndSetReqShieldData.callable() returns v: Int throws RTE, CHK, INTR, CANCEL`**; `decideToUpdateCache` (same text as core); `use kotlinx.launch`, `kotlinx.withContext`, `kotlinx.ensureActive`, `kotlinx.delay`, `slf4j.Logger`; `assume A1, A3, A4, A5`, **`assume A6: dispatch-does-not-throw`** | `trusted param`, CANCEL in `throws`, 4 `use` models, A6 |
| 3 vocabulary | types, enums, `class Data`, `obligation Lease`, `trace` with `sup = last callable`, `nsup = count callable`, `nspawn = count kotlinx.launch`, **`cancelled = raised CANCEL`** | the `raised` form |
| 4 intent | LD_write, LD_supplier, MODE_create, MODE_update, SUP_once, POLL_bound, 6 RET clauses with `&& exc != CANCEL`, **RET_cancel** | UE7 escape, RET_cancel |
| 5 hole | `hole loop ReqShield.handleLockFailure`, 6 groups | the 6th group |

### 5.4 What the user signs (proposed `reviewed` notes are in the draft)

| # | Item | Kind | Sources |
|---|---|---|---|
| 1-2 | LD_write, LD_supplier | intent | as core (CLAUDE.md work modes, README wait section, UE5); LD_supplier cites `ReqShield.kt#handleLockFailure` "end the wait early, after which the supplier is called on this coroutine as the last resort." |
| 3-6 | MODE_create, MODE_update, SUP_once, POLL_bound | intent | as core; the code comments are the coroutine's (97, 69) |
| 7-12 | RET_hit, RET_get_error, RET_recheck, RET_poll, RET_supplier, RET_supplier_error | intent | as core where a block exists, plus `source none "user decision UE7 ..."` |
| 13 | RET_cancel | intent | `ReqShield.kt#handleLockFailure` "[delay] is a cancellation point, so a cancelled caller"; tests 920 and 1008 (`source none`); UE8 |
| 14-17 | A1, A3, A4, A5 | assumption | as core, with coroutine wording: A3 "a block the dispatcher accepted runs"; A4 "a cancelled unLock is not covered"; A5 "a failure or a cancellation" |
| 18 | A6 dispatch-does-not-throw | assumption | `source none "user decision UE6 (2026-10-29) ..."`, README thread-pools block 3 |
| 19-24 | 6 trusted declarations | trust | T-A unit: contract text + Kotlin text; `decideToUpdateCache` keeps its core fingerprint `a858dc2f51d5892c` (same contract and Kotlin text, judgment) |
| 25 | vocabulary note | vocabulary | — |
| 26 | scope note | binding | README wait section, as core |

## 6. Tool changes

### 6.1 What the current tool does with the coroutine variant (measured, `probe/`)

1. `kv discover --client .../core-kotlin-coroutine/src/main/kotlin`: no client candidate (`findClients` looks for
   `CompletableFuture.runAsync` only, `frontend/ClientDiscovery.kt:69-72`).
2. `kv resolve --draft probe/probe0-port.kv` (the core sidecar ported, no new syntax): exit 1, two errors
   `trusted value ReqShieldConfiguration.getCacheFunction: ... is a kotlin.coroutines.SuspendFunction1, not a Kotlin
   function type` (and `SuspendFunction3` for the setter). Cause: `frontend/Resolver.kt:311` accepts only
   `kotlin/Function*`.
3. `kv lower --draft probe/probe1-novalues.kv` (no trusted values): **exit 1, `java.lang.StackOverflowError`** in
   `ClientUnit.expr` <-> `ClientUnit.valueInto` (`lower/ClientLowering.kt:1818` and `:1077`). Cause: `e is
   CancellationException` (250) is a `FirTypeOperatorCall`; `expr` sends an expression kind it does not know to
   `valueInto` (its `else` branch), and `valueInto` sends it back to `expr`. Confirmed by a copy without line 250
   (`soe/`), which lowers.
4. That copy: module INCOMPLETE, 12 UNSUPPORTED sites of 9 construct kinds: `SuspendFunction1.invoke` (203),
   `SuspendFunction0.invoke` (242), `catch (e: java.util.concurrent.CancellationException)` (204, 170),
   `catch (e: ...ClientException)` (172), `kotlinx.coroutines.launch` (75, 138), `kotlinx.coroutines.withContext`
   (111, 246), `kotlinx.coroutines.ensureActive` (241), `kotlinx.coroutines.delay` (181), `kotlin.also` (168).
   The task bodies are not lowered (no spawn), so 218, 219, 227 and 228 add no new kinds of construct.

### 6.2 Steps

Paths: code under `translator/src/main/kotlin/kv/`, tests under `translator/src/test/kotlin/kv/`. Each step lists a
deliberate break: put it in, see the named test fail, take it out. Estimates: one FIR+Viper engineer (as DESIGN-E 6.10).

| Step | Change (files, functions) | Tests (new or changed) | Deliberate break -> failing test | Estimate (person-days) |
|---|---|---|---|---|
| S0 | Baseline: save the outputs of the 6 L1 modules (`kv discover/resolve/lower/emit`), both core Vipers and their `.positions`, the test count, `mutantsReqShield/Aspect/Client` | — | — | 0.25 |
| S1 | `lower/ClientLowering.kt` `expr`: the `else` branch only for branching kinds (when, try, elvis, safe call); any other kind -> `unsupported("expression <kind>")`. New case `FirTypeOperatorCall` (`is`, `!is`) whose subject is a caught exception (`Bound.Exception`) and whose type maps by `catchKinds` -> new KVIR `CaughtIs(kinds, negated)` (`kvir/Client.kt`), printed (`kvir/Printer.kt`), allowed only inside a catch body (`kvir/ClientWellFormed.kt`, new rule W23); emitted as `<caught flag> == K` (`emit/ClientEmitter.kt` `expr`, the frame's `caught`) | `client/ClientConstructs.kt`: fixture "is-test on the caught exception" lowers; fixture "`x is String` on a value" gives UNSUPPORTED and **no crash** | restore the `else -> valueInto` fallback -> the no-crash test fails (StackOverflowError) | 0.5 |
| S2 | CANCEL: `kvir/Client.kt` `LibExc.CANCEL`; `ClientLowering.catchKinds` (2.1: `java/util/concurrent/CancellationException`, CANCEL in Throwable/Exception/RuntimeException only if `usesCancel`); a catch of a module-thrown class -> its `Thrown` kinds, with the 2.1 item 3 rejection; `sidecar/ClientCheck.kt` `TRUSTED_THROW_KINDS += CANCEL`; emitter kind order and `IS_EXCEPTION` bound (2.1) | catch-mapping unit tests (5 types x with/without CANCEL); `client/ClientGoldenTest` (core KVIR unchanged) | add CANCEL to `catch (e: Exception)` unconditionally -> core golden KVIR test fails | 0.5 |
| S3 | Suspend trusted calls: `frontend/Resolver.kt` `trustedValue` accepts `kotlin/coroutines/SuspendFunctionN`; new `trusted param FUN.PARAM(...)` (`sidecar/Parser.kt` `trusted()`, `sidecar/Ast.kt` `TrustedDecl.kind`, `Resolver.trustedParam`, fingerprint unit = parameter source text via `frontend/ReviewedSource.kt` + contract text (T-A)); check `cancel-kind`: a suspend function or type lists CANCEL, another does not; lowering: `isInvoke` accepts `SuspendFunctionN`, `trustedValueOf` follows a parameter to its `trusted param` (also through task captures); KVIR `Call.cx: Boolean?`; emitter: `cx` parameter, `(cx && exc == CANCEL)`, the A4 line `exc != CANCEL &&`, the `cancelKeeps` line and domain `CancelledRelease` (only for a model with CANCEL) | `client/ClientResolveTest`: SuspendFunction1 value resolves; `trusted param` resolves, wrong type -> `kotlin-type`; missing CANCEL -> `cancel-kind`; `frontend/ClientTrustedReviewTest`: param fingerprint; `client/ClientEmitterTest`: model text with cx | (1) emit `cx = true` inside NonCancellable -> the fixed-copy verify test FAILS (LD_noleak); (2) drop the `cancelKeeps` line -> K1, K2, K3 MISSED in the suite | 1.5-2.5 |
| S4 | Coroutine models: `model/ClientModels.kt` `kotlinx.launch` (SPAWN, owner `kotlinx/coroutines`), `kotlinx.withContext` (new role NON_CANCELLABLE: argument `NonCancellable` only, block inlined with cx = false, other contexts UNSUPPORTED), `kotlinx.ensureActive` and `kotlinx.delay` (CALL, throws CANCEL), `kotlinx.currentCoroutineContext` (NO_EFFECT receiver); `ClientLowering.spawn`: the task is the trailing lambda for `launch`; `start = CoroutineStart.ATOMIC` -> `Spawn.atomic = true`, absent or DEFAULT -> false, LAZY or UNDISPATCHED -> UNSUPPORTED; `scopeCall` adds `also`; `frontend/ClientDiscovery.kt` `isRunAsync` -> `isSpawn`; emitter `kotlinx_launch(atomic)`, `kotlinx_ensureActive(cx)`, `kotlinx_delay(cx)`; `SWITCHES` adds A6 `DISPATCH_NEVER_THROWS`, emitted only when the module uses `kotlinx.launch`; `CLIENT_ASSUMPTIONS` adds `A6 to dispatch-does-not-throw` | lowering fixtures: launch(ATOMIC), launch(), launch(start = LAZY) UNSUPPORTED, withContext(NonCancellable) inline, withContext(Dispatchers.IO) UNSUPPORTED, delay, ensureActive, also; `cli/DiscoverClientCliTest`: the coroutine module has 1 entry and 2 tasks (75:69, 138:69) | (1) treat a DEFAULT start as atomic -> K4 MISSED; (2) emit the A6 define always -> core Viper byte check fails | 1-1.5 |
| S5 | Task precondition part 3 (DESIGN-E 3.4): after an `if (c) <jump>` without else, `!c` holds for the rest of the block (`ClientLowering.block`/`chain` push `Not(c)` onto `conds` until the block ends). That the lowering drops `!c` today is a judgment from reading `chain()` (its `conds` hold only inside the `if`); core has no spawn after an early return to measure it on | `client/TaskPreconditionTest`: the spawn after `if (!only && token == null) return` gets the condition; core tasks unchanged (golden) | skip the push -> G1 false alarm: LD_supplier and LD_write in task_75 (measured on the hand model) | 0.5 |
| S6 | `raised KIND` event form: `sidecar/Parser.kt` trace events, `sidecar/Ast.kt` `EventForm.RAISED`, `ClientCheck.trace` (a known kind), vocabulary unit (`Ast.kt` 12.3.2) covers it; emitter: `field NAME: Bool`, macro `EV_raised_<KIND>(tr, e)` after each entry call whose flag can be KIND, not in tasks; hole vocabulary accepts the field | grammar tests (A-style), emitter text test; isolation test of the original's failing set | record it in tasks too -> task_138 gets a trace (gate diff); skip built-in models -> a fixture where only `ensureActive` raises CANCEL passes RET_cancel wrongly | 0.5-1 |
| S7 | Sidecar and snapshot: `specdraft/reqshield-coroutine/snapshot` (5.2), `specs/reqshield/reqshield.coroutine.kv` from the draft; one user round (UE7, UE8, UE9, 26 notes); `kv resolve` reports the fingerprints (DESIGN-E 12.6 steps 6-8). If UE9 (a): the req-shield commit with tests (a)-(d) of 4.7, as for core (`1739f01`) | `cli/ClientCliTest`-style test for the coroutine sidecar (note count 26) | — | 0.5 (+ user) |
| S8 | Gate: the hand model goes into the repository (proposal `spike/protocol2c/`: `target.vpr`, `fixed-coroutine.patch`, `mutants.py`, `isolate.py`, `reach.py`, `verdicts.py`, `run.sh`); `translator/scripts/protocol2c_gate.py` (or `protocol2_gate.py --suite coroutine`): the normalizer of `protocol2_gate.py` (LOCAL, unparen) plus a small STRUCTURAL table | `client/ClientGateTest`: 2 new tests (original; copy with `fixed-coroutine.patch`) | change the kind order (CANCEL before REE) -> the gate test fails | 0.5-1 |
| S9 | Mutant suite: `scripts/mutants.py --suite coroutine` (the Kotlin edits of 4.4), `scripts/coroutine-mutants-expected.txt`, Gradle task `mutantsCoroutine` (as `mutantsClient`, with `--fixed-patch`); `verify/ClientReport.kt`: A6 in Assumptions, RET_cancel in "Spec to review", the variant in the title | `scripts/MutantScriptTest`: the suite's edits apply to the pinned file; the expected file parses | a wrong expected row -> the Gradle task reports 1 mismatch | 1-1.5 |
| S10 | Regression (7) and records: `HISTORY.md`, `PROTOCOL2.md`, DESIGN-E 6.10 row E5 | R1-R6 of 7 | — | 0.5 |
| Total | | | | 7-9.5 |

1. DESIGN-E 6.10 estimated E5 at 3-5 person-days for "4 trusted models, CANCEL, suspend function values, variant
   sidecar". The measured list (6.1) adds a crash fix, `trusted param`, a module-class catch, `also`, the early-return
   fact, the `raised` form and the A6 gating. This is a judgment from the code; not run.
2. The steps are ordered: S1 first (the crash blocks every lowering run of this file); S2-S6 in any order; S7 needs the
   user; S8 and S9 need S1-S7.

## 7. Regression guard

| # | Check | Expected |
|---|---|---|
| R1 | all tests | pass (count = the last record + the new tests) |
| R2 | `mutantsReqShield`, `mutantsAspect`, `mutantsClient` | 33, 30, 42 runs, 0 mismatches |
| R3 | the core original and fixed copy (`client/ClientVerifyTest`) | FAILED with exactly RET_hit, RET_recheck, RET_supplier_error; PROVEN |
| R4 | the 6 L1 modules: `kv discover`, `resolve`, `lower`, `emit` outputs | byte-identical to S0 |
| R5 | both core Vipers and their `.positions` | byte-identical to S0 (no core sidecar edit) |
| R6 | the core golden KVIR `golden/client/reqshield.core.kvir` | unchanged |

How the design keeps R4-R6 (judgment from the code): every new emitter output is gated on the module: CANCEL in the
kind list and the catch mapping only if the module uses CANCEL; the `cx` parameter and the `cancelKeeps` line only for
a model with CANCEL; the A6 define only with `kotlinx.launch`; `raised` fields only if the trace declares one. The L1
lowering (`lower/ActionLowering.kt`) and emitter (`emit/Emitter.kt`) are not touched.

## 8. Predictions (register before the implementation)

| # | Prediction | Basis |
|---|---|---|
| P1 | `kv verify` of the pinned coroutine code with the signed coroutine sidecar (UE7 (a), UE8 (a)): module FAILED; failing properties exactly RET_hit, RET_recheck, RET_supplier_error, RET_cancel; LD_noleak, LD_own, LD_transfer, LD_wait, LD_write, LD_supplier, MODE_create, MODE_update, SUP_once, POLL_bound, TERM, HOLE, RET_get_error, RET_poll, RET_supplier PROVEN | hand model, 4.2 |
| P2 | the copy with `fixed-coroutine.patch`: PROVEN, with the same sidecar | 4.3 |
| P3 | `mutantsCoroutine`: every non-info row of `verdicts.txt` reproduced (same failing set) on both bases, except at most 2 rows with a documented encoding difference (core had 1, M12). K5 (`catch (e: IOException)`) is UNSUPPORTED (module INCOMPLETE) in the tool, by design | 4.4 |
| P4 | gate: the generated model equals `target-coroutine.vpr` after normalization with at most 10 structural edits and 5 renames (core needed 16 edits; this hand model was written in the tool's layout) | 4.1 |
| P5 | R1-R6 of 7 hold | 7 |
| P6 | Silicon time of the coroutine model at most 3.5 s; `kv verify` of the original (with the automatic isolation) at most 40 s | 4.5; core 18 s (DESIGN-E 12.2.1) |
| P7 | user effort: 1 question round (UE7, UE8, UE9) and 26 notes | 5.4 |
| P8 | size: code +400 to +600 lines, tests +600 to +900 lines (DESIGN-E 12.12: tests came to 1.8 times their estimate) | judgment |
| P9 | an LLM hole run seeded with the core hole needs at least one Silicon round to add `cancelled ==> exc == CANCEL` | 2.5 item 4 |

## 9. Open questions

For the user (one round):
1. UE7 (3): (a) the RET clauses do not apply to a cancelled call (recommended); (b) they do.
2. UE8 (3): (a) add RET_cancel (recommended); (b) not.
3. UE9 (3): apply `fixed-coroutine.patch` to req-shield, as for core (`1739f01`)? Without it the original stays FAILED.
4. The 26 notes of 5.4, in particular the coroutine wording of A3, A4 ("a cancelled unLock is not covered"), A5 and the
   new A6.

For the main session:

| ID | Question | Opinion of this design |
|---|---|---|
| JE9 | supplier parameter: new `trusted param`, or `trusted value` that also names a parameter | `trusted param`: one meaning per keyword; its fingerprint unit is a parameter, not a property |
| JE10 | CANCEL in `throws`: listed by the sidecar (checked against FIR), or added by the tool | listed and checked: the signed text then shows the cancellation point |
| JE11 | cancellation not sticky, or a ghost job state | not sticky: no false PROVEN; a sticky model needs a new assumption "a CancellationException means the job is cancelled" |
| JE12 | the cancellable context: a `cx` parameter, or two model methods per suspend call | `cx`: one method per model, as today |
| JE13 | where the hand model lives in the repository | `spike/protocol2c/` beside `spike/protocol2/`; the gate as `protocol2_gate.py --suite coroutine` to share the normalizer |
| JE14 | RET_cancel as an intent clause (signed) or as a tool fact | intent clause: it can fail on the original, so the user decides it (UE8) |
| JE15 | critic review of UE7/UE8 before the user round | the decisions change the clauses the user signs; a critic pass is cheap |

## 10. Cost record (this agent)

1. Wall clock about 1 h 15 min (22:08-23:22, from file times); the full measurement run took 23 min of it, and the
   req-shield test run 32 s.
2. Silicon runs: about 1,410 (79 files and 2 probe files once, 1,264 isolation copies, 48 in `run2/`, 6 timing runs,
   about 10 experiments).
3. Tokens: not measured.
4. The advisor timed out on the first call (before the model); the second call (before the handback) asked for the
   K8 transparency note, the S5 judgment label, the scope placeholder and the K11 measurement, all done.

## Appendix A. Reproduction

1. Model and suite: `bash tools/run_c.sh target-coroutine.vpr OUT` (both bases, 29 probes each, one Silicon run per
   file, the isolation, the verdicts). Output of this work: `run1/` (`first-reports.txt`, `isolation.txt`,
   `verdicts.txt`). `verdicts.txt` in `e5/` is the copy.
2. Single runs: `spike/silicon.sh --numberOfErrorsToReport 0 target-coroutine.vpr`; `TIMES=1` for the method times.
3. Current tool probes: `bin/kv.sh` wraps `translator/build/install/kv/bin/kv` with the `reqShieldClasspath` jars;
   `probe/probe0-port.kv` (resolve), `probe/probe1-novalues.kv` (lower), `soe/` (the copy without line 250).
4. The fix: `fixed-coroutine.patch` (`patch -p1` in a copy of the req-shield checkout); K2 compile check:
   `bin/kv.sh discover fixroot/core-kotlin-coroutine/src/main/kotlin` (exit 0; a broken copy exits 1).
5. kotlinx sources: `src/kx181/` (the 1.8.1 sources jar from the Gradle cache).

## Appendix B. Assumptions E5 adds (Silicon does not check them)

1. A6 (UE6 (a)): `scope.launch(start = ATOMIC)` does not throw.
2. A3 read for coroutines: a block the dispatcher accepted runs.
3. A cancellation of the caller's coroutine reaches no point inside `withContext(NonCancellable)`
   (`Builders.common.kt:134-136`, a library contract, not a signed assumption).
4. Not modeled (2.2 item 4): a CancellationException a user function throws by itself inside NonCancellable.
5. The default scope's `SupervisorJob` and `CoroutineExceptionHandler` (coroutine `ReqShieldConfiguration.kt:87-94`) are outside
   the model: a task's exception reaches no caller.
