package kv.scripts

import kv.ReqShield
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mechanical mutation driver (scripts/mechanical.py, MUTATION.md 4-8) against a fake `kv`: it prints canned sites,
 * writes canned Viper programs and prints canned reports, keyed by the name of the mutant's copy, and logs every call.
 * The sites edit the real coroutine KeyLocalLock.kt; the real run, with Silicon, is `./gradlew mechanicalMutants`.
 */
class MechanicalScriptTest {
    @TempDir
    lateinit var dir: File

    private val script = File(System.getProperty("kv.scriptsDir"), "mechanical.py")
    private val normalizer = File(System.getProperty("kv.spikeDir"), "vpr_normalize.py")
    private val kotlinFile = "core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine/KeyLocalLock.kt"
    private val supportFile = "support/src/main/kotlin/com/linecorp/cse/reqshield/support/config/LocalLockLimit.kt"

    /** A site of `kv mutation-sites`: [find] is the text it replaces, at its first occurrence in [within] in [file]. */
    private data class Site(
        val op: String,
        val find: String,
        val replacement: String,
        val facts: Map<String, String> = emptyMap(),
        val scope: String = "operation tryLock",
        val file: String = "core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine/KeyLocalLock.kt",
        val ledger: String = "translated",
        val within: String = find,
    )

    /** What the fake kv does for one command on one copy: its exit code, its output, and the program `emit` writes. */
    private data class Response(
        val exit: Int = 0,
        val out: String = "",
        val err: String = "",
        val vpr: String? = null,
    )

    private fun program(body: String) = "method tryLock(k: Int) returns (res: Int)\n{\n  var existing__155: Int\n  existing__155 := k\n$body}\n"

    private val original = program("  res := existing__155 + 1\n")

    private fun report(
        verdict: String,
        findings: List<String> = emptyList(),
        tail: List<String> = emptyList(),
    ): String =
        (
            listOf("m.Companion (keylocallock.coroutine.kv)    module: $verdict (Silicon 1.00 s)", "  action tryLock  KeyLocalLock.kt:155:9   ${if (verdict == "FAILED") "FAILED" else "PROVEN"}") +
                findings.map { "      $it" } + listOf("  action unLock   KeyLocalLock.kt:209:9   PROVEN", "  init PROVEN · lemma P3c PROVEN · probe PROVEN", "Spec to review: none") + tail + listOf("")
        ).joinToString("\n")

    private val p3a = "Postcondition of tryLock might not hold: sidecar ends_only_if P3a keylocallock.common.kv:56:3"
    private val proven = Response(0, report("PROVEN"))

