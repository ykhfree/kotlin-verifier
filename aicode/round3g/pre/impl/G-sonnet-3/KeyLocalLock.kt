package com.linecorp.cse.reqshield.kotlin.coroutine

import com.linecorp.cse.reqshield.support.config.LocalLockLimit
import com.linecorp.cse.reqshield.support.constant.ConfigValues.LOCK_MONITOR_INTERVAL_MILLIS
import com.linecorp.cse.reqshield.support.utils.nowToEpochTime
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * JVM-local [KeyLock]. One entry exists per (cache key, lock type) that is currently locked.
 * Each acquisition returns a token. Only that token can release the lock.
 */
class KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock {
    override suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): String? {
        val lockKey = lockKeyOf(key, lockType)
        val now = nowToEpochTime()
        val acquiredToken = AtomicReference<String?>(null)

        lockMap.compute(lockKey) { _, existing ->
            if (existing == null) {
                // The map is full: hand out a permit that no entry backs. The caller runs as if it holds the lock.
                if (LocalLockLimit.rejectsNewEntry(lockMap.size.toLong())) {
                    acquiredToken.set(newToken())
                    null
                } else {
                    val token = newToken()
                    acquiredToken.set(token)
                    LockInfo(Semaphore(1), now + lockTimeoutMillis, AtomicBoolean(true), token)
                }
            } else if (existing.isHeld.get() && existing.expiresAt > now) {
                // Another holder owns a lock that has not expired.
                existing
            } else {
                // The entry is released or expired but not yet removed: the new holder takes it over.
                val token = newToken()
                acquiredToken.set(token)
                existing.token = token
                existing.expiresAt = now + lockTimeoutMillis
                existing.isHeld.set(true)
                existing
            }
        }

        return acquiredToken.get()
    }

    override suspend fun unLock(
        key: String,
        lockType: LockType,
        token: String,
    ): Boolean {
        val lockKey = lockKeyOf(key, lockType)
        val released = AtomicBoolean(false)

        lockMap.compute(lockKey) { _, existing ->
            if (existing != null && existing.isHeld.get() && existing.token == token) {
                released.set(true)
                existing.isHeld.set(false)
                existing.token = null
                // Drop the entry as soon as the lock is released.
                null
            } else {
                existing
            }
        }

        return released.get()
    }

    private class LockInfo(
        val semaphore: Semaphore,
        @Volatile var expiresAt: Long,
        val isHeld: AtomicBoolean = AtomicBoolean(false),
        @Volatile var token: String? = null,
    )

    companion object {
        private val lockMap = ConcurrentHashMap<String, LockInfo>()
        private val tokenSequence = AtomicLong(0)

        private val monitor =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "key-local-lock-monitor").apply { isDaemon = true }
            }

        init {
            monitor.scheduleWithFixedDelay(
                { removeExpiredEntries() },
                LOCK_MONITOR_INTERVAL_MILLIS,
                LOCK_MONITOR_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }

        private fun newToken(): String = "local-${tokenSequence.incrementAndGet()}"

        private fun removeExpiredEntries() {
            try {
                for (lockKey in lockMap.keys.toList()) {
                    val now = System.currentTimeMillis()
                    lockMap.compute(lockKey) { _, existing ->
                        if (existing != null && existing.expiresAt <= now) null else existing
                    }
                }
            } catch (ignored: Exception) {
                // Keep the scheduled task alive: an escaped exception would cancel all later runs.
            }
        }
    }
}
