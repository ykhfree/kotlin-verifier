package kv.scripts

import kv.emit.python
import kv.json.parseJson
import kv.sidecar.pilotCells
import kv.spec.pilotDir
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A test resource of the questions fixtures (spec/questions, spec/answers). */
fun specResource(path: String): File = File(SpecQuestionsScriptTest::class.java.getResource("/spec/$path")?.toURI() ?: error("missing fixture spec/$path"))

/**
 * C4 acceptance 2 (DESIGN-C.md 8): `scripts/spec.py questions` on the pilot data. The check.json fixtures are
 * `kv spec check --root` on the three final pilot drafts and the pilot's signed sidecar with the pilot cells
 * (pilot-c/checks/final-NAME, pilot-c/specs-signed); the answers are pilot-c/frozen-final/D-NAME/ANSWERS.md converted
 * by scripts/pilot_answers.py.
 */
class SpecQuestionsScriptTest {
    @TempDir
    lateinit var dir: File

    private val script = File(System.getProperty("kv.scriptsDir"), "spec.py").path
    private val models = listOf("haiku", "sonnet", "opus")

    private fun drafts(vararg names: String) = names.flatMap { listOf("--check", "$it=${specResource("questions/check-$it.json")}", "--answers", "$it=${specResource("answers/$it.json")}") }

    private fun questions(vararg args: String): Pair<Int, String> = python(script, "questions", *args)

    private fun selected(out: String) = out.lines().single { it.startsWith("question cells: ") }.substringAfter(": ").split(" ").drop(1)

    /** The rules of each question cell: `  C04 rules 1,2,3,4,5 | ...` or `  C09 (cell C08) rules 1 | ...` lines. */
    private fun rules(out: String) =
        out.lines().mapNotNull { Regex("""^  (\S+)(?: \(cell \S+\))? rules ([\d,]+) \|""").find(it) }.associate { m -> m.groupValues[1] to m.groupValues[2].split(",").map(String::toInt) }

    private val reference get() = listOf("--reference", specResource("questions/check-signed.json").path)

    /**
     * Rules 1-5 give exactly the 8 question cells of the pilot. Rule 6 (DESIGN-C.md 10.6 finding 3) adds C16: the
     * signed sidecar leaves the monitor duty open (D9), and the three drafts decide it (DESIGN-C.md 3.3: D9 shows only
     * against the signed sidecar).
     */
    @Test
    fun `three drafts - the 8 question cells of the pilot by rules 1-5, and C16 (D9) by rule 6 alone`() {
        val (code, out) = questions(*drafts(*models.toTypedArray()).toTypedArray(), *reference.toTypedArray())
        assertEquals(0, code, out)
        assertEquals(listOf("C04", "C05", "C06", "C07", "C09", "C12", "C15", "C16", "C19"), selected(out), out)
        assertEquals(
            mapOf(
                "C04" to listOf(1, 2, 3, 4, 5), "C05" to listOf(1, 2, 3, 4), "C06" to listOf(2, 4), "C07" to listOf(2, 4),
                "C09" to listOf(1), "C12" to listOf(1), "C15" to listOf(1, 3, 4, 5), "C16" to listOf(6), "C19" to listOf(1, 2, 3, 4),
            ),
            rules(out),
            out,
        )
        assertTrue(out.lines().first().startsWith("kv spec questions: 3 draft(s) (haiku, sonnet, opus), mode a; rule 1: one author answered open, or two or more assumed; rule 2 on"), out)
        assertTrue(out.lines().first().endsWith("; rule 6 on (the reference's OPEN cells)"), out)
        assertTrue("  C09 (cell C08) rules 1 |" in out, out)
        assertTrue("  C16 rules 6 | answers haiku=dropped/assumed sonnet=dropped/settled opus=dropped/settled | drafts haiku=dropped sonnet=dropped opus=dropped | reference open" in out, out)
    }

