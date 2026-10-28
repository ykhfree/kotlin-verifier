package kv.verify

import kv.emit.MethodKind
import kv.emit.Workspace
import kv.emit.lineOf
import kv.sidecar.SidecarException
import kv.sidecar.SidecarLoader
import kv.sidecar.checkSidecar
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * C1 acceptance (DESIGN-C.md 8, adapted to the current sidecar): named probes each become a Viper method, a probe
 * calls an operation without a result, and the decision probes of D1 and D4 (3.5) on the coroutine KeyLocalLock and
 * on the decision mutants of pilot-c/tools/mutset.py. Temp copies of the specs and sources only.
 */
class DecisionProbeTest {
    @TempDir
    lateinit var dir: File

    private val common = "keylocallock.common.kv"

    /**
     * The decision probes, signed and sourced as DESIGN-C.md 3.5 shows. The notes are test fixtures, not the user's
     * signature; [Workspace.rereview] stamps their fingerprints.
     */
    private val decisions =
        """
        |
        |// D1 (DESIGN-C.md 3.6): at exactly the lease end the lock is still held.
        |probe expiry_boundary(k: Key, a: Int, d: Int) requires d > 0 {
        |  t1 := tryLock(k, now = a, timeout = d, rej = false, limit = 0)
        |  t2 := tryLock(k, now = a + d, timeout = d, rej = false, limit = 0)
        |  assert t2 == NULL
        |}
        |  source "README.md#local-lock-map-size/2" quote "once `lockTimeoutMillis` has passed" sha256 "a50a09d95953837f"
        |  reviewed "test fixture, not a signature: strict, the lease end is still held"
        |// D4: one ms after the lease end, tryLock takes the lock over at once and gets a backed token.
        |probe expired_takeover_at_once(k: Key, a: Int, d: Int) requires d > 0 {
        |  t1 := tryLock(k, now = a, timeout = d, rej = false, limit = 0)
        |  t2 := tryLock(k, now = a + d + 1, timeout = d, rej = false, limit = 0)
        |  assert t2 != NULL && tok(t2).gBacked
        |}
        |  source "KeyLock.kt(core)#KeyLock" quote "a holder whose lock already expired and was taken over by someone else" sha256 "029ff8a4f97809d0"
        |  reviewed "test fixture, not a signature: an expired lock is taken over by the next tryLock"
        |
        """.trimMargin()

    /** A workspace whose common spec has [probes] at its end, with the notes stamped. */
    private fun workspace(
        name: String,
        probes: String,
    ): Workspace {
        val ws = Workspace(File(dir, name).apply { mkdirs() })
        File(ws.specs, common).appendText(probes)
        ws.rereview(common)
        assertEquals(emptyList(), checkSidecar(SidecarLoader.load(File(ws.specs, "keylocallock.coroutine.kv"))))
        return ws
    }

    private val probeRows = listOf("probe", "probe expiry_boundary", "probe expired_takeover_at_once")

    @Test
    fun `the decision probes are PROVEN on the original coroutine KeyLocalLock`() {
        Silicon.check(Silicon.defaultHome())
        val r = workspace("original", decisions).verify("coroutine")
        val text = r.render()
        assertEquals(ModuleVerdict.PROVEN, r.verdict, text)
        assertEquals(probeRows, r.rows.filter { it.kind == MethodKind.PROBE }.map { it.label }, text)
        assertTrue("probe PROVEN · probe expiry_boundary PROVEN · probe expired_takeover_at_once PROVEN" in text, text)
    }

