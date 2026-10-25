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
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * In-JVM [KeyLock]. Keeps one entry per (key, lock type) while that lock is held.
 * A lock lease lasts [lockTimeoutMillis]. An entry whose lease has ended counts as free.
 */
class KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock {

    override suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): String? {
        val now = nowToEpochTime()
        val mapKey = lockKeyOf(key, lockType)
        val newToken = "local-${tokenSequence.incrementAndGet()}"
        val grantedToken = AtomicReference<String?>(null)
        lockMap.compute(mapKey) { _, existing ->
            when {
                existing != null && existing.expiresAt > now -> existing
                existing == null && LocalLockLimit.rejectsNewEntry(lockMap.size.toLong()) -> {
                    // The map is full. Grant a permit that no map entry backs.
                    grantedToken.set(newToken)
                    null
                }
                else -> {
                    // A new entry, or a takeover of an entry whose lease has ended.
                    grantedToken.set(newToken)
                    LockInfo(
                        // Zero permits left: the new holder owns the only permit.
                        semaphore = Semaphore(0),
                        expiresAt = now + lockTimeoutMillis,
                        isHeld = AtomicBoolean(true),
                        token = newToken,
                    )
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
            if (existing != null && existing.token == token) {
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
        private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        init {
            monitorScope.launch {
                while (true) {
                    delay(LOCK_MONITOR_INTERVAL_MILLIS)
                    removeExpiredEntries()
                }
            }
        }

        /** Removes every entry whose lease has ended. Runs on each monitor tick. */
        private fun removeExpiredEntries() {
            for (mapKey in lockMap.keys) {
                val now = System.currentTimeMillis()
                lockMap.compute(mapKey) { _, existing ->
                    if (existing != null && existing.expiresAt <= now) null else existing
                }
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
