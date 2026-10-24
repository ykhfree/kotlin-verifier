package kv.verify

import kv.emit.Source
import kv.emit.Workspace
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * With Silicon: the property check (pass 2) of an action that fails only at hole conjuncts, on mechanical mutants of
 * MUTATION.md (the same edits as `kv mutation-sites`). These tests fail, not skip, without Silicon.
 */
class PropertyCheckTest {
    @TempDir
    lateinit var dir: File

    private lateinit var ws: Workspace

    @BeforeEach
    fun setUp() {
        Silicon.check(Silicon.defaultHome())
        ws = Workspace(dir)
    }

    /** The sidecar or generated IDs of [findings]. */
    private fun ids(findings: List<Finding>) =
        findings.map {
            when (val s = it.source) {
                is Source.Sidecar -> s.id
                is Source.Generated -> s.what
                else -> "$s"
            }
        }.toSet()

    private fun Report.rowLine(label: String) = render().lines().single { it.startsWith("  action $label ") }

    private fun pass2File(variant: String) = File(dir, "$variant.pass2.vpr")

    /** The `ensures MI_INV_i(S)` lines of [method] in the Viper [text]. */
    private fun holeEnsures(
        text: String,
        method: String,
    ): List<String> {
        val lines = text.lines()
        val from = lines.indexOfFirst { it.startsWith("method $method(") }
        val to = lines.drop(from).indexOfFirst { it == "{" } + from
        return lines.subList(from, to).filter { it.trim().startsWith("ensures MI_INV_") }
    }

    /**
     * Coroutine RET-11: the sweep keeps an expired entry after it released it, so the entry is not held. That breaks
     * INV-E (MI_INV_1), and no sidecar property of sweep.
     */
    private fun coroutineRet11() = ws.editKotlin("coroutine", "null // Atomic removal", "lockInfo // Atomic removal")

    @Test
    fun `a hole-only failure whose property check is clean is FAILED (invariant only), and the module stays FAILED`() {
        coroutineRet11()
        val r = ws.verify("coroutine")
        val text = r.render()
        assertEquals(ModuleVerdict.FAILED, r.verdict, text)
        val sweep = r.row("sweep")
        assertEquals(Verdict.FAILED to SubStatus.INVARIANT_ONLY, sweep.verdict to sweep.sub, text)
        assertEquals(setOf("MI_INV_1"), ids(sweep.findings), text)
        assertEquals(emptyList(), sweep.pass2, text)
        assertEquals(PropertyCheck(listOf("sweep"), r.propertyCheck!!.seconds, null), r.propertyCheck, text)
        assertEquals(listOf(Verdict.PROVEN), r.rows.filter { it.label != "sweep" }.map { it.verdict }.distinct(), text)
        assertTrue(r.rowLine("sweep").endsWith("   FAILED (invariant only)"), text)
        for (sentence in listOf(
            "        meaning: this action does not preserve the inductive invariant (the hole MI_INV).",
            "        property check: no sidecar property fails in one step from a state that satisfies the invariant.",
            "        A property may still break in a later step: the code may be wrong, or the hole may be too strong for this code.",
            "        Next: re-derive the hole for this code (kv hole). If no inductive invariant proves the properties, the code is wrong.",
        )) {
            assertTrue(text.lines().contains(sentence), "$sentence\n$text")
        }
        assertTrue(text.lines().any { it.startsWith("Property check (pass 2): sweep without the hole ensures MI_INV_i (Silicon ") }, text)
        // The pass-2 program lost the hole ensures of sweep only.
        val pass2 = pass2File("coroutine").readText()
        assertEquals(emptyList(), holeEnsures(pass2, "sweep"))
        assertEquals(holeEnsures(File(dir, "coroutine.vpr").readText(), "tryLock"), holeEnsures(pass2, "tryLock"))
        assertTrue(holeEnsures(pass2, "tryLock").isNotEmpty())
    }