    /**
     * A probe sees the operations only through their contracts, so on a mutant the probe rows stay PROVEN and the
     * module is FAILED at the action whose contract the mutant breaks. The decision lives in that contract too.
     */
    @Test
    fun `D1-both and D4-no-takeover are FAILED with the decision probes, at the actions that break the decided contract`() {
        Silicon.check(Silicon.defaultHome())
        val d1 = workspace("d1", decisions)
        d1.editKotlin("coroutine", "if (now > existing.expiresAt && existing.isHeld", "if (now >= existing.expiresAt && existing.isHeld")
        d1.editKotlin("coroutine", "if (now > lockInfo.expiresAt) {", "if (now >= lockInfo.expiresAt) {")
        val r1 = d1.verify("coroutine")
        val t1 = r1.render()
        assertEquals(ModuleVerdict.FAILED, r1.verdict, t1)
        assertEquals(Verdict.FAILED to Verdict.FAILED, r1.row("tryLock").verdict to r1.row("sweep").verdict, t1)
        probeRows.forEach { assertEquals(Verdict.PROVEN, r1.row(it).verdict, "$it\n$t1") }

        val d4 = workspace("d4", decisions)
        d4.editKotlin("coroutine", "if (now > existing.expiresAt && existing.isHeld.compareAndSet(true, false)) {", "if (false) {")
        val r4 = d4.verify("coroutine")
        val t4 = r4.render()
        assertEquals(ModuleVerdict.FAILED, r4.verdict, t4)
        assertEquals(Verdict.FAILED, r4.row("tryLock").verdict, t4)
        assertTrue(r4.row("tryLock").findings.any { it.describe().contains("expired_takeover") }, t4)
        probeRows.forEach { assertEquals(Verdict.PROVEN, r4.row(it).verdict, "$it\n$t4") }
    }

    /** The decision rides on clauses: without the ones that fix the cell, its probe fails on the reference code. */
    @Test
    fun `a decision probe is FAILED when the sidecar loses the clauses that decide its cell`() {
        Silicon.check(Silicon.defaultHome())
        val ws = workspace("weak", decisions)
        // tryLock's strict boundary is in two clauses: progress_live keeps a live entry at now == expiresAt, and P3a
        // ends a token only after its lease. Drop the first and widen the second (re-signed, as a person would).
        val spec = File(ws.specs, common).readText()
        ws.editSpec(common, Regex("(?m)^  ensures progress_live:.*\n(    source .*\n)*  reviewed .*\n").find(spec)!!.value, "")
        ws.editSpec(common, "ends_only_if P3a: now > old(tok(qt).gLease)\n    source \"README.md#global-lock-guidance/2\"", "ends_only_if P3a: now >= old(tok(qt).gLease)\n    source \"README.md#global-lock-guidance/2\"")
        ws.rereview(common)
        val r = ws.verify("coroutine")
        val text = r.render()
        assertEquals(ModuleVerdict.FAILED, r.verdict, text)
        assertEquals(Verdict.FAILED, r.row("probe expiry_boundary").verdict, text)
        assertEquals(Verdict.PROVEN, r.row("probe expired_takeover_at_once").verdict, text)
        assertTrue(listOf("init", "tryLock", "unLock", "sweep").all { r.row(it).verdict == Verdict.PROVEN }, text)
    }

    /**
     * Three named probes, two of them calling sweep, which has no result, without outs (the haiku pilot's call). The
     * spec is under review intent (DESIGN-C.md 10.14), so each probe has a fixture source line and note.
     */
    private val sweeps =
        """
        |
        |probe sweep_before_lease_end(key: Key, a: Int) requires a >= 0 {
        |  x := tryLock(key, now = a, timeout = 3000, rej = false, limit = 0)
        |  sweep(key, now = a + 3000)
        |  ok := unLock(key, t = x)
        |  assert ok
        |}
        |  source none "test fixture"
        |  reviewed "test fixture, not a signature"
        |probe sweep_after_lease_end(key: Key, a: Int) requires a >= 0 {
        |  x := tryLock(key, now = a, timeout = 3000, rej = false, limit = 0)
        |  sweep(key, now = a + 3001)
        |  ok := unLock(key, t = x)
        |  assert ok
        |}
        |  source none "test fixture"
        |  reviewed "test fixture, not a signature"
        |probe no_sweep(key: Key, a: Int) requires a >= 0 {
        |  x := tryLock(key, now = a, timeout = 3000, rej = false, limit = 0)
        |  ok := unLock(key, t = x)
        |  assert ok
        |}
        |  source none "test fixture"
        |  reviewed "test fixture, not a signature"
        |
        """.trimMargin()

