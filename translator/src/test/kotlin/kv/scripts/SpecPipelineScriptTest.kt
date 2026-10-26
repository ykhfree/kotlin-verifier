package kv.scripts

import kv.ReqShield
import kv.json.parseJson
import kv.sidecar.pilotCells
import kv.spec.pilotDir
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DESIGN-C.md C5: scripts/spec.py task, collect, cells, questions --run, align and outcomes on the KeyLocalLock
 * pilot. kv is this build's CLI: the grammar check and the cell checks run Silicon (mode b, without code). Only
 * `kv verify` of the outcomes test is a fake. The runs on the code are specdraft/pilot-replay/replay.sh.
 */
class SpecPipelineScriptTest {
    @TempDir
    lateinit var dir: File

    private val repo = File(System.getProperty("kv.specDir")).parentFile
    private val script = File(System.getProperty("kv.scriptsDir"), "spec.py")
    private val protocol = File(repo, "specdraft/keylocallock")
    private val java = File(System.getProperty("java.home"), "bin/java").path
    private val run get() = File(dir, "run")

    private fun box(phase: Int, name: String = "opus") = File(run, "boxes/$name/phase$phase")

    /** Runs COMMAND in [cwd]; the exit code and the merged output. */
    private fun exec(
        vararg command: String,
        cwd: File = dir,
    ): Pair<Int, String> {
        val p = ProcessBuilder(*command).directory(cwd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(600, TimeUnit.SECONDS)) { "${command.toList()} did not finish" }
        return p.exitValue() to out
    }

    private fun spec(vararg args: String) = exec("python3", "-I", script.path, *args)

    private fun ok(result: Pair<Int, String>): String {
        assertEquals(0, result.first, result.second)
        return result.second
    }

    /**
     * A kv install whose bin/kv runs this build's CLI. With [fakeVerify], `kv verify` is a fake: it logs its arguments
     * to verify.log and proves exactly when the draft is strict (`now > tok(qt).gLease`) and the implementation file
     * has no `now >= `, or the other way round. A drafted file with the comment `FAKE: verify fails` fails at init, a
     * probe assert and the sweep clause sweep_keeps_live_entry, with the report lines of kv verify. With [fakeVerify],
     * `kv spec check` also runs without the code (mode b, no isolation runs), so that a run with code stays fast.
     */
    private fun kvInstall(fakeVerify: Boolean): File {
        val install = File(dir, "kvinstall")
        File(install, "lib/translator.jar").apply { parentFile.mkdirs() }.writeText("test build")
        File(dir, "cp.txt").writeText(System.getProperty("java.class.path"))
        val kv = File(install, "bin/kv").apply { parentFile.mkdirs() }
        kv.writeText(
            """
            |#!/usr/bin/env python3
            |import json, os, subprocess, sys
            |d = "${dir.path}"
            |args = sys.argv[1:]
            |fake = ${if (fakeVerify) "True" else "False"}
            |if args[0] != "verify" or not fake:
            |    if fake and args[0] == "spec":
            |        for o in ("--root", "--classpath", "--kotlinc"):
            |            if o in args:
            |                del args[args.index(o):args.index(o) + 2]
            |    sys.exit(subprocess.call(["$java", "-cp", open(os.path.join(d, "cp.txt")).read(), "kv.cli.MainKt"] + args))
            |root, sidecar = args[args.index("--root") + 1], args[-1]
            |common = open(os.path.join(os.path.dirname(sidecar), "keylocallock.common.kv")).read()
            |if "FAKE: verify fails" in common:
            |    at = lambda s: next(i for i, l in enumerate(common.splitlines(), 1) if s in l)
            |    print("DRAFT: not signed; the verdict counts for nothing")
            |    print("m (keylocallock.coroutine.kv)    module: FAILED (Silicon 1.00 s)")
            |    print("  action tryLock  KeyLocalLock.kt:155:9   PROVEN")
            |    print("  action sweep    KeyLocalLock.kt:97:37   FAILED (property)")
            |    print("      Postcondition of sweep might not hold: sidecar sweep_keeps_live_entry keylocallock.common.kv:%d:3" % at("ensures sweep_keeps_live_entry"))
            |    print("  init FAILED · probe FAILED")
            |    print("      init: Postcondition of init might not hold: generated MI (incomplete quantifiers)")
            |    print("      probe: Assert might fail: sidecar probe assert keylocallock.common.kv:%d:3" % at("  assert "))
            |    print("Spec to review: none")
            |    sys.exit(1)
            |impl = open(os.path.join(root, "core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine/KeyLocalLock.kt")).read()
            |strict, ge = "now > tok(qt).gLease" in common, "now >= " in impl
            |with open(os.path.join(d, "verify.log"), "a") as f:
            |    f.write(json.dumps({"args": args, "root": root, "classes": [l for l in common.splitlines() if l.startswith("class LockInfo")]}) + "\n")
            |proven = strict != ge
            |print("DRAFT: not signed; the verdict counts for nothing")
            |print("m (keylocallock.coroutine.kv)    module: %s (Silicon 1.00 s)" % ("PROVEN" if proven else "FAILED"))
            |print("  action tryLock  KeyLocalLock.kt:155:9   %s" % ("PROVEN" if proven else "FAILED (property)"))
            |if not proven:
            |    print("      Postcondition of tryLock might not hold: sidecar ends_only_if tryLock_ends_only_expired keylocallock.common.kv:80:3")
            |print("  action unLock   KeyLocalLock.kt:209:9   PROVEN")
            |print("  init PROVEN · probe PROVEN")
            |sys.exit(0 if proven else 1)
            |
            """.trimMargin(),
        )
        kv.setExecutable(true)
        return install
    }

