// Judge 2 harness. It drives one KeyLocalLock with the fake clocks from stubs/TimeUtils.kt.
// The copy of the code under test reads FakeClock.sweepNow() where the original reads System.currentTimeMillis().
package judge

import com.linecorp.cse.reqshield.kotlin.coroutine.KeyLocalLock
import com.linecorp.cse.reqshield.kotlin.coroutine.LockType
import com.linecorp.cse.reqshield.support.config.LocalLockLimit
import com.linecorp.cse.reqshield.support.utils.FakeClock
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

private fun lockMap(): ConcurrentHashMap<String, Any> {
    val field = KeyLocalLock::class.java.getDeclaredField("lockMap")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return field.get(null) as ConcurrentHashMap<String, Any>
}

private fun mapKey(key: String) = "reqshield:lock:${key}_CREATE"

private fun field(info: Any, name: String): Any? {
    val f = info.javaClass.getDeclaredField(name)
    f.isAccessible = true
    return f.get(info)
}

// Free permits of the entry's semaphore: java.util.concurrent or kotlinx.coroutines.sync.
private fun permits(info: Any): Int {
    val sem = field(info, "semaphore")!!
    val m = sem.javaClass.methods.first { it.name == "availablePermits" || it.name == "getAvailablePermits" }
    m.isAccessible = true
    return m.invoke(sem) as Int
}

private fun entry(key: String): String {
    val info = lockMap()[mapKey(key)] ?: return "no entry"
    val held = (field(info, "isHeld") as java.util.concurrent.atomic.AtomicBoolean).get()
    return "token=${field(info, "token")} expiresAt=${field(info, "expiresAt")} isHeld=$held permits=${permits(info)}"
}

fun main() = runBlocking {
    // The monitor clock stays at 0 until S6, so the monitor drops nothing before S6.
    FakeClock.sweep = 0L
    val lock = KeyLocalLock(100)
    val c = LockType.CREATE

    // S1: tryLock at now == expiresAt of a held entry. progress_live requires null.
    FakeClock.lock = 1000
    val t1 = lock.tryLock("s1", c)!!
    println("S1 after tryLock at now=1000: ${entry("s1")}")
    FakeClock.lock = 1100
    val t2 = lock.tryLock("s1", c)
    println("S1 tryLock at now=1100 (== expiresAt) -> $t2   [sidecar progress_live: must be null]")
    println("S1 entry now: ${entry("s1")}")
    println("S1 unLock(old token $t1) -> ${lock.unLock("s1", c, t1)}   [false = $t1 ended at now == its lease: P3a]")

    // S2 (control): one millisecond before and after the lease.
    FakeClock.lock = 1000
    lock.tryLock("s2", c)
    FakeClock.lock = 1099
    println("S2 tryLock at now=1099 -> ${lock.tryLock("s2", c)}   [expect null]")
    FakeClock.lock = 1101
    println("S2 tryLock at now=1101 -> ${lock.tryLock("s2", c)}   [expect a token]   entry: ${entry("s2")}")

    // S3: unLock with a wrong token, the right token, then the right token again.
    FakeClock.lock = 5000
    val t3 = lock.tryLock("s3", c)!!
    println("S3 unLock(wrong) -> ${lock.unLock("s3", c, "local-999999")}, unLock($t3) -> ${lock.unLock("s3", c, t3)}, " +
        "again -> ${lock.unLock("s3", c, t3)}, entry: ${entry("s3")}")

    // S4: unLock on a key without an entry must not make one.
    println("S4 unLock on absent key -> ${lock.unLock("s4", c, "local-1")}, entry: ${entry("s4")}")

    // S5: cap. A new key gets an unbacked token and no entry.
    LocalLockLimit.reject = true
    val u1 = lock.tryLock("s5", c)
    val u2 = lock.tryLock("s5", c)
    LocalLockLimit.reject = false
    println("S5 cap: tokens $u1, $u2, entry: ${entry("s5")}, unLock($u1) -> ${lock.unLock("s5", c, u1!!)}")

    // S6: the monitor at now == expiresAt.
    FakeClock.lock = 1999
    val a = lock.tryLock("s6-lease2099", c)!!
    FakeClock.lock = 2000
    val b = lock.tryLock("s6-lease2100", c)!!
    FakeClock.lock = 2001
    val d = lock.tryLock("s6-lease2101", c)!!
    FakeClock.sweep = 2100
    Thread.sleep(3500)
    println("S6 monitor at now=2100: lease 2099 -> ${entry("s6-lease2099")}   [expect no entry]")
    println("S6 monitor at now=2100: lease 2100 -> ${entry("s6-lease2100")}   [sidecar P3a: must stay]")
    println("S6 monitor at now=2100: lease 2101 -> ${entry("s6-lease2101")}   [expect kept]")
    println("S6 unLock: 2099 -> ${lock.unLock("s6-lease2099", c, a)}, 2100 -> ${lock.unLock("s6-lease2100", c, b)}, " +
        "2101 -> ${lock.unLock("s6-lease2101", c, d)}")
}
