package judge

import com.linecorp.cse.reqshield.kotlin.coroutine.KeyLocalLock
import com.linecorp.cse.reqshield.kotlin.coroutine.LockType
import com.linecorp.cse.reqshield.kotlin.coroutine.lockKeyOf
import com.linecorp.cse.reqshield.support.config.LocalLockLimit
import com.linecorp.cse.reqshield.support.utils.TestClock
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Modifier
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TIMEOUT = 1000L
private val TYPE = LockType.CREATE

/** One observation: what the sidecar requires and what the code did. */
private data class Row(
    val property: String,
    val scenario: String,
    val required: String,
    val observed: String,
    val ok: Boolean,
)

private val rows = mutableListOf<Row>()

private fun row(property: String, scenario: String, required: String, observed: String, ok: Boolean) {
    rows += Row(property, scenario, required, observed, ok)
}

/** Read-only reflection view of the implementation's shared state. Only reset() writes (clear). */
private object Probe {
    @Suppress("UNCHECKED_CAST")
    val lockMap: ConcurrentHashMap<String, Any> by lazy {
        val outer = KeyLocalLock::class.java
        val direct = runCatching { outer.getDeclaredField("lockMap") }.getOrNull()
        if (direct != null) {
            direct.isAccessible = true
            return@lazy direct.get(null) as ConcurrentHashMap<String, Any>
        }
        val companionClass = outer.declaredClasses.first { it.simpleName == "Companion" }
        val field = companionClass.getDeclaredField("lockMap").also { it.isAccessible = true }
        val companion = outer.getDeclaredField("Companion").also { it.isAccessible = true }.get(null)
        field.get(if (Modifier.isStatic(field.modifiers)) null else companion) as ConcurrentHashMap<String, Any>
    }

    fun read(obj: Any, name: String): Any? {
        val field = obj.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(obj)
    }

    fun entry(key: String): Any? = lockMap[lockKeyOf(key, TYPE)]

    fun token(key: String): String? = entry(key)?.let { read(it, "token") as String? }

    fun expiresAt(key: String): Long? = entry(key)?.let { read(it, "expiresAt") as Long }

    fun permits(info: Any): Int =
        when (val sem = read(info, "semaphore")!!) {
            is java.util.concurrent.Semaphore -> sem.availablePermits()
            is kotlinx.coroutines.sync.Semaphore -> sem.availablePermits
            else -> error("unknown semaphore type ${sem.javaClass}")
        }

    fun isHeld(info: Any): Boolean = (read(info, "isHeld") as AtomicBoolean).get()
}

private fun reset() {
    Probe.lockMap.clear()
    LocalLockLimit.maxEntries = 0L
    LocalLockLimit.calls.set(0)
}

/** A clock base far ahead of the real clock, so the background monitor never treats test entries as expired. */
private fun farBase(): Long = System.currentTimeMillis() + 100_000_000L

private fun KeyLocalLock.lockAt(now: Long, key: String): String? {
    TestClock.nowMs = now
    return runBlocking { tryLock(key, TYPE) }
}

private fun KeyLocalLock.unlock(key: String, token: String): Boolean = runBlocking { unLock(key, TYPE, token) }

/** MI_INV INV-E plus P2 on live entries: permits == 0, isHeld, token != null. */
private fun checkEntries(scenario: String) {
    val bad =
        Probe.lockMap.entries.filter { (_, info) ->
            Probe.permits(info) != 0 || !Probe.isHeld(info) || Probe.read(info, "token") == null
        }
    row(
        "INV-E/P2",
        "$scenario: all live entries",
        "permits==0, isHeld, token!=null",
        if (bad.isEmpty()) "ok (${Probe.lockMap.size} entries)" else "bad keys ${bad.map { it.key }}",
        bad.isEmpty(),
    )
}

