package kv.spec

import kv.cli.runCli
import kv.sidecar.reviewFingerprint
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * DESIGN-J.md 9.2: `kv spec snapshot` on a fetched Jira issue. No Jira connection: the fixture follows the official ADF
 * schema and the layout of `jira_fetch.py`, and all its values are fake. The anchors below are the values registered in
 * DESIGN-J.md appendix A before Jira.kt existed.
 */
class JiraSnapshotTest {
    @TempDir
    lateinit var dir: File

    private val fixture = File(javaClass.getResource("/jira/10042.jira.json")!!.toURI())

    /** The anchor after `jira:10042#` and the text of each block of the fixture (DESIGN-J.md appendix A). */
    private val golden =
        listOf(
            "summary/67df719f" to "잠금 만료 규칙 변경",
            "description/23025083" to "잠금은 획득 후 30초가 지나면 만료된다.",
            "description/383f1cd4" to "재시도는 3회까지 한다.",
            "description/93a3ada9" to "그 다음에는 실패를 돌려준다.",
            "description/cf590ed5" to "대기 간격은 ~~100ms~~ 50ms 이다.",
            "description/b8facbea" to "@mention 님 확인 부탁드립니다 :smile:",
            "description/02b39019" to "배경은 설계 문서 (https://kv-test.atlassian.net/wiki/x/AbC) 참고. 관련 이슈: https://kv-test.atlassian.net/browse/KV-9",
            "description/bcbff204" to "- 키마다 잠금은 하나다.",
            "description/ac636da3" to "  - 같은 키의 두 번째 요청은 기다린다.",
            "description/6844cd7e" to "- 먼저 온 요청이 잠금을 얻는다.",
            "description/fa713a38" to "- 나머지 요청은 줄을 선다.",
            "description/69011646" to "모드 | 잠금",
            "description/a6e83197" to "CREATE | 유지",
            "description/0e1887b1" to "[redacted by kv spec snapshot: auth-header]",
            "description/858aae13" to "warning: 만료된 잠금은 해제할 수 없다.",
            "description/6d5c37af" to "expand: 세부 규칙",
            "description/1f35263f" to "해제는 소유자만 한다.",
            "description/5a941dee" to "task: 경계 테스트 추가",
            "description/03fdc268" to "decision: 만료 경계는 초과로 본다.",
            "description/1129aec3" to "[not read: media a1b2c3d4 \"state.png\"] 상태 전이 그림",
            "description/44c4a99f" to "[not read: card https://kv-test.atlassian.net/wiki/spaces/KV/pages/1]",
            "description/a2ea898a" to "[not read: syncBlock rid-1]",
            "description/70fee00e" to "[not read: node futureNode]",
            "description/1140749b" to "문의는 [email] 또는 [phone] 로 한다.",
            "description/f0dc6b71" to "heading: 참고",
            "description/23025083-2" to "잠금은 획득 후 30초가 지나면 만료된다.",
            "customfield_10050/17173a29" to "30초 경계에서는 만료되지 않는다.",
            "customfield_10050/e31a0275" to "31초에는 만료된다.",
            "customfield_10060/a604f85b" to "잠금 TTL 조정",
            "comment/10071/c05a422f" to "확인했습니다.",
            "comment/10072/20b44600" to "논의 결과 만료 시간은 30초가 아니라 60초로 바꾼다.",
            "comment/10073/9c082cbf" to "제 생각에는 로그도 남기면 좋겠습니다.",
            "comment/10073/89c81a24" to "해제 실패는 예외를 던진다.",
            "comment/10074/81defb9e" to "[not read: restricted comment 10074]",
            "comment/10075/ce588d7e" to "[not read: restricted comment 10075]",
            "comment/10076/636d083d" to "[redacted by kv spec snapshot: assigned-credential]",
            "comment/10076/9825802a" to "배포 순서는 별도 문서에 둔다.",
            "attachment/9c372999" to "[not read: attachment 20001 \"state.png\" (image/png)]",
            "attachment/fd56a215" to "[not read: attachment 20002 \"spec.pdf\" (application/pdf)]",
            "parent/3de74c69" to "[not read: parent KV-1]",
            "subtask/998c7d2f" to "[not read: subtask KV-8]",
            "link/80a67d6c" to "[not read: link \"relates to\" KV-9]",
            "remotelink/ea8cf98d" to "[not read: remote link \"잠금 설계\" https://kv-test.atlassian.net/wiki/pages/viewpage.action?pageId=123]",
        )

