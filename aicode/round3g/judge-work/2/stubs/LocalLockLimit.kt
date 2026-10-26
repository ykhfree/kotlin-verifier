// Harness stub: replaces support's LocalLockLimit so that the test sets the cap answer.
package com.linecorp.cse.reqshield.support.config

object LocalLockLimit {
    @Volatile var reject: Boolean = false

    fun rejectsNewEntry(currentSize: Long): Boolean = reject
}
