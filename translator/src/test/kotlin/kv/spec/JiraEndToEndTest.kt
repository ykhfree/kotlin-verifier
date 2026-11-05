package kv.spec

import kv.cli.runCli
import kv.json.jsonOf
import kv.json.parseJson
import kv.sidecar.reviewFingerprint
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [v] with each fraction as a whole number, because `jsonOf` writes no fractions. Its maps and lists can change. */
internal fun withoutFractions(v: Any?): Any? =
    when (v) {
        is Double -> v.toLong()
        is Map<*, *> -> LinkedHashMap(v.entries.associate { (k, x) -> k as String to withoutFractions(x) })
        is List<*> -> v.mapTo(mutableListOf(), ::withoutFractions)
        else -> v
    }

/**
 * DESIGN-J.md 9.3, prediction P12: one change to a copy of the free-form fixture, then `kv spec snapshot` and
 * `kv spec stale` against a sidecar made from the unchanged fixture. The sidecar has `scope "jira:10042"`, 16 quotes and
 * 27 excludes. E11, E12 and E13 pin known limits.
 */
@Suppress("UNCHECKED_CAST") // The parsed tree holds only maps and lists that can change.
class JiraEndToEndTest {
    @TempDir
    lateinit var dir: File

    private val fixture = File(javaClass.getResource("/jira/10042.jira.json")!!.toURI())
    private val source = "jira:10042"

    /** The block that each quote cites, as the anchor after `jira:10042#`. */
    private val quotes =
        mapOf(
            "description/23025083" to "30초가 지나면 만료된다",
            "description/383f1cd4" to "재시도는 3회까지 한다",
            "description/93a3ada9" to "실패를 돌려준다",
            "description/cf590ed5" to "50ms 이다",
            "description/bcbff204" to "키마다 잠금은 하나다",
            "description/ac636da3" to "두 번째 요청은 기다린다",
            "description/6844cd7e" to "먼저 온 요청이 잠금을 얻는다",
            "description/fa713a38" to "나머지 요청은 줄을 선다",
            "description/a6e83197" to "CREATE | 유지",
            "description/858aae13" to "만료된 잠금은 해제할 수 없다",
            "description/1f35263f" to "해제는 소유자만 한다",
            "description/03fdc268" to "만료 경계는 초과로 본다",
            "customfield_10050/17173a29" to "30초 경계에서는 만료되지 않는다",
            "customfield_10050/e31a0275" to "31초에는 만료된다",
            "comment/10072/20b44600" to "60초로 바꾼다",
            "comment/10073/89c81a24" to "해제 실패는 예외를 던진다",
        )

    /** The tree of the fetched file. Its maps and lists can change. */
    private fun tree(): MutableMap<String, Any?> = withoutFractions(parseJson(fixture.readText())) as MutableMap<String, Any?>

    private fun MutableMap<String, Any?>.map(key: String) = this[key] as MutableMap<String, Any?>

    private fun MutableMap<String, Any?>.list(key: String) = this[key] as MutableList<Any?>

    private fun MutableMap<String, Any?>.fields() = map("issue").map("fields")

    private fun MutableMap<String, Any?>.description() = fields().map("description").list("content")

    private fun MutableMap<String, Any?>.comments() = list("comments")

    private fun text(s: String) = mapOf("type" to "text", "text" to s)

    private fun paragraph(s: String) = mapOf("type" to "paragraph", "content" to listOf(text(s)))

    private fun doc(s: String) = mapOf("version" to 1L, "type" to "doc", "content" to listOf(paragraph(s)))

    private fun comment(
        id: String,
        s: String,
    ) = mapOf("id" to id, "body" to doc(s), "created" to "t", "updated" to "t")

    /** Sets the text of the paragraph at [index] of the description. */
    private fun MutableMap<String, Any?>.rewrite(
        index: Int,
        s: String,
    ) {
        description()[index] = paragraph(s)
    }

    private fun snapshotOf(
        name: String,
        change: (MutableMap<String, Any?>) -> Unit,
    ): File {
        val root = tree().also(change)
        val file = File(dir, "$name/10042.jira.json").apply { parentFile.mkdirs() }
        file.writeText(jsonOf(root))
        val out = File(dir, "$name/snap")
        writeSnapshot(out, listOf(SnapshotInput(source, file.path)))
        return out
    }

    /** The sidecar lines for the unchanged fixture. [keep] says which blocks it cites or excludes. */
    private fun sidecar(
        scope: String = source,
        keep: (String) -> Boolean = { true },
    ): File {
        val blocks = readSnapshot(snapshotOf("base") {}).filter { keep(it.anchor) }
        val lines = mutableListOf<String>()
        blocks.filter { it.anchor.substringAfter('#') in quotes }.forEachIndexed { n, b ->
            lines += "invariant I$n: true"
            lines += "  source \"${b.anchor}\" quote \"${quotes.getValue(b.anchor.substringAfter('#'))}\" sha256 \"${b.sha256}\""
        }
        lines += "scope \"$scope\""
        blocks.filter { it.anchor.substringAfter('#') !in quotes }.forEach { lines += "exclude \"${it.anchor}\" because \"not encoded\" sha256 \"${it.sha256}\"" }
        return File(dir, "side.kv").apply { writeText(lines.joinToString("\n", postfix = "\n")) }
    }

