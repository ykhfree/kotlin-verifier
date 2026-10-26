// Judge 3 harness. It drives one KeyLocalLock implementation through fixed scenarios and prints what it observes.
// It reads the module state (lockMap, LockInfo fields) by reflection and calls the monitor's pass by reflection.
package judge3

import com.linecorp.cse.reqshield.kotlin.coroutine.KeyLocalLock
import com.linecorp.cse.reqshield.kotlin.coroutine.LockType
import com.linecorp.cse.reqshield.support.config.LocalLockLimit
import com.linecorp.cse.reqshield.support.constant.ConfigValues
import com.linecorp.cse.reqshield.support.utils.JudgeClock
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Modifier
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val outer: Class<*> = KeyLocalLock::class.java
private val companionClass: Class<*> = Class.forName(outer.name + "\$Companion")

private fun companionInstance(): Any {
    val f = outer.getDeclaredField("Companion")
    f.isAccessible = true
    return f.get(null)
}

// Reads a module-level field. Kotlin keeps companion properties as static fields of the outer class.
private fun moduleField(name: String): Any? {
    for (c in listOf(outer, companionClass)) {
        val f = c.declaredFields.firstOrNull { it.name == name } ?: continue
        f.isAccessible = true
        return f.get(if (Modifier.isStatic(f.modifiers)) null else companionInstance())
    }
    error("field $name not found")
}

@Suppress("UNCHECKED_CAST")
private fun lockMap(): ConcurrentHashMap<String, Any> = moduleField("lockMap") as ConcurrentHashMap<String, Any>

private fun mapKey(key: String) = "${ConfigValues.LOCK_KEY_PREFIX}${key}_${LockType.CREATE.name}"

private fun entry(key: String): Any? = lockMap()[mapKey(key)]

private fun field(obj: Any, name: String): Any? {
    val f = obj.javaClass.getDeclaredField(name)
    f.isAccessible = true
    return f.get(obj)
}

// java.util.concurrent.Semaphore has availablePermits(); kotlinx.coroutines.sync.Semaphore has getAvailablePermits().
private fun permits(sem: Any): Int {
    val m = sem.javaClass.methods.first { it.name == "availablePermits" || it.name == "getAvailablePermits" }
    m.isAccessible = true
    return m.invoke(sem) as Int
}

private fun describe(e: Any?): String {
    if (e == null) return "absent"
    val held = (field(e, "isHeld") as AtomicBoolean).get()
    val id = Integer.toHexString(System.identityHashCode(e))
    return "LockInfo@$id(token=${field(e, "token")}, expiresAt=${field(e, "expiresAt")}, isHeld=$held, " +
        "permits=${permits(field(e, "semaphore")!!)}, semaphore=${field(e, "semaphore")!!.javaClass.name})"
}

// Runs one monitor pass: the companion's removeExpiredEntries().
private fun sweepOnce() {
    val m = companionClass.declaredMethods.firstOrNull { it.name == "removeExpiredEntries" && it.parameterCount == 0 }
        ?: error("removeExpiredEntries not found: " + companionClass.declaredMethods.joinToString { it.name })
    m.isAccessible = true
    if (Modifier.isStatic(m.modifiers)) m.invoke(null) else m.invoke(companionInstance())
}

// S1: tryLock at the lease boundary. The lease is T+100.
private suspend fun s1(lock: KeyLocalLock, base: Long) {
    println("-- S1 tryLock at the lease boundary (lockTimeoutMillis=100, first tryLock at T)")
    JudgeClock.fixed.set(base)
    val t1 = lock.tryLock("s1", LockType.CREATE)!!
    println("  T+0   tryLock(s1) -> $t1 ; entry ${describe(entry("s1"))}")
    JudgeClock.fixed.set(base + 99)
    val r99 = lock.tryLock("s1", LockType.CREATE)
    println("  T+99  tryLock(s1) -> $r99 ; entry ${describe(entry("s1"))}")
    JudgeClock.fixed.set(base + 100)
    val before = describe(entry("s1"))
    val r100 = lock.tryLock("s1", LockType.CREATE)
    println("  T+100 tryLock(s1) -> $r100 ; before $before ; after ${describe(entry("s1"))}")
    println("        [progress_live requires null here; P3a requires that $t1 stays held]")
    val stale = lock.unLock("s1", LockType.CREATE, t1)
    println("  T+100 unLock(s1, $t1) -> $stale ; entry ${describe(entry("s1"))}")
    JudgeClock.fixed.set(base)
    val u1 = lock.tryLock("s1b", LockType.CREATE)
    JudgeClock.fixed.set(base + 101)
    val u101 = lock.tryLock("s1b", LockType.CREATE)
    println("  control s1b: T+0 -> $u1 ; T+101 -> $u101 ; entry ${describe(entry("s1b"))} [expired_takeover]")
}

// S2: the owner's unLock while the entry still carries its token.
private suspend fun s2(lock: KeyLocalLock, base: Long) {
    println("-- S2 owner's unLock while its entry still carries its token (lease T+100, no monitor pass)")
    for ((key, at) in listOf("s2a" to 99L, "s2b" to 100L, "s2c" to 5_000L)) {
        JudgeClock.fixed.set(base)
        val t = lock.tryLock(key, LockType.CREATE)!!
        JudgeClock.fixed.set(base + at)
        val before = describe(entry(key))
        val ok = lock.unLock(key, LockType.CREATE, t)
        println("  $key: tryLock at T -> $t ; unLock at T+$at -> $ok ; before $before ; after ${describe(entry(key))}")
    }
    println("        [progress_owner requires true in all three rows]")
}

