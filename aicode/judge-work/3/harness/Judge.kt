// Judge harness: replays concrete scenarios against one KeyLocalLock implementation.
// It reads the private shared state through reflection and never changes it.
package com.linecorp.cse.reqshield.kotlin.coroutine

import com.linecorp.cse.reqshield.support.config.LocalLockLimit
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object Peek {
    private val outer = KeyLocalLock::class.java

    @Suppress("UNCHECKED_CAST")
    val map: ConcurrentHashMap<String, Any> =
        outer.getDeclaredField("lockMap").let {
            it.isAccessible = true
            it.get(null) as ConcurrentHashMap<String, Any>
        }

    private fun field(info: Any, name: String): Any? =
        info.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(info)
        }

    fun entry(key: String, type: LockType): Any? = map[lockKeyOf(key, type)]

    fun token(info: Any): String? = field(info, "token") as String?

    fun expiresAt(info: Any): Long = field(info, "expiresAt") as Long

    fun isHeld(info: Any): Boolean = (field(info, "isHeld") as AtomicBoolean).get()

    fun permits(info: Any): Int =
        when (val s = field(info, "semaphore")) {
            is java.util.concurrent.Semaphore -> s.availablePermits()
            is kotlinx.coroutines.sync.Semaphore -> s.availablePermits
            else -> error("unknown semaphore type: ${s?.javaClass}")
        }

    fun describe(info: Any?): String =
        if (info == null) {
            "absent"
        } else {
            "{token=${token(info)}, expiresAt=${expiresAt(info)}, isHeld=${isHeld(info)}, permits=${permits(info)}}"
        }

    // Runs one pass of the companion's sweep function. Returns false if the monitor body has no such function.
    fun sweepOnce(): Boolean {
        val companionClass = Class.forName("${outer.name}\$Companion")
        val sweep =
            companionClass.declaredMethods.firstOrNull { it.name.startsWith("removeExpired") && it.parameterCount == 0 }
                ?: return false
        sweep.isAccessible = true
        sweep.invoke(outer.getDeclaredField("Companion").get(null))
        return true
    }
}

fun now() = System.currentTimeMillis()

fun spinUntilAtLeast(t: Long) {
    while (now() < t) Thread.onSpinWait()
}

suspend fun release(lock: KeyLocalLock, key: String, type: LockType, vararg tokens: String?) {
    tokens.filterNotNull().forEach { lock.unLock(key, type, it) }
}

// S0: cap path. Needs an empty map, so it runs first.
fun s0Cap() = runBlocking {
    check(Peek.map.isEmpty()) { "map is not empty at start" }
    val longLock = KeyLocalLock(60_000)
    val shortLock = KeyLocalLock(20)
    val tA = longLock.tryLock("cap-A", LockType.CREATE)
    val tC = shortLock.tryLock("cap-C", LockType.CREATE)
    val cEntry = Peek.entry("cap-C", LockType.CREATE)!!
    LocalLockLimit.maxEntries = Peek.map.size.toLong()
    try {
        val tB1 = longLock.tryLock("cap-B", LockType.CREATE)
        val bAfter = Peek.describe(Peek.entry("cap-B", LockType.CREATE))
        val tB2 = longLock.tryLock("cap-B", LockType.CREATE)
        val releaseB1 = tB1?.let { longLock.unLock("cap-B", LockType.CREATE, it) }
        val tA2 = longLock.tryLock("cap-A", LockType.CREATE)
        spinUntilAtLeast(Peek.expiresAt(cEntry) + 2)
        val cPresent = Peek.entry("cap-C", LockType.CREATE) === cEntry
        val tC2 = shortLock.tryLock("cap-C", LockType.CREATE)
        val cAfter = Peek.describe(Peek.entry("cap-C", LockType.CREATE))
        println("S0 cap: maxEntries=${LocalLockLimit.maxEntries}, map size=${Peek.map.size} before new key B")
        println("   new key B: tryLock#1=$tB1 entryAfter=$bAfter; tryLock#2=$tB2; unLock(B, tryLock#1 token)=$releaseB1")
        println("   live key A (holder $tA): tryLock=$tA2")
        println("   expired key C (holder $tC, entry present before call=$cPresent): tryLock=$tC2 entryAfter=$cAfter")
        release(shortLock, "cap-C", LockType.CREATE, tC2)
    } finally {
        LocalLockLimit.maxEntries = 0
    }
    release(longLock, "cap-A", LockType.CREATE, tA)
}

