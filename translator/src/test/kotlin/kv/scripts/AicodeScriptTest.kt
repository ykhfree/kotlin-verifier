package kv.scripts

import kv.ReqShield
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The deterministic parts of the AI-written-code harness (scripts/aicode.py, AI-CODE.md 3-6): the sandbox files, the
 * sandbox compile.sh (the real compiler), the token similarity, the binding adaptations and the hole task's verify.sh.
 * `kv verify` is a fake that prints a canned report; `kv hole check` is the real CLI. The real runs (Silicon, the
 * req-shield tests) are the dry run in ../aicode/dryrun.
 */
class AicodeScriptTest {
    @TempDir
    lateinit var dir: File

    private val script = File(System.getProperty("kv.scriptsDir"), "aicode.py")
    private val specDir = File(System.getProperty("kv.specDir"), "reqshield")
    private val aiCode = File(File(System.getProperty("kv.specDir")).parentFile, "AI-CODE.md")
    private val original = File(ReqShield.coroutineDir, "KeyLocalLock.kt").readText()
    private val sb1 = original.replace("existing.token == token && ", "")
    private val work get() = File(dir, "work")
    private val results get() = File(dir, "results")

    private val failedReport =
        """
        |com.linecorp.cse.reqshield.kotlin.coroutine.KeyLocalLock.Companion (keylocallock.coroutine.kv)    module: FAILED (Silicon 4.61 s)
        |  action tryLock  KeyLocalLock.kt:155:9   PROVEN
        |  action unLock   KeyLocalLock.kt:209:9   FAILED (property)
        |      Postcondition of unLock might not hold: sidecar ends_only_if P3a keylocallock.common.kv:80:3 (incomplete quantifiers)
        |  action sweep    KeyLocalLock.kt:97:37   PROVEN
        |  init PROVEN · lemma P3c PROVEN · probe PROVEN
        |Spec to review: none
        |Unsupported: none
        |
        """.trimMargin()
    private val provenReport = failedReport.replace("module: FAILED", "module: PROVEN").replace("FAILED (property)", "PROVEN").lines().filter { "Postcondition" !in it }.joinToString("\n")

