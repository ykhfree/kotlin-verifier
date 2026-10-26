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

/**
 * JVM-local [KeyLock]. One map entry exists per (key, lock type) while that lock is held.
 * Releasing the lock removes the entry. An expired entry is free: the next tryLock takes it over,
 * and the monitor removes it if nobody does.
 */
class KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock {
    override suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): String? {
        val now = nowToEpochTime()
        val grantedToken = AtomicReference<String?>(null)
        lockMap.compute(lockKeyOf(key, lockType)) { _, existing ->
            when {
                // A live holder owns the lock.
                existing != null && existing.expiresAt > now -> existing
                // Map is full: grant a permit that no entry backs, and insert nothing.
                existing == null && LocalLockLimit.rejectsNewEntry(lockMap.mappingCount()) -> {
                    grantedToken.set(newToken())
                    null
                }
                // Key is free, or its holder let the lock expire.
                else -> {
                    val token = newToken()
                    grantedToken.set(token)
                    LockInfo(Semaphore(0), expiryAfter(now), AtomicBoolean(true), token)
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

    /** Returns now + lockTimeoutMillis, capped at Long.MAX_VALUE instead of overflowing. */
    private fun expiryAfter(now: Long): Long =
        if (lockTimeoutMillis > Long.MAX_VALUE - now) Long.MAX_VALUE else now + lockTimeoutMillis

    /** One held lock. The entry is created held and is removed on release, so its fields never change. */
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
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                while (isActive) {
                    delay(LOCK_MONITOR_INTERVAL_MILLIS)
                    removeExpiredEntries()
                }
            }
        }

        private fun newToken(): String = "local-${tokenSequence.incrementAndGet()}"

        /** Removes each entry whose expiry has passed. The check runs inside compute, so a re-taken lock stays. */
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
