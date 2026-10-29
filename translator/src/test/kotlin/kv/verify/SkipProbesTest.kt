package kv.verify

import kv.emit.MethodKind
import kv.emit.Workspace
import kv.emit.emitViper
import kv.lower.bindGhosts
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DESIGN-C.md 10.18: the property check (pass 2) skips every probe of its program with `--excludeMethods`, whose
 * pattern matches whole method names. The report reads only action errors of pass 2.
 */
class SkipProbesTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `the pass-2 program names its unnamed and named probes, and only those`() {
        val ws = Workspace(dir)
        // Two named probes after the signed unnamed one, signed as a fixture (review intent, DESIGN-C.md 10.14).
        val probes =
            listOf("first", "second").joinToString("") { name ->
                "\nprobe $name(k: Key) {\n  t := tryLock(k, now = 0, timeout = 1, rej = false, limit = 0)\n}\n" +
                    "  source none \"test fixture\"\nreviewed \"test fixture, not a signature\"\n"
            }
        File(ws.specs, "keylocallock.common.kv").appendText(probes)
        ws.rereview("keylocallock.common.kv")
        val (m, s) = ws.lower("coroutine")
        val pass2 = emitViper(bindGhosts(m, s), s, withoutHole = setOf("tryLock"))
        assertEquals(listOf("--excludeMethods", "probe|probe_first|probe_second"), skipProbes(pass2))
        // The actions are never in the pattern.
        val actions = pass2.methods.filter { it.kind != MethodKind.PROBE }.map { it.name }
        assertTrue("tryLock" in actions && actions.none { it in skipProbes(pass2)[1].split("|") }, actions.toString())
        // A program without a probe gets no extra argument.
        assertEquals(emptyList(), skipProbes(pass2.copy(methods = pass2.methods.filter { it.kind != MethodKind.PROBE })))
    }
}