    private fun json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    /**
     * Runs the driver on the coroutine module with [sites]. [responses] maps `<command> <copy>` (e.g. `emit ROR-01`,
     * `verify original`) to the fake run's result; the original's emit and verify default to [original] and PROVEN.
     * Returns the exit code, the output, and the fake's call log.
     */
    private fun run(
        sites: List<Site>,
        responses: Map<String, Response>,
    ): Triple<Int, String, List<String>> {
        val data = File(dir, "fake").apply { mkdirs() }
        val texts = mutableMapOf<String, String>()
        val sitesJson =
            sites.joinToString(",\n", "{\"module\": \"m.Companion\", \"sidecar\": \"x.kv\", \"sites\": [\n", "\n], \"properties\": [" + PROPERTY + "]}\n") { s ->
                val text = texts.getOrPut(s.file) { File(ReqShield.dir, s.file).readText() }
                val start = text.indexOf(s.within).also { check(it >= 0 && s.find in s.within) { "no ${s.within}" } } + s.within.indexOf(s.find)
                val line = text.substring(0, start).count { it == '\n' } + 1
                "{\"operator\": \"${s.op}\", \"scope\": ${json(s.scope)}, \"file\": ${json(s.file)}, \"line\": $line, \"col\": 1, \"endLine\": $line, \"endCol\": 2, " +
                    "\"ledger\": ${json(s.ledger)}, \"edits\": [{\"start\": $start, \"end\": ${start + s.find.length}, \"original\": ${json(s.find)}, \"replacement\": ${json(s.replacement)}}], " +
                    "\"facts\": {" + s.facts.entries.joinToString(", ") { "${json(it.key)}: ${json(it.value)}" } + "}}"
            }
        File(data, "sites.json").writeText(sitesJson)
        val all = mapOf("emit original" to Response(vpr = original), "verify original" to proven) + responses
        for ((key, r) in all) {
            File(data, "$key.json").writeText("{\"exit\": ${r.exit}, \"out\": ${json(r.out)}, \"err\": ${json(r.err)}, \"vpr\": ${r.vpr?.let(::json) ?: "null"}}")
        }
        val kv =
            File(dir, "kv").apply {
                writeText(
                    """
                    |#!/usr/bin/env python3
                    |import json, os, sys
                    |data, args = "${data.path}", sys.argv[1:]
                    |cmd = args[0]
                    |if cmd == "mutation-sites":
                    |    sys.stdout.write(open(os.path.join(data, "sites.json")).read())
                    |    sys.exit(0)
                    |where = args[args.index("--root") + 1] if "--root" in args else args[-1]
                    |parts = where.split(os.sep)
                    |key = "%s %s" % (cmd, parts[parts.index("roots") + 1])
                    |with open(os.path.join(data, "calls.log"), "a") as f:
                    |    f.write(key + "\n")
                    |path = os.path.join(data, key + ".json")
                    |r = json.load(open(path)) if os.path.exists(path) else {"exit": 0, "out": "", "err": "", "vpr": None}
                    |if cmd == "emit" and r["exit"] == 0:
                    |    open(args[args.index("--out") + 1], "w").write(r["vpr"])
                    |sys.stdout.write(r["out"])
                    |sys.stderr.write(r["err"])
                    |sys.exit(r["exit"])
                    |
                    """.trimMargin(),
                )
                setExecutable(true)
            }
        val p =
            ProcessBuilder(
                "python3", "-I", script.path, "--kv", kv.path, "--req-shield", ReqShield.dir.path, "--classpath", "unused", "--aspect-classpath", "unused",
                "--kotlinc", "unused", "--specs", dir.path, "--normalizer", normalizer.path, "--work", File(dir, "work").path, "--results", File(dir, "results").path,
                "--modules", "keylocallock.coroutine", "--jobs", "2",
            ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(120, TimeUnit.SECONDS)) { "mechanical.py did not finish" }
        val calls = File(data, "calls.log").takeIf { it.isFile }?.readLines().orEmpty()
        return Triple(p.exitValue(), out, calls)
    }

    /** results.jsonl by mutant id: each line's fields, as their JSON text. */
    private fun results(): Map<String, Map<String, String>> =
        File(dir, "results/results.jsonl").readLines().associate { line ->
            val fields = FIELD.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
            fields.getValue("id").trim('"') to fields
        }

    private fun summary() = File(dir, "results/summary.md").readText()

