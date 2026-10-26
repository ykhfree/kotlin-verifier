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
import kotlin.concurrent.thread

/** JVM-local [KeyLock]. Each held lock has one entry that expires after [lockTimeoutMillis]. */
class KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock {
    override suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): String? {
        val lockKey = lockKeyOf(key, lockType)
        val now = nowToEpochTime()
        val issuedToken = AtomicReference<String?>(null)
        lockMap.compute(lockKey) { _, existing ->
            when {
                // A live holder keeps the lock. The caller gets no token.
                existing != null && existing.isHeld.get() && now < existing.expiresAt -> existing
                // The cap is full. The caller gets a token that no entry backs.
                existing == null && LocalLockLimit.rejectsNewEntry(lockMap.size.toLong()) -> {
                    issuedToken.set(nextToken())
                    null
                }
                // No entry, or an expired entry. Store a new entry with a new token.
                else -> {
                    val token = nextToken()
                    issuedToken.set(token)
                    LockInfo(
                        semaphore = Semaphore(1).apply { tryAcquire() },
                        expiresAt = now + lockTimeoutMillis,
                        isHeld = AtomicBoolean(true),
                        token = token,
                    )
                }
            }
        }
        return issuedToken.get()
    }

    override suspend fun unLock(
        key: String,
        lockType: LockType,
        token: String,
    ): Boolean {
        val lockKey = lockKeyOf(key, lockType)
        val released = AtomicBoolean(false)
        lockMap.compute(lockKey) { _, existing ->
            if (existing == null || existing.token != token) {
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

        init {
            // One monitor for the whole JVM. It is a daemon, so it does not block JVM exit.
            thread(isDaemon = true, name = "key-local-lock-monitor") {
                while (true) {
                    Thread.sleep(LOCK_MONITOR_INTERVAL_MILLIS)
                    lockMap.keys.toList().forEach { removeIfExpired(it) }
                }
            }
        }

        private fun nextToken(): String = "local-${tokenSequence.incrementAndGet()}"

        // Removes the entry for [lockKey] when its lease has passed.
        private fun removeIfExpired(lockKey: String) {
            val now = System.currentTimeMillis()
            lockMap.compute(lockKey) { _, existing ->
                if (existing != null && now >= existing.expiresAt) null else existing
            }
        }
    }
}

/** One lock entry. Its permit and [token] belong to the holder that created the entry. */
private class LockInfo(
    val semaphore: Semaphore,
    @Volatile var expiresAt: Long,
    val isHeld: AtomicBoolean = AtomicBoolean(false),
    @Volatile var token: String? = null,
)
