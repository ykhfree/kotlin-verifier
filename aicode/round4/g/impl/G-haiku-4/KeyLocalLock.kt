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
 * Lock for one JVM. Every instance shares one map, so the keys stay unique across instances.
 * An entry expires [lockTimeoutMillis] after its acquisition.
 */
class KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock {

    override suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): String? {
        val now = nowToEpochTime()
        val mapKey = lockKeyOf(key, lockType)
        val token = "local-${tokenSequence.incrementAndGet()}"
        val acquired = AtomicReference<String?>(null)
        lockMap.compute(mapKey) { _, existing ->
            if (existing != null && existing.expiresAt > now) {
                // Another holder owns the lock and it has not expired.
                existing
            } else if (existing == null && LocalLockLimit.rejectsNewEntry(lockMap.size.toLong())) {
                // The map is full. Hand out a permit that no entry backs.
                acquired.set(token)
                null
            } else {
                // Take a free key, or take over an expired entry.
                acquired.set(token)
                newHeldEntry(token, now + lockTimeoutMillis)
            }
        }
        return acquired.get()
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
                existing.isHeld.set(false)
                existing.semaphore.release()
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

        private val monitor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "req-shield-local-lock-monitor").apply { isDaemon = true }
        }

        init {
            monitor.scheduleAtFixedRate(
                ::removeExpiredEntries,
                LOCK_MONITOR_INTERVAL_MILLIS,
                LOCK_MONITOR_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }

        /** Drops every entry whose timeout has passed. A holder that never released its lock is removed here. */
        private fun removeExpiredEntries() {
            for (key in lockMap.keys) {
                val now = System.currentTimeMillis()
                lockMap.compute(key) { _, existing ->
                    if (existing != null && existing.expiresAt <= now) null else existing
                }
            }
        }

        private fun newHeldEntry(
            token: String,
            expiresAt: Long,
        ): LockInfo =
            LockInfo(
                semaphore = Semaphore(1).apply { tryAcquire() },
                expiresAt = expiresAt,
                isHeld = AtomicBoolean(true),
                token = token,
            )

        private class LockInfo(
            val semaphore: Semaphore,
            @Volatile var expiresAt: Long,
            val isHeld: AtomicBoolean = AtomicBoolean(false),
            @Volatile var token: String? = null,
        )
    }
}
