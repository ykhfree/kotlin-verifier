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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * In-JVM [KeyLock]. All instances share one lock map, so two instances lock the same key against each other.
 *
 * The map holds one entry per locked (key, lock type). A release removes the entry at once.
 * A lock that its holder never released expires after [lockTimeoutMillis]. After that time,
 * the next [tryLock] takes the lock over, or the cleanup monitor removes the entry.
 */
class KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock {
    override suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): String? {
        val lockKey = lockKeyOf(key, lockType)
        val now = nowToEpochTime()
        val grantedToken = AtomicReference<String?>(null)

        lockMap.compute(lockKey) { _, existing ->
            if (existing != null && now < existing.expiresAt) {
                // A live holder owns the lock. Keep its entry and grant nothing.
                existing
            } else {
                val token = "local-${tokenSequence.incrementAndGet()}"
                grantedToken.set(token)
                if (existing == null && LocalLockLimit.rejectsNewEntry(lockMap.size.toLong())) {
                    // The map is full. The caller gets a permit that no entry backs.
                    null
                } else {
                    // The key is free, or the old holder's lock expired. Install a new entry.
                    val info = LockInfo(Semaphore(1), now + lockTimeoutMillis, AtomicBoolean(true), token)
                    info.semaphore.tryAcquire()
                    info
                }
            }
        }

        return grantedToken.get()
    }

    override suspend fun unLock(
        key: String,
        lockType: LockType,
        token: String,
    ): Boolean {
        val released = AtomicBoolean(false)

        lockMap.compute(lockKeyOf(key, lockType)) { _, existing ->
            if (existing != null && existing.isHeld.get() && existing.token == token) {
                existing.isHeld.set(false)
                existing.token = null
                existing.semaphore.release()
                released.set(true)
                // Remove the entry as soon as its owner releases it.
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

        private class LockInfo(
            val semaphore: Semaphore,
            @Volatile var expiresAt: Long,
            val isHeld: AtomicBoolean = AtomicBoolean(false),
            @Volatile var token: String? = null,
        )

        private val monitor =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "req-shield-local-lock-monitor").apply { isDaemon = true }
            }

        init {
            monitor.scheduleWithFixedDelay(
                { removeExpiredLocks() },
                LOCK_MONITOR_INTERVAL_MILLIS,
                LOCK_MONITOR_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }

        /** Removes every entry whose holder did not release it before it expired. */
        private fun removeExpiredLocks() {
            for (lockKey in lockMap.keys) {
                val now = System.currentTimeMillis()
                lockMap.compute(lockKey) { _, existing ->
                    if (existing != null && now >= existing.expiresAt) null else existing
                }
            }
        }
    }
}