    private class Result(
        val exit: Int,
        val codes: Map<String, Int>,
        val uncovered: List<String>,
        val stdout: String,
    )

    private fun stale(
        snapshot: File,
        side: File,
    ): Result {
        val out = ByteArrayOutputStream()
        val exit = runCli(listOf("spec", "stale", "--snapshot", snapshot.path, side.path), PrintStream(out), PrintStream(ByteArrayOutputStream()))
        val stdout = out.toString()
        val findings = Regex("""(?m)^\S+:\d+:\d+: (?:warning|error)\[([\w-]+)]: (.*)$""").findAll(stdout).toList()
        return Result(
            exit,
            findings.groupingBy { it.groupValues[1] }.eachCount(),
            findings.filter { it.groupValues[1] == "uncovered-block" }.map { Regex("""block (\S+)""").find(it.groupValues[2])!!.groupValues[1].substringAfter('#') },
            stdout,
        )
    }

    /** Runs one case against the full sidecar and checks the exit code and the counts of findings. */
    private fun case(
        exit: Int,
        expected: Map<String, Int>,
        change: (MutableMap<String, Any?>) -> Unit,
    ): Result {
        val side = sidecar()
        val r = stale(snapshotOf("changed", change), side)
        assertEquals(expected, r.codes, r.stdout)
        assertEquals(exit, r.exit, r.stdout)
        return r
    }

    /** The snapshot's blocks without the path, which differs per copy. */
    private fun blocksOf(snapshot: File) = readSnapshot(snapshot).map { listOf(it.anchor, it.text, it.sha256) }

    @Test
    fun `E0 the unchanged issue has no finding, with 16 quotes and 27 excludes`() {
        val lines = sidecar().readLines()
        assertEquals(16, lines.count { it.trim().startsWith("source ") })
        assertEquals(27, lines.count { it.startsWith("exclude ") })
        case(0, emptyMap()) {}
    }

    @Test
    fun `E1 a new paragraph is the one finding and it names the new block, and no signed line moves`() {
        val r = case(4, mapOf("uncovered-block" to 1)) { it.description().add(1, paragraph("해제는 2회까지 재시도한다.")) }
        val new = reviewOf("해제는 2회까지 재시도한다.")
        assertEquals(listOf("description/$new"), r.uncovered)
    }

    @Test
    fun `E2 a new comment is a finding even if it repeats an old comment`() {
        val r = case(4, mapOf("uncovered-block" to 1)) { it.comments().add(comment("10077", "확인했습니다.")) }
        assertEquals(listOf("comment/10077/c05a422f"), r.uncovered)
    }

    @Test
    fun `E3 an edit of a cited comment that keeps the quote asks for a new look`() {
        case(4, mapOf("source-moved" to 1, "uncovered-block" to 1)) {
            (it.comments()[1] as MutableMap<String, Any?>)["body"] = doc("논의 결과 만료 시간은 30초가 아니라 60초로 바꾼다. 이유는 지연이다.")
        }
    }

    @Test
    fun `E4 a deleted cited comment is stale`() {
        case(4, mapOf("stale-source" to 1)) { it.comments().removeAt(1) }
    }

    @Test
    fun `E5 a requirement added to an excluded comment does not hide under the old exclude`() {
        val r =
            case(4, mapOf("stale-exclude" to 1, "uncovered-block" to 1)) {
                (it.comments()[0] as MutableMap<String, Any?>)["body"] = doc("확인했습니다. 단, 재시도는 5회로 바꿉니다.")
            }
        assertEquals(listOf("comment/10071/${reviewOf("확인했습니다. 단, 재시도는 5회로 바꿉니다.")}"), r.uncovered)
    }

    @Test
    fun `E6 a moved issue changes the head line and no block`() {
        case(0, emptyMap()) { it.map("issue")["key"] = "XYZ-3" }
        val moved = snapshotOf("moved") { it.map("issue")["key"] = "XYZ-3" }
        assertEquals(blocksOf(snapshotOf("base2") {}), blocksOf(moved))
        assertTrue("## jira:10042 (XYZ-3," in File(moved, "SOURCES.md").readText())
    }

    @Test
    fun `E7 a new attachment is a finding`() {
        case(4, mapOf("uncovered-block" to 1)) {
            it.fields().list("attachment").add(mapOf("id" to "20003", "filename" to "new.png", "mimeType" to "image/png"))
        }
    }

