package kv.lower

import kv.emit.Workspace
import kv.kvir.DispositionKind
import kv.kvir.Status
import kv.kvir.Unsupported
import kv.kvir.checkWellFormed
import kv.kvir.incomplete
import kv.kvir.walk
import kv.verify.ModuleVerdict
import kv.verify.Silicon
import kv.verify.Verdict
import kv.verify.render
import kv.verify.row
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fix 1 of AI-CODE.md 13: an action whose input (key, free value, cell or operation result) flows through an
 * unsupported module-state access outside every action is itself unsupported, as DESIGN-v0.md 5.8 says for the
 * module. The reason names the construct and its position. Before the fix such an action was verified, and its
 * failures could rest on the unmodeled construct (AI-CODE.md 12.1, T-haiku-2).
 */
class InputTest {
    @TempDir
    lateinit var dir: File

    private companion object {
        const val NEXT_TOKEN = "        private fun nextToken(): String = \"local-\${counter.incrementAndGet()}\""

        /** A new entry that carries [token]; the cell `result` gets the token. */
        fun install(token: String) = "if (e != null) return@compute e\nresult.set($token)\nInfo(Semaphore(0), now, AtomicBoolean(true), $token)"
    }

    private fun LockFixture.where(fragment: String) = at(fragment).let { (l, c) -> "Lock.kt:$l:$c" }

    @Test
    fun `a token the counter makes outside the action makes the action unsupported, and names the counter operation`() {
        val f = LockFixture(install("token"), before = "        val token = \"local-\${counter.incrementAndGet()}\"", operations = LockFixture.RESULT_OPERATION)
        val m = f.lower(dir)
        val u = assertUnsupported(m, "input token", f.at("val token"))
        assertTrue("counter operation outside an action: counter.incrementAndGet() at ${f.where("counter.incrementAndGet()")}" in u.reason, u.reason)
        assertTrue("DESIGN-v0.md 5.8" in u.reason, u.reason)
        // The construct itself stays in the ledger as unsupported; the check only adds the action's reason.
        assertTrue(m.ledger.any { it.kind == DispositionKind.UNSUPPORTED && it.pos.line == f.at("val token").first }, m.ledger.joinToString("\n"))
    }

    @Test
    fun `a token made outside the action by modeled code keeps the action supported`() {
        val f = LockFixture(install("token"), before = "        val token = \"local-\${now}\"", operations = LockFixture.RESULT_OPERATION)
        assertSupported(f.lower(dir))
    }

    @Test
    fun `an input that reads a local whose assignment runs the counter is unsupported`() {
        val before = "        var n = 0L\n        n = counter.incrementAndGet()\n        val token = \"local-\$n\""
        val f = LockFixture(install("token"), before = before, operations = LockFixture.RESULT_OPERATION)
        val u = assertUnsupported(f.lower(dir), "input token", f.at("val token"))
        assertTrue("counter.incrementAndGet() at ${f.where("counter.incrementAndGet()")}" in u.reason, u.reason)
    }

    @Test
    fun `an input from a helper call outside the action names the call and the operation in the helper`() {
        val f = LockFixture(install("token"), before = "        val token = nextToken()", companion = NEXT_TOKEN, operations = LockFixture.RESULT_OPERATION)
        val u = assertUnsupported(f.lower(dir), "input token", f.at("val token"))
        val call = f.at("nextToken()", after = "val token").let { (l, c) -> "Lock.kt:$l:$c" }
        assertTrue("call of nextToken, which changes module state" in u.reason && "outside an action: nextToken() at $call" in u.reason, u.reason)
        assertTrue("counter operation outside an action: counter.incrementAndGet() at ${f.where("counter.incrementAndGet()")}" in u.reason, u.reason)
    }

