package kv.verify

import kv.emit.MethodKind
import kv.emit.Source
import kv.emit.Workspace
import kv.emit.emitBound
import kv.emit.lowerReqShield
import kv.emit.normalize
import kv.emit.spikeDir
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M6 acceptance tests (DESIGN-v0.md section 7) on temp copies of the KeyLocalLock sources: (1) a compute that no
 * operation names gets the default contract, and its unconditional delete fails `ends_only_if false`; (2) a token
 * issued twice fails the set-once check; (3) the generated HIST is the target model's HIST.
 */
class GhostFixtureTest {
    @TempDir
    lateinit var dir: File

    private lateinit var ws: Workspace

    @BeforeEach
    fun setUp() {
        ws = Workspace(dir)
    }

    @Test
    fun `1 - a stray compute that deletes the entry fails the default ends_only_if false`() {
        Silicon.check(Silicon.defaultHome())
        ws.editKotlin("coroutine", "    fun cancel() {", "    fun forceRelease(key: String) {\n        lockMap.compute(key) { _, _ -> null }\n    }\n\n    fun cancel() {")
        val line = ws.kotlinLine("coroutine", "lockMap.compute(key) { _, _ -> null }")
        val r = ws.verify("coroutine")
        val text = r.render()
        assertEquals(ModuleVerdict.FAILED, r.verdict, text)
        val stray = r.rows.single { it.kind == MethodKind.STRAY }
        assertEquals("stray action forceRelease#$line", stray.label, text)
        assertEquals(Verdict.FAILED, stray.verdict, text)
        assertEquals(listOf(Source.Generated("ends_only_if default false")), stray.findings.map { it.source }, text)
        assertEquals(listOf(Verdict.PROVEN), r.rows.filter { it !== stray }.map { it.verdict }.distinct(), text)
        assertTrue("default ends_only_if false for stray forceRelease#$line" in text, text)
    }

    @Test
    fun `2 - re-issuing the same token fails the set-once check, reported at the issue rule and the action`() {
        Silicon.check(Silicon.defaultHome())
        // SB4 (DESIGN-v0.md 6): nextToken() reads the counter instead of incrementing it.
        ws.editKotlin("coroutine", "tokenSequence.incrementAndGet()", "tokenSequence.get()")
        val r = ws.verify("coroutine")
        val text = r.render()
        assertEquals(ModuleVerdict.FAILED, r.verdict, text)
        val tryLock = r.row("tryLock")
        assertEquals(Verdict.FAILED, tryLock.verdict, text)
        assertEquals(155 to 9, tryLock.kotlin!!.line to tryLock.kotlin!!.col)
        val f = tryLock.findings.single { (it.source as? Source.Sidecar)?.id == "issue tryLock: set-once gIssued" }
        assertTrue(f.error.message.startsWith("Assert might fail"), f.error.message)
        val (line, col) = ws.at("keylocallock.common.kv", "issue res {", "issue")
        assertEquals(line to col, f.at!!.line to f.at!!.col)
        assertTrue(text.lines().any { "action tryLock" in it && "KeyLocalLock.kt:155:9" in it && it.trimEnd().endsWith("FAILED (property)") }, text)
        assertTrue("sidecar issue tryLock: set-once gIssued keylocallock.common.kv:$line:$col" in text, text)
    }

    /** The `define HIST (...)` of a Viper file, up to its closing line. */
    private fun hist(text: String): String = text.lines().dropWhile { !it.startsWith("define HIST") }.let { l -> l.take(l.indexOfFirst { it == ")" } + 1) }.joinToString("\n")

    @Test
    fun `3 - the generated HIST is the HIST of the target models`() {
        val expected = File(dir, "target-hist.vpr").apply { writeText(hist(File(spikeDir, "effect_ghost/target.vpr").readText())) }
        assertTrue(expected.readText().startsWith("define HIST ("), expected.readText())
        for (variant in listOf("coroutine", "core", "reactor")) {
            val (m, s) = lowerReqShield(variant)
            val actual = File(dir, "$variant-hist.vpr").apply { writeText(hist(emitBound(m, s).text)) }
            val (code, out) = normalize(expected, actual)
            assertEquals(0, code, "$variant HIST:\n$out")
        }
    }
}
