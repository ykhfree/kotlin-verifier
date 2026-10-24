package com.linecorp.cse.reqshield.kotlin.coroutine

import com.linecorp.cse.reqshield.support.config.LocalLockLimit
import com.linecorp.cse.reqshield.support.utils.FakeClock
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// Judge-work driver. Each scenario prints what the implementation did and what a sidecar property requires.
// "OK" means the required result was observed. "VIOLATION" means it was not.

private const val K = "k"
private val TYPE = LockType.CREATE

private fun mapKeyOf(key: String) = lockKeyOf(key, TYPE)

@Suppress("UNCHECKED_CAST")
private val lockMap: ConcurrentHashMap<String, Any> by lazy {
    val f = KeyLocalLock::class.java.getDeclaredField("lockMap")
    f.isAccessible = true
    f.get(null) as ConcurrentHashMap<String, Any>
}

private fun field(obj: Any, name: String): Any? {
    val f = obj.javaClass.getDeclaredField(name)
    f.isAccessible = true
    return f.get(obj)
}

private fun tokenOf(info: Any) = field(info, "token") as String?

private fun expiresAtOf(info: Any) = field(info, "expiresAt") as Long

private fun permitsOf(info: Any) = (field(info, "semaphore") as Semaphore).availablePermits()

private fun heldOf(info: Any) = (field(info, "isHeld") as AtomicBoolean).get()

private fun entry(key: String = K) = lockMap[mapKeyOf(key)]

private fun rel(v: Long, base: Long): String {
    val d = v - base
    return if (base != 0L && d in -1_000_000_000L..1_000_000_000L) "base${if (d >= 0) "+" else ""}$d" else "$v"
}

private fun describe(
    info: Any?,
    base: Long = 0,
): String =
    if (info == null) {
        "no entry"
    } else {
        "entry{token=${tokenOf(info)}, expiresAt=${rel(expiresAtOf(info), base)}, isHeld=${heldOf(info)}, permits=${permitsOf(info)}}"
    }

private fun verdict(ok: Boolean) = if (ok) "OK" else "VIOLATION"

private fun now() = System.currentTimeMillis()

// A base time far in the future, so the real monitor thread (system clock) never sweeps these entries.
private fun futureBase() = now() + 10_000_000L

private fun tryLock(
    lock: KeyLocalLock,
    key: String = K,
) = runBlocking { lock.tryLock(key, TYPE) }

private fun unLock(
    lock: KeyLocalLock,
    token: String,
    key: String = K,
) = runBlocking { lock.unLock(key, TYPE, token) }

private fun reset() {
    FakeClock.fixed = null
    LocalLockLimit.maxEntries = 0
    lockMap.clear()
}

// Calls the companion's private sweep function. The four implementations name it differently.
private fun sweep() {
    val companionField = KeyLocalLock::class.java.getDeclaredField("Companion")
    companionField.isAccessible = true
    val companion = companionField.get(null)
    val names = listOf("removeExpiredEntries", "dropExpiredEntries", "removeExpiredLocks")
    val m =
        companion.javaClass.declaredMethods.firstOrNull { it.name in names && it.parameterCount == 0 }
            ?: error("no sweep method in ${companion.javaClass.declaredMethods.map { it.name }}")
    m.isAccessible = true
    m.invoke(companion)
}

private fun s1BoundaryFakeClock() {
    println("S1 tryLock at now == expiresAt (fake clock, lockTimeoutMillis = 100)")
    reset()
    val lock = KeyLocalLock(100)
    val b = futureBase()
    FakeClock.fixed = b
    val t1 = tryLock(lock)
    println("  now=base     tryLock -> $t1; ${describe(entry(), b)}")
    FakeClock.fixed = b + 99
    val tMid = tryLock(lock)
    println("  now=base+99  tryLock -> $tMid   [progress_live requires null: ${verdict(tMid == null)}]")
    FakeClock.fixed = b + 100
    val t2 = tryLock(lock)
    val after = entry()
    println("  now=base+100 tryLock -> $t2; ${describe(after, b)}")
    println("    progress_live (old has, isHeld, now <= old expiresAt ==> res == NULL): ${verdict(t2 == null)}")
    println("    P3a tryLock (t1 may end only if now > gLease(t1) = base+100): ${verdict(after != null && tokenOf(after) == t1)}")
}