// S1: the expiry boundary now == expiresAt == gLease(A).
private fun s1Boundary() {
    reset()
    val lock = KeyLocalLock(TIMEOUT)
    val t0 = farBase()
    val tA = lock.lockAt(t0, "s1")
    row(
        "progress_free/P1a_entry",
        "S1.a A.tryLock now=t0",
        "token; entry carries it; expiresAt=t0+1000",
        "token=$tA entryToken=${Probe.token("s1")} expiresAt-t0=${Probe.expiresAt("s1")?.minus(t0)}",
        tA != null && Probe.token("s1") == tA && Probe.expiresAt("s1") == t0 + TIMEOUT,
    )
    val b1 = lock.lockAt(t0 + TIMEOUT - 1, "s1")
    row("progress_live", "S1.b B.tryLock now=t0+999", "null", "$b1", b1 == null)
    val b2 = lock.lockAt(t0 + TIMEOUT, "s1")
    row(
        "progress_live",
        "S1.c B.tryLock now=t0+1000 (= expiresAt = gLease(A))",
        "null",
        "$b2 (entry token after: ${Probe.token("s1")})",
        b2 == null,
    )
    val aRel = lock.unlock("s1", tA!!)
    row(
        "P3a(tryLock)",
        "S1.d A.unLock(tA) right after S1.c",
        "true: A's acquisition may end only when now > gLease(A)",
        "$aRel",
        aRel,
    )
    checkEntries("S1")
}

// S2: expired takeover strictly after the lease, then stale and owner releases.
private fun s2ExpiredTakeover() {
    reset()
    val lock = KeyLocalLock(TIMEOUT)
    val t0 = farBase()
    val tA = lock.lockAt(t0, "s2")!!
    val tB = lock.lockAt(t0 + TIMEOUT + 1, "s2")
    row(
        "expired_takeover",
        "S2.a B.tryLock now=t0+1001",
        "token, backed",
        "$tB entryToken=${Probe.token("s2")}",
        tB != null && Probe.token("s2") == tB,
    )
    val aRel = lock.unlock("s2", tA)
    row(
        "P1/P3a(unLock)",
        "S2.b stale A.unLock(tA) after takeover",
        "false; B's entry stays",
        "$aRel entryToken=${Probe.token("s2")}",
        !aRel && Probe.token("s2") == tB,
    )
    val info = Probe.entry("s2")
    val bRel = lock.unlock("s2", tB!!)
    row(
        "progress_owner/P1",
        "S2.c B.unLock(tB)",
        "true; entry removed",
        "$bRel entry=${Probe.entry("s2")}",
        bRel && Probe.entry("s2") == null,
    )
    val p = Probe.permits(info!!)
    row("P2", "S2.d permits of the released entry", "0..1", "$p", p in 0..1)
    checkEntries("S2")
}

// S3: the sidecar probe, cross-key and forged tokens.
private fun s3Probe() {
    reset()
    val lock = KeyLocalLock(TIMEOUT)
    val now = farBase()
    val t1 = lock.lockAt(now, "s3")
    val t2 = lock.lockAt(now, "s3")
    val info = Probe.entry("s3")
    val ok = lock.unlock("s3", t1!!)
    val ok2 = lock.unlock("s3", t1)
    row(
        "probe",
        "S3.a t1=tryLock; t2=tryLock; unLock(t1); unLock(t1)",
        "t1!=null, t2==null, true, false",
        "t1=$t1 t2=$t2 ok=$ok ok2=$ok2",
        t2 == null && ok && !ok2,
    )
    val p = Probe.permits(info!!)
    row("P2", "S3.b permits after one release", "0..1", "$p", p in 0..1)
    val tx = lock.lockAt(now, "s3x")!!
    val ty = lock.lockAt(now, "s3y")!!
    val cross = lock.unlock("s3y", tx)
    row("P1", "S3.c token of key x used on key y", "false", "$cross", !cross && Probe.token("s3y") == ty)
    val forged = lock.unlock("s3y", "local-999999999")
    row("P1", "S3.d forged token string", "false", "$forged", !forged && Probe.token("s3y") == ty)
    checkEntries("S3")
}