    @Test
    fun `mutants with one normalized model share one verify, and a mutant with another model gets its own`() {
        val changed = program("  res := existing__155 + 2\n")
        // The same program: other comments, layout and position suffix.
        val sameAsChanged = "// mutant\nmethod tryLock(k: Int) returns (res: Int) {\n  var existing__156: Int\n\n  existing__156 := k\n  res := existing__156 + 2 // two\n}\n"
        val other = program("  res := existing__155 + 3\n")
        val (exit, out, calls) =
            run(
                listOf(Site("ROR", "existing != null", "existing == null"), Site("NEG", "if (existing != null)", "if (!(existing != null))"), Site("BOOL", "compareAndSet(true", "compareAndSet(false")),
                mapOf(
                    "emit ROR-01" to Response(vpr = changed),
                    "emit NEG-01" to Response(vpr = sameAsChanged),
                    "emit BOOL-01" to Response(vpr = other),
                    "verify ROR-01" to Response(1, report("FAILED", listOf(p3a))),
                    "verify BOOL-01" to proven,
                    "lower NEG-01" to Response(out = "module complete; W1-W16: 0 violation(s)\n"),
                ),
            )
        assertEquals(0, exit, out)
        assertEquals(listOf("verify BOOL-01", "verify ROR-01", "verify original"), calls.filter { it.startsWith("verify") }.sorted(), "one verify per hash")
        assertEquals(listOf("lower NEG-01"), calls.filter { it.startsWith("lower") }, "only a mutant that shares a verdict is lowered again")
        val r = results()
        assertEquals(r.getValue("keylocallock.coroutine/ROR-01")["model_hash"], r.getValue("keylocallock.coroutine/NEG-01")["model_hash"])
        assertEquals("\"FAILED\"", r.getValue("keylocallock.coroutine/NEG-01")["verdict"])
        assertEquals("[\"tryLock: ends_only_if P3a\"]", r.getValue("keylocallock.coroutine/NEG-01")["failing"])
        assertEquals("\"keylocallock.coroutine/ROR-01\"", r.getValue("keylocallock.coroutine/NEG-01")["shared_from"])
        assertEquals("false" to "true", r.getValue("keylocallock.coroutine/NEG-01")["silicon_run"] to r.getValue("keylocallock.coroutine/ROR-01")["silicon_run"])
        // The other hash keeps its own verdict.
        assertEquals("\"PROVEN\"", r.getValue("keylocallock.coroutine/BOOL-01")["verdict"])
        assertEquals("\"pending judge: PROVEN, model changed\"", r.getValue("keylocallock.coroutine/BOOL-01")["class"])
        assertTrue("Silicon 실행 3회 (원본 1회, 새 해시 2회). 중복 제거로 아낀 Silicon 실행: 1회." in summary(), summary())
        // The copy holds the mutant; req-shield does not.
        val copy = File(dir, "work/keylocallock.coroutine/roots/ROR-01/$kotlinFile").readText()
        assertTrue("if (existing == null) {" in copy && "if (existing != null) {" in File(ReqShield.dir, kotlinFile).readText())
    }

    @Test
    fun `a model equal to the original's after normalization is model identical, even with other raw text`() {
        val renamed = "// a comment\nmethod tryLock(k: Int) returns (res: Int)\n{\n  var existing__160: Int\n  existing__160   :=   k\n  res := existing__160 + 1\n}\n"
        val (exit, out, calls) =
            run(
                listOf(Site("SDL", "log.debug(\"Attempted", "//log.debug(\"Attempted", mapOf("callee" to "org/slf4j/Logger.debug"), "operation unLock", ledger = "not-modeled"), Site("BOOL", "compareAndSet(true", "compareAndSet(false")),
                mapOf("emit SDL-01" to Response(vpr = renamed), "emit BOOL-01" to Response(vpr = program("  res := existing__155 - 1\n")), "verify BOOL-01" to proven),
            )
        assertEquals(0, exit, out)
        assertTrue("verify SDL-01" !in calls, "no Silicon for a model-identical mutant: $calls")
        val sdl = results().getValue("keylocallock.coroutine/SDL-01")
        assertEquals(listOf("true", "\"PROVEN\"", "\"abstracted by rule\"", "\"original\""), listOf(sdl["model_identical"], sdl["verdict"], sdl["class"], sdl["shared_from"]))
        assertEquals("[\"7.6\"]", sdl["tags"])
        val bool = results().getValue("keylocallock.coroutine/BOOL-01")
        assertEquals("false" to "\"pending judge: PROVEN, model changed\"", bool["model_identical"] to bool["class"])
        assertTrue("규칙으로 추상화됨 (PROVEN, 모델 동일): 1개. ledger 종류별: not-modeled 1." in summary(), summary())
        assertTrue("| 7.6 | SDL, `log.*` 호출 | PROVEN 1 | 모두 PROVEN, 모델 동일 (예측과 같음) |" in summary(), summary())
    }

