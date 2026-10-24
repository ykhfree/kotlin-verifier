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
 * In-process [KeyLock].
 *
 * All instances share one lock map for the whole JVM. The map holds one entry for each (cache key, lock type) that is
 * locked now. A holder removes its entry with [unLock]. If a holder never calls [unLock], a monitor thread removes the
 * entry after [lockTimeoutMillis]. The monitor runs every [LOCK_MONITOR_INTERVAL_MILLIS].
 *
 * The map size can be capped with [LocalLockLimit]. When the map is full, [tryLock] still returns a token for a new key.
 * No map entry backs this token, so the key is not locked and other callers are not blocked. The caller then works as
 * if it held the lock.
 *
 * Every acquisition gets a new random token. [unLock] releases an entry only if the token matches. A slow holder whose
 * lock has expired can therefore not release the lock of the next holder.
 *
 * @param lockTimeoutMillis time after which an unreleased lock expires
 */
class KeyLocalLock(
    private val lockTimeoutMillis: Long,
) : KeyLock {
    /**
     * Returns a new token if the caller now owns the lock. Returns null if another holder owns it.
     *
     * An expired entry that the monitor has not yet removed counts as free. The new holder replaces it.
     * The size cap applies only when the key has no entry. A key that already has an entry never counts against the cap.
     */
    override suspend fun tryLock(
        key: String,
        lockType: LockType,
    ): String? {
        val lockKey = lockKeyOf(key, lockType)
        val now = nowToEpochTime()
        var token: String? = null

        // compute() runs the function once and holds the bin lock, so the check and the update are atomic for this key.
        locks.compute(lockKey) { _, current ->
            when {
                current != null && !current.isExpiredAt(now) -> {
                    current
                }

                current == null && LocalLockLimit.rejectsNewEntry(locks.mappingCount()) -> {
                    // Map is full: hand out a permit that no entry backs, so the caller runs without request collapsing.
                    token = newToken()
                    null
                }

                else -> {
                    LockEntry(newToken().also { token = it }, now, lockTimeoutMillis)
                }
            }
        }

        return token
    }

    /**
     * Returns true only if this call removed the entry that [token] owns.
     *
     * Returns false if no entry exists, if the token does not match, or if the entry has already expired.
     * An expired entry is removed, whatever token the caller gives. A token from a permit that no entry backs
     * never matches, so it always returns false.
     */
    override suspend fun unLock(
        key: String,
        lockType: LockType,
        token: String,
    ): Boolean {
        val lockKey = lockKeyOf(key, lockType)
        val now = nowToEpochTime()
        var released = false

        locks.computeIfPresent(lockKey) { _, current ->
            when {
                current.isExpiredAt(now) -> {
                    null
                }

                current.token == token -> {
                    released = true
                    null
                }

                else -> {
                    current
                }
            }
        }

        return released
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(KeyLocalLock::class.java)

        private val locks = ConcurrentHashMap<String, LockEntry>()

        init {
            // A daemon thread, so that the monitor never keeps the JVM alive.
            Executors
                .newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "req-shield-local-lock-monitor").apply { isDaemon = true }
                }.scheduleWithFixedDelay(
                    { dropExpiredEntries() },
                    LOCK_MONITOR_INTERVAL_MILLIS,
                    LOCK_MONITOR_INTERVAL_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
        }

        private fun newToken(): String = UUID.randomUUID().toString()

        private fun dropExpiredEntries() {
            // A scheduled task that throws is never run again, so the monitor must not let an exception escape.
            try {
                val now = nowToEpochTime()
                // removeIf removes an entry only if it still holds the value that the filter checked.
                // A fresh entry that replaced an expired one in the meantime is therefore kept.
                locks.values.removeIf { it.isExpiredAt(now) }
            } catch (e: Exception) {
                logger.warn("Local lock monitor failed to drop expired entries. It will retry on the next run.", e)
            }
        }
    }
}

/**
 * The owner of one lock. Each entry carries its own timeout, because the shared map serves
 * [KeyLocalLock] instances with different timeouts.
 */
private class LockEntry(
    val token: String,
    private val acquiredAtMillis: Long,
    private val timeoutMillis: Long,
) {
    // The elapsed time is compared, not the end time, so a very large timeout cannot overflow.
    fun isExpiredAt(nowMillis: Long): Boolean = nowMillis - acquiredAtMillis >= timeoutMillis
}
