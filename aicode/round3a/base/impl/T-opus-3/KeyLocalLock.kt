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
 * JVM-local [KeyLock]. tryLock adds an entry to a JVM-wide map, unless the map is full. unLock removes it.
 * If the holder never calls unLock, the entry expires after [lockTimeoutMillis].
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
            when {
                // Another holder owns a live lock: keep its entry and give no token.
                existing != null && !isExpired(existing, now) -> existing
                // The map is full: hand out a token that no entry backs, and insert nothing.
                existing == null && LocalLockLimit.rejectsNewEntry(lockMap.size.toLong()) -> {
                    acquiredToken.set(nextToken())
                    null
                }
                // No entry, or an expired one: the caller becomes the new holder.
                else -> {
                    val token = nextToken()
                    acquiredToken.set(token)
                    LockInfo(
                        semaphore = Semaphore(permits = 1, acquiredPermits = 1),
                        expiresAt = now + lockTimeoutMillis,
                        isHeld = AtomicBoolean(true),
                        token = token,
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
            // The CAS makes sure one entry releases its semaphore at most once.
            if (existing != null && existing.token == token && existing.isHeld.compareAndSet(true, false)) {
                existing.semaphore.release()
                released.set(true)
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
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                while (isActive) {
                    delay(LOCK_MONITOR_INTERVAL_MILLIS)
                    removeExpiredEntries()
                }
            }
        }

        private fun nextToken(): String = "local-${tokenSequence.incrementAndGet()}"

        // tryLock and the monitor share this rule, so they agree on when a lock expires.
        private fun isExpired(
            lockInfo: LockInfo,
            now: Long,
        ): Boolean = lockInfo.expiresAt <= now

        private fun removeExpiredEntries() {
            for (key in lockMap.keys) {
                val now = System.currentTimeMillis()
                // For a key that was removed in the meantime, existing is null and compute inserts nothing.
                lockMap.compute(key) { _, existing ->
                    if (existing != null && isExpired(existing, now)) null else existing
                }
            }
        }
    }
}