    @Test
    fun `a mutant that does not compile is stillborn, gets no verify, and never counts as ERROR`() {
        val notCompiled = Response(1, err = "KeyLocalLock.kt:156:17: error: unresolved reference\nkv: compilation failed\n")
        val (exit, out, calls) =
            run(
                listOf(
                    Site("ROR", "existing != null", "existing =!= null"),
                    // A library trusted fun: kv emit does not compile it, so kv discover does.
                    Site("BOOL", "return true", "return 1", scope = "trusted rejectsNewEntry", file = supportFile, ledger = "-"),
                ),
                mapOf("emit ROR-01" to notCompiled, "discover BOOL-01" to Response(1, err = "LocalLockLimit.kt:100:16: error: return type mismatch\nkv: compilation failed\n")),
            )
        assertEquals(0, exit, out)
        assertEquals(listOf("verify original"), calls.filter { it.startsWith("verify") })
        assertTrue("emit BOOL-01" !in calls, calls.toString())
        for (id in listOf("ROR-01", "BOOL-01")) {
            val r = results().getValue("keylocallock.coroutine/$id")
            assertEquals(listOf("false", "null", "\"stillborn\""), listOf(r["compiled"], r["verdict"], r["class"]), id)
        }
        assertTrue("| keylocallock.coroutine | ROR | 1 | 1 | 0 | 0 | 0 | 0 | 0 |" in summary(), summary())
        assertTrue("- 서명이 막음 (stale-review): 0개.\n- 도구 결함 후보: 0개." in summary(), summary())
        assertTrue("규칙별 사산 수: ROR 1, COR 0, NEG 0, BOOL 1," in summary(), summary())
    }

    @Test
    fun `a shared verdict keeps the mutant's own unsupported construct, and a completeness conflict is reported`() {
        val incomplete = program("  res := existing__155 + 11\n")
        val lowered = "  U156_17 UNSUPPORTED value of type kotlin.Nothing in action tryLock#155 at KeyLocalLock.kt:156:17: the type is not part of the model @156:17\nmodule INCOMPLETE (unsupported parts above); W1-W16: 0 violation(s)\n"
        val (exit, out, calls) =
            run(
                listOf(Site("SDL", "existing.semaphore.release()", ""), Site("RET", "            existing\n", "            null\n"), Site("BOOL", "compareAndSet(true", "compareAndSet(false")),
                mapOf(
                    "emit SDL-01" to Response(vpr = incomplete),
                    "verify SDL-01" to Response(3, report("INCOMPLETE", tail = listOf("Unsupported: tryLock KeyLocalLock.kt:155:9; probe"))),
                    "emit RET-01" to Response(vpr = incomplete),
                    "lower RET-01" to Response(out = lowered),
                    // The original's model, but this copy's own lowering is INCOMPLETE.
                    "emit BOOL-01" to Response(vpr = original),
                    "lower BOOL-01" to Response(out = lowered),
                ),
            )
        assertEquals(0, exit, out)
        assertEquals(listOf("lower BOOL-01", "lower RET-01"), calls.filter { it.startsWith("lower") }.sorted())
        val r = results()
        assertEquals("\"tryLock KeyLocalLock.kt:155:9; probe\"", r.getValue("keylocallock.coroutine/SDL-01")["message"])
        val ret = r.getValue("keylocallock.coroutine/RET-01")
        assertEquals(listOf("\"INCOMPLETE\"", "\"value of type kotlin.Nothing in action tryLock#155 at KeyLocalLock.kt:156:17: the type is not part of the model\"", "false"), listOf(ret["verdict"], ret["message"], ret["dedupe_conflict"]))
        val bool = r.getValue("keylocallock.coroutine/BOOL-01")
        assertEquals(listOf("\"PROVEN\"", "\"INCOMPLETE\"", "true"), listOf(bool["verdict"], bool["own_lowering"], bool["dedupe_conflict"]))
        assertTrue("완결성이 함께 쓴 판정과 어긋나는 변이: 1개\n  - keylocallock.coroutine/BOOL-01: 함께 쓴 판정 PROVEN (original에서), 자기 lowering INCOMPLETE." in summary(), summary())
    }

