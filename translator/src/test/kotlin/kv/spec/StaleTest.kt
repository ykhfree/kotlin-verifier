package kv.spec

import kv.cli.runCli
import kv.sidecar.Sidecar
import kv.sidecar.SourceQuote
import kv.sidecar.draftFixture
import kv.sidecar.intentClauses
import kv.sidecar.parseSidecar
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C0 acceptance 2 and 3 (DESIGN-C.md 8) and the stale rules of 5.3 on the C0 fixture: the three pilot drafts with
 * their quotes as source lines, against snapshots of the req-shield texts (edited copies of README.md for 3).
 */
class StaleTest {
    @TempDir
    lateinit var dir: File

    private val readme = File(pilotInputs[0].path).readText()

    /** README.md line 118, the first line of block README.md#local-lock-map-size/2 (lines 118-121). */
    private val line118 = "- An entry is dropped as soon as the lock is released; an entry whose holder never released it is dropped by the"

    /** A snapshot of the pilot inputs with [readmeText] as README.md; returns its directory. */
    private fun snapshot(readmeText: String = readme): File {
        val out = File(dir, "snap-${readmeText.hashCode()}")
        val copy = File(dir, "README-${readmeText.hashCode()}.md").apply { writeText(readmeText) }
        writeSnapshot(out, listOf(SnapshotInput("README.md", copy.path)) + pilotInputs.drop(1))
        return out
    }

    private fun draft(
        name: String,
        text: String = draftFixture(name),
    ): Sidecar = parseSidecar("$name.kv", text)

    private fun stale(
        s: Sidecar,
        snap: File = snapshot(),
    ) = staleCheck(s, readSnapshot(snap))

    /** The clause name a finding's message starts with. */
    private fun clauseOf(f: StaleFinding) = f.message.substringBefore(" (")

    private fun replaceOnce(
        text: String,
        old: String,
        new: String,
    ): String {
        check(text.split(old).size == 2) { "'$old' occurs ${text.split(old).size - 1} times" }
        return text.replace(old, new)
    }

    /** 1-based line and column of the first line of [text] that contains [fragment]. */
    private fun at(
        text: String,
        fragment: String,
    ): Pair<Int, Int> {
        val lines = text.lines()
        val n = lines.indexOfFirst { fragment in it }
        check(n >= 0) { "no '$fragment'" }
        return n + 1 to lines[n].indexOf(fragment) + 1
    }

    @Test
    fun `the 114 quotes of the pilot drafts all pass on the unchanged texts`() {
        val snap = snapshot()
        val reports = listOf("haiku", "sonnet", "opus").map { stale(draft(it), snap) }
        reports.forEach { assertEquals(emptyList(), it.findings) }
        assertEquals(listOf(28, 36, 50), reports.map { it.quotes })
        assertEquals(listOf(0, 0, 0), reports.map { it.withoutBlockFingerprint })
        assertEquals(listOf(9, 2, 1), reports.map { it.none })
    }

    @Test
    fun `a quote matches across the block's line breaks and with its own whitespace runs, but not with other words`() {
        // README.md:118-119: "... is dropped by the" ends line 118, "cleanup monitor once" starts line 119.
        val s =
            parseSidecar(
                "t.kv",
                """
                |invariant I: true
                |  source "README.md#local-lock-map-size/2" quote "is dropped by the cleanup monitor once"
                |  source "README.md#local-lock-map-size/2" quote "is  dropped by the   cleanup monitor"
                |lemma L: true
                |  source "README.md#local-lock-map-size/2" quote "is dropped by thecleanup monitor"
                """.trimMargin(),
            )
        val findings = stale(s).findings
        assertEquals(listOf("stale-source" to 5), findings.map { it.code to it.pos.line })
    }

