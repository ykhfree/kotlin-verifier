package kv.spec

import kv.cli.runCli
import kv.sidecar.SidecarException
import kv.sidecar.checkSidecar
import kv.sidecar.parseSidecar
import kv.sidecar.sha16
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * C7 (DESIGN-C.md 10.10): `scope` and `exclude` in a sidecar, and the rules `kv spec stale` adds with them:
 * `uncovered-block`, `empty-scope`, `stale-exclude` and three warnings.
 */
class CoverageTest {
    @TempDir
    lateinit var dir: File

    /** Two sections: rules (2 blocks) and notes (1 block). */
    private val doc = "# Rules\n\nLock expires at the lease.\n\nRefusal keeps the entry.\n\n# Notes\n\nAn informational example.\n"

    private fun blocks(text: String = doc) = snapshotBlocks(listOf(SnapshotInput("doc", File(dir, "doc.md").apply { writeText(text) }.path)))

    /** A sidecar with one clause that cites `doc#rules/1`, then [extra]. */
    private fun sidecar(extra: String) = parseSidecar("s.kv", "invariant A: true\n  source \"doc#rules/1\" quote \"Lock expires\"\n$extra")

    private fun findings(
        extra: String,
        text: String = doc,
    ) = staleCheck(sidecar(extra), blocks(text)).findings

    @Test
    fun `a scope names a section, a whole source or one block`() {
        val b = blocks()
        fun covered(target: String) = b.filter { sidecar("scope \"$target\"\n").scopes.single().covers(it.anchor, it.source) }.map { it.anchor }
        assertEquals(listOf("doc#rules/1", "doc#rules/2"), covered("doc#rules"))
        assertEquals(listOf("doc#rules/1", "doc#rules/2", "doc#notes/1"), covered("doc"))
        assertEquals(listOf("doc#notes/1"), covered("doc#notes/1"))
        // A section name is no prefix of another section: `doc#rule` matches nothing.
        assertEquals(emptyList(), covered("doc#rule"))
    }

    @Test
    fun `a block in scope that no quote cites is uncovered, once, at the scope`() {
        val f = findings("scope \"doc#rules\"\nscope \"doc\"\n")
        assertEquals(listOf("uncovered-block" to 3, "uncovered-block" to 4), f.map { it.code to it.pos.line }, f.joinToString("\n"))
        assertTrue(f[0].error && "block doc#rules/2 (lines 5-5 of ${File(dir, "doc.md").path}) is in scope \"doc#rules\"" in f[0].message, f[0].message)
        assertTrue("doc#notes/1" in f[1].message, f[1].message)
    }

    @Test
    fun `acceptance - a new block in a scoped section is exactly one uncovered-block`() {
        val scoped = "scope \"doc#rules\"\nexclude \"doc#rules/2\" because \"a decision with no clause yet\"\n"
        assertEquals(emptyList(), findings(scoped))
        val grown = doc.replace("Refusal keeps the entry.\n", "Refusal keeps the entry.\n\nA new rule nobody encoded.\n")
        val f = findings(scoped, grown)
        assertEquals(listOf("uncovered-block"), f.map { it.code }, f.joinToString("\n"))
        assertTrue("doc#rules/3" in f.single().message, f.single().message)
    }

    @Test
    fun `source none covers no block`() {
        val s = parseSidecar("s.kv", "invariant A: true\n  source none \"a user decision\"\nscope \"doc#notes\"\n")
        assertEquals(listOf("uncovered-block"), staleCheck(s, blocks()).findings.map { it.code })
    }

    @Test
    fun `a scope that matches nothing is empty-scope`() {
        val f = findings("scope \"doc#rulez\"\n")
        assertEquals(listOf("empty-scope" to true), f.map { it.code to it.error })
    }

