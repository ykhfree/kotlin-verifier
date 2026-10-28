package kv.spec

import kv.json.JsonException
import kv.json.json
import kv.json.parseJson
import kv.sidecar.reviewFingerprint
import java.io.File

// `kv spec snapshot` (DESIGN-C.md 2.2 step 1, 5.2 rule 1): Markdown files, Kotlin KDoc and Confluence storage format
// (C6, 10.8) split into blocks with anchors and fingerprints. The Markdown and KDoc rules are those of the pilot's
// pilot-c/tools/snapshot.py, and the two output files are the ones it writes: sources.json (what kv spec stale reads)
// and SOURCES.md (what a person or an LLM reads). A block that holds a secret shape is redacted (10.8, Secrets.kt).

/**
 * One block of a snapshot. [anchor] is `SOURCE#section-slug/N` for Markdown (N counts the blocks of the section) and
 * `SOURCE#declaration` for KDoc; [first] and [last] are its 1-based lines in [path]; [sha256] is the fingerprint of
 * [text] ([reviewFingerprint]).
 */
data class Block(
    val anchor: String,
    val source: String,
    val path: String,
    val first: Int,
    val last: Int,
    val text: String,
    val sha256: String,
)

/**
 * One `NAME=FILE` input: a `.kt` file contributes its KDoc, a `.xhtml` file is a Confluence storage value (NAME is
 * `conf:<page id>`, DESIGN-C.md 6.2), any other file is Markdown.
 */
data class SnapshotInput(
    val name: String,
    val path: String,
) {
    val kotlin: Boolean get() = path.endsWith(".kt")
    val storage: Boolean get() = path.endsWith(".xhtml")
}

/** A block and the heading SOURCES.md shows for it: the section heading (Markdown) or the declaration line (KDoc). */
private data class Cut(
    val block: Block,
    val heading: String,
)

/** The blocks of [inputs], in input order. [warn] gets each warning: an unknown macro, a redacted block. */
fun snapshotBlocks(
    inputs: List<SnapshotInput>,
    warn: (String) -> Unit = {},
): List<Block> = cuts(inputs, warn).flatMap { (_, cuts) -> cuts.map { it.block } }

private fun cuts(
    inputs: List<SnapshotInput>,
    warn: (String) -> Unit,
): List<Pair<SnapshotInput, List<Cut>>> =
    inputs.map { input ->
        val text = File(input.path).readText()
        val cuts =
            when {
                input.kotlin -> kdocCuts(input, splitLines(text))
                input.storage -> storageCuts(input, text, warn)
                else -> markdownCuts(input, splitLines(text))
            }
        input to cuts.map { redact(it, warn) }
    }

/** [cut] with its text replaced if it holds a secret shape; [warn] names the block and the kind, never the value. */
private fun redact(
    cut: Cut,
    warn: (String) -> Unit,
): Cut {
    val kind = secretKind(cut.block.text) ?: return cut
    warn("${cut.block.anchor}: redacted, it holds a $kind (DESIGN-C.md 6.3)")
    val text = redacted(kind)
    return cut.copy(block = cut.block.copy(text = text, sha256 = reviewFingerprint(text)))
}

/** The blocks of a Confluence storage value (DESIGN-C.md 10.8, Storage.kt). */
private fun storageCuts(
    input: SnapshotInput,
    text: String,
    warn: (String) -> Unit,
): List<Cut> =
    storageCuts(input.path, text, ::slug, warn).map { b ->
        Cut(Block("${input.name}#${b.section}/${b.number}", input.name, input.path, b.first, b.last, b.text, reviewFingerprint(b.text)), b.heading)
    }

/** The lines of [text] without their line breaks (`\n`, `\r\n` or `\r`); a final line break ends the last line. */
private fun splitLines(text: String): List<String> {
    val lines = text.split(Regex("\r\n|\r|\n"))
    return if (lines.last().isEmpty()) lines.dropLast(1) else lines
}

/**
 * A section's name in an anchor: [title] in lower case, each run of characters other than letters and digits as `-`
 * (DESIGN-C.md 10.8: a Korean title keeps its letters). An empty result is `h` and the 1-based heading [number].
 */
