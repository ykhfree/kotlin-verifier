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

/** Prototype: the stdlib bounds of Int and Long inside an action are their exact values. */
class LibraryConstantTest {
    @TempDir
    lateinit var dir: File

    private fun LockFixture.e() = "e__${at("{ _, e ->").first}"

    @Test
    fun `C1 Long MAX_VALUE is its value`() {
        val f = LockFixture("if (e != null) e.expiresAt = Long.MAX_VALUE\ne")
        val text = assertSupported(f.lower(dir))
        assertEquals(listOf("if ${f.e()} != null", "  ${f.e()}.expiresAt := 9223372036854775807", "exit keep"), shape(text))
    }

    @Test
    fun `C2 Long MIN_VALUE is its value`() {
        val f = LockFixture("if (e != null) e.expiresAt = Long.MIN_VALUE\ne")
        val text = assertSupported(f.lower(dir))
        assertEquals(listOf("if ${f.e()} != null", "  ${f.e()}.expiresAt := -9223372036854775808", "exit keep"), shape(text))
    }

    @Test
    fun `C3 Int bounds through toLong`() {
        val f = LockFixture("if (e != null && e.expiresAt > Int.MAX_VALUE.toLong()) e.expiresAt = Int.MIN_VALUE.toLong()\ne")
        val text = assertSupported(f.lower(dir))
        println("C3\n$text")
        assertTrue("2147483647" in text && "-2147483648" in text, text)
    }

    @Test
    fun `C4 another stdlib const stays unsupported`() {
        val f = LockFixture("if (e != null) e.expiresAt = Long.SIZE_BITS.toLong()\ne")
        val u = assertUnsupported(f.lower(dir), "read of Companion.SIZE_BITS", f.at("Long.SIZE_BITS"))
        println("C4 ${u.what}: ${u.reason}")
    }

    @Test
    fun `C5 a const of the module file stays unsupported`() {
        val f = LockFixture("if (e != null) e.expiresAt = CAP\ne", topLevel = "private const val CAP = 5L")
        val m = f.lower(dir)
        println("C5 status=${m.run.status}")
    }

    @Test
    fun `C6 the coroutine KeyLocalLock with a lease saturated at Long MAX_VALUE fails entry_lease`() {
        Silicon.check(Silicon.defaultHome())
        val ws = Workspace(dir)
        ws.editKotlin(
            "coroutine",
            "LockInfo(Semaphore(1), now + lockTimeoutMillis, token = token)",
            "LockInfo(Semaphore(1), if (lockTimeoutMillis > Long.MAX_VALUE - now) Long.MAX_VALUE else now + lockTimeoutMillis, token = token)",
        )
        val r = ws.verify("coroutine")
        val text = r.render()
        println("C6\n$text")
        assertEquals(Verdict.FAILED, r.row("tryLock").verdict, text)
    }

    @Test
    fun `C7 adding and subtracting Long MIN_VALUE keeps the coroutine KeyLocalLock PROVEN`() {
        Silicon.check(Silicon.defaultHome())
        val ws = Workspace(dir)
        ws.editKotlin(
            "coroutine",
            "LockInfo(Semaphore(1), now + lockTimeoutMillis, token = token)",
            "LockInfo(Semaphore(1), now + lockTimeoutMillis + Long.MIN_VALUE - Long.MIN_VALUE, token = token)",
        )
        val r = ws.verify("coroutine")
        val text = r.render()
        println("C7\n$text")
        assertEquals(ModuleVerdict.PROVEN, r.verdict, text)
    }
}
