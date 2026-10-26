package kv.spec

import kv.emit.ContractOnly
import kv.emit.Emitted
import kv.emit.emitViper
import kv.emit.lowerReqShield
import kv.emit.specDir
import kv.lower.bindGhosts
import kv.sidecar.SidecarLoader
import kv.sidecar.parseCells
import kv.sidecar.pilotCells
import kv.verify.SiliconError
import kv.verify.SiliconOutcome
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How `kv spec check` reads a Silicon outcome, without Silicon: the cell verdicts of the contract-only model
 * (DESIGN-C.md 3.2) and the clause verdicts of an isolation run (4.1), from errors placed at chosen lines.
 */
class SpecCheckUnitTest {
    /** Silicon's message for a `refute false` at an unreachable point (v.26.08). */
    private val REFUTE_HELD = "Refute holds in all cases or could not be reached (e.g. see Silicon `numberOfErrorsToReport`). Assertion false definitely holds."

    private val sidecar by lazy { SidecarLoader.load(File(specDir, "keylocallock.coroutine.kv")) }
    private val cells by lazy { parseCells(pilotCells.path, pilotCells.readText()) }

    /** The contract-only model from the sidecar alone, mode b: no compile needed. */
    private val model by lazy { emitViper(sidecarModule(sidecar).module, sidecar, contract = ContractOnly(cells, holeTrue = true, fromCode = false)) }

    /** A verification error at the first column of [fragment] on the first line of method [method] that holds it. */
    private fun errorAt(
        e: Emitted,
        method: String,
        fragment: String,
        message: String = "Assert might fail. Assertion might not hold.",
    ): SiliconError {
        val m = e.methods.single { it.name == method }
        val lines = e.text.lines()
        val n = (m.range.line - 1 until m.range.endLine).first { fragment in lines[it] }
        return SiliconError(message, n + 1, lines[n].indexOf(fragment) + 1, n + 1, lines[n].indexOf(fragment) + fragment.length, null)
    }

    private fun classify(vararg errors: SiliconError) = classifyCells(Mode.B, cells, model, SiliconOutcome.Failed(errors.toList()), 1.0)

    @Test
    fun `a cell is DETERMINED by each outcome that verifies, OPEN if none does, INFEASIBLE if its end is unreachable`() {
        val d =
            classify(
                errorAt(model, "cell_C05_out_null", "assert r == NULL"),
                errorAt(model, "cell_C16_out_stays", "assert carried"),
                errorAt(model, "cell_C16_out_dropped", "assert !has"),
                errorAt(model, "cell_C06_reach", "refute false", REFUTE_HELD),
                errorAt(model, "cell_C01_out_null", "assert r == NULL"),
                errorAt(model, "cell_C01_out_unbacked", "assert r != NULL && !tok"),
            )
        assertNull(d.error)
        assertEquals("DETERMINED(token)", d.cells["C05"])
        assertEquals("OPEN", d.cells["C16"])
        assertEquals("INFEASIBLE", d.cells["C06"])
        assertEquals("DETERMINED(backed)", d.cells["C01"])
        // A cell without any error: every outcome verifies (they overlap, or the end is unreachable and the reach
        // method failed, which this outcome does not say). The verdict names them all.
        assertEquals("DETERMINED(true|false)", d.cells["C08"])
        assertEquals(19, d.cells.size)
    }

    @Test
    fun `a refute that held is a failure only where refutes are probes`() {
        val out = "Silicon found 1 error in 1.00s:\n  [0] $REFUTE_HELD (m.vpr@3.10--3.15)\n"
        val run = kv.verify.SiliconRun(1, out, 1.0)
        assertTrue(kv.verify.classify(run, kv.verify.SiliconBudget()) is SiliconOutcome.Abnormal)
        assertEquals(listOf(3), (kv.verify.classify(run, kv.verify.SiliconBudget(), refutes = true) as SiliconOutcome.Failed).errors.map { it.line })
    }

    @Test
    fun `a failing step makes the cell ERROR, an error outside the cells or an abnormal run makes the mode ERROR`() {
        val step = errorAt(model, "cell_C07_out_true", "b := unLock", "The precondition of method unLock might not hold.")
        assertEquals("ERROR (a step fails: cell C07 call unLock)", classify(step).cells["C07"])
        val outside = errorAt(model, "tryLock", "ensures", "Postcondition of tryLock might not be well-formed.")
        val d = classify(outside, errorAt(model, "cell_C05_out_null", "assert r == NULL"))
        assertEquals(emptyMap(), d.cells)
        assertTrue(d.error!!.startsWith("Silicon reported errors outside the cells"), d.error)
        assertEquals(1, d.findings.size)
        val abnormal = classifyCells(Mode.A, cells, model, SiliconOutcome.Abnormal("timeout: killed", emptyList()), null)
        assertEquals("timeout: killed" to emptyMap<String, String>(), abnormal.error to abnormal.cells)
    }

    @Test
    fun `isolation - an error in the kept clause is FAILED, another error of the action makes its clause UNKNOWN, a hole error is named`() {
        val (m, s) = lowerReqShield("coroutine")
        val run1 = emitViper(bindGhosts(m, s), s, onlyClause = 1)
        // Run 1 keeps tryLock's unbacked_only_if P3b, unLock's P1 and sweep's no_entry_created.
        val p1 = errorAt(run1, "unLock", "ensures res ==> old(has(S, k))", "Postcondition of unLock might not hold.")
        val hole = errorAt(run1, "tryLock", "ensures MI_INV_4(S)", "Postcondition of tryLock might not hold.")
        val r = isolationRun(run1, SiliconOutcome.Failed(listOf(p1, hole)))
        assertEquals(listOf("tryLock unbacked_only_if P3b UNKNOWN", "unLock ensures P1 FAILED", "sweep ensures no_entry_created PROVEN"), r.clauses.map { "${it.action} ${it.clause} ${it.verdict}" })
        assertTrue(r.clauses[0].detail!!.startsWith("another error of the action in this run may hide it: tryLock: Postcondition of tryLock might not hold: sidecar MI_INV_4"), r.clauses[0].detail)
        assertEquals(1, r.hole.size)
        assertEquals(listOf("PROVEN", "PROVEN", "PROVEN"), isolationRun(run1, SiliconOutcome.Verified).clauses.map { it.verdict })
        val abnormal = isolationRun(run1, SiliconOutcome.Abnormal("exit code 2", emptyList()))
        assertEquals(listOf("ERROR", "ERROR", "ERROR") to "exit code 2", abnormal.clauses.map { it.verdict } to abnormal.error)
    }
}