    /**
     * A new run from the replay's run config (kv-spec-run/1) without code: every Silicon run is mode b, with no
     * isolation run. [fakeVerify] picks the kv install ([kvInstall]). Returns the output of `task`.
     */
    private fun newRun(
        fakeVerify: Boolean = false,
        drafter: String = "opus",
        decisions: File? = null,
    ): String {
        val env =
            File(dir, "env.json").apply {
                writeText(
                    kv.json.jsonOf(
                        mapOf(
                            "kvInstall" to kvInstall(fakeVerify).path, "reqShield" to ReqShield.dir.path,
                            "classpath" to ReqShield.classpath.joinToString(File.pathSeparator), "kotlinc" to ReqShield.kotlincArgs.joinToString(" "),
                        ),
                    ),
                )
            }
        val config = File(dir, "config.json")
        config.writeText(File(protocol, "config.json").readText().replace("\"../../", "\"${repo.path}/").replace("\"SKELETON.kv\"", "\"${protocol.path}/SKELETON.kv\"").replace("\"LANGUAGE.md\"", "\"${protocol.path}/LANGUAGE.md\"").replace("\"code\": true", "\"code\": false"))
        decisions?.let { config.writeText(config.readText().replace("\"code\": false", "\"decisions\": [\"${it.path}\"],\n \"code\": false")) }
        return ok(spec("task", "--run", run.path, "--drafter", drafter, "--config", config.path, "--env", env.path))
    }

    private fun putDraft(
        text: String,
        name: String = "opus",
        from: String = "frozen-phase1/D-$name",
    ) {
        File(pilotDir, from).listFiles()!!.filter { it.name in setOf("COVERAGE.md", "QUESTIONS.md") }.forEach { it.copyTo(File(box(1, name), it.name), true) }
        File(box(1, name), "DRAFT.kv").writeText(text)
    }

    private val opusDraft get() = File(pilotDir, "frozen-phase1/D-opus/DRAFT.kv").readText()

    /** A decision file of an earlier run that leaves C16 open (kv-decisions/1). */
    private val earlierDecisions: File
        get() = File(dir, "earlier.json").apply { writeText("""{"format": "kv-decisions/1", "decisions": [{"group": "D9", "cells": {"C16": "open"}}]}""") }

    /** Gives the run the code after its cells ran without it (mode b, no isolation runs): `kv verify` runs from now on. */
    private fun withCode() {
        val runJson = File(run, "run.json")
        runJson.writeText(
            runJson.readText().replace("\"root\": null", "\"root\": \"${ReqShield.dir.path}\"")
                .replace("\"classpath\": null", "\"classpath\": \"${ReqShield.classpath.joinToString(File.pathSeparator)}\"").replace("\"kotlinc\": null", "\"kotlinc\": \"-language-version 2.0\""),
        )
    }

    /**
     * task, collect (phase 1), cells with the pilot cells, the opus answers, collect (phase 2), questions with groups.
     * The run is mode b (no code), where only an earlier decision makes a cell a question by rule 6: the run config
     * names [earlierDecisions], which leaves C16 open (D9, user decision UC1), so the pilot grouping's D9 is a question.
     */
    private fun pipeline(fakeVerify: Boolean = false) {
        newRun(fakeVerify, decisions = earlierDecisions)
        putDraft(opusDraft)
        ok(spec("collect", "--run", run.path, "--drafter", "opus"))
        ok(spec("cells", "--run", run.path, "--cells", pilotCells.path, "--notes", File(protocol, "pilot-cells-notes.md").path))
        specResource("answers/opus.json").copyTo(File(box(2), "ANSWERS.json"), true)
        ok(spec("collect", "--run", run.path, "--drafter", "opus"))
        ok(spec("questions", "--run", run.path, "--groups", File(repo, "specdraft/pilot-replay/groups-opus.json").path))
    }

    /** Words of a verification result or of the check report: none may reach a drafter in a correction round. */
    private val verificationWords =
        Regex("PROVEN|FAILED|INCOMPLETE|DETERMINED|OPEN|INFEASIBLE|verdict|verif|counts for nothing|Postcondition|might not hold|might fail|[Ff]ield fixing|stale|warning|Spec to review|isolation|Silicon|kv spec|module|cell")

