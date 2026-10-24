// Test stub for the judge harness. Values follow HELPERS.md and SPEC.md.
// LOCK_KEY_PREFIX is not given in the packet; any prefix gives the same lock behavior.
package com.linecorp.cse.reqshield.support.constant

object ConfigValues {
    const val LOCK_KEY_PREFIX = "judge-lock:"
    const val LOCK_MONITOR_INTERVAL_MILLIS = 1000L
    const val MAX_LOCK_ENTRIES_PROPERTY = "req-shield.lock.max-entries"
    const val UNLIMITED_LOCK_ENTRIES = 0L
}
