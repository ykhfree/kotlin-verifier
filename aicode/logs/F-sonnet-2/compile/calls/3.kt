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
 * [KeyLock] that keeps its state in the memory of the current JVM.
 *
 * All instances share one lock map, so two instances lock against each other for the same key and lock type.
 * Each entry stores its own expiry time. Instances with different [lockTimeoutMillis] can therefore share the map.
 *
 * - [tryLock] gives the caller an ownership token. [unLock] releases the lock only if the token matches.
 * - [unLock] removes the entry at once. If the holder never calls it, a monitor thread removes the entry after
 *   [lockTimeoutMillis]. The monitor runs every [LOCK_MONITOR_INTERVAL_MILLIS] ms.
 * - A lock whose timeout has passed counts as free, even if the monitor has not removed its entry yet.
 * - If [LocalLockLimit] refuses a new entry, [tryLock] still returns a token. No map entry backs this token,
 *   so the lock is not exclusive. The caller runs without request collapsing for that key.
 */
class KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock {
    override suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): String? {
        val lockKey = lockKeyOf(key, lockType)
        val now = nowToEpochTime()

        // Fast path: a live holder exists. Most calls under request collapsing end here without a write lock.
        if (locks[lockKey]?.isExpiredAt(now) == false) return null

        var token: String? = null
        locks.compute(lockKey) { _, current ->
            when {
                // Another caller took the lock after the fast path check.
                current != null && !current.isExpiredAt(now) -> current

                // New key but the map is full. Hand out a permit that no entry backs.
                current == null && LocalLockLimit.rejectsNewEntry(locks.mappingCount()) -> {
                    token = newToken()
                    null
                }

                // New key, or the entry of a holder whose timeout has passed. Replacing keeps the map size.
                else -> {
                    val entry = LockEntry(token = newToken(), expiresAt = expiresAt(now))
                    token = entry.token
                    entry
                }
            }
        }
        return token
    }

    override suspend fun unLock(
        key: String,
        lockType: LockType,
        token: String,
    ): Boolean {
        val lockKey = lockKeyOf(key, lockType)

        // Fast path: no entry, or the entry belongs to another holder. This also covers unbacked permits.
        if (locks[lockKey]?.token != token) return false

        val now = nowToEpochTime()
        var released = false
        locks.compute(lockKey) { _, current ->
            if (current?.token == token) {
                // An entry past its timeout is removed but does not count as a release: the lock was no longer held.
                released = !current.isExpiredAt(now)
                null
            } else {
                current
            }
        }
        return released
    }

    /** Saturates at [Long.MAX_VALUE], so a very large timeout means "never expires" and does not overflow. */
    private fun expiresAt(now: Long): Long =
        if (lockTimeoutMillis >= Long.MAX_VALUE - now) Long.MAX_VALUE else now + lockTimeoutMillis

    private fun newToken(): String = UUID.randomUUID().toString()

    private class LockEntry(
        val token: String,
        val expiresAt: Long,
    ) {
        fun isExpiredAt(now: Long): Boolean = now >= expiresAt
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(KeyLocalLock::class.java)

        /** Key is [lockKeyOf]. Declared before the monitor starts, because the monitor reads it. */
        private val locks = ConcurrentHashMap<String, LockEntry>()

        init {
            // Daemon thread: the monitor must not keep the JVM alive.
            Executors
                .newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "req-shield-local-lock-monitor").apply { isDaemon = true }
                }.scheduleWithFixedDelay(
                    ::removeExpiredEntries,
                    LOCK_MONITOR_INTERVAL_MILLIS,
                    LOCK_MONITOR_INTERVAL_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
        }

        private fun removeExpiredEntries() {
            // An uncaught exception would cancel all later runs of the scheduled task.
            try {
                val now = nowToEpochTime()
                for ((lockKey, entry) in locks) {
                    // Conditional remove: a new holder that replaced the entry in the meantime stays in the map.
                    if (entry.isExpiredAt(now)) locks.remove(lockKey, entry)
                }
            } catch (e: Exception) {
                logger.warn("Failed to remove expired local lock entries. The monitor will retry.", e)
            }
        }
    }
}
