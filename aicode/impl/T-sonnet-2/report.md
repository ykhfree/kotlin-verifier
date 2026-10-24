**Result:** `/private/tmp/claude-501/-Users-ykhfree-AI/9a7562f5-114a-4bd1-9a46-6cd88dafecdb/scratchpad/aicode/sandboxes/T-sonnet-2/KeyLocalLock.kt` compiles. `./compile.sh` gave no diagnostics on the first call (1 of 10 used). I did not run it or test it.

**Rules followed**
- `lockMap` and `tokenSequence` live in the companion object. Tokens are `"local-${tokenSequence.incrementAndGet()}"`.
- `LockInfo` has the exact shape from RULES.md.
- `tryLock` and `unLock` each use exactly one `lockMap.compute(lockKey)`. Results leave the lambda through an `AtomicReference` or an `AtomicBoolean` declared before the call.
- `tryLock` reads `val now = nowToEpochTime()` before its compute call.
- The monitor reads `val now = System.currentTimeMillis()` per key, then calls `compute` once.
- No `synchronized`, locks or `try/catch` anywhere.

**Design decisions the spec did not settle**
1. **Where the size check lives.** RULES.md rule 3 allows `lockMap` reads only inside the one compute call. So `LocalLockLimit.rejectsNewEntry(lockMap.size.toLong())` runs inside the `tryLock` lambda. I used `size`, not `mappingCount()`.
2. **When the cap applies.** It applies only when `existing == null`. An expired entry is replaced in place and skips the cap, because the map does not grow. A live entry is never subject to the cap, as the spec says.
3. **Over-cap permit.** `tryLock` returns a fresh `local-N` token and the lambda returns `null`, so nothing is inserted. A later `unLock` with that token finds no entry, or a different token, and returns `false`. The spec only says the permit has no entry behind it. It does not say what `unLock` returns, so `false` is my choice.
4. **Expiry test.** A lock counts as expired when `now >= expiresAt`, with `expiresAt = now + lockTimeoutMillis`. One private helper, `isExpired`, is shared by `tryLock` and the monitor. The spec says "once `lockTimeoutMillis` has passed" and does not say whether the boundary is inclusive. With `lockTimeoutMillis = 0` a lock expires at once.
5. **Takeover of an expired entry.** `tryLock` replaces it with a new `LockInfo` and a new token. The old holder's later `unLock` has a stale token, so it returns `false` and the new owner's lock stays.
6. **`unLock` on an expired entry the monitor has not yet removed.** If the token still matches, `unLock` removes the entry and returns `true`, because the token matches the current owner. `unLock` does not read the clock.
7. **`unLock` guard.** It removes the entry only if `token == existing.token` and `existing.isHeld.compareAndSet(true, false)` succeeds. It then calls `semaphore.release()`. Otherwise it returns the entry unchanged and `false`.
8. **Which `Semaphore`.** I used `kotlinx.coroutines.sync.Semaphore(1, 1)`, one permit already acquired, so a new entry starts held. The `java.util.concurrent` one would also fit. The semaphore carries no logic beyond `isHeld`; I kept it because the rule requires the field. I used `isHeld` as the real guard against double release.
9. **Token timing.** `nextToken()` runs inside the lambda, only when a token is granted (also for an over-cap permit). `compute` runs the lambda once, so no token numbers are skipped.
10. **Monitor start.** The companion `init` block starts one JVM-wide `newSingleThreadScheduledExecutor` with a daemon thread named `req-shield-local-lock-monitor`. It calls `scheduleWithFixedDelay` with `LOCK_MONITOR_INTERVAL_MILLIS` as both the first delay and the period. I wrapped the sweep in no `try/catch`, because the sweep lambda cannot throw in normal use. If `compute` threw, the scheduler would silently stop the monitor.
11. **Shared map across instances.** All `KeyLocalLock` instances share the companion map, so different instances lock against each other. Each entry stores its own `expiresAt`, so instances with different `lockTimeoutMillis` values coexist. The monitor does not need a timeout of its own.
12. **`LockInfo` placement.** It is a private nested class of `KeyLocalLock`, not a top-level private class. A top-level private class named `LockInfo` could clash with another file in the same package.
13. **Not handled.** I added no validation of `lockTimeoutMillis`. A huge value, such as `Long.MAX_VALUE`, makes `now + lockTimeoutMillis` overflow to a negative number, so the lock is treated as already expired. A negative value gives an already-expired lock. I did not add a saturating add, because the spec does not ask for one.
14. **No logging in this class.** `LocalLockLimit.rejectsNewEntry` does the WARN logging itself, so the class does not use SLF4J.
