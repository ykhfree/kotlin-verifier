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

/**
 * Local lock for one JVM. A lock expires [lockTimeoutMillis] after it was acquired.
 */
class KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock {
    override suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): String? {
        val now = nowToEpochTime()
        val lockKey = lockKeyOf(key, lockType)
        val token = "local-${tokenSequence.incrementAndGet()}"
        val granted = AtomicBoolean(false)
        lockMap.compute(lockKey) { _, existing ->
            when {
                // Another holder owns the lock, and it has not expired.
                existing != null && existing.isHeld.get() && now < existing.expiresAt -> existing
                // The map is full, so the caller gets a token that no entry backs.
                existing == null && LocalLockLimit.rejectsNewEntry(lockMap.size.toLong()) -> {
                    granted.set(true)
                    null
                }
                // The key is free, or its old entry has expired.
                else -> {
                    granted.set(true)
                    LockInfo(
                        semaphore = Semaphore(1).apply { tryAcquire() },
                        expiresAt = now + lockTimeoutMillis,
                        isHeld = AtomicBoolean(true),
                        token = token,
                    )
                }
            }
        }
        return if (granted.get()) token else null
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

        init {
            // Removes expired entries every LOCK_MONITOR_INTERVAL_MILLIS.
            // A daemon thread, so it does not keep the JVM alive.
            Thread {
                while (true) {
                    Thread.sleep(LOCK_MONITOR_INTERVAL_MILLIS)
                    for (key in lockMap.keys) {
                        val now = System.currentTimeMillis()
                        lockMap.compute(key) { _, existing ->
                            if (existing != null && existing.expiresAt <= now) null else existing
                        }
                    }
                }
            }.apply {
                isDaemon = true
                start()
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