// S1: the sidecar probe scenario.
fun s1Probe() = runBlocking {
    val lock = KeyLocalLock(60_000)
    val t1 = lock.tryLock("probe", LockType.CREATE)!!
    val e1 = Peek.describe(Peek.entry("probe", LockType.CREATE))
    val t2 = lock.tryLock("probe", LockType.CREATE)
    val ok = lock.unLock("probe", LockType.CREATE, t1)
    val e2 = Peek.describe(Peek.entry("probe", LockType.CREATE))
    val ok2 = lock.unLock("probe", LockType.CREATE, t1)
    println("S1 probe: t1=$t1 entry=$e1; t2=$t2; unLock(t1)=$ok entry=$e2; unLock(t1) again=$ok2")
}

// S2: a second tryLock whose clock reading is the holder's expiresAt (= gLease) plus offset.
fun s2BoundaryTryLock(offset: Long) = runBlocking {
    val lock = KeyLocalLock(100)
    for (attempt in 1..100) {
        val key = "s2-$offset-$attempt"
        val t1 = lock.tryLock(key, LockType.CREATE) ?: continue
        val info0 = Peek.entry(key, LockType.CREATE) ?: continue
        val e = Peek.expiresAt(info0) + offset
        spinUntilAtLeast(e)
        val c0 = now()
        val presentBefore = Peek.entry(key, LockType.CREATE) === info0
        val t2 = lock.tryLock(key, LockType.CREATE)
        val c1 = now()
        val after = Peek.describe(Peek.entry(key, LockType.CREATE))
        if (c0 != e || c1 != e || !presentBefore) {
            release(lock, key, LockType.CREATE, t1, t2)
            continue
        }
        val stale = lock.unLock(key, LockType.CREATE, t1)
        println("S2 tryLock with now == expiresAt${if (offset == 0L) "" else "%+d".format(offset)} == $e (holder t1=$t1 present and held, attempt $attempt):")
        println("   second tryLock=$t2, entry after=$after, unLock(t1) afterwards=$stale")
        release(lock, key, LockType.CREATE, t2)
        return@runBlocking
    }
    println("S2: no valid boundary observation")
}

// S3: a second tryLock strictly after expiry, then a stale release by the old holder.
fun s3ExpiredTakeover() = runBlocking {
    val lock = KeyLocalLock(20)
    for (attempt in 1..20) {
        val key = "s3-$attempt"
        val t1 = lock.tryLock(key, LockType.UPDATE) ?: continue
        val info0 = Peek.entry(key, LockType.UPDATE) ?: continue
        spinUntilAtLeast(Peek.expiresAt(info0) + 2)
        if (Peek.entry(key, LockType.UPDATE) !== info0) continue
        val t2 = lock.tryLock(key, LockType.UPDATE)
        val after = Peek.describe(Peek.entry(key, LockType.UPDATE))
        val stale = lock.unLock(key, LockType.UPDATE, t1)
        val afterStale = Peek.describe(Peek.entry(key, LockType.UPDATE))
        val own = t2?.let { lock.unLock(key, LockType.UPDATE, it) }
        println("S3 tryLock with now > expiresAt (entry present): t1=$t1 -> tryLock=$t2 entry=$after")
        println("   unLock(stale t1)=$stale entry=$afterStale; unLock(new token)=$own")
        return@runBlocking
    }
    println("S3: no valid observation")
}

