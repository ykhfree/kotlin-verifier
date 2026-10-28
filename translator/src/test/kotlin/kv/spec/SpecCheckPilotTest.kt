package kv.spec

import kv.ReqShield
import kv.cli.runCli
import kv.emit.specDir
import kv.json.parseJson
import kv.sidecar.draftFixture
import kv.sidecar.pilotCells
import kv.verify.Silicon
import kv.verify.SiliconBudget
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C3 acceptance with Silicon (DESIGN-C.md 8): `kv spec check` on the pilot's sidecars against the pilot's measured
 * results (pilot-c/results/det, pilot-c/results/iso), and on the current sidecar. The pilot ran a frozen older kv
 * whose harness added the tool facts by text; the tool emits them now (C2). The full table of every sidecar in both
 * modes is in the C3 report; this class runs a subset that covers each acceptance item.
 */
class SpecCheckPilotTest {
    @TempDir
    lateinit var dir: File

    private fun check(
        sidecar: File,
        root: Boolean,
        cells: Boolean = true,
        draft: Boolean = false,
        name: String = sidecar.parentFile.name,
    ): SpecCheckReport {
        Silicon.check(Silicon.defaultHome())
        val out = File(dir, "$name-${if (root) "root" else "noroot"}")
        return specCheck(
            SpecCheckOptions(
                sidecar,
                File(pilotDir, "packet"),
                pilotCells.takeIf { cells },
                ReqShield.dir.takeIf { root },
                ReqShield.classpath,
                ReqShield.kotlincArgs,
                draft,
                out,
                Silicon.defaultHome(),
                SiliconBudget(),
            ),
        )
    }

    private fun pilotSidecar(dir: String) = File(pilotDir, "$dir/keylocallock.coroutine.kv")

    /** The pilot's cells of `results/det/<name>-<mode>.json`. */
    private fun pilot(
        name: String,
        mode: String,
    ): Map<*, *> = (parseJson(File(pilotDir, "results/det/$name-$mode.json").readText()) as Map<*, *>)["cells"] as Map<*, *>

    private fun SpecCheckReport.cells(mode: Mode): Map<String, String> = determinacy.single { it.mode == mode }.also { assertEquals(null, it.error, render()) }.cells

    private fun SpecCheckReport.failing() = isolation!!.clauses.filter { it.verdict == "FAILED" }.map { "${it.action} ${it.clause}" }

    @Test
    fun `the pilot's signed sidecar - both modes as the pilot, every clause PROVEN, two field-fixing warnings, mode b the same without code`() {
        val r = check(pilotSidecar("specs-signed"), root = true)
        val text = r.render()
        assertEquals(0, r.errorCount, text)
        assertEquals(pilot("signed", "a"), r.cells(Mode.A), text)
        assertEquals(pilot("signed", "b"), r.cells(Mode.B), text)
        assertEquals(listOf("C16"), r.cells(Mode.A).filterValues { it == "OPEN" }.keys.toList())
        assertEquals(listOf("C05", "C16", "C19"), r.cells(Mode.B).filterValues { it == "OPEN" }.keys.toList())
        assertEquals(12 to emptyList(), r.isolation!!.clauses.size to r.isolation.clauses.filter { it.verdict != "PROVEN" }, text)
        assertEquals(emptyList(), r.isolation.hole)
        assertEquals(listOf("init PROVEN", "lemma P3c PROVEN", "probe PROVEN"), r.isolation.methods.map { "${it.label} ${it.verdict}" }, text)
        assertEquals(listOf("ensures progress_live of tryLock", "ensures expired_takeover of tryLock"), r.fieldWarnings.map { it.clause })
        assertTrue(File(r.options.out, "check.json").isFile && File(r.options.out, "contract-a.vpr").isFile && File(r.options.out, "isolate-7.vpr").isFile)

        val alone = check(pilotSidecar("specs-signed"), root = false)
        assertEquals(listOf(Mode.B), alone.determinacy.map { it.mode })
        assertEquals(r.cells(Mode.B), alone.cells(Mode.B))
        assertEquals(null, alone.isolation)
        assertTrue("mode b only: without code, no run proves the hole" in alone.render(), alone.render())
    }