private fun s1BoundaryRealClock() {
    println("S1r tryLock twice in one millisecond (real clock, lockTimeoutMillis = 0)")
    val lock = KeyLocalLock(0)
    repeat(1000) { attempt ->
        reset()
        val r0 = now()
        val t1 = tryLock(lock)
        val t2 = tryLock(lock)
        val r1 = now()
        if (r0 == r1) {
            println("  attempt=$attempt both calls read now=r0: t1=$t1 t2=$t2; ${describe(entry(), r0)}")
            println("    progress_live (now <= expiresAt ==> null): ${verdict(t2 == null)}")
            return
        }
    }
    println("  could not bracket two calls inside one millisecond")
}

private fun s1BoundaryRealClockTimeout5() {
    println("S1r5 second tryLock exactly 5 ms after the first (real clock, lockTimeoutMillis = 5)")
    val lock = KeyLocalLock(5)
    repeat(500) { attempt ->
        reset()
        val r0 = now()
        val t1 = tryLock(lock)
        if (now() != r0) return@repeat
        while (now() < r0 + 5) Thread.onSpinWait()
        val r2 = now()
        val t2 = tryLock(lock)
        val r3 = now()
        if (r2 == r0 + 5 && r3 == r2) {
            println("  attempt=$attempt t1=$t1 (now=r0), t2=$t2 (now=r0+5); ${describe(entry(), r0)}")
            println("    progress_live (now <= expiresAt ==> null): ${verdict(t2 == null)}")
            return
        }
    }
    println("  could not hit now == r0 + 5")
}

private fun s2SweepBoundary() {
    println("S2 sweep at now == expiresAt (system clock for the sweep, lockTimeoutMillis = 100)")
    val lock = KeyLocalLock(100)
    repeat(1000) { attempt ->
        reset()
        val r0 = now()
        FakeClock.fixed = r0 - 100 // tryLock reads r0-100, so expiresAt == gLease == r0
        val t1 = tryLock(lock)
        FakeClock.fixed = null
        val before = entry()
        sweep()
        val r1 = now()
        if (r0 == r1) {
            val after = entry()
            println("  attempt=$attempt tryLock -> $t1, ${describe(before, r0)}; sweep read now=r0; after: ${describe(after, r0)}")
            println("    P3a sweep (removal needs now > gLease = r0): ${verdict(after != null)}")
            return
        }
    }
    println("  could not bracket the sweep inside one millisecond")
}

private fun s2SweepBoundaryRealClock() {
    println("S2r sweep in the same millisecond as tryLock (real clock only, lockTimeoutMillis = 0)")
    val lock = KeyLocalLock(0)
    repeat(1000) { attempt ->
        reset()
        val r0 = now()
        val t1 = tryLock(lock)
        sweep()
        val r1 = now()
        if (r0 == r1) {
            println("  attempt=$attempt tryLock -> $t1 and sweep both read now=r0 (expiresAt = gLease = r0); after: ${describe(entry(), r0)}")
            println("    P3a sweep (removal needs now > gLease = r0): ${verdict(entry() != null)}")
            return
        }
    }
    println("  could not bracket tryLock and sweep inside one millisecond")
}

private fun s3OwnerReleaseRealClock() {
    println("S3r owner unLock in the same millisecond as tryLock (real clock only, lockTimeoutMillis = 0)")
    val lock = KeyLocalLock(0)
    repeat(1000) { attempt ->
        reset()
        val r0 = now()
        val t1 = tryLock(lock)!!
        val ok = unLock(lock, t1)
        val r1 = now()
        if (r0 == r1) {
            println("  attempt=$attempt tryLock -> $t1, unLock(t1) -> $ok, both at now=r0; after: ${describe(entry(), r0)}")
            println("    progress_owner: ${verdict(ok)}")
            return
        }
    }
    println("  could not bracket tryLock and unLock inside one millisecond")
}

private fun s2SweepControl() {
    println("S2c sweep strictly after expiry and strictly before it (system clock)")
    val lock = KeyLocalLock(100)
    reset()
    val r0 = now()
    FakeClock.fixed = r0 - 101 // expiresAt = r0 - 1 < now
    tryLock(lock, "old")
    FakeClock.fixed = r0 + 10_000 // expiresAt far ahead
    tryLock(lock, "young")
    FakeClock.fixed = null
    sweep()
    println("  expired entry after sweep: ${describe(entry("old"))}  [sweep may remove: removed=${entry("old") == null}]")
    println("  live entry after sweep: ${describe(entry("young"), r0)}  [must stay: ${verdict(entry("young") != null)}]")
}

