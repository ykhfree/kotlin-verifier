// Judge-B harness: runs a mutated KeyLocalLock as compiled, and forces a thread interleaving through map hooks.
// The mutant source is compiled unchanged. Only the lockMap object is swapped for a HookedMap after class init.
package judge

import sun.misc.Unsafe
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.BiFunction

// A ConcurrentHashMap whose containsKey and compute call per-thread hooks. The map behavior is unchanged.
class HookedMap<K : Any, V : Any> : ConcurrentHashMap<K, V>() {
    @Volatile var beforeContains: (String) -> Unit = {}
    @Volatile var afterContains: (String, Boolean) -> Unit = { _, _ -> }
    @Volatile var beforeCompute: (String) -> Unit = {}
    @Volatile var afterCompute: (String) -> Unit = {}
    @Volatile var onComputePre: (String, Any?) -> Unit = { _, _ -> }

    override fun containsKey(key: K): Boolean {
        val who = Thread.currentThread().name
        beforeContains(who)
        val r = super.containsKey(key)
        log("$who containsKey($key) = $r")
        afterContains(who, r)
        return r
    }

    override fun compute(key: K, remappingFunction: BiFunction<in K, in V?, out V?>): V? {
        val who = Thread.currentThread().name
        beforeCompute(who)
        onComputePre(who, super.get(key))
        val r = super.compute(key, remappingFunction)
        afterCompute(who)
        return r
    }
}

private val t0 = System.currentTimeMillis()

fun log(msg: String) = println("[+${System.currentTimeMillis() - t0} ms] $msg")

// Swap the static final lockMap of a KeyLocalLock class for a HookedMap. The class must be initialized first.
@Suppress("UNCHECKED_CAST")
fun installHookedMap(cls: Class<*>): HookedMap<String, Any> {
    Class.forName(cls.name, true, cls.classLoader)
    val f = cls.getDeclaredField("lockMap")
    val uf = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
    val unsafe = uf.get(null) as Unsafe
    val map = HookedMap<String, Any>()
    @Suppress("DEPRECATION")
    unsafe.putObject(unsafe.staticFieldBase(f), unsafe.staticFieldOffset(f), map)
    f.isAccessible = true
    check(f.get(null) === map) { "lockMap swap failed" }
    return map
}

// A readable snapshot of one LockInfo (null when there is no entry).
fun snap(info: Any?): String {
    if (info == null) return "no entry"
    fun field(n: String) = info.javaClass.getDeclaredField(n).apply { isAccessible = true }.get(info)
    val sem = field("semaphore") as java.util.concurrent.Semaphore
    val held = field("isHeld") as java.util.concurrent.atomic.AtomicBoolean
    return "entry{permits=${sem.availablePermits()}, isHeld=${held.get()}, token=${field("token")}, " +
        "expiresAt=+${(field("expiresAt") as Long) - t0} ms}"
}

fun expiresAt(info: Any?): Long =
    info!!.javaClass.getDeclaredField("expiresAt").apply { isAccessible = true }.get(info) as Long

fun rel(epochMillis: Long) = "+${epochMillis - t0} ms"

fun latch() = CountDownLatch(1)

fun CountDownLatch.await5() = check(await(5, TimeUnit.SECONDS)) { "latch timeout" }

fun thread(name: String, body: () -> Unit): Thread = Thread(body, name).apply { start() }

// Prints PASS or FAIL for one observed fact. The harness exits with 1 if any fact fails.
var failures = 0

fun expect(what: String, ok: Boolean) {
    println((if (ok) "  PASS " else "  FAIL ") + what)
    if (!ok) failures++
}
