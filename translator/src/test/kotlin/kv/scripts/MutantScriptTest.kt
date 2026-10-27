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
    private val sb1Rows = "coroutine  SB1  FAILED  unLock  ends_only_if P3a\ncore  SB1  FAILED  unLock  ends_only_if P3a\nreactor  SB1  FAILED  unLock  ends_only_if P3a\n"

    private data class Response(
        val exit: Int,
        val out: String,
        val err: String = "",
    )

    /**
     * Runs the script on mutant [only] of the three variants with a fake kv. [responses] maps `<variant>-<mutant>` to the
     * fake run's result. [expected] is the expected table. Returns the exit code and the output.
     */
    private fun run(
        only: String,
        expected: String,
        responses: Map<String, Response>,
        reqShield: File = ReqShield.dir,
        suite: String = "keylocallock",
        extra: List<String> = emptyList(),
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
                    |open(root + ".args", "w").write("\n".join(sys.argv[1:]))
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
                "python3", "-I", script.path, "--suite", suite, "--kv", kv.path, "--req-shield", reqShield.path, "--classpath", "unused",
                "--kotlinc", "unused", "--specs", dir.path, "--expected", table.path, "--out", File(dir, "out").path, "--only", only, *extra.toTypedArray(),
            ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(120, TimeUnit.SECONDS)) { "mutants.py did not finish" }
        return p.exitValue() to out
    }

    @Test
    fun `matching verdicts and obligations exit 0, and the copy holds the mutant`() {
        val (exit, out) = run("SB1", sb1Rows, mapOf("coroutine-SB1" to sb1Failed, "core-SB1" to sb1Failed, "reactor-SB1" to sb1Failed))
        assertEquals(0, exit, out)
        assertTrue(out.lines().any { it.startsWith("coroutine  SB1       FAILED") && it.endsWith("unLock {ends_only_if P3a}") }, out)
        assertTrue("3 run(s), 0 mismatch(es)" in out, out)
        for ((variant, rel) in listOf(
            "coroutine" to "core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine/KeyLocalLock.kt",
            "reactor" to "core-reactor/src/main/kotlin/com/linecorp/cse/reqshield/reactor/KeyLocalLock.kt",
        )) {
            val copy = File(dir, "out/roots/$variant-SB1/$rel").readText()
            assertTrue("existing.token == token && " !in copy && "if (existing.isHeld.compareAndSet(true, false))" in copy, copy)
            assertTrue("existing.token == token && " in File(ReqShield.dir, rel).readText(), "req-shield is not touched")
        }
    }

    @Test
    fun `a different set of failing obligations is a mismatch`() {
        val p1 = "Postcondition of unLock might not hold: sidecar P1 keylocallock.common.kv:76:3"
        val (exit, out) = run("SB1", sb1Rows, mapOf("coroutine-SB1" to Response(1, report("FAILED", "FAILED", listOf(p3a, p1))), "core-SB1" to sb1Failed, "reactor-SB1" to sb1Failed))
        assertEquals(1, exit, out)
        assertTrue("MISMATCH: FAILED rows unLock {P1, ends_only_if P3a}, expected unLock {ends_only_if P3a}" in out, out)
    }

    @Test
    fun `the sub-status and the property check errors are printed, and only the pass-1 obligations are compared`() {
        val hole = "Postcondition of unLock might not hold: sidecar MI_INV_1 keylocallock.common.kv:99:3 \"INV-E\""
        val p1 = "Postcondition of unLock might not hold: sidecar P1 keylocallock.common.kv:78:3"
        val property = Response(1, report("FAILED", "FAILED (property)", listOf(hole, "  pass 2 (property check): $p1")))
        val invariantOnly = Response(1, report("FAILED", "FAILED (invariant only)", listOf(hole, "  meaning: this action does not preserve the inductive invariant (the hole MI_INV).")))
        val checkError = Response(1, report("FAILED", "FAILED (invariant only; property check ERROR)", listOf(hole, "  property check ERROR: timeout; it is not known whether a sidecar property fails")))
        val responses = mapOf("coroutine-SB1" to property, "core-SB1" to invariantOnly, "reactor-SB1" to checkError)
        val rows = "coroutine  SB1  FAILED  unLock  MI_INV_1\ncore  SB1  FAILED  unLock  MI_INV_1\nreactor  SB1  FAILED  unLock  MI_INV_1\n"
        val (exit, out) = run("SB1", rows, responses)
        assertEquals(0, exit, out)
        assertTrue(out.lines().any { it.startsWith("coroutine  SB1") && it.endsWith("unLock {MI_INV_1} (property; pass 2 {P1})") }, out)
        assertTrue(out.lines().any { it.startsWith("core       SB1") && it.endsWith("unLock {MI_INV_1} (invariant only)") }, out)
        assertTrue(out.lines().any { it.startsWith("reactor    SB1") && it.endsWith("unLock {MI_INV_1} (invariant only; property check ERROR)") }, out)
        // A pass-2 error is not a failing obligation of the expected table.
        val (exit2, out2) = run("SB1", rows.replaceFirst("unLock  MI_INV_1", "unLock  MI_INV_1; P1"), responses)
        assertEquals(1, exit2, out2)
        assertTrue("MISMATCH: FAILED rows unLock {MI_INV_1}, expected unLock {MI_INV_1, P1}" in out2, out2)
    }

    @Test
    fun `the report reader takes named probe rows (DESIGN-C 7_2) with the other rows`() {
        val text = report("FAILED", others = "init PROVEN · lemma P3c PROVEN · probe PROVEN · probe expiry_boundary FAILED")
        val code = "import sys; sys.path.insert(0, sys.argv[1]); import mutants; print(mutants.parse_report(open(sys.argv[2]).read())[1])"
        val file = File(dir, "report.txt").apply { writeText(text) }
        val p = ProcessBuilder("python3", "-I", "-c", code, script.parent, file.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        assertEquals("[['init', 'PROVEN'], ['lemma P3c', 'PROVEN'], ['probe', 'PROVEN'], ['probe expiry_boundary', 'FAILED']]", out.trim(), out)
    }

    @Test
    fun `a PROVEN run where FAILED is expected is a mismatch`() {
        val (exit, out) = run("SB1", sb1Rows, mapOf("coroutine-SB1" to sb1Failed, "core-SB1" to Response(0, report("PROVEN")), "reactor-SB1" to sb1Failed))
        assertEquals(1, exit, out)
        assertTrue(out.lines().any { it.startsWith("core       SB1") && "MISMATCH: verdict PROVEN, expected FAILED" in it }, out)
    }

    @Test
    fun `INCOMPLETE and ERROR always fail the script`() {
        val incomplete = Response(3, report("INCOMPLETE", "UNSUPPORTED", listOf("UNSUPPORTED String value in action unLock#209")))
        val (exit1, out1) = run("SB1", sb1Rows, mapOf("coroutine-SB1" to incomplete, "core-SB1" to sb1Failed, "reactor-SB1" to sb1Failed))
        assertEquals(1, exit1, out1)
        assertTrue("verdict INCOMPLETE, expected FAILED" in out1 && "rows that are neither PROVEN nor FAILED: unLock" in out1, out1)
        val error = Response(4, report("ERROR", "ERROR", others = "init ERROR · lemma P3c ERROR · probe ERROR"))
        val (exit2, out2) = run("SB1", sb1Rows, mapOf("coroutine-SB1" to sb1Failed, "core-SB1" to error, "reactor-SB1" to sb1Failed))
        assertEquals(1, exit2, out2)
        assertTrue("verdict ERROR, expected FAILED" in out2 && "init ERROR" in out2, out2)
    }

    @Test
    fun `a mutant that does not compile is a script error, not a verdict`() {
        val notCompiled = Response(4, "", "KeyLocalLock.kt:213:5: error: unresolved reference\nkv: compilation failed\n")
        val (exit, out) = run("SB1", sb1Rows, mapOf("coroutine-SB1" to notCompiled, "core-SB1" to sb1Failed, "reactor-SB1" to sb1Failed))
        assertEquals(2, exit, out)
        assertTrue("does not compile (rule 3)" in out, out)
    }

    @Test
    fun `the aspect suite copies the module sources, edits the aspect file, and names a generated obligation without Silicon's reason`() {
        fun aspectReport(
            verdict: String,
            findings: List<String>,
        ) = (
            listOf("a.ReqShieldAspect (aspect.x.kv)    module: $verdict (Silicon 2.20 s)", "  action getOrCreate  ReqShieldAspect.kt:112:9   $verdict") +
                findings.map { "      $it" } + listOf("        meaning: a map value is not an object this module created, or two keys share one object", "  init PROVEN · probe PROVEN", "Spec to review: none", "")
        ).joinToString("\n")
        val m5 = Response(1, aspectReport("FAILED", listOf("Postcondition of getOrCreate might not hold: generated MI_OWN (incomplete quantifiers)")))
        val rows = listOf("core-spring", "webflux", "coroutine").joinToString("") { "$it  M5  FAILED  getOrCreate  MI_OWN\n" }
        val (exit, out) = run("M5", rows, listOf("core-spring", "webflux", "coroutine").associate { "$it-M5" to m5 }, suite = "aspect")
        assertEquals(0, exit, out)
        assertTrue("3 run(s), 0 mismatch(es)" in out, out)
        val module = "core-spring/src/main/kotlin/com/linecorp/cse/reqshield/spring"
        val aspect = File(dir, "out/roots/core-spring-M5/$module/aspect/ReqShieldAspect.kt").readText()
        assertTrue("            sharedReqShield\n" in aspect && "private val sharedReqShield: ReqShield<T>" in aspect, aspect)
        // The whole main source directory is copied: the sidecar's `source` names it.
        assertTrue(File(dir, "out/roots/core-spring-M5/$module/cache/ReqShieldCache.kt").isFile)
        assertTrue("private val sharedReqShield" !in File(ReqShield.dir, "$module/aspect/ReqShieldAspect.kt").readText(), "req-shield is not touched")
    }

    @Test
    fun `an edit that does not match exactly once is a script error`() {
        // A req-shield copy whose coroutine unLock has no token check: the SB1 edit matches 0 times there.
        val fake = File(dir, "req-shield")
        for (variant in listOf(
            "core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine",
            "core/src/main/kotlin/com/linecorp/cse/reqshield",
            "core-reactor/src/main/kotlin/com/linecorp/cse/reqshield/reactor",
        )) {
            for (name in listOf("KeyLock.kt", "KeyLocalLock.kt")) File(ReqShield.dir, "$variant/$name").copyTo(File(fake, "$variant/$name"))
        }
        File(ReqShield.dir, "support/src/main/kotlin").copyRecursively(File(fake, "support/src/main/kotlin"))
        val kt = File(fake, "core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine/KeyLocalLock.kt")
        kt.writeText(kt.readText().replace("existing.token == token && ", ""))
        val (exit, out) = run("SB1", sb1Rows, mapOf("coroutine-SB1" to sb1Failed, "core-SB1" to sb1Failed, "reactor-SB1" to sb1Failed), fake)
        assertEquals(2, exit, out)
        assertTrue("coroutine SB1: edit matches 0 times (must be 1)" in out, out)
    }

    /** A client report (DESIGN-E 6.7): its module line and Properties rows, [failed] FAILED and the others PROVEN. */
    private fun clientReport(vararg failed: String): String =
        (
            listOf("DRAFT: not signed; the verdict counts for nothing", "c.ReqShield (reqshield.core.kv)    module: ${if (failed.isEmpty()) "PROVEN" else "FAILED"} (Silicon 2.30 s)", "Properties (each property's full failing set, DESIGN-E 5.2):") +
                listOf("LD_noleak", "LD_own", "RET_hit").map { "  ${it.padEnd(10)}  ${if (it in failed) "FAILED" else "PROVEN"}  ensures of getAndSet" } + listOf("Tool facts: LD_noleak", "")
        ).joinToString("\n")

    @Test
    fun `the client suite patches the fixed base, moves the hole loop line, edits the sidecar for a toggle, and compares the failing properties`() {
        File(System.getProperty("kv.specDir"), "reqshield/reqshield.core.kv").copyTo(File(dir, "reqshield.core.kv"))
        val patch = File(System.getProperty("kv.spikeDir"), "protocol2/fixed-core.patch")
        val rows = "original  M3  FAILED  LD_own; RET_hit\nfixed  M3  FAILED  LD_own\noriginal  A1_off  FAILED  LD_noleak; RET_hit  # a comment\nfixed  A1_off  FAILED  LD_noleak\n"
        val responses =
            mapOf(
                "original-M3" to Response(1, clientReport("LD_own", "RET_hit")),
                "fixed-M3" to Response(1, clientReport("LD_own")),
                "original-A1_off" to Response(1, clientReport("LD_noleak", "RET_hit")),
                "fixed-A1_off" to Response(1, clientReport("LD_noleak")),
            )
        val (exit, out) = run("M3,A1_off", rows, responses, suite = "client", extra = listOf("--fixed-patch", patch.path))
        assertEquals(0, exit, out)
        assertTrue(out.lines().any { it.startsWith("fixed      M3        FAILED") && it.endsWith("properties {LD_own}") }, out)
        val rel = "core/src/main/kotlin/com/linecorp/cse/reqshield/ReqShield.kt"
        val fixed = File(dir, "out/roots/fixed-M3/$rel").readText()
        assertTrue("private fun releaseQuietly(" in fixed && "                if (true) {" in fixed, "the fixed copy has the patch and the mutant")
        assertTrue("releaseQuietly" !in File(ReqShield.dir, rel).readText(), "req-shield is not touched")
        // The fixed copy's loop is at 187; the toggle drops `assume A1` with its source and reviewed lines; each run
        // reads its own signed sidecar, not as a draft.
        assertTrue("hole loop ReqShield.handleLockFailure@187" in File(dir, "out/sidecars/fixed-M3.kv").readText())
        val a1Off = File(dir, "out/sidecars/original-A1_off.kv").readText()
        assertTrue("assume A1:" !in a1Off && "a java.lang.Error ends the process" !in a1Off && "assume A3:" in a1Off, a1Off)
        val args = File(dir, "responses/original-A1_off.args").readLines()
        assertEquals(File(dir, "out/sidecars/original-A1_off.kv").path, args.last())
        assertTrue("--draft" !in args, args.toString())
        // Another failing set is a mismatch.
        val (bad, badOut) = run("M3,A1_off", rows, responses + ("fixed-M3" to Response(1, clientReport("LD_own", "LD_noleak"))), suite = "client", extra = listOf("--fixed-patch", patch.path))
        assertEquals(1, bad, badOut)
        assertTrue("MISMATCH: FAILED rows properties {LD_noleak, LD_own}, expected properties {LD_own}" in badOut, badOut)
    }
}