    @Test
    fun `E8 a checked task changes no block`() {
        val checked =
            snapshotOf("checked") {
                val list = it.description().single { n -> (n as Map<*, *>)["type"] == "taskList" } as MutableMap<String, Any?>
                val task = list.list("content")[0] as MutableMap<String, Any?>
                task.map("attrs")["state"] = "DONE"
            }
        assertEquals(blocksOf(snapshotOf("base2") {}), blocksOf(checked))
        assertEquals(0, stale(checked, sidecar()).exit)
    }

    @Test
    fun `E9 a new first item of an ordered list moves no later item`() {
        case(4, mapOf("uncovered-block" to 1)) {
            val list = it.description().single { n -> (n as Map<*, *>)["type"] == "orderedList" } as MutableMap<String, Any?>
            list.list("content").add(0, mapOf("type" to "listItem", "content" to listOf(paragraph("큐가 차면 거절한다."))))
        }
    }

    @Test
    fun `E10 a heading added later moves nothing below it`() {
        case(4, mapOf("uncovered-block" to 1)) {
            it.description().add(0, mapOf("type" to "heading", "attrs" to mapOf("level" to 1L), "content" to listOf(text("개요"))))
        }
    }

    @Test
    fun `E11 limit a change of block order is not found`() {
        case(0, emptyMap()) {
            val d = it.description()
            val first = d[0]
            d[0] = d[5]
            d[5] = first
        }
    }

    @Test
    fun `E12 limit with a scope on the description, a comment that overrides it is not found`() {
        val side = sidecar("$source#description") { "#description/" in it }
        assertEquals(13, side.readLines().count { it.startsWith("exclude ") })
        val r = stale(snapshotOf("changed") { it.comments().add(comment("10077", "만료 시간은 90초로 다시 바꾼다.")) }, side)
        assertEquals(emptyMap(), r.codes, r.stdout)
        assertEquals(0, r.exit, r.stdout)
    }

    @Test
    fun `E13 limit a scope on the comments of an issue with no comment is empty`() {
        val side = File(dir, "side.kv").apply { writeText("scope \"$source#comment\"\n") }
        val r = stale(snapshotOf("changed") { it.comments().clear() }, side)
        assertEquals(mapOf("empty-scope" to 1), r.codes, r.stdout)
        assertEquals(4, r.exit)
    }

    @Test
    fun `E14 a change of whitespace only is no finding`() {
        case(0, emptyMap()) { it.rewrite(0, "잠금은  획득 후\t30초가 지나면 만료된다.") }
    }

    @Test
    fun `E15 a strike inside a quote is stale, although the words stay`() {
        case(4, mapOf("stale-source" to 1, "uncovered-block" to 1)) {
            val d = it.description()[1] as MutableMap<String, Any?>
            d["content"] =
                listOf(
                    text("재시도는 "),
                    mapOf("type" to "text", "text" to "3회까지", "marks" to listOf(mapOf("type" to "strike"))),
                    text(" 한다."),
                    mapOf("type" to "hardBreak"),
                    text("그 다음에는 실패를 돌려준다."),
                )
        }
    }

    @Test
    fun `E16 a deleted excluded attachment makes the exclude stale`() {
        case(4, mapOf("stale-exclude" to 1)) { it.fields().list("attachment").removeAt(1) }
    }

    @Test
    fun `E17 a secret pasted into a cited line redacts it, and the clause goes stale`() {
        case(4, mapOf("stale-source" to 1, "uncovered-block" to 1)) {
            val d = it.description()[1] as MutableMap<String, Any?>
            d["content"] = listOf(text("재시도는 3회까지 한다. password: hunter22x"), mapOf("type" to "hardBreak"), text("그 다음에는 실패를 돌려준다."))
        }
    }

    @Test
    fun `E18 a scope on one comment covers that comment only`() {
        val side = File(dir, "side.kv").apply { writeText("scope \"$source#comment/10073\"\n") }
        val r = stale(snapshotOf("same") {}, side)
        assertEquals(mapOf("uncovered-block" to 2), r.codes, r.stdout)
        assertEquals(listOf("comment/10073/9c082cbf", "comment/10073/89c81a24"), r.uncovered)
    }

    @Test
    fun `E19 an edit of one of two equal blocks moves the citation to the other, and the exclude of the other goes stale`() {
        case(4, mapOf("stale-exclude" to 1, "uncovered-block" to 1)) { it.rewrite(0, "잠금은 획득 후 60초가 지나면 만료된다.") }
    }

    @Test
    fun `E20 a new remote link is a finding`() {
        case(4, mapOf("uncovered-block" to 1)) {
            it.list("remoteLinks").add(mapOf("object" to mapOf("title" to "다른 문서", "url" to "https://kv-test.atlassian.net/wiki/pages/viewpage.action?pageId=456")))
        }
    }

    /** The anchor hash of a block with text [s]. */
    private fun reviewOf(s: String) = reviewFingerprint(s).take(8)
}
