**Result:** `/private/tmp/claude-501/-Users-ykhfree-AI/9a7562f5-114a-4bd1-9a46-6cd88dafecdb/scratchpad/aicode/sandboxes/F-sonnet-3/KeyLocalLock.kt` compiles. `./compile.sh` printed "compiles" on the first call, so I used 1 of the 10 calls. It compiles only. I wrote no tests and ran nothing, so the behavior is unverified.

**How it is built**
- `KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock` keeps a `ConcurrentHashMap<String, LockEntry>` in a private companion object. The key is `lockKeyOf(key, lockType)`.
- `tryLock` and `unLock` use `compute` and `computeIfPresent`. The check and the update are atomic for each key, and no code blocks.
- A daemon `ScheduledExecutorService` sweeps the map every `LOCK_MONITOR_INTERVAL_MILLIS`. It removes expired entries with `values.removeIf`, which only removes an entry that still has the value the filter checked. A fresh entry that replaced an expired one in the meantime is kept. The sweep catches `Exception` and logs it at WARN, because a scheduled task that throws never runs again.
- `LockEntry` is a private top-level class holding the token, the time it was acquired and its own timeout.

**Decisions the specification did not settle**
1. **Shared map.** The spec says "a map shared by the whole JVM", so the map and the monitor are static. All `KeyLocalLock` instances share them. Two instances with the same key and lock type exclude each other.
2. **Per-entry timeout.** Each entry stores its own `lockTimeoutMillis`, because instances with different timeouts share the map.
3. **Expired entries count as free in `tryLock`.** The monitor can lag by up to 1 s. Without this rule the lock would last up to `timeout + 1 s`. This rule also lets the "next holder" in the spec acquire the lock right after expiry. The new holder replaces the expired entry, and the cap does not apply to that replacement.
4. **Expiry test.** An entry is expired when `now - acquiredAt >= timeout`. The elapsed time is compared, not an end time, so a huge timeout such as `Long.MAX_VALUE` cannot overflow. A timeout of 0 or less makes every entry expire at once, so the lock never excludes anyone. I do not validate the value and the constructor never throws. I left it unvalidated because the spec says a bad tuning value should not stop the application from starting.
5. **Clock.** Expiry uses `nowToEpochTime()`, the wall clock the helpers provide. A clock jump can expire an entry early or late. A monotonic clock would avoid this, but I kept to the sanctioned helper.
6. **Cap handling.**
   - The cap check runs only when the key has no entry. It runs inside `compute`, with `locks.mappingCount()` as the size. A contended or replaced key never calls `rejectsNewEntry`, so it neither counts as a refusal nor gets logged as one.
   - When the map is full, `tryLock` returns a fresh random token and stores no entry. The next `tryLock` for that key is checked against the cap again, so no collapsing happens while the map stays full.
   - `rejectsNewEntry` logs from inside the bin lock. It does this only for rate-limited refusals.
7. **`unLock` return values.**
   - It returns `true` only when it removes the entry that the token owns.
   - It returns `false` when there is no entry, the token does not match, or the entry has expired.
   - It also removes an expired entry, whatever token the caller gives. The result is then the same whether or not the monitor already dropped it, and it matches TTL-based global locks.
   - A token from an unbacked permit therefore always gets `false`. The interface says "false when not held", so I kept it. If callers log WARN on `false`, they will see noise while the map is full.
8. **Tokens.** `UUID.randomUUID().toString()`, created only when a permit is granted. Callers that lose the race to hold the lock pay nothing for it.
9. **Monitor.** It starts when the companion object loads, which is on first use of the class. It uses one daemon thread named `req-shield-local-lock-monitor`. I chose a thread over a coroutine, so the monitor does not depend on any dispatcher or test scheduler.
10. **Style.** Formatting follows ktlint conventions (trailing commas, sorted imports, no wildcard imports). I did not run ktlint.

I did not use the advisor tool, to stay within the "no other agents" constraint. Nothing else is left open.
