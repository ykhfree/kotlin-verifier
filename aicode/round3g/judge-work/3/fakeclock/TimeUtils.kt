// Judge harness only. This class shadows support-1.0.0.jar's TimeUtilsKt on the runtime classpath.
// It lets the harness set the value that nowToEpochTime() returns. It does not change System.currentTimeMillis().
@file:JvmName("TimeUtilsKt")

package com.linecorp.cse.reqshield.support.utils

import java.util.concurrent.atomic.AtomicLong

object JudgeClock {
    /** If this value is 0 or more, nowToEpochTime() returns it. If it is -1, nowToEpochTime() reads the system clock. */
    val fixed = AtomicLong(-1)
}

fun nowToEpochTime(): Long {
    val f = JudgeClock.fixed.get()
    return if (f >= 0) f else System.currentTimeMillis()
}