    /** Runs COMMAND; returns the exit code and the merged output. */
    private fun run(vararg command: String): Pair<Int, String> {
        val p = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(300, TimeUnit.SECONDS)) { "${command.toList()} did not finish" }
        return p.exitValue() to out
    }

    private fun aicode(vararg args: String): Pair<Int, String> = run("python3", "-I", script.path, "--env", File(dir, "env.json").path, *args)

    /**
     * A fake kv install. `kv hole ...` runs the real CLI. `kv verify` prints [failedReport] (exit 1), or [provenReport]
     * (exit 0) if the hole holds the text MARK-PROVEN or the root's KeyLocalLock.kt still checks the token. In mode
     * "binding" it fails to bind (exit 4) until the sidecar says `local currentTime`, and then proves. Every call is
     * logged to kv.log.
     */
    private fun fakeKv(mode: String): File {
        val install = File(dir, "kvinstall")
        File(install, "lib/translator.jar").apply { parentFile.mkdirs() }.writeText("fake")
        File(dir, "cp.txt").writeText(System.getProperty("java.class.path"))
        File(dir, "failed.report").writeText(failedReport)
        File(dir, "proven.report").writeText(provenReport)
        val java = File(System.getProperty("java.home"), "bin/java").path
        File(install, "bin/kv").apply {
            parentFile.mkdirs()
            writeText(
                """
                |#!/usr/bin/env python3
                |import os, subprocess, sys
                |d = "${dir.path}"
                |with open(os.path.join(d, "kv.log"), "a") as f:
                |    f.write(" ".join(sys.argv[1:3]) + "\n")
                |if sys.argv[1] == "hole":
                |    sys.exit(subprocess.call(["$java", "-cp", open(os.path.join(d, "cp.txt")).read(), "kv.cli.MainKt"] + sys.argv[1:]))
                |common = open(os.path.join(os.path.dirname(sys.argv[-1]), "keylocallock.common.kv")).read()
                |if "$mode" == "binding" and "= local currentTime" not in common:
                |    sys.stderr.write(os.path.dirname(sys.argv[-1]) + "/keylocallock.common.kv:56:27: error[unresolved]: no local 'now' is visible at the compute call at 155:9\n")
                |    sys.exit(4)
                |impl = os.path.join(sys.argv[sys.argv.index("--root") + 1], "$IMPL")
                |checks_token = os.path.exists(impl) and "existing.token == token" in open(impl).read()
                |proven = "$mode" == "binding" or "MARK-PROVEN" in common or checks_token
                |sys.stdout.write(open(os.path.join(d, "proven.report" if proven else "failed.report")).read())
                |sys.exit(0 if proven else 1)
                |
                """.trimMargin(),
            )
            setExecutable(true)
        }
        return install
    }

    /** Writes env.json, as `./gradlew aicodeEnv` does, with [kvInstall] as kv and the temp dir's work and results. */
    private fun env(kvInstall: File = File(dir, "no-kv")) {
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val values =
            mapOf(
                "kv" to File(kvInstall, "bin/kv").path,
                "kvInstall" to kvInstall.path,
                "reqShield" to ReqShield.dir.path,
                "classpath" to System.getProperty("kv.reqShieldClasspath"),
                "kotlinc" to System.getProperty("kv.reqShieldKotlincArgs"),
                "compilerClasspath" to System.getProperty("kv.compilerClasspath"),
                "specs" to specDir.path,
                "aiCode" to aiCode.path,
                "results" to results.path,
                "work" to work.path,
            )
        File(dir, "env.json").writeText(values.entries.joinToString(",\n", "{\n", "\n}\n") { (k, v) -> "  ${q(k)}: ${q(v)}" })
    }

    /** A sandbox [id] of [kind] whose generator wrote [text], collected. */
    private fun collected(
        id: String,
        text: String,
        kind: String = "template",
    ) {
        assertEquals(0, aicode("sandbox", id, "--kind", kind).first)
        File(work, "sandboxes/$id/KeyLocalLock.kt").writeText(text)
        val report = File(dir, "report.txt").apply { writeText("the generator's report") }
        val (code, out) = aicode("collect", id, "--model", "fake", "--report", report.path)
        assertEquals(0, code, out)
    }

    /** The JSON text of a field of implementation [id] in results.jsonl, following [keys]. */
    private fun field(
        id: String,
        vararg keys: String,
    ): String {
        val program = "import json, sys\nfor l in open(sys.argv[1]):\n    r = json.loads(l)\n    if r['id'] == sys.argv[2]:\n" +
            "        for k in sys.argv[3:]:\n            r = r[k]\n        print(json.dumps(r, sort_keys=True))\n"
        val (code, out) = run("python3", "-I", "-c", program, File(results, "results.jsonl").path, id, *keys)
        check(code == 0) { out }
        return out.trim()
    }

    private fun kvLog(): List<String> = File(dir, "kv.log").takeIf { it.exists() }?.readLines() ?: emptyList()

    @Test
    fun `a template sandbox holds exactly its five files, a free one has no RULES_md, and the copied texts are verbatim`() {
        env()
        assertEquals(0, aicode("sandbox", "T", "--kind", "template").first)
        assertEquals(0, aicode("sandbox", "F", "--kind", "free").first)
        val t = File(work, "sandboxes/T")
        val f = File(work, "sandboxes/F")
        assertEquals(setOf("SPEC.md", "KeyLock.kt", "HELPERS.md", "compile.sh", "RULES.md"), t.list()!!.toSet())
        assertEquals(setOf("SPEC.md", "KeyLock.kt", "HELPERS.md", "compile.sh"), f.list()!!.toSet())
        assertTrue(File(t, "compile.sh").canExecute())
        // RULES.md and the prompt are the two text blocks of AI-CODE.md 4.1, byte for byte.
        val section = aiCode.readText().substringAfter("### 4.1").substringBefore("### 4.2")
        val (prompt, rules) = Regex("```text\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(section).map { it.groupValues[1] }.toList()
        assertEquals(rules, File(t, "RULES.md").readText())
        assertEquals(prompt.replace("<SANDBOX>", t.path), File(work, "state/T/prompt.txt").readText())
        // AI-CODE.md 4.2: the free prompt does not mention RULES.md and ends with one more sentence.
        val free = File(work, "state/F/prompt.txt").readText()
        assertTrue("RULES.md" !in free && f.path in free && free.endsWith("did not settle. Write production-quality code.\n"), free)
        // SPEC.md holds README.md 104-105 and 113-149 and CLAUDE.md 24 verbatim, and names the ranges.
        val spec = File(t, "SPEC.md").readText()
        val readme = File(ReqShield.dir, "README.md").readLines()
        for (range in listOf(103..104, 112..148)) assertTrue(readme.slice(range).joinToString("\n") in spec, "README lines ${range.first + 1}-${range.last + 1}")
        assertTrue(File(ReqShield.dir, "CLAUDE.md").readLines()[23] in spec)
        assertTrue("README.md lines 104-105" in spec && "README.md lines 113-149" in spec && "CLAUDE.md line 24" in spec, spec)
        assertEquals(File(ReqShield.coroutineDir, "KeyLock.kt").readText(), File(t, "KeyLock.kt").readText())
        val helpers = File(t, "HELPERS.md").readText()
        for (s in listOf("fun nowToEpochTime()", "fun rejectsNewEntry(currentSize: Long): Boolean\n", "const val LOCK_MONITOR_INTERVAL_MILLIS = 1000L", "import org.slf4j.LoggerFactory")) {
            assertTrue(s in helpers, s)
        }
        // No sandbox file names the verifier's command, a sidecar, this repository or the req-shield checkout.
        for (file in t.listFiles()!! + f.listFiles()!!) {
            for (word in listOf("kv verify", ".kv", "sidecar", "kotlin-verifier", ReqShield.dir.path)) assertFalse(word in file.readText(), "${file.name} names $word")
        }
        assertEquals("\"template\"", field("T", "kind"))
        // A second build of an existing sandbox needs --force.
        assertEquals(1, aicode("sandbox", "T", "--kind", "template").first)
    }

    @Test
    fun `compile_sh prints only compiler diagnostics, counts its calls and refuses a call over its budget`() {
        env()
        assertEquals(0, aicode("sandbox", "C", "--kind", "template").first)
        val box = File(work, "sandboxes/C")
        val state = File(work, "state/C")
        val compile = File(box, "compile.sh").path
        val broken = original.replace("existing.token == token && ", "existing.tokn == token && ")
        File(box, "KeyLocalLock.kt").writeText(broken)
        val (exit1, out1) = run(compile)
        assertEquals(1, exit1, out1)
        val lines = out1.trimEnd().lines()
        assertEquals("compile.sh: KeyLocalLock.kt does not compile (call 1 of 10 used)", lines.last())
        val diagnostic = Regex("""^/.*/KeyLocalLock\.kt:\d+:\d+: (error|warning): .+""")
        assertTrue(diagnostic.matches(lines.first()) && "unresolved reference 'tokn'" in lines.first(), out1)
        // Every other line is a diagnostic, the source line it quotes, or its caret line.
        val source = broken.lines().toSet()
        for (l in lines.dropLast(1)) assertTrue(diagnostic.matches(l) || l in source || Regex("""^\s*\^+\s*$""").matches(l), "not a diagnostic line: $l")
        File(box, "KeyLocalLock.kt").writeText(original)
        val (exit2, out2) = run(compile)
        assertEquals(0, exit2, out2)
        assertEquals("compile.sh: KeyLocalLock.kt compiles (call 2 of 10 used)", out2.trim())
        assertEquals(listOf("1.kt", "2.kt"), File(state, "calls").list()!!.sorted())
        // Over the budget: no compile, and the count stays.
        File(state, "compile.count").writeText("10\n")
        val (exit3, out3) = run(compile)
        assertEquals(3, exit3, out3)
        assertEquals("BUDGET EXHAUSTED: all 10 compile.sh calls are used. Stop and report.", out3.trim())
        assertEquals("10", File(state, "compile.count").readText().trim())
        assertFalse(File(state, "calls/11.kt").exists())
        // collect records the calls and compiles the final file once more, without counting that compile.
        val report = File(dir, "report.txt").apply { writeText("compiles") }
        assertEquals(0, aicode("collect", "C", "--model", "m", "--report", report.path).first)
        assertEquals("true", field("C", "compiled"))
        assertEquals("10", field("C", "compile_calls"))
        assertEquals("10", File(state, "compile.count").readText().trim())
        assertEquals(original, File(results, "impl/C/KeyLocalLock.kt").readText())
    }

    @Test
    fun `token similarity is 1 for the same tokens, high but below 1 for renamed identifiers, and low for other code`() {
        val a = File(dir, "a.kt").apply { writeText(original) }

        fun similarity(other: String): Double {
            val (code, out) = run("python3", "-I", script.path, "similarity", "--pair", a.path, File(dir, "b.kt").apply { writeText(other) }.path)
            check(code == 0) { out }
            return out.trim().toDouble()
        }
        assertEquals(1.0, similarity(original))
        // Comments and whitespace do not count; Kotlin block comments nest.
        assertEquals(1.0, similarity(original.replace("\n", "\n\n").replace("// Only the current owner may release", "// a new comment") + "/* a /* nested */ comment */\n"))
        val renamed = similarity(original.replace("lockMap", "locks").replace("completeKey", "mapKey"))
        assertTrue(renamed >= 0.9 && renamed < 1.0, "$renamed")
        // A string literal is one token, so a changed token text lowers the ratio.
        assertTrue(similarity(original.replace("\"local-\${tokenSequence.incrementAndGet()}\"", "\"lock-\${tokenSequence.incrementAndGet()}\"")) < 1.0)
        val other = similarity(File(ReqShield.coroutineDir, "ReqShield.kt").readText())
        assertTrue(other < 0.5, "$other")
    }

    @Test
    fun `binding adaptations change only the named local or source path, are counted, and keep the first binding failure`() {
        env(fakeKv("binding"))
        // The generator named the clock local currentTime instead of now (tryLock only).
        val tryLock = original.substringAfter("override suspend fun tryLock").substringBefore("override suspend fun unLock")
        val renamed =
            original.replace(
                tryLock,
                tryLock.replace("val now = nowToEpochTime()", "val currentTime = nowToEpochTime()").replace("now > existing", "currentTime > existing")
                    .replace("now + lockTimeoutMillis", "currentTime + lockTimeoutMillis"),
            )
        collected("B", renamed, kind = "free")
        assertEquals(0, aicode("tool", "B").first)
        assertTrue("no local 'now' is visible" in field("B", "tool", "binding_failure"))
        assertEquals("\"binding failure\"", field("B", "final_verdict"))
        val bindings = File(results, "impl/B/bindings.txt")
        bindings.writeText("# AI-CODE.md 6.1\nlocal tryLock now currentTime   # the clock local\nsource $IMPL impl/KeyLocalLock.kt\n")
        val (code, out) = aicode("tool", "B")
        assertEquals(0, code, out)
        assertEquals("\"PROVEN\"", field("B", "final_verdict"))
        assertEquals("2", field("B", "binding_adaptations"))
        assertTrue("no local 'now' is visible" in field("B", "tool_unadapted", "binding_failure"))
        // Only tryLock's local and the source path changed; sweep keeps `local now`.
        val common = File(specDir, "keylocallock.common.kv").readText()
        val expected = common.replaceFirst("free now        = local now", "free now        = local currentTime")
        assertTrue(expected != common)
        assertEquals(expected, File(work, "tool/B/spec/keylocallock.common.kv").readText())
        val coroutine = File(specDir, "keylocallock.coroutine.kv").readText()
        assertEquals(coroutine.replace("source \"$IMPL\"", "source \"impl/KeyLocalLock.kt\""), File(work, "tool/B/spec/keylocallock.coroutine.kv").readText())
        assertEquals(renamed, File(work, "tool/B/root/impl/KeyLocalLock.kt").readText())
        assertFalse(File(work, "tool/B/root/$IMPL").exists())
        // Any other form is refused, and so is an adaptation that matches no `free ... = local` line.
        bindings.writeText("state m = locks\n")
        val (other, otherOut) = aicode("tool", "B")
        assertTrue(other == 2 && "an adaptation is 'local OPERATION FREE NAME' or 'source OLD NEW'" in otherOut, otherOut)
        bindings.writeText("local tryLock rej accepted\n")
        val (none, noneOut) = aicode("tool", "B")
        assertTrue(none == 2 && "matches 0 places" in noneOut, noneOut)
        // AI-CODE.md 4.2: a free implementation gets no hole stage.
        assertEquals(1, aicode("hole-task", "B").first)
    }

    @Test
    fun `verify_sh rejects edits outside the hole, logs every call and stops at its budget`() {
        env(fakeKv("failed"))
        collected("H", sb1)
        assertEquals(0, aicode("tool", "H").first)
        assertEquals("\"FAILED (hole pending)\"", field("H", "final_verdict"))
        val (built, builtOut) = aicode("hole-task", "H")
        assertEquals(0, built, builtOut)
        File(dir, "kv.log").delete() // from here on, the kv calls of verify.sh only
        val task = File(work, "holes/H/task")
        // Only the sidecar files, the implementation, TASK.md and verify.sh: no tests, no judgments.
        assertEquals(setOf("keylocallock.coroutine.kv", "keylocallock.common.kv", "KeyLocalLock.kt", "TASK.md", "verify.sh"), task.list()!!.toSet())
        for (file in task.listFiles()!!) assertFalse(results.path in file.readText(), file.name)
        val verify = File(task, "verify.sh").path
        val common = File(task, "keylocallock.common.kv")
        val pristine = common.readText()
        val coroutine = File(task, "keylocallock.coroutine.kv")

        // 1. An edit outside the hole: kv hole check rejects it, and kv verify does not run.
        common.writeText(pristine.replace("ensures P1:", "ensures P1x:"))
        val (e1, o1) = run(verify)
        assertEquals(6, e1, o1)
        assertTrue("hole check REJECTED" in o1 && "the text outside the hole MI_INV was changed" in o1 && o1.trimEnd().endsWith("(call 1 of 20 used)"), o1)
        assertEquals(listOf("hole check"), kvLog())
        // 2. A forbidden word inside the hole.
        common.writeText(pristine.replace("  0 <= S.seq &&", "  0 <= S.seq && (assume true) &&"))
        val (e2, o2) = run(verify)
        assertTrue(e2 == 6 && "the hole uses 'assume'" in o2, o2)
        // 3. A changed coroutine sidecar is rejected before any kv call.
        common.writeText(pristine)
        coroutine.writeText(coroutine.readText() + "\n")
        val (e3, o3) = run(verify)
        assertTrue(e3 == 6 && "REJECTED: keylocallock.coroutine.kv was changed" in o3, o3)
        assertEquals(listOf("hole check", "hole check"), kvLog())
        coroutine.writeText(File(work, "holes/H/harness/original/keylocallock.coroutine.kv").readText())
        // 4. An edit of the hole only: kv verify runs on the edited sidecar.
        common.writeText(pristine.replace("  0 <= S.seq &&", "  S.seq >= 0 &&"))
        val (e4, o4) = run(verify)
        assertEquals(1, e4, o4)
        assertTrue("hole check OK" in o4 && "unLock   KeyLocalLock.kt:209:9   FAILED (property)" in o4 && o4.trimEnd().endsWith("(call 4 of 20 used)"), o4)
        assertEquals(listOf("hole check", "hole check", "hole check", "verify --root"), kvLog())
        val log = File(work, "holes/H/harness/calls.log").readLines()
        assertEquals(4, log.size, log.toString())
        assertTrue(log[0].matches(Regex("call 1 \\S+ exit=6")) && log[3].matches(Regex("call 4 \\S+ exit=1")), log.toString())
        assertEquals(pristine.replace("  0 <= S.seq &&", "  S.seq >= 0 &&"), File(work, "holes/H/harness/attempts/4/keylocallock.common.kv").readText())

        val (outcome, outcomeOut) = aicode("hole-outcome", "H")
        assertEquals(0, outcome, outcomeOut)
        assertEquals("\"FAILED\"", field("H", "hole", "outcome"))
        assertEquals("4", field("H", "hole", "calls"))
        assertEquals("[\"REJECTED\", \"REJECTED\", \"REJECTED\", \"FAILED\"]", field("H", "hole", "statuses"))
        assertEquals("\"FAILED (property)\"", field("H", "final_verdict"))

        // 5. Over the budget: refused, no kv call, the count stays.
        File(work, "holes/H/harness/calls.count").writeText("20\n")
        val (e5, o5) = run(verify)
        assertEquals(5, e5, o5)
        assertEquals("BUDGET EXHAUSTED: all 20 verify.sh calls are used. Stop and report.", o5.trim())
        assertEquals(4, kvLog().size)
        assertEquals("20", File(work, "holes/H/harness/calls.count").readText().trim())
        // A second build of a task that has calls needs --force.
        assertEquals(1, aicode("hole-task", "H").first)
    }

    @Test
    fun `a hole that proves the module is recorded with its text, and the summary puts a judged implementation in its cell`() {
        env(fakeKv("failed"))
        collected("P", sb1)
        collected("Q", original)
        assertEquals(0, aicode("tool", "P").first)
        assertEquals(0, aicode("hole-task", "P").first)
        val common = File(work, "holes/P/task/keylocallock.common.kv")
        common.writeText(common.readText().replace("  0 <= S.seq &&", "  // MARK-PROVEN\n  0 <= S.seq &&"))
        val (e, o) = run(File(work, "holes/P/task/verify.sh").path)
        assertEquals(0, e, o)
        assertEquals(0, aicode("hole-outcome", "P").first)
        assertEquals("\"PROVEN\"", field("P", "hole", "outcome"))
        assertEquals("1", field("P", "hole", "proven_call"))
        assertTrue("MARK-PROVEN" in field("P", "hole", "hole_text"))
        assertEquals("\"PROVEN (new hole)\"", field("P", "final_verdict"))
        // A tool verdict other than FAILED gets no hole task.
        assertEquals(0, aicode("tool", "Q").first)
        assertEquals(1, aicode("hole-task", "Q").first)
        assertEquals(0, aicode("judge", "P", "defect-property", "--note", "unLock ignores the token").first)
        val (s, summary) = aicode("summary")
        assertEquals(0, s, summary)
        assertTrue("| 결함 (sidecar 성질) | P | - | - | - |" in summary, summary)
        assertTrue("| 판정 대기 | Q | - | - | - |" in summary, summary)
        assertEquals(summary, File(results, "summary.md").readText())
    }

    private companion object {
        const val IMPL = "core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine/KeyLocalLock.kt"
    }
}
