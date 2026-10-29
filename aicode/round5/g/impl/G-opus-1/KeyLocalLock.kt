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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class KeyLocalLock(
    private val lockTimeoutMillis: Long,
) : KeyLock {
    override suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): String? {
        val lockKey = lockKeyOf(key, lockType)
        val newToken = "local-${tokenSequence.incrementAndGet()}"
        val acquiredToken = AtomicReference<String?>(null)
        val now = nowToEpochTime()

        lockMap.compute(lockKey) { _, existing ->
            when {
                // A live holder owns the key: keep its entry and refuse.
                existing != null && existing.isHeld.get() && now < existing.expiresAt -> existing

                // The map is full and the key is new: hand out a permit that no entry backs.
                existing == null && LocalLockLimit.rejectsNewEntry(lockMap.mappingCount()) -> {
                    acquiredToken.set(newToken)
                    null
                }

                // The key is free, or its holder expired: the caller becomes the new owner.
                else -> {
                    acquiredToken.set(newToken)
                    LockInfo(
                        semaphore = Semaphore(1),
                        expiresAt = now + lockTimeoutMillis,
                        isHeld = AtomicBoolean(true),
                        token = newToken,
                    )
                }
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
                // The caller owns the entry: release it and drop it from the map.
                existing.isHeld.set(false)
                released.set(true)
                null
            } else {
                // Not held, or held by another owner: leave the map unchanged.
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
        private val monitorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        init {
            monitorScope.launch {
                while (isActive) {
                    removeExpiredEntries()
                    delay(LOCK_MONITOR_INTERVAL_MILLIS)
                }
            }
        }

        // Drops every entry whose lock timeout has passed, including entries whose holder never released them.
        private fun removeExpiredEntries() {
            for (key in lockMap.keys) {
                val now = System.currentTimeMillis()
                lockMap.compute(key) { _, existing ->
                    if (existing != null && now >= existing.expiresAt) null else existing
                }
            }
        }
    }
}
