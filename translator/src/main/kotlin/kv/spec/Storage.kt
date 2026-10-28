package kv.spec

import java.io.StringReader
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException

// Confluence storage format (DESIGN-C.md 10.8): the XHTML-based XML of a page body, as `confluence_fetch.py` writes
// it to <id>.xhtml. The value is a fragment with undeclared `ac:`, `ri:` and `at:` prefixes and HTML named entities.

/** An element of a storage value: [name] as written (with its prefix), its attributes, children, and lines. */
private class Node(
    val name: String,
    val attrs: Map<String, String>,
    val first: Int,
) {
    val children = mutableListOf<Any>() // Node or String
    var last = first

    fun nodes() = children.filterIsInstance<Node>()

    fun child(name: String) = nodes().firstOrNull { it.name == name }

    /** The `ac:parameter` of a macro with `ac:name` [name]. */
    fun parameter(name: String) = nodes().firstOrNull { it.name == "ac:parameter" && it.attrs["ac:name"] == name }
}

/** A node named [name] at line [first] that holds [children]: a part of an element read as an element of its own. */
private fun shell(
    name: String,
    first: Int,
    children: List<Any>,
) = Node(name, emptyMap(), first).also { it.children.addAll(children) }

/** HTML 4 named entities (resource html4-entities.txt): name to code point. */
private val ENTITIES: Map<String, Int> by lazy {
    val text = Node::class.java.getResource("/kv/spec/html4-entities.txt")!!.readText()
    text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line -> line.split(' ').let { it[0] to it[1].toInt() } }
}

private val XML_ENTITIES = setOf("lt", "gt", "amp", "quot", "apos")

private val CDATA = Regex("""<!\[CDATA\[.*?]]>""", RegexOption.DOT_MATCHES_ALL)

private val ENTITY = Regex("""&([A-Za-z][A-Za-z0-9]*);""")

/**
 * [text] with each HTML named entity as a numeric reference, so that an XML parser reads it. A CDATA section keeps
 * its text: a code macro body is verbatim. Throws [SnapshotException] for a name the table does not have.
 */
private fun numericEntities(
    path: String,
    text: String,
): String {
    fun replace(part: String) =
        ENTITY.replace(part) { m ->
            val name = m.groupValues[1]
            when {
                name in XML_ENTITIES -> m.value
                else -> ENTITIES[name]?.let { "&#$it;" } ?: throw SnapshotException("$path: unknown entity &$name; (not an HTML 4 entity)")
            }
        }
    val out = StringBuilder()
    var at = 0
    for (c in CDATA.findAll(text)) {
        out.append(replace(text.substring(at, c.range.first))).append(c.value)
        at = c.range.last + 1
    }
    return out.append(replace(text.substring(at))).toString()
}

/**
 * Parses a storage value. The parser ignores namespaces, so `ac:structured-macro` is an element name with a colon,
 * and reads no DTD. A synthetic root on the first line, before any line break, keeps the file's line numbers.
 */
private fun parseStorage(
    path: String,
    text: String,
): Node {
    val factory =
        XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false)
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_COALESCING, true)
        }
    val root = Node("kv-root", emptyMap(), 1)
    val stack = ArrayDeque(listOf(root))
    try {
        val r = factory.createXMLStreamReader(StringReader("<kv-root>${numericEntities(path, text)}</kv-root>"))
        while (r.hasNext()) {
            when (r.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    val attrs = (0 until r.attributeCount).associate { r.getAttributeLocalName(it).let { n -> (r.getAttributePrefix(it)?.takeIf(String::isNotEmpty)?.let { p -> "$p:$n" } ?: n) } to r.getAttributeValue(it) }
                    val name = r.prefix?.takeIf(String::isNotEmpty)?.let { "$it:${r.localName}" } ?: r.localName
                    val node = Node(name, attrs, r.location.lineNumber)
                    stack.last().children += node
                    stack.addLast(node)
                }
                XMLStreamConstants.END_ELEMENT -> stack.removeLast().last = r.location.lineNumber
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> stack.last().children += r.text
            }
        }
    } catch (e: XMLStreamException) {
        throw SnapshotException("$path: not well-formed storage format at line ${e.location?.lineNumber ?: "?"}: ${e.message?.substringAfter("Message: ")}")
    }
    return root
}

/** Lines of text: NBSP is a space, each run of white space one space, the ends trimmed, empty lines dropped. */
private fun tidy(text: String): List<String> = text.split('\n').map { it.replace(' ', ' ').replace(Regex("""\s+"""), " ").trim() }.filter(String::isNotEmpty)

