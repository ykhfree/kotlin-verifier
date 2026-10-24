// Judge-B scenarios. Each one runs on the original build (mutant = false) and on a mutant build (mutant = true),
// and checks what the run shows. Times are real clock readings, printed relative to the JVM start.
package judge

import java.util.concurrent.atomic.AtomicReference

class Api(
    val map: HookedMap<String, Any>,
    val ck: String, // the complete lock key of the key "k" with LockType.CREATE
    val tryLock: () -> String?,
    val unLock: (String) -> Boolean,
    val setCap: (Long) -> Unit,
)

fun entry(api: Api) = snap(api.map[api.ck])

// A's lock is taken by B 5 ms after A got it (AOR-03) or at once (COR-01, MOP-02).
fun takeover(api: Api, mutant: Boolean, gapMillis: Long, timeout: Long) {
    val tA = api.tryLock(); log("A tryLock -> $tA; ${entry(api)}")
    if (gapMillis > 0) Thread.sleep(gapMillis)
    val tB = api.tryLock(); log("B tryLock -> $tB; ${entry(api)}")
    val uA = api.unLock(tA!!); log("A unLock($tA) -> $uA; ${entry(api)}")
    expect("B gets a token while A's lease ($timeout ms) has not run out: ${tB != null}", (tB != null) == mutant)
    expect("A can no longer release its own lock: ${!uA}", (!uA) == mutant)
}

// RET-06: unLock returns true but the entry stays.
fun keepAfterUnlock(api: Api, mutant: Boolean) {
    val tA = api.tryLock(); log("A tryLock -> $tA; ${entry(api)}")
    val ok = api.unLock(tA!!); log("A unLock($tA) -> $ok; ${entry(api)}")
    val pre = api.map[api.ck]
    expect("unLock returned true", ok)
    expect("an entry is still in the map after the release: ${pre != null}", (pre != null) == mutant)
    val preExp = pre?.let { expiresAt(it) }
    val before = System.currentTimeMillis()
    val tB = api.tryLock(); log("B tryLock -> $tB; ${entry(api)}")
    if (mutant) {
        expect("B acquires the left-over entry although B's clock (<= ${rel(before)}) is before its expiresAt " +
            "(${rel(preExp!!)})", tB != null && before <= preExp)
    }
}

// RET-08: unLock of a token nobody holds, on an empty map.
fun unlockEmpty(api: Api, mutant: Boolean) {
    val ok = api.unLock("local-999"); log("X unLock(local-999) on an empty map -> $ok; ${entry(api)}")
    expect("unLock returns true without releasing anything: $ok", ok == mutant)
}

// SDL-12: the first tryLock of a key creates an entry but returns null.
fun firstLockNull(api: Api, mutant: Boolean, timeout: Long) {
    val t1 = api.tryLock(); log("A tryLock on an empty map -> $t1; ${entry(api)}")
    expect("the first tryLock of a free key returns null: ${t1 == null}", (t1 == null) == mutant)
    val t2 = api.tryLock(); log("B tryLock -> $t2; ${entry(api)}")
    expect("B is refused too", t2 == null)
    Thread.sleep(timeout + 30)
    val t3 = api.tryLock(); log("C tryLock after the timeout -> $t3; ${entry(api)}")
    expect("only a takeover after the timeout gets the lock", t3 != null)
}

// RACY-01: A's racy read runs before B creates the entry; A's compute runs after B got the lock.
fun racy01(api: Api, mutant: Boolean, cap: Boolean) {
    if (cap) api.setCap(1)
    val tB = AtomicReference<String?>()
    api.map.beforeCompute = { who ->
        if (who == "A") {
            log("A reached its compute; B runs its whole tryLock now")
            thread("B") { tB.set(api.tryLock()); log("B tryLock -> ${tB.get()}; ${entry(api)}") }.join()
        }
    }
    val tA = AtomicReference<String?>()
    thread("A") { tA.set(api.tryLock()) }.join()
    api.map.beforeCompute = {}
    log("A tryLock -> ${tA.get()}; ${entry(api)}")
    val goneAfterA = api.map[api.ck] == null
    val uB = api.unLock(tB.get()!!); log("B unLock(${tB.get()}) -> $uB; ${entry(api)}")
    expect("B got the lock first: ${tB.get()}", tB.get() != null)
    expect("A also gets a token while B's lock is live: ${tA.get()}", (tA.get() != null) == mutant)
    expect("B can no longer release its lock: ${!uB}", (!uB) == mutant)
    if (cap) expect("at the cap, A's tryLock removed B's entry: $goneAfterA", goneAfterA == mutant)
}

