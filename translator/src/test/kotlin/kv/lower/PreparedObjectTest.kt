package kv.lower

import kv.emit.Workspace
import kv.kvir.Unsupported
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
 * AI-CODE.md 14.1 A1: a Semaphore that model operations prepare before a construction passes it to a new entry:
 * `Semaphore(1).apply { tryAcquire() }`, `Semaphore(1).also { it.tryAcquire() }`, and a local `val s = Semaphore(1);
 * s.tryAcquire(); Info(s, ...)`, for jdk.Semaphore and kotlinx.Semaphore. The object is made in the action by the
 * model constructor, used only by model operations, passed to exactly one constructor, and not read after the pass;
 * so its operations run on the new entry's flattened field right after the construction. A dropped `tryAcquire()`
 * result still moves the permit from 1 to 0. Each shape that breaks a condition stays UNSUPPORTED with its reason.
 */
class PreparedObjectTest {
    @TempDir
    lateinit var dir: File

    private companion object {
        const val KX = "kotlinx.coroutines.sync.Semaphore"

        /** LockFixture.DEFAULT_INFO with a kotlinx Semaphore. */
        const val KX_INFO =
            """        val semaphore: $KX,
        @Volatile var expiresAt: Long,
        val isHeld: AtomicBoolean = AtomicBoolean(false),
        @Volatile var token: String? = null,"""

        const val KX_OPERATION = "use kotlinx.Semaphore\n" + LockFixture.DEFAULT_OPERATION

        /** The coroutine KeyLocalLock's new entry: made, then acquired by a separate statement. */
        const val NEW_LOCK = "val newLock = LockInfo(Semaphore(1), now + lockTimeoutMillis, token = token)\n                newLock.semaphore.tryAcquire() // Always succeeds for new semaphore\n"
    }

