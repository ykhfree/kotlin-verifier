package kv.lower

import kv.ReqShield
import kv.frontend.Frontend
import kv.kvir.DispositionKind
import kv.kvir.Module
import kv.kvir.RacyRead
import kv.kvir.checkWellFormed
import kv.kvir.incomplete
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * M4 acceptance (DESIGN-v0.md section 7): module audit, ledger and abstract types. (1) the racy reads of KeyLocalLock
 * are those of 2.14 and 4.5; (2) a write of module state outside an action is unsupported and the module INCOMPLETE;
 * (3) every FIR node of the module file is translated or in the ledger, once; (4) a String used as both Key and
 * Token is unsupported.
 */
class AuditTest {
    @TempDir
    lateinit var dir: File

    private val specDir = File(System.getProperty("kv.specDir"), "reqshield")

    private fun keyLocalLock(variant: String): Module =
        Frontend.resolve(File(specDir, "keylocallock.$variant.kv"), ReqShield.dir, ReqShield.classpath, ReqShield.kotlincArgs).module ?: fail("no module")

    /** The racy reads of the ledger: one per construct, with its Kotlin position. */
    private fun racyReads(m: Module) = m.ledger.filter { it.kind == DispositionKind.RACY_READ }.map { "${it.what} ${it.pos.line}:${it.pos.col}" }.distinct()

    /** The unsupported constructs of the ledger. */
    private fun unsupported(m: Module) = m.ledger.filter { it.kind == DispositionKind.UNSUPPORTED }.map { it.what to (it.pos.line to it.pos.col) }.distinct()

    // ---- (1) racy reads ----

    @Test
    fun `the racy reads of coroutine KeyLocalLock are the table of section 2_14`() {
        assertEquals(listOf("lockMap.keys 96:33"), racyReads(keyLocalLock("coroutine")))
    }

    @Test
    fun `the racy reads of core KeyLocalLock are the table of sections 2_14 and 4_5`() {
        // 2.14 lists lockMap.size 102:34 and 126:33 for core; 4.5 adds the lockMap.keys read both variants share.
        assertEquals(listOf("lockMap.size 102:34", "lockMap.keys 108:21", "lockMap.size 126:33"), racyReads(keyLocalLock("core")))
    }

    @Test
    fun `the racy read of reactor KeyLocalLock is the keys read of its sweep`() {
        assertEquals(listOf("lockMap.keys 104:37"), racyReads(keyLocalLock("reactor")))
    }

    @Test
    fun `a racy read that a captured local holds is that free value's origin (E4 shape)`() {
        val f = LockFixture("if (sawEntry && e != null) e.expiresAt = now\ne", before = "        val sawEntry = lockMap.containsKey(key)")
        val m = f.lower(dir)
        assertSupported(m)
        val read = f.at("lockMap.containsKey(key)")
        assertEquals(listOf("lockMap.containsKey(key) ${read.first}:${read.second}"), racyReads(m))
        val free = m.run.free.single { it.local.name == "sawEntry__${read.first}" }
        assertEquals(RacyRead::class, free.origin::class)
        assertEquals(read, free.origin.pos.line to free.origin.pos.col)
    }

    // ---- (2) writes outside actions ----

    @Test
    fun `a map write outside an action is unsupported and the module is INCOMPLETE`() {
        val f = LockFixture("if (e != null) e.expiresAt = now\ne", after = "        lockMap[key] = Info(Semaphore(1), now)")
        val m = f.lower(dir)
        assertSupported(m, complete = false)
        assertTrue(m.incomplete)
        assertEquals(listOf("lockMap operation outside an action: lockMap[key] = Info(Semaphore(1), now)" to f.at("lockMap[key] =")), unsupported(m))
    }

    @Test
    fun `a field write and a counter operation outside an action are unsupported`() {
        val f =
            LockFixture(
                "if (e != null) e.expiresAt = now\ne",
                after = "        val info = lockMap[key]\n        if (info != null) info.token = null\n        counter.incrementAndGet()",
            )
        val m = f.lower(dir)
        assertSupported(m, complete = false)
        assertEquals(
            listOf(
                "write of module state outside an action: info.token = null" to f.at("info.token = null"),
                "counter operation outside an action: counter.incrementAndGet()" to f.at("counter.incrementAndGet()"),
            ),
            unsupported(m),
        )
        // Reading the map entry outside the action is a racy read, not a write.
        assertTrue(racyReads(m).single().startsWith("lockMap[key] "), racyReads(m).toString())
    }

