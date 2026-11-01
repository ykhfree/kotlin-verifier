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
 * In-JVM [KeyLock]. All instances share one lock map, so a lock is visible to the whole JVM.
 *
 * A lock is held from [tryLock] until [unLock] or until [lockTimeoutMillis] has passed.
 * After that time, the lock is free and the next [tryLock] can take it.
 */
class KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock {
    override suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): String? {
        val lockKey = lockKeyOf(key, lockType)
        val now = nowToEpochTime()
        val acquiredToken = AtomicReference<String?>(null)
        lockMap.compute(lockKey) { _, existing ->
            val heldByOther = existing != null && existing.isHeld.get() && now < existing.expiresAt
            if (heldByOther) {
                existing
            } else {
                val newToken = "local-${tokenSequence.incrementAndGet()}"
                acquiredToken.set(newToken)
                if (existing == null && LocalLockLimit.rejectsNewEntry(lockMap.size.toLong())) {
                    // The map is full. The caller gets a token, but no entry backs it.
                    null
                } else {
                    val semaphore = Semaphore(1)
                    semaphore.tryAcquire()
                    LockInfo(semaphore, now + lockTimeoutMillis, AtomicBoolean(true), newToken)
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
        val now = nowToEpochTime()
        val released = AtomicBoolean(false)
        lockMap.compute(lockKey) { _, existing ->
            if (existing != null && existing.token == token) {
                // An expired lock counts as not held, so it is dropped but not reported as released.
                released.set(existing.isHeld.get() && now < existing.expiresAt)
                existing.isHeld.set(false)
                existing.semaphore.release()
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
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "req-shield-local-lock-monitor").apply { isDaemon = true }
            }.scheduleAtFixedRate(
                { removeExpiredLocks() },
                LOCK_MONITOR_INTERVAL_MILLIS,
                LOCK_MONITOR_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }

        private fun removeExpiredLocks() {
            for (key in lockMap.keys) {
                val now = System.currentTimeMillis()
                lockMap.compute(key) { _, existing ->
                    if (existing != null && now >= existing.expiresAt) null else existing
                }
            }
        }
    }
}