private fun s3OwnerRelease() {
    println("S3 owner unLock at and after the lease end (fake clock, lockTimeoutMillis = 100)")
    val lock = KeyLocalLock(100)
    for (d in listOf(99L, 100L, 101L, 500L)) {
        reset()
        val b = futureBase()
        FakeClock.fixed = b
        val t1 = tryLock(lock)!!
        val info = entry()!!
        FakeClock.fixed = b + d
        val ok = unLock(lock, t1)
        val after = entry()
        println(
            "  tryLock at base -> $t1; unLock(t1) at base+$d -> $ok; after: ${describe(after, b)}; " +
                "released LockInfo permits=${permitsOf(info)}",
        )
        println("    progress_owner (old has && old token == t ==> res): ${verdict(ok)}")
    }
}

private fun s4Overflow() {
    println("S4 lockTimeoutMillis = Long.MAX_VALUE (fake clock)")
    reset()
    val lock = KeyLocalLock(Long.MAX_VALUE)
    val b = futureBase()
    FakeClock.fixed = b
    val t1 = tryLock(lock)
    println("  now=base   tryLock -> $t1; ${describe(entry())}   (gLease = base + Long.MAX_VALUE)")
    FakeClock.fixed = b + 1
    val t2 = tryLock(lock)
    println("  now=base+1 tryLock -> $t2; ${describe(entry())}")
    println("    P3a tryLock (t1 may end only if now > gLease(t1)): ${verdict(t2 == null)}")
    val holder = entry()?.let { tokenOf(it) }
    if (holder != null) {
        FakeClock.fixed = b + 2
        val ok = unLock(lock, holder)
        println("  now=base+2 unLock(current holder $holder) -> $ok   [progress_owner: ${verdict(ok)}]")
    }
    println("S4r lockTimeoutMillis = Long.MAX_VALUE (real clock): two tryLock calls back to back")
    reset()
    val r1 = tryLock(lock)
    val r2 = tryLock(lock)
    println("  t1=$r1 t2=$r2; ${describe(entry())}")
}

private fun s4NegativeClock() {
    println("S4n clock before 1970 (fake clock = -1000, lockTimeoutMillis = 100): informative only")
    reset()
    val lock = KeyLocalLock(100)
    FakeClock.fixed = -1000
    val t1 = tryLock(lock)
    println("  tryLock -> $t1; ${describe(entry())}   (gLease = -900)")
    FakeClock.fixed = -800
    val t2 = tryLock(lock)
    println("  now=-800 tryLock -> $t2; ${describe(entry())}")
}

private fun s5Probe() {
    println("S5 sidecar probe (fake clock, lockTimeoutMillis = 100)")
    reset()
    val lock = KeyLocalLock(100)
    val b = futureBase()
    FakeClock.fixed = b
    val t1 = tryLock(lock)
    val info = entry()
    val t2 = tryLock(lock)
    val ok = unLock(lock, t1!!)
    val ok2 = unLock(lock, t1)
    println("  t1=$t1 t2=$t2 unLock(t1)=$ok unLock(t1) again=$ok2; ${describe(entry())}; released LockInfo permits=${permitsOf(info!!)}")
    println("    probe: ${verdict(t1 != null && t2 == null && ok && !ok2)}")
}

private fun s6Cap() {
    println("S6 cap path (LocalLockLimit.maxEntries = 1, fake clock, lockTimeoutMillis = 100)")
    reset()
    val lock = KeyLocalLock(100)
    val b = futureBase()
    FakeClock.fixed = b
    LocalLockLimit.maxEntries = 1
    val a = tryLock(lock, "k1")
    println("  tryLock(k1) -> $a; k1 ${describe(entry("k1"), b)}   [progress_free: ${verdict(a != null && entry("k1")?.let { tokenOf(it) } == a)}]")
    val u1 = tryLock(lock, "k2")
    println("  tryLock(k2) -> $u1; k2 ${describe(entry("k2"), b)}   [cap_unbacked: ${verdict(u1 != null && entry("k2") == null)}]")
    val u2 = tryLock(lock, "k2")
    println("  tryLock(k2) again -> $u2; k2 ${describe(entry("k2"), b)}   [cap_unbacked: ${verdict(u2 != null && u2 != u1 && entry("k2") == null)}]")
    val r = unLock(lock, u1!!, "k2")
    println("  unLock(k2, $u1) -> $r   [KDoc false when not held: ${verdict(!r)}]")
    val live = tryLock(lock, "k1")
    println("  tryLock(k1) while live -> $live   [progress_live: ${verdict(live == null)}]")
    FakeClock.fixed = b + 101
    val take = tryLock(lock, "k1")
    println(
        "  now=base+101 tryLock(k1) on expired entry, map full -> $take; ${describe(entry("k1"), b)}   " +
            "[expired_takeover: ${verdict(take != null && entry("k1")?.let { tokenOf(it) } == take)}]",
    )
    LocalLockLimit.maxEntries = 0
}