    @Test
    fun `one draft (JC1) - haiku 9, sonnet 9, opus 7 cells, each covering the decisions D1, D2, D4, D5 and D9`() {
        val expected =
            mapOf(
                "haiku" to listOf("C03", "C04", "C05", "C09", "C12", "C14", "C15", "C16", "C19"),
                "sonnet" to listOf("C04", "C05", "C06", "C07", "C09", "C12", "C15", "C16", "C19"),
                "opus" to listOf("C04", "C05", "C09", "C12", "C15", "C16", "C19"),
            )
        val decisions = mapOf("D1" to setOf("C04", "C15"), "D2" to setOf("C09"), "D4" to setOf("C05", "C06", "C07", "C19"), "D5" to setOf("C12"), "D9" to setOf("C16"))
        for ((m, cells) in expected) {
            val (code, out) = questions(*drafts(m).toTypedArray(), *reference.toTypedArray())
            assertEquals(0, code, out)
            assertTrue("rule 1: the author answered open or assumed; rule 2 off (one draft)" in out, out)
            assertEquals(cells, selected(out), "$m:\n$out")
            decisions.forEach { (d, cs) -> assertTrue(cs.any { it in cells }, "$m does not cover $d") }
        }
        // Without the reference, rules 4 and 6 are off: sonnet's C06 and C07 came from rule 4 alone (INFEASIBLE in its
        // draft), C16 from rule 6 alone.
        val (_, out) = questions(*drafts("sonnet").toTypedArray())
        assertEquals(listOf("C04", "C05", "C09", "C12", "C15", "C19"), selected(out), out)
        assertTrue(out.lines().first().endsWith("; rule 6 off (no --reference, no earlier decisions)"), out)
    }

    private fun decisionsFile(
        name: String,
        vararg decisions: String,
    ): File = File(dir, name).apply { writeText("""{"format": "kv-decisions/1", "decisions": [${decisions.joinToString()}]}""") }

    /**
     * DESIGN-C.md 10.6 finding 3, persistence: a decision file of an earlier run that leaves a cell open makes it a
     * question by rule 6 whenever a draft decides it, also without a reference. A cell left open that the draft
     * leaves OPEN too is no question; an open cell that is no scenario of the drafts, or an open group without its
     * cells, is an input error.
     */
    @Test
    fun `rule 6 - an earlier decision that leaves a cell open makes it a question when a draft decides it`() {
        val (_, before) = questions(*drafts("opus").toTypedArray())
        assertTrue("C16" !in selected(before), before)
        val c16 = decisionsFile("c16.json", """{"group": "D9", "cells": {"C16": "open"}}""", """{"group": "D1", "cells": {"C04": "null", "C15": "stays"}}""")
        val (code, out) = questions(*drafts("opus").toTypedArray(), "--decisions-ref", c16.path)
        assertEquals(0, code, out)
        assertEquals(selected(before) + "C16", selected(out).let { s -> s.filter { it != "C16" } + "C16" })
        assertEquals(listOf(6), rules(out)["C16"], out)
        assertTrue(out.lines().first().endsWith("; rule 4 off (no --reference); rule 6 on (1 cell(s) that earlier decisions leave open)"), out)
        // Only the cell left open counts: C04 and C15, decided earlier, keep their rules.
        assertEquals(rules(before)["C04"], rules(out)["C04"], out)
        // The signed sidecar as the draft leaves C16 OPEN, so rule 6 does not ask.
        val signed = listOf("--check", "signed=${specResource("questions/check-signed.json")}", "--answers", "signed=${specResource("answers/opus.json")}")
        val (_, open) = questions(*signed.toTypedArray(), "--decisions-ref", c16.path)
        assertTrue("C16" !in selected(open), open)
        // Input errors.
        val unknown = decisionsFile("unknown.json", """{"group": "D9", "cells": {"C21": "open"}}""")
        val (c1, o1) = questions(*drafts("opus").toTypedArray(), "--decisions-ref", unknown.path)
        assertEquals(2, c1, o1)
        assertTrue("the earlier decisions leave C21 open, which is no scenario of these drafts" in o1, o1)
        val noCells = decisionsFile("nocells.json", """{"group": "D9", "option": "open"}""")
        val (c2, o2) = questions(*drafts("opus").toTypedArray(), "--decisions-ref", noCells.path)
        assertEquals(2, c2, o2)
        assertTrue("group D9 is decided \"open\" and names no cell" in o2, o2)
    }