    private fun snapshot(
        file: File,
        name: String = "jira:10042",
    ): Pair<List<Block>, List<String>> {
        val warnings = mutableListOf<String>()
        return snapshotBlocks(listOf(SnapshotInput(name, file.path))) { warnings += it } to warnings
    }

    /** A fetched issue with [fields] (JSON members), [comments] and [remoteLinks] (JSON values). */
    private fun issue(
        fields: String,
        comments: String = "[]",
        remoteLinks: String = "[]",
        extra: String = "",
    ) = File(dir, "1.jira.json").apply {
        writeText("""{"format": "kv-jira-issue/1", "issue": {"id": "1", "key": "T-1"$extra, "fields": {$fields}}, "remoteLinks": $remoteLinks, "comments": $comments}""")
    }

    private fun description(vararg nodes: String) = issue(""""description": {"version": 1, "type": "doc", "content": [${nodes.joinToString()}]}""")

    /** A paragraph of [text] (a JSON string body). */
    private fun p(text: String) = """{"type": "paragraph", "content": [{"type": "text", "text": "$text"}]}"""

    private fun texts(file: File) = snapshot(file, "jira:1").first.map { it.text }

    @Test
    fun `the fixture gives the registered blocks, anchors and fingerprints`() {
        // DESIGN-J.md predictions P3 and P4.
        val (blocks, _) = snapshot(fixture)
        assertEquals(golden.map { (a, t) -> "jira:10042#$a" to t }, blocks.map { it.anchor to it.text })
        blocks.forEach {
            assertEquals(reviewFingerprint(it.text), it.sha256, it.anchor)
            assertEquals(it.sha256.take(8), it.anchor.substringAfterLast('/').substringBefore('-'), it.anchor)
            assertEquals("jira:10042", it.source)
        }
        assertEquals(blocks.size, blocks.map { it.anchor }.toSet().size)
    }

    @Test
    fun `the fixture gives the registered warnings, and no warning holds text of the issue`() {
        // DESIGN-J.md prediction P5.
        val (_, warnings) = snapshot(fixture)
        val notRead = listOf("description/1129aec3", "description/44c4a99f", "description/a2ea898a", "description/70fee00e", "comment/10074/81defb9e", "comment/10075/ce588d7e", "attachment/9c372999", "attachment/fd56a215", "parent/3de74c69", "subtask/998c7d2f", "link/80a67d6c", "remotelink/ea8cf98d")
        val expected =
            notRead.map { "jira:10042#$it: the tool did not read this item; a person must check it (DESIGN-J.md 3.4)" } +
                listOf(
                    "jira:10042#description/02b39019: holds a link the tool does not follow (DESIGN-J.md 3.4)",
                    "jira:10042#description/0e1887b1: redacted, it holds a auth-header (DESIGN-C.md 6.3)",
                    "jira:10042#comment/10076/636d083d: redacted, it holds a assigned-credential (DESIGN-C.md 6.3)",
                    "jira:10042#description/1140749b: replaced 2 email or phone shape(s) (DESIGN-J.md 6.1)",
                    "jira:10042: field customfield_10070 (\"Rank\") holds a string the tool does not read (DESIGN-J.md 3.3)",
                    "jira:10042: field customfield_10080 (\"Development\") holds a string the tool does not read (DESIGN-J.md 3.3)",
                    "jira:10042: field worklog (\"Log Work\") holds rich text the tool does not read (DESIGN-J.md 3.4)",
                )
        assertEquals(19, expected.size)
        assertEquals(expected.sorted(), warnings.sorted())
    }

    @Test
    fun `a block's lines are the lines of its node in the fetched file`() {
        // DESIGN-J.md prediction P6. These lines were checked by reading the fixture itself.
        val byAnchor = snapshot(fixture).first.associateBy { it.anchor.substringAfter('#') }
        fun lines(anchor: String) = byAnchor.getValue(anchor).let { it.first to it.last }
        assertEquals(53 to 53, lines("summary/67df719f"))
        assertEquals(78 to 86, lines("description/23025083"))
        assertEquals(87 to 102, lines("description/383f1cd4"))
        assertEquals(87 to 102, lines("description/93a3ada9"))
        assertEquals(563 to 573, lines("attachment/9c372999"))
    }