private val PANELS = setOf("info", "tip", "note", "warning")

private val LISTS = setOf("ul", "ol")

/** Elements whose content is not page text. */
private val SKIPPED = setOf("ac:parameter", "ac:image", "ac:emoticon", "ac:placeholder", "ac:task-status", "at:var", "hr", "ac:adf-attribute")

/** Inline text of [n]: `br` is a line break; a link without a body shows its page title or file name. */
private fun inline(n: Node): String =
    when (n.name) {
        "br" -> "\n"
        in SKIPPED -> ""
        "ac:link" -> {
            val body = n.child("ac:link-body") ?: n.child("ac:plain-text-link-body")
            if (body != null) {
                inline(body)
            } else {
                n.nodes().firstNotNullOfOrNull { it.attrs["ri:content-title"] ?: it.attrs["ri:filename"] } ?: ""
            }
        }
        else -> n.children.joinToString("") { if (it is Node) inline(it) else (it as String).replace('\n', ' ') }
    }

/**
 * The lines of the block content [n] inside a block (a list item, a panel, an expand): paragraphs, nested lists
 * ([depth] indents them), table rows, macros. Text between block elements counts as a paragraph.
 */
private fun lines(
    n: Node,
    depth: Int = 0,
): List<String> =
    when (n.name) {
        in LISTS -> listLines(n, depth)
        "table" -> rows(n).map { rowText(it) }
        "ac:structured-macro" -> macroLines(n)
        "ac:task-list" -> n.nodes().filter { it.name == "ac:task" }.map(::taskText)
        "pre" -> n.children.joinToString("") { if (it is Node) inline(it) else it as String }.split('\n').map { it.replace(' ', ' ').trimEnd() }.filter(String::isNotEmpty)
        in SKIPPED -> emptyList()
        else -> {
            if (n.nodes().any { it.name in BLOCKS }) {
                // Mixed content: the text runs between block elements are paragraphs of their own.
                val out = mutableListOf<String>()
                val run = StringBuilder()
                fun flush() {
                    out += tidy(run.toString())
                    run.clear()
                }
                for (c in n.children) {
                    if (c is Node && c.name in BLOCKS) {
                        flush()
                        out += lines(c, depth)
                    } else {
                        run.append(if (c is Node) inline(c) else (c as String).replace('\n', ' '))
                    }
                }
                flush()
                out
            } else {
                tidy(inline(n))
            }
        }
    }

/** Elements that are blocks of their own inside other content. */
private val BLOCKS = setOf("p", "pre", "blockquote", "ul", "ol", "table", "ac:structured-macro", "ac:task-list", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ac:layout", "ac:layout-section", "ac:layout-cell", "ac:adf-extension", "ac:rich-text-body")

/** The items of list [n]: `- text` or `N. text`, each nested list two spaces further in. */
private fun listLines(
    n: Node,
    depth: Int,
): List<String> =
    n.nodes().filter { it.name == "li" }.flatMapIndexed { i, li -> itemLines(li, n.name == "ol", i, depth) }

private fun itemLines(
    li: Node,
    ordered: Boolean,
    index: Int,
    depth: Int,
): List<String> {
    val indent = "  ".repeat(depth)
    val marker = if (ordered) "${index + 1}. " else "- "
    val own = shell(li.name, li.first, li.children.filterNot { it is Node && it.name in LISTS })
    val text = lines(own, depth + 1)
    val head = listOf(indent + marker + (text.firstOrNull() ?: "")) + text.drop(1).map { "$indent  $it" }
    return head + li.nodes().filter { it.name in LISTS }.flatMap { listLines(it, depth + 1) }
}

/** The rows of table [n], in `thead`, `tbody`, `tfoot` or directly under it. */
private fun rows(n: Node): List<Node> = n.nodes().flatMap { if (it.name == "tr") listOf(it) else it.nodes().filter { r -> r.name == "tr" } }

private fun rowText(tr: Node): String = tr.nodes().filter { it.name == "th" || it.name == "td" }.joinToString(" | ") { lines(it).joinToString(" ") }

/** `[ ] text` or `[x] text`; the status of a done task (`complete`) is not confirmed by the docs (DESIGN-C.md 10.8). */
private fun taskText(task: Node): String {
    val done = task.child("ac:task-status")?.let { inline(shell("s", 0, it.children)).trim() } == "complete"
    val body = task.child("ac:task-body")?.let { lines(it).joinToString(" ") } ?: ""
    return (if (done) "[x] " else "[ ] ") + body
}

/** The text of a `code` body: the CDATA verbatim. */
private fun plainBody(macro: Node): String = macro.child("ac:plain-text-body")?.children?.joinToString("") { it as? String ?: "" } ?: ""

/** A macro's lines: `code` gives its body verbatim; a panel or `expand` a `NAME: title` line, then its body. */
private fun macroLines(m: Node): List<String> {
    val name = m.attrs["ac:name"] ?: ""
    return when (name) {
        "code" -> plainBody(m).split('\n').let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }
        in PANELS, "expand" -> {
            val title = m.parameter("title")?.let { inline(shell("t", 0, it.children)).trim() } ?: ""
            listOf(if (title.isEmpty()) "$name:" else "$name: $title") + (m.child("ac:rich-text-body")?.let { lines(it) } ?: emptyList())
        }
        else -> lines(shell("m", m.first, m.children))
    }
}