    @Test
    fun `a call outside an action of a helper that changes module state is unsupported`() {
        // nextToken is inlined by the action and also called after it.
        val f =
            LockFixture(
                "if (e != null) e.token = nextToken()\ne",
                after = "        println(nextToken())",
                companion = "        private fun nextToken(): String = \"local-\${counter.incrementAndGet()}\"",
            )
        val m = f.lower(dir)
        assertSupported(m, complete = false)
        val (what, at) = unsupported(m).single()
        assertEquals(f.at("nextToken())", after = "println"), at)
        assertTrue(what.startsWith("call of nextToken, which changes module state (counter operation outside an action: counter.incrementAndGet() at ${f.at("counter.incrementAndGet()").first}:"), what)
    }

    @Test
    fun `a module object without writes outside its actions is complete`() {
        // The same reads and helper calls as above, minus the writes: nothing is unsupported.
        val f = LockFixture("if (e != null) e.token = nextToken()\ne", after = "        val info = lockMap[key]\n        println(info?.token)", companion = "        private fun nextToken(): String = \"local-\${counter.incrementAndGet()}\"")
        val m = f.lower(dir)
        assertSupported(m)
        assertEquals(emptyList(), unsupported(m))
    }

    // ---- (3) ledger completeness ----

    private fun assertComplete(m: Module) {
        val translated = (m.operations.map { it.action } + m.strayActions).flatMap { it.translated }.toSet()
        val ledger = m.ledger.map { it.node }
        assertEquals(ledger.size, ledger.toSet().size, "one ledger entry per node")
        assertEquals(emptySet(), ledger.toSet() intersect translated, "no node is both translated and in the ledger")
        assertEquals((0 until m.firNodeCount).toSet(), ledger.toSet() + translated, "every node is translated or in the ledger")
        assertEquals(m.firNodeCount, ledger.size + translated.size)
        assertEquals(emptyList(), checkWellFormed(m).filter { it.rule == "W14" })
    }

    @Test
    fun `every FIR node of both KeyLocalLock files is translated or in the ledger, once`() {
        val coroutine = keyLocalLock("coroutine")
        val core = keyLocalLock("core")
        assertComplete(coroutine)
        assertComplete(core)
        assertEquals(397 to 206, coroutine.firNodeCount to coroutine.operations.flatMap { it.action.translated }.toSet().size)
        assertEquals(479 to 209, core.firNodeCount to core.operations.flatMap { it.action.translated }.toSet().size)
    }

    @Test
    fun `every FIR node of an unsupported module is translated or in the ledger, once`() {
        val f = LockFixture("try {\n    e?.expiresAt = now\n} finally {\n}\ne", after = "        lockMap[key] = Info(Semaphore(1), now)")
        val m = f.lower(dir)
        assertTrue(m.incomplete)
        assertComplete(m)
    }

    // ---- (4) abstract types ----

    @Test
    fun `a String used as both Key and Token is unsupported`() {
        // The compute key is a Key; writing it to the token field makes it a Token too.
        val f = LockFixture("if (e != null) e.token = key\ne")
        val u = assertUnsupported(f.lower(dir), "String abstraction", f.at("key)", after = "lockMap.compute"))
        val field = f.at("var token")
        assertTrue("String used as both Key and Token (Key at ${f.at("key)", after = "lockMap.compute").let { "${it.first}:${it.second}" }}, Token at ${field.first}:" in u.reason, u.reason)
    }

    @Test
    fun `a String value with no Key or Token use is unsupported`() {
        val f = LockFixture("if (e != null && tag == tag) e.expiresAt = now\ne", before = "        val tag = key.trim()")
        val u = assertUnsupported(f.lower(dir), "String value", f.at("tag == tag"))
        assertTrue("unknown abstraction" in u.reason, u.reason)
    }

    @Test
    fun `KeyLocalLock strings are Key for compute keys and Token for tokens (section 2_13 rule 4)`() {
        val m = keyLocalLock("coroutine")
        val tryLock = m.operations.single { it.name == "tryLock" }
        val unLock = m.operations.single { it.name == "unLock" }
        val sweep = m.operations.single { it.name == "sweep" }
        assertEquals(listOf("Key", "Key", "Key"), listOf(tryLock, unLock, sweep).map { (it.action.key.local.type as kv.kvir.KType.Abs).name })
        assertEquals(kv.kvir.KType.Abs("Token", true), tryLock.cells.single().local.type)
        assertEquals(kv.kvir.KType.Abs("Token", true), tryLock.result!!.local.type)
        assertEquals(kv.kvir.KType.Abs("Token", false), unLock.action.free.single { it.sidecarName == "t" }.local.type)
        assertEquals(kv.kvir.KType.Abs("Token", true), m.classes.single().fields.single { it.name == "token" }.type)
    }
}
