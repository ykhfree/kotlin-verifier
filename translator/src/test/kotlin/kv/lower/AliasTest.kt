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
 * AI-CODE.md 14.1 A3: a local `val` alias of the entry or of an object this action created. `val e = existing` is
 * the entry. `val e = if (...) X else existing` splits the statements after it in its block: they are lowered once
 * per branch, with e bound to that branch's object, so each field access and write goes to the object itself (W6).
 * An alias of anything else stays UNSUPPORTED.
 */
class AliasTest {
    @TempDir
    lateinit var dir: File

    private fun LockFixture.e() = "e__${at("{ _, e ->").first}"

    private fun LockFixture.new(fragment: String) = at(fragment, after = "{ _, e ->").let { (l, c) -> "new_${l}_$c" }

    private fun sub(name: String) = File(dir, name).apply { mkdirs() }

    @Test
    fun `a val of the entry is the entry`() {
        val f = LockFixture("if (e == null) return@compute null\nval x = e\nx.expiresAt = now\nx")
        val e = f.e()
        assertEquals(listOf("if $e == null", "  exit remove", "else", "  $e.expiresAt := now", "  exit keep"), shape(assertSupported(f.lower(dir))))
    }

    @Test
    fun `a val of an if lowers the statements after it once per object, and each write goes to its object`() {
        // T-opus-4's tryLock: a new entry for a free or expired key, else the entry; then the same steps on either.
        val f = LockFixture("val x = if (e == null || now > e.expiresAt) Info(Semaphore(1), now) else e\nif (x.semaphore.tryAcquire()) x.expiresAt = now + timeout\nx")
        val e = f.e()
        val n = f.new("Info(Semaphore")
        val (l, c) = f.at("x.semaphore.tryAcquire()", after = "{ _, e ->")
        assertEquals(
            listOf(
                "if ($e == null) || (now > $e.expiresAt)",
                "  new $n: Info {permits := 1, isHeld := false, token := null, expiresAt := now}",
                "  cond_${l}_$c := jdk.Semaphore.tryAcquire($n.permits)",
                "  if cond_${l}_$c",
                "    $n.expiresAt := now + timeout [ASSUME A$l]",
                "  exit put($n)",
                "else",
                "  cond_${l}_${c}b := jdk.Semaphore.tryAcquire($e.permits)",
                "  if cond_${l}_${c}b",
                "    $e.expiresAt := now + timeout [ASSUME A$l]",
                "  exit keep",
            ),
            shape(assertSupported(f.lower(dir))),
        )
    }

    @Test
    fun `a branch of the alias that exits ends there`() {
        val f = LockFixture("val x = if (e == null) return@compute null else e\nx.expiresAt = now\nx")
        val e = f.e()
        assertEquals(listOf("if $e == null", "  exit remove", "else", "  $e.expiresAt := now", "  exit keep"), shape(assertSupported(f.lower(dir))))
    }

    @Test
    fun `an alias that may be null or that is a var is unsupported`() {
        val nullable = LockFixture("val x = if (now > 0) e else null\nx")
        val u = assertUnsupported(nullable.lower(sub("null")), "local alias x", nullable.at("null", after = "val x"))
        assertTrue("AI-CODE.md 14.1 A3" in u.reason, u.reason)
        val variable = LockFixture("var x = e\nx")
        assertUnsupported(variable.lower(sub("var")), "local alias x", variable.at("var x"))
    }

    // ---- Silicon ----

    /** The coroutine KeyLocalLock whose tryLock is T-opus-4's shape, with the expiry test [expired]. */
    private fun verifyMerged(
        name: String,
        expired: String,
    ): kv.verify.Report {
        val ws = Workspace(sub(name))
        val source = File(ReqShield.coroutineDir, "KeyLocalLock.kt").readText()
        val start = source.indexOf("        lockMap.compute(completeKey) { _, existing ->\n            if (existing != null) {")
        val end = source.indexOf("        return acquiredToken.get()", start)
        check(start >= 0 && end > start) { "tryLock's compute call not found" }
        val merged =
            """
            |        lockMap.compute(completeKey) { _, existing ->
            |            if (existing == null && LocalLockLimit.rejectsNewEntry(lockMap.mappingCount())) {
            |                acquiredToken.set(nextToken())
            |                return@compute null
            |            }
            |            val entry = if (existing == null || $expired) LockInfo(Semaphore(1), now + lockTimeoutMillis) else existing
            |            if (entry.semaphore.tryAcquire()) {
            |                val token = nextToken()
            |                entry.isHeld.set(true)
            |                entry.token = token
            |                entry.expiresAt = now + lockTimeoutMillis
            |                acquiredToken.set(token)
            |            }
            |            entry
            |        }
            |
            """.trimMargin()
        ws.editKotlin("coroutine", source.substring(start, end), merged)
        return ws.verify("coroutine")
    }

    @Test
    fun `T-opus-4's tryLock shape with a strict expiry is PROVEN, and with the boundary it breaks P3a`() {
        Silicon.check(Silicon.defaultHome())
        val ok = verifyMerged("strict", "now > existing.expiresAt")
        assertEquals(ModuleVerdict.PROVEN, ok.verdict, ok.render())
        val bad = verifyMerged("boundary", "now >= existing.expiresAt")
        val text = bad.render()
        val tryLock = bad.row("tryLock")
        assertEquals(Verdict.FAILED to SubStatus.PROPERTY, tryLock.verdict to tryLock.sub, text)
        assertTrue("Postcondition of tryLock might not hold: sidecar ends_only_if P3a" in text, text)
    }
}
