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
 * M5 acceptance tests 1 and 3-7 (DESIGN-v0.md section 7) with Silicon, on temp copies of the specs and the
 * KeyLocalLock sources. Budget: DESIGN-v0.md 8.3. These tests fail, not skip, without Silicon.
 */
class VerifyTest {
    @TempDir
    lateinit var dir: File

    private lateinit var ws: Workspace

    @BeforeEach
    fun setUp() {
        Silicon.check(Silicon.defaultHome())
        ws = Workspace(dir)
    }

    private fun Report.findings(label: String) = row(label).findings

    @Test
    fun `1 - the three generated models verify within the budget, and the reactor report names the fromCallable model`() {
        for (variant in listOf("coroutine", "core", "reactor")) {
            // A pass-2 program of an earlier run at the same path.
            File(dir, "$variant.pass2.vpr").writeText("// stale")
            val r = ws.verify(variant)
            val text = r.render()
            assertEquals(ModuleVerdict.PROVEN, r.verdict, text)
            assertEquals(listOf("init", "tryLock", "unLock", "sweep", "lemma P3c", "probe"), r.rows.map { it.label }, text)
            assertEquals(listOf(Verdict.PROVEN), r.rows.map { it.verdict }.distinct(), text)
            assertTrue(r.seconds!! < SiliconBudget().wallSeconds, text)
            assertTrue("module: PROVEN" in text.lines().first(), text)
            // Nothing failed, so no property check (pass 2) ran, and the stale pass-2 program is gone.
            assertEquals(null, r.propertyCheck, text)
            assertTrue(!File(dir, "$variant.pass2.vpr").exists() && "Property check" !in text, text)
            // DESIGN-v0.md 8.3: the trusted model of the reactor operation bodies is listed for review.
            val models = text.lines().single { it.startsWith("  Trusted models: ") }.removePrefix("  Trusted models: ").split(", ")
            assertEquals(variant == "reactor", "reactor.Mono.fromCallable" in models, text)
            assertTrue("jdk.ConcurrentHashMap.compute" in models, text)
        }
    }

    @Test
    fun `3 - a false sidecar property is FAILED with its ID, sidecar position and the Kotlin action position`() {
        val reviewed = "  reviewed \"ykhfree 2026-10-23: README: the cleanup monitor drops an entry once lockTimeoutMillis has passed\" sha256 \"3c68af4c1e630a46\"\n"
        // Under review intent the new clause is signed as a person would sign it (DESIGN-C.md 10.14).
        ws.editSpec("keylocallock.common.kv", reviewed, reviewed + "  ensures P_false: !has(S, k)\n    source none \"t\"\n  reviewed \"t\"\n")
        ws.rereview("keylocallock.common.kv")
        val r = ws.verify("coroutine")
        val text = r.render()
        assertEquals(ModuleVerdict.FAILED, r.verdict, text)
        val sweep = r.row("sweep")
        assertEquals(Verdict.FAILED, sweep.verdict, text)
        assertEquals(97 to 37, sweep.kotlin!!.line to sweep.kotlin!!.col)
        val f = sweep.findings.single()
        assertEquals("P_false", (f.source as Source.Sidecar).id, text)
        assertEquals(ws.at("keylocallock.common.kv", "ensures P_false:", "!has"), f.at!!.line to f.at!!.col, text)
        val (line, col) = ws.at("keylocallock.common.kv", "ensures P_false:", "!has")
        assertTrue(text.lines().any { "action sweep" in it && "KeyLocalLock.kt:97:37" in it && it.trimEnd().endsWith("FAILED (property)") }, text)
        // A property error in pass 1 needs no property check.
        assertEquals(SubStatus.PROPERTY, sweep.sub, text)
        assertEquals(null, r.propertyCheck, text)
        assertTrue(!File(dir, "coroutine.pass2.vpr").exists(), text)
        assertTrue("sidecar P_false keylocallock.common.kv:$line:$col" in text, text)
        assertEquals(listOf(Verdict.PROVEN), r.rows.filter { it.label != "sweep" }.map { it.verdict }.distinct(), text)
    }

