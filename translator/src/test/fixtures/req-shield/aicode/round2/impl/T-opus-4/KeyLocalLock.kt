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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * In-JVM [KeyLock]. The JVM-wide map holds one entry per locked (cache key, lock type).
 * An expired entry stays until the monitor drops it or a new holder replaces it.
 * Each acquisition gets a new token. Only that token releases the lock.
 */
class KeyLocalLock(
    private val lockTimeoutMillis: Long,
) : KeyLock {
    override suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): String? {
        val now = nowToEpochTime()
        val acquiredToken = AtomicReference<String?>(null)
        lockMap.compute(lockKeyOf(key, lockType)) { _, existing ->
            if (existing == null && LocalLockLimit.rejectsNewEntry(lockMap.size.toLong())) {
                // The map is full. Grant a permit that no entry backs, so this key only loses request collapsing.
                acquiredToken.set(newToken())
                null
            } else {
                // Replace an expired entry, so the token of its late holder cannot release the new lock.
                val entry =
                    if (existing == null || isExpired(existing, now)) {
                        LockInfo(Semaphore(1), now + lockTimeoutMillis)
                    } else {
                        existing
                    }
                if (entry.semaphore.tryAcquire()) {
                    val token = newToken()
                    entry.isHeld.set(true)
                    entry.token = token
                    entry.expiresAt = now + lockTimeoutMillis
                    acquiredToken.set(token)
                }
                entry
            }
        }
        return acquiredToken.get()
    }

    override suspend fun unLock(
        key: String,
        lockType: LockType,
        token: String,
    ): Boolean {
        val released = AtomicBoolean(false)
        lockMap.compute(lockKeyOf(key, lockType)) { _, existing ->
            if (existing != null && existing.token == token && existing.isHeld.compareAndSet(true, false)) {
                existing.token = null
                existing.semaphore.release()
                released.set(true)
                // Drop the entry on release, so the map size tracks concurrent locks, not distinct keys.
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
            // The monitor drops expired entries whose holder never released them.
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                while (isActive) {
                    delay(LOCK_MONITOR_INTERVAL_MILLIS)
                    removeExpiredEntries()
                }
            }
        }

        private fun newToken(): String = "local-${tokenSequence.incrementAndGet()}"

        private fun isExpired(
            info: LockInfo,
            now: Long,
        ): Boolean = info.expiresAt <= now

        private fun removeExpiredEntries() {
            for (key in lockMap.keys) {
                val now = System.currentTimeMillis()
                lockMap.compute(key) { _, info ->
                    if (info != null && isExpired(info, now)) null else info
                }
            }
        }
    }
}
