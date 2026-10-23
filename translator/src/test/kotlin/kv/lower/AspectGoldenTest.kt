package kv.lower

import kv.Aspects
import kv.ReqShield
import kv.frontend.Frontend
import kv.kvir.ActionKind
import kv.kvir.DispositionKind
import kv.kvir.Module
import kv.kvir.ModuleKind
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
 * DESIGN-B.md 8: B2 acceptance 1, the KVIR of the three Spring aspect modules equals the reviewed golden files
 * (golden/README.md maps each line to the Kotlin source and to spike/aspect/target.vpr), and B0 acceptance 2, in each
 * module only the reqShieldMap call is an action and the expressionMap and keyGeneratorMap calls are not modeled.
 */
class AspectGoldenTest {
    private val specDir = File(System.getProperty("kv.specDir"), "reqshield")

    private fun lower(module: String): Module {
        val outcome = Frontend.resolve(File(specDir, "aspect.$module.kv"), ReqShield.dir, Aspects.classpath, ReqShield.kotlincArgs)
        assertNull(outcome.compileMessages)
        assertEquals(emptyList(), outcome.errors)
        return outcome.module ?: fail("no module")
    }

    /** Compares with the golden file; on a mismatch, writes the actual text under build/ for review. */
    private fun assertGolden(
        name: String,
        actual: String,
    ) {
        val expected = AspectGoldenTest::class.java.getResource("/golden/aspect/$name")?.readText()
        if (expected != actual) File("build/golden-actual/aspect/$name").apply { parentFile.mkdirs() }.writeText(actual)
        assertEquals(expected, actual, "KVIR differs from golden/aspect/$name; actual text: build/golden-actual/aspect/$name")
    }

    private fun assertModule(module: String) {
        val m = lower(module)
        assertEquals(emptyList(), checkWellFormed(m), "W1-W16")
        assertEquals(ModuleKind.INSTANCE, m.kind)
        assertTrue(!m.incomplete, module)
        val op = m.operations.single()
        assertEquals(Status.Supported, op.action.status)
        assertEquals(ActionKind.COMPUTE_IF_ABSENT, op.action.kind)
        assertEquals(emptyList(), m.strayActions)
        assertGolden("$module/getOrCreate.kvir", printOperation(m, "getOrCreate"))
        assertGolden("$module/ledger.kvir", printModule(m).substringAfter("stray actions: none\n"))
    }

    @Test
    fun `core-spring getOrCreate and ledger`() = assertModule("core-spring")

    @Test
    fun `webflux getOrCreate and ledger`() = assertModule("webflux")

    @Test
    fun `coroutine getOrCreate and ledger`() = assertModule("coroutine")

    /** B0 acceptance 2 (DESIGN-B.md 5.3 rule 2): the other two computeIfAbsent calls are not modeled, at the 1.1 positions. */
    @Test
    fun `only the reqShieldMap call is an action, the expressionMap and keyGeneratorMap calls are not modeled`() {
        val others = mapOf("core-spring" to listOf(237 to 9, 246 to 16), "webflux" to listOf(249 to 9, 258 to 16), "coroutine" to listOf(196 to 34, 282 to 16))
        val action = mapOf("core-spring" to (112 to 9), "webflux" to (125 to 9), "coroutine" to (232 to 9))
        for (module in Aspects.roots.keys) {
            val m = lower(module)
            val a = m.operations.single().action
            assertEquals(action.getValue(module), a.pos.line to a.pos.col, module)
            assertEquals("reqShieldMap", a.map.kotlin)
            for ((line, col) in others.getValue(module)) {
                val d = m.ledger.filter { it.pos.line == line && it.pos.col == col && it.pos.file.endsWith("ReqShieldAspect.kt") }
                assertTrue(d.isNotEmpty() && d.all { it.kind == DispositionKind.NOT_MODELED && "computeIfAbsent on" in it.what && "which is not a sidecar state map" in it.what }, "$module $line:$col $d")
            }
        }
    }
}