    @Test
    fun `task - the phase-1 box holds exactly its five files, neutral texts, and no path of the repository`() {
        val out = newRun()
        assertEquals(setOf("TASK.md", "SOURCES.md", "LANGUAGE.md", "SKELETON.kv", "check.sh"), box(1).list()!!.toSet())
        assertEquals(File(pilotDir, "packet/SOURCES.md").readText(), File(box(1), "SOURCES.md").readText())
        assertEquals(File(protocol, "SKELETON.kv").readText(), File(box(1), "SKELETON.kv").readText())
        val task = File(box(1), "TASK.md").readText()
        assertTrue("The\n  specification is about the local lock, `KeyLocalLock`: its `tryLock`, its `unLock` and its cleanup monitor (`sweep`)." in task, task)
        assertTrue("You have at most 10 calls." in task, task)
        // DESIGN-C.md 10.6 finding 6: the language says that a record never issued has no fixed field values.
        assertTrue("So a quantified `invariant` or `lemma` over `tok(x)` needs `tok(x).gIssued` in its premise." in File(box(1), "LANGUAGE.md").readText())
        assertTrue("Read TASK.md in the directory ${box(1).path} and do the task" in out, out)
        for (f in box(1).listFiles()!!) {
            assertFalse(repo.path in f.readText(), "${f.name} names the repository")
            // DESIGN-C.md 2.1 observation 1: no text of the box but the team's own (SOURCES.md) and the code's field
            // name expiresAt speaks of expiry; the skeleton comment on gLease pulled drafters to `>=` in the pilot.
            if (f.name != "SOURCES.md") assertFalse(Regex("(?i)expir").containsMatchIn(f.readText().replace("expiresAt", "")), "${f.name} speaks of expiry")
        }
        val (again, msg) = spec("task", "--run", run.path, "--drafter", "opus")
        assertEquals(1, again, msg)
        assertTrue("--force builds it again" in msg, msg)
    }

