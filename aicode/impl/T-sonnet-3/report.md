**Result:** `KeyLocalLock.kt` compiles. The one `./compile.sh` call (1 of 10 used) printed no errors or warnings. I did not run it at runtime, because `compile.sh` only compiles and I may not read outside the sandbox. I checked the logic by reading it.

File: /private/tmp/claude-501/-Users-ykhfree-AI/9a7562f5-114a-4bd1-9a46-6cd88dafecdb/scratchpad/aicode/sandboxes/T-sonnet-3/KeyLocalLock.kt

I did not call the advisor, to keep to the "no other agents" instruction.

**How the rules are met**
- `lockMap`, `tokenSequence` and the monitor are in the companion object. `LockInfo` is exactly the class from RULES.md.
- Tokens are `"local-${tokenSequence.incrementAndGet()}"`.
- `tryLock` and `unLock` each read `val now = nowToEpochTime()` first. Each then calls `lockMap.compute(lockKey) { _, existing -> ... }` once. Results leave the lambda through an `AtomicReference<String?>` or an `AtomicBoolean`.
- The monitor reads `val now = System.currentTimeMillis()` per key and calls `compute(key)` once per key.
- No `synchronized`, locks or `try/catch` anywhere. The lambdas use no other `ConcurrentHashMap` mutator.

**Design decisions the specification did not settle**
1. **Map key.** The map key is `lockKeyOf(key, lockType)`, so one entry exists per (cache key, lock type).
2. **When a lock is held.** An entry blocks `tryLock` only if `isHeld` is true and `now < expiresAt`. An expired entry that the monitor has not yet removed is free, so `tryLock` takes it over at once and does not wait up to 1 s for the monitor. This matches the "slow holder whose lock already expired" text.
3. **Expiry boundary.** A lock is valid for `[acquire, acquire + lockTimeoutMillis)`. It is expired when `now >= expiresAt`. `tryLock`, `unLock` and the monitor all use this same test.
4. **Takeover.** Takeover builds a fresh `LockInfo` (new `Semaphore(1)` with its permit taken, `isHeld = true`, new token, `expiresAt = now + lockTimeoutMillis`). It does not change the old entry in place. So the `var` fields `expiresAt` and `token` are never written after construction.
5. **`unLock` on an expired lock.** If the token matches but the lock has expired, `unLock` drops the entry and returns `false`. I treat an expired lock as not held. This keeps the result independent of when the monitor runs. A literal reading of "releases when the token matches" could return `true` here.
6. **`unLock` on a valid match.** It returns `true`. It sets `isHeld` to false and releases the semaphore permit before dropping the entry. Those two writes only keep the discarded entry consistent. The entry is removed by returning `null`, as the specification says ("dropped as soon as the lock is released").
7. **`unLock` in other cases.** It returns `false` for no entry, a token mismatch, or an unbacked token. An unbacked token is one issued while the map was full.
8. **Cap.** The cap applies only when `existing == null`. Then `LocalLockLimit.rejectsNewEntry(lockMap.size.toLong())` is called inside the compute lambda. This keeps every map read inside `compute`, as rule 3 requires. If it returns true, `tryLock` returns a fresh `local-N` token and the lambda returns `null`, so no entry is created. An expired entry being taken over is not subject to the cap. A token is consumed from the sequence on every success, including a capped success.
9. **Semaphore type.** I used `java.util.concurrent.Semaphore`, not `kotlinx.coroutines.sync.Semaphore`, because every other field type is from `java.util.concurrent`. Nothing ever waits on it. It is only acquired on creation and released on `unLock`.
10. **`LockInfo` placement.** It is a `private` nested class inside `KeyLocalLock`, not file-level.
11. **Monitor.**
    - It starts in the companion `init` block, once per JVM. It is a single-thread `ScheduledExecutorService` with a daemon thread named `req-shield-local-lock-monitor`.
    - It runs with `scheduleAtFixedRate` at `LOCK_MONITOR_INTERVAL_MILLIS`, using the same value as the first delay.
    - It uses each entry's own `expiresAt`, so instances with different timeouts that share the map work correctly.
12. **Not added.**
    - `lockTimeoutMillis` is not validated. A value near `Long.MAX_VALUE` would overflow `now + lockTimeoutMillis` and make the lock expire at once.
    - There is no `try/catch` in the monitor sweep, so a throw would stop later runs. The sweep body cannot throw as written.
    - `tryLock` and `unLock` are `suspend` but never suspend.
13. **Style.** I used the same Apache header and the same trailing-comma, multi-line parameter style as `KeyLock.kt` (ktlint-style).