private fun s7StaleToken() {
    println("S7 stale and forged tokens after a takeover (fake clock, lockTimeoutMillis = 100)")
    reset()
    val lock = KeyLocalLock(100)
    val b = futureBase()
    FakeClock.fixed = b
    val t1 = tryLock(lock)!!
    FakeClock.fixed = b + 101
    val t2 = tryLock(lock)
    println("  t1=$t1 at base; tryLock at base+101 -> $t2   [expired_takeover: ${verdict(t2 != null && entry()?.let { tokenOf(it) } == t2)}]")
    val stale = unLock(lock, t1)
    println("  unLock(t1) -> $stale   [P1: ${verdict(!stale)}]; ${describe(entry(), b)}")
    val forged = unLock(lock, "local-999999999")
    println("  unLock(local-999999999) -> $forged   [P1: ${verdict(!forged)}]")
    val own = unLock(lock, t2!!)
    println("  unLock(t2) -> $own   [progress_owner: ${verdict(own)}]; ${describe(entry(), b)}   [P1 !has: ${verdict(entry() == null)}]")
}

private fun s8Stress() {
    println("S8 mutual exclusion stress (real clock, lockTimeoutMillis = 60000, 8 threads x 20000 rounds)")
    reset()
    val lock = KeyLocalLock(60_000)
    val holders = AtomicInteger(0)
    val overlap = AtomicInteger(0)
    val failedOwnerRelease = AtomicInteger(0)
    val staleRelease = AtomicInteger(0)
    val acquired = AtomicInteger(0)
    val threads =
        (1..8).map {
            Thread {
                var lastReleased: String? = null
                repeat(20_000) {
                    val t = tryLock(lock)
                    if (t != null) {
                        acquired.incrementAndGet()
                        if (holders.incrementAndGet() != 1) overlap.incrementAndGet()
                        repeat(20) { Thread.onSpinWait() }
                        holders.decrementAndGet()
                        if (!unLock(lock, t)) failedOwnerRelease.incrementAndGet()
                        lastReleased = t
                    } else {
                        val s = lastReleased
                        if (s != null && unLock(lock, s)) staleRelease.incrementAndGet()
                    }
                }
            }
        }
    threads.forEach { it.start() }
    threads.forEach { it.join() }
    println(
        "  acquired=${acquired.get()} overlap=${overlap.get()} failedOwnerRelease=${failedOwnerRelease.get()} " +
            "staleRelease=${staleRelease.get()} mapSize=${lockMap.size}",
    )
}

private fun s9MonitorLiveness() {
    println("S9 the real monitor removes an expired entry (real clock, lockTimeoutMillis = 200)")
    reset()
    val lock = KeyLocalLock(200)
    val t = tryLock(lock)
    Thread.sleep(2600)
    println("  tryLock -> $t; after 2.6 s: ${describe(entry())}")
}

private fun s10InterruptMonitor() {
    println("S10 interrupt the thread named req-shield-local-lock-monitor, then check the monitor still sweeps")
    reset()
    val monitors = Thread.getAllStackTraces().keys.filter { it.name == "req-shield-local-lock-monitor" }
    println("  monitor threads by that name: ${monitors.size}")
    monitors.forEach { it.interrupt() }
    Thread.sleep(300)
    println("  alive after interrupt: ${monitors.map { it.isAlive }}")
    val lock = KeyLocalLock(100)
    val t = tryLock(lock)
    Thread.sleep(2600)
    println("  tryLock -> $t; after 2.6 s: ${describe(entry())}")
}

fun main(args: Array<String>) {
    println("=== ${args.firstOrNull() ?: "?"} ===")
    s5Probe()
    s1BoundaryFakeClock()
    s1BoundaryRealClock()
    s1BoundaryRealClockTimeout5()
    s2SweepBoundary()
    s2SweepBoundaryRealClock()
    s2SweepControl()
    s3OwnerRelease()
    s3OwnerReleaseRealClock()
    s4Overflow()
    s4NegativeClock()
    s6Cap()
    s7StaleToken()
    s8Stress()
    s9MonitorLiveness()
    s10InterruptMonitor()
}