// S4: concurrent acquisition and a stale release racing a takeover.
private fun s4Concurrent() {
    reset()
    val lock = KeyLocalLock(TIMEOUT)
    val pool = Executors.newFixedThreadPool(16)
    val now = farBase()
    var badRounds = 0
    repeat(300) { round ->
        val key = "s4-$round"
        TestClock.nowMs = now
        val latch = CountDownLatch(1)
        val wins = AtomicInteger()
        val futures =
            (1..16).map {
                pool.submit(
                    Callable {
                        latch.await()
                        if (runBlocking { lock.tryLock(key, TYPE) } != null) wins.incrementAndGet()
                    },
                )
            }
        latch.countDown()
        futures.forEach { it.get() }
        if (wins.get() != 1) badRounds++
    }
    row("P3c/progress_live", "S4.a 16 threads x 300 rounds, same key, same now", "exactly 1 token per round", "rounds with !=1 token: $badRounds", badRounds == 0)

    var badTakeover = 0
    repeat(300) { round ->
        val key = "s4t-$round"
        TestClock.nowMs = now
        runBlocking { lock.tryLock(key, TYPE) }!!
        TestClock.nowMs = now + TIMEOUT + 1
        val latch = CountDownLatch(1)
        val wins = AtomicInteger()
        val futures =
            (1..16).map {
                pool.submit(
                    Callable {
                        latch.await()
                        if (runBlocking { lock.tryLock(key, TYPE) } != null) wins.incrementAndGet()
                    },
                )
            }
        latch.countDown()
        futures.forEach { it.get() }
        if (wins.get() != 1) badTakeover++
    }
    row("P3c/expired_takeover", "S4.b 16 threads take over one expired entry x 300", "exactly 1 token per round", "rounds with !=1 token: $badTakeover", badTakeover == 0)

    var lostB = 0
    var aWonOrder = 0
    repeat(300) { round ->
        val key = "s4r-$round"
        TestClock.nowMs = now
        val tA = runBlocking { lock.tryLock(key, TYPE) }!!
        TestClock.nowMs = now + TIMEOUT + 1
        val latch = CountDownLatch(1)
        val fA = pool.submit(Callable { latch.await(); runBlocking { lock.unLock(key, TYPE, tA) } })
        val fB = pool.submit(Callable { latch.await(); runBlocking { lock.tryLock(key, TYPE) } })
        latch.countDown()
        val aOk = fA.get()
        val tB = fB.get()
        if (aOk) aWonOrder++
        if (tB == null || Probe.token(key) != tB) lostB++
    }
    row(
        "P1/P3c",
        "S4.c stale A.unLock races B.takeover x 300",
        "B always ends up owning the entry",
        "rounds where B lost its entry: $lostB (A released first in $aWonOrder rounds)",
        lostB == 0,
    )
    pool.shutdown()
    checkEntries("S4")
}

// S5: the cap path.
private fun s5Cap() {
    reset()
    LocalLockLimit.maxEntries = 1
    val lock = KeyLocalLock(TIMEOUT)
    val t0 = farBase()
    val t1 = lock.lockAt(t0, "c1")
    row("progress_free", "S5.a first key under cap 1", "token, backed", "$t1 entryToken=${Probe.token("c1")}", t1 != null && Probe.token("c1") == t1)
    val t2 = lock.lockAt(t0, "c2")
    row(
        "cap_unbacked/P3b",
        "S5.b new key c2 at cap",
        "token; no entry for c2; map size 1",
        "$t2 entry(c2)=${Probe.entry("c2")} size=${Probe.lockMap.size}",
        t2 != null && Probe.entry("c2") == null && Probe.lockMap.size == 1,
    )
    val rel2 = lock.unlock("c2", t2!!)
    row("P1", "S5.c unLock(c2, unbacked token)", "false", "$rel2", !rel2)
    val callsBefore = LocalLockLimit.calls.get()
    val again = lock.lockAt(t0 + 1, "c1")
    val callsLive = LocalLockLimit.calls.get() - callsBefore
    row("progress_live", "S5.d live existing key c1 at cap", "null; cap not consulted", "$again capCalls=$callsLive", again == null && callsLive == 0)
    val take = lock.lockAt(t0 + TIMEOUT + 1, "c1")
    val callsTake = LocalLockLimit.calls.get() - callsBefore - callsLive
    row(
        "expired_takeover",
        "S5.e expired existing key c1 at cap",
        "token, backed; cap not consulted",
        "$take entryToken=${Probe.token("c1")} capCalls=$callsTake",
        take != null && Probe.token("c1") == take && callsTake == 0,
    )
    checkEntries("S5")
    LocalLockLimit.maxEntries = 0
}