    @Test
    fun `a hole-only failure whose property check finds a property is FAILED (property), with that property listed`() {
        // Core RET-06: unLock keeps the released entry instead of removing it; P1 hides behind MI_INV_1 in pass 1.
        ws.editKotlin("core", "runs out.\n                return@compute null", "runs out.\n                return@compute existing")
        val r = ws.verify("core")
        val text = r.render()
        assertEquals(ModuleVerdict.FAILED, r.verdict, text)
        val unLock = r.row("unLock")
        assertEquals(Verdict.FAILED to SubStatus.PROPERTY, unLock.verdict to unLock.sub, text)
        assertEquals(setOf("MI_INV_1"), ids(unLock.findings), "pass 1 is unchanged:\n$text")
        assertTrue("P1" in ids(unLock.pass2), text)
        assertTrue(ids(unLock.pass2).none { it.startsWith("MI_INV_") }, text)
        assertEquals(listOf("unLock"), r.propertyCheck!!.actions, text)
        assertNull(r.propertyCheck!!.error, text)
        assertTrue(r.rowLine("unLock").endsWith("   FAILED (property)"), text)
        assertTrue(text.lines().any { it.startsWith("        pass 2 (property check): Postcondition of unLock might not hold: sidecar P1 keylocallock.common.kv:") }, text)
        assertTrue("invariant only" !in text, text)
    }

    @Test
    fun `an action with a property error in pass 1 is FAILED (property) and gets no property check, even with a hole error`() {
        // Coroutine NEG-02: the expiry condition is negated; pass 1 fails at a hole conjunct and at a property.
        ws.editKotlin("coroutine", "if (now > existing.expiresAt && existing.isHeld.compareAndSet(true, false)) {", "if (!(now > existing.expiresAt && existing.isHeld.compareAndSet(true, false))) {")
        val r = ws.verify("coroutine")
        val text = r.render()
        assertEquals(ModuleVerdict.FAILED, r.verdict, text)
        val tryLock = r.row("tryLock")
        assertEquals(Verdict.FAILED to SubStatus.PROPERTY, tryLock.verdict to tryLock.sub, text)
        val pass1 = ids(tryLock.findings)
        assertTrue(pass1.any { it.startsWith("MI_INV_") } && pass1.any { !it.startsWith("MI_INV_") }, "a hole and a property error:\n$text")
        assertEquals(emptyList(), tryLock.pass2, text)
        assertNull(r.propertyCheck, text)
        assertTrue(!pass2File("coroutine").exists(), "no pass-2 program")
        assertTrue(r.rowLine("tryLock").endsWith("   FAILED (property)"), text)
        assertTrue("Property check (pass 2)" !in text && "pass 2 (property check)" !in text, text)
    }

    @Test
    fun `a property check that times out is FAILED (invariant only, property check ERROR), never PROVEN`() {
        coroutineRet11()
        val r = ws.lower("coroutine").let { (m, s) -> verify(m, s, File(dir, "coroutine.vpr"), Silicon.defaultHome(), SiliconBudget(), SiliconBudget(wallSeconds = 1)) }
        val text = r.render()
        assertEquals(ModuleVerdict.FAILED, r.verdict, text)
        val sweep = r.row("sweep")
        assertEquals(Verdict.FAILED to SubStatus.INVARIANT_ONLY_CHECK_ERROR, sweep.verdict to sweep.sub, text)
        assertEquals(setOf("MI_INV_1"), ids(sweep.findings), text)
        assertEquals("timeout: killed at the wall clock limit of 1 s", r.propertyCheck!!.error, text)
        assertTrue(r.rowLine("sweep").endsWith("   FAILED (invariant only; property check ERROR)"), text)
        assertTrue("        property check ERROR: timeout: killed at the wall clock limit of 1 s; it is not known whether a sidecar property fails" in text.lines(), text)
        assertTrue("Property check (pass 2): sweep without the hole ensures MI_INV_i: ERROR: timeout: killed at the wall clock limit of 1 s; this run proves nothing" in text.lines(), text)
        // The INVARIANT_ONLY reading needs a clean property check: it is not printed.
        assertTrue("no sidecar property fails in one step" !in text, text)
        assertTrue("module: FAILED" in text.lines().first(), text)
    }
}
