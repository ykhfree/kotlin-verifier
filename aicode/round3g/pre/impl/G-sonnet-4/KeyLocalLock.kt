package com.linecorp.cse.reqshield.kotlin.coroutine

import com.linecorp.cse.reqshield.support.config.LocalLockLimit
import com.linecorp.cse.reqshield.support.constant.ConfigValues.LOCK_MONITOR_INTERVAL_MILLIS
import com.linecorp.cse.reqshield.support.utils.nowToEpochTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

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
                // New key: the cap is checked only here, so keys already in the map are never refused.
                val token = nextToken()
                acquiredToken.set(token)
                if (LocalLockLimit.rejectsNewEntry(lockMap.size.toLong())) {
                    // Cap reached: hand out a token that no map entry backs.
                    null
                } else {
                    val info = LockInfo(Semaphore(1), now + lockTimeoutMillis)
                    info.semaphore.tryAcquire()
                    info.isHeld.set(true)
                    info.token = token
                    info
                }
            } else if (!existing.isHeld.get()) {
                // Entry exists but nobody holds it: take it.
                val token = nextToken()
                if (existing.semaphore.tryAcquire()) {
                    existing.isHeld.set(true)
                    existing.token = token
                    existing.expiresAt = now + lockTimeoutMillis
                    acquiredToken.set(token)
                }
                existing
            } else if (existing.expiresAt <= now) {
                // The holder missed its timeout: the new caller takes over. The semaphore stays acquired.
                val token = nextToken()
                existing.token = token
                existing.expiresAt = now + lockTimeoutMillis
                acquiredToken.set(token)
                existing
            } else {
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
                existing.isHeld.set(false)
                existing.token = null
                existing.semaphore.release()
                released.set(true)
                // Drop the entry as soon as it is released.
                null
            } else {
                existing
            }
        }

        return released.get()
    }

    private companion object {
        private val lockMap = ConcurrentHashMap<String, LockInfo>()
        private val tokenSequence = AtomicLong(0)

        init {
            val monitor =
                Executors.newSingleThreadScheduledExecutor { runnable ->
                    val thread = Thread(runnable, "key-local-lock-monitor")
                    thread.isDaemon = true
                    thread
                }
            monitor.scheduleWithFixedDelay(
                { removeExpiredEntries() },
                LOCK_MONITOR_INTERVAL_MILLIS,
                LOCK_MONITOR_INTERVAL_MILLIS,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        }

        private fun nextToken(): String = "local-${tokenSequence.incrementAndGet()}"

        private fun removeExpiredEntries() {
            for (lockKey in lockMap.keys) {
                val now = System.currentTimeMillis()
                lockMap.compute(lockKey) { _, existing ->
                    if (existing != null && existing.expiresAt <= now) null else existing
                }
            }
        }
    }

    private class LockInfo(
        val semaphore: Semaphore,
        @Volatile var expiresAt: Long,
        val isHeld: AtomicBoolean = AtomicBoolean(false),
        @Volatile var token: String? = null,
    )
}
