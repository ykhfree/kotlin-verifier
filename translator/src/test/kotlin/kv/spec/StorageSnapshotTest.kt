package kv.spec

import kv.cli.runCli
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * C6 (DESIGN-C.md 10.8): `kv spec snapshot` on Confluence storage format (acceptance 1), the section name of a
 * non-ASCII heading, and the secret check on every input (acceptance 4). No Confluence connection: the fixture follows
 * the documented storage format.
 */
class StorageSnapshotTest {
    @TempDir
    lateinit var dir: File

    private val fixture = File(javaClass.getResource("/confluence/page.xhtml")!!.toURI())

    private fun file(
        name: String,
        text: String,
    ) = File(dir, name).apply { writeText(text) }

    /** The blocks of [file] as source `conf:1`, and the warnings. */
    private fun blocks(file: File): Pair<List<Block>, List<String>> {
        val warnings = mutableListOf<String>()
        return snapshotBlocks(listOf(SnapshotInput("conf:1", file.path))) { warnings += it } to warnings
    }

    @Test
    fun `the fixture page gives one block per paragraph, list item, table row, macro and task, in sections`() {
        val (blocks, warnings) = blocks(fixture)
        val expected =
            listOf(
                "conf:1#top/1" to "Intro before any heading.",
                "conf:1#잠금-규칙/1" to "A lock expires once lockTimeoutMillis has passed—not before.",
                "conf:1#잠금-규칙/2" to "Line one\nline two",
                "conf:1#잠금-규칙/3" to "- Top item with KeyLock Spec\n  - Nested item",
                "conf:1#잠금-규칙/4" to "- Second item",
                "conf:1#잠금-규칙/5" to "1. First step",
                "conf:1#잠금-규칙/6" to "2. Second step",
                "conf:1#table-macros/1" to "Mode | Lock",
                "conf:1#table-macros/2" to "CREATE | held",
                "conf:1#table-macros/3" to "val a = \"&nbsp;\" // kept\nif (a < b) unLock()",
                "conf:1#table-macros/4" to "info: Note on expiry\nThe lease is the deadline.\n- Not extended",
                "conf:1#table-macros/5" to "expand: Details\nHidden text.",
                "conf:1#table-macros/6" to "[ ] Write the probe",
                "conf:1#table-macros/7" to "[x] Sign it",
                "conf:1#h3/1" to "Left cell",
                "conf:1#h3/2" to "Right cell",
                "conf:1#h3/3" to "Status body",
                "conf:1#h3/4" to "ADF panel text",
            )
        assertEquals(expected, blocks.map { it.anchor to it.text })
        // Each block keeps the lines of its element in the file: the synthetic root adds no line.
        val lines = blocks.associate { it.anchor to (it.first to it.last) }
        assertEquals(1 to 1, lines["conf:1#top/1"])
        assertEquals(6 to 10, lines["conf:1#잠금-규칙/3"])
        assertEquals(22 to 26, lines["conf:1#table-macros/3"])
        // The jira macro has no text and gives no block; both unknown macros give a warning.
        assertEquals(2, warnings.size, warnings.toString())
        assertTrue("unknown macro 'jira' at line 44" in warnings[0] && "unknown macro 'status' at line 45" in warnings[1], warnings.toString())
    }

    @Test
    fun `a non-breaking space is a space, and an entity inside CDATA stays as written`() {
        val (blocks, _) = blocks(file("p.xhtml", "<p>a b&nbsp;&nbsp;c</p><ac:structured-macro ac:name=\"code\"><ac:plain-text-body><![CDATA[x&amp;y &nbsp;]]></ac:plain-text-body></ac:structured-macro>"))
        assertEquals(listOf("a b c", "x&amp;y &nbsp;"), blocks.map { it.text })
    }

    @Test
    fun `an unknown entity and malformed XML are errors that name the file`() {
        val entity = assertFailsWith<SnapshotException> { blocks(file("e.xhtml", "<p>&bogus;</p>")) }
        assertTrue("e.xhtml: unknown entity &bogus;" in entity.message!!, entity.message)
        val broken = assertFailsWith<SnapshotException> { blocks(file("b.xhtml", "<p>one</p>\n<p>two</q>")) }
        assertTrue("b.xhtml: not well-formed storage format at line 2" in broken.message!!, broken.message)
    }