    @Test
    fun `three named probes emit three methods, and a result-less sweep call is a Viper call without targets`() {
        val e = workspace("emit", sweeps).emit("coroutine")
        assertEquals(
            listOf("probe" to "probe", "probe_sweep_before_lease_end" to "probe sweep_before_lease_end", "probe_sweep_after_lease_end" to "probe sweep_after_lease_end", "probe_no_sweep" to "probe no_sweep"),
            e.methods.filter { it.kind == MethodKind.PROBE }.map { it.name to it.label },
        )
        val lines = e.text.lines()
        assertEquals("  sweep(S, key, a + 3000)", lines[e.lineOf("sweep(S, key, a + 3000)") - 1])
        assertEquals("method probe_no_sweep(key: Key, a: Int)", lines[e.lineOf("method probe_no_sweep") - 1])
        // The warning about operations without progress_* ensures names the probe.
        assertTrue("probe sweep_before_lease_end uses operation sweep, which has no progress_* ensures: the probe may be vacuous (DESIGN-v0.md 5.4)" in e.warnings, e.warnings.toString())
    }

    @Test
    fun `the result-less sweep call has its contract - unLock still succeeds at the lease end, not after it`() {
        Silicon.check(Silicon.defaultHome())
        val r = workspace("sweeps", sweeps).verify("coroutine")
        val text = r.render()
        assertEquals(Verdict.PROVEN, r.row("probe sweep_before_lease_end").verdict, text)
        // After the lease the sweep may end the token, so the release is not certain: the call is not dropped.
        assertEquals(Verdict.FAILED, r.row("probe sweep_after_lease_end").verdict, text)
        assertEquals(Verdict.PROVEN, r.row("probe no_sweep").verdict, text)
        assertTrue(r.row("probe sweep_after_lease_end").findings.single().describe().contains("probe sweep_after_lease_end assert"), text)
    }

    @Test
    fun `each probe that calls an unsupported operation is its own UNSUPPORTED row, and the others run`() {
        Silicon.check(Silicon.defaultHome())
        val ws = workspace("unsupported", "\nprobe uses_unlock(key: Key) {\n  ok := unLock(key, t = 0)\n}\n  source none \"test fixture\"\n  reviewed \"test fixture, not a signature\"\nprobe only_trylock(key: Key) {\n  x := tryLock(key, now = 0, timeout = 1, rej = false, limit = 0)\n  assert x != NULL\n}\n  source none \"test fixture\"\n  reviewed \"test fixture, not a signature\"\n")
        // FreeTypeVerifyTest: a String of unknown abstraction makes unLock UNSUPPORTED.
        ws.editKotlin("coroutine", "            if (existing == null) return@compute null\n", "            if (existing == null) return@compute null\n            val seen = key\n")
        val r = ws.verify("coroutine")
        val text = r.render()
        assertEquals(ModuleVerdict.INCOMPLETE, r.verdict, text)
        assertEquals(
            listOf("probe only_trylock" to Verdict.PROVEN, "probe" to Verdict.UNSUPPORTED, "probe uses_unlock" to Verdict.UNSUPPORTED),
            r.rows.filter { it.kind == MethodKind.PROBE }.map { it.label to it.verdict },
            text,
        )
        assertEquals("probe uses_unlock not emitted: it calls unLock, which is not emitted", r.row("probe uses_unlock").note, text)
    }

    @Test
    fun `a call without outs of an operation with a result is an emit error`() {
        val ws = workspace("bad", "\nprobe drop(key: Key, a: Int) {\n  tryLock(key, now = a, timeout = 3000, rej = false, limit = 0)\n}\n  source none \"test fixture\"\n  reviewed \"test fixture, not a signature\"\n")
        val e = assertFailsWith<SidecarException> { ws.emit("coroutine") }
        assertTrue("operation tryLock returns the result; the probe call names 0 out(s)" in e.errors.single().message, e.message)
    }
}
