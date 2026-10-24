**Result:** `/private/tmp/claude-501/-Users-ykhfree-AI/9a7562f5-114a-4bd1-9a46-6cd88dafecdb/scratchpad/aicode/sandboxes/F-sonnet-1/KeyLocalLock.kt` compiles. `./compile.sh` printed "compiles" and used 1 of 10 calls. Nothing was run or tested beyond compiling. I did not use the advisor tool, because the task said no other agents.

I wrote the first version of the monitor's cleanup loop wrongly. It removed every entry whether or not it had expired. I caught this before the first compile call and fixed it, and the compiled file has the fix.

**What the file does**
- `tryLock` uses `ConcurrentHashMap.compute` for each key. The map is in a private companion object, so every `KeyLocalLock` instance in the JVM shares it.
- `tryLock` returns a random UUID string as the token.
- If another holder's entry is still active, `tryLock` returns null.
- If the key is new and `LocalLockLimit.rejectsNewEntry(locks.size.toLong())` says the map is full, `tryLock` stores no entry and still returns a token. This is the "permit no map entry backs".
- `unLock` removes the entry only if the token matches and the entry has not expired. The removal compares the exact entry object (`locks.remove(key, entry)`), so a newer holder's entry is never removed.
- A daemon thread starts once, when the companion object loads. It runs every `LOCK_MONITOR_INTERVAL_MILLIS` and drops expired entries.

**Design decisions the spec did not settle**
1. **Expired but not yet cleaned entries count as free.** `tryLock` takes over such an entry atomically. The spec only says the monitor "can lag by up to 1 s". I did not want a lock to last up to 1 s longer than `lockTimeoutMillis`. The monitor therefore only frees memory.
2. **`unLock` on an expired entry returns false, even when the token matches.** It leaves the entry for the monitor. Otherwise the result would depend on whether the monitor had already run.
3. **`unLock` on an unbacked permit returns false.** No entry exists, so the lock is "not held". The spec does not say what such a permit's release should return.
4. **The size cap applies only when `tryLock` finds no entry for the key.** I call `rejectsNewEntry` only in that case, because it counts and logs every refusal. Keys already in the map are never counted. Taking over an expired entry is not "new" either, since the map does not grow.
5. **Each entry stores its own acquire time and timeout.** Instances created with different `lockTimeoutMillis` can then share the map. The expiry test is `now - acquiredAt >= timeout`, which cannot overflow when the timeout is very large.
6. **I used `nowToEpochTime()` (wall clock) for all time checks.** This is the project helper. A backward clock jump would extend lock lifetimes. `System.nanoTime` would be monotonic, but I kept the project convention.
7. **I added no validation of `lockTimeoutMillis`.** A zero or negative value makes every lock expire at once, and the spec asks for no check.
8. **The monitor catches `Exception` and logs it at WARN.** A scheduled task that throws is never run again, and I did not want one failure to stop all cleanup. It does not catch `Error`.
9. **The monitor thread is a daemon named `req-shield-local-lock-monitor`.** It is never stopped. It starts when the class loads, even if no lock is ever taken. In an app server that reloads classes, this thread will leak.
10. **The token is a `UUID.randomUUID()` string.** It is created before the map lookup, so a failed `tryLock` also pays for it. I chose simplicity over a fast path.
11. **The class is not reentrant.** A second `tryLock` on the same key by the same caller returns null.
12. **`LockEntry` has no `equals`.** I did this on purpose so identity-based `remove(key, value)` is exact.

The suspend functions never suspend, because the map operations are short and non-blocking.