    @Test
    fun `no name, address, account id, secret or restricted text reaches the output, and the fixture holds them all`() {
        // DESIGN-J.md predictions P9 and P10.
        val warnings = mutableListOf<String>()
        val out = File(dir, "snap")
        writeSnapshot(out, listOf(SnapshotInput("jira:10042", fixture.path))) { warnings += it }
        val written = File(out, SOURCES_JSON).readText() + File(out, "SOURCES.md").readText() + warnings.joinToString()
        val raw = fixture.readText()
        val hidden = listOf("Fixture Person Alpha", "fixture.alpha@example.com", "010-0000-0000", "fixture-account-0001", "abcdef0123456789abcdef", "hunter22x", "RESTRICTED-FIXTURE-TEXT", "요약: 스냅숏에 나오면 안 된다")
        for (value in hidden) {
            assertTrue(value in raw, "the fixture must hold $value, or this test proves nothing")
            assertTrue(value !in written, "$value leaked")
        }
    }

    @Test
    fun `an anchor holds no trace of a redacted value`() {
        // DESIGN-J.md prediction P11: two different secrets of one kind give one anchor.
        fun anchorOf(secret: String) = snapshot(description(p("password: $secret")), "jira:1").first.single().anchor
        assertEquals(anchorOf("hunter22x"), anchorOf("other99value"))
    }

