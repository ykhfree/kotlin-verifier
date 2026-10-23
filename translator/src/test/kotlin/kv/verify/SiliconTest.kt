package kv.verify

import kv.emit.Source
import kv.emit.emitBound
import kv.emit.lineOf
import kv.emit.colOf
import kv.emit.lowerReqShield
import kv.lower.LockFixture
import kv.lower.LockFixture.Companion.MINIMAL_GHOST
import kv.sidecar.parseSidecar
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Silicon outcome rules of DESIGN-v0.md 5.6 on fake Silicon results (no Silicon run), and the report verdicts
 * of 5.8 built from them: an abnormal run proves nothing, an unsupported part makes the module INCOMPLETE, and an
 * error maps to the innermost source range (5.7).
 */
class SiliconTest {
    @TempDir
    lateinit var dir: File

    private val budget = SiliconBudget()
    private val banner = "Silicon 1.1-SNAPSHOT (6b540aa4@(detached))\n"
    private val success = banner + "Silicon finished verification successfully in 3.97s.\n"

    private fun run(
        code: Int?,
        output: String,
    ) = classify(SiliconRun(code, output, 1.0), budget)

    private fun abnormal(
        code: Int?,
        output: String,
    ): String = assertIs<SiliconOutcome.Abnormal>(run(code, output)).reason

    @Test
    fun `exit 0 with the success line is the only verified run`() {
        assertEquals(SiliconOutcome.Verified, run(0, success))
        assertTrue("without 'Silicon finished" in abnormal(0, banner), "exit 0 without the success line")
        assertTrue("exit code 2" in abnormal(2, success))
        assertTrue("exit code 137" in abnormal(137, ""))
    }

    @Test
    fun `aborted and a Java exception are ERROR even with the success line`() {
        assertTrue("aborted" in abnormal(0, success + "Verification aborted exceptionally\n"))
        val trace = "Exception in thread \"main\" java.lang.StackOverflowError\n\tat viper.silicon.Foo.bar(Foo.scala:12)\n"
        assertTrue("Java exception" in abnormal(0, success + trace))
        assertTrue("Java exception" in abnormal(1, banner + "java.lang.IllegalStateException: no z3\n"))
    }

    @Test
    fun `a run killed at the wall clock limit is ERROR`() {
        assertTrue("timeout" in abnormal(null, banner))
    }

    @Test
    fun `exit 1 with the summary and verification errors is a failure, with positions and reasons`() {
        val out =
            banner + "Silicon found 2 errors in 4.01s:\n" +
                "  [0] Postcondition of tryLock might not hold. Assertion (forall qk: Key :: { S.m[qk] } P(qk)) might not hold. (gen.vpr@124.11--124.22)\n" +
                "Potential prover incompleteness: (incomplete quantifiers)\n\n" +
                "  [1] Assert might fail. Assertion !tok(res).gIssued might not hold. (gen.vpr@201.12--201.29)\n"
        val failed = assertIs<SiliconOutcome.Failed>(run(1, out))
        assertEquals(listOf(124 to 11, 201 to 12), failed.errors.map { it.line to it.col })
        assertEquals(listOf(124 to 22, 201 to 29), failed.errors.map { it.endLine to it.endCol })
        assertEquals(listOf("(incomplete quantifiers)", null), failed.errors.map { it.reason })
        assertTrue(failed.errors[0].message.startsWith("Postcondition of tryLock might not hold."))
    }

    @Test
    fun `with 10 or more errors Silicon pads the index, and every error is still read`() {
        val lines = (0..10).joinToString("") { i -> "  [${"%2d".format(i)}] Assert might fail. Assertion ok might not hold. (gen.vpr@${100 + i}.10--${100 + i}.12)\n\n" }
        val failed = assertIs<SiliconOutcome.Failed>(run(1, banner + "Silicon found 11 errors in 4.60s:\n" + lines))
        assertEquals((100..110).toList(), failed.errors.map { it.line })
    }

    @Test
    fun `a prover query canceled at the assert timeout is ERROR, not a failure`() {
        val out =
            banner + "Silicon found 1 error in 3.62s:\n" +
                "  [0] Postcondition of tryLock might not hold. There might be insufficient permission to access S.m[qk$4].token (gen.vpr@124.11--124.22)\n" +
                "Potential prover incompleteness: canceled\n"
        assertTrue("timeout" in abnormal(1, out))
    }

    @Test
    fun `a type or parse error is ERROR`() {
        assertTrue("type" in abnormal(1, banner + "Silicon found 1 error in 0.51s:\n  [0] found incompatible type `Int`, expected `Bool` (te.vpr@4.16--4.20)\n"))
        assertTrue("type" in abnormal(1, banner + "Silicon found 1 error in 0.47s:\n  [0] Parse error: Expected (parens | atom):3:1, found \"{\". (pe.vpr@3.1--3.1)\n"))
    }

    @Test
    fun `exit 1 without the summary, or with errors that do not parse, is ERROR`() {
        assertTrue("summary" in abnormal(1, banner + "  [0] Assert might fail. (gen.vpr@1.1--1.2)\n"))
        assertTrue("readable" in abnormal(1, banner + "Silicon found 2 errors in 1s:\n  [0] Assert might fail. (gen.vpr@1.1--1.2)\n  [1] something unreadable\n"))
    }

