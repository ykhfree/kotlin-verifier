package kv.lower

import kv.emit.Workspace
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
 * AI-CODE.md 14.1 A5: a member function of a module class (T-haiku-1's `existing.isExpired(now)`) is inlined with
 * `this` bound to its receiver, the entry or an object the action created, as the companion-helper inlining binds
 * an object parameter (43786d8). It must be final and pure: the inlined statements only declare locals and compute
 * a value.
 */
class MemberHelperTest {
    @TempDir
    lateinit var dir: File

    private companion object {
        const val IS_LIVE = "        fun isLive(at: Long): Boolean = at < expiresAt"
    }

    private fun LockFixture.e() = "e__${at("{ _, e ->").first}"

    private fun LockFixture.line(fragment: String) = at(fragment).first

    private fun sub(name: String) = File(dir, name).apply { mkdirs() }

    @Test
    fun `a member helper called on the entry reads the entry's fields`() {
        val f = LockFixture("if (e != null && e.isLive(now)) e else null", infoMembers = IS_LIVE)
        val e = f.e()
        val p = f.line("fun isLive")
        val (l, c) = f.at("e != null &&", after = "{ _, e ->")
        assertEquals(
            listOf(
                "var cond_${l}_$c: Bool := false",
                "if $e != null",
                "  let at__$p: Int := now",
                "  cond_${l}_$c := at__$p < $e.expiresAt",
                "if cond_${l}_$c",
                "  exit keep",
                "else",
                "  exit remove",
            ),
            shape(assertSupported(f.lower(dir))),
        )
    }

    @Test
    fun `a member helper called on a new object reads that object's fields`() {
        val f = LockFixture("val n = Info(Semaphore(1), now)\nif (n.isLive(now + 1)) n else e", infoMembers = IS_LIVE)
        val n = "n__${f.line("val n")}"
        val text = assertSupported(f.lower(dir))
        assertTrue(shape(text).any { it.startsWith("if at__") && it.endsWith(" < $n.expiresAt") }, text)
        assertTrue("${f.e()}.expiresAt" !in text, text)
    }

    @Test
    fun `a member helper with an effect or a model operation is unsupported`() {
        for ((name, member) in listOf(
            "write" to "        fun touch(at: Long): Boolean {\n            expiresAt = at\n            return true\n        }",
            "operation" to "        fun touch(at: Long): Boolean = isHeld.get() && at < expiresAt",
        )) {
            val f = LockFixture("if (e != null && e.touch(now)) e else null", infoMembers = member)
            val u = assertUnsupported(f.lower(sub(name)), "member helper touch with effects", f.at("e.touch(now)"))
            assertTrue("AI-CODE.md 14.1 A5" in u.reason, "$name: ${u.reason}")
        }
    }

    @Test
    fun `the coroutine KeyLocalLock with a member isExpired helper for tryLock and the sweep is PROVEN`() {
        Silicon.check(Silicon.defaultHome())
        val ws = Workspace(dir)
        ws.editKotlin("coroutine", "        @Volatile var token: String? = null,\n    )", "        @Volatile var token: String? = null,\n    ) {\n        fun isExpired(now: Long): Boolean = now > expiresAt\n    }")
        ws.editKotlin("coroutine", "if (now > lockInfo.expiresAt) {", "if (lockInfo.isExpired(now)) {")
        ws.editKotlin("coroutine", "if (now > existing.expiresAt && existing.isHeld", "if (existing.isExpired(now) && existing.isHeld")
        val r = ws.verify("coroutine")
        val text = r.render()
        assertEquals(ModuleVerdict.PROVEN, r.verdict, text)
        assertEquals(Verdict.PROVEN to Verdict.PROVEN, r.row("tryLock").verdict to r.row("sweep").verdict, text)
        assertTrue("Unsupported: none" in text, text)
    }
}
