package kv.scripts

import kv.ReqShield
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The checks of the M8 script (scripts/mutants.py, DESIGN-v0.md section 6 rules 1-5) against a fake `kv` that prints
 * a canned report and exit code per mutant copy. The real run, with Silicon, is `./gradlew mutantsReqShield`.
 */
class MutantScriptTest {
    @TempDir
    lateinit var dir: File

    private val script = File(System.getProperty("kv.scriptsDir"), "mutants.py")

    /** A `kv verify` report: [unLock] is the verdict of the unLock row, followed by [findings]. */
    private fun report(
        verdict: String,
        unLock: String = "PROVEN",
        findings: List<String> = emptyList(),
        others: String = "init PROVEN · lemma P3c PROVEN · probe PROVEN",
    ): String =
        (
            listOf(
                "m.Companion (keylocallock.coroutine.kv)    module: $verdict (Silicon 4.20 s)",
                "  action tryLock  KeyLocalLock.kt:155:9   PROVEN",
                "  action unLock   KeyLocalLock.kt:209:9   $unLock",
            ) + findings.map { "      $it" } +
                listOf("  action sweep    KeyLocalLock.kt:97:37   PROVEN", "  $others", "Spec to review: none", "")
        ).joinToString("\n")

    private val p3a = "Postcondition of unLock might not hold: sidecar ends_only_if P3a keylocallock.common.kv:78:3 (incomplete quantifiers)"
    private val sb1Failed = Response(1, report("FAILED", "FAILED", listOf(p3a)))
    private val sb1Rows = "coroutine  SB1  FAILED  unLock  ends_only_if P3a\ncore  SB1  FAILED  unLock  ends_only_if P3a\n"

    private data class Response(
        val exit: Int,
        val out: String,
        val err: String = "",
    )

    /**
     * Runs the script on mutant [only] of both variants with a fake kv. [responses] maps `<variant>-<mutant>` to the
     * fake run's result. [expected] is the expected table. Returns the exit code and the output.
     */
    private fun run(
        only: String,
        expected: String,
        responses: Map<String, Response>,
        reqShield: File = ReqShield.dir,
    ): Pair<Int, String> {
        val data = File(dir, "responses").apply { mkdirs() }
        for ((root, r) in responses) {
            File(data, "$root.exit").writeText("${r.exit}")
            File(data, "$root.out").writeText(r.out)
            File(data, "$root.err").writeText(r.err)
        }
        val kv =
            File(dir, "kv").apply {
                writeText(
                    """
                    |#!/usr/bin/env python3
                    |import os, sys
                    |root = os.path.join("${data.path}", os.path.basename(sys.argv[sys.argv.index("--root") + 1]))
                    |sys.stdout.write(open(root + ".out").read())
                    |sys.stderr.write(open(root + ".err").read())
                    |sys.exit(int(open(root + ".exit").read()))
                    |
                    """.trimMargin(),
                )
                setExecutable(true)
            }
        val table = File(dir, "expected.txt").apply { writeText(expected) }
        val p =
            ProcessBuilder(
                "python3", "-I", script.path, "--kv", kv.path, "--req-shield", reqShield.path, "--classpath", "unused",
                "--kotlinc", "unused", "--specs", dir.path, "--expected", table.path, "--out", File(dir, "out").path, "--only", only,
            ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(120, TimeUnit.SECONDS)) { "mutants.py did not finish" }
        return p.exitValue() to out
    }

    @Test
    fun `matching verdicts and obligations exit 0, and the copy holds the mutant`() {
        val (exit, out) = run("SB1", sb1Rows, mapOf("coroutine-SB1" to sb1Failed, "core-SB1" to sb1Failed))
        assertEquals(0, exit, out)
        assertTrue(out.lines().any { it.startsWith("coroutine  SB1       FAILED") && it.endsWith("unLock {ends_only_if P3a}") }, out)
        assertTrue("2 run(s), 0 mismatch(es)" in out, out)
        val rel = "core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine/KeyLocalLock.kt"
        val copy = File(dir, "out/roots/coroutine-SB1/$rel").readText()
        assertTrue("existing.token == token && " !in copy && "if (existing.isHeld.compareAndSet(true, false))" in copy, copy)
        assertTrue("existing.token == token && " in File(ReqShield.dir, rel).readText(), "req-shield is not touched")
    }

