## Result

The final file compiles with no diagnostics. I used 3 of the 10 `./compile.sh` calls, and call 3 ran on the final file. Compiling is the only check I ran, so nothing was tested.

File: `/private/tmp/claude-501/-Users-ykhfree-AI/9a7562f5-114a-4bd1-9a46-6cd88dafecdb/scratchpad/aicode/sandboxes/F-sonnet-2/KeyLocalLock.kt`

## Structure

- The lock map is `ConcurrentHashMap<String, LockEntry>` in a private companion object. It is shared by the whole JVM, as the spec requires.
- The key is `lockKeyOf(key, lockType)`.
- `LockEntry` holds `token` and `expiresAt`.
- `tryLock` and `unLock` decide inside `ConcurrentHashMap.compute`, so each decision is atomic per key. Each has a lock-free fast path before `compute`.
- The cleanup monitor is one daemon thread started in the companion `init`. It runs `removeExpiredEntries` every `LOCK_MONITOR_INTERVAL_MILLIS`.

## Design decisions the spec did not settle

1. **Expiry is stored per entry.**
   - Each entry stores `expiresAt = acquireTime + lockTimeoutMillis` of the instance that took the lock.
   - The map is shared but the timeout belongs to an instance. The monitor therefore cannot use one global timeout.
2. **An expired but uncleaned entry counts as free in `tryLock`.**
   - A new caller replaces it with a new token.
   - Without this, a lock would stay held for up to `timeout + 1 s`, because the monitor lags.
   - The replace does not change the map size, so the cap is not checked.
   - The old holder's token no longer matches, so its `unLock` returns false. This is the spec's "slow holder" case.
3. **`unLock` on an expired entry with a matching token removes the entry and returns false.**
   - I treat "expired" as "not held", as the `KeyLock` KDoc says. The result then does not depend on when the monitor runs.
   - A reviewer who wants true here only has to change the line `released = !current.isExpiredAt(now)`.
4. **Expiry boundary.** An entry is expired when `now >= expiresAt`, so the boundary counts as expired.
5. **Unbacked permit when the map is full.**
   - If the key has no entry and `LocalLockLimit.rejectsNewEntry(locks.mappingCount())` returns true, `tryLock` returns a fresh UUID token and inserts nothing.
   - `unLock` with that token returns false, because there is no entry to release. This also cannot release a later real holder.
   - The cap check runs inside `compute`, in the same atomic step as the "no entry" check. So no check-then-insert race can give two callers a permit for a key that has an entry.
   - Keys that already have an entry skip the cap check.
   - `rejectsNewEntry` writes its WARN log while the map's bin lock is held. This happens only on the first refusal and every 1000th one.
6. **Tokens** are `UUID.randomUUID().toString()`, which the spec leaves open.
7. **Monitor**
   - It uses `scheduleWithFixedDelay` rather than fixed rate. The sweep is idempotent, so it needs no catch-up runs.
   - It runs on a daemon thread, so it never keeps the JVM alive.
   - The task catches `Exception` and logs WARN. An uncaught exception would silently cancel all later runs.
   - It removes entries with `locks.remove(lockKey, entry)`, which compares by identity and only removes the same entry. A holder who replaced the entry during the sweep is not removed. This also avoids the JDK 8 iterator-remove race.
   - `locks` is declared before the `init` block that starts the monitor. This ordering matters, because the monitor reads `locks`.
8. **Time source.** I used the supplied `nowToEpochTime()` (wall clock), not `System.nanoTime()`.
9. **Overflow.** `expiresAt` saturates at `Long.MAX_VALUE`, so a huge timeout means "never expires".
10. **No input validation on `lockTimeoutMillis`.**
    - I added no `require`.
    - A value of 0 or less gives entries that are expired at once, so there is no mutual exclusion. I chose not to reject it because the spec does not say to.
11. **Other choices.**
    - I kept the Apache license header from `KeyLock.kt`.
    - Formatting follows the `KeyLock.kt` style: trailing commas, one parameter per line.
    - I kept lines to 120 columns or less. This is my own judgement, because I saw no ktlint config.
    - Neither `suspend` method suspends or blocks. They only do short, non-blocking `ConcurrentHashMap` operations.