    @Test
    fun `each ADF node gives its registered text and warning`() {
        // DESIGN-J.md 2.4, prediction P15: the node names of the schema and of the document page.
        val notRead = "the tool did not read"
        val cases =
            listOf(
                Triple("""{"type": "paragraph", "content": [{"type": "status", "attrs": {"text": "DONE", "color": "green"}}]}""", listOf("[DONE]"), emptyList<String>()),
                Triple("""{"type": "paragraph", "content": [{"type": "date", "attrs": {"timestamp": "1700000000000"}}]}""", listOf("[date 1700000000000]"), emptyList()),
                Triple("""{"type": "paragraph", "content": [{"type": "emoji", "attrs": {"text": "ok", "shortName": ":x:"}}]}""", listOf("ok"), emptyList()),
                Triple("""{"type": "paragraph", "content": [{"type": "mention", "attrs": {"id": "a"}}]}""", listOf("@mention"), emptyList()),
                Triple("""{"type": "paragraph", "content": [{"type": "placeholder", "attrs": {"text": "here"}}]}""", listOf("here"), emptyList()),
                Triple("""{"type": "paragraph", "content": [{"type": "inlineExtension", "attrs": {"extensionKey": "k"}}]}""", listOf("[not read: extension k]"), listOf(notRead)),
                Triple("""{"type": "paragraph", "content": [{"type": "mediaInline", "attrs": {"id": "m1", "alt": "a.png"}}]}""", listOf("[not read: media m1 \"a.png\"]"), listOf(notRead)),
                Triple("""{"type": "paragraph", "content": [{"type": "inlineCard", "attrs": {"data": {}}}]}""", listOf("[card]"), listOf("holds a link")),
                Triple("""{"type": "paragraph", "content": [{"type": "newInline"}]}""", listOf("[not read: node newInline]"), listOf(notRead)),
                Triple("""{"type": "paragraph", "content": [{"type": "newInline", "attrs": {"text": "t"}}]}""", listOf("t"), emptyList()),
                Triple("""{"type": "paragraph", "content": [{"type": "text", "text": "a\nb"}]}""", listOf("a", "b"), emptyList()),
                Triple("""{"type": "paragraph", "content": [{"type": "text", "text": "a   b "}]}""", listOf("a b"), emptyList()),
                Triple("""{"type": "paragraph", "content": []}""", emptyList(), emptyList()),
                Triple("""{"type": "paragraph", "content": [{"type": "text", "text": "x", "marks": [{"type": "strike"}, {"type": "link", "attrs": {"href": "https://h"}}]}]}""", listOf("~~x (https://h)~~"), listOf("holds a link")),
                Triple("""{"type": "paragraph", "content": [{"type": "text", "text": "https://h", "marks": [{"type": "link", "attrs": {"href": "https://h"}}, {"type": "strong"}]}]}""", listOf("https://h"), listOf("holds a link")),
                Triple("""{"type": "nestedExpand", "attrs": {"title": "t"}, "content": [${p("a")}]}""", listOf("expand: t", "a"), emptyList()),
                Triple("""{"type": "expand", "content": [${p("a")}]}""", listOf("expand:", "a"), emptyList()),
                Triple("""{"type": "blockquote", "content": [${p("a")}, ${p("b")}]}""", listOf("> a", "> b"), emptyList()),
                Triple("""{"type": "panel", "attrs": {"panelType": "custom"}, "content": [${p("a")}]}""", listOf("custom: a"), emptyList()),
                Triple("""{"type": "panel", "attrs": {"panelType": "note"}, "content": [{"type": "bulletList", "content": [{"type": "listItem", "content": [${p("a")}]}]}]}""", listOf("note: - a"), emptyList()),
                Triple("""{"type": "bulletList", "content": [{"type": "listItem", "content": [${p("a")}, ${p("b")}]}]}""", listOf("- a", "  b"), emptyList()),
                Triple("""{"type": "bulletList", "content": [{"type": "listItem", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "a"}, {"type": "hardBreak"}, {"type": "text", "text": "b"}]}]}]}""", listOf("- a", "  b"), emptyList()),
                Triple("""{"type": "taskList", "content": [{"type": "blockTaskItem", "attrs": {"state": "DONE"}, "content": [${p("a")}, ${p("b")}]}]}""", listOf("task: a b"), emptyList()),
                Triple("""{"type": "layoutSection", "content": [{"type": "layoutColumn", "attrs": {"width": 50.5}, "content": [${p("a")}]}]}""", listOf("a"), emptyList()),
                Triple("""{"type": "bodiedSyncBlock", "content": [${p("a")}]}""", listOf("a"), emptyList()),
                Triple("""{"type": "bodiedExtension", "attrs": {"extensionKey": "k"}, "content": [${p("a")}]}""", listOf("a"), listOf("node bodiedExtension")),
                Triple("""{"type": "multiBodiedExtension", "content": [{"type": "extensionFrame", "content": [${p("a")}]}]}""", listOf("a"), listOf("node multiBodiedExtension", "node extensionFrame")),
                Triple("""{"type": "extension", "attrs": {"extensionKey": "k", "text": "kept"}}""", listOf("kept"), emptyList()),
                Triple("""{"type": "extension", "attrs": {"extensionKey": "k"}}""", listOf("[not read: extension k]"), listOf(notRead)),
                Triple("""{"type": "mediaGroup", "content": [{"type": "media", "attrs": {"id": "m1"}}, {"type": "media", "attrs": {"id": "m2"}}]}""", listOf("[not read: media m1]", "[not read: media m2]"), listOf(notRead, notRead)),
                Triple("""{"type": "mediaSingle", "content": [{"type": "media", "attrs": {"url": "https://i"}}]}""", listOf("[not read: media https://i]"), listOf(notRead)),
                Triple("""{"type": "embedCard", "attrs": {"layout": "wide"}}""", listOf("[not read: card]"), listOf(notRead)),
                Triple("""{"type": "rule"}""", emptyList(), emptyList()),
                Triple("""{"type": "codeBlock", "content": [{"type": "text", "text": "  a\n    b\n"}]}""", listOf("  a\n    b"), emptyList()),
                Triple("""{"type": "codeBlock", "content": []}""", emptyList(), emptyList()),
                Triple("""{"type": "table", "content": [{"type": "tableRow", "content": [{"type": "tableCell", "content": [${p("a")}, ${p("b")}]}, {"type": "tableHeader", "content": [${p("c")}]}, {"type": "tableCell", "content": []}]}]}""", listOf("a b | c | "), emptyList()),
                Triple("""{"type": "table", "content": [{"type": "tableRow", "content": [{"type": "tableCell", "content": []}]}]}""", emptyList(), emptyList()),
            )
        for ((node, expected, warned) in cases) {
            val (blocks, warnings) = snapshot(description(node), "jira:1")
            assertEquals(expected, blocks.map { it.text }, node)
            assertEquals(warned.size, warnings.size, "$node gave $warnings")
            warned.forEach { w -> assertTrue(warnings.any { w in it }, "$node gave $warnings") }
        }
    }

