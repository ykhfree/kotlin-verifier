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
 * In-JVM lock. Holds one entry per locked (key, lock type) pair in a map shared by all instances.
 * An entry expires [lockTimeoutMillis] after its acquisition.
 */
class KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock {

    override suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): String? {
        val mapKey = lockKeyOf(key, lockType)
        val now = nowToEpochTime()
        val token = "local-${tokenSequence.incrementAndGet()}"
        val granted = AtomicReference<String?>(null)
        lockMap.compute(mapKey) { _, existing ->
            when {
                // Held and not expired: another holder owns the lock.
                existing != null && now < existing.expiresAt -> existing
                // New key over the size cap: grant a permit that no map entry backs.
                existing == null && LocalLockLimit.rejectsNewEntry(lockMap.size.toLong()) -> {
                    granted.set(token)
                    null
                }
                // Free, or held by an expired holder: take over with a new token.
                else -> {
                    granted.set(token)
                    LockInfo(
                        semaphore = Semaphore(1).apply { tryAcquire() },
                        expiresAt = now + lockTimeoutMillis,
                        isHeld = AtomicBoolean(true),
                        token = token,
                    )
                }
            }
        }
        return granted.get()
    }

    override suspend fun unLock(
        key: String,
        lockType: LockType,
        token: String,
    ): Boolean {
        val mapKey = lockKeyOf(key, lockType)
        val now = nowToEpochTime()
        val released = AtomicBoolean(false)
        lockMap.compute(mapKey) { _, existing ->
            // Release only a live entry whose token matches. Otherwise keep the entry unchanged.
            if (existing == null || existing.token != token || now >= existing.expiresAt) {
                existing
            } else {
                existing.isHeld.set(false)
                existing.semaphore.release()
                released.set(true)
                null
            }
        }
        return released.get()
    }

    companion object {
        private val lockMap = ConcurrentHashMap<String, LockInfo>()
        private val tokenSequence = AtomicLong(0)

        private val monitor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "req-shield-local-lock-monitor").apply { isDaemon = true }
        }

        init {
            monitor.scheduleAtFixedRate(
                { removeExpiredEntries() },
                LOCK_MONITOR_INTERVAL_MILLIS,
                LOCK_MONITOR_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }

        /** Drops every entry whose holder never released it and whose lock has expired. */
        private fun removeExpiredEntries() {
            for (key in lockMap.keys.toList()) {
                val now = System.currentTimeMillis()
                lockMap.compute(key) { _, existing ->
                    if (existing != null && now >= existing.expiresAt) null else existing
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
}
