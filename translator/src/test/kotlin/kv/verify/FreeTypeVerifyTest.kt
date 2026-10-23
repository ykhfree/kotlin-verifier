package kv.verify

import kv.emit.MethodKind
import kv.emit.Source
import kv.emit.Workspace
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * With Silicon: SB1 and SB2 (DESIGN-v0.md 6) are FAILED at unLock, not ERROR, now that the sidecar types unLock's
 * token (4.2 restriction 4); and an action that is unsupported by the String rule of 2.13 makes the module
 * INCOMPLETE through the whole pipeline. These tests fail, not skip, without Silicon.
 */
class FreeTypeVerifyTest {
    @TempDir
    lateinit var dir: File

    private lateinit var ws: Workspace

    @BeforeEach
    fun setUp() {
        Silicon.check(Silicon.defaultHome())
        ws = Workspace(dir)
    }

    /** The sidecar IDs of the failing obligations of [label]. */
    private fun Report.failing(label: String) = row(label).findings.map { (it.source as Source.Sidecar).id }.toSet()

    @Test
    fun `SB1 is FAILED at unLock, and only there`() {
        ws.editKotlin("coroutine", "existing.token == token && ", "")
        val r = ws.verify("coroutine")
        val text = r.render()
        assertEquals(ModuleVerdict.FAILED, r.verdict, text)
        assertEquals(Verdict.FAILED, r.row("unLock").verdict, text)
        // The END rule ends the other holder's token: unLock's ends_only_if P3a rejects that first (see M8 table).
        assertEquals(setOf("ends_only_if P3a"), r.failing("unLock"), text)
        assertEquals(listOf(Verdict.PROVEN), r.rows.filter { it.label != "unLock" }.map { it.verdict }.distinct(), text)
    }

    @Test
    fun `SB2 is FAILED at unLock, with progress_owner`() {
        ws.editKotlin("coroutine", "        val released = AtomicBoolean(false)\n", "        val released = AtomicBoolean(false)\n        val sawMatch = lockMap[completeKey]?.token == token\n")
        ws.editKotlin("coroutine", "if (existing.token == token && existing.isHeld", "if (sawMatch && existing.isHeld")
        val r = ws.verify("coroutine")
        val text = r.render()
        assertEquals(ModuleVerdict.FAILED, r.verdict, text)
        assertEquals(setOf("ends_only_if P3a", "progress_owner"), r.failing("unLock"), text)
        assertEquals(listOf(Verdict.PROVEN), r.rows.filter { it.label != "unLock" }.map { it.verdict }.distinct(), text)
    }

    @Test
    fun `an action with a String of unknown abstraction is UNSUPPORTED and the module INCOMPLETE, never ERROR`() {
        // unLock reads its unnamed String parameter `key` into a local: no compute key, template or field types it.
        ws.editKotlin("coroutine", "            if (existing == null) return@compute null\n", "            if (existing == null) return@compute null\n            val seen = key\n")
        val line = ws.kotlinLine("coroutine", "val seen = key")
        val r = ws.verify("coroutine")
        val text = r.render()
        assertEquals(ModuleVerdict.INCOMPLETE, r.verdict, text)
        val unLock = r.row("unLock")
        assertEquals(Verdict.UNSUPPORTED, unLock.verdict, text)
        assertTrue("UNSUPPORTED String value" in unLock.note!! && "KeyLocalLock.kt:$line:13" in unLock.note!!, unLock.note)
        // The probe calls unLock, so it is not emitted either; every emitted method is PROVEN.
        assertEquals(Verdict.UNSUPPORTED, r.rows.single { it.kind == MethodKind.PROBE }.verdict, text)
        assertEquals(listOf(Verdict.PROVEN), r.rows.filter { it.verdict != Verdict.UNSUPPORTED }.map { it.verdict }.distinct(), text)
        assertTrue("W14" !in text && "ERROR" !in text, text)
    }
}
