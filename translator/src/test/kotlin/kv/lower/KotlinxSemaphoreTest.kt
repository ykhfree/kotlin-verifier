package kv.lower

import kv.emit.OVER_RELEASE
import kv.emit.Source
import kv.emit.Workspace
import kv.emit.variantDirs
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
 * Range fix 2 of AI-CODE.md 11: the kotlinx.Semaphore model (TrustedModels). A sidecar `Semaphore` field binds a
 * java.util.concurrent or a kotlinx.coroutines.sync Semaphore; the Kotlin type chooses the model, which the sidecar
 * must `use`. The kotlinx field is a count of available permits with a maximum, and `release()` is a proof
 * obligation that the count is below the maximum. The fixtures write the kotlinx type with its package, because the
 * fixture file imports the jdk Semaphore.
 */
class KotlinxSemaphoreTest {
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

        /** A new entry from [semaphore]; an entry that is held gives its permit back. */
        fun body(semaphore: String) = "if (e == null) return@compute Info($semaphore, now)\nif (e.isHeld.compareAndSet(true, false)) e.semaphore.release()\ne"
    }

    private fun LockFixture.e() = "e__${at("{ _, e ->").first}"

    private fun LockFixture.line(fragment: String) = at(fragment).first

    private fun sub(name: String) = File(dir, name).apply { mkdirs() }

    // ---- the model chosen by the Kotlin type ----

    @Test
    fun `a kotlinx Semaphore field binds kotlinx_Semaphore, and a jdk one still binds jdk_Semaphore`() {
        val kx = LockFixture(body("$KX(1, 1)"), info = KX_INFO, operations = KX_OPERATION)
        val m = kx.lower(sub("kotlinx"))
        val text = assertSupported(m)
        val e = kx.e()
        val n = "new_${kx.line("return@compute Info")}_${kx.at("Info($KX").second}"
        val (cl, cc) = kx.at("e.isHeld.compareAndSet")
        assertEquals("kotlinx.Semaphore", m.classes.single().fields.single { it.name == "permits" }.model)
        assertEquals(
            listOf(
                "if $e == null",
                "  new $n: Info {permits := 0, isHeld := false, token := null, expiresAt := now}",
                "  exit put($n)",
                "else",
                "  cond_${cl}_$cc := jdk.AtomicBoolean.compareAndSet($e.isHeld, true, false)",
                "  if cond_${cl}_$cc",
                "    kotlinx.Semaphore.release($e.permits, 1)",
                "  exit keep",
            ),
            shape(text),
        )
        // The same code with a java.util.concurrent.Semaphore: the jdk model, whose release has no obligation.
        val jdk = LockFixture(body("Semaphore(0)"))
        val j = jdk.lower(sub("jdk"))
        val jdkText = assertSupported(j)
        assertEquals("jdk.Semaphore", j.classes.single().fields.single { it.name == "permits" }.model)
        assertTrue("    jdk.Semaphore.release(${jdk.e()}.permits)" in shape(jdkText), jdkText)
        assertTrue("kotlinx" !in jdkText, jdkText)
    }

    @Test
    fun `named arguments give the available count and the maximum`() {
        val f = LockFixture(body("$KX(permits = 2, acquiredPermits = 1)"), info = KX_INFO, operations = KX_OPERATION)
        val s = shape(assertSupported(f.lower(dir)))
        assertTrue(s.any { "new new_" in it && "{permits := 1," in it }, s.joinToString("\n"))
        assertTrue("    kotlinx.Semaphore.release(${f.e()}.permits, 2)" in s, s.joinToString("\n"))
    }

    @Test
    fun `availablePermits reads the count, and tryAcquire is the jdk guard`() {
        val f =
            LockFixture(
                "if (e == null) return@compute Info($KX(1), now)\nif (e.semaphore.availablePermits > 0 && e.semaphore.tryAcquire()) e.expiresAt = now\ne",
                info = KX_INFO,
                operations = KX_OPERATION,
            )
        val e = f.e()
        val n = "new_${f.line("return@compute Info")}_${f.at("Info($KX").second}"
        val (l, c) = f.at("e.semaphore.availablePermits")
        assertEquals(
            listOf(
                "if $e == null",
                "  new $n: Info {permits := 1, isHeld := false, token := null, expiresAt := now}",
                "  exit put($n)",
                "else",
                "  n_${l}_$c := kotlinx.Semaphore.availablePermits($e.permits)",
                "  var cond_${l}_$c: Bool := false",
                "  if n_${l}_$c > 0",
                "    cond_${l}_$c := kotlinx.Semaphore.tryAcquire($e.permits)",
                "  if cond_${l}_$c",
                "    $e.expiresAt := now",
                "  exit keep",
            ),
            shape(assertSupported(f.lower(dir))),
        )
    }

    // ---- constructions the model does not cover ----

    @Test
    fun `permits below 1, acquiredPermits above permits, and a non-literal argument are unsupported`() {
        for ((name, semaphore) in listOf("zero" to "$KX(0)", "over" to "$KX(1, 2)", "variable" to "$KX(now.toInt())")) {
            val f = LockFixture(body(semaphore), info = KX_INFO, operations = KX_OPERATION)
            val u = assertUnsupported(f.lower(sub(name)), "flattened field semaphore", f.at("$KX("))
            assertTrue("needs Int literals with 1 <= permits and 0 <= acquiredPermits <= permits" in u.reason, "$name: ${u.reason}")
        }
    }

    @Test
    fun `two constructions with different permits are unsupported`() {
        val f =
            LockFixture(
                "if (e == null) return@compute Info($KX(1, 1), now)\nif (now < 0) return@compute Info($KX(2, 2), now)\nif (e.isHeld.compareAndSet(true, false)) e.semaphore.release()\ne",
                info = KX_INFO,
                operations = KX_OPERATION,
            )
        val u = assertUnsupported(f.lower(dir), "flattened field semaphore", f.at("$KX("))
        assertTrue("give different permits (1, 2)" in u.reason && "${f.at("$KX(2, 2)").first}:" in u.reason, u.reason)
    }

    // ---- the sidecar must use the chosen model ----

    @Test
    fun `a Semaphore field whose model the sidecar does not use is a sidecar error that names the missing use`() {
        val kx = Workspace(sub("kotlinx"))
        kx.toKotlinx()
        val kxErrors = kx.resolve("coroutine").errors
        assertEquals(listOf("missing-use"), kxErrors.map { it.code }, kxErrors.joinToString("\n"))
        assertTrue("`use kotlinx.Semaphore`" in kxErrors.single().message, kxErrors.single().message)
        assertEquals(kx.at("keylocallock.common.kv", "permits:   Semaphore", "Semaphore"), kxErrors.single().pos.line to kxErrors.single().pos.col)
        // The same rule for the jdk model.
        val jdk = Workspace(sub("jdk"))
        jdk.editSpec("keylocallock.common.kv", "use jdk.Semaphore\n", "")
        val jdkErrors = jdk.resolve("coroutine").errors
        assertEquals(listOf("missing-use"), jdkErrors.map { it.code }, jdkErrors.joinToString("\n"))
        assertTrue("`use jdk.Semaphore`" in jdkErrors.single().message, jdkErrors.single().message)
    }

    // ---- Silicon ----

    /** The coroutine KeyLocalLock with a kotlinx Semaphore created acquired, and the sidecar using its model. */
    private fun Workspace.toKotlinx() {
        editKotlin("coroutine", "import java.util.concurrent.Semaphore\n", "import kotlinx.coroutines.sync.Semaphore\n")
        editKotlin("coroutine", "LockInfo(Semaphore(1), now", "LockInfo(Semaphore(1, 1), now")
        editKotlin("coroutine", "newLock.semaphore.tryAcquire() // Always succeeds for new semaphore\n", "\n")
    }

    private fun Workspace.useKotlinx() = editSpec("keylocallock.coroutine.kv", "include \"keylocallock.common.kv\"\n", "include \"keylocallock.common.kv\"\nuse kotlinx.Semaphore\n")

    @Test
    fun `the coroutine KeyLocalLock with a kotlinx Semaphore created acquired and released once is PROVEN`() {
        Silicon.check(Silicon.defaultHome())
        val ws = Workspace(dir)
        ws.toKotlinx()
        ws.useKotlinx()
        val r = ws.verify("coroutine")
        val text = r.render()
        assertEquals(ModuleVerdict.PROVEN, r.verdict, text)
        assertEquals(listOf(Verdict.PROVEN), r.rows.map { it.verdict }.distinct(), text)
        // Each release (tryLock's takeover, unLock, sweep) carries its obligation.
        assertEquals(3, File(dir, "coroutine.vpr").readLines().count { Regex("""^\s*assert \S+\.permits < 1 """).containsMatchIn(it) })
    }

    @Test
    fun `an over-release is FAILED at the release obligation, with the Kotlin position`() {
        Silicon.check(Silicon.defaultHome())
        val ws = Workspace(dir)
        ws.toKotlinx()
        ws.useKotlinx()
        ws.editKotlin("coroutine", "existing.semaphore.release()\n                released.set(true)", "existing.semaphore.release()\n                existing.semaphore.release()\n                released.set(true)")
        val lines = File(ws.root, "${variantDirs.getValue("coroutine")}/KeyLocalLock.kt").readLines()
        val second = lines.indexOfFirst { "released.set(true)" in it } - 1
        val pos = second + 1 to lines[second].indexOf("existing") + 1
        val r = ws.verify("coroutine")
        val text = r.render()
        assertEquals(ModuleVerdict.FAILED, r.verdict, text)
        val unLock = r.row("unLock")
        assertEquals(Verdict.FAILED to SubStatus.PROPERTY, unLock.verdict to unLock.sub, text)
        val f = unLock.findings.first()
        val src = f.source as Source.Kotlin
        assertEquals(pos to OVER_RELEASE, (src.pos.line to src.pos.col) to src.note, text)
        assertTrue("kotlin KeyLocalLock.kt:${pos.first}:${pos.second} \"$OVER_RELEASE\"" in text, text)
        assertEquals(listOf(Verdict.PROVEN), r.rows.filter { it.label != "unLock" }.map { it.verdict }.distinct(), text)
    }
}