// S3: one monitor pass at now == expiresAt, on the real clock.
private suspend fun s3(lock: KeyLocalLock) {
    println("-- S3 one monitor pass while System.currentTimeMillis() == expiresAt (real clock)")
    for (trial in 1..300) {
        val r = System.currentTimeMillis() + 30
        val kEq = "s3eq-$trial"
        val kNext = "s3next-$trial"
        JudgeClock.fixed.set(r - 100) // lease = r
        val tEq = lock.tryLock(kEq, LockType.CREATE)
        JudgeClock.fixed.set(r - 99) // lease = r + 1
        lock.tryLock(kNext, LockType.CREATE)
        JudgeClock.fixed.set(-1)
        while (System.currentTimeMillis() < r) Thread.onSpinWait()
        val clockBefore = System.currentTimeMillis()
        val eqBefore = entry(kEq)
        val nextBefore = entry(kNext)
        if (clockBefore != r || eqBefore == null || nextBefore == null) continue
        sweepOnce()
        val clockAfter = System.currentTimeMillis()
        if (clockAfter != r) continue
        println("  trial $trial: the clock read $r before and after the pass")
        println("    lease r   ($tEq): before ${describe(eqBefore)} ; after ${describe(entry(kEq))}")
        println("    lease r+1        : before ${describe(nextBefore)} ; after ${describe(entry(kNext))}")
        println("        [sweep P3a: a pass with now == $r must not end $tEq, whose lease is $r]")
        return
    }
    println("  no clean trial in 300 attempts")
}

// S4: the sidecar's non-vacuity probe.
private suspend fun s4(lock: KeyLocalLock, base: Long) {
    println("-- S4 sidecar probe (fixed clock)")
    JudgeClock.fixed.set(base)
    val t1 = lock.tryLock("p", LockType.CREATE)!!
    val backed = entry("p")?.let { field(it, "token") } == t1
    val t2 = lock.tryLock("p", LockType.CREATE)
    val ok = lock.unLock("p", LockType.CREATE, t1)
    val ok2 = lock.unLock("p", LockType.CREATE, t1)
    println("  t1=$t1 backed=$backed ; t2=$t2 ; unLock(t1)=$ok ; unLock(t1) again=$ok2 ; entry ${describe(entry("p"))}")
}

// S5: the cap. A new key gets an unbacked token. An existing key is not subject to the cap.
private suspend fun s5(lock: KeyLocalLock, base: Long) {
    println("-- S5 cap (LocalLockLimit.maxEntries = current map size)")
    JudgeClock.fixed.set(base)
    val held = lock.tryLock("cap-held", LockType.CREATE)
    LocalLockLimit.maxEntries = maxOf(1L, lockMap().size.toLong())
    try {
        val u = lock.tryLock("cap-new", LockType.CREATE)
        println("  new key at cap: tryLock -> $u ; entry ${describe(entry("cap-new"))} [cap_unbacked]")
        val rel = lock.unLock("cap-new", LockType.CREATE, u!!)
        println("  unLock of the unbacked token -> $rel ; entry ${describe(entry("cap-new"))} [P1: false]")
        val again = lock.tryLock("cap-held", LockType.CREATE)
        println("  existing key at cap, live ($held): tryLock -> $again [progress_live: null]")
        JudgeClock.fixed.set(base + 101)
        val take = lock.tryLock("cap-held", LockType.CREATE)
        println("  existing key at cap, expired: tryLock -> $take ; entry ${describe(entry("cap-held"))} [expired_takeover]")
    } finally {
        LocalLockLimit.maxEntries = 0L
    }
}

// S6: stress with a fixed clock, so no lease ends. Checks mutual exclusion and the owner's release.
private fun s6(base: Long) {
    println("-- S6 stress: 8 threads x 20000 rounds on 4 keys, fixed clock")
    JudgeClock.fixed.set(base)
    val lock = KeyLocalLock(100)
    val keys = (0 until 4).map { "stress-$it" }
    val holders = keys.associateWith { AtomicInteger(0) }
    val overlap = AtomicInteger(0)
    val ownerRefused = AtomicInteger(0)
    val acquired = AtomicInteger(0)
    val threads = (0 until 8).map { ti ->
        Thread {
            val rnd = Random(ti.toLong())
            runBlocking {
                repeat(20_000) {
                    val k = keys[rnd.nextInt(keys.size)]
                    val t = lock.tryLock(k, LockType.CREATE)
                    if (t != null) {
                        acquired.incrementAndGet()
                        if (holders.getValue(k).incrementAndGet() != 1) overlap.incrementAndGet()
                        holders.getValue(k).decrementAndGet()
                        if (!lock.unLock(k, LockType.CREATE, t)) ownerRefused.incrementAndGet()
                    }
                }
            }
        }
    }
    threads.forEach { it.start() }
    threads.forEach { it.join() }
    println(
        "  acquired=${acquired.get()} overlapping holders=${overlap.get()} " +
            "owner unLock refused=${ownerRefused.get()} entries left=${keys.count { entry(it) != null }}",
    )
}

fun main(args: Array<String>) {
    println("=== ${args.getOrElse(0) { "?" }} ===")
    // The monitor reads System.currentTimeMillis(). Leases at a far-future fake time never look expired to it.
    val far = System.currentTimeMillis() + 1_000_000_000L
    val lock = KeyLocalLock(100)
    runBlocking {
        s1(lock, far)
        s2(lock, far + 10_000)
        s4(lock, far + 20_000)
        s5(lock, far + 30_000)
    }
    s6(far + 40_000)
    runBlocking { s3(lock) }
    JudgeClock.fixed.set(-1)
    System.exit(0)
}
