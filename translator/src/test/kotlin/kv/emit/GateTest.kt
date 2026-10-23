package kv.emit

import kv.Aspects
import kv.ReqShield
import kv.frontend.Frontend
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

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

    // ---- DESIGN-B.md 8, B3: the Spring aspect gate ----

    /** The emitted model of aspect [module] with the req-shield sources under [root], written to [name].vpr. */
    private fun generatedAspect(
        module: String,
        root: File = ReqShield.dir,
        name: String = module,
    ): File {
        val outcome = Frontend.resolve(File(specDir, "aspect.$module.kv"), root, Aspects.classpath, ReqShield.kotlincArgs)
        assertNull(outcome.compileMessages)
        assertEquals(emptyList(), outcome.errors)
        val m = outcome.module ?: fail("no module")
        return File(dir, "$name.vpr").apply { writeText(emitBound(m, outcome.sidecar!!).text) }
    }

    @Test
    fun `the generated core-spring aspect model is spike aspect target_vpr`() {
        val (code, out) = normalize(File(spikeDir, "aspect/target.vpr"), generatedAspect("core-spring"))
        assertEquals(0, code, out)
    }

    /**
     * B3 acceptance 2 under DESIGN-B.md 9.4 question 5: the core-spring Kotlin mutants of scripts/mutants.py (suite
     * aspect) give the models of spike/aspect/mutants.py. In M2, M4 and M7 the key argument getTargetMethod(joinPoint)
     * runs after the fresh call and before the action, so the generated model has one more raise path; with that
     * path taken out, the models are the same. All other mutants and the two controls are the same as they are.
     */
    @Test
    fun `the core-spring Kotlin mutants give the models of spike aspect mutants_py, up to the question 5 raise`() {
        val targets = File(dir, "aspect-targets")
        val (made, list) = python(File(spikeDir, "aspect/mutants.py").path, targets.path)
        assertEquals(0, made, list)
        val names = mapOf(
            "R1" to "R1_compute_elvis", "R2" to "R2_fast_path_get", "M1" to "M1_get_then_put", "M2" to "M2_unconditional_put",
            "M3" to "M3_getOrPut", "M4" to "M4_putIfAbsent_after_create", "M5" to "M5_shared_instance",
            "M6" to "M6_remove_on_failure", "M7" to "M7_putIfAbsent_ignore_result",
        )
        val (code, roots) =
            python(
                File(System.getProperty("kv.scriptsDir"), "mutants.py").path, "--suite", "aspect", "--req-shield", ReqShield.dir.path,
                "--out", File(dir, "mutants").path, "--roots-only", "--only", names.keys.joinToString(","),
            )
        assertEquals(0, code, roots)
        val raising = setOf("M2", "M4", "M7")
        for (line in roots.lines().filter { it.startsWith("core-spring ") }) {
            val (_, mutant, root) = line.split(" ", limit = 3)
            val generated = generatedAspect("core-spring", File(root), "core-spring-$mutant")
            val expected = File(targets, "${names.getValue(mutant)}.vpr")
            val (same, diff) = normalize(expected, generated)
            if (mutant !in raising) {
                assertEquals(0, same, "$mutant:\n$diff")
                continue
            }
            assertEquals(1, same, "$mutant has the question 5 raise path:\n$diff")
            val stripped = File(dir, "core-spring-$mutant-without-raise.vpr").apply { writeText(withoutQuestion5Raise(generated.readText(), mutant)) }
            val (rest, restDiff) = normalize(expected, stripped)
            assertEquals(0, rest, "$mutant without its raise path:\n$restDiff")
        }
    }

    /**
     * [text] without its one raise of DESIGN-B.md 9.4 question 5: the flag declaration, `if (flag) { exc := true }
     * else {` and the brace that closes the else. Fails unless there is exactly one.
     */
    private fun withoutQuestion5Raise(
        text: String,
        what: String,
    ): String {
        val lines = text.lines().toMutableList()
        val flags = lines.withIndex().filter { Regex("""^\s*var cond_\d+_\d+: Bool  // call .* may throw""").containsMatchIn(it.value) }
        assertEquals(1, flags.size, "$what: one question 5 flag in\n$text")
        val i = flags.single().index
        val indent = lines[i].takeWhile { it == ' ' }
        val flag = Regex("""cond_\d+_\d+""").find(lines[i])!!.value
        assertEquals("${indent}if ($flag) {", lines[i + 1])
        assertTrue(lines[i + 2].trim().startsWith("exc := true"), lines[i + 2])
        assertEquals("$indent} else {", lines[i + 3])
        val close = (i + 4 until lines.size).first { lines[it] == "$indent}" }
        lines.removeAt(close)
        repeat(4) { lines.removeAt(i) }
        return lines.joinToString("\n")
    }

    @Test
    fun `the generated webflux and coroutine aspect models differ from core-spring only in positions`() {
        val core = generatedAspect("core-spring")
        for (module in listOf("webflux", "coroutine")) {
            val (code, out) = normalize(core, generatedAspect(module))
            assertEquals(0, code, "$module:\n$out")
        }
    }
}
