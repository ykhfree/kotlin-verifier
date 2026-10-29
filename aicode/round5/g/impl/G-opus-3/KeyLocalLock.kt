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
        val now = nowToEpochTime()
        val acquiredToken = AtomicReference<String?>(null)

        lockMap.compute(lockKey) { _, existing ->
            if (existing != null && existing.isHeld.get() && now < existing.expiresAt) {
                // Another holder owns a live lock.
                existing
            } else if (existing == null && LocalLockLimit.rejectsNewEntry(lockMap.mappingCount())) {
                // The map is full: hand out a permit that no map entry backs.
                acquiredToken.set(newToken())
                null
            } else {
                // No entry, or the entry expired: install a fresh entry for the new holder.
                val token = newToken()
                val lockInfo = LockInfo(semaphore = Semaphore(1), expiresAt = now + lockTimeoutMillis)
                lockInfo.semaphore.tryAcquire()
                lockInfo.isHeld.set(true)
                lockInfo.token = token
                acquiredToken.set(token)
                lockInfo
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
                // The caller owns the lock: release it and drop the entry.
                existing.isHeld.set(false)
                existing.token = null
                existing.semaphore.release()
                released.set(true)
                null
            } else {
                // Not held, or held by another holder: leave the entry unchanged.
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

        private fun newToken(): String = "local-${tokenSequence.incrementAndGet()}"

        init {
            // Removes entries whose holder never released them once their timeout has passed.
            CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
                while (true) {
                    delay(LOCK_MONITOR_INTERVAL_MILLIS)
                    for (key in lockMap.keys) {
                        val now = System.currentTimeMillis()
                        lockMap.compute(key) { _, existing ->
                            if (existing != null && now >= existing.expiresAt) null else existing
                        }
                    }
                }
            }
        }
    }
}