    /**
     * Rule 1's threshold with several drafts: C14 has one assumed answer (haiku) and is no question; with a second
     * assumed answer (sonnet's, edited) it is, by rule 1 alone.
     */
    @Test
    fun `three drafts - two assumed answers make a question, one does not`() {
        val (_, before) = questions(*drafts(*models.toTypedArray()).toTypedArray(), *reference.toTypedArray())
        assertTrue("C14" !in selected(before), before)
        val sonnet = specResource("answers/sonnet.json").readText()
        val c14 = Regex(""""cell": "C14",[^}]*?"status": "settled"""").find(sonnet)!!.value
        val edited = File(dir, "sonnet.json").apply { writeText(sonnet.replace(c14, c14.replace("\"settled\"", "\"assumed\""))) }
        val args = drafts("haiku", "opus") + listOf("--check", "sonnet=${specResource("questions/check-sonnet.json")}", "--answers", "sonnet=$edited")
        val (code, after) = questions(*args.toTypedArray(), *reference.toTypedArray())
        assertEquals(0, code, after)
        assertEquals(listOf(1), rules(after)["C14"], after)
        assertEquals(selected(before) + "C14", selected(after).let { s -> s.filter { it != "C14" } + "C14" })
    }

    /**
     * DESIGN-C.md 10.6 finding 3 on the real run's data: `kv spec check --root` with the run's 65 cells on the run's
     * phase-1 draft (spec/e2e/check-d1.json) and on the current signed sidecar (check-reference.json): the files
     * WORK/run/drafts/d1/check/check.json and WORK/run/reference/check/check.json of
     * specdraft/keylocallock/e2e-open-decision.sh, with the WORK and repository paths removed. The answers are the run's.
     * Mode a: rule 6 adds H14s alone (the monitor duty that the user declined, UC1) to the run's 15 question cells.
     * Mode b: the reference's clauses alone also leave open 4 cells after a monitor step that took t1's entry (O8r,
     * O8s, O9s, O10s; its hole decides them), so the reference's OPEN cells do not count; only an earlier decision
     * that leaves H14s open makes it a question.
     */
    @Test
    fun `rule 6 on the real run - mode a adds H14s alone to the run's 15 questions, mode b only from an earlier open decision`() {
        val run = File(pilotDir.parentFile, "specdraft/e2e-2026-10-26")
        val args = listOf("--check", "d1=${specResource("e2e/check-d1.json")}", "--answers", "d1=${File(run, "answers.json")}", "--reference", specResource("e2e/check-reference.json").path)
        val earlier = ((parseJson(File(run, "questions.json").readText()) as Map<*, *>)["questions"] as List<*>).map { (it as Map<*, *>)["cell"] as String }
        assertEquals(15, earlier.size)
        val (code, a) = questions(*args.toTypedArray())
        assertEquals(0, code, a)
        assertEquals((earlier + "H14s").toSet(), selected(a).toSet(), a)
        assertEquals(mapOf("H14s" to listOf(6)), rules(a).filterValues { 6 in it }, a)
        assertTrue("  H14s rules 6 | answers d1=no_entry/settled | drafts d1=no_entry | reference open" in a, a)
        val (_, b) = questions(*args.toTypedArray(), "--mode", "b")
        assertEquals(emptyMap(), rules(b).filterValues { 6 in it }, b)
        assertEquals(earlier, selected(b), b)
        assertTrue(b.lines().first().endsWith("; rule 6 off (no earlier decisions; the reference's OPEN cells count in mode a only)"), b)
        val (_, decided) = questions(*args.toTypedArray(), "--mode", "b", "--decisions-ref", specResource("e2e/decisions-open-H14s.json").path)
        assertEquals(mapOf("H14s" to listOf(6)), rules(decided).filterValues { 6 in it }, decided)
        assertEquals((earlier + "H14s").toSet(), selected(decided).toSet(), decided)
        assertTrue(decided.lines().first().endsWith("; rule 6 on (1 cell(s) that earlier decisions leave open)"), decided)
    }

    private fun groupFile(edit: (String) -> String): File = File(dir, "groups.json").apply { writeText(edit(specResource("questions/pilot-groups.json").readText())) }

    @Test
    fun `groups - the pilot grouping passes and writes the bundle, a cell left out, in two groups or not a question fails`() {
        val args = drafts(*models.toTypedArray()) + reference
        val bundle = File(dir, "bundle.md")
        val (code, out) = questions(*args.toTypedArray(), "--groups", specResource("questions/pilot-groups.json").path, "--bundle", bundle.path)
        assertEquals(0, code, out)
        assertTrue("group check: OK, 5 group(s)" in out, out)
        val text = bundle.readText()
        assertEquals(
            listOf("## Q-D1 Expiry boundary", "## Q-D4 Takeover of an expired lock", "## Q-D2 unLock by the holder after expiry", "## Q-D5 unLock of a token no entry backs", "## Q-D9 Monitor duty after the lease end"),
            text.lines().filter { it.startsWith("## ") },
        )
        // D9's options: the signed sidecar leaves C16 open, the three drafts drop the entry.
        assertTrue("Cells: C16 (rules 6)" in text && "A. C16 open: reference.\nB. C16 dropped: draft haiku, draft sonnet, draft opus." in text, text)
        val d1 = text.substringAfter("## Q-D1").substringBefore("## Q-D4")
        assertTrue("Cells: C04 (rules 1, 2, 3, 4, 5), C15 (rules 1, 3, 4, 5)" in d1, d1)
        assertTrue("""- [README.md#local-lock-map-size/2] "cleanup monitor once `lockTimeoutMillis` has passed" (haiku C04, haiku C15, opus C04, opus C15)""" in d1, d1)
        // The options from the verdicts (DESIGN-C.md 3.2 table): sonnet holds the lock at the lease end but its monitor drops it.
        assertTrue("A. C04 null, C15 stays: reference.\nB. C04 token, C15 dropped: draft haiku, draft opus.\nC. C04 null, C15 dropped: draft sonnet." in d1, d1)
        assertTrue("| haiku | token | null (open) | dropped | dropped (open) |" in d1, d1)
        assertTrue("| C09 | Acquired at time 0: unLock(k, t1) at time 5 (the steps of C08) |" in text, text)

        val leftOut = groupFile { it.replace("\"cells\": [\"C12\"]", "\"cells\": []") }
        val (c1, o1) = questions(*args.toTypedArray(), "--groups", leftOut.path)
        assertEquals(1, c1, o1)
        assertEquals(listOf("group D5 has no cells", "question C12 is in no group"), o1.lines().dropWhile { it != "group check: FAILED" }.drop(1).filter { it.isNotBlank() }.map { it.trim() })

        val twice = groupFile { it.replace("\"cells\": [\"C09\"]", "\"cells\": [\"C09\", \"C15\"]") }
        val (c2, o2) = questions(*args.toTypedArray(), "--groups", twice.path)
        assertEquals(1, c2, o2)
        assertTrue("  C15 is in two groups: D1 and D2" in o2, o2)

        val notQuestion = groupFile { it.replace("\"cells\": [\"C09\"]", "\"cells\": [\"C09\", \"C08\"]") }
        val (c3, o3) = questions(*args.toTypedArray(), "--groups", notQuestion.path)
        assertEquals(1, c3, o3)
        assertTrue("  group D2 lists C08, which is no question" in o3, o3)
    }

    @Test
    fun `input errors - a draft without answers, an outcome the cell does not have, a missing answer`() {
        val (c1, o1) = questions("--check", "haiku=${specResource("questions/check-haiku.json")}")
        assertEquals(2, c1)
        assertTrue("each draft needs --check and --answers: haiku" in o1, o1)
        val answers = specResource("answers/haiku.json").readText()
        val wrong = File(dir, "wrong.json").apply { writeText(answers.replace("\"outcome\": \"stays\"", "\"outcome\": \"kept\"")) }
        val (c2, o2) = questions("--check", "haiku=${specResource("questions/check-haiku.json")}", "--answers", "haiku=$wrong")
        assertEquals(2, c2)
        assertTrue("C14 names outcome 'kept'; cell C14 has stays, dropped" in o2, o2)
        val missing = File(dir, "missing.json").apply { writeText(answers.replace("\"cell\": \"C20\"", "\"cell\": \"C21\"")) }
        val (c3, o3) = questions("--check", "haiku=${specResource("questions/check-haiku.json")}", "--answers", "haiku=$missing")
        assertEquals(2, c3)
        assertTrue("answers C21, which is no cell of the check" in o3, o3)
    }

    @Test
    fun `the fixtures - the check verdicts are the pilot's, and the answers are a fresh conversion of ANSWERS_md`() {
        fun cells(f: File, mode: String) = ((parseJson(f.readText()) as Map<*, *>)["cells"] as Map<*, *>).let { (it[mode] as Map<*, *>)["cells"] }
        for (name in models + "signed") {
            for (mode in listOf("a", "b")) {
                val pilot = (parseJson(File(pilotDir, "results/det/$name-$mode.json").readText()) as Map<*, *>)["cells"]
                assertEquals(pilot, cells(specResource("questions/check-$name.json"), mode), "$name mode $mode")
            }
        }
        val (code, out) = python(File(System.getProperty("kv.scriptsDir"), "pilot_answers.py").path, pilotDir.path, pilotCells.path, dir.path)
        assertEquals(0, code, out)
        for (m in models) assertEquals(specResource("answers/$m.json").readText(), File(dir, "$m.json").readText(), m)
    }
}
