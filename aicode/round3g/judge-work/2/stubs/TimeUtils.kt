// Harness stub: replaces support's TimeUtils.kt so that the test controls the clock.
package com.linecorp.cse.reqshield.support.utils

/** Two test clocks: [lock] feeds nowToEpochTime() (tryLock), [sweep] feeds the monitor. */
object FakeClock {
    @Volatile var lock: Long = 0L
    @Volatile var sweep: Long = 0L

    fun sweepNow(): Long = sweep
}

fun nowToEpochTime(): Long = FakeClock.lock