    @Test
    fun `a wrong anchor is source-moved and a paraphrased quote is stale-source, each at its line, naming its clause`() {
        val sonnet = draftFixture("sonnet")
        val wrong = replaceOnce(sonnet, "source \"README.md#local-lock-map-size/3\" quote \"The map is uncapped", "source \"README.md#local-lock-map-size/2\" quote \"The map is uncapped")
        val moved = stale(draft("sonnet", wrong)).findings.single()
        assertEquals("source-moved", moved.code)
        assertEquals(false, moved.error)
        assertEquals(at(wrong, "local-lock-map-size/2\" quote \"The map is uncapped").first to 5, moved.pos.line to moved.pos.col)
        val (l, c) = at(wrong, "ensures progress_tryLock_free_key_acquires")
        assertTrue(moved.message.startsWith("ensures progress_tryLock_free_key_acquires of tryLock ($l:$c): "), moved.message)
        assertTrue("is not in README.md#local-lock-map-size/2 but in README.md#local-lock-map-size/3" in moved.message, moved.message)

        val haiku = draftFixture("haiku")
        val quote = "quote \"cleanup monitor once `lockTimeoutMillis` has passed, which runs"
        val paraphrased = haiku.replaceFirst(quote, "quote \"cleanup monitor after `lockTimeoutMillis` has passed, which runs")
        val e = stale(draft("haiku", paraphrased)).findings.single()
        assertEquals("stale-source", e.code)
        assertEquals(true, e.error)
        assertEquals(at(haiku, quote).first to 5, e.pos.line to e.pos.col)
        val (il, ic) = at(paraphrased, "issue res {")
        assertTrue(e.message.startsWith("the issue rule of tryLock ($il:$ic): quote \"cleanup monitor after"), e.message)
        assertTrue("haiku.kv:${e.pos.line}:5: error[stale-source]: " in e.toString(), e.toString())
    }

    @Test
    fun `an anchor that is not in the snapshot is source-moved if the quote is elsewhere, else stale-source`() {
        val opus = draftFixture("opus")
        val gone =
            replaceOnce(
                opus,
                "source \"KeyLock.kt(core)#tryLock\" quote \"Acquires the lock",
                "source \"KeyLock.kt(core)#acquire\" quote \"Acquires the lock",
            ).let { replaceOnce(it, "quote \"To bound it, set", "quote \"To limit it, set") }
        val findings = stale(draft("opus", gone)).findings
        assertEquals(listOf("source-moved", "stale-source"), findings.map { it.code })
        assertTrue("is not in KeyLock.kt(core)#acquire (no such block in the snapshot) but in KeyLock.kt(core)#tryLock" in findings[0].message, findings[0].message)
        assertTrue("quote \"To limit it, set" in findings[1].message && "(anchor README.md#local-lock-map-size/3)" in findings[1].message, findings[1].message)
    }

    @Test
    fun `README line 118 changed - the clauses that quote it are stale-source, the block's other quotes only warn`() {
        val edited = replaceOnce(readme, line118, "- An entry is removed as soon as the lock is released; an entry that its holder never released is dropped by the")
        val snap = snapshot(edited)
        val stale =
            listOf("haiku", "sonnet", "opus").associateWith { name ->
                val s = draft(name)
                val findings = stale(s, snap).findings
                // Every finding is at a source line that cites the block, and every such line has one finding.
                val cites = s.intentClauses().flatMap { (_, c) -> c.sources.filterIsInstance<SourceQuote>().filter { it.anchor == "README.md#local-lock-map-size/2" } }
                assertEquals(cites.map { it.pos }, findings.map { it.pos }, name)
                findings.filter { it.code != "stale-source" }.forEach { assertEquals("source-context-changed", it.code, "$name: $it") }
                findings.filter { it.code == "stale-source" }.map { clauseOf(it) }
            }
        assertEquals(
            mapOf(
                "haiku" to listOf("ensures unLock_release_removes of unLock"),
                "sonnet" to
                    listOf(
                        "ensures progress_unLock_owner_releases of unLock",
                        "ensures sweep_never_adds_or_changes of sweep",
                        "ensures progress_sweep_drops_expired of sweep",
                        "ends_only_if sweep_ends_only_expired of sweep",
                    ),
                "opus" to
                    listOf(
                        "ensures progress_unLock_owner_releases of unLock",
                        "ensures progress_sweep_drops_expired of sweep",
                        "ends_only_if sweep_ends_only_expired of sweep",
                    ),
            ),
            stale,
        )
    }