// RACY-03: B read its clock long ago; A's stale racy read sends A past the acquire; C (old clock) acquires.
fun racy03(api: Api, mutant: Boolean, timeout: Long) {
    val aAtCompute = latch(); val bDone = latch(); val aDone = latch()
    val cPre = AtomicReference<Long?>()
    api.map.beforeContains = { who -> if (who == "C") bDone.await5() }
    api.map.beforeCompute = { who ->
        when (who) {
            "B" -> aAtCompute.await5()
            "A" -> { aAtCompute.countDown(); bDone.await5() }
            "C" -> aDone.await5()
        }
    }
    api.map.onComputePre = { who, e ->
        if (who == "C") { cPre.set(e?.let { expiresAt(it) }); log("C compute pre-state: ${snap(e)}") }
    }
    api.map.afterCompute = { who -> if (who == "B") bDone.countDown(); if (who == "A") aDone.countDown() }
    val tB = AtomicReference<String?>(); val tA = AtomicReference<String?>(); val tC = AtomicReference<String?>()
    val b = thread("B") { tB.set(api.tryLock()); log("B tryLock -> ${tB.get()}; ${entry(api)}") }
    val c = thread("C") { tC.set(api.tryLock()); log("C tryLock -> ${tC.get()}; ${entry(api)}") }
    Thread.sleep(timeout + 40)
    val a = thread("A") { tA.set(api.tryLock()); log("A tryLock -> ${tA.get()}; ${entry(api)}") }
    a.join(); b.join(); c.join()
    api.map.beforeContains = {}; api.map.beforeCompute = {}; api.map.afterCompute = {}; api.map.onComputePre = { _, _ -> }
    expect("B got the lock: ${tB.get()}", tB.get() != null)
    expect("A finds B's expired entry and returns null: ${tA.get() == null}", (tA.get() == null) == mutant)
    if (mutant) {
        val nowC = expiresAt(api.map[api.ck]) - timeout
        val pre = cPre.get()
        expect("C acquires: ${tC.get()}", tC.get() != null)
        expect("C's compute saw an entry, and C's clock ${rel(nowC)} <= its expiresAt ${pre?.let { rel(it) }} " +
            "(progress_live antecedent), yet C got a token", pre != null && nowC <= pre && tC.get() != null)
    } else {
        expect("C is refused by A's live lock", tC.get() == null)
    }
}

// RACY-06: X presents "local-1" before Y's tryLock issues it.
fun racy06(api: Api, mutant: Boolean) {
    val tY = AtomicReference<String?>()
    api.map.beforeCompute = { who ->
        if (who == "X") {
            log("X reached its compute; Y runs its whole tryLock now")
            thread("Y") { tY.set(api.tryLock()); log("Y tryLock -> ${tY.get()}; ${entry(api)}") }.join()
        }
    }
    val uX = AtomicReference<Boolean>()
    val pre = AtomicReference<Any?>()
    api.map.onComputePre = { who, e -> if (who == "X") pre.set(snap(e)) }
    thread("X") { uX.set(api.unLock("local-1")) }.join()
    api.map.beforeCompute = {}; api.map.onComputePre = { _, _ -> }
    log("X unLock(local-1) -> ${uX.get()}; X's compute pre-state: ${pre.get()}; now ${entry(api)}")
    expect("Y got local-1: ${tY.get()}", tY.get() == "local-1")
    expect("X's compute saw the entry carrying local-1, yet X got false: ${uX.get()}", uX.get() == !mutant)
    if (mutant) {
        val uY = api.unLock("local-1"); log("Y unLock(local-1) -> $uY; ${entry(api)}")
        expect("the holder Y still releases its lock", uY)
    }
}

// SDL-06: a takeover does not renew expiresAt, so the next caller takes the new holder's lock at once.
fun takeoverNoRenew(api: Api, mutant: Boolean, timeout: Long) {
    val tA = api.tryLock(); log("A tryLock -> $tA; ${entry(api)}")
    Thread.sleep(timeout + 30)
    val tC = api.tryLock(); log("C tryLock after A's timeout -> $tC; ${entry(api)}")
    Thread.sleep(2)
    val tD = api.tryLock(); log("D tryLock 2 ms later -> $tD; ${entry(api)}")
    val uC = api.unLock(tC!!); log("C unLock($tC) -> $uC; ${entry(api)}")
    expect("C took over A's expired lock", tC != null)
    expect("D takes C's lock 2 ms after C got it (C's lease is $timeout ms): ${tD != null}", (tD != null) == mutant)
    expect("C can no longer release its own lock: ${!uC}", (!uC) == mutant)
}

