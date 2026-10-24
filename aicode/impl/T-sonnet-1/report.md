**Result:** `/private/tmp/claude-501/-Users-ykhfree-AI/9a7562f5-114a-4bd1-9a46-6cd88dafecdb/scratchpad/aicode/sandboxes/T-sonnet-1/KeyLocalLock.kt` compiles. `./compile.sh` printed "compiles (call 1 of 10 used)" with no diagnostics. Compiling is the only check I ran. I did not test or verify behavior.

**What the class does**
- `tryLock` makes the lock key with `lockKeyOf`, then reads `val now = nowToEpochTime()` before one `lockMap.compute`. The token, taken from `tokenSequence`, leaves the lambda through an `AtomicReference` declared before the call.
- `unLock` makes one `lockMap.compute`. It releases only if the entry is held and its token equals the given token. It returns the result through an `AtomicBoolean`. A successful release removes the entry (returns null from the lambda).
- The companion object holds `lockMap`, `tokenSequence` and the monitor, as RULES.md rules 1 and 5 say.
- The monitor runs on a daemon thread from `Executors.newSingleThreadScheduledExecutor`. It runs every `LOCK_MONITOR_INTERVAL_MILLIS` (1000 ms).
- For each key the monitor reads `System.currentTimeMillis()` and calls `lockMap.compute(key)` once.
- The lambdas contain no `synchronized`, no locks, no try/catch, and no other `ConcurrentHashMap` mutators.

**Decisions the specification did not settle**
1. **Token generation.** `tryLock` creates the token once, inline, as `"local-${tokenSequence.incrementAndGet()}"`. I used no helper function, so the line matches RULES.md rule 1 literally. The token is also created for the unbacked permit (see 3).
2. **Cap check position.** RULES.md rule 3 says `lockMap` may be read only inside the compute call. So `lockMap.size` is read inside the lambda. `LocalLockLimit.rejectsNewEntry(lockMap.size.toLong())` runs only when `existing == null`. A key that already has an entry, held or expired, is never subject to the cap and never causes a refusal log.
3. **Unbacked permit.** If the map is full and the key is new, the lambda returns null. The caller still gets a normal `local-N` token, and no entry is stored. `unLock` with that token returns false. If a real holder has since taken the key, the token does not match, so `unLock` leaves that holder's lock alone.
4. **Expiry rule.** `expiresAt = now + lockTimeoutMillis`. An entry is expired when `now >= expiresAt`. `tryLock` and the monitor both use this rule. With `lockTimeoutMillis = 0`, every lock is expired at once.
5. **Takeover of an expired entry.** `tryLock` replaces it with a new `LockInfo` that has a new `Semaphore(1)` and a new token. The old holder's token then no longer matches. A takeover of an existing key is not a new entry, so the cap does not apply.
6. **Release after expiry, before takeover or cleanup.** `unLock` does not check the clock. If the token matches and nobody has taken over, it returns true. Its result therefore does not depend on how long the entry has been expired. It does depend on whether the monitor has already removed the entry, which gives false.
7. **Semaphore.** RULES.md requires the field, so each granted entry gets a fresh `Semaphore(1)` that `tryAcquire()` takes once. `unLock` calls `release()` on it. The real ownership checks are `isHeld` and `token`. I used `java.util.concurrent.Semaphore`, not the kotlinx one, because `tryLock` and `unLock` need no suspension.
8. **Where `LockInfo` lives.** It is a `private class` nested in `KeyLocalLock`, outside the companion. This avoids a name clash with other files in the same package and lets the companion's `lockMap` use it. It matches the fields in RULES.md rule 2.
9. **Monitor lifecycle.** It starts in the companion `init`, once per JVM, on a daemon thread. Nothing stops it. It has no try/catch, because the lambda cannot throw.
10. **Not handled.** `now + lockTimeoutMillis` can overflow if the timeout is near `Long.MAX_VALUE`. I did not add a guard because the spec does not ask for one.
11. **`unLock` on an unknown key.** It makes no entry and returns false.
