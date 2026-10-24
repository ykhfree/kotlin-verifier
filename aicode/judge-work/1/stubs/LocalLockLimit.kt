package com.linecorp.cse.reqshield.support.config

import java.util.concurrent.atomic.AtomicInteger

/**
 * Copies the decision rule of the real LocalLockLimit.rejectsNewEntry (LocalLockLimit.kt:86-101):
 * false when the limit is 0 (uncapped) or currentSize < limit, otherwise true.
 * Logging is left out. [calls] counts invocations so a scenario can see whether the cap was consulted.
 */
object LocalLockLimit {
    @Volatile
    var maxEntries: Long = 0L

    val calls = AtomicInteger(0)

    fun rejectsNewEntry(currentSize: Long): Boolean {
        calls.incrementAndGet()
        val limit = maxEntries
        if (limit == 0L || currentSize < limit) return false
        return true
    }
}
