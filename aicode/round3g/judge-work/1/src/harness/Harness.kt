// Judge 1 harness. It drives one KeyLocalLock implementation and prints what it observes.
// The class under test is unchanged. Only the clock behind nowToEpochTime() can be fixed (judge.FakeClock).
// The monitor still reads the real System.currentTimeMillis().
package com.linecorp.cse.reqshield.kotlin.coroutine

import com.linecorp.cse.reqshield.support.config.LocalLockLimit
import judge.FakeClock
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.system.exitProcess

// The companion's private map. Kotlin stores a companion property as a static field of the outer class.
@Suppress("UNCHECKED_CAST")
private val lockMap: ConcurrentHashMap<String, Any> =
    KeyLocalLock::class.java.getDeclaredField("lockMap").let {
        it.isAccessible = true
        it.get(null) as ConcurrentHashMap<String, Any>
    }

private fun field(
    entry: Any,
    name: String,
): Any? =
    entry.javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(entry)
    }

private fun entry(key: String): Any? = lockMap[lockKeyOf(key, LockType.CREATE)]

private fun show(entry: Any?): String =
    if (entry == null) {
        "no entry"
    } else {
        "entry(token=${field(entry, "token")}, expiresAt=${field(entry, "expiresAt")}, " +
            "isHeld=${(field(entry, "isHeld") as AtomicBoolean).get()}, " +
            "permits=${(field(entry, "semaphore") as Semaphore).availablePermits()})"
    }

private fun tryAt(
    lock: KeyLocalLock,
    key: String,
    now: Long,
): String? {
    FakeClock.fixed = now
    return runBlocking { lock.tryLock(key, LockType.CREATE) }
}

private fun release(
    lock: KeyLocalLock,
    key: String,
    token: String,
): Boolean = runBlocking { lock.unLock(key, LockType.CREATE, token) }

/** A: tryLock at three clock readings around holder 1's lease (gLease = base + timeout). */
private fun boundary(
    lock: KeyLocalLock,
    timeout: Long,
    base: Long,
) {
    println("== A: tryLock around the lease. Holder 1 acquires at base; its lease is base+$timeout.")
    for (delta in listOf(timeout - 1, timeout, timeout + 1)) {
        val key = "A$delta"
        val t1 = tryAt(lock, key, base)
        val before = show(entry(key))
        val t2 = tryAt(lock, key, base + delta)
        val after = show(entry(key))
        val r1 = t1?.let { release(lock, key, it) }
        val sidecar =
            if (delta <= timeout) {
                "sidecar: res == NULL (progress_live); holder 1 stays held (P3a)"
            } else {
                "sidecar: res != NULL and backed (expired_takeover)"
            }
        println("  now = base+$delta: holder1=$t1 $before")
        println("    tryLock -> $t2 ; map: $after ; then unLock(holder1) -> $r1   [$sidecar]")
        t2?.let { release(lock, key, it) }
    }
}

/** E: the same boundary with the real clock. Holder 2 calls tryLock in the millisecond that equals holder 1's expiresAt. */
private fun boundaryRealClock() {
    FakeClock.fixed = -1L
    val timeout = 5L
    val realLock = KeyLocalLock(timeout)
    repeat(20) { attempt ->
        val key = "E$attempt"
        val t1 = runBlocking { realLock.tryLock(key, LockType.CREATE) } ?: return@repeat
        val lease = field(entry(key)!!, "expiresAt") as Long
        while (System.currentTimeMillis() < lease) Thread.onSpinWait()
        val presentBefore = entry(key)?.let { field(it, "token") } == t1
        val t2 = runBlocking { realLock.tryLock(key, LockType.CREATE) }
        val after = entry(key)
        val now2 = after?.let { (field(it, "expiresAt") as Long) - timeout }
        if (presentBefore && t2 != null && now2 == lease) {
            println("== E: real clock, timeout=$timeout: holder1=$t1 lease=$lease; tryLock with now=$now2 -> $t2 ; map: ${show(after)} ; unLock(holder1) -> ${release(realLock, key, t1)}")
            return
        }
        t2?.let { release(realLock, key, it) }
        release(realLock, key, t1)
    }
    println("== E: real clock: no attempt landed exactly on the lease")
}

/**
 * B: the real monitor at the lease boundary. Key "S<e>" gets an entry whose expiresAt is e, a real future time.
 * Nothing but the monitor removes these entries, and the monitor removes "S<e>" only when its clock reading is >= e.
 * So if "S<e>" is absent while the real clock still reads e, the monitor removed it with now == e.
 */
