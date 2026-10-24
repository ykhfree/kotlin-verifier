// Black-box check of the background monitor at the expiry boundary.
// It finds the monitor's phase, then places entries whose expiresAt lies around the next pass.
// For each entry it records an upper bound of the time at which the entry left the map.
package com.linecorp.cse.reqshield.kotlin.coroutine

import kotlinx.coroutines.runBlocking

private data class Placed(val key: String, val expiresAt: Long, val token: String)

private fun findPhase(round: Int): Long =
    runBlocking {
        val key = "phase-$round"
        KeyLocalLock(1).tryLock(key, LockType.CREATE)!!
        while (Peek.entry(key, LockType.CREATE) != null) Thread.onSpinWait()
        now()
    }

private fun place(key: String, target: Long): Placed =
    runBlocking {
        while (true) {
            val lock = KeyLocalLock(target - now())
            val token = lock.tryLock(key, LockType.CREATE)!!
            val info = Peek.entry(key, LockType.CREATE)!!
            if (Peek.expiresAt(info) == target) return@runBlocking Placed(key, target, token)
            // The clock ticked inside tryLock, so expiresAt missed the target. Release and retry.
            lock.unLock(key, LockType.CREATE, token)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

fun main() {
    println("== ${System.getProperty("impl.id")} (monitor boundary, black box)")
    for (round in 1..3) {
        val phase = findPhase(round)
        val placed = (-3L..25L).mapIndexed { i, d -> place("mb-$round-$i", phase + 1000 + d) }
        val removedBy = LongArray(placed.size) { -1 }
        val deadline = phase + 2600
        while (now() < deadline && removedBy.any { it < 0 }) {
            for ((i, p) in placed.withIndex()) {
                // Read the clock after the absence check, so the value bounds the removal time from above.
                if (removedBy[i] < 0 && Peek.entry(p.key, LockType.CREATE) == null) removedBy[i] = now()
            }
        }
        val rows = placed.mapIndexed { i, p -> "expiresAt=${p.expiresAt} removedBy=${removedBy[i]} (removedBy - expiresAt = ${removedBy[i] - p.expiresAt})" }
        val early = placed.indices.filter { removedBy[it] in 0..placed[it].expiresAt }
        println("round $round: phase=$phase")
        rows.forEach { println("   $it") }
        println("   entries removed while now <= expiresAt: ${early.size}")
    }
}