    @Test
    fun `an anchor does not depend on a heading's level, a task's state or the place of a block`() {
        fun heading(level: Int) = """{"type": "heading", "attrs": {"level": $level}, "content": [{"type": "text", "text": "h"}]}"""
        fun task(state: String) = """{"type": "taskList", "content": [{"type": "taskItem", "attrs": {"state": "$state"}, "content": [{"type": "text", "text": "t"}]}]}"""
        fun anchors(vararg nodes: String) = snapshot(description(*nodes), "jira:1").first.map { it.anchor }.toSet()
        assertEquals(anchors(heading(1)), anchors(heading(4)))
        assertEquals(anchors(task("TODO")), anchors(task("DONE")))
        assertEquals(anchors(p("a"), p("b")), anchors(p("b"), p("a")))
        // Whitespace is no part of a fingerprint, so a space does not move an anchor.
        assertEquals(anchors(p("a b")), anchors(p("a   b")))
    }

    @Test
    fun `blocks with the same text get a counter in their part only`() {
        val blocks = snapshot(issue(""""description": {"version": 1, "type": "doc", "content": [${p("x")}, ${p("x")}, ${p("x")}]}, "environment": {"version": 1, "type": "doc", "content": [${p("x")}]}"""), "jira:1").first
        val hash = reviewFingerprint("x").take(8)
        assertEquals(listOf("description/$hash", "description/$hash-2", "description/$hash-3", "environment/$hash"), blocks.map { it.anchor.substringAfter('#') })
    }

