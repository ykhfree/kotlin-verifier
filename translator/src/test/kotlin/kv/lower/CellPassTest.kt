package kv.lower

import kv.emit.Workspace
import kv.kvir.Status
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

/** Prototype: a cell passed to a module helper (DESIGN-v0.md 2.5 rule 7, proposed). */
class CellPassTest {
    @TempDir
    lateinit var dir: File

    private companion object {
        const val NEXT_TOKEN = "        private fun nextToken(): String = \"local-\${counter.incrementAndGet()}\""
        const val PUBLISH = "    private fun publish(cell: AtomicReference<String?>, value: String?) {\n        cell.set(value)\n    }"
    }

    private fun LockFixture.e() = "e__${at("{ _, e ->").first}"

    private fun LockFixture.line(fragment: String) = at(fragment).first

    private fun reasons(f: LockFixture): String {
        val m = f.lower(dir)
        assertTrue(m.run.status is Status.Unsupported, m.runText)
        return (m.run.status as Status.Unsupported).diagnostics.joinToString("\n") { it.message }
    }

    // ---- supported ----

    @Test
    fun `a set through a helper parameter is a set of the cell`() {
        val f = LockFixture("if (e != null) publish(result, e.token)\ne", members = PUBLISH, operations = LockFixture.RESULT_OPERATION)
        val text = assertSupported(f.lower(dir))
        println("T1\n$text")
        val cell = "result__${f.line("val result")}"
        assertTrue("  $cell := value__${f.line("value: String?")}" in shape(text), text)
    }

    @Test
    fun `a helper that sets the cell and returns a new entry, called in two branches`() {
        val f =
            LockFixture(
                "when {\n    e == null -> held(now, result)\n    now > e.expiresAt -> held(now, result)\n    else -> e\n}",
                companion = NEXT_TOKEN,
                members =
                    """    private fun held(at: Long, cell: AtomicReference<String?>): Info {
        val token = nextToken()
        val info = Info(Semaphore(1), at + timeout)
        info.semaphore.tryAcquire()
        info.isHeld.set(true)
        info.token = token
        cell.set(token)
        return info
    }""",
                operations = LockFixture.RESULT_OPERATION,
            )
        val text = assertSupported(f.lower(dir))
        println("T2\n$text")
        val cell = "result__${f.line("val result")}"
        val token = "token__${f.line("val token = nextToken()")}"
        assertEquals(2, shape(text).count { it.trim() == "$cell := $token" || it.trim() == "$cell := ${token}b" }, text)
    }

    @Test
    fun `two cells passed to one helper stay two cells`() {
        val f =
            LockFixture(
                "if (e != null) {\n    publish(result, e.token)\n    publish(other, null)\n}\ne",
                before = "        val other = AtomicReference<String?>(null)",
                members = PUBLISH,
                operations = LockFixture.RESULT_OPERATION,
            )
        val text = assertSupported(f.lower(dir))
        println("T3\n$text")
        assertTrue(shape(text).any { it.trim().startsWith("result__") } && shape(text).any { it.trim().startsWith("other__") }, text)
    }

    @Test
    fun `an AtomicBoolean cell passed to a helper`() {
        val f =
            LockFixture(
                "if (e != null) raise(flag)\ne",
                before = "        val flag = AtomicBoolean(false)",
                members = "    private fun raise(f: AtomicBoolean) {\n        f.set(true)\n    }",
            )
        val text = assertSupported(f.lower(dir))
        println("T4\n$text")
        assertTrue(shape(text).any { it.trim() == "flag__${f.line("val flag")} := true" }, text)
    }

    @Test
    fun `a get through a helper parameter reads the cell`() {
        val f =
            LockFixture(
                "if (e != null) result.set(e.token)\nif (e != null && seen(result)) e.expiresAt = now\ne",
                members = "    private fun seen(cell: AtomicReference<String?>): Boolean = cell.get() != null",
                operations = LockFixture.RESULT_OPERATION,
            )
        val text = assertSupported(f.lower(dir))
        println("T5\n$text")
    }

    // ---- unsupported ----

    @Test
    fun `N1 a helper that stores the parameter lets the cell escape`() {
        val f =
            LockFixture(
                "if (e != null) keep(result)\ne",
                members = "    private var kept: Any? = null\n    private fun keep(cell: AtomicReference<String?>) {\n        kept = cell\n    }",
                operations = LockFixture.RESULT_OPERATION,
            )
        val r = reasons(f)
        println("N1\n$r")
        assertTrue("parameter cell of keep is used other than as the receiver of set or get at Lock.kt:${f.line("kept = cell")}" in r, r)
    }

    @Test
    fun `N2 a helper that passes the parameter on lets the cell escape`() {
        val f =
            LockFixture(
                "if (e != null) outer(result)\ne",
                members =
                    "    private fun outer(cell: AtomicReference<String?>) {\n        inner(cell)\n    }\n" +
                        "    private fun inner(c: AtomicReference<String?>) {\n        c.set(null)\n    }",
                operations = LockFixture.RESULT_OPERATION,
            )
        val r = reasons(f)
        println("N2\n$r")
        assertTrue("parameter cell of outer is used other than as the receiver of set or get at Lock.kt:${f.line("inner(cell)")}" in r, r)
    }

    @Test
    fun `N3 a lambda in the helper that captures the parameter lets the cell escape`() {
        val f =
            LockFixture(
                "if (e != null) later(result)\ne",
                members = "    private fun later(cell: AtomicReference<String?>) {\n        run { cell.set(null) }\n    }",
                operations = LockFixture.RESULT_OPERATION,
            )
        val r = reasons(f)
        println("N3\n$r")
        assertTrue("a lambda in later captures parameter cell at Lock.kt:${f.line("run { cell.set")}" in r, r)
    }

    @Test
    fun `N4 a helper that returns the parameter lets the cell escape`() {
        val f =
            LockFixture(
                "if (e != null) {\n    val r = self(result)\n    r.set(null)\n}\ne",
                members = "    private fun self(cell: AtomicReference<String?>): AtomicReference<String?> = cell",
                operations = LockFixture.RESULT_OPERATION,
            )
        val r = reasons(f)
        println("N4\n$r")
        assertTrue("parameter cell of self is used other than as the receiver of set or get" in r, r)
    }

    @Test
    fun `N5 a helper call with the cell before compute is outside the action`() {
        val f =
            LockFixture(
                "if (e != null) result.set(e.token)\ne",
                before = "        publish(result, null)",
                members = PUBLISH,
                operations = LockFixture.RESULT_OPERATION,
            )
        val r = reasons(f)
        println("N5\n$r")
        assertTrue("cell result is passed to a helper outside the action at Lock.kt:${f.line("publish(result, null)")}" in r, r)
    }

    @Test
    fun `N6 a helper that prints the parameter lets the cell escape`() {
        val f =
            LockFixture(
                "if (e != null) show(result)\ne",
                members = "    private fun show(cell: AtomicReference<String?>) {\n        println(cell)\n    }",
                operations = LockFixture.RESULT_OPERATION,
            )
        val r = reasons(f)
        println("N6\n$r")
        assertTrue("parameter cell of show is used other than as the receiver of set or get at Lock.kt:${f.line("println(cell)")}" in r, r)
    }

    @Test
    fun `N7 compareAndSet through the parameter is no cell operation`() {
        val f =
            LockFixture(
                "if (e != null) cas(result)\ne",
                members = "    private fun cas(cell: AtomicReference<String?>) {\n        cell.compareAndSet(null, null)\n    }",
                operations = LockFixture.RESULT_OPERATION,
            )
        val r = reasons(f)
        println("N7\n$r")
        assertTrue("parameter cell of cas is used other than as the receiver of set or get" in r, r)
    }

    @Test
    fun `N8 a member helper of the entry that sets the cell has effects`() {
        val f =
            LockFixture(
                "if (e != null) e.give(result)\ne",
                infoMembers = "        fun give(cell: AtomicReference<String?>) {\n            cell.set(token)\n        }",
                operations = LockFixture.RESULT_OPERATION,
            )
        val r = reasons(f)
        println("N8\n$r")
        assertTrue("member helper give with effects" in r, r)
    }

    @Test
    fun `N9 a named cell argument`() {
        val f = LockFixture("if (e != null) publish(value = e.token, cell = result)\ne", members = PUBLISH, operations = LockFixture.RESULT_OPERATION)
        val m = f.lower(dir)
        println("N9 status=${m.run.status}\n${m.runText}")
    }

    @Test
    fun `N10 a helper call with the cell inside a nested lambda of the action`() {
        val f = LockFixture("if (e != null) listOf(1).forEach { publish(result, null) }\ne", members = PUBLISH, operations = LockFixture.RESULT_OPERATION)
        val r = reasons(f)
        println("N10\n$r")
    }

    // ---- integration (Silicon) ----

    @Test
    fun `I1 the coroutine KeyLocalLock that publishes the new token through a helper is PROVEN`() {
        Silicon.check(Silicon.defaultHome())
        val ws = Workspace(dir)
        ws.editKotlin("coroutine", "                acquiredToken.set(token)\n                newLock", "                publish(acquiredToken, token)\n                newLock")
        ws.editKotlin(
            "coroutine",
            "    override suspend fun unLock(",
            "    private fun publish(cell: AtomicReference<String?>, value: String) {\n        cell.set(value)\n    }\n\n    override suspend fun unLock(",
        )
        val r = ws.verify("coroutine")
        val text = r.render()
        println("I1\n$text")
        assertEquals(ModuleVerdict.PROVEN, r.verdict, text)
        assertEquals(Verdict.PROVEN, r.row("tryLock").verdict, text)
        assertTrue("Unsupported: none" in text, text)
    }

    @Test
    fun `I2 the helper that publishes another token is FAILED`() {
        Silicon.check(Silicon.defaultHome())
        val ws = Workspace(dir)
        ws.editKotlin("coroutine", "                acquiredToken.set(token)\n                newLock", "                publish(acquiredToken, token)\n                newLock")
        ws.editKotlin(
            "coroutine",
            "    override suspend fun unLock(",
            "    private fun publish(cell: AtomicReference<String?>, value: String) {\n        cell.set(nextToken())\n    }\n\n    override suspend fun unLock(",
        )
        val r = ws.verify("coroutine")
        val text = r.render()
        println("I2\n$text")
        assertEquals(Verdict.FAILED, r.row("tryLock").verdict, text)
    }
}