/**
 * Splits a storage value into blocks (DESIGN-C.md 10.8 table): a heading starts a section; a paragraph, a top-level
 * list item, a table row, a macro and a task are blocks; layouts and `div` are transparent. [slug] names a section
 * from its heading text and its 1-based heading number; [warn] gets each macro the table does not know.
 */
internal fun storageCuts(
    path: String,
    text: String,
    slug: (String, Int) -> String,
    warn: (String) -> Unit,
): List<StorageBlock> {
    val out = mutableListOf<StorageBlock>()
    var section = "top"
    var heading = "(top)"
    var headings = 0
    var counter = 0
    fun emit(
        n: Node,
        lines: List<String>,
    ) {
        if (lines.isEmpty()) return
        counter++
        out += StorageBlock(section, heading, counter, lines.joinToString("\n"), n.first, n.last)
    }

    fun walk(n: Node) {
        when {
            n.name.length == 2 && n.name[0] == 'h' && n.name[1] in '1'..'6' -> {
                headings++
                heading = tidy(inline(n)).joinToString(" ")
                section = slug(heading, headings)
                counter = 0
            }
            n.name in LISTS -> n.nodes().filter { it.name == "li" }.forEachIndexed { i, li -> emit(li, itemLines(li, n.name == "ol", i, 0)) }
            n.name == "table" -> rows(n).forEach { emit(it, listOf(rowText(it))) }
            n.name == "ac:task-list" -> n.nodes().filter { it.name == "ac:task" }.forEach { emit(it, listOf(taskText(it))) }
            n.name == "ac:structured-macro" -> {
                val name = n.attrs["ac:name"] ?: ""
                if (name != "code" && name !in PANELS && name != "expand") warn("$path: unknown macro '$name' at line ${n.first}: its text, if any, is one block")
                emit(n, macroLines(n))
            }
            n.name == "ac:adf-extension" -> {
                val content = n.nodes().firstOrNull { it.name == "ac:adf-node" }?.child("ac:adf-content")
                (content ?: n.child("ac:adf-fallback"))?.let(::walk)
            }
            n.name in TRANSPARENT -> {
                // Text between the blocks of a transparent element is a paragraph of its own.
                val run = Node("p", emptyMap(), n.first)
                fun flush() {
                    if (run.children.isNotEmpty()) emit(run, lines(run))
                    run.children.clear()
                }
                for (c in n.children) {
                    if (c is Node && c.name !in INLINE) {
                        flush()
                        walk(c)
                    } else {
                        run.children += c
                        if (c is Node) run.last = c.last
                    }
                }
                flush()
            }
            n.name in SKIPPED -> Unit
            else -> emit(n, lines(n))
        }
    }
    walk(parseStorage(path, text))
    return out
}

/** One block of a storage value: [section] slug and [heading] text, its number in the section, text and lines. */
internal data class StorageBlock(
    val section: String,
    val heading: String,
    val number: Int,
    val text: String,
    val first: Int,
    val last: Int,
)

/** Wrappers whose children are the blocks (DESIGN-C.md 10.8: layouts, `div`, the ADF content). */
private val TRANSPARENT = setOf("kv-root", "div", "section", "ac:layout", "ac:layout-section", "ac:layout-cell", "ac:adf-content", "ac:adf-fallback", "ac:adf-node")

/** Inline elements: at the top level they join the text around them into one paragraph. */
private val INLINE = setOf("span", "strong", "b", "em", "i", "u", "s", "code", "tt", "sub", "sup", "a", "br", "ac:link", "ac:emoticon", "ac:image", "at:var", "time", "ac:inline-comment-marker")
