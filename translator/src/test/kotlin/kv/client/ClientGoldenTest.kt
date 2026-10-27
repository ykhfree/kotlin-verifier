package kv.client

import kv.ReqShield
import kv.frontend.Frontend
import kv.frontend.ResolveOutcome
import kv.kvir.Module
import kv.kvir.Status
import kv.kvir.checkWellFormed
import kv.kvir.incomplete
import kv.kvir.printModule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * DESIGN-E 6.10 E2 acceptance: the KVIR of req-shield core ReqShield (the signed sidecar, req-shield 2e792d2) equals the
 * reviewed golden file golden/client/reqshield.core.kvir; golden/README.md maps it to spike/protocol2/target.vpr.
 */
class ClientGoldenTest {
    companion object {
        /** The resolved core sidecar and its module; one compile serves every client test that reads it. */
        val outcome: ResolveOutcome by lazy {
            val o = Frontend.resolve(File(System.getProperty("kv.specDir"), "reqshield/reqshield.core.kv"), ReqShield.dir, ReqShield.classpath, ReqShield.kotlincArgs)
            assertNull(o.compileMessages)
            assertEquals(emptyList(), o.errors)
            o
        }

        /** The lowered core module. */
        val core: Module get() = outcome.module ?: fail("no module")
    }

    @Test
    fun `the core ReqShield lowers completely and well formed, with one entry and the tasks at 69 and 125`() {
        assertFalse(core.incomplete)
        assertEquals(emptyList(), checkWellFormed(core))
        assertEquals(listOf("getAndSet"), core.entries.map { it.name })
        assertEquals(listOf("task_69" to "69:51", "task_125" to "125:47"), core.tasks.map { it.id to "${it.pos.line}:${it.pos.col}" })
        assertEquals(listOf(Status.Supported, Status.Supported, Status.Supported), core.entries.map { it.status } + core.tasks.map { it.status })
    }

    @Test
    fun `the core ReqShield KVIR matches the golden file`() {
        val actual = printModule(core)
        val name = "client/reqshield.core.kvir"
        val expected = ClientGoldenTest::class.java.getResource("/golden/$name")?.readText()
        if (expected != actual) File("build/golden-actual/$name").apply { parentFile.mkdirs() }.writeText(actual)
        assertEquals(expected, actual, "KVIR differs from golden/$name; actual text: build/golden-actual/$name")
    }
}