    @Test
    fun `stale-review is blocked by review, another ERROR is a tool defect candidate, INCOMPLETE names its construct`() {
        val stale = "keylocallock.common.kv:38:3: stale-review: trusted fun rejectsNewEntry changed after its review\n"
        val (exit, out, calls) =
            run(
                listOf(
                    Site("BOOL", "return true", "return false", scope = "trusted rejectsNewEntry", file = supportFile, ledger = "-"),
                    Site("ROR", "existing != null", "existing == null"),
                    Site("NEG", "if (existing != null)", "if (!(existing != null))"),
                    Site("SDL", "log.warn(", "if (false) log.warn(", mapOf("callee" to "org/slf4j/Logger.warn"), "trusted rejectsNewEntry", supportFile, "-"),
                ),
                mapOf(
                    "emit BOOL-01" to Response(1, err = stale),
                    "verify BOOL-01" to Response(4, err = stale),
                    "emit SDL-01" to Response(1, err = stale),
                    "verify SDL-01" to Response(4, err = stale),
                    "emit ROR-01" to Response(vpr = program("  res := existing__155 + 5\n")),
                    "verify ROR-01" to Response(4, report("ERROR").replaceFirst("\n  action", "\n  ERROR: timeout: killed at the wall clock limit of 300 s; this run proves nothing\n  action").replace("(Silicon 1.00 s)", "")),
                    "emit NEG-01" to Response(vpr = program("  res := existing__155 + 6\n")),
                    "verify NEG-01" to Response(3, report("INCOMPLETE", tail = listOf("Unsupported: try expression KeyLocalLock.kt:170:13"))),
                ),
            )
        assertEquals(0, exit, out)
        assertTrue("verify BOOL-01" in calls, "a mutant without a model gets its own kv verify")
        val r = results()
        assertEquals("\"blocked by review\"", r.getValue("keylocallock.coroutine/BOOL-01")["class"])
        assertEquals("[\"7.5\"]", r.getValue("keylocallock.coroutine/BOOL-01")["tags"])
        // 7.6 (the slf4j model) is not about a trusted fun body: 7.5 predicts those.
        assertEquals("[\"7.5\"]" to "\"blocked by review\"", r.getValue("keylocallock.coroutine/SDL-01").let { it["tags"] to it["class"] })
        assertEquals("\"tool defect candidate\"", r.getValue("keylocallock.coroutine/ROR-01")["class"])
        assertEquals("\"ERROR: timeout: killed at the wall clock limit of 300 s; this run proves nothing\"", r.getValue("keylocallock.coroutine/ROR-01")["message"])
        assertEquals("\"unsupported\"" to "\"try expression KeyLocalLock.kt:170:13\"", r.getValue("keylocallock.coroutine/NEG-01")["class"] to r.getValue("keylocallock.coroutine/NEG-01")["message"])
        assertTrue("  - keylocallock.coroutine/ROR-01: ERROR: timeout: killed at the wall clock limit of 300 s; this run proves nothing" in summary(), summary())
        assertTrue("| 7.5 | trusted fun 본문의 변이 | ERROR 2 | 컴파일되는 것 모두 stale-review (예측과 같음) |" in summary(), summary())
    }

