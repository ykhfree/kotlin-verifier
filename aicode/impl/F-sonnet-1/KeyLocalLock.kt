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
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * In-process [KeyLock]. It keeps one map entry per `(cache key, lock type)` that is currently locked.
 *
 * The map is shared by the whole JVM, so every instance sees the same locks.
 * Each entry stores its own timeout. Instances with different timeouts can therefore share the map.
 *
 * A lock expires [lockTimeoutMillis] after it was acquired. An expired lock counts as free:
 * [tryLock] can take it over, and the old token no longer releases anything.
 * A background monitor drops expired entries to free memory. It runs every
 * [LOCK_MONITOR_INTERVAL_MILLIS] ms, so an expired entry can stay in the map for up to that long.
 *
 * The number of entries can be capped with [LocalLockLimit]. If the map is full,
 * [tryLock] still returns a token for a new key, but no entry backs it. The caller then works
 * without request collapsing for that key. [unLock] returns false for such a token.
 */
class KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock {
    override suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): String? {
        val now = nowToEpochTime()
        val token = UUID.randomUUID().toString()

        // compute() runs atomically per map key, so only one caller can win the same lock.
        val entry =
            locks.compute(lockKeyOf(key, lockType)) { _, current ->
                when {
                    // Another holder owns the lock. Keys that are already in the map skip the size cap.
                    current != null && !current.isExpiredAt(now) -> current
                    // New key and the map is full. Store nothing and let the caller run without a lock entry.
                    current == null && LocalLockLimit.rejectsNewEntry(locks.size.toLong()) -> null
                    // Free key, or an expired entry that this caller takes over.
                    else -> LockEntry(token, now, lockTimeoutMillis)
                }
            }

        return if (entry == null || entry.token == token) token else null
    }

    override suspend fun unLock(
        key: String,
        lockType: LockType,
        token: String,
    ): Boolean {
        val lockKey = lockKeyOf(key, lockType)
        val current = locks[lockKey] ?: return false
        if (current.token != token || current.isExpiredAt(nowToEpochTime())) return false

        // Removes only this exact entry. If another caller took the lock after our read, this fails.
        return locks.remove(lockKey, current)
    }

    /**
     * Ownership record of one lock. It has no equals() on purpose: [ConcurrentHashMap.remove]
     * with a value then compares by identity and can never remove a newer entry of the same key.
     */
    private class LockEntry(
        val token: String,
        private val acquiredAtMillis: Long,
        private val timeoutMillis: Long,
    ) {
        fun isExpiredAt(nowMillis: Long): Boolean = nowMillis - acquiredAtMillis >= timeoutMillis
    }

    private companion object {
        const val MONITOR_THREAD_NAME = "req-shield-local-lock-monitor"

        val logger = LoggerFactory.getLogger(KeyLocalLock::class.java)

        val locks = ConcurrentHashMap<String, LockEntry>()

        init {
            // Started once per JVM when this class loads. The thread is a daemon, so it never blocks JVM exit.
            Executors
                .newSingleThreadScheduledExecutor { task -> Thread(task, MONITOR_THREAD_NAME).apply { isDaemon = true } }
                .scheduleWithFixedDelay(
                    { dropExpiredEntries() },
                    LOCK_MONITOR_INTERVAL_MILLIS,
                    LOCK_MONITOR_INTERVAL_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
        }

        fun dropExpiredEntries() {
            // A task that throws is never scheduled again, so the monitor catches the failure and logs it.
            try {
                val now = nowToEpochTime()
                for ((lockKey, entry) in locks) {
                    if (entry.isExpiredAt(now)) locks.remove(lockKey, entry)
                }
            } catch (e: Exception) {
                logger.warn("Local lock cleanup failed. It will run again at the next interval.", e)
            }
        }
    }
}
