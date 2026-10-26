package kv.lower

import kv.ReqShield
import kv.emit.Workspace
import kv.verify.ModuleVerdict
import kv.verify.Silicon
import kv.verify.SubStatus
import kv.verify.Verdict
import kv.verify.render
import kv.verify.row
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AI-CODE.md 14.1 A4: `a?.s` inside an action is s with a as its receiver if a is not null, else null. As the
 * lambda result both branches exit; `x.takeIf { p }` there keeps x if p holds and else returns null (T-haiku-3's
 * sweep: `existing?.takeIf { it.expiresAt > now }`). As a statement it runs only for a non-null receiver; as a
 * nullable Token or Key value it is a local that stays null on the null path.
 */
class SafeCallTest {
    @TempDir
    lateinit var dir: File

    private fun LockFixture.e() = "e__${at("{ _, e ->").first}"

    private fun sub(name: String) = File(dir, name).apply { mkdirs() }

    @Test
    fun `takeIf after ?_ as the lambda result keeps the entry if the predicate holds and removes it otherwise`() {
        val f = LockFixture("e?.takeIf { it.expiresAt > now }")
        val e = f.e()
        assertEquals(
            listOf("if $e != null", "  if $e.expiresAt > now", "    exit keep", "  else", "    exit remove", "else", "  exit remove"),
            shape(assertSupported(f.lower(dir))),
        )
    }

    @Test
    fun `takeUnless negates the predicate`() {
        val f = LockFixture("e?.takeUnless { now > it.expiresAt }")
        val e = f.e()
        assertEquals(
            listOf("if $e != null", "  if !(now > $e.expiresAt)", "    exit keep", "  else", "    exit remove", "else", "  exit remove"),
            shape(assertSupported(f.lower(dir))),
        )
    }

    @Test
    fun `a safe call statement runs only for a non-null receiver`() {
        val f = LockFixture("e?.expiresAt = now\ne")
        val e = f.e()
        assertEquals(listOf("if $e != null", "  $e.expiresAt := now", "exit keep"), shape(assertSupported(f.lower(dir))))
    }

    @Test
    fun `a Token safe call value is a local that stays null on the null path`() {
        val f = LockFixture("result.set(e?.token)\ne")
        val e = f.e()
        val (l, c) = f.at("e?.token")
        val text = assertSupported(f.lower(dir))
        val lines = shape(text)
        assertEquals(listOf("var value_${l}_$c: Token? := null", "if $e != null", "  value_${l}_$c := $e.token"), lines.take(3), text)
        assertTrue(lines[3].startsWith("result__") && lines[3].endsWith(":= value_${l}_$c"), text)
    }

    @Test
    fun `a safe call value of a Long or of an object outside the result is unsupported`() {
        val long = LockFixture("val x = e?.expiresAt\ne")
        assertUnsupported(long.lower(sub("long")), "nullable Long", long.at("val x"))
        val obj = LockFixture("val x = e?.takeIf { now > 0 }\ne")
        val u = assertUnsupported(obj.lower(sub("object")), "safe call ?. with a value of type", obj.at("e?.takeIf"))
        assertTrue("AI-CODE.md 14.1 A4" in u.reason, u.reason)
    }

    // ---- Silicon ----

    /** The coroutine KeyLocalLock whose monitor keeps an entry by T-haiku-3's `?.takeIf` with [live]. */
    private fun verifySweep(
        name: String,
        live: String,
    ): kv.verify.Report {
        val ws = Workspace(sub(name))
        val source = File(ReqShield.coroutineDir, "KeyLocalLock.kt").readText()
        val start = source.indexOf("if (lockInfo == null) return@compute null")
        val end = source.indexOf("}", source.indexOf("lockInfo // Keep the entry", start)) + 1
        check(start >= 0 && end > start) { "the sweep's compute lambda not found" }
        ws.editKotlin("coroutine", source.substring(start, end), "lockInfo?.takeIf { $live }")
        return ws.verify("coroutine")
    }

    @Test
    fun `T-haiku-3's sweep shape with a strict expiry is PROVEN, and with the boundary it breaks P3a`() {
        Silicon.check(Silicon.defaultHome())
        val ok = verifySweep("strict", "now <= it.expiresAt")
        assertEquals(ModuleVerdict.PROVEN, ok.verdict, ok.render())
        val bad = verifySweep("boundary", "it.expiresAt > now")
        val text = bad.render()
        assertEquals(Verdict.FAILED to SubStatus.PROPERTY, bad.row("sweep").let { it.verdict to it.sub }, text)
        assertTrue("Postcondition of sweep might not hold: sidecar ends_only_if P3a" in text, text)
    }
}
