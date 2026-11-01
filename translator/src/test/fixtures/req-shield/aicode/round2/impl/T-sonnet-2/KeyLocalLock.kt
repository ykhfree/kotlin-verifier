/*
 *  Copyright 2024 LY Corporation
 *
 *  LY Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

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
 * In-JVM [KeyLock]. All instances share one lock map, so two instances lock the same
 * `(key, lockType)` pair against each other.
 */
class KeyLocalLock(
    private val lockTimeoutMillis: Long,
) : KeyLock {
    /** Returns a token when the caller now owns the lock, or null when another live holder owns it. */
    override suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): String? {
        val lockKey = lockKeyOf(key, lockType)
        val now = nowToEpochTime()
        val acquiredToken = AtomicReference<String?>(null)

        lockMap.compute(lockKey) { _, existing ->
            if (existing != null && !isExpired(existing, now)) {
                // A live holder owns the lock. Keep its entry.
                existing
            } else if (existing == null && LocalLockLimit.rejectsNewEntry(lockMap.size.toLong())) {
                // The map is full. Hand out a permit that no map entry backs.
                acquiredToken.set(nextToken())
                null
            } else {
                // The key is new, or its holder let the lock expire. Install a held entry for the caller.
                val token = nextToken()
                acquiredToken.set(token)
                // Semaphore(1, 1): one permit, already acquired, so the entry starts out held.
                LockInfo(Semaphore(1, 1), now + lockTimeoutMillis, AtomicBoolean(true), token)
            }
        }

        return acquiredToken.get()
    }

    /** Removes the entry and returns true only when [token] is the token of the entry's current owner. */
    override suspend fun unLock(
        key: String,
        lockType: LockType,
        token: String,
    ): Boolean {
        val lockKey = lockKeyOf(key, lockType)
        val released = AtomicBoolean(false)

        lockMap.compute(lockKey) { _, existing ->
            if (existing != null && existing.token == token && existing.isHeld.compareAndSet(true, false)) {
                existing.semaphore.release()
                released.set(true)
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

        init {
            // A daemon thread, so the monitor never keeps the JVM alive.
            val monitor =
                Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "req-shield-local-lock-monitor").apply { isDaemon = true }
                }
            monitor.scheduleWithFixedDelay(
                { removeExpiredEntries() },
                LOCK_MONITOR_INTERVAL_MILLIS,
                LOCK_MONITOR_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }

        private fun nextToken(): String = "local-${tokenSequence.incrementAndGet()}"

        /** A lock is expired from the moment [now] reaches its `expiresAt`. */
        private fun isExpired(
            info: LockInfo,
            now: Long,
        ): Boolean = now >= info.expiresAt

        /** Drops every entry whose holder never released it before the timeout. */
        private fun removeExpiredEntries() {
            for (key in lockMap.keys) {
                val now = System.currentTimeMillis()
                lockMap.compute(key) { _, existing ->
                    if (existing != null && isExpired(existing, now)) null else existing
                }
            }
        }
    }
}
