package com.linecorp.cse.reqshield.kotlin.coroutine

import com.linecorp.cse.reqshield.support.config.LocalLockLimit
import com.linecorp.cse.reqshield.support.constant.ConfigValues.LOCK_MONITOR_INTERVAL_MILLIS
import com.linecorp.cse.reqshield.support.utils.nowToEpochTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private class LockInfo(
    val semaphore: Semaphore,
    @Volatile var expiresAt: Long,
    val isHeld: AtomicBoolean = AtomicBoolean(false),
    @Volatile var token: String? = null,
)

class KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock {
    override suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): String? {
        val lockKey = lockKeyOf(key, lockType)
        val now = nowToEpochTime()
        val newToken = nextToken()
        val acquired = AtomicBoolean(false)
        val unbacked = AtomicBoolean(false)

        lockMap.compute(lockKey) { _, existing ->
            if (existing == null) {
                if (LocalLockLimit.rejectsNewEntry(lockMap.size.toLong())) {
                    // The map is full: hand out a permit that no entry backs.
                    unbacked.set(true)
                    null
                } else {
                    val info = LockInfo(Semaphore(1), now + lockTimeoutMillis)
                    info.semaphore.tryAcquire()
                    info.isHeld.set(true)
                    info.token = newToken
                    acquired.set(true)
                    info
                }
            } else if (existing.isHeld.get() && now < existing.expiresAt) {
                // Another holder owns a live lock.
                existing
            } else {
                // Free or expired entry: the new holder takes it over. The semaphore permit stays taken.
                existing.expiresAt = now + lockTimeoutMillis
                existing.token = newToken
                existing.isHeld.set(true)
                acquired.set(true)
                existing
            }
        }

        return if (acquired.get() || unbacked.get()) newToken else null
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

        private fun nextToken(): String = "local-${tokenSequence.incrementAndGet()}"

        init {
            val monitor =
                Thread {
                    while (true) {
                        Thread.sleep(LOCK_MONITOR_INTERVAL_MILLIS)
                        removeExpired()
                    }
                }
            monitor.isDaemon = true
            monitor.name = "key-local-lock-monitor"
            monitor.start()
        }

        private fun removeExpired() {
            for (lockKey in lockMap.keys) {
                val now = com.linecorp.cse.reqshield.support.utils.FakeClock.sweepNow()
                lockMap.compute(lockKey) { _, existing ->
                    if (existing != null && existing.expiresAt <= now) null else existing
                }
            }
        }
    }
}
