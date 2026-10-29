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
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap
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
            when {
                existing == null -> {
                    if (LocalLockLimit.rejectsNewEntry(lockMap.size.toLong())) {
                        // The map is full: hand out a permit that no map entry backs.
                        acquiredToken.set(newToken())
                        null
                    } else {
                        newHeldEntry(now, acquiredToken)
                    }
                }
                // The previous holder let its lock expire: the next caller takes a fresh entry.
                existing.expiresAt <= now -> newHeldEntry(now, acquiredToken)
                existing.semaphore.tryAcquire() -> {
                    val token = newToken()
                    existing.isHeld.set(true)
                    existing.token = token
                    existing.expiresAt = now + lockTimeoutMillis
                    acquiredToken.set(token)
                    existing
                }
                else -> existing
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
                existing.isHeld.set(false)
                existing.token = null
                existing.semaphore.release()
                released.set(true)
                // Drop the entry as soon as the lock is released.
                null
            } else {
                existing
            }
        }

        return released.get()
    }

    /** Builds an entry held by a new token, and publishes the token through [acquiredToken]. */
    private fun newHeldEntry(
        now: Long,
        acquiredToken: AtomicReference<String?>,
    ): LockInfo {
        val token = newToken()
        val info = LockInfo(semaphore = Semaphore(1), expiresAt = now + lockTimeoutMillis)
        info.semaphore.tryAcquire()
        info.isHeld.set(true)
        info.token = token
        acquiredToken.set(token)
        return info
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
        private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        init {
            monitorScope.launch {
                while (true) {
                    delay(LOCK_MONITOR_INTERVAL_MILLIS)
                    removeExpiredEntries()
                }
            }
        }

        private fun newToken(): String = "local-${tokenSequence.incrementAndGet()}"

        /** Removes every entry whose holder did not release it before it expired. */
        private fun removeExpiredEntries() {
            for (key in lockMap.keys) {
                val now = System.currentTimeMillis()
                lockMap.compute(key) { _, existing ->
                    if (existing != null && existing.expiresAt <= now) null else existing
                }
            }
        }
    }
}