private fun slug(
    title: String,
    number: Int,
): String = title.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), "-").trim('-').ifEmpty { "h$number" }

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val FENCE = Regex("""^\s*```""")
private val LIST_ITEM = Regex("""^- |^\d+\. """)
private val ITEM_OR_HEADING = Regex("""^- |^\d+\. |^#""")
private val PARAGRAPH_END = Regex("""^- |^\d+\. |^#|^\s*```""")

/**
 * Markdown blocks: a heading starts a section and is no block. A block is a fenced block, a top-level list item with
 * everything indented under it, or a paragraph (lines up to a blank line, a list item, a heading or a fence).
 * Trailing blank lines are not part of a block. Text before the first heading is section `top`.
 */
private fun markdownCuts(
    input: SnapshotInput,
    lines: List<String>,
): List<Cut> {
    val out = mutableListOf<Cut>()
    var section = "top"
    var heading = "(top)"
    var headings = 0
    var counter = 0
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val h = HEADING.find(line)
        if (h != null) {
            section = slug(h.groupValues[2], ++headings)
            heading = h.groupValues[2].trim()
            counter = 0
            i++
            continue
        }
        if (line.isBlank()) {
            i++
            continue
        }
        val start = i
        when {
            FENCE.containsMatchIn(line) -> {
                i++
                while (i < lines.size && !FENCE.containsMatchIn(lines[i])) i++
                i = minOf(i + 1, lines.size)
            }
            LIST_ITEM.containsMatchIn(line) -> {
                i++
                while (i < lines.size) {
                    val next = lines[i]
                    if (ITEM_OR_HEADING.containsMatchIn(next) || next.isNotBlank() && !next.startsWith(" ")) break
                    i++
                }
            }
            else -> {
                i++
                while (i < lines.size && lines[i].isNotBlank() && !PARAGRAPH_END.containsMatchIn(lines[i])) i++
            }
        }
        while (i > start && lines[i - 1].isBlank()) i--
        counter++
        val text = lines.subList(start, i).joinToString("\n")
        out += Cut(Block("${input.name}#$section/$counter", input.name, input.path, start + 1, i, text, reviewFingerprint(text)), heading)
    }
    return out
}

private val DECLARED = Regex("""(?:fun|interface|class)\s+(\w+)""")

/**
 * KDoc blocks: one per doc comment (a line that starts with slash, star, star), named after the declaration on the
 * next non-blank line (`fun`, `interface` or `class` NAME), else `declN` for the comment's line N. The text drops the
 * comment markers and the leading star of each line.
 */
private fun kdocCuts(
    input: SnapshotInput,
    lines: List<String>,
): List<Cut> {
    val out = mutableListOf<Cut>()
    var i = 0
    while (i < lines.size) {
        if (lines[i].trim().startsWith("/**")) {
            val start = i
            while (i < lines.size - 1 && "*/" !in lines[i]) i++
            val body =
                lines.subList(start, i + 1).map {
                    it.trim().replace(Regex("""^/\*\*\s?"""), "").replace(Regex("""\s?\*/$"""), "").replace(Regex("""^\*\s?"""), "")
                }
            var declLine = i + 1
            while (declLine < lines.size && lines[declLine].isBlank()) declLine++
            val decl = if (declLine < lines.size) lines[declLine].trim() else ""
            val who = DECLARED.find(decl)?.groupValues?.get(1) ?: "decl${start + 1}"
            val text = body.joinToString("\n").trim()
            out += Cut(Block("${input.name}#$who", input.name, input.path, start + 1, i + 1, text, reviewFingerprint(text)), decl)
        }
        i++
    }
    return out
}

/** Writes [dir]/sources.json and [dir]/SOURCES.md for [inputs]; returns the blocks. [warn] gets each warning. */
fun writeSnapshot(
    dir: File,
    inputs: List<SnapshotInput>,
    warn: (String) -> Unit = {},
): List<Block> {
    val cuts = cuts(inputs, warn)
    val blocks = cuts.flatMap { (_, c) -> c.map { it.block } }
    dir.mkdirs()
    File(dir, SOURCES_JSON).writeText(sourcesJson(blocks))
    File(dir, "SOURCES.md").writeText(sourcesMd(cuts))
    return blocks
}