    @Test
    fun `an exclude is checked against the snapshot, with or without a scope`() {
        val fp = blocks().single { it.anchor == "doc#rules/2" }.sha256
        // Its block fingerprint is the block's: no finding.
        assertEquals(emptyList(), findings("scope \"doc#rules\"\nexclude \"doc#rules/2\" because \"r\" sha256 \"$fp\"\n"))
        // The block changed: only the warning.
        val changed = findings("scope \"doc#rules\"\nexclude \"doc#rules/2\" because \"r\" sha256 \"$fp\"\n", doc.replace("Refusal keeps the entry.", "Refusal keeps the whole entry."))
        assertEquals(listOf("exclude-context-changed" to false), changed.map { it.code to it.error }, changed.joinToString("\n"))
        // The anchor is gone: an error, without a scope too.
        assertEquals(listOf("stale-exclude" to true), findings("exclude \"doc#rules/9\" because \"r\"\n").map { it.code to it.error })
        // A section name, copied from a scope line, is no block: the message says how to write it.
        val section = findings("exclude \"doc#rules\" because \"r\"\n").single()
        assertTrue(section.code == "stale-exclude" && "is a section or a source of 2 block(s)" in section.message, section.message)
        // Outside every scope, and cited by a quote: warnings.
        assertEquals(listOf("exclude-out-of-scope"), findings("exclude \"doc#notes/1\" because \"r\"\n").map { it.code })
        val cited = findings("scope \"doc#rules\"\nexclude \"doc#rules/1\" because \"r\"\n")
        assertEquals(listOf("uncovered-block", "exclude-cited"), cited.map { it.code }, cited.joinToString("\n"))
    }

    @Test
    fun `scope and exclude parse, and an exclude needs its because`() {
        val s = parseSidecar("s.kv", "scope \"conf:1\"\n  reviewed \"n\" sha256 \"0123456789abcdef\"\nexclude \"conf:1#a/1\" because \"model out\" sha256 \"00000000000000aa\"\n  reviewed \"m\"\n")
        assertEquals("scope \"conf:1\"", s.scopes.single().unit)
        val e = s.excludes.single()
        assertEquals(listOf("conf:1#a/1", "model out", "00000000000000aa", "m"), listOf(e.anchor, e.reason, e.blockSha256, e.reviewed))
        // The note covers the exclude without its block fingerprint (DESIGN-C.md 10.10).
        assertEquals("exclude \"conf:1#a/1\" because \"model out\"", e.unit)
        val bad = assertFailsWith<SidecarException> { parseSidecar("s.kv", "exclude \"conf:1#a/1\" \"model out\"") }.errors.single()
        assertTrue("expected 'because'" in bad.message, bad.message)
    }