    @Test
    fun `collect - the three pilot drafts pass, the assembled sidecar is the C0 fixture with the run's hole`() {
        newRun(drafter = "haiku")
        for (m in listOf("sonnet", "opus")) ok(spec("task", "--run", run.path, "--drafter", m))
        val hole = Regex("hole MI_INV \"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL)
        val signedHole = hole.find(File(pilotDir, "specs-signed/keylocallock.common.kv").readText())!!.value
        for (m in listOf("sonnet", "haiku", "opus")) {
            putDraft(File(pilotDir, "frozen-final/D-$m/DRAFT.kv").readText(), m, "frozen-final/D-$m")
            val out = ok(spec("collect", "--run", run.path, "--drafter", m))
            assertTrue("$m phase 1: collected after 0 correction round(s)" in out, out)
            val fixture = specResource("drafts/$m.kv").readText().split("\n", limit = 5)[4]
            assertEquals(fixture.replace(hole, Regex.escapeReplacement(signedHole)), File(run, "drafts/$m/sidecar/keylocallock.common.kv").readText(), m)
            assertFalse(box(2, m).exists())
        }
        // The first draft to pass gives the cells (DESIGN-C.md 10.4).
        assertEquals(listOf("sonnet", "haiku", "opus"), ((parseJson(File(run, "state.json").readText()) as Map<*, *>)["passed"] as List<*>))
    }

    @Test
    fun `collect - a correction round holds the error lines and nothing else, after two rounds the draft is dropped`() {
        newRun()
        // A type error, an unknown name in a clause, a broken source comment and a stale quote (not grammar: no line).
        val broken =
            opusDraft
                .replace("ensures sweep_adds_no_entry: !old(has(S, k)) ==> !has(S, k)", "ensures sweep_adds_no_entry: !old(has(S, k)) ==> undefinedName > 0")
                .replace("// source: [README.md#local-lock-map-size/1] \"keeps one entry per `(cache key, lock type)` it is currently\"\n  ensures sweep_adds_no_entry", "// source: [README.md#local-lock-map-size/1] keeps one entry\n  ensures sweep_adds_no_entry")
                .replace("// source: [README.md#local-lock-map-size/4] \"Keys whose entry is already in\"\n  // source: [README.md#local-lock-map-size/4] \"the map are not subject to the cap at all.\"\n  unbacked_only_if", "// source: [README.md#top/1] \"a quote from no block\"\n  unbacked_only_if")
        assertTrue(broken != opusDraft && broken.split("undefinedName").size == 2 && "a quote from no block" in broken)
        putDraft(broken)
        val lineOf = { s: String -> broken.lines().indexOfFirst { s in it } + 1 }
        val expected =
            listOf(
                "DRAFT.kv:${lineOf("[README.md#local-lock-map-size/1] keeps one entry")}: a source comment is // source: [ANCHOR] \"EXACT QUOTE\" or // source: none (REASON); the quote and the reason hold no double quote character",
                "DRAFT.kv:${lineOf("undefinedName")}:52: undeclared identifier `undefinedName` (in sweep_adds_no_entry)",
            )
        val (c1, o1) = spec("collect", "--run", run.path, "--drafter", "opus")
        assertEquals(1, c1, o1)
        val round1 = File(box(1), "CORRECTION-1.md").readText()
        assertEquals(
            "# Corrections, round 1 of 2\n\nDRAFT.kv has these grammar or type errors. Fix them in DRAFT.kv and change nothing else.\n\n" +
                expected.joinToString("\n") { "- $it" } + "\n",
            round1,
        )
        assertFalse(verificationWords.containsMatchIn(round1), round1)
        assertTrue("correction round 1 of 2" in o1 && "CORRECTION-1.md lists errors of DRAFT.kv" in o1, o1)
        // Round 2: one error fixed, one left.
        putDraft(broken.replace("// source: [README.md#local-lock-map-size/1] keeps one entry", "// source: none (no sentence)"))
        assertEquals(1, spec("collect", "--run", run.path, "--drafter", "opus").first)
        val round2 = File(box(1), "CORRECTION-2.md").readText()
        assertEquals("- ${expected[1]}", round2.lines().filter { it.startsWith("- ") }.single())
        assertFalse(verificationWords.containsMatchIn(round2), round2)
        // Still broken after two rounds: dropped, no third round, and collect refuses it from then on.
        val (c3, o3) = spec("collect", "--run", run.path, "--drafter", "opus")
        assertEquals(1, c3, o3)
        assertTrue("opus phase 1: DROPPED after 2 correction round(s)" in o3, o3)
        assertFalse(File(box(1), "CORRECTION-3.md").exists())
        assertFalse(File(run, "drafts/opus").exists())
        assertTrue("was dropped" in spec("collect", "--run", run.path, "--drafter", "opus").second)
    }

    @Test
    fun `collect - a fixed draft passes after a correction round`() {
        newRun()
        putDraft(opusDraft.replace("  t1 := tryLock(k1, now = a", "  t1 = tryLock(k1, now = a"))
        File(box(1), "COVERAGE.md").delete()
        assertEquals(1, spec("collect", "--run", run.path, "--drafter", "opus").first)
        val lines = File(box(1), "CORRECTION-1.md").readText().lines().filter { it.startsWith("- ") }
        assertEquals(listOf("- COVERAGE.md is missing: write it as TASK.md says", "- DRAFT.kv:187:6: error[syntax]: expected ':='"), lines)
        putDraft(opusDraft)
        val out = ok(spec("collect", "--run", run.path, "--drafter", "opus"))
        assertTrue("opus phase 1: collected after 1 correction round(s)" in out, out)
        assertEquals(opusDraft, File(run, "drafts/opus/DRAFT.kv").readText())
    }

    @Test
    fun `check_sh - error lines only, the calls counted outside the box, refused after the budget`() {
        newRun()
        putDraft(opusDraft.replace("  free t: Token = param token\n", "  free t: Token = param tok\n"))
        val (code, out) = exec("./check.sh", cwd = box(1))
        assertEquals(1, code, out)
        assertEquals(
            listOf(
                "DRAFT.kv: line 62 of SKELETON.kv is missing, changed or out of order: free t: Token = param token",
                "check.sh: 1 grammar or type error in DRAFT.kv.",
                "(check.sh call 1 of 10 used)",
            ),
            out.trim().lines(),
        )
        val harness = File(run, "harness/opus/phase1")
        assertEquals("1", File(harness, "check.count").readText().trim())
        assertTrue(File(harness, "calls/1/DRAFT.kv").exists())
        putDraft(opusDraft)
        assertEquals(listOf("check.sh: no grammar or type errors in DRAFT.kv.", "(check.sh call 2 of 10 used)"), ok(exec("./check.sh", cwd = box(1))).trim().lines())
        File(harness, "check.count").writeText("10\n")
        val (refused, msg) = exec("./check.sh", cwd = box(1))
        assertEquals(3, refused, msg)
        assertTrue(msg.startsWith("BUDGET EXHAUSTED"), msg)
    }

    @Test
    fun `cells and phase 2 - the box holds the cells and the form but no file of the draft, the answers are collected`() {
        newRun()
        putDraft(opusDraft)
        ok(spec("collect", "--run", run.path, "--drafter", "opus"))
        val out = ok(spec("cells", "--run", run.path, "--cells", pilotCells.path, "--notes", File(protocol, "pilot-cells-notes.md").path))
        assertTrue("  draft opus: mode b 19 DETERMINED, 0 OPEN, 0 INFEASIBLE" in out, out)
        assertTrue("phase-2 box size: 19 cells (2 boundary)" in out, out)
        assertEquals(setOf("TASK.md", "SOURCES.md", "LANGUAGE.md", "SCENARIOS.md", "ANSWERS.json", "check.sh"), box(2).list()!!.toSet())
        // Phase 2 does not edit the draft: the phase-1 box is read-only, the phase-2 box has none of its files.
        for (f in box(1).listFiles()!!) assertFalse(f.canWrite(), "${f.name} of the phase-1 box is writable")
        val scenarios = File(box(2), "SCENARIOS.md").readText()
        assertEquals(19, scenarios.lines().count { Regex("""^\| C\d\d \|""").containsMatchIn(it) })
        assertTrue(scenarios.startsWith("# Scenarios\n\nAll scenarios use one local lock with `lockTimeoutMillis = 10`"), scenarios)
        assertFalse(Regex("boundary|DETERMINED|OPEN|rule").containsMatchIn(scenarios), scenarios)
        assertTrue("| C04 | Acquired at time 0, not released: tryLock(k) at time 10 (the lease end) | `null`: `r == NULL`<br>`token`: `r != NULL` |" in scenarios, scenarios)
        // The empty form fails the form check (every status is missing); the pilot answers pass it.
        val (formCode, form) = exec("./check.sh", cwd = box(2))
        assertEquals(1, formCode, form)
        assertTrue(form.lines().first().startsWith("ANSWERS.json: C01 has status None, not one of settled, assumed, open"), form)
        assertFalse(verificationWords.containsMatchIn(form.replace("cell", "")), form)
        val answers = specResource("answers/opus.json").readText()
        File(box(2), "ANSWERS.json").writeText(answers.replace("\"outcome\": \"stays\"", "\"outcome\": \"kept\""))
        assertEquals(1, spec("collect", "--run", run.path, "--drafter", "opus").first)
        assertTrue("- ANSWERS.json: C14 names outcome 'kept'; cell C14 has stays, dropped" in File(box(2), "CORRECTION-1.md").readText())
        File(box(2), "ANSWERS.json").writeText(answers)
        // A phase-1 draft changed after collection is reported; the frozen draft stays in use.
        File(box(1), "DRAFT.kv").apply { setWritable(true) }.appendText("\n// changed in phase 2\n")
        val collected = ok(spec("collect", "--run", run.path, "--drafter", "opus"))
        assertTrue("opus phase 2: collected after 1 correction round(s), 1 check.sh call(s): 14 settled, 3 assumed, 3 open" in collected, collected)
        assertTrue("NOTE: the phase-1 DRAFT.kv of opus changed after collection" in collected, collected)
        assertEquals(opusDraft, File(run, "drafts/opus/DRAFT.kv").readText())
        val cost = parseJson(File(run, "cells/cost.json").readText()) as Map<*, *>
        assertEquals(19L, (cost["cells"] as Number).toLong())
    }

    @Test
    fun `questions --run - one draft, rule 1 open or assumed - the 6 opus cells and C16 by rule 6, the bundle and the options of Q-D1`() {
        // The run config names a decision file of an earlier run (DESIGN-C.md 10.6 finding 3). In mode b, rule 6 takes
        // it alone: the reference's OPEN cells (C05, C16, C19 here) count in mode a only.
        pipeline()
        assertTrue("\"decisions\": [\n  \"${File(dir, "earlier.json").path}\"\n ]" in File(run, "run.json").readText(), File(run, "run.json").readText())
        val out = ok(spec("questions", "--run", run.path))
        assertTrue("kv spec questions: 1 draft(s) (opus), mode b; rule 1: the author answered open or assumed; rule 2 off (one draft); rule 4 on (proxy: --reference); rule 6 on (1 cell(s) that earlier decisions leave open)" in out, out)
        assertEquals("question cells: 7 C04 C05 C09 C12 C15 C16 C19", out.lines().single { it.startsWith("question cells:") })
        assertTrue("  C16 rules 6 |" in out, out)
        assertTrue("group check: OK, 5 group(s)" in out, out)
        val bundle = File(run, "bundle.md").readText()
        assertTrue("A. C04 null, C15 stays: reference.\nB. C04 token, C15 dropped: draft opus." in bundle, bundle)
        assertTrue("Results per option on implementations (decision mutants, AI code): not measured." in bundle, bundle)
        val q = parseJson(File(run, "questions.json").readText()) as Map<*, *>
        val d1 = (q["groups"] as List<*>).map { it as Map<*, *> }.single { it["id"] == "D1" }
        assertEquals(listOf(mapOf("C04" to "null", "C15" to "stays"), mapOf("C04" to "token", "C15" to "dropped")), (d1["options"] as List<*>).map { (it as Map<*, *>)["cells"] })
    }

    /** Words of a verdict or a table: none may reach the alignment agent in FEEDBACK (DESIGN-C.md 10.6 finding 5). */
    private val verdictWords = Regex("PROVEN|FAILED|INCOMPLETE|DETERMINED|OPEN|INFEASIBLE|verdict|counts for nothing|isolation|Silicon|kv spec|kv verify|reference|module|mode [ab]|(?m)^\\|")

    private fun feedback(n: Int) = File(run, "align/box/FEEDBACK-$n.md").readText()

    private fun feedbackLines(n: Int) = feedback(n).lines().filter { it.startsWith("- ") }

    /** The pilot's aligned opus draft without its monitor-duty clause and that clause's source comments. */
    private val alignedWithoutMonitorDuty: String get() {
        val aligned = File(pilotDir, "aligned/opus.kv").readText()
        return Regex("""(?m)^(  // source:.*\n)*  ensures progress_sweep_drops_expired:.*\n(    .*\n)*\n?""").replace(aligned, "").also { assertTrue("progress_sweep" !in it && it.length < aligned.length - 200) }
    }

    private val c16 = "- Scenario C16 (Acquired at time 0: a monitor pass at time 11 looks at k): no clause may decide its outcome; it must stay undecided."

    @Test
    fun `align - the box holds the decisions, the check accepts the aligned draft, not an undecided or other cell, nor a cell decided open`() {
        pipeline()
        val decisions = File(repo, "specdraft/pilot-replay/decisions-opus.json")
        ok(spec("align", "--run", run.path, "--decisions", decisions.path))
        val alignBox = File(run, "align/box")
        assertEquals(setOf("TASK.md", "DRAFT.kv", "DECISIONS.md", "SOURCES.md", "LANGUAGE.md", "SKELETON.kv", "check.sh"), alignBox.list()!!.toSet())
        val md = File(alignBox, "DECISIONS.md").readText()
        assertTrue("| C04 | Acquired at time 0, not released: tryLock(k) at time 10 (the lease end) | `null`: `r == NULL` |" in md, md)
        assertTrue("| C09 | Acquired at time 0: unLock(k, t1) at time 5 (the steps of C08) | `true`: `b` |" in md, md)
        // D9 is decided open (DESIGN-C.md 10.6 finding 3): no outcome, and the box says that no clause may decide it.
        assertTrue("| C16 | Acquired at time 0: a monitor pass at time 11 looks at k | left open: no clause may decide it |" in md, md)
        assertTrue("A scenario whose chosen outcome is \"left open\" must stay undecided" in md, md)
        assertTrue("If DECISIONS.md leaves a\n   scenario open, no clause may decide its outcome" in File(alignBox, "TASK.md").readText())
        // The run's decisions with every cell, as later runs take them (--decisions-ref).
        val resolved = parseJson(File(run, "align/decisions.json").readText()) as Map<*, *>
        assertEquals("kv-decisions/1", resolved["format"])
        assertEquals(mapOf("C16" to "open"), (resolved["decisions"] as List<*>).map { it as Map<*, *> }.single { it["group"] == "D9" }["cells"])
        // A later run takes it as earlier decisions: without a reference, rule 6 asks about C16 again.
        val later = spec("questions", "--check", "opus=${File(run, "drafts/opus/check/check.json")}", "--answers", "opus=${File(run, "drafts/opus/answers.json")}", "--decisions-ref", File(run, "align/decisions.json").path)
        assertTrue("  C16 rules 6 |" in ok(later), later.second)
        // The pilot's aligned opus draft decides the 6 cells as decided, and decides C16, which D9 leaves open.
        File(pilotDir, "aligned/opus.kv").copyTo(File(alignBox, "DRAFT.kv"), true)
        val (c0, monitor) = spec("align", "--run", run.path, "--check")
        assertEquals(1, c0, monitor)
        assertTrue("decided cells: all 6 DETERMINED as decided in mode(s) b" in monitor, monitor)
        assertTrue("cells decided open: 1 of 1 NOT OPEN (modes b):\n    C16 mode b: dropped, not open" in monitor, monitor)
        assertTrue("decision probes (DESIGN-C.md 10 UC5): missing decision_D1, decision_D4, decision_D2, decision_D5\n" in monitor, monitor)
        assertEquals(listOf(c16), feedbackLines(1))
        assertFalse(verdictWords.containsMatchIn(feedback(1)), feedback(1))
        assertTrue("loop-back: 1 line(s), round 1 of 2" in monitor && "FEEDBACK-1.md lists problems of DRAFT.kv" in monitor, monitor)
        // Without its monitor-duty clause, the draft leaves C16 open and passes.
        File(alignBox, "DRAFT.kv").writeText(alignedWithoutMonitorDuty)
        val good = spec("align", "--run", run.path, "--check").let { (c, o) -> assertEquals(0, c, o + File(run, "align/check/report.txt").readText()); o }
        assertTrue("decided cells: all 6 DETERMINED as decided in mode(s) b" in good, good)
        assertTrue("cells decided open: all 1 OPEN in mode(s) b" in good, good)
        assertFalse("loop-back" in good, good)
        // The unaligned draft decides C04 and C15 the other way, and C16: FEEDBACK states each as its scenario and the
        // decided outcome, in the order of DECISIONS.md.
        File(pilotDir, "frozen-phase1/D-opus/DRAFT.kv").copyTo(File(alignBox, "DRAFT.kv"), true)
        val (c1, other) = spec("align", "--run", run.path, "--check")
        assertEquals(1, c1, other)
        assertTrue("    C04 mode b: token, not null\n    C15 mode b: dropped, not stays" in other, other)
        assertEquals(
            listOf(
                "- Scenario C04 (Acquired at time 0, not released: tryLock(k) at time 10 (the lease end)): the clauses must give the outcome `null`: `r == NULL`.",
                "- Scenario C15 (Acquired at time 0: a monitor pass at time 10 looks at k): the clauses must give the outcome `stays`: `carried(S, k, t1)`.",
                c16,
            ),
            feedbackLines(2),
        )
        assertFalse(verdictWords.containsMatchIn(feedback(2)), feedback(2))
        // Without the two tryLock clauses on a live and an expired entry, C05 and C19 are OPEN, which is no decision.
        // C04 stays null: the allowance clause alone forbids ending t1 at its lease. Both rounds are used: no FEEDBACK-3.
        val open = Regex("""(?m)^  ensures (tryLock_live_entry_returns_null|progress_tryLock_takeover):.*\n(?:      .*\n)*""").replace(alignedWithoutMonitorDuty, "")
        assertTrue(open.length < alignedWithoutMonitorDuty.length - 200)
        File(alignBox, "DRAFT.kv").writeText(open)
        val (c2, undecided) = spec("align", "--run", run.path, "--check")
        assertEquals(1, c2, undecided)
        assertTrue("decided cells: 2 of 6 NOT as decided (modes b):\n    C05 mode b: open, not token\n    C19 mode b: open, not backed\n" in undecided, undecided)
        assertTrue("loop-back: none left: the 2 feedback rounds are used" in undecided, undecided)
        assertFalse(File(alignBox, "FEEDBACK-3.md").exists())
        // Decision files: a group left out, an option the group does not have.
        val text = decisions.readText()
        val noD5 = File(dir, "noD5.json").apply { writeText(text.replace(Regex(""",\n  \{"group": "D5".*\n"""), ",\n")) }
        val (e1, m1) = spec("align", "--run", run.path, "--decisions", noD5.path, "--force")
        assertEquals(2, e1, m1)
        assertTrue("no decision for group(s) D5" in m1, m1)
        val badOption = File(dir, "badOption.json").apply { writeText(text.replace("\"option\": \"A\"", "\"option\": \"C\"")) }
        assertTrue("group D1 has no option 'C'" in spec("align", "--run", run.path, "--decisions", badOption.path, "--force").second)
    }

    /**
     * DESIGN-C.md 10.6 findings 2 and 5: with the code, `align --check` fails when `kv verify --draft` on the reference
     * is not PROVEN, although every decided cell is as decided. FEEDBACK holds the error lines of the report and
     * nothing else, with positions in DRAFT.kv (the assembled file moves source lines and holds the run's hole), at
     * most 2 rounds. `kv verify` is the fake of [kvInstall].
     */
    @Test
    fun `align --check - a verification failure fails the check and goes to FEEDBACK as error lines in DRAFT_kv positions`() {
        pipeline(fakeVerify = true)
        withCode()
        ok(spec("align", "--run", run.path, "--decisions", File(repo, "specdraft/pilot-replay/decisions-opus.json").path))
        val draft = alignedWithoutMonitorDuty.replace("// ---- Inductive invariant", "// FAKE: verify fails\n// ---- Inductive invariant")
        assertTrue("FAKE: verify fails" in draft)
        File(run, "align/box/DRAFT.kv").writeText(draft)
        val (code, out) = spec("align", "--run", run.path, "--check")
        assertEquals(1, code, out)
        assertTrue("decided cells: all 6 DETERMINED as decided in mode(s) b" in out && "cells decided open: all 1 OPEN in mode(s) b" in out, out)
        assertTrue("kv verify --draft on the reference implementation: FAILED (sweep sweep_keeps_live_entry; init FAILED; probe FAILED)" in out, out)
        val lineIn = { text: String, s: String -> text.lines().indexOfFirst { s in it } + 1 }
        val assembled = File(run, "align/sidecar/keylocallock.common.kv").readText()
        val (sweep, assert) = lineIn(draft, "ensures sweep_keeps_live_entry") to lineIn(draft, "  assert ")
        // In the assembled file, the source lines move the clause up, the source lines and the run's hole move the probe.
        assertTrue(lineIn(assembled, "ensures sweep_keeps_live_entry") < sweep && lineIn(assembled, "  assert ") != assert)
        val lines =
            listOf(
                "- sweep: Postcondition of sweep might not hold: sidecar sweep_keeps_live_entry DRAFT.kv:$sweep:3",
                "- init: Postcondition of init might not hold: generated MI (incomplete quantifiers)",
                "- probe: Assert might fail: sidecar probe assert DRAFT.kv:$assert:3",
            )
        assertEquals(
            "# Feedback, round 1 of 2\n\nThe team's tool found these problems in DRAFT.kv. Fix them in DRAFT.kv. Change only what the fixes need, " +
                "and keep following TASK.md and DECISIONS.md.\n\n" + lines.joinToString("\n") + "\n",
            feedback(1),
        )
        assertFalse(verdictWords.containsMatchIn(feedback(1)), feedback(1))
        assertTrue("prompt for the alignment agent:\nIn the directory ${File(run, "align/box").path}, FEEDBACK-1.md lists problems of DRAFT.kv." in out, out)
        assertTrue(" \"feedback\": 1\n" in File(run, "align/result.json").readText())
        // Not fixed: round 2 with the same lines, then no round left.
        assertEquals(1, spec("align", "--run", run.path, "--check").first)
        assertEquals(lines, feedbackLines(2))
        val (c3, o3) = spec("align", "--run", run.path, "--check")
        assertEquals(1, c3, o3)
        assertTrue("loop-back: none left: the 2 feedback rounds are used" in o3, o3)
        assertFalse(File(run, "align/box/FEEDBACK-3.md").exists())
        // Fixed: PROVEN, and the check passes.
        File(run, "align/box/DRAFT.kv").writeText(alignedWithoutMonitorDuty)
        val fixed = ok(spec("align", "--run", run.path, "--check"))
        assertTrue("kv verify --draft on the reference implementation: PROVEN" in fixed, fixed)
    }

    @Test
    fun `outcomes - every implementation runs on each option draft with --draft, and the rows go into the bundle`() {
        pipeline(fakeVerify = true)
        withCode()
        val impls = listOf("T-sonnet-1", "T-sonnet-4").flatMap { listOf("--impl", "$it=${File(repo, "aicode/round2/impl/$it")}") }
        val args = listOf("outcomes", "--run", run.path, "--group", "D1", "--mutants", File(protocol, "decision-mutants.json").path) + impls
        val out = ok(spec(*(args + listOf("--option", "A=${File(pilotDir, "aligned/opus.kv")}", "--option", "B=${File(run, "drafts/opus/DRAFT.kv")}")).toTypedArray()))
        assertTrue("option A (C04 null, C15 stays): the draft decides the cells so (mode b)" in out, out)
        val rows = out.lines().filter { it.startsWith("| ") && "Implementation" !in it }.associate { it.split(" | ")[0].removePrefix("| ") to it.split(" | ").drop(1).map { c -> c.removeSuffix(" |") } }
        val failed = "FAILED (tryLock ends_only_if tryLock_ends_only_expired)"
        assertEquals(listOf("PROVEN", failed), rows["reference"])
        assertEquals(listOf(failed, "PROVEN"), rows["dm:D1-both"])
        assertEquals(listOf(failed, "PROVEN"), rows["impl:T-sonnet-1"])
        assertEquals(listOf(failed, "PROVEN"), rows["impl:T-sonnet-4"])
        assertEquals(listOf("PROVEN", failed), rows["dm:D9-sweep-never"])
        // 2 options x (reference, 5 decision mutants, 2 implementations), each with --draft; the reference is the
        // checkout itself, a mutant root has its edits, and T-sonnet-4's binding adaptation is in its sidecar.
        val log = File(dir, "verify.log").readLines().map { parseJson(it) as Map<*, *> }
        assertEquals(16, log.size)
        assertTrue(log.all { "--draft" in (it["args"] as List<*>) })
        assertEquals(2, log.count { it["root"] == ReqShield.dir.path })
        assertEquals(listOf("class LockInfo = LockInfo {"), log.filter { "impl-T-sonnet-4" in (it["root"] as String) }.flatMap { it["classes"] as List<*> }.distinct())
        assertEquals(listOf("class LockInfo = KeyLocalLock.LockInfo {"), log.filter { "impl-T-sonnet-1" in (it["root"] as String) }.flatMap { it["classes"] as List<*> }.distinct())
        val dm = File(run, "outcomes/D1/roots/dm-D1-both/core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine/KeyLocalLock.kt").readText()
        assertTrue("if (now >= lockInfo.expiresAt) {" in dm && "if (now >= existing.expiresAt && existing.isHeld" in dm)
        val bundle = File(run, "bundle.md").readText()
        assertTrue("| impl:T-sonnet-1 | $failed | PROVEN |" in bundle, bundle)
        // An option draft that does not decide the cells as its option is not run.
        File(dir, "verify.log").delete()
        val (code, swapped) = spec(*(args + listOf("--option", "A=${File(run, "drafts/opus/DRAFT.kv")}")).toTypedArray())
        assertEquals(1, code, swapped)
        assertTrue("option A (C04 null, C15 stays): NOT RUN: C04 mode b: token, not null; C15 mode b: dropped, not stays" in swapped, swapped)
        assertFalse(File(dir, "verify.log").exists())
    }
}