    @Test
    fun `4 - a wrong hole conjunct is FAILED with that conjunct's comment name`() {
        ws.editSpec("keylocallock.common.kv", "qt <= S.seq", "qt < S.seq")
        val r = ws.verify("coroutine")
        val text = r.render()
        assertEquals(ModuleVerdict.FAILED, r.verdict, text)
        assertEquals(Verdict.FAILED, r.row("tryLock").verdict, text)
        // MI_INV_3 has no comment of its own: it takes the nearest earlier comment, that of MI_INV_2.
        val f = r.findings("tryLock").single { (it.source as? Source.Sidecar)?.id == "MI_INV_3" }
        val name = "The counter is never negative, and every issued token is between 0 and the counter."
        assertEquals(name, (f.source as Source.Sidecar).note)
        assertEquals(ws.at("keylocallock.common.kv", "(forall qt: Int :: { tok(qt).gIssued }"), f.at!!.line to f.at!!.col)
        assertTrue("sidecar MI_INV_3 keylocallock.common.kv:${f.at!!.line}:3 \"$name\"" in text, text)
    }

    @Test
    fun `5 - a type error in a sidecar expression is ERROR with the sidecar position`() {
        ws.editSpec("keylocallock.common.kv", "==> res == NULL", "==> res == true")
        ws.rereview("keylocallock.common.kv")
        val r = ws.verify("coroutine")
        val text = r.render()
        assertEquals(ModuleVerdict.ERROR, r.verdict, text)
        assertTrue(r.rows.none { it.verdict == Verdict.PROVEN }, text)
        val f = r.findings("tryLock").single()
        assertEquals("progress_live", (f.source as Source.Sidecar).id, text)
        val (line, _) = ws.at("keylocallock.common.kv", "ensures progress_live:")
        assertEquals(line, f.at!!.line, text)
        val (_, start) = ws.at("keylocallock.common.kv", "ensures progress_live:", "res == true")
        assertTrue(f.at!!.col in start..start + "res == true".length, "${f.at} points into `res == true`:\n$text")
        assertTrue("ERROR: Silicon reported a parse, type or consistency error" in text, text)
    }

    @Test
    fun `6 - a forced timeout is ERROR, and nothing is PROVEN`() {
        val r = ws.verify("coroutine", SiliconBudget(wallSeconds = 1))
        val text = r.render()
        assertEquals(ModuleVerdict.ERROR, r.verdict, text)
        assertTrue(r.error!!.startsWith("timeout"), text)
        assertEquals(listOf(Verdict.ERROR), r.rows.map { it.verdict }.distinct(), text)
        assertTrue(" PROVEN" !in text, text)
    }

    @Test
    fun `7 - an unsupported action makes the module INCOMPLETE and is not emitted`() {
        ws.editKotlin(
            "coroutine",
            "    fun cancel() {",
            "    fun spin(key: String, n: Long) {\n        lockMap.compute(key) { _, v ->\n            while (n < 0) {\n            }\n            v\n        }\n    }\n\n    fun cancel() {",
        )
        val line = ws.kotlinLine("coroutine", "lockMap.compute(key) { _, v ->")
        val r = ws.verify("coroutine")
        val text = r.render()
        assertEquals(ModuleVerdict.INCOMPLETE, r.verdict, text)
        val spin = r.rows.single { it.kind == MethodKind.STRAY }
        assertEquals("stray action spin#$line" to Verdict.UNSUPPORTED, spin.label to spin.verdict, text)
        assertEquals(listOf(Verdict.PROVEN), r.rows.filter { it !== spin }.map { it.verdict }.distinct(), text)
        assertTrue("stray_spin" !in File(dir, "coroutine.vpr").readText())
        assertTrue("module: INCOMPLETE" in text && "Unsupported: stray action spin#$line" in text, text)
    }
}