    @Test
    fun `the sonnet draft - INFEASIBLE C06 and C07 as in the pilot, and four failing clauses on the code`() {
        val r = check(pilotSidecar("checks/final-sonnet"), root = true, name = "sonnet")
        val text = r.render()
        assertEquals(pilot("sonnet", "a"), r.cells(Mode.A), text)
        assertEquals(pilot("sonnet", "b"), r.cells(Mode.B), text)
        assertEquals(
            listOf(
                "tryLock ends_only_if (default false)",
                "tryLock ensures tryLock_held_key_returns_null",
                "tryLock ensures tryLock_held_key_unchanged",
                "sweep ensures progress_sweep_drops_expired",
            ),
            r.failing(),
            text,
        )
        assertTrue(text.trimEnd().lines().last().endsWith("; 4 failing clause(s), 0 failing init, lemma or probe"), text)
    }

    /** DESIGN-C.md appendix B.3: haiku 3 and opus 2 failing clauses (sonnet's 4 are above): 9 in all. */
    @Test
    fun `the haiku and opus drafts - the failing clauses of appendix B3, and mode b as in the pilot without code`() {
        val haiku = check(pilotSidecar("checks/final-haiku"), root = true, cells = false, name = "haiku")
        assertEquals(
            listOf("tryLock ensures progress_expired_takeover", "tryLock ensures tryLock_null_only_when_owned", "sweep ensures progress_sweep_drops_expired"),
            haiku.failing(),
            haiku.render(),
        )
        assertEquals(19, haiku.isolation!!.clauses.size)
        val opus = check(pilotSidecar("checks/final-opus"), root = true, cells = false, name = "opus")
        assertEquals(listOf("tryLock ensures progress_tryLock_takeover", "sweep ensures progress_sweep_drops_expired"), opus.failing(), opus.render())
        assertEquals(14, opus.isolation!!.clauses.size)
        for (name in listOf("haiku", "opus")) {
            assertEquals(pilot(name, "b"), check(pilotSidecar("checks/final-$name"), root = false).cells(Mode.B), name)
        }
    }

