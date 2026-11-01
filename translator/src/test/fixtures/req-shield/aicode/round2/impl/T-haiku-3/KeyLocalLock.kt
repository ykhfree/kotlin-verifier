package com.linecorp.cse.reqshield.kotlin.coroutine

import com.linecorp.cse.reqshield.support.config.LocalLockLimit
import com.linecorp.cse.reqshield.support.constant.ConfigValues.LOCK_MONITOR_INTERVAL_MILLIS
import com.linecorp.cse.reqshield.support.utils.nowToEpochTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Lock for one JVM. All instances share one lock map held in the companion object.
 * A granted lock expires [lockTimeoutMillis] after [tryLock] grants it.
 */
class KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock {
    /** Returns an ownership token when the lock is granted, or null when another live holder owns it. */
    override suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): String? {
        val mapKey = lockKeyOf(key, lockType)
        val now = nowToEpochTime()
        val granted = AtomicReference<String?>(null)
        lockMap.compute(mapKey) { _, existing ->
            if (existing != null && existing.expiresAt > now) {
                // Another holder owns a live lock. Keep its entry and grant nothing.
                existing
            } else if (existing == null && LocalLockLimit.rejectsNewEntry(lockMap.size.toLong())) {
                // The map is full. Grant a token that no entry backs, so the caller runs without a lock entry.
                granted.set(nextToken())
                null
            } else {
                // The key is free, or its entry expired. Take the lock and store a new entry.
                val token = nextToken()
                granted.set(token)
                LockInfo(
                    semaphore = Semaphore(1).also { it.tryAcquire() },
                    expiresAt = now + lockTimeoutMillis,
                    isHeld = AtomicBoolean(true),
                    token = token,
                )
            }
        }
        return granted.get()
    }

    /** Releases the lock only when [token] matches the live owner. Returns false for an expired or unknown lock. */
    override suspend fun unLock(
        key: String,
        lockType: LockType,
        token: String,
    ): Boolean {
        val mapKey = lockKeyOf(key, lockType)
        val now = nowToEpochTime()
        val released = AtomicBoolean(false)
        lockMap.compute(mapKey) { _, existing ->
            if (existing != null && existing.expiresAt > now && existing.token == token) {
                existing.semaphore.release()
                released.set(true)
                // Drop the entry on release.
                null
            } else {
                existing
            }
        }
        return released.get()
    }

    companion object {
        private val lockMap = ConcurrentHashMap<String, LockInfo>()
        private val tokenSequence = AtomicLong(0)

        // Starts one daemon thread per JVM. It drops expired entries on each tick.
        init {
            Thread {
                while (true) {
                    Thread.sleep(LOCK_MONITOR_INTERVAL_MILLIS)
                    dropExpiredEntries()
                }
            }.apply {
                name = "req-shield-local-lock-monitor"
                isDaemon = true
                start()
            }
        }

        private fun nextToken() = "local-${tokenSequence.incrementAndGet()}"

        /** Removes each entry whose lock expired. An entry is live while its expiry is after the current time. */
        private fun dropExpiredEntries() {
            for (key in lockMap.keys) {
                val now = System.currentTimeMillis()
                lockMap.compute(key) { _, existing -> existing?.takeIf { it.expiresAt > now } }
            }
        }
    }
}

/** One lock entry. [expiresAt] is in epoch milliseconds. */
private class LockInfo(
    val semaphore: Semaphore,
    @Volatile var expiresAt: Long,
    val isHeld: AtomicBoolean = AtomicBoolean(false),
    @Volatile var token: String? = null,
)
