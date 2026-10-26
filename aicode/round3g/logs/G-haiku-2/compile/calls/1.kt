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
import com.linecorp.cse.reqshield.support.constant.ConfigValues
import com.linecorp.cse.reqshield.support.utils.nowToEpochTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * In-JVM lock. Every instance shares one lock map, so keys locked by any instance are visible to all.
 * A lock expires [lockTimeoutMillis] after it is taken, and an expired lock can be taken again.
 */
class KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock {
    override suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): String? {
        val now = nowToEpochTime()
        val granted = AtomicReference<String?>(null)
        lockMap.compute(lockKeyOf(key, lockType)) { _, existing ->
            when {
                // Another holder still owns an unexpired lock.
                existing != null && existing.isHeld.get() && existing.expiresAt > now -> existing
                // The map is full, so the caller gets a token that no entry backs.
                existing == null && LocalLockLimit.rejectsNewEntry(lockMap.size.toLong()) -> {
                    granted.set(newToken())
                    null
                }
                // No entry, or the previous holder's lock expired: take the lock.
                else -> {
                    val token = newToken()
                    granted.set(token)
                    LockInfo(
                        semaphore = Semaphore(1),
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
        val released = AtomicBoolean(false)
        lockMap.compute(lockKeyOf(key, lockType)) { _, existing ->
            if (existing != null && existing.token == token) {
                // Remove the entry, so the map holds only locks that are still taken.
                released.set(true)
                null
            } else {
                existing
            }
        }
        return released.get()
    }

    private companion object {
        private val lockMap = ConcurrentHashMap<String, LockInfo>()
        private val tokenSequence = AtomicLong(0)

        init {
            // Daemon thread: it must not keep the JVM alive.
            Thread {
                while (true) {
                    Thread.sleep(ConfigValues.LOCK_MONITOR_INTERVAL_MILLIS)
                    lockMap.keys.toList().forEach { key ->
                        val now = System.currentTimeMillis()
                        lockMap.compute(key) { _, existing ->
                            if (existing != null && existing.expiresAt <= now) null else existing
                        }
                    }
                }
            }.apply {
                name = "req-shield-local-lock-monitor"
                isDaemon = true
                start()
            }
        }

        private fun newToken(): String = "local-${tokenSequence.incrementAndGet()}"
    }

    private class LockInfo(
        val semaphore: Semaphore,
        @Volatile var expiresAt: Long,
        val isHeld: AtomicBoolean = AtomicBoolean(false),
        @Volatile var token: String? = null,
    )
}