    @Test
    fun `a missing Silicon is reported with what is missing`() {
        val e = assertFailsWith<SiliconMissing> { Silicon.check(File(dir, "no-viper")) }
        assertTrue("viperserver.jar" in e.message!! && "VIPER_HOME" in e.message!!, e.message)
    }

    // ---- report verdicts from fake outcomes ----

    @Test
    fun `an abnormal run reports ERROR for every row and the module, never PROVEN`() {
        val (m, s) = lowerReqShield("coroutine")
        val emitted = emitBound(m, s)
        for (outcome in listOf(SiliconOutcome.Abnormal("exit code 0 without the success line", emptyList()), classify(SiliconRun(0, banner, 1.0), budget))) {
            val r = buildReport(m, s, emitted, outcome, 1.0)
            assertEquals(ModuleVerdict.ERROR, r.verdict)
            assertEquals(listOf(Verdict.ERROR), r.rows.map { it.verdict }.distinct())
            assertTrue("module: ERROR" in r.render() && " PROVEN" !in r.render(), r.render())
        }
    }

    @Test
    fun `a failure maps to the innermost range, the sidecar property, not the method`() {
        val (m, s) = lowerReqShield("coroutine")
        val emitted = emitBound(m, s)
        val line = emitted.lineOf("ensures res != NULL && tok(res).gBacked ==> has(S, k)")
        val col = emitted.colOf(line, "res != NULL")
        val error = SiliconError("Postcondition of tryLock might not hold.", line, col, line, col + 10, null)
        val r = buildReport(m, s, emitted, SiliconOutcome.Failed(listOf(error)), 1.0)
        assertEquals(ModuleVerdict.FAILED, r.verdict)
        val row = r.row("tryLock")
        assertEquals(Verdict.FAILED, row.verdict)
        val f = row.findings.single()
        assertEquals("P1a_entry", (f.source as Source.Sidecar).id)
        assertEquals(64, f.at!!.line)
        assertEquals(listOf(Verdict.PROVEN), r.rows.filter { it.label != "tryLock" }.map { it.verdict }.distinct())
        assertTrue("sidecar P1a_entry keylocallock.common.kv:64:" in r.render(), r.render())
    }

    @Test
    fun `a FAILED obligation shows Silicon's reason when Silicon gives one (Q1)`() {
        val (m, s) = lowerReqShield("coroutine")
        val emitted = emitBound(m, s)
        val p1a = emitted.lineOf("ensures res != NULL && tok(res).gBacked ==> has(S, k)")
        val owner = emitted.lineOf("ensures old(has(S, k)) && old(S.m[k].token) == t ==> res")
        val errors =
            listOf(
                SiliconError("Postcondition of tryLock might not hold.", p1a, 11, p1a, 40, "(incomplete quantifiers)"),
                SiliconError("Postcondition of unLock might not hold.", owner, 11, owner, 40, null),
            )
        val text = buildReport(m, s, emitted, SiliconOutcome.Failed(errors), 1.0).render()
        val lines = text.lines()
        assertTrue(lines.any { "sidecar P1a_entry keylocallock.common.kv:64:" in it && it.endsWith(" (incomplete quantifiers)") }, text)
        assertTrue(lines.any { "sidecar progress_owner keylocallock.common.kv:" in it && !it.endsWith(")") }, text)
    }

    @Test
    fun `the racy reads line lists each read without the free value that holds it (Q5)`() {
        val f = LockFixture("if (sawEntry && e != null) e.expiresAt = now\ne", before = "        val sawEntry = lockMap.containsKey(key)", operations = MINIMAL_GHOST + LockFixture.DEFAULT_OPERATION)
        val m = f.lower(dir)
        val s = parseSidecar(File(dir, "Lock.kv").path, f.sidecar)
        val text = buildReport(kv.lower.bindGhosts(m, s), s, emitBound(m, s), SiliconOutcome.Verified, 1.0).render()
        val (line, col) = f.at("lockMap.containsKey(key)")
        assertTrue("Racy reads (modeled as arbitrary values): lockMap.containsKey(key) $line:$col\n" in text, text)
        assertTrue(" -> " !in text, text)
        // The free value keeps its origin kind in the free value list.
        assertTrue("sawEntry__$line <- sawEntry $line:9 (racy read, unnamed)" in text, text)
    }

    @Test
    fun `a verified run of a module with an unsupported action is INCOMPLETE, not PROVEN`() {
        val ghost = "ghost record tok(Token) {\n  gIssued: Bool set-once\n  gHeld: Bool ends\n}\nwitness Info.token\n"
        val f =
            LockFixture(
                "if (e != null) e.expiresAt = now\ne",
                members = "    fun spin(k: String, n: Long) {\n        lockMap.compute(k) { _, v ->\n            while (n < 0) {\n            }\n            v\n        }\n    }",
                operations = ghost + LockFixture.DEFAULT_OPERATION,
            )
        val m = f.lower(dir)
        val s = parseSidecar(File(dir, "Lock.kv").path, f.sidecar)
        val r = buildReport(kv.lower.bindGhosts(m, s), s, emitBound(m, s), SiliconOutcome.Verified, 1.0)
        assertEquals(ModuleVerdict.INCOMPLETE, r.verdict)
        val spin = r.rows.single { it.label.startsWith("stray action spin#") }
        assertEquals(Verdict.UNSUPPORTED, spin.verdict)
        assertEquals(Verdict.PROVEN, r.row("run").verdict)
        assertTrue("module: INCOMPLETE" in r.render() && "Unsupported: stray action spin#" in r.render(), r.render())
    }
}