// MOP-02: every tryLock on an entry takes it, and the semaphore gains a permit on each expired takeover.
fun permitsGrow(api: Api, mutant: Boolean, timeout: Long) {
    val tA = api.tryLock(); log("A tryLock -> $tA; ${entry(api)}")
    val tB = api.tryLock(); log("B tryLock at once -> $tB; ${entry(api)}")
    expect("B takes A's live lock: ${tB != null}", (tB != null) == mutant)
    Thread.sleep(timeout + 30)
    val tC = api.tryLock(); log("C tryLock after the timeout -> $tC; ${entry(api)}")
    Thread.sleep(timeout + 30)
    val tD = api.tryLock(); log("D tryLock after the next timeout -> $tD; ${entry(api)}")
    val sem = api.map[api.ck]!!.javaClass.getDeclaredField("semaphore").apply { isAccessible = true }
        .get(api.map[api.ck]) as java.util.concurrent.Semaphore
    expect("the entry's semaphore has 2 permits (P2 allows at most 1): ${sem.availablePermits()}",
        (sem.availablePermits() == 2) == mutant)
}

// SDL-02: an expired lock is not taken over; the key stays blocked until the monitor removes the entry.
fun deadEntry(api: Api, mutant: Boolean, timeout: Long) {
    val tA = api.tryLock(); log("A tryLock -> $tA; ${entry(api)}")
    Thread.sleep(timeout + 30)
    val tB = api.tryLock(); log("B tryLock after A's timeout -> $tB; ${entry(api)}")
    expect("B takes over A's expired lock: ${tB != null}", (tB != null) == !mutant)
    if (!mutant) return
    val uA = api.unLock(tA!!); log("A unLock($tA) -> $uA; ${entry(api)}")
    expect("A's late unLock is refused", !uA)
    val start = System.currentTimeMillis()
    var t: String? = null
    var tries = 0
    while (t == null && System.currentTimeMillis() - start < 3000) {
        t = api.tryLock(); tries++
        if (t == null) Thread.sleep(20)
    }
    val waited = System.currentTimeMillis() - start
    log("a tryLock succeeds after $tries tries and $waited ms -> $t; ${entry(api)}")
    expect("every tryLock is refused until the monitor drops the dead entry ($waited ms)", t != null && waited > 100)
}

// RET-11 a: the monitor frees an expired entry but never drops it.
fun sweepKeeps(api: Api, mutant: Boolean) {
    val tA = api.tryLock(); log("A tryLock -> $tA; ${entry(api)}")
    Thread.sleep(2600) // at least two monitor passes after the expiry
    val e = api.map[api.ck]; log("after 2.6 s: ${snap(e)}")
    expect("the expired entry is still in the map: ${e != null}", (e != null) == mutant)
    if (mutant) expect("it is free: ${snap(e)}", snap(e).startsWith("entry{permits=1, isHeld=false, token=null"))
}

// RET-11 b: C reads its clock before the expiry, and its compute runs after the monitor pass on the key.
fun sweepStaleClock(api: Api, mutant: Boolean, timeout: Long) {
    val tA = api.tryLock(); log("A tryLock -> $tA; ${entry(api)}")
    val expA = expiresAt(api.map[api.ck])
    val swept = latch()
    val pre = AtomicReference<Long?>()
    api.map.afterCompute = { who ->
        if (who.startsWith("DefaultDispatcher") && System.currentTimeMillis() > expA) {
            log("monitor pass on the key done: ${entry(api)}"); swept.countDown()
        }
    }
    api.map.beforeCompute = { who -> if (who == "C") swept.await5() }
    api.map.onComputePre = { who, e ->
        if (who == "C") { pre.set(e?.let { expiresAt(it) }); log("C compute pre-state: ${snap(e)}") }
    }
    val tC = AtomicReference<String?>()
    thread("C") { tC.set(api.tryLock()) }.join()
    api.map.afterCompute = {}; api.map.beforeCompute = {}; api.map.onComputePre = { _, _ -> }
    val nowC = expiresAt(api.map[api.ck]) - timeout
    log("C tryLock -> ${tC.get()}; C's clock was ${rel(nowC)}; ${entry(api)}")
    expect("C gets a token", tC.get() != null)
    expect("C's compute saw an entry with C's clock ${rel(nowC)} <= its expiresAt ${pre.get()?.let { rel(it) }}: " +
        "${pre.get() != null}", (pre.get() != null && nowC <= pre.get()!!) == mutant)
}

fun finish(): Nothing {
    println(if (failures == 0) "RESULT: every expectation met" else "RESULT: $failures expectation(s) failed")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