// S4: the owner releases after expiry while its entry is still in the map.
fun s4OwnerUnlockExpired() = runBlocking {
    val lock = KeyLocalLock(20)
    for (attempt in 1..20) {
        val key = "s4-$attempt"
        val t1 = lock.tryLock(key, LockType.CREATE) ?: continue
        val info0 = Peek.entry(key, LockType.CREATE) ?: continue
        spinUntilAtLeast(Peek.expiresAt(info0) + 2)
        if (Peek.entry(key, LockType.CREATE) !== info0) continue
        val before = Peek.describe(info0)
        val r = lock.unLock(key, LockType.CREATE, t1)
        val after = Peek.describe(Peek.entry(key, LockType.CREATE))
        println("S4 owner unLock with now > expiresAt, entry present $before: result=$r, entry after=$after")
        return@runBlocking
    }
    println("S4: no valid observation")
}

// S4b: the owner releases with now == expiresAt.
fun s4bOwnerUnlockBoundary() = runBlocking {
    val lock = KeyLocalLock(100)
    for (attempt in 1..100) {
        val key = "s4b-$attempt"
        val t1 = lock.tryLock(key, LockType.CREATE) ?: continue
        val info0 = Peek.entry(key, LockType.CREATE) ?: continue
        val e = Peek.expiresAt(info0)
        spinUntilAtLeast(e)
        val c0 = now()
        val presentBefore = Peek.entry(key, LockType.CREATE) === info0
        val r = lock.unLock(key, LockType.CREATE, t1)
        val c1 = now()
        val after = Peek.describe(Peek.entry(key, LockType.CREATE))
        if (c0 != e || c1 != e || !presentBefore) continue
        println("S4b owner unLock with now == expiresAt == $e: result=$r, entry after=$after")
        return@runBlocking
    }
    println("S4b: no valid observation")
}

// S5: one sweep pass whose clock reading is the holder's expiresAt plus offset.
fun s5SweepBoundary(offset: Long) = runBlocking {
    val lock = KeyLocalLock(100)
    for (attempt in 1..100) {
        val key = "s5-$offset-$attempt"
        val t1 = lock.tryLock(key, LockType.UPDATE) ?: continue
        val info0 = Peek.entry(key, LockType.UPDATE) ?: continue
        val e = Peek.expiresAt(info0) + offset
        spinUntilAtLeast(e)
        val c0 = now()
        val presentBefore = Peek.entry(key, LockType.UPDATE) === info0
        val hasSweep = Peek.sweepOnce()
        val c1 = now()
        if (!hasSweep) {
            println("S5: no callable sweep function (the monitor body is inline in init)")
            release(lock, key, LockType.UPDATE, t1)
            return@runBlocking
        }
        val after = Peek.describe(Peek.entry(key, LockType.UPDATE))
        if (c0 != e || c1 != e || !presentBefore) {
            release(lock, key, LockType.UPDATE, t1)
            continue
        }
        println("S5 sweep pass with now == expiresAt${if (offset == 0L) "" else "%+d".format(offset)} == $e (entry carried t1=$t1): entry after=$after")
        release(lock, key, LockType.UPDATE, t1)
        return@runBlocking
    }
    println("S5: no valid observation")
}

