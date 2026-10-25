package kv.spec

import kv.ReqShield
import kv.emit.python
import kv.json.JsonException
import kv.json.json
import kv.json.parseJson
import kv.sidecar.reviewFingerprint
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The DESIGN-C pilot directory (pilot-c/). Read only. */
val pilotDir = File(System.getProperty("kv.pilotDir"))

/** The five inputs of the pilot snapshot pilot-c/packet/sources.json, in its order, from the req-shield checkout. */
val pilotInputs =
    listOf(
        SnapshotInput("README.md", File(ReqShield.dir, "README.md").path),
        SnapshotInput("CLAUDE.md", File(ReqShield.dir, "CLAUDE.md").path),
        SnapshotInput("KeyLock.kt(core)", File(ReqShield.coreDir, "KeyLock.kt").path),
        SnapshotInput("KeyLock.kt(reactor)", File(ReqShield.reactorDir, "KeyLock.kt").path),
        SnapshotInput("KeyLock.kt(coroutine)", File(ReqShield.coroutineDir, "KeyLock.kt").path),
    )

/**
 * C0 acceptance 1 (DESIGN-C.md 8): `kv spec snapshot` splits Markdown and KDoc into the blocks of the pilot's
 * tools/snapshot.py, with the same anchors, line ranges, texts and fingerprints, and writes the same two files.
 */
class SnapshotTest {
    @TempDir
    lateinit var dir: File

    /** The checkout path the pilot's sources.json records for every input. */
    private val pilotCheckout = "/Users/ykhfree/IdeaProjects/req-shield/"

    @Test
    fun `the pilot inputs give the 128 blocks of pilot-c sources_json, and the same two files`() {
        val out = File(dir, "snap")
        val blocks = writeSnapshot(out, pilotInputs)
        val expected = readSnapshot(File(pilotDir, "packet"))
        assertEquals(128, expected.size)
        assertEquals(listOf(48, 72, 3, 2, 3), pilotInputs.map { i -> blocks.count { it.source == i.name } })
        // The pilot ran on the default checkout; the paths are the only difference a different checkout makes.
        val relative = { b: Block -> b.copy(path = b.path.removePrefix(pilotCheckout).removePrefix(ReqShield.dir.path + "/")) }
        assertEquals(expected.map(relative), blocks.map(relative))
        val json = File(pilotDir, "packet/sources.json").readText().replace(pilotCheckout, ReqShield.dir.path + "/")
        assertEquals(json, File(out, "sources.json").readText())
        assertEquals(File(pilotDir, "packet/SOURCES.md").readText(), File(out, "SOURCES.md").readText())
    }

    @Test
    fun `a block's fingerprint is the reviewed-note fingerprint of its text`() {
        val blocks = snapshotBlocks(pilotInputs)
        blocks.forEach { assertEquals(reviewFingerprint(it.text), it.sha256, it.anchor) }
        // README.md:118-121, the block that holds "once `lockTimeoutMillis` has passed".
        val lease = blocks.single { it.anchor == "README.md#local-lock-map-size/2" }
        assertEquals(118 to 121, lease.first to lease.last)
        assertTrue("once `lockTimeoutMillis` has passed" in lease.text)
    }

    private val markdown =
        """
        |Text before the first heading.
        |It goes on here.
        |
        |# Title: with *punctuation*!
        |
        |- first item
        |  continued, indented
        |
        |  ```yaml
        |  inside: the item
        |  ```
        |
        |  still the first item
        |- second item
        |1. a numbered item
        |A paragraph right after the item, not indented.
        |- an item after the paragraph
        |
        |```
        |a fence
        |
        |with a blank line
        |```
        |#not a heading, but it ends nothing before it
        |## Second section
        |
        |
        |Last paragraph.
        |
        |
        """.trimMargin()

    private val kotlin =
        """
        |/*
        | * License header: a plain comment, no block.
        | */
        |package p
        |
        |/**
        | * Locks a key.
        | *
        | * @return a token
        | */
        |interface Lock {
        |    /** One line. */
        |
        |    suspend fun tryLock(key: String): String?
        |
        |    /**
        |     * A value, so no fun, interface or class name.
        |     */
        |    val size: Int
        |}
        """.trimMargin()

