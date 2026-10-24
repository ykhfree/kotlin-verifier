package kv.verify

import kv.emit.Emitted
import kv.emit.Source
import kv.emit.emitBound
import kv.emit.emitViper
import kv.emit.lowerReqShield
import kv.lower.bindGhosts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The property check (pass 2) on fake Silicon results (no Silicon run): which actions get it, how its errors sort
 * each of them, and that it never changes a verdict. The runs with Silicon are in PropertyCheckTest.
 */
class PropertyCheckReportTest {
    private val lowered = lowerReqShield("coroutine")
    private val module = bindGhosts(lowered.first, lowered.second)
    private val sidecar = lowered.second
    private val pass1 = emitBound(lowered.first, lowered.second)

    /**
     * A Silicon postcondition error at the line of [method] in [e] that starts with [ensures], at the column of the
     * asserted expression (after `ensures `), as Silicon reports it.
     */
    private fun error(
        e: Emitted,
        method: String,
        ensures: String,
    ): SiliconError {
        val range = e.methods.single { it.label == method }.range
        val line = (range.line..range.endLine).first { e.text.lines()[it - 1].trim().startsWith(ensures) }
        val col = e.text.lines()[line - 1].indexOf("ensures ") + "ensures ".length + 1
        return SiliconError("Postcondition of $method might not hold.", line, col, line, col + ensures.length, null)
    }

    /**
     * Pass 1: tryLock and sweep fail only at hole conjuncts; unLock fails at a hole conjunct and at P1.
     */
    private val report =
        buildReport(
            module,
            sidecar,
            pass1,
            SiliconOutcome.Failed(
                listOf(
                    error(pass1, "tryLock", "ensures MI_INV_1(S)"),
                    error(pass1, "unLock", "ensures MI_INV_2(S)"),
                    error(pass1, "unLock", "ensures res ==> old(has(S, k))"),
                    error(pass1, "sweep", "ensures MI_INV_1(S)"),
                    error(pass1, "sweep", "ensures MI_INV_4(S)"),
                ),
            ),
            1.0,
        )

    private val pass2 = emitViper(module, sidecar, withoutHole = setOf("tryLock", "sweep"))

    private fun ids(findings: List<Finding>) = findings.map { (it.source as Source.Sidecar).id }

    @Test
    fun `only FAILED actions whose errors are all at hole conjuncts get the property check`() {
        assertEquals(listOf("tryLock", "sweep"), report.holeOnly())
        assertEquals(SubStatus.PROPERTY, report.row("unLock").sub, "a property error in pass 1 is PROPERTY at once")
        assertNull(report.row("tryLock").sub, "a hole-only action waits for the property check")
        assertNull(report.row("sweep").sub)
        assertEquals(listOf(null), report.rows.filter { it.label !in setOf("tryLock", "unLock", "sweep") }.map { it.sub }.distinct(), "init, lemma and probe have no sub-status")
    }

    @Test
    fun `each hole-only action is sorted by its own pass-2 errors, and no verdict changes`() {
        val errors =
            listOf(
                error(pass2, "tryLock", "ensures old(has(S, k)) && old(S.m[k].isHeld)"),
                // An error of an action that had no property check is not a pass-2 finding of any row.
                error(pass2, "unLock", "ensures old(has(S, k)) && old(S.m[k].token) == t ==> res"),
            )
        val r = report.withPropertyCheck(pass2, SiliconOutcome.Failed(errors), 2.5)
        val text = r.render()
        assertEquals(ModuleVerdict.FAILED, r.verdict, text)
        assertEquals(report.rows.map { it.label to it.verdict }, r.rows.map { it.label to it.verdict }, text)
        assertEquals(SubStatus.PROPERTY to listOf("progress_live"), r.row("tryLock").let { it.sub to ids(it.pass2) }, text)
        assertEquals(SubStatus.INVARIANT_ONLY to emptyList(), r.row("sweep").let { it.sub to ids(it.pass2) }, text)
        assertEquals(SubStatus.PROPERTY to emptyList(), r.row("unLock").let { it.sub to ids(it.pass2) }, text)
        assertEquals(listOf("MI_INV_1"), ids(r.row("tryLock").findings), "pass-1 findings stay")
        assertEquals(PropertyCheck(listOf("tryLock", "sweep"), 2.5, null), r.propertyCheck)
        val lines = text.lines()
        assertTrue(lines.any { it.startsWith("  action tryLock") && it.endsWith("   FAILED (property)") }, text)
        assertTrue(lines.any { it.startsWith("  action sweep") && it.endsWith("   FAILED (invariant only)") }, text)
        assertTrue(lines.any { it.startsWith("        pass 2 (property check): Postcondition of tryLock might not hold: sidecar progress_live keylocallock.common.kv:") }, text)
        assertTrue("Property check (pass 2): tryLock, sweep without the hole ensures MI_INV_i (Silicon 2.50 s)" in lines, text)
    }

    @Test
    fun `a clean property check makes every hole-only action INVARIANT_ONLY, and the module stays FAILED`() {
        val r = report.withPropertyCheck(pass2, SiliconOutcome.Verified, 2.0)
        assertEquals(ModuleVerdict.FAILED, r.verdict)
        assertEquals(listOf(SubStatus.INVARIANT_ONLY, SubStatus.PROPERTY, SubStatus.INVARIANT_ONLY), listOf("tryLock", "unLock", "sweep").map { r.row(it).sub })
        assertEquals(listOf(Verdict.FAILED), listOf("tryLock", "unLock", "sweep").map { r.row(it).verdict }.distinct())
        assertTrue("module: FAILED" in r.render().lines().first(), r.render())
    }

    @Test
    fun `a property check that did not finish normally is CHECK_ERROR for every hole-only action, never INVARIANT_ONLY`() {
        val outside = SiliconError("Assert might fail.", 1, 1, 1, 5, null)
        val outcomes =
            listOf(
                SiliconOutcome.Abnormal("timeout: killed at the wall clock limit of 300 s", emptyList()),
                // An abnormal run with a readable error in tryLock: still no answer.
                SiliconOutcome.Abnormal("timeout: a prover query was canceled (assert/check timeout 20000 ms)", listOf(error(pass2, "tryLock", "ensures old(has(S, k)) && old(S.m[k].isHeld)"))),
                SiliconOutcome.Failed(listOf(outside)),
            )
        for (outcome in outcomes) {
            val r = report.withPropertyCheck(pass2, outcome, 3.0)
            val text = r.render()
            assertEquals(ModuleVerdict.FAILED, r.verdict, text)
            assertEquals(listOf(SubStatus.INVARIANT_ONLY_CHECK_ERROR, SubStatus.PROPERTY, SubStatus.INVARIANT_ONLY_CHECK_ERROR), listOf("tryLock", "unLock", "sweep").map { r.row(it).sub }, text)
            val reason = (outcome as? SiliconOutcome.Abnormal)?.reason ?: "Silicon reported errors outside every method"
            assertEquals(reason, r.propertyCheck!!.error, text)
            assertTrue("no sidecar property fails in one step" !in text, text)
            assertTrue(text.lines().count { it == "        property check ERROR: $reason; it is not known whether a sidecar property fails" } == 2, text)
            assertTrue("Property check (pass 2): tryLock, sweep without the hole ensures MI_INV_i: ERROR: $reason; this run proves nothing" in text.lines(), text)
        }
    }
}