    @Test
    fun `a different set of failing obligations is a mismatch`() {
        val p1 = "Postcondition of unLock might not hold: sidecar P1 keylocallock.common.kv:76:3"
        val (exit, out) = run("SB1", sb1Rows, mapOf("coroutine-SB1" to Response(1, report("FAILED", "FAILED", listOf(p3a, p1))), "core-SB1" to sb1Failed))
        assertEquals(1, exit, out)
        assertTrue("MISMATCH: FAILED rows unLock {P1, ends_only_if P3a}, expected unLock {ends_only_if P3a}" in out, out)
    }

    @Test
    fun `a PROVEN run where FAILED is expected is a mismatch`() {
        val (exit, out) = run("SB1", sb1Rows, mapOf("coroutine-SB1" to sb1Failed, "core-SB1" to Response(0, report("PROVEN"))))
        assertEquals(1, exit, out)
        assertTrue(out.lines().any { it.startsWith("core       SB1") && "MISMATCH: verdict PROVEN, expected FAILED" in it }, out)
    }

    @Test
    fun `INCOMPLETE and ERROR always fail the script`() {
        val incomplete = Response(3, report("INCOMPLETE", "UNSUPPORTED", listOf("UNSUPPORTED String value in action unLock#209")))
        val (exit1, out1) = run("SB1", sb1Rows, mapOf("coroutine-SB1" to incomplete, "core-SB1" to sb1Failed))
        assertEquals(1, exit1, out1)
        assertTrue("verdict INCOMPLETE, expected FAILED" in out1 && "rows that are neither PROVEN nor FAILED: unLock" in out1, out1)
        val error = Response(4, report("ERROR", "ERROR", others = "init ERROR · lemma P3c ERROR · probe ERROR"))
        val (exit2, out2) = run("SB1", sb1Rows, mapOf("coroutine-SB1" to sb1Failed, "core-SB1" to error))
        assertEquals(1, exit2, out2)
        assertTrue("verdict ERROR, expected FAILED" in out2 && "init ERROR" in out2, out2)
    }

    @Test
    fun `a mutant that does not compile is a script error, not a verdict`() {
        val notCompiled = Response(4, "", "KeyLocalLock.kt:213:5: error: unresolved reference\nkv: compilation failed\n")
        val (exit, out) = run("SB1", sb1Rows, mapOf("coroutine-SB1" to notCompiled, "core-SB1" to sb1Failed))
        assertEquals(2, exit, out)
        assertTrue("does not compile (rule 3)" in out, out)
    }

    @Test
    fun `an edit that does not match exactly once is a script error`() {
        // A req-shield copy whose coroutine unLock has no token check: the SB1 edit matches 0 times there.
        val fake = File(dir, "req-shield")
        for (variant in listOf("core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine", "core/src/main/kotlin/com/linecorp/cse/reqshield")) {
            for (name in listOf("KeyLock.kt", "KeyLocalLock.kt")) File(ReqShield.dir, "$variant/$name").copyTo(File(fake, "$variant/$name"))
        }
        val kt = File(fake, "core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine/KeyLocalLock.kt")
        kt.writeText(kt.readText().replace("existing.token == token && ", ""))
        val (exit, out) = run("SB1", sb1Rows, mapOf("coroutine-SB1" to sb1Failed, "core-SB1" to sb1Failed), fake)
        assertEquals(2, exit, out)
        assertTrue("coroutine SB1: edit matches 0 times (must be 1)" in out, out)
    }
}