const val SOURCES_JSON = "sources.json"

/** The blocks as JSON, laid out as Python's `json.dump(blocks, f, indent=1, ensure_ascii=False)` lays them out. */
private fun sourcesJson(blocks: List<Block>): String =
    if (blocks.isEmpty()) {
        "[]"
    } else {
        blocks.joinToString(",\n", "[\n", "\n]") { b ->
            listOf(
                "anchor" to json(b.anchor),
                "source" to json(b.source),
                "path" to json(b.path),
                "first" to "${b.first}",
                "last" to "${b.last}",
                "text" to json(b.text),
                "sha256" to json(b.sha256),
            ).joinToString(",\n", " {\n", "\n }") { (k, v) -> "  \"$k\": $v" }
        }
    }

/**
 * ` ("TITLE", version N)` from the `<id>.page.json` that `confluence_fetch.py` writes beside `<id>.xhtml`
 * (DESIGN-C.md 6.2 rule 4: the page version goes in the snapshot head), or "" if there is none.
 */
private fun pageLabel(input: SnapshotInput): String {
    val page = File(input.path.removeSuffix(".xhtml") + ".page.json")
    if (!page.isFile) return ""
    val m =
        try {
            parseJson(page.readText()) as? Map<*, *>
        } catch (e: JsonException) {
            throw SnapshotException("${page.path}: ${e.message}")
        } ?: throw SnapshotException("${page.path}: expected a JSON object")
    val title = m["title"] as? String ?: throw SnapshotException("${page.path}: needs the string title")
    val version = m["version"] as? Long ?: throw SnapshotException("${page.path}: needs the number version")
    return " (${json(title)}, version $version)"
}

private fun sourcesMd(cuts: List<Pair<SnapshotInput, List<Cut>>>): String {
    val md =
        mutableListOf(
            "# Sources\n",
            "This is the team's specification text, split into blocks. Each block starts with its anchor in square",
            "brackets and its line numbers. When you cite a block, give its anchor and quote its words exactly as written",
            "here (a quote may be part of a block; it must not join text from two blocks).\n",
        )
    for ((input, list) in cuts) {
        md += "\n## ${input.name}${if (input.storage) pageLabel(input) else ""}\n"
        var lastHeading: String? = null
        for ((b, heading) in list) {
            if (input.kotlin) {
                md += "[${b.anchor}] (lines ${b.first}-${b.last}, KDoc of: $heading)\n${b.text}\n"
            } else {
                if (heading != lastHeading) {
                    md += "### $heading\n"
                    lastHeading = heading
                }
                md += "[${b.anchor}] (lines ${b.first}-${b.last})\n${b.text}\n"
            }
        }
    }
    return md.joinToString("\n")
}

class SnapshotException(
    message: String,
) : Exception(message)

/** The blocks of the snapshot in [dir] (its sources.json). Throws [SnapshotException] if it is missing or malformed. */
fun readSnapshot(dir: File): List<Block> {
    val file = File(dir, SOURCES_JSON)
    if (!file.isFile) throw SnapshotException("no ${file.path}: the snapshot directory holds what kv spec snapshot wrote")
    val list =
        try {
            parseJson(file.readText())
        } catch (e: JsonException) {
            throw SnapshotException("${file.path}: ${e.message}")
        }
    if (list !is List<*>) throw SnapshotException("${file.path}: expected a JSON array of blocks")
    return list.mapIndexed { n, o ->
        fun bad(): Nothing = throw SnapshotException("${file.path}: block $n needs the strings anchor, source, path, text, sha256 and the numbers first, last")
        val m = o as? Map<*, *> ?: bad()
        fun str(k: String) = m[k] as? String ?: bad()
        fun int(k: String) = (m[k] as? Long)?.toInt() ?: bad()
        Block(str("anchor"), str("source"), str("path"), int("first"), int("last"), str("text"), str("sha256"))
    }
}
