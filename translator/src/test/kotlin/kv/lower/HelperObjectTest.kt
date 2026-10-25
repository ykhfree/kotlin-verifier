package kv.lower

import kv.emit.Workspace
import kv.kvir.checkWellFormed
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
 * Fix 2 of AI-CODE.md 13: an inlined helper whose parameter gets the entry or an object the action created reads
 * that object itself. Before the fix the parameter was a `let` of the parameter's non-null type, which the smart-cast
 * entry does not have (W5), and its field reads went through an object that is not the entry (W6). The pattern is
 * T-sonnet-2's companion helper `isExpired(lockInfo, now)`, called with a smart-cast entry.
 */
class HelperObjectTest {
    @TempDir
    lateinit var dir: File

    private companion object {
        const val IS_EXPIRED = "        private fun isExpired(info: Info, at: Long): Boolean = at > info.expiresAt"
    }

    private fun LockFixture.e() = "e__${at("{ _, e ->").first}"

    private fun LockFixture.line(fragment: String) = at(fragment).first

    @Test
    fun `a smart-cast entry passed to a companion helper is the entry inside the helper`() {
        // T-sonnet-2 tryLock: `existing != null && !isExpired(existing, now)` keeps a live entry.
        val f = LockFixture("if (e != null && !isExpired(e, now)) return@compute e\nnull", companion = IS_EXPIRED)
        val m = f.lower(dir)
        val text = assertSupported(m)
        val e = f.e()
        val p = f.line("private fun isExpired")
        val (l, c) = f.at("e != null &&")
        assertEquals(
            listOf(
                "var cond_${l}_$c: Bool := false",
                "if $e != null",
                "  let at__$p: Int := now",
                "  cond_${l}_$c := !(at__$p > $e.expiresAt)",
                "if cond_${l}_$c",
                "  exit keep",
                "else",
                "  exit remove",
            ),
            shape(text),
        )
        assertTrue("info__" !in text, text)
    }

    @Test
    fun `the sweep shape with the helper on the right of && lowers and passes W1-W16`() {
        // T-sonnet-2 sweep: `if (existing != null && isExpired(existing, now)) null else existing`.
        val f = LockFixture("if (e != null && isExpired(e, now)) null else e", companion = IS_EXPIRED)
        val m = f.lower(dir)
        val text = assertSupported(m)
        assertTrue(shape(text).any { "> ${f.e()}.expiresAt" in it }, text)
        assertEquals(emptyList(), checkWellFormed(m), text)
    }

    @Test
    fun `a new object passed to a helper is that object inside the helper`() {
        val f = LockFixture("val fresh = Info(Semaphore(1), now)\nif (isExpired(fresh, now)) return@compute null\nfresh", companion = IS_EXPIRED)
        val text = assertSupported(f.lower(dir))
        val fresh = "fresh__${f.line("val fresh")}"
        assertTrue(shape(text).any { it.startsWith("if at__") && it.endsWith("> $fresh.expiresAt") }, text)
    }

    @Test
    fun `the coroutine KeyLocalLock with a companion isExpired helper for tryLock and the sweep is PROVEN`() {
        Silicon.check(Silicon.defaultHome())
        val ws = Workspace(dir)
        ws.editKotlin(
            "coroutine",
            "        private fun nextToken(): String = \"local-\${tokenSequence.incrementAndGet()}\"\n",
            "        private fun nextToken(): String = \"local-\${tokenSequence.incrementAndGet()}\"\n\n" +
                "        private fun isExpired(\n            info: LockInfo,\n            now: Long,\n        ): Boolean = now > info.expiresAt\n",
        )
        ws.editKotlin("coroutine", "if (now > lockInfo.expiresAt) {", "if (isExpired(lockInfo, now)) {")
        ws.editKotlin("coroutine", "if (now > existing.expiresAt && existing.isHeld", "if (isExpired(existing, now) && existing.isHeld")
        val r = ws.verify("coroutine")
        val text = r.render()
        assertEquals(ModuleVerdict.PROVEN, r.verdict, text)
        assertEquals(Verdict.PROVEN to Verdict.PROVEN, r.row("tryLock").verdict to r.row("sweep").verdict, text)
        assertTrue("Unsupported: none" in text, text)
    }
}