    @Test
    fun `any field that holds an ADF document is text, and fields come in a fixed order`() {
        // DESIGN-J.md 3.1 and 3.2: free form promises no field, so no list of fields and no flag.
        val doc = """{"version": 1, "type": "doc", "content": [${p("x")}]}"""
        val file = issue(""""customfield_2": $doc, "environment": $doc, "someAppField": $doc, "description": $doc, "summary": "s"""")
        assertEquals(listOf("summary", "description", "environment", "customfield_2", "someAppField"), snapshot(file, "jira:1").first.map { it.anchor.substringAfter('#').substringBefore('/') })
    }

    @Test
    fun `a string custom field is text only if its schema says so, and its value stays out of a warning`() {
        val file =
            issue(
                """"customfield_1": "line one\n\nline two", "customfield_2": "short", "customfield_3": "skipped value", "created": "2026-01-01"""",
                extra = """, "names": {"customfield_3": "Other"}, "schema": {"customfield_1": {"custom": "x:textarea"}, "customfield_2": {"custom": "x:textfield"}, "customfield_3": {"custom": "x:rank"}}""",
            )
        val (blocks, warnings) = snapshot(file, "jira:1")
        assertEquals(listOf("line one", "line two", "short"), blocks.map { it.text })
        assertEquals(listOf("jira:1: field customfield_3 (\"Other\") holds a string the tool does not read (DESIGN-J.md 3.3)"), warnings)
    }

    @Test
    fun `a comment field in the issue response is not read and raises no warning`() {
        // DESIGN-J.md 7.2 rule 3: the comment pages are the one source of comments.
        val file = issue(""""comment": {"comments": [{"id": "9", "body": {"version": 1, "type": "doc", "content": [${p("hidden")}]}}]}""")
        val (blocks, warnings) = snapshot(file, "jira:1")
        assertEquals(emptyList(), blocks)
        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `an ADF version other than 1 is read with a warning`() {
        val (blocks, warnings) = snapshot(issue(""""description": {"version": 2, "type": "doc", "content": [${p("x")}]}"""), "jira:1")
        assertEquals(listOf("x"), blocks.map { it.text })
        assertTrue("ADF version 2" in warnings.single(), warnings.toString())
    }

    @Test
    fun `a restricted comment and an internal note are not read, and a public comment is`() {
        // DESIGN-J.md 5.2 and 5.3.
        val body = """{"version": 1, "type": "doc", "content": [${p("secret plan")}]}"""
        val comments =
            """[
            {"id": "1", "created": "t1", "updated": "t1", "body": $body, "visibility": {"type": "role", "value": "Dev"}},
            {"id": "2", "created": "t2", "updated": "t2", "body": $body, "jsdPublic": false},
            {"id": "3", "created": "t3", "updated": "t3", "body": $body, "jsdPublic": true},
            {"id": "4", "created": "t4", "updated": "t4", "body": $body, "visibility": null},
            {"id": "5", "created": "t5", "updated": "t6", "body": "not a document"}
            ]"""
        val texts = texts(issue(""""summary": "s"""", comments))
        assertEquals(listOf("s", "[not read: restricted comment 1]", "[not read: restricted comment 2]", "secret plan", "secret plan", "[not read: comment body 5]"), texts)
    }

    @Test
    fun `a failed remote link fetch gives a warning, and an inward link names its own verb`() {
        val file = issue(""""issuelinks": [{"type": {"inward": "is blocked by", "outward": "blocks"}, "inwardIssue": {"key": "T-2"}}], "sub-tasks": [{"outwardIssue": {"key": "T-3"}}]""", remoteLinks = "null")
        val (blocks, warnings) = snapshot(file, "jira:1")
        assertEquals(listOf("[not read: subtask T-3]", "[not read: link \"is blocked by\" T-2]"), blocks.map { it.text })
        assertTrue(warnings.any { "got no remote links" in it }, warnings.toString())
    }

    @Test
    fun `SOURCES_md names the key and the time in the head, gives the guidance once, and heads each part`() {
        // DESIGN-J.md 5.6.
        val out = File(dir, "snap")
        val md = File(dir, "plain.md").apply { writeText("# T\n\nPlain.\n") }
        writeSnapshot(out, listOf(SnapshotInput("jira:10042", fixture.path), SnapshotInput("doc", md.path)))
        val text = File(out, "SOURCES.md").readText()
        assertTrue("## jira:10042 (KV-7, status In Progress, updated 2026-11-01T10:00:00.000+0900)\n" in text, text)
        assertEquals(1, Regex("This source is a Jira issue in free form").findAll(text).count())
        assertTrue(text.indexOf("This source is a Jira issue") < text.indexOf("### summary") && text.indexOf("## doc") > text.indexOf("### remotelink"), text)
        for (head in listOf("### summary", "### description", "### customfield_10050 (\"수용 기준\")", "### customfield_10060 (\"요약 메모\")", "### comment 10071 (created 2026-10-20T09:00:00.000+0900)", "### comment 10072 (created 2026-10-20T10:00:00.000+0900, edited 2026-10-21T09:00:00.000+0900)", "### attachment", "### parent", "### subtask", "### link", "### remotelink")) {
            assertTrue("\n$head\n" in text, "no line $head")
        }
        assertTrue("[jira:10042#description/23025083] (lines 78-86)\n잠금은 획득 후 30초가 지나면 만료된다.\n" in text, text)
    }

    @Test
    fun `a snapshot of an issue reads back as the same blocks`() {
        val out = File(dir, "snap")
        val written = writeSnapshot(out, listOf(SnapshotInput("jira:10042", fixture.path)))
        assertEquals(43, written.size)
        assertEquals(written, readSnapshot(out))
    }

    @Test
    fun `a wrong name, a wrong format and broken JSON are usage errors that name the file, and other json is Markdown`() {
        // DESIGN-J.md prediction P17.
        fun cli(input: String): Pair<Int, String> {
            val err = ByteArrayOutputStream()
            val exit = runCli(listOf("spec", "snapshot", "--out", File(dir, "out").path, input), PrintStream(ByteArrayOutputStream()), PrintStream(err))
            return exit to err.toString()
        }
        assertEquals(0, cli("jira:10042=${fixture.path}").first)
        val (wrongName, nameErr) = cli("jira:KV-7=${fixture.path}")
        assertEquals(2, wrongName)
        assertTrue("the source name must be jira:10042, not jira:KV-7" in nameErr && fixture.path in nameErr, nameErr)
        val format = File(dir, "f.jira.json").apply { writeText("""{"format": "other/9", "issue": {"id": "1"}}""") }
        val (wrongFormat, formatErr) = cli("jira:1=${format.path}")
        assertEquals(2, wrongFormat)
        assertTrue("not a file of jira_fetch.py" in formatErr && format.path in formatErr, formatErr)
        val broken = File(dir, "b.jira.json").apply { writeText("{\"format\": ") }
        val (brokenJson, brokenErr) = cli("jira:1=${broken.path}")
        assertEquals(2, brokenJson)
        assertTrue("JSON offset" in brokenErr && broken.path in brokenErr, brokenErr)
        val noId = File(dir, "n.jira.json").apply { writeText("""{"format": "kv-jira-issue/1", "issue": {"key": "K-1"}}""") }
        assertEquals(2, cli("jira:1=${noId.path}").first)
        val other = File(dir, "other.json").apply { writeText("{\"a\": 1}\n") }
        assertEquals(listOf("{\"a\": 1}"), snapshot(other, "x").first.map { it.text })
        assertFailsWith<SnapshotException> { snapshot(format, "jira:1") }
    }
}
