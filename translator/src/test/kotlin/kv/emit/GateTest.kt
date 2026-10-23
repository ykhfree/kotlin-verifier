package kv.emit

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** The spike directory: the gate script and the hand-written target models. Read only. */
val spikeDir = File(System.getProperty("kv.spikeDir"))

/**
 * Runs `python3 -I spike/vpr_normalize.py EXPECTED ACTUAL` (the gate of DESIGN-v0.md 8.3) and returns its exit code
 * and output. Fails if the script does not finish within a minute.
 */
fun normalize(
    expected: File,
    actual: File,
): Pair<Int, String> = python(File(spikeDir, "vpr_normalize.py").path, expected.path, actual.path)

/** Runs `python3 -I` with [args]; returns the exit code and the merged output. */
fun python(vararg args: String): Pair<Int, String> {
    val p = ProcessBuilder(listOf("python3", "-I") + args).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    check(p.waitFor(60, TimeUnit.SECONDS)) { "python3 ${args.toList()} did not finish" }
    return p.exitValue() to out
}

/**
 * M5 acceptance 2 (DESIGN-v0.md section 7, gate of 8.3): the generated models are the hand-written target models up
 * to comments, layout and the names of position-suffixed locals. The core and reactor targets are CORE_variant.vpr
 * and REACTOR_variant.vpr, which spike/effect_ghost/mutants.py writes (8.3 "reactor gate").
 */
class GateTest {
    @TempDir
    lateinit var dir: File

    private fun generated(variant: String): File {
        val (m, s) = lowerReqShield(variant)
        return File(dir, "$variant.vpr").apply { writeText(emitBound(m, s).text) }
    }

    @Test
    fun `the generated coroutine model is target_vpr`() {
        val (code, out) = normalize(File(spikeDir, "effect_ghost/target.vpr"), generated("coroutine"))
        assertEquals(0, code, out)
    }

    /** The target models that spike/effect_ghost/mutants.py writes to a temp directory. */
    private fun targets(): File {
        val targets = File(dir, "targets")
        val (made, list) = python(File(spikeDir, "effect_ghost/mutants.py").path, targets.path)
        assertEquals(0, made, list)
        return targets
    }

    @Test
    fun `the generated core model is CORE_variant_vpr`() {
        val (code, out) = normalize(File(targets(), "CORE_variant.vpr"), generated("core"))
        assertEquals(0, code, out)
    }

    @Test
    fun `the generated reactor model is REACTOR_variant_vpr`() {
        val (code, out) = normalize(File(targets(), "REACTOR_variant.vpr"), generated("reactor"))
        assertEquals(0, code, out)
    }
}
