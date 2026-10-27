package kv.client

import kv.verify.ModuleVerdict
import kv.verify.render
import kv.verify.verifyClient
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PROTOCOL2.md 5, predictions 1 and 2 (DESIGN-E 6.10 E4): `kv verify` on the core ReqShield is FAILED at exactly
 * RET_hit, RET_recheck and RET_supplier_error, every other property PROVEN (the full failing set); on the fixed copy it
 * is PROVEN. The report lists the assumptions A1-A5 with their state.
 */
class ClientVerifyTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `the core is FAILED at exactly RET_hit, RET_recheck and RET_supplier_error`() {
        val o = ClientGoldenTest.outcome
        val report = verifyClient(o.module!!, o.sidecar!!, File(dir, "core.vpr"))
        val text = report.render()
        assertEquals(ModuleVerdict.FAILED, report.verdict, text)
        val failed = report.properties!!.filter { it.failed }.map { it.id }
        assertEquals(listOf("RET_hit", "RET_recheck", "RET_supplier_error"), failed, text)
        // Every tool fact, the hole, both guards and the other ensures are PROVEN; the run measured the full set.
        assertEquals(18, report.properties!!.size, text)
        assertEquals(15, report.isolationRuns, text)
        assertEquals(listOf("entry getAndSetReqShieldData FAILED", "task task_69 PROVEN", "task task_125 PROVEN", "loop ReqShield.handleLockFailure@181 PROVEN"), report.rows.map { "${it.kind} ${it.label} ${it.verdict}" })
        for (a in listOf("A1 errors-are-fatal: on", "A2 lock-calls-return: off", "A3 accepted-tasks-run: on", "A4 throwing-release-ends-obligation: on", "A5 throwing-acquire-takes-nothing: on")) {
            assertTrue("  $a" in text, text)
        }
    }

    @Test
    fun `the fixed copy is PROVEN`() {
        val o = Protocol2.resolveFixed(dir)
        val report = verifyClient(o.module!!, o.sidecar!!, File(dir, "fixed.vpr"))
        assertEquals(ModuleVerdict.PROVEN, report.verdict, report.render())
        assertTrue(report.properties!!.none { it.failed })
        assertEquals(0, report.isolationRuns)
    }
}
