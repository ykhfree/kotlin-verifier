package com.linecorp.cse.reqshield.support.constant

// Judge-work stub. It holds only the constants that the compiled files need.
// LOCK_MONITOR_INTERVAL_MILLIS comes from HELPERS.md, the property name from SPEC.md,
// and UNLIMITED_LOCK_ENTRIES from the sidecar review note. LOCK_KEY_PREFIX is a test value.
object ConfigValues {
    const val LOCK_KEY_PREFIX = "judge-lock:"
    const val LOCK_MONITOR_INTERVAL_MILLIS = 1000L
    const val MAX_LOCK_ENTRIES_PROPERTY = "req-shield.lock.max-entries"
    const val UNLIMITED_LOCK_ENTRIES = 0L
}