    @Test
    fun `the sidecar checks - duplicates, notes under review intent, and a draft`() {
        val twice = checkSidecar(parseSidecar("s.kv", "scope \"doc\"\nscope \"doc\"\nexclude \"doc#a/1\" because \"x\"\nexclude \"doc#a/1\" because \"y\"\n")).filter { it.code == "duplicate-decl" }
        assertEquals(2, twice.size, twice.toString())
        val unsigned = "review intent\nscope \"doc\"\nexclude \"doc#a/1\" because \"x\"\n"
        val errors = checkSidecar(parseSidecar("s.kv", unsigned)).filter { "scope" in it.message || "exclude" in it.message }
        assertEquals(listOf("unreviewed", "unreviewed", "missing-block-fingerprint"), errors.map { it.code }, errors.joinToString("\n"))
        assertEquals(emptyList(), checkSidecar(parseSidecar("s.kv", unsigned), draft = true).filter { "scope" in it.message || "exclude" in it.message })
        // A signed scope and exclude: no error; a changed scope text is stale, in a draft too.
        val signed = "review intent\nscope \"doc\"\n  reviewed \"n\" sha256 \"${sha16("scope \"doc\"")}\"\nexclude \"doc#a/1\" because \"x\" sha256 \"00000000000000aa\"\n  reviewed \"m\" sha256 \"${sha16("exclude \"doc#a/1\" because \"x\"")}\"\n"
        assertEquals(emptyList(), checkSidecar(parseSidecar("s.kv", signed)).filter { "scope" in it.message || "exclude" in it.message })
        val narrowed = signed.replace("scope \"doc\"\n", "scope \"doc#a\"\n")
        assertEquals(listOf("stale-review"), checkSidecar(parseSidecar("s.kv", narrowed), draft = true).filter { "scope" in it.message }.map { it.code })
        // Re-recording the block fingerprint of an exclude needs no new signature.
        val reread = signed.replace("sha256 \"00000000000000aa\"", "sha256 \"00000000000000bb\"")
        assertEquals(emptyList(), checkSidecar(parseSidecar("s.kv", reread)).filter { "exclude" in it.message })
    }

    /** The signed core sidecar. Since 2026-10-28 it has a scope and cites README block waiting.../2 (DESIGN-C.md 10.12). */
    private val core = File(System.getProperty("kv.specDir"), "reqshield/reqshield.core.kv")

    private val coreSnapshot = File(System.getProperty("kv.specDir"), "../specdraft/reqshield-core/snapshot")

    @Test
    fun `scope README_md on a copy of the signed core sidecar - 45 uncovered blocks, as counted by hand`() {
        val snap = coreSnapshot
        val copy = File(dir, "core.kv").apply { writeText(core.readText() + "\nscope \"README.md\"\n") }
        val out = ByteArrayOutputStream()
        val exit = runCli(listOf("spec", "stale", "--snapshot", snap.path, copy.path), PrintStream(out), PrintStream(ByteArrayOutputStream()))
        assertEquals(4, exit)
        val uncovered = out.toString().lines().filter { "error[uncovered-block]" in it }
        assertEquals(45, uncovered.size)
        val bySection = uncovered.groupingBy { Regex("""block README\.md#([^ ]+)/\d+ """).find(it)!!.groupValues[1] }.eachCount()
        // DESIGN-C.md 10.10 prediction 3, the per-section count registered before the tool existed (46). Since 10.12 the
        // sidecar cites waiting-for-another-request-lock-not-acquired/2, so that section's 1 is gone.
        val expected =
            "top 1, req-shield 1, requirements 4, usage 2, release-versions 2, dev-snapshot-versions 3, integration-tests-with-redis-testcontainers 3, " +
                "kotlin-with-reactor-3-8-spring-boot-4 2, webflux-null-handling 1, cache-key-layout 3, global-lock-guidance 4, local-lock-map-size 7, " +
                "cache-eviction-semantics 2, thread-pools-and-schedulers 4, webflux-adapter 2, " +
                "kotlin-coroutine-adapter 1, contributing 1, license 1, how-to-reach-us 1"
        assertEquals(expected, bySection.entries.joinToString { "${it.key} ${it.value}" })
        assertTrue(out.toString().lines().any { it == "kv spec stale: 2 scope(s), 0 exclude(s): 45 uncovered-block, 0 empty-scope, 0 stale-exclude, 0 exclude-context-changed, 0 exclude-out-of-scope, 0 exclude-cited" }, out.toString())
    }

    @Test
    fun `without a scope or an exclude, kv spec stale prints no C7 line`() {
        val noScope = File(dir, "core.kv").apply { writeText(core.readText().replace(Regex("""(?m)^scope "[^"]*"\n {2}reviewed "[^"]*" sha256 "[0-9a-f]{16}"\n"""), "")) }
        check("scope \"" !in noScope.readText()) { "the scope was not removed" }
        val out = ByteArrayOutputStream()
        val exit = runCli(listOf("spec", "stale", "--snapshot", coreSnapshot.path, noScope.path), PrintStream(out), PrintStream(ByteArrayOutputStream()))
        assertEquals(0, exit, out.toString())
        assertTrue(out.toString().lines().none { "scope(s)" in it }, out.toString())
    }

    @Test
    fun `the signed core sidecar covers its scope - no C7 finding (DESIGN-C 10 point 12)`() {
        val out = ByteArrayOutputStream()
        val exit = runCli(listOf("spec", "stale", "--snapshot", coreSnapshot.path, core.path), PrintStream(out), PrintStream(ByteArrayOutputStream()))
        assertEquals(0, exit, out.toString())
        assertTrue(out.toString().lines().any { it.startsWith("kv spec stale: 1 scope(s), 0 exclude(s): 0 uncovered-block") }, out.toString())
    }
}
