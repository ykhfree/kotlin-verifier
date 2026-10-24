package kv.lower

import kv.emit.Workspace
import kv.kvir.DispositionKind
import kv.sidecar.checkSidecar
import kv.sidecar.parseSidecar
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
 * Range fix 1 of AI-CODE.md 11: `Int.toLong()` is the identity on mathematical integers, and inside an action
 * `lockMap.size` is an arbitrary value with no contract, as `mappingCount()` is. Outside every action the conversion
 * is not modeled and `lockMap.size` stays a racy read.
 */
class IntToLongTest {
    @TempDir
    lateinit var dir: File

    private companion object {
        const val CALL = "com.linecorp.cse.reqshield.support.config.LocalLockLimit.rejectsNewEntry"

        // The fingerprint of LocalLockLimit.kt:86-101 at req-shield 2e792d2, as in keylocallock.common.kv (B6).
        const val REJECTS =
            "trusted fun $CALL(kotlin.Long)\n" +
                "  returns rej: Bool\n  ghost   limit: Int\n  ensures rej ==> limit != 0\n  effects none\n  reviewed \"test fixture\" sha256 \"c5bffa07e14e90b1\"\n"
    }

    @Test
    fun `rejectsNewEntry(lockMap_size_toLong()) inside compute is an arbitrary size and then the trusted call`() {
        val f = LockFixture("if ($CALL(lockMap.size.toLong())) return@compute null\ne", operations = REJECTS + LockFixture.DEFAULT_OPERATION)
        val text = assertSupported(f.lower(dir))
        val (l, c) = f.at("lockMap.size")
        val (cl, cc) = f.at("com.linecorp")
        assertEquals(
            listOf(
                "nondet n_${l}_$c := jdk.ConcurrentHashMap.racyRead",
                "nondet rej_${cl}_$cc, limit_${cl}_$cc := LocalLockLimit.rejectsNewEntry",
                "if rej_${cl}_$cc",
                "  exit remove",
                "else",
                "  exit keep",
            ),
            shape(text),
        )
        // The conversion is the identity: no statement and no ASSUMPTION of its own.
        assertTrue("toLong" !in text, text)
    }

    @Test
    fun `a narrowing Long_toInt inside an action stays unsupported`() {
        val f = LockFixture("if (e != null && e.expiresAt.toInt() > 0) e.expiresAt = now\ne")
        assertUnsupported(f.lower(dir), "Long toInt", f.at("e.expiresAt.toInt"))
    }

    @Test
    fun `size of a map that is not the module map is unsupported inside an action`() {
        val f = LockFixture("if (e != null && other.size > 0) e.expiresAt = now\ne", companion = "        private val other = ConcurrentHashMap<String, Long>()")
        assertUnsupported(f.lower(dir), "size on other.size", f.at("other.size"))
    }

    @Test
    fun `toLong outside every action is not modeled, and its value enters the action as a free value`() {
        val f = LockFixture("if (e != null && n > 0L) e.expiresAt = now\ne", before = "        val n = lockMap.size.toLong()")
        val m = f.lower(dir)
        val text = assertSupported(m)
        val n = m.run.free.single { it.kotlin == "n" }
        assertEquals(f.line("val n"), n.local.pos.line, text)
        val e = f.e()
        assertEquals(listOf("if ($e != null) && (${n.local.name} > 0)", "  $e.expiresAt := now", "exit keep"), shape(text))
        // Outside the action, lockMap.size is a racy read, as before the fix.
        assertTrue(m.ledger.any { it.kind == DispositionKind.RACY_READ && it.what == "lockMap.size" && it.pos.line == f.line("val n") }, m.ledger.joinToString("\n"))
    }

    @Test
    fun `a conversion needs no use, so a sidecar cannot use one`() {
        val text = LockFixture("e").sidecar + "use kotlin.Int.toLong\n"
        val errors = checkSidecar(parseSidecar(File(dir, "Lock.kv").path, text))
        assertEquals(listOf("unknown-model"), errors.map { it.code }, errors.joinToString("\n"))
    }

    @Test
    fun `the coroutine KeyLocalLock with lockMap_size_toLong() instead of mappingCount() is PROVEN`() {
        Silicon.check(Silicon.defaultHome())
        val ws = Workspace(dir)
        ws.editKotlin("coroutine", "rejectsNewEntry(lockMap.mappingCount())", "rejectsNewEntry(lockMap.size.toLong())")
        val r = ws.verify("coroutine")
        val text = r.render()
        assertEquals(ModuleVerdict.PROVEN, r.verdict, text)
        assertEquals(Verdict.PROVEN, r.row("tryLock").verdict, text)
        assertTrue("Unsupported: none" in text, text)
    }

    private fun LockFixture.line(fragment: String) = at(fragment).first

    private fun LockFixture.e() = "e__${at("{ _, e ->").first}"
}
