package com.linecorp.cse.reqshield.support.utils

// Judge-work test double for the real helper `fun nowToEpochTime() = System.currentTimeMillis()`.
// If FakeClock.fixed is null, the function returns the system clock, like the real helper.
object FakeClock {
    @Volatile
    var fixed: Long? = null
}

fun nowToEpochTime(): Long = FakeClock.fixed ?: System.currentTimeMillis()