    @Test
    fun `predictions 7_1 and 7_2 are tagged by rule, and the judge bundle holds what MUTATION_md 6 names`() {
        val failed = Response(1, report("FAILED", listOf(p3a)))
        val (exit, out, _) =
            run(
                listOf(
                    Site("ROR", ">", ">=", mapOf("op" to ">", "fields" to "expiresAt", "boundary" to "true"), within = "now > existing.expiresAt"),
                    Site("RACY", "if (existing != null)", "if (racy_1 && existing != null)", mapOf("map" to "m", "setOnce" to "")),
                    Site("SDL", "existing.semaphore.release()\n", "\n", mapOf("callee" to "java/util/concurrent/Semaphore.release", "removalFollows" to "false")),
                    Site("COR", "existing.token == token && ", "existing.token == token || "),
                ),
                mapOf(
                    "emit ROR-01" to Response(vpr = program("  res := existing__155 + 7\n")), "verify ROR-01" to failed,
                    "emit RACY-01" to Response(vpr = program("  res := existing__155 + 8\n")), "verify RACY-01" to failed,
                    "emit SDL-01" to Response(vpr = program("  res := existing__155 + 9\n")), "verify SDL-01" to proven,
                    "emit COR-01" to Response(vpr = program("  res := existing__155 + 10\n")), "verify COR-01" to failed,
                ),
            )
        assertEquals(0, exit, out)
        val r = results()
        assertEquals("[\"7.1\"]" to "\"detected (prediction 7.1)\"", r.getValue("keylocallock.coroutine/ROR-01").let { it["tags"] to it["class"] })
        assertEquals("[\"7.2\"]" to "\"pending judge: FAILED, prediction 7.2\"", r.getValue("keylocallock.coroutine/RACY-01").let { it["tags"] to it["class"] })
        assertEquals("[]" to "\"detected\"", r.getValue("keylocallock.coroutine/COR-01").let { it["tags"] to it["class"] })
        val index = File(dir, "results/judge/index.md").readText()
        assertTrue("- 그 밖의 FAILED 2개에서 무작위 2개 (seed 20261024)." in index, index)
        for ((id, why) in listOf("SDL-01" to "PROVEN, 모델 바뀜", "RACY-01" to "FAILED, 예측 7.2", "COR-01" to "FAILED, 무작위 표본", "ROR-01" to "FAILED, 무작위 표본")) {
            assertTrue(index.lines().any { it.startsWith("| [keylocallock.coroutine/$id](keylocallock.coroutine__$id.md)") && it.endsWith("| $why |") }, "$id\n$index")
        }
        val sdl = File(dir, "results/judge/keylocallock.coroutine__SDL-01.md").readText()
        assertTrue(" 164 |                     existing.semaphore.release()" in sdl, sdl)
        assertTrue(Regex("""(?m)^ 164 \|\s*$""").containsMatchIn(sdl.substringAfter("## Kotlin 변이")), sdl)
        assertTrue("-res := _v1 + 1\n+res := _v1 + 9\n" in sdl, sdl)
        assertTrue("- ends_only_if P3a / tryLock (keylocallock.common.kv:56): `now > old(tok(qt).gLease)`\n  - 검토 메모(출처 인용): \"README: only an expired lock is taken over\"" in sdl, sdl)
        assertTrue("- 판정: FAILED\n- 실패 의무: tryLock: ends_only_if P3a" in File(dir, "results/judge/keylocallock.coroutine__RACY-01.md").readText())
    }

    @Test
    fun `over 30 PROVEN changed mutants the judge sees every ROR boundary and RET one and a seeded sample of the rest`() {
        val sites =
            List(31) { Site("BOOL", "compareAndSet(true", "compareAndSet(false") } +
                listOf(
                    Site("RET", "            existing\n", "            null\n"),
                    Site("ROR", "now > existing.expiresAt", "now >= existing.expiresAt", mapOf("op" to ">", "fields" to "", "boundary" to "true")),
                    Site("ROR", "existing != null", "existing == null", mapOf("op" to "!=", "fields" to "", "boundary" to "false")),
                )
        val ids = List(31) { "BOOL-%02d".format(it + 1) } + listOf("RET-01", "ROR-01", "ROR-02")
        val responses = ids.withIndex().flatMap { (i, id) -> listOf("emit $id" to Response(vpr = program("  res := existing__155 + ${100 + i}\n")), "verify $id" to proven) }.toMap()
        val (exit, out, _) = run(sites, responses)
        assertEquals(0, exit, out)
        val index = File(dir, "results/judge/index.md").readText()
        assertTrue("- \"PROVEN, 모델 바뀜\" 34개가 상한 30을 넘는다: ROR 경계와 RET 2개를 모두 넣고, 나머지 32개에서 무작위 28개를 뽑았다 (seed 20261025). 이 범주의 판정은 표본이다." in index, index)
        assertTrue("| PROVEN, 모델 바뀜 | 30 |" in index, index)
        assertTrue("(keylocallock.coroutine__RET-01.md)" in index && "(keylocallock.coroutine__ROR-01.md)" in index, index)
        assertEquals(30, File(dir, "results/judge").listFiles()!!.count { it.name != "index.md" })
    }

    private companion object {
        val FIELD = Regex(""""(\w+)": ("(?:[^"\\]|\\.)*"|\[[^\]]*]|\{[^}]*}|true|false|null|-?\d+)""")
        const val PROPERTY =
            "{\"kind\": \"ends_only_if\", \"operation\": \"tryLock\", \"id\": \"P3a\", \"text\": \"now > old(tok(qt).gLease)\", \"reviewed\": \"README: only an expired lock is taken over\", \"at\": \"keylocallock.common.kv:56\"}"
    }
}
