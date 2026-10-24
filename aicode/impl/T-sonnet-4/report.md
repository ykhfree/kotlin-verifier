**Result:** `/private/tmp/claude-501/-Users-ykhfree-AI/9a7562f5-114a-4bd1-9a46-6cd88dafecdb/scratchpad/aicode/sandboxes/T-sonnet-4/KeyLocalLock.kt` compiles with `./compile.sh`. It compiled on the first call (1 of 10 used) with no warnings. I did not test it, and compile.sh does not test it either.

**How the rules are met**
- The companion object holds `lockMap`, `tokenSequence`, the private `LockInfo` class (exactly as in RULES.md), and the monitor.
- Each of `tryLock` and `unLock` makes exactly one `lockMap.compute(lockKeyOf(key, lockType)) { _, existing -> ... }` call. Results leave the lambda through an `AtomicReference<String?>` (tryLock) or an `AtomicBoolean` (unLock).
- `tryLock` reads `val now = nowToEpochTime()` before its compute call. The monitor reads `val now = System.currentTimeMillis()` per key and calls compute once per key.
- The compute lambdas contain no `synchronized`, no locks and no try/catch. `lockMap` is changed only through `compute`.
- The token is `"local-${tokenSequence.incrementAndGet()}"`.

**Design decisions the spec did not settle**
1. **An expired lock is taken over by `tryLock`.**
   - If an entry exists and `now >= expiresAt`, `tryLock` replaces it with a fresh `LockInfo` and a new token, without waiting for the monitor.
   - I chose this because the monitor can lag by up to 1 s, and the spec says a slow holder's lock "already expired".
   - The old holder's later `unLock` then gets a token mismatch and returns false.
   - The cap does not apply to a takeover, because the key already has an entry.
2. **Expiry boundary.** An entry is live while `now < expiresAt`. It is expired when `now >= expiresAt`. `tryLock` and the monitor use this same test, with `expiresAt = now + lockTimeoutMillis`. I added no validation of `lockTimeoutMillis`, so a value of 0 or less makes every lock expire at once.
3. **A permit with no entry returns a token, but its `unLock` returns false.**
   - When `LocalLockLimit.rejectsNewEntry(lockMap.size.toLong())` is true for a key with no entry, `tryLock` returns a normal `local-N` token and stores nothing.
   - `unLock` with that token finds no entry, or an entry with a different token, so it returns false. This follows the KeyLock KDoc, "false when not held".
   - A caller that ignores the return value is not affected.
   - The cap check runs only when `existing == null`, and inside the compute lambda, so the code never reads `lockMap` outside compute.
4. **`unLock` does not check expiry.**
   - If the entry is present and the token matches, `unLock` removes the entry and returns true, even if the lock has expired but nobody took it over and the monitor has not removed it yet.
   - It releases only on a token match and while `isHeld` is true.
5. **Token creation.** A token is created only when the caller is granted a lock or a permit. A failed `tryLock` does not use up a sequence number. I create it inside the lambda in one place, so there is no duplicate code or helper function.
6. **`Semaphore` type.** I used `java.util.concurrent.Semaphore(1)`, because compute lambdas cannot suspend.
   - A new entry calls `tryAcquire()` and starts with `isHeld = AtomicBoolean(true)`.
   - A release sets `isHeld = false`, clears the token, calls `release()`, and returns null so the entry is removed.
   - Both are mostly bookkeeping, because an entry exists only while its lock is held.
7. **`LockInfo` placement.** It is a `private class` nested inside the companion object, next to `lockMap`. The outer class can use it, and the compiler accepted that.
8. **Monitor implementation.**
   - It is a single-thread `ScheduledExecutorService` with a daemon thread named `req-shield-local-lock-monitor`.
   - It starts in the companion `init` block, after `lockMap` is initialized.
   - It runs `scheduleWithFixedDelay` with an initial delay and period of `LOCK_MONITOR_INTERVAL_MILLIS`.
   - It removes any entry with `now >= expiresAt`. The expiry time is stored per entry, so the shared monitor does not need the instance's `lockTimeoutMillis`.
   - I added no try/catch around the task. An uncaught `Throwable` would stop later runs.
9. **Cap size read.** I used `lockMap.size.toLong()` as `currentSize`, not `mappingCount()`.
10. **License header.** I copied the Apache header from `KeyLock.kt` to match the other file. I also added English KDoc and comments in short sentences.
