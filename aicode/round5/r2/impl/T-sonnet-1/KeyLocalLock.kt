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
 * A lock expires [lockTimeoutMillis] after it was taken. Another caller can take over an expired lock.
 * The old holder then fails to release it, because its token no longer matches.
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
            if (existing != null && existing.isHeld.get() && now < existing.expiresAt) {
                // Another holder owns an unexpired lock. Keep its entry as it is.
                existing
            } else {
                val token = "local-${tokenSequence.incrementAndGet()}"
                grantedToken.set(token)
                if (existing == null && LocalLockLimit.rejectsNewEntry(lockMap.size.toLong())) {
                    // The map is full. The caller gets a permit that no map entry backs.
                    null
                } else {
                    // The key is free, or its old lock expired. Install a new entry for the new holder.
                    val info = LockInfo(Semaphore(1), now + lockTimeoutMillis)
                    info.semaphore.tryAcquire()
                    info.isHeld.set(true)
                    info.token = token
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
        val lockKey = lockKeyOf(key, lockType)
        val released = AtomicBoolean(false)

        lockMap.compute(lockKey) { _, existing ->
            if (existing != null && existing.isHeld.get() && existing.token == token) {
                existing.semaphore.release()
                existing.isHeld.set(false)
                existing.token = null
                released.set(true)
                // Drop the entry as soon as its holder releases it.
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

        private fun removeExpiredEntries() {
            for (lockKey in lockMap.keys) {
                val now = System.currentTimeMillis()
                lockMap.compute(lockKey) { _, existing ->
                    if (existing != null && now >= existing.expiresAt) {
                        null
                    } else {
                        existing
                    }
                }
            }
        }
    }
}
