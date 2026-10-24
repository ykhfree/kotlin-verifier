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
 * Lock that works within one JVM. All instances share one lock map, so they exclude each other.
 * A lock expires [lockTimeoutMillis] after its acquisition.
 */
class KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock {
    override suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): String? {
        val lockKey = lockKeyOf(key, lockType)
        val now = nowToEpochTime()
        val token = "local-${tokenSequence.incrementAndGet()}"
        val grantedToken = AtomicReference<String?>(null)
        lockMap.compute(lockKey) { _, existing ->
            when {
                existing == null -> {
                    grantedToken.set(token)
                    // Past the size cap, the caller gets a token that no entry backs.
                    if (LocalLockLimit.rejectsNewEntry(lockMap.size.toLong())) {
                        null
                    } else {
                        LockInfo(
                            semaphore = Semaphore(1).apply { tryAcquire() },
                            expiresAt = now + lockTimeoutMillis,
                            isHeld = AtomicBoolean(true),
                            token = token,
                        )
                    }
                }
                existing.isExpired(now) -> {
                    // Take over the expired lock. The old holder's token stops matching.
                    existing.token = token
                    existing.expiresAt = now + lockTimeoutMillis
                    grantedToken.set(token)
                    existing
                }
                // The lock is held and not expired, so the entry stays and the caller gets null.
                else -> existing
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
            if (existing == null || existing.token != token) {
                existing
            } else {
                existing.semaphore.release()
                released.set(true)
                // Returning null removes the entry.
                null
            }
        }
        return released.get()
    }

    companion object {
        private val tokenSequence = AtomicLong(0)
        private val lockMap = ConcurrentHashMap<String, LockInfo>()

        init {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "req-shield-local-lock-monitor").apply { isDaemon = true }
            }.scheduleAtFixedRate(
                { removeExpiredEntries() },
                LOCK_MONITOR_INTERVAL_MILLIS,
                LOCK_MONITOR_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }

        /** Drops each entry whose lock has expired. Each key gets one compute call. */
        private fun removeExpiredEntries() {
            for (key in lockMap.keys) {
                val now = System.currentTimeMillis()
                lockMap.compute(key) { _, existing ->
                    if (existing != null && existing.isExpired(now)) null else existing
                }
            }
        }
    }
}

/** One lock entry. The map holds it from acquisition until unLock or the monitor removes it. */
private class LockInfo(
    val semaphore: Semaphore,
    @Volatile var expiresAt: Long,
    val isHeld: AtomicBoolean = AtomicBoolean(false),
    @Volatile var token: String? = null,
) {
    fun isExpired(now: Long): Boolean = now > expiresAt
}
