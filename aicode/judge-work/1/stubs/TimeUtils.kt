package com.linecorp.cse.reqshield.support.utils

/**
 * Test clock for the judge harness. The real helper returns System.currentTimeMillis().
 * The harness sets [nowMs] so that a scenario can replay exact clock values in tryLock.
 */
object TestClock {
    @Volatile
    var nowMs: Long = 0L
}

/** Same signature as the real helper (TimeUtils.kt:23); reads the test clock instead. */
fun nowToEpochTime(): Long = TestClock.nowMs