    @Test
    fun `another sentence of the same block changed - every quote of the block warns source-context-changed, none is stale`() {
        val edited = replaceOnce(readme, "The map's size therefore tracks", "The size of the map therefore tracks")
        val snap = snapshot(edited)
        val counts =
            listOf("haiku", "sonnet", "opus").map { name ->
                val findings = stale(draft(name), snap).findings
                findings.forEach { assertEquals("source-context-changed", it.code, "$name: $it") }
                assertTrue(findings.all { "block README.md#local-lock-map-size/2 changed" in it.message && "the source line records sha256 \"a50a09d95953837f\"" in it.message })
                findings.size
            }
        assertEquals(listOf(7, 11, 11), counts)
        // Without block fingerprints on the source lines the change is not seen.
        val unsigned = draftFixture("sonnet").replace(Regex(""" sha256 "[0-9a-f]{16}""""), "")
        val report = stale(draft("sonnet", unsigned), snap)
        assertEquals(emptyList(), report.findings)
        assertEquals(36, report.withoutBlockFingerprint)
    }

    private fun kv(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = runCli(args.toList(), PrintStream(out), PrintStream(err))
        return Triple(exit, out.toString(), err.toString())
    }

    @Test
    fun `kv spec snapshot and kv spec stale - exit 0 without stale-source, 4 with it, 2 on a usage error`() {
        val snap = File(dir, "cli-snap")
        val (made, madeOut, madeErr) = kv("spec", "snapshot", "--out", snap.path, *pilotInputs.map { "${it.name}=${it.path}" }.toTypedArray())
        assertEquals(0, made, madeErr)
        assertTrue("128 blocks" in madeOut, madeOut)
        val opus = File(dir, "opus.kv").apply { writeText(draftFixture("opus")) }
        val (ok, okOut, _) = kv("spec", "stale", "--snapshot", snap.path, opus.path)
        assertEquals(0, ok, okOut)
        assertEquals(
            "kv spec stale: 50 quoted source line(s), 0 without a block fingerprint, 1 'source none': 0 stale-source, 0 source-moved, 0 source-context-changed\n",
            okOut,
        )
        val edited = snapshot(replaceOnce(readme, line118, line118.replace("dropped as soon as", "removed when")))
        val (bad, badOut, _) = kv("spec", "stale", "--snapshot", edited.path, opus.path)
        assertEquals(4, bad, badOut)
        assertEquals(1, badOut.lines().count { it.startsWith("${opus.path}:") && ": error[stale-source]: " in it }, badOut)
        assertEquals(10, badOut.lines().count { ": warning[source-context-changed]: " in it }, badOut)
        assertTrue(badOut.trim().endsWith("1 stale-source, 0 source-moved, 10 source-context-changed"), badOut)

        val syntax = File(dir, "bad.kv").apply { writeText("invariant I: true\n  source none reason\n") }
        assertEquals(4, kv("spec", "stale", "--snapshot", snap.path, syntax.path).first)
        assertEquals(2, kv("spec", "stale", "--snapshot", File(dir, "nothing").path, opus.path).first)
        assertEquals(2, kv("spec", "stale", opus.path).first)
        assertEquals(2, kv("spec", "snapshot", "--out", snap.path, "README.md").first)
        assertEquals(2, kv("spec", "snapshot", "--out", snap.path, "x=${File(dir, "missing.md").path}").first)
        assertEquals(2, kv("spec", "check").first)
    }
}
