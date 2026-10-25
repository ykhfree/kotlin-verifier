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

    /** The rules of each question cell: `C04 rules 1,2,3,4,5 | ...` lines. */
    private fun rules(out: String) =
        out.lines().filter { it.startsWith("  C") }.associate { l -> l.trim().substringBefore(" ") to l.substringAfter("rules ").substringBefore(" ").split(",").map(String::toInt) }

    private val reference get() = listOf("--reference", specResource("questions/check-signed.json").path)

    @Test
    fun `three drafts - exactly the 8 question cells of the pilot, with the rules of DESIGN-C 3_3`() {
        val (code, out) = questions(*drafts(*models.toTypedArray()).toTypedArray(), *reference.toTypedArray())
        assertEquals(0, code, out)
        assertEquals(listOf("C04", "C05", "C06", "C07", "C09", "C12", "C15", "C19"), selected(out), out)
        assertEquals(
            mapOf(
                "C04" to listOf(1, 2, 3, 4, 5), "C05" to listOf(1, 2, 3, 4), "C06" to listOf(2, 4), "C07" to listOf(2, 4),
                "C09" to listOf(1), "C12" to listOf(1), "C15" to listOf(1, 3, 4, 5), "C19" to listOf(1, 2, 3, 4),
            ),
            rules(out),
            out,
        )
        assertTrue(out.lines().first().startsWith("kv spec questions: 3 draft(s) (haiku, sonnet, opus), mode a; rule 1: one author answered open, or two or more assumed; rule 2 on"), out)
        assertTrue("  C09 (cell C08) rules 1 |" in out, out)
    }

    @Test
    fun `one draft (JC1) - haiku 9, sonnet 8, opus 6 cells, each covering the decisions D1, D2, D4 and D5`() {
        val expected =
            mapOf(
                "haiku" to listOf("C03", "C04", "C05", "C09", "C12", "C14", "C15", "C16", "C19"),
                "sonnet" to listOf("C04", "C05", "C06", "C07", "C09", "C12", "C15", "C19"),
                "opus" to listOf("C04", "C05", "C09", "C12", "C15", "C19"),
            )
        val decisions = mapOf("D1" to setOf("C04", "C15"), "D2" to setOf("C09"), "D4" to setOf("C05", "C06", "C07", "C19"), "D5" to setOf("C12"))
        for ((m, cells) in expected) {
            val (code, out) = questions(*drafts(m).toTypedArray(), *reference.toTypedArray())
            assertEquals(0, code, out)
            assertTrue("rule 1: the author answered open or assumed; rule 2 off (one draft)" in out, out)
            assertEquals(cells, selected(out), "$m:\n$out")
            decisions.forEach { (d, cs) -> assertTrue(cs.any { it in cells }, "$m does not cover $d") }
        }
        // Without the reference, rule 4 is off: sonnet's C06 and C07 came from rule 4 alone (INFEASIBLE in its draft).
        val (_, out) = questions(*drafts("sonnet").toTypedArray())
        assertEquals(listOf("C04", "C05", "C09", "C12", "C15", "C19"), selected(out), out)
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

    private fun groupFile(edit: (String) -> String): File = File(dir, "groups.json").apply { writeText(edit(specResource("questions/pilot-groups.json").readText())) }

    @Test
    fun `groups - the pilot grouping passes and writes the bundle, a cell left out, in two groups or not a question fails`() {
        val args = drafts(*models.toTypedArray()) + reference
        val bundle = File(dir, "bundle.md")
        val (code, out) = questions(*args.toTypedArray(), "--groups", specResource("questions/pilot-groups.json").path, "--bundle", bundle.path)
        assertEquals(0, code, out)
        assertTrue("group check: OK, 4 group(s)" in out, out)
        val text = bundle.readText()
        assertEquals(listOf("## Q-D1 Expiry boundary", "## Q-D4 Takeover of an expired lock", "## Q-D2 unLock by the holder after expiry", "## Q-D5 unLock of a token no entry backs"), text.lines().filter { it.startsWith("## ") })
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