// S7: lockTimeoutMillis so large that now + lockTimeoutMillis wraps around Long.
private fun s7Overflow() {
    reset()
    val lock = KeyLocalLock(Long.MAX_VALUE)
    val t0 = System.currentTimeMillis()
    val tA = lock.lockAt(t0, "s7")!!
    val exp = Probe.expiresAt("s7")
    val presentBeforeB = Probe.token("s7") == tA
    val tB = lock.lockAt(t0 + 1, "s7")
    row(
        "P3a(math Int lease)",
        "S7 lockTimeoutMillis=Long.MAX_VALUE, B one ms after A",
        "null: gLease(A)=t0+MAX (unbounded Int) has not passed",
        "B=$tB; A's expiresAt=$exp (wrapped); A's entry present just before B=$presentBeforeB",
        tB == null,
    )
}

// S8: lockTimeoutMillis = 0, B in the same millisecond as A.
private fun s8ZeroTimeout() {
    reset()
    val lock = KeyLocalLock(0L)
    val t0 = farBase()
    val tA = lock.lockAt(t0, "s8")
    val tB = lock.lockAt(t0, "s8")
    row("progress_live", "S8 lockTimeoutMillis=0, A then B at the same now", "B gets null (now <= expiresAt)", "A=$tA B=$tB", tB == null)
}

// S6: monitor sweep against the real clock, no code change. A key with expiresAt >= c2 that is already gone
// proves the monitor removed it at now == expiresAt (its now <= c2 <= expiresAt, and it removed the entry).
private fun s6SweepBoundary() {
    reset()
    val lock = KeyLocalLock(TIMEOUT)
    val n = 2600
    val start = System.currentTimeMillis() + 300
    val expiry = HashMap<String, Long>()
    for (i in 0 until n) {
        val e = start + i
        val key = "s6-$i"
        lock.lockAt(e - TIMEOUT, key) ?: error("setup failed for $key")
        expiry[lockKeyOf(key, TYPE)] = e
    }
    val alive = HashSet(expiry.keys)
    var removed = 0
    var witnesses = 0
    var minGap = Long.MAX_VALUE
    var example = ""
    val deadline = start + n + 2500
    while (alive.isNotEmpty() && System.currentTimeMillis() < deadline) {
        val present = HashSet(Probe.lockMap.keys)
        val c2 = System.currentTimeMillis()
        val it = alive.iterator()
        while (it.hasNext()) {
            val k = it.next()
            if (k !in present) {
                val e = expiry.getValue(k)
                removed++
                if (c2 - e < minGap) minGap = c2 - e
                if (e >= c2) {
                    witnesses++
                    if (example.isEmpty()) example = "; e.g. entry with expiresAt=$e already gone when clock read $c2"
                }
                it.remove()
            }
        }
    }
    row(
        "P3a(sweep)",
        "S6 monitor vs real clock, $n entries with consecutive expiresAt",
        "no entry removed while clock <= expiresAt (min gap >= 1)",
        "removed=$removed/$n, boundary witnesses=$witnesses, min(c2-expiresAt)=$minGap$example",
        witnesses == 0,
    )
}

fun main(args: Array<String>) {
    val id = args.firstOrNull() ?: "?"
    s1Boundary()
    s2ExpiredTakeover()
    s3Probe()
    s4Concurrent()
    s5Cap()
    s7Overflow()
    s8ZeroTimeout()
    s6SweepBoundary()
    println("=== $id ===")
    for (r in rows) {
        println("${if (r.ok) "PASS" else "FAIL"} | ${r.property} | ${r.scenario} | required: ${r.required} | observed: ${r.observed}")
    }
}