    @Test
    fun `a key computed with the counter outside the action is an unsupported input`() {
        val run =
            """
            |    fun run(key: String, now: Long): String? {
            |        val result = AtomicReference<String?>(null)
            |        val k = key + counter.incrementAndGet()
            |        lockMap.compute(k) { _, e ->
            |            e
            |        }
            |        return result.get()
            |    }
            """.trimMargin()
        val f = LockFixture("", run = run)
        val u = assertUnsupported(f.lower(dir), "input key k", f.at("compute(k)").let { (l, c) -> l to c + "compute(".length })
        assertTrue("counter.incrementAndGet() at ${f.where("counter.incrementAndGet()")}" in u.reason, u.reason)
    }

    @Test
    fun `an action that is unsupported for another reason also lists the input`() {
        val body = "if (e != null) {\n    e.expiresAt = now * 2\n    return@compute e\n}\nresult.set(token)\nInfo(Semaphore(0), now, AtomicBoolean(true), token)"
        val f = LockFixture(body, before = "        val token = \"local-\${counter.incrementAndGet()}\"", operations = LockFixture.RESULT_OPERATION)
        val m = f.lower(dir)
        assertUnsupported(m, "Long *", f.at("now * 2"))
        assertUnsupported(m, "input token", f.at("val token"))
        val status = m.run.status as Status.Unsupported
        assertEquals(m.run.body.walk().filterIsInstance<Unsupported>().count(), status.diagnostics.size, m.runText)
    }

    @Test
    fun `an object a value-class constructor makes outside the fresh function keeps the action supported (M5)`() {
        // The constructor changes no module state: the object is an arbitrary opaque one (DESIGN-B.md 7.2, M5).
        val f = AspectFixture(op = "    fun getOrCreate(jp: Any): Shield =\n        shieldMap.computeIfAbsent(target(jp)) { spare }", members = "    private val spare = Shield(\"spare\")")
        val m = f.lower(dir)
        assertEquals(Status.Supported, m.getOrCreate.action.status, m.getOrCreateText)
        assertEquals(emptyList(), checkWellFormed(m), m.getOrCreateText)
        assertTrue(m.getOrCreate.action.free.any { it.kotlin == "this.spare" }, m.getOrCreateText)
        // The constructor still makes the module INCOMPLETE (DESIGN-B.md 3.3 rule 4).
        assertTrue(m.ledger.any { it.kind == DispositionKind.UNSUPPORTED && "Shield constructor outside" in it.what && it.pos.line == f.at("Shield(\"spare\")").first }, m.ledger.joinToString("\n"))
        assertTrue(m.incomplete)
    }

    @Test
    fun `the coroutine KeyLocalLock with its token made before compute has an unsupported tryLock, not a FAILED one`() {
        Silicon.check(Silicon.defaultHome())
        val ws = Workspace(dir)
        ws.editKotlin("coroutine", "val acquiredToken = AtomicReference<String?>(null)\n", "val acquiredToken = AtomicReference<String?>(null)\n        val fresh = nextToken()\n")
        ws.editKotlin("coroutine", "val token = nextToken()\n                    existing.isHeld.set(true)", "val token = fresh\n                    existing.isHeld.set(true)")
        ws.editKotlin("coroutine", "val token = nextToken()\n                val newLock", "val token = fresh\n                val newLock")
        val r = ws.verify("coroutine")
        val text = r.render()
        assertEquals(ModuleVerdict.INCOMPLETE, r.verdict, text)
        val line = ws.kotlinLine("coroutine", "val fresh = nextToken()")
        assertEquals(Verdict.UNSUPPORTED, r.row("tryLock").verdict, text)
        assertTrue("UNSUPPORTED input fresh in action tryLock#" in text && "at KeyLocalLock.kt:$line:9: its value comes from" in text, text)
        assertTrue("call of nextToken, which changes module state" in text, text)
        assertEquals(Verdict.PROVEN to Verdict.PROVEN, r.row("unLock").verdict to r.row("sweep").verdict, text)
    }
}