    /**
     * DESIGN-C.md 10.6 finding 2: the real run's aligned draft, with the run's hole, has no failing clause on the code,
     * and its init fails: its invariant over tok(x) has no gIssued premise. The isolation runs report the init error
     * among the errors outside the kept clauses only; the init row names it as kv verify does (align-verify.report).
     */
    @Test
    fun `the real run's aligned draft - no failing clause, and the init row FAILED with kv verify's error line`() {
        val d = File(dir, "e2e").apply { mkdirs() }
        val hole = Regex("hole MI_INV \"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL)
        val runHole = hole.find(File(pilotDir, "specs-signed/keylocallock.common.kv").readText())!!.value
        val draft = File(pilotDir.parentFile, "specdraft/e2e-2026-10-26/DRAFT-aligned.kv").readText()
        File(d, "keylocallock.common.kv").writeText(draft.replace(hole, Regex.escapeReplacement(runHole)))
        File(specDir, "keylocallock.coroutine.kv").copyTo(File(d, "keylocallock.coroutine.kv"))
        val r = check(File(d, "keylocallock.coroutine.kv"), root = true, cells = false, draft = true, name = "e2e-aligned")
        val text = r.render()
        assertEquals(emptyList(), r.failing(), text)
        assertEquals("init" to "FAILED", r.isolation!!.methods.first().let { it.label to it.verdict }, text)
        assertEquals("Postcondition of init might not hold: generated MI (incomplete quantifiers)", r.isolation.methods.first().detail, text)
        val probes = listOf("contend", "release_reacquire", "stale_holder", "cap_new_key", "cap_existing_key", "unbacked_unlock").map { "probe pr_$it" } + listOf("D1", "D4", "D7", "D5").map { "probe decision_$it" }
        assertEquals(listOf("lemma lem_mutual_exclusion_backed") + probes, r.isolation.methods.drop(1).map { it.label }, text)
        assertTrue(r.isolation.methods.drop(1).all { it.verdict == "PROVEN" }, text)
        assertTrue("  FAILED init\n      Postcondition of init might not hold: generated MI (incomplete quantifiers)\n" in text, text)
        assertTrue(text.trimEnd().lines().last().endsWith("; 0 failing clause(s), 1 failing init, lemma or probe"), text)
        assertTrue(File(r.options.out, "program.vpr").isFile)
    }

    @Test
    fun `the aligned drafts - mode b decides all 19 cells, as in the pilot, without code`() {
        for (name in listOf("haiku", "sonnet", "opus")) {
            val r = check(pilotSidecar("checks/aligned-$name"), root = false, name = "aligned-$name")
            assertEquals(pilot("aligned-$name", "b"), r.cells(Mode.B), r.render())
            assertEquals(emptyList(), r.fieldWarnings)
        }
    }

    /**
     * The current sidecar: entry_lease ties expiresAt to gLease, so mode b now decides C05 and C19, which were OPEN for
     * the pilot's signed sidecar (prediction of the C3 task). C16 stays OPEN: no clause says that the monitor drops an
     * expired entry (UC1).
     */
    @Test
    fun `the current sidecar - mode b decides C05 and C19 now, C16 stays OPEN`() {
        val r = check(File(specDir, "keylocallock.coroutine.kv"), root = false, name = "current")
        val b = r.cells(Mode.B)
        assertEquals("DETERMINED(token)" to "DETERMINED(backed)", b["C05"] to b["C19"], r.render())
        assertEquals(listOf("C16"), b.filterValues { it == "OPEN" }.keys.toList(), r.render())
        // Every other cell as the pilot's signed sidecar decided it with the hole.
        assertEquals<Map<*, *>>(pilot("signed", "a").filterKeys { it != "C16" }, b.filterKeys { it != "C16" })
    }

    @Test
    fun `without cells, Silicon still type-checks the clauses - an ill-typed ensures is an error at its sidecar position`() {
        val d = File(dir, "typed").apply { mkdirs() }
        specDir.listFiles()!!.forEach { it.copyTo(File(d, it.name)) }
        val good = check(File(d, "keylocallock.coroutine.kv"), root = false, cells = false, name = "typed-ok")
        assertEquals(0, good.errorCount, good.render())
        assertTrue("Types: no cells; the contract-only model (mode b) type-checks and its contracts are well-formed" in good.render(), good.render())
        val common = File(d, "keylocallock.common.kv")
        val clause = "ensures progress_free: !old(has(S, k)) && !rej ==> res != NULL && tok(res).gBacked"
        require(common.readText().split(clause).size == 2)
        common.writeText(common.readText().replace(clause, "ensures progress_free: !old(has(S, k)) && !rej ==> res"))
        // Re-signed as a person would (review intent, DESIGN-C.md 10.14), so the type error is the only error.
        kv.rereview(common)
        val bad = check(File(d, "keylocallock.coroutine.kv"), root = false, cells = false, name = "typed-bad")
        val text = bad.render()
        assertEquals(1, bad.errorCount, text)
        assertTrue("is ERROR: Silicon reported a parse, type or consistency error" in text, text)
        assertTrue("found incompatible type `Int`, expected `Bool`: sidecar progress_free keylocallock.common.kv:" in text, text)
    }

    private fun kv(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runCli(args.toList(), PrintStream(out), PrintStream(err))
        return Triple(code, out.toString(), err.toString())
    }

    /** The haiku draft as the drafter wrote it (unsigned, with source lines), with the signed variant file. */
    private fun unsignedDraft(): File {
        val d = File(dir, "draft").apply { mkdirs() }
        File(d, "keylocallock.common.kv").writeText(draftFixture("haiku"))
        File(pilotDir, "specs-signed/keylocallock.coroutine.kv").copyTo(File(d, "keylocallock.coroutine.kv"))
        return File(d, "keylocallock.coroutine.kv")
    }

    @Test
    fun `kv spec check --draft - an unsigned draft is checked, the report starts with DRAFT, without --draft it is an error`() {
        Silicon.check(Silicon.defaultHome())
        val sidecar = unsignedDraft()
        val args = arrayOf("spec", "check", "--snapshot", File(pilotDir, "packet").path, "--cells", pilotCells.path, "--out", File(dir, "draft-out").path)
        val (code, out, err) = kv(*args, "--draft", sidecar.path)
        assertEquals(0, code, out + err)
        assertEquals("DRAFT: not signed; the verdict counts for nothing", out.lines().first())
        assertTrue("Signatures: draft, 23 intent clause(s) without a 'reviewed' note: the issue rule of tryLock, ensures progress_new_key of tryLock," in out, out)
        assertTrue("Sources (snapshot ${File(pilotDir, "packet").path}): 28 quoted source line(s)" in out && "0 stale-source" in out, out)
        // The draft's hole is true, so its mode b is the pilot's (which ran it with the signed hole).
        val json = parseJson(File(dir, "draft-out/check.json").readText()) as Map<*, *>
        assertEquals(pilot("haiku", "b"), ((json["cells"] as Map<*, *>)["b"] as Map<*, *>)["cells"], out)

        val (signedOnly, out2, _) = kv(*args, sidecar.path)
        assertEquals(4, signedOnly, out2)
        assertTrue(out2.lines().first().startsWith("kv spec check: keylocallock.coroutine.kv") && "error[unreviewed]" in out2, out2)
        assertEquals(2, kv("spec", "check", sidecar.path).first)
    }
}