    @Test
    fun `the block rules on edge cases - sections, list items, paragraphs, fences, KDoc names`() {
        val md = File(dir, "doc.md").apply { writeText(markdown) }
        val kt = File(dir, "Lock.kt").apply { writeText(kotlin) }
        val blocks = snapshotBlocks(listOf(SnapshotInput("doc", md.path), SnapshotInput("lock", kt.path)))
        assertEquals(
            listOf(
                "doc#top/1" to (1 to 2),
                "doc#title-with-punctuation/1" to (6 to 13),
                "doc#title-with-punctuation/2" to (14 to 14),
                "doc#title-with-punctuation/3" to (15 to 15),
                "doc#title-with-punctuation/4" to (16 to 16),
                "doc#title-with-punctuation/5" to (17 to 17),
                "doc#title-with-punctuation/6" to (19 to 23),
                "doc#title-with-punctuation/7" to (24 to 24),
                "doc#second-section/1" to (28 to 28),
                "lock#Lock" to (6 to 10),
                "lock#tryLock" to (12 to 12),
                "lock#decl16" to (16 to 18),
            ),
            blocks.map { it.anchor to (it.first to it.last) },
        )
        assertEquals("Locks a key.\n\n@return a token", blocks.single { it.anchor == "lock#Lock" }.text)
        assertEquals("One line.", blocks.single { it.anchor == "lock#tryLock" }.text)
        assertEquals("- first item\n  continued, indented\n\n  ```yaml\n  inside: the item\n  ```\n\n  still the first item", blocks[1].text)
    }

    @Test
    fun `on edge cases and CRLF line breaks the files equal what pilot-c tools snapshot_py writes`() {
        val inputs =
            listOf(
                "doc" to File(dir, "doc.md").apply { writeText(markdown) },
                "crlf" to File(dir, "crlf.md").apply { writeText(markdown.replace("\n", "\r\n")) },
                "lock" to File(dir, "Lock.kt").apply { writeText(kotlin) },
                "tabs \"quoted\"" to File(dir, "tabs.md").apply { writeText("# T\n\na\tb \\ c\n") },
            )
        val kotlinOut = File(dir, "kt")
        writeSnapshot(kotlinOut, inputs.map { (n, f) -> SnapshotInput(n, f.path) })
        val pythonOut = File(dir, "py").apply { mkdirs() }
        val (code, log) = python(File(pilotDir, "tools/snapshot.py").path, pythonOut.path, *inputs.map { (n, f) -> "$n=${f.path}" }.toTypedArray())
        assertEquals(0, code, log)
        for (name in listOf("sources.json", "SOURCES.md")) assertEquals(File(pythonOut, name).readText(), File(kotlinOut, name).readText(), name)
    }

    @Test
    fun `reading a snapshot back gives the blocks it wrote, and a missing or malformed file is an error`() {
        val out = File(dir, "snap")
        val written = writeSnapshot(out, pilotInputs.take(1))
        assertEquals(written, readSnapshot(out))
        assertTrue("no " in assertFailsWith<SnapshotException> { readSnapshot(File(dir, "none")) }.message!!)
        File(out, "sources.json").writeText("[{\"anchor\": \"a\"}]")
        assertTrue("block 0 needs" in assertFailsWith<SnapshotException> { readSnapshot(out) }.message!!)
        File(out, "sources.json").writeText("[{\"anchor\": \"a\",]")
        assertTrue("JSON offset" in assertFailsWith<SnapshotException> { readSnapshot(out) }.message!!)
    }

    @Test
    fun `the JSON reader reads what the writer writes, escapes included`() {
        val s = "q\"b\\s/\n\t\r\b\u000c\u0001 ü"
        assertEquals(s, parseJson(json(s)))
        assertEquals(listOf(mapOf("a" to 1L, "b" to listOf(true, false, null), "c" to "x")), parseJson(" [ {\"a\": 1, \"b\": [true, false, null], \"c\": \"\\u0078\"} ] "))
        assertEquals(-12L, parseJson("-12"))
        for (bad in listOf("[1,]", "{\"a\" 1}", "\"open", "[1] 2", "nul")) assertFailsWith<JsonException>(bad) { parseJson(bad) }
    }
}
