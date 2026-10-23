# SnaKt: comment drafts for existing issues (not posted)

All four soundness defects found in the 2026-10-23 evaluation are already tracked upstream, so these are comments with
minimal reproducers, not new issues. Environment for every reproducer: SnaKt `main` `93d55208` (2026-10-22),
Kotlin 2.3.0 (`K2JVMCompiler` with `-Xplugin`), `-language-version 2.0`, JDK 21, Silicon `2026-04-10_b005beb`,
Z3 4.16.0, plugin options `conversion_targets_selection=all_targets verification_targets_selection=all_targets
unsupported_feature_behaviour=throw_exception error_style=both`.

| Defect | Existing issue | Draft below |
|---|---|---|
| `finally` body dropped | #306 (open), recorded as known in #404 | 1 |
| catch entered only before/after the try body | #306 (open) | 1 |
| `Int` is unbounded | #296 (open) | 2 |
| Silicon abort reported as success | #582 (open), #383 (closed) | 3 |
| Omitted default argument of a library call | #273, #595, #596 (open) | 3 |

## 1. Comment on #306

> Two minimal reproducers at `93d55208` where the current exception encoding accepts a false postcondition (no
> diagnostic, exit 0). In both, the same behavior written without `try` is correctly rejected.
>
> `finally` is dropped:
>
> ```kotlin
> fun probeFinally(): Int {
>     postconditions<Int> { r -> r == 1 }   // false: the function returns 2
>     var a = 0
>     try { a = 1 } finally { a = 2 }
>     return a
> }
> ```
>
> The catch handler is not reachable from a throwing call inside the try body:
>
> ```kotlin
> @NeverConvert
> fun mayThrow(): Int = 42
> fun probe(): Int {
>     postconditions<Int> { r -> r != 1 }   // false when mayThrow() throws
>     var a = 0
>     try { a = 1; mayThrow(); a = 2 } catch (e: Exception) { return a }
>     return a
> }
> ```
>
> Control (rejected as expected): `var a = 0; a = 1; if (b) { return a }; a = 2; return a` with `r != 1`.

## 2. Comment on #296

> Reproducer at `93d55208`: `fun incInt(x: Int): Int { postconditions<Int> { r -> r > x }; return x + 1 }` verifies,
> although it is false at `Int.MAX_VALUE`. Control: `r > x + 1` is rejected, so the check itself runs.

## 3. Comment on #582 (and cross-reference #595)

> A user-level reproducer for the silent abort, which also shows the omitted-default-argument cause (#595):
>
> ```kotlin
> import kotlinx.coroutines.Job
> fun stop(job: Job) { job.cancel() }   // Job.cancel(cause: CancellationException? = null)
> ```
>
> At `93d55208` the call is translated without the `cause` argument, Silicon aborts with
> `java.util.NoSuchElementException: key not found: par_cause`, and the compilation still ends with exit 0 and no
> diagnostic. With #582 merged this should become a `VIPER_VERIFICATION_ABORTED` error.