private fun sweep(
    lock: KeyLocalLock,
    timeout: Long,
) {
    val start = System.currentTimeMillis() + 1500
    val width = 3500
    for (i in 0 until width) {
        val e = start + i
        tryAt(lock, "S$e", e - timeout)
    }
    FakeClock.fixed = -1L
    val created =
        (0 until width).count { i ->
            val en = entry("S${start + i}")
            en != null && field(en, "expiresAt") == start + i
        }
    val margin = start - System.currentTimeMillis()
    println("== B: monitor at the lease boundary: $created of $width entries made; the window opens in $margin ms")
    val removedAtLease = sortedSetOf<Long>()
    val end = start + width
    while (true) {
        val t1 = System.currentTimeMillis()
        if (t1 >= end) break
        if (t1 < start) continue
        val present = lockMap.containsKey(lockKeyOf("S$t1", LockType.CREATE))
        val t2 = System.currentTimeMillis()
        if (t1 == t2 && !present) removedAtLease.add(t1)
    }
    println("  entries the monitor removed while the real clock read exactly their expiresAt: ${removedAtLease.size}")
    removedAtLease.forEach { println("    expiresAt = $it (window offset ${it - start} ms)") }
}

/** C: the other tryLock and unLock clauses on fresh keys. The clock is fixed far ahead of the real clock. */
private fun others(
    lock: KeyLocalLock,
    timeout: Long,
    base: Long,
) {
    println("== C: other clauses")
    val t = tryAt(lock, "C1", base)
    println("  progress_free/P1a_entry: new key -> $t ; ${show(entry("C1"))}")
    val r = tryAt(lock, "C1", base + 10)
    println("  refusal_keeps_entry: live key at base+10 -> $r ; ${show(entry("C1"))}")
    val wrong = release(lock, "C1", "local-999999999")
    println("  unLock(wrong token) -> $wrong ; ${show(entry("C1"))}")
    val held = entry("C1")
    val ok = release(lock, "C1", t!!)
    println("  progress_owner: unLock(owner) -> $ok ; ${show(entry("C1"))} ; removed object: ${show(held)}")
    val stale = release(lock, "C1", t)
    println("  P1: unLock(same token again) -> $stale ; ${show(entry("C1"))}")
    val none = release(lock, "C0", "local-1")
    println("  no_entry_created: unLock(absent key) -> $none ; ${show(entry("C0"))}")

    val t3 = tryAt(lock, "C3", base)
    LocalLockLimit.maxEntries = 1L
    try {
        val u = tryAt(lock, "C2", base)
        val uEntry = show(entry("C2"))
        println("  cap_unbacked (max-entries=1, map size ${lockMap.size}): new key -> $u ; $uEntry ; unLock -> ${u?.let { release(lock, "C2", it) }}")
        val live = tryAt(lock, "C3", base + 10)
        println("  capped map, live key (holder $t3) -> $live ; ${show(entry("C3"))}")
        val exp = tryAt(lock, "C3", base + timeout + 1)
        println("  capped map, expired key -> $exp ; ${show(entry("C3"))}")
        exp?.let { release(lock, "C3", it) }
    } finally {
        LocalLockLimit.maxEntries = 0L
    }
}

/** D: 8 threads contend for one key with the clock fixed, so no lease passes. It counts overlapping holders. */
private fun exclusion(
    lock: KeyLocalLock,
    base: Long,
) {
    FakeClock.fixed = base
    val holders = AtomicInteger()
    val overlaps = AtomicInteger()
    val failedReleases = AtomicInteger()
    val acquisitions = AtomicInteger()
    val workers =
        List(8) {
            thread {
                repeat(20_000) {
                    val t = runBlocking { lock.tryLock("D", LockType.CREATE) }
                    if (t != null) {
                        acquisitions.incrementAndGet()
                        if (holders.incrementAndGet() != 1) overlaps.incrementAndGet()
                        repeat(50) { Thread.onSpinWait() }
                        holders.decrementAndGet()
                        if (!runBlocking { lock.unLock("D", LockType.CREATE, t) }) failedReleases.incrementAndGet()
                    }
                }
            }
        }
    workers.forEach { it.join() }
    println(
        "== D: exclusion, 8 threads x 20000 tries: acquisitions=${acquisitions.get()}, overlaps=${overlaps.get()}, " +
            "failed owner releases=${failedReleases.get()}, ${show(entry("D"))}",
    )
}

fun main() {
    val timeout = 100L
    // Far ahead of the real clock, so the real-clock monitor never removes these entries.
    val base = System.currentTimeMillis() + 1_000_000_000L
    val lock = KeyLocalLock(timeout)
    boundary(lock, timeout, base)
    boundaryRealClock()
    others(lock, timeout, base + 10_000)
    exclusion(lock, base + 20_000)
    sweep(lock, timeout)
    exitProcess(0)
}
