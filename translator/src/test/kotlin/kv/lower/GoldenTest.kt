package kv.lower

import kv.ReqShield
import kv.frontend.Frontend
import kv.kvir.Module
import kv.kvir.Status
import kv.kvir.checkWellFormed
import kv.kvir.incomplete
import kv.kvir.printModule
import kv.kvir.printOperation
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * M3 acceptance 1 (DESIGN-v0.md section 7): the KVIR of both KeyLocalLock variants x 3 actions equals the reviewed
 * golden files (golden/README.md maps each one to spike/effect_ghost/target.vpr), and every lowered module passes
 * W1-W14. The ledger part of each module is a golden file too (M4).
 */
class GoldenTest {
    private val specDir = File(System.getProperty("kv.specDir"), "reqshield")

    private fun lower(variant: String): Module {
        val outcome = Frontend.resolve(File(specDir, "keylocallock.$variant.kv"), ReqShield.dir, ReqShield.classpath, ReqShield.kotlincArgs)
        assertNull(outcome.compileMessages)
        assertEquals(emptyList(), outcome.errors)
        return outcome.module ?: fail("no module")
    }

    /** Compares with the golden file; on a mismatch, writes the actual text under build/ for review. */
    private fun assertGolden(
        name: String,
        actual: String,
    ) {
        val expected = GoldenTest::class.java.getResource("/golden/$name")?.readText()
        if (expected != actual) File("build/golden-actual/$name").apply { parentFile.mkdirs() }.writeText(actual)
        assertEquals(expected, actual, "KVIR differs from golden/$name; actual text: build/golden-actual/$name")
    }

    private fun assertAction(
        variant: String,
        operation: String,
    ) {
        val m = lower(variant)
        assertEquals(emptyList(), checkWellFormed(m), "W1-W14")
        assertEquals(Status.Supported, m.operations.single { it.name == operation }.action.status)
        assertGolden("$variant/$operation.kvir", printOperation(m, operation))
    }

    @Test
    fun `coroutine tryLock`() = assertAction("coroutine", "tryLock")

    @Test
    fun `coroutine unLock`() = assertAction("coroutine", "unLock")

    @Test
    fun `coroutine sweep`() = assertAction("coroutine", "sweep")

    @Test
    fun `core tryLock`() = assertAction("core", "tryLock")

    @Test
    fun `core unLock`() = assertAction("core", "unLock")

    @Test
    fun `core sweep`() = assertAction("core", "sweep")

    @Test
    fun `both modules are complete, with no stray action, and their ledgers match the golden files`() {
        for (variant in listOf("coroutine", "core")) {
            val m = lower(variant)
            assertTrue(!m.incomplete, variant)
            assertEquals(emptyList(), m.strayActions, variant)
            assertEquals(emptyList(), checkWellFormed(m), variant)
            assertGolden("$variant/ledger.kvir", printModule(m).substringAfter("stray actions: none\n"))
        }
    }
}