// S6: contention without expiry. Counts concurrent holders and stale-token releases.
fun s6Stress() {
    val lock = KeyLocalLock(60_000)
    val holders = AtomicInteger(0)
    val maxHolders = AtomicInteger(0)
    val acquisitions = AtomicLong(0)
    val ownerReleaseFailures = AtomicLong(0)
    val releasedTokens = ConcurrentLinkedQueue<String>()
    val staleReleases = AtomicLong(0)
    val stop = AtomicBoolean(false)
    val workers =
        (1..8).map {
            Thread {
                runBlocking {
                    repeat(20_000) {
                        val t = lock.tryLock("s6", LockType.CREATE)
                        if (t != null) {
                            val h = holders.incrementAndGet()
                            maxHolders.accumulateAndGet(h) { a, b -> maxOf(a, b) }
                            acquisitions.incrementAndGet()
                            holders.decrementAndGet()
                            if (lock.unLock("s6", LockType.CREATE, t)) releasedTokens.add(t) else ownerReleaseFailures.incrementAndGet()
                        }
                    }
                }
            }
        }
    val attacker =
        Thread {
            runBlocking {
                while (!stop.get()) {
                    val stale = releasedTokens.poll() ?: continue
                    if (lock.unLock("s6", LockType.CREATE, stale)) staleReleases.incrementAndGet()
                }
            }
        }
    attacker.start()
    workers.forEach { it.start() }
    workers.forEach { it.join() }
    stop.set(true)
    attacker.join()
    println(
        "S6 stress (8 threads x 20000 tryLock, timeout 60 s): acquisitions=${acquisitions.get()}, " +
            "maxConcurrentHolders=${maxHolders.get()}, ownerReleaseFailures=${ownerReleaseFailures.get()}, " +
            "staleTokenReleases=${staleReleases.get()}",
    )
}

// S7: forged tokens and a token used with the other lock type.
fun s7Forged() = runBlocking {
    val lock = KeyLocalLock(60_000)
    val t1 = lock.tryLock("s7", LockType.CREATE)!!
    val n = t1.removePrefix("local-").toLong()
    val r1 = lock.unLock("s7", LockType.CREATE, "local-${n + 1000}")
    val r2 = lock.unLock("s7", LockType.UPDATE, t1)
    val r3 = lock.unLock("s7", LockType.CREATE, "local-0")
    val entry = Peek.describe(Peek.entry("s7", LockType.CREATE))
    val r4 = lock.unLock("s7", LockType.CREATE, t1)
    println("S7 forged: unLock(unissued)=$r1, unLock(UPDATE, t1)=$r2, unLock(local-0)=$r3, entry=$entry; owner unLock=$r4")
}

// S9: lockTimeoutMillis = 0, two tryLock calls in the same millisecond.
fun s9ZeroTimeout() = runBlocking {
    val lock = KeyLocalLock(0)
    for (attempt in 1..100) {
        val key = "s9-$attempt"
        val c0 = now()
        val t1 = lock.tryLock(key, LockType.CREATE)
        val t2 = lock.tryLock(key, LockType.CREATE)
        val c1 = now()
        release(lock, key, LockType.CREATE, t1, t2)
        if (c0 != c1) continue
        println("S9 lockTimeoutMillis=0, two tryLock calls within millisecond $c0: t1=$t1, t2=$t2")
        return@runBlocking
    }
    println("S9: no valid observation")
}

// S10: the background monitor drops an unreleased entry within about two monitor intervals.
fun s10MonitorRuns() = runBlocking {
    val lock = KeyLocalLock(10)
    val t1 = lock.tryLock("s10", LockType.CREATE)
    val start = now()
    while (Peek.entry("s10", LockType.CREATE) != null && now() - start < 3000) Thread.sleep(5)
    val gone = Peek.entry("s10", LockType.CREATE) == null
    println("S10 background monitor: unreleased entry of $t1 (timeout 10 ms) ${if (gone) "dropped after ${now() - start} ms" else "still present after 3 s"}")
}

fun invE(label: String) {
    val bad =
        Peek.map.entries.filter { (_, info) -> !(Peek.permits(info) == 0 && Peek.isHeld(info) && Peek.token(info) != null) }
    println("INV-E ($label): entries=${Peek.map.size}, violations=${bad.map { it.key + Peek.describe(it.value) }}")
}

fun main() {
    println("== ${System.getProperty("impl.id")}")
    s0Cap()
    s1Probe()
    s7Forged()
    invE("after S0, S1, S7")
    s2BoundaryTryLock(-1)
    s2BoundaryTryLock(0)
    s4bOwnerUnlockBoundary()
    s5SweepBoundary(-1)
    s5SweepBoundary(0)
    s3ExpiredTakeover()
    s4OwnerUnlockExpired()
    s9ZeroTimeout()
    s6Stress()
    s10MonitorRuns()
    invE("end")
}
