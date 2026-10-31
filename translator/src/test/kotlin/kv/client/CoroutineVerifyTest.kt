package kv.client

import kv.ReqShield
import kv.emit.spikeDir
import kv.frontend.Frontend
import kv.frontend.ResolveOutcome
import kv.verify.ClientReport
import kv.verify.ModuleVerdict
import kv.verify.render
import kv.verify.verifyClient
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DESIGN-E5 8 predictions P1 and P2 (DESIGN-E 13.2): `kv verify` of the coroutine ReqShield with the signed sidecar
 * specs/reqshield/reqshield.coroutine.kv is FAILED at exactly RET_hit, RET_recheck, RET_supplier_error and RET_cancel,
 * every other property PROVEN (the full failing set); the copy with spike/protocol2c/fixed-coroutine.patch is PROVEN.
 * The fixed copy is also the check of DESIGN-E5 6.2 S3 deliberate break (1): a release inside
 * withContext(NonCancellable) emitted with cx = true may keep its obligation (cancelKeeps), so LD_noleak fails.
 */
class CoroutineVerifyTest {
    companion object {
        private val sidecar = File(System.getProperty("kv.specDir"), "reqshield/reqshield.coroutine.kv")

        private fun resolve(root: File): ResolveOutcome {
            val o = Frontend.resolve(sidecar, root, ReqShield.classpath, ReqShield.kotlincArgs)
            assertNull(o.compileMessages)
            assertEquals(emptyList(), o.errors)
            return o
        }

        /** The signed coroutine sidecar on the pinned checkout; one compile serves every test that reads it. */
        val outcome: ResolveOutcome by lazy { resolve(ReqShield.dir) }

        /** The copy with fixed-coroutine.patch, resolved with the same signed sidecar (the fix changes no reviewed text). */
        fun resolveFixed(dir: File): ResolveOutcome =
            resolve(Protocol2.fixedCopy(dir, "core-kotlin-coroutine", File(spikeDir, "protocol2c/fixed-coroutine.patch")))
    }

    @TempDir
    lateinit var dir: File

    private fun rows(report: ClientReport) =
        report.rows.map { row -> "${row.kind} ${row.label} ${row.kotlin!!.let { "${File(it.file).name}:${it.line}:${it.col}" }} ${row.verdict}" }

    @Test
    fun `the coroutine original is FAILED at exactly RET_hit, RET_recheck, RET_supplier_error and RET_cancel (P1)`() {
        val o = outcome
        val report = verifyClient(o.module!!, o.sidecar!!, File(dir, "original.vpr"))
        val text = report.render()
        assertEquals(ModuleVerdict.FAILED, report.verdict, text)
        assertEquals(listOf("RET_hit", "RET_recheck", "RET_supplier_error", "RET_cancel"), report.properties!!.filter { it.failed }.map { it.id }, text)
        // The 5 tool facts, the hole, both guards and the 11 ensures: the full failing set is measured, one run each.
        assertEquals(19, report.properties.size, text)
        assertEquals(16, report.isolationRuns, text)
        assertEquals(
            listOf(
                "entry getAndSetReqShieldData ReqShield.kt:43:5 FAILED",
                "task task_75 ReqShield.kt:75:69 PROVEN",
                "task task_138 ReqShield.kt:138:69 PROVEN",
                "loop ReqShield.handleLockFailure ReqShield.kt:165:9 PROVEN",
            ),
            rows(report),
        )
        for (a in listOf("A1 errors-are-fatal: on", "A2 lock-calls-return: off", "A3 accepted-tasks-run: on", "A4 throwing-release-ends-obligation: on", "A5 throwing-acquire-takes-nothing: on", "A6 dispatch-does-not-throw: on")) {
            assertTrue("  $a" in text, text)
        }
    }

    @Test
    fun `the fixed coroutine copy is PROVEN (P2)`() {
        val o = resolveFixed(dir)
        val report = verifyClient(o.module!!, o.sidecar!!, File(dir, "fixed.vpr"))
        assertEquals(ModuleVerdict.PROVEN, report.verdict, report.render())
        assertTrue(report.properties!!.none { it.failed })
        assertEquals(0, report.isolationRuns)
        // The patch moves the tasks and the loop; the signed sidecar, read as is, still names the loop by its function.
        assertEquals(
            listOf(
                "entry getAndSetReqShieldData ReqShield.kt:43:5 PROVEN",
                "task task_88 ReqShield.kt:88:69 PROVEN",
                "task task_149 ReqShield.kt:149:69 PROVEN",
                "loop ReqShield.handleLockFailure ReqShield.kt:176:9 PROVEN",
            ),
            rows(report),
        )
    }
}