    @Test
    fun `a heading of letters other than a-z keeps them, and a heading of symbols only is h and its number`() {
        val md = file("doc.md", "# 개요\n\ntext\n\n## Größe & Limit\n\nmore\n\n## ***\n\nlast\n")
        assertEquals(listOf("doc#개요/1", "doc#größe-limit/1", "doc#h3/1"), snapshotBlocks(listOf(SnapshotInput("doc", md.path))).map { it.anchor })
    }

    @Test
    fun `SOURCES_md names the page title and version from the page json beside the storage file`() {
        val page = file("42.xhtml", "<h1>Rules</h1><p>One rule.</p>")
        file("42.page.json", """{"id": "42", "title": "Lock rules", "status": "current", "version": 7, "createdAt": "2026-10-01T10:00:00.000Z", "site": "https://example.atlassian.net", "fetchedAt": "2026-10-28T00:00:00Z"}""")
        val out = File(dir, "snap")
        writeSnapshot(out, listOf(SnapshotInput("conf:42", page.path)))
        val md = File(out, "SOURCES.md").readText()
        assertTrue("## conf:42 (\"Lock rules\", version 7)\n" in md, md)
        assertTrue("### Rules\n\n[conf:42#rules/1] (lines 1-1)\nOne rule.\n" in md, md)
    }

    /** One fixture secret of each kind; each value is made up for this test. */
    private val secrets =
        listOf(
            "private-key" to "-----BEGIN RSA PRIVATE KEY-----",
            "aws-access-key" to "AKIAABCDEFGHIJKLMNOP",
            "atlassian-api-token" to "ATATT3xFfGF0aBcDeFgHiJkLmNoPqRsTuV",
            "github-token" to "ghp_" + "a1".repeat(18),
            "slack-token" to "xoxb-1234567890-abcdef",
            "anthropic-key" to "sk-ant-api03-" + "x".repeat(24),
            "openai-key" to "sk-" + "Q".repeat(24),
            "jwt" to "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop",
            "assigned-credential" to "password: hunter22x",
        )

    @Test
    fun `a block that holds a secret shape is redacted, and no output and no warning holds the value`() {
        val md = file("s.md", "# Secrets\n\n" + secrets.joinToString("") { (_, v) -> "Value $v here.\n\n" } + "A plain sentence.\n")
        val page = file("s.xhtml", "<p>key ${secrets[1].second}</p><p>fine</p>")
        val warnings = mutableListOf<String>()
        val out = File(dir, "snap")
        val blocks = writeSnapshot(out, listOf(SnapshotInput("s", md.path), SnapshotInput("conf:9", page.path))) { warnings += it }
        val kinds = secrets.map { it.first }
        assertEquals(kinds.map { "[redacted by kv spec snapshot: $it]" } + "A plain sentence." + "[redacted by kv spec snapshot: aws-access-key]" + "fine", blocks.map { it.text })
        assertEquals(kinds.mapIndexed { i, k -> "s#secrets/${i + 1}: redacted, it holds a $k (DESIGN-C.md 6.3)" } + "conf:9#top/1: redacted, it holds a aws-access-key (DESIGN-C.md 6.3)", warnings)
        val written = File(out, "sources.json").readText() + File(out, "SOURCES.md").readText() + warnings.joinToString()
        secrets.forEach { (k, v) -> assertTrue(v !in written, "$k value leaked") }
    }

    @Test
    fun `code that names a token without a digit in its value is no secret`() {
        val md = file("k.md", "token = nextToken()\n\npassword: required\n")
        assertEquals(listOf("token = nextToken()", "password: required"), snapshotBlocks(listOf(SnapshotInput("k", md.path))).map { it.text })
    }

    @Test
    fun `kv spec snapshot prints the warnings, and a bad storage file is a usage error`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = runCli(listOf("spec", "snapshot", "--out", File(dir, "snap").path, "conf:1=${fixture.path}"), PrintStream(out), PrintStream(err))
        assertEquals(0, exit, err.toString())
        assertTrue("18 blocks" in out.toString(), out.toString())
        assertEquals(2, err.toString().lines().count { it.startsWith("kv: warning: ") && "unknown macro" in it }, err.toString())
        val bad = runCli(listOf("spec", "snapshot", "--out", File(dir, "snap2").path, "conf:2=${file("bad.xhtml", "<p>&bogus;</p>").path}"), PrintStream(out), PrintStream(err))
        assertEquals(2, bad)
        assertTrue("unknown entity &bogus;" in err.toString(), err.toString())
    }
}
