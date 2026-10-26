package com.linecorp.cse.reqshield.kotlin.coroutine

import com.linecorp.cse.reqshield.support.config.LocalLockLimit
import com.linecorp.cse.reqshield.support.constant.ConfigValues.LOCK_MONITOR_INTERVAL_MILLIS
import com.linecorp.cse.reqshield.support.utils.nowToEpochTime
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private class LockInfo(
    val semaphore: Semaphore,
    @Volatile var expiresAt: Long,
    val isHeld: AtomicBoolean = AtomicBoolean(false),
    @Volatile var token: String? = null,
)

/** In-JVM [KeyLock]. One map entry per (key, lock type) that is locked now. */
class KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock {
    override suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): String? {
        val lockKey = lockKeyOf(key, lockType)
        val now = nowToEpochTime()
        // A timeout that would overflow the clock sum means "never expires".
        val expiresAt = if (lockTimeoutMillis > Long.MAX_VALUE - now) Long.MAX_VALUE else now + lockTimeoutMillis
        val acquired = AtomicReference<String?>(null)

        lockMap.compute(lockKey) { _, existing ->
            if (existing == null) {
                if (LocalLockLimit.rejectsNewEntry(lockMap.size.toLong())) {
                    // Map is full: hand out a token that no map entry backs.
                    acquired.set(newToken())
                    null
                } else {
                    acquired.set(newToken())
                    newEntry(expiresAt, acquired.get())
                }
            } else if (existing.isHeld.get() && existing.expiresAt > now) {
                existing
            } else {
                // The old holder never released and its time ran out: the new caller takes over.
                acquired.set(newToken())
                newEntry(expiresAt, acquired.get())
            }
        }
        return acquired.get()
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
                null
            } else {
                existing
            }
        }
        return released.get()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(KeyLocalLock::class.java)
        private val lockMap = ConcurrentHashMap<String, LockInfo>()
        private val tokenSequence = AtomicLong(0)

        init {
            val executor =
                Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "req-shield-local-lock-monitor").apply { isDaemon = true }
                }
            executor.scheduleWithFixedDelay(
                { removeExpiredEntries() },
                LOCK_MONITOR_INTERVAL_MILLIS,
                LOCK_MONITOR_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }

        private fun newToken(): String = "local-${tokenSequence.incrementAndGet()}"

        private fun newEntry(
            expiresAt: Long,
            token: String?,
        ): LockInfo {
            val semaphore = Semaphore(1)
            semaphore.tryAcquire()
            val info = LockInfo(semaphore, expiresAt)
            info.isHeld.set(true)
            info.token = token
            return info
        }

        private fun removeExpiredEntries() {
            // The loop catches errors so one failure does not stop later runs.
            try {
                for (mapKey in lockMap.keys) {
                    val now = System.currentTimeMillis()
                    lockMap.compute(mapKey) { _, existing ->
                        if (existing != null && existing.expiresAt <= now) null else existing
                    }
                }
            } catch (e: Exception) {
                logger.warn("Local lock monitor failed", e)
            }
        }
    }
}