    private fun LockFixture.e() = "e__${at("{ _, e ->").first}"

    /** The local of the object constructed at [fragment]. */
    private fun LockFixture.new(fragment: String) = at(fragment, after = "{ _, e ->").let { (l, c) -> "new_${l}_$c" }

    /** The result local of the model operation at [fragment]. */
    private fun LockFixture.cond(fragment: String) = at(fragment, after = "{ _, e ->").let { (l, c) -> "cond_${l}_$c" }

    private fun LockFixture.where(fragment: String) = at(fragment, after = "{ _, e ->").let { (l, c) -> "Lock.kt:$l:$c" }

    private fun sub(name: String) = File(dir, name).apply { mkdirs() }

    // ---- the supported shapes ----

    @Test
    fun `apply and also prepare the new entry's permits right after the construction`() {
        for ((name, semaphore, op) in listOf(
            Triple("apply", "Semaphore(1).apply { tryAcquire() }", "tryAcquire()"),
            Triple("also", "Semaphore(1).also { it.tryAcquire() }", "it.tryAcquire()"),
        )) {
            // The release on the entry is supported too: every construction of the field passes a new object.
            val f = LockFixture("if (e == null) return@compute Info($semaphore, now)\ne.semaphore.release()\ne")
            val n = f.new("Info(Semaphore")
            val e = f.e()
            assertEquals(
                listOf(
                    "if $e == null",
                    "  new $n: Info {permits := 1, isHeld := false, token := null, expiresAt := now}",
                    "  ${f.cond(op)} := jdk.Semaphore.tryAcquire($n.permits)",
                    "  exit put($n)",
                    "else",
                    "  jdk.Semaphore.release($e.permits)",
                    "  exit keep",
                ),
                shape(assertSupported(f.lower(sub(name)))),
                name,
            )
        }
    }

    @Test
    fun `a local Semaphore gets no statement, and its operations run in source order on the entry it is passed to`() {
        val f = LockFixture("if (e != null) return@compute e\nval s = Semaphore(0)\ns.release()\ns.tryAcquire()\nInfo(s, now)")
        val n = f.new("Info(s")
        assertEquals(
            listOf(
                "if ${f.e()} != null",
                "  exit keep",
                "else",
                "  new $n: Info {permits := 0, isHeld := false, token := null, expiresAt := now}",
                "  jdk.Semaphore.release($n.permits)",
                "  ${f.cond("s.tryAcquire()")} := jdk.Semaphore.tryAcquire($n.permits)",
                "  exit put($n)",
            ),
            shape(assertSupported(f.lower(dir))),
        )
    }

    @Test
    fun `a local initial count that is not a literal is read where the Semaphore is made`() {
        // A var that changes between the Semaphore and the construction: the count is the value at the Semaphore.
        val f = LockFixture("var c = 1\nval s = Semaphore(c)\nc = 5\ns.tryAcquire()\nInfo(s, now)")
        val (l, col) = f.at("val s", after = "{ _, e ->")
        val n = f.new("Info(s")
        val text = assertSupported(f.lower(dir))
        val lines = shape(text)
        assertTrue("let permits_${l}_$col: Int := c__${f.at("var c").first}" in lines, text)
        assertTrue(lines.any { it.startsWith("new $n: Info {permits := permits_${l}_$col,") }, text)
    }

    @Test
    fun `a kotlinx Semaphore is prepared the same way, and the constructor gives its maximum`() {
        val f = LockFixture("if (e != null) {\n    e.semaphore.release()\n    return@compute e\n}\nval s = $KX(1)\ns.tryAcquire()\nInfo(s, now)", info = KX_INFO, operations = KX_OPERATION)
        val n = f.new("Info(s")
        assertEquals(
            listOf(
                "if ${f.e()} != null",
                "  kotlinx.Semaphore.release(${f.e()}.permits, 1)",
                "  exit keep",
                "else",
                "  new $n: Info {permits := 1, isHeld := false, token := null, expiresAt := now}",
                "  ${f.cond("s.tryAcquire()")} := kotlinx.Semaphore.tryAcquire($n.permits)",
                "  exit put($n)",
            ),
            shape(assertSupported(f.lower(dir))),
        )
    }

    // ---- the conditions: each violation stays unsupported with its reason ----

    @Test
    fun `a Semaphore passed to two constructors is unsupported`() {
        val f = LockFixture("val s = Semaphore(1)\ns.tryAcquire()\nif (e == null) return@compute Info(s, now)\nInfo(s, now + 1)")
        val first = f.at("Info(s, now)", after = "{ _, e ->").let { (l, c) -> l to c + 5 }
        val second = f.at("Info(s, now + 1)", after = "{ _, e ->").let { (l, c) -> "Lock.kt:$l:${c + 5}" }
        val u = assertUnsupported(f.lower(dir), "flattened field semaphore", first)
        assertTrue("s is passed to more than one constructor at $second" in u.reason, u.reason)
        assertTrue("s is passed to more than one constructor at Lock.kt:${first.first}:${first.second}" in u.reason, u.reason)
    }

    @Test
    fun `a Semaphore also stored into an existing entry's field is unsupported`() {
        // `spare` is a field the sidecar does not model.
        val f = LockFixture("val s = Semaphore(1)\nif (e != null) e.spare = s\nInfo(s, now)", info = LockFixture.DEFAULT_INFO + "\n        var spare: Semaphore? = null,")
        val m = f.lower(dir)
        val stored = f.at("e.spare = s", after = "{ _, e ->").let { (l, c) -> "Lock.kt:$l:${c + 10}" }
        val u = assertUnsupported(m, "flattened field semaphore", f.at("Info(s", after = "{ _, e ->").let { (l, c) -> l to c + 5 })
        assertTrue("s is used other than as the receiver of a jdk.Semaphore operation or as one constructor argument at $stored" in u.reason, u.reason)
        assertUnsupported(m, "write of", f.at("e.spare = s", after = "{ _, e ->"))
    }

    @Test
    fun `a Semaphore used after the pass is unsupported`() {
        val f = LockFixture("val s = Semaphore(1)\nval n = Info(s, now)\ns.tryAcquire()\nn")
        val u = assertUnsupported(f.lower(dir), "flattened field semaphore", f.at("Info(s", after = "{ _, e ->").let { (l, c) -> l to c + 5 })
        assertTrue("s is used after the statement that passes it at ${f.where("s.tryAcquire()")}" in u.reason, u.reason)
    }

    @Test
    fun `a local alias of an existing entry's Semaphore is unsupported`() {
        val f = LockFixture("if (e == null) return@compute null\nval s = e.semaphore\ns.tryAcquire()\nInfo(s, now)")
        val m = f.lower(dir)
        assertUnsupported(m, "read of flattened field semaphore", f.at("e.semaphore", after = "{ _, e ->"))
        val u = m.run.body.walk().filterIsInstance<Unsupported>().single { it.what == "flattened field semaphore" }
        assertEquals(f.at("Info(s", after = "{ _, e ->").let { (l, c) -> l to c + 5 }, u.pos.line to u.pos.col, m.runText)
        assertTrue("s is not made by a new jdk.Semaphore object at ${f.where("val s")}" in u.reason, u.reason)
        assertTrue("semaphore is used other than as the receiver of a jdk.Semaphore operation at ${f.where("e.semaphore")}" in u.reason, u.reason)
    }

    @Test
    fun `an apply or also lambda that does more than model operations on its receiver is unsupported`() {
        for ((name, semaphore, culprit) in listOf(
            Triple("other state", "Semaphore(1).also { it.tryAcquire(); counter.incrementAndGet() }", "counter.incrementAndGet()"),
            Triple("result used", "Semaphore(1).apply { if (tryAcquire()) release() }", "if (tryAcquire())"),
            Triple("other object", "Semaphore(1).also { e?.semaphore?.tryAcquire() }", "e?.semaphore"),
        )) {
            val f = LockFixture("if (e != null) return@compute e\nInfo($semaphore, now)")
            val scope = if (".also" in semaphore) "also" else "apply"
            val u = assertUnsupported(f.lower(sub(name.replace(' ', '-'))), "flattened field semaphore", f.at("Semaphore(1)", after = "{ _, e ->"))
            assertTrue("the lambda of $scope does something other than a jdk.Semaphore operation on its receiver whose result is unused at ${f.where(culprit)}" in u.reason, "$name: ${u.reason}")
        }
    }

    @Test
    fun `a kotlinx release before the pass is unsupported, since it may throw`() {
        val f = LockFixture("if (e != null) return@compute e\nval s = $KX(1, 1)\ns.release()\nInfo(s, now)", info = KX_INFO, operations = KX_OPERATION)
        val u = assertUnsupported(f.lower(dir), "flattened field semaphore", f.at("Info(s", after = "{ _, e ->").let { (l, c) -> l to c + 5 })
        assertTrue("s gets a kotlinx.Semaphore operation that may throw before the pass at ${f.where("s.release()")}" in u.reason, u.reason)
    }

    // ---- Silicon: the prepared permit is what the proof sees ----

    /** The coroutine KeyLocalLock with its new entry made by [newLock] (16 spaces before each further line). */
    private fun verifyNewLock(
        name: String,
        newLock: String,
        kotlinx: Boolean = false,
    ): kv.verify.Report {
        val ws = Workspace(sub(name))
        ws.editKotlin("coroutine", NEW_LOCK, newLock)
        if (kotlinx) {
            ws.editKotlin("coroutine", "import java.util.concurrent.Semaphore\n", "import kotlinx.coroutines.sync.Semaphore\n")
            ws.editSpec("keylocallock.coroutine.kv", "include \"keylocallock.common.kv\"\n", "include \"keylocallock.common.kv\"\nuse kotlinx.Semaphore\n")
        }
        return ws.verify("coroutine")
    }

    @Test
    fun `the coroutine KeyLocalLock with a prepared new Semaphore is PROVEN in each shape`() {
        Silicon.check(Silicon.defaultHome())
        val shapes =
            listOf(
                Triple("apply", "val newLock = LockInfo(Semaphore(1).apply { tryAcquire() }, now + lockTimeoutMillis, token = token)\n", false),
                Triple("also", "val newLock = LockInfo(Semaphore(1).also { it.tryAcquire() }, now + lockTimeoutMillis, token = token)\n", false),
                Triple("kotlinx-local", "val semaphore = Semaphore(1)\n                semaphore.tryAcquire()\n                val newLock = LockInfo(semaphore, now + lockTimeoutMillis, token = token)\n", true),
            )
        for ((name, newLock, kotlinx) in shapes) {
            val r = verifyNewLock(name, newLock, kotlinx)
            val text = r.render()
            assertEquals(ModuleVerdict.PROVEN, r.verdict, "$name\n$text")
            assertTrue("Unsupported: none" in text, "$name\n$text")
        }
    }

    @Test
    fun `a new Semaphore whose permit is not taken fails the invariant that entries hold no permit`() {
        Silicon.check(Silicon.defaultHome())
        // The same entry without the operation: the permit stays 1, and MI_INV says an entry has none. A lowering that
        // dropped the prepared operations would give the PROVEN shapes above this verdict.
        val r = verifyNewLock("not-taken", "val newLock = LockInfo(Semaphore(1), now + lockTimeoutMillis, token = token)\n")
        val text = r.render()
        assertEquals(ModuleVerdict.FAILED, r.verdict, text)
        assertEquals(Verdict.FAILED, r.row("tryLock").verdict, text)
        assertEquals(Verdict.PROVEN to Verdict.PROVEN, r.row("unLock").verdict to r.row("sweep").verdict, text)
    }
}
