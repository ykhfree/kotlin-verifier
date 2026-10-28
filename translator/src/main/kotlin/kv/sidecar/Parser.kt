package kv.sidecar

import kv.kvir.GhostKind
import java.io.File

/** Reads a sidecar file and the files it includes (one level, DESIGN-v0.md 4.2). */
object SidecarLoader {
    fun load(file: File): Sidecar {
        val root = parseSidecar(file.path, file.readText())
        val decls =
            root.decls.flatMap { d ->
                if (d !is IncludeDecl) return@flatMap listOf(d)
                val included = File(file.parentFile, d.path)
                if (!included.isFile) throw SidecarException(listOf(SidecarError(d.pos, "include", "no file ${included.path}")))
                val inner = parseSidecar(included.path, included.readText())
                inner.decls.filterIsInstance<IncludeDecl>().firstOrNull()?.let {
                    throw SidecarException(listOf(SidecarError(it.pos, "include", "an included file may not include another (one level only)")))
                }
                inner.decls
            }
        return Sidecar(file.path, decls)
    }
}

/** Parses one sidecar file. Throws [SidecarException] with the first syntax error. */
fun parseSidecar(
    file: String,
    text: String,
): Sidecar = SidecarParser(file, text).parse()

/**
 * Parses a cell file (DESIGN-C.md 3.2, `kv spec check --cells`). Throws [SidecarException] with the first syntax
 * error. Grammar, with the probe step of DESIGN-C.md 7.2:
 * ```
 * cell    := "cell" NAME "(" [ NAME ":" TYPE { "," NAME ":" TYPE } ] ")" [ STRING ] [ "boundary" ] "{" { step } { outcome } "}"
 * step    := [ NAME { "," NAME } ":=" ] NAME "(" ARGS ")" | "assume" VEXPR
 * outcome := "outcome" NAME ":" VEXPR
 * ```
 * `boundary` marks a cell whose clock reading equals a lease (DESIGN-C.md 3.3 rule 5: always a question).
 */
fun parseCells(
    file: String,
    text: String,
): List<CellDecl> = SidecarParser(file, text).cells()

/** The clause keywords of a `trusted` declaration (DESIGN-v0.md 4.2, DESIGN-B.md 5.1, DESIGN-E 6.2). */
private val TRUSTED_CLAUSES = setOf("returns", "ghost", "ensures", "effects", "reviewed", "throws", "creates", "consumes")

/** A source line in a place where none may be. */
private const val MISPLACED_SOURCE = "a source line must follow an issue rule, an ensures, an allowance clause, an invariant, a lemma, a probe, an assume or a set-once (DESIGN-C.md 5.2)"

/** The start of a line that ends an expression above it: `source "`, `source none` or `reviewed "`. */
private val TRAILER_LINE = Regex("""^(source[ \t]+("|none(?!\w))|reviewed[ \t]+")""")

private class SidecarParser(
    private val file: String,
    private val text: String,
) {
    private var i = 0
    private val lineStarts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }

    private fun posAt(index: Int): SPos {
        val line = lineStarts.indexOfLast { it <= index }
        return SPos(file, line + 1, index - lineStarts[line] + 1)
    }

    private fun fail(
        index: Int,
        message: String,
    ): Nothing = throw SidecarException(listOf(SidecarError(posAt(index), "syntax", message)))

    // ---- characters ----

    private fun at(s: String) = text.startsWith(s, i)

    private fun eof() = i >= text.length

    /** Skips spaces, newlines and `//` comments. */
    private fun skipWs() {
        while (!eof()) {
            when {
                text[i].isWhitespace() -> i++
                at("//") -> while (!eof() && text[i] != '\n') i++
                else -> return
            }
        }
    }

    private fun skipInlineWs() {
        while (!eof() && (text[i] == ' ' || text[i] == '\t')) i++
    }

    /** The index in [text] of the first character of [e]'s text after its last character: its raw end. */
    private fun endOf(e: Vexpr): Int = lineStarts[e.pos.line - 1] + e.pos.col - 1 + e.text.length

    /** `reviewed "<note>" [sha256 "<hex>"]` after the keyword: the note, the fingerprint (B6) and the keyword position. */
    private fun review(keyword: Int): Triple<String, String?, SPos> {
        val note = string()
        val sha = if (peekWord() == "sha256") name().let { string() } else null
        return Triple(note, sha, posAt(keyword))
    }

    /** Column of the first non-space character on the line that holds [index], 0-based. */
    private fun indentOf(index: Int): Int {
        val start = lineStarts[lineStarts.indexOfLast { it <= index }]
        var j = start
        while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
        return j - start
    }

    /** True if only spaces or a comment follow [index] on its line. */
    private fun restOfLineBlank(index: Int): Boolean {
        var j = index
        while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
        return j >= text.length || text[j] == '\n' || text.startsWith("//", j)
    }

    // ---- tokens ----

    private fun isNameStart(c: Char) = c.isLetter() || c == '_'

    private fun isNamePart(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun peekWord(): String? {
        skipWs()
        if (eof() || !isNameStart(text[i])) return null
        var j = i
        while (j < text.length && isNamePart(text[j])) j++
        return text.substring(i, j)
    }

    private fun name(what: String = "a name"): Name {
        skipWs()
        if (eof() || !isNameStart(text[i])) fail(i, "expected $what")
        val start = i
        while (!eof() && isNamePart(text[i])) i++
        return Name(text.substring(start, i), posAt(start))
    }

    /** NAME { "." NAME }, written without spaces. */
    private fun dotted(what: String): Name {
        val first = name(what)
        val sb = StringBuilder(first.text)
        while (at(".") && i + 1 < text.length && isNameStart(text[i + 1])) {
            i++
            sb.append('.').append(name().text)
        }
        return Name(sb.toString(), first.pos)
    }

    private fun keyword(word: String) {
        val start = i
        val got = name("'$word'")
        if (got.text != word) fail(start, "expected '$word', found '${got.text}'")
    }

    private fun expect(s: String) {
        skipWs()
        if (!at(s)) fail(i, "expected '$s'")
        i += s.length
    }

    private fun accept(s: String): Boolean {
        skipWs()
        if (!at(s)) return false
        i += s.length
        return true
    }

    private fun string(): String {
        skipWs()
        if (!at("\"")) fail(i, "expected a string")
        val start = ++i
        while (!eof() && text[i] != '"' && text[i] != '\n') i++
        if (eof() || text[i] != '"') fail(start - 1, "unterminated string")
        return text.substring(start, i++)
    }

    /** The text between triple quotes, with the position of its first character. */
    private fun tripleString(): Pair<String, SPos> {
        skipWs()
        if (!at("\"\"\"")) fail(i, "expected a \"\"\" string")
        val start = i + 3
        val end = text.indexOf("\"\"\"", start)
        if (end < 0) fail(i, "unterminated \"\"\" string")
        i = end + 3
        return text.substring(start, end) to posAt(start)
    }

    /**
     * Viper expression text from here. It goes on over the following lines that are indented deeper than
     * [itemIndent], except a source line or a `reviewed` note (DESIGN-C.md 5.2). It stops before an unmatched closing
     * bracket, before `;` if [stopAtSemicolon], and before a `{` that ends its line if [stopAtBodyBrace]. Comments
     * become spaces, so columns stay the same.
     */
    private fun vexpr(
        itemIndent: Int,
        stopAtSemicolon: Boolean = false,
        stopAtBodyBrace: Boolean = false,
    ): Vexpr {
        skipInlineWs()
        val start = i
        val sb = StringBuilder()
        var depth = 0
        while (!eof()) {
            val c = text[i]
            if (at("//")) {
                while (!eof() && text[i] != '\n') {
                    sb.append(' ')
                    i++
                }
                continue
            }
            if (c == '\n') {
                val next = i + 1
                if (next >= text.length || restOfLineBlank(next) && !commentLine(next) || indentOf(next) <= itemIndent || startsTrailer(next)) break
                sb.append(c)
                i++
                continue
            }
            when (c) {
                '(', '[', '{' -> {
                    if (c == '{' && stopAtBodyBrace && depth == 0 && restOfLineBlank(i + 1)) break
                    depth++
                }
                ')', ']', '}' -> {
                    if (depth == 0) break
                    depth--
                }
                ';' -> if (stopAtSemicolon && depth == 0) break
            }
            sb.append(c)
            i++
        }
        val first = sb.indexOfFirst { !it.isWhitespace() }
        if (first < 0) fail(start, "expected an expression")
        return Vexpr(sb.substring(first).trimEnd(), posAt(start + first))
    }

    /**
     * True if the line at [index] starts with `source "`, `source none` or `reviewed "`: a source line or a note, which
     * no Viper expression can start with, so it ends the expression above it at any indentation.
     */
    private fun startsTrailer(index: Int): Boolean {
        var j = index
        while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
        val end = text.indexOf('\n', j).let { if (it < 0) text.length else it }
        return TRAILER_LINE.containsMatchIn(text.substring(j, end))
    }

    /** True if the line at [index] holds only a comment (a comment line inside a multi-line expression). */
    private fun commentLine(index: Int): Boolean {
        var j = index
        while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
        return text.startsWith("//", j)
    }

    // ---- declarations ----

    fun parse(): Sidecar {
        val decls = mutableListOf<Decl>()
        while (true) {
            skipWs()
            if (eof()) break
            decls += decl()
        }
        return Sidecar(file, decls)
    }

    private fun decl(): Decl {
        val start = i
        val pos = posAt(start)
        val indent = indentOf(start)
        return when (val kw = name("a declaration").text) {
            "module" -> {
                val fqn = dotted("a module FQN")
                // DESIGN-E 6.2: `module FQN client` on one line.
                skipInlineWs()
                val client = at("client") && (i + "client".length >= text.length || !isNamePart(text[i + "client".length]))
                if (client) name()
                ModuleDecl(fqn, pos, client)
            }
            "source" -> {
                // The module's `source "File.kt"`; a source line here follows no intent clause.
                if (peekWord() == "none") fail(start, MISPLACED_SOURCE)
                val path = string()
                if (peekWord() == "quote") fail(start, MISPLACED_SOURCE)
                SourceDecl(path, pos)
            }
            "include" -> IncludeDecl(string(), pos)
            "type" -> {
                val n = name("a type name")
                expect("=")
                when (val k = name("'opaque' or 'template'").text) {
                    // DESIGN-B.md 5.1: a Kotlin class on the same line binds the opaque type to it.
                    "opaque" -> {
                        skipInlineWs()
                        val kclass = if (!eof() && isNameStart(text[i])) dotted("a Kotlin class name") else null
                        TypeDecl(n, null, pos, kclass, unit = text.substring(start, i))
                    }
                    "template" -> {
                        val template = string()
                        TypeDecl(n, template, pos, unit = text.substring(start, i))
                    }
                    else -> fail(i - k.length, "expected 'opaque' or 'template'")
                }
            }
            "set" -> {
                if (!at("-once")) fail(start, "unknown declaration 'set'")
                i += "-once".length
                val id = name("a property ID")
                expect(":")
                val map = name("a state name")
                val unit = text.substring(start, i)
                val sources = topSources()
                val (note, sha, at) = topReview()
                SetOnceDecl(id, map, pos, note, sha, at, unit, sources)
            }
            "state" -> {
                val n = name("a state name")
                expect(":")
                val kindStart = i
                val kind =
                    when (name("'Map' or 'Counter'").text) {
                        "Map" -> {
                            expect("<")
                            val key = name("a key type")
                            expect(",")
                            val value = name("a class name")
                            expect(">")
                            StateKind.MapOf(key, value)
                        }
                        "Counter" -> StateKind.Counter
                        else -> fail(kindStart, "expected 'Map<K, C>' or 'Counter'")
                    }
                expect("=")
                StateDecl(n, kind, dotted("a Kotlin property name"), pos)
            }
            "class" -> {
                val n = name("a class name")
                expect("=")
                val kname = dotted("a Kotlin class name")
                expect("{")
                val fields = mutableListOf<FieldDecl>()
                while (!accept("}")) {
                    val f = name("a field name")
                    expect(":")
                    val type = name("a field type")
                    val nullable = accept("?")
                    expect("=")
                    fields += FieldDecl(f, type, nullable, name("a Kotlin property name"))
                }
                ClassDecl(n, kname, fields, pos, text.substring(start, i))
            }
            "use" -> UseDecl(dotted("a trusted model name"), pos)
            "enum" -> {
                val n = name("an enum name")
                expect("=")
                val kclass = dotted("a Kotlin enum class name")
                EnumDecl(n, kclass, pos, text.substring(start, i))
            }
            "obligation" -> {
                val n = name("an obligation name")
                expect("(")
                val types = mutableListOf<Name>()
                do {
                    types += name("a type")
                } while (accept(","))
                expect(")")
                ObligationDecl(n, types, pos, text.substring(start, i))
            }
            "trace" -> trace(start, pos)
            "assume" -> {
                val id = name("an assumption ID")
                expect(":")
                val n = hyphenated("an assumption name")
                val unit = text.substring(start, i)
                val sources = topSources()
                val (note, sha, at) = topReview()
                AssumeDecl(id, n, pos, note, sha, at, unit, sources)
            }
            "trusted" -> trusted(start, pos)
            "ghost" -> {
                keyword("record")
                val n = name("a ghost record name")
                expect("(")
                val index = name("an index type")
                expect(")")
                expect("{")
                val fields = mutableListOf<GhostFieldDecl>()
                while (!accept("}")) {
                    val f = name("a ghost field name")
                    expect(":")
                    val type = name("a type")
                    fields += GhostFieldDecl(f, type, ghostKind())
                }
                GhostRecordDecl(n, index, fields, pos, text.substring(start, i))
            }
            "witness" -> {
                val target = dotted("CLASS.FIELD")
                val parts = target.text.split('.')
                if (parts.size != 2) fail(start, "witness needs CLASS.FIELD")
                WitnessDecl(Name(parts[0], target.pos), Name(parts[1], target.pos.copy(col = target.pos.col + parts[0].length + 1)), pos, text.substring(start, i))
            }
            "operation" -> operation(pos)
            "invariant", "lemma" -> {
                val id = name("a property ID")
                expect(":")
                val e = vexpr(indent)
                val unit = text.substring(start, endOf(e))
                val sources = topSources()
                val (note, sha, at) = topReview()
                PropertyDecl(if (kw == "invariant") PropertyKind.INVARIANT else PropertyKind.LEMMA, id, e, pos, note, sha, at, unit, sources)
            }
            "hole" -> {
                val n = name("MI_INV")
                // DESIGN-E 6.2, 12.5: `hole loop KNAME [#N]` is the invariant of one loop of a client module.
                val loop =
                    if (n.text == "loop") {
                        val fn = dotted("a Kotlin function name")
                        if (at("@")) {
                            fail(i, "hole loop names a loop by its function: write 'hole loop KNAME' (its only loop) or 'hole loop KNAME#N' (its N-th loop); a line number moves with every edit above the loop")
                        }
                        val ordinal =
                            if (at("#")) {
                                i++
                                val digits = text.substring(i).takeWhile { it.isDigit() }
                                val number = digits.toIntOrNull()?.takeIf { it >= 1 } ?: fail(i, "expected a loop number from 1 after '#'")
                                i += digits.length
                                number
                            } else {
                                null
                            }
                        LoopRef(fn, ordinal)
                    } else {
                        null
                    }
                if (n.text != "MI_INV" && loop == null) fail(i - n.text.length, "the only hole in v0 is MI_INV")
                val (body, bodyPos) = tripleString()
                HoleDecl(n, body, pos, bodyPos, loop)
            }
            "probe" -> probe(start, indent)
            // DESIGN-C.md 10.10 (C7): the blocks this sidecar answers for, and a block it leaves out on purpose.
            "scope" -> {
                val target = string()
                val unit = text.substring(start, i)
                val (note, sha, at) = topReview()
                ScopeDecl(target, pos, note, sha, at, unit)
            }
            "exclude" -> {
                val anchor = string()
                keyword("because")
                val reason = string()
                val unit = text.substring(start, i)
                val block = if (peekWord() == "sha256") name().let { string() } else null
                val (note, sha, at) = topReview()
                ExcludeDecl(anchor, reason, block, pos, note, sha, at, unit)
            }
            "review" -> {
                val what = name("'intent' or 'vocabulary'")
                when (what.text) {
                    "intent" -> ReviewIntentDecl(pos)
                    // DESIGN-E 12.3 (V-C): one note signs every vocabulary declaration.
                    "vocabulary" -> {
                        val (note, sha, at) = topReview()
                        ReviewVocabularyDecl(pos, note, sha, at)
                    }
                    else -> fail(i - what.text.length, "expected 'intent' or 'vocabulary'")
                }
            }
            "reviewed" -> fail(start, "'reviewed' must follow a trusted fun, an invariant, a lemma, a probe, an assume, a set-once, a scope, an exclude or 'review vocabulary' without a note, or a clause of an operation")
            else -> fail(start, "unknown declaration '$kw'")
        }
    }

    private fun ghostKind(): GhostKind {
        val start = i
        return when (name("set-once, frozen or ends").text) {
            "set" -> {
                if (!at("-once")) fail(start, "expected set-once, frozen or ends")
                i += "-once".length
                GhostKind.SET_ONCE
            }
            "frozen" -> GhostKind.FROZEN
            "ends" -> GhostKind.ENDS
            else -> fail(start, "expected set-once, frozen or ends")
        }
    }

    private fun trusted(
        start: Int,
        pos: SPos,
    ): TrustedDecl {
        // DESIGN-E 6.2: `trusted value KNAME` is a function-typed property; its parameter list is optional.
        val kw = name("'fun' or 'value'")
        if (kw.text != "fun" && kw.text != "value") fail(i - kw.text.length, "expected 'fun', found '${kw.text}'")
        val value = kw.text == "value"
        val fn = dotted(if (value) "a Kotlin property name" else "a Kotlin function name")
        val params = mutableListOf<Name>()
        val named = mutableListOf<Typed?>()
        if (!value || accept("(")) {
            if (!value) expect("(")
            if (!accept(")")) {
                do {
                    // `NAME: TYPE` is a parameter the model passes (DESIGN-E 6.2); a bare Kotlin type is one it drops.
                    val t = dotted("a parameter type")
                    if (!t.text.contains('.') && accept(":")) {
                        val type = nullable(name("a sidecar type"))
                        params += type
                        named += Typed(t, type)
                    } else {
                        params += if (accept("?")) t.copy(text = t.text + "?") else t
                        named += null
                    }
                } while (accept(","))
                expect(")")
            }
        }
        val clock = !value && accept("=").also { if (it) keyword("clock") }
        val returns = mutableListOf<Typed>()
        val ghosts = mutableListOf<Typed>()
        val ensures = mutableListOf<Vexpr>()
        var effectsNone = false
        var reviewed: String? = null
        var fresh: Name? = null
        var throws = false
        val throwKinds = mutableListOf<Name>()
        var creates: ObligationUse? = null
        var consumes: ObligationUse? = null
        var sha: String? = null
        var reviewPos: SPos? = null
        var reviewSpan = IntRange.EMPTY
        while (true) {
            val word = peekWord() ?: break
            if (word !in TRUSTED_CLAUSES) break
            val clauseStart = i
            val indent = indentOf(clauseStart)
            name()
            // `ghost record` starts the next declaration; `ghost NAME: TYPE` is a clause.
            if (word == "ghost" && peekWord() == "record") {
                i = clauseStart
                break
            }
            if (clock && word != "reviewed") fail(clauseStart, "a clock function takes only 'reviewed'")
            when (word) {
                "returns", "ghost" -> {
                    val n = name("a result name")
                    // DESIGN-B.md 5.1: `returns fresh NAME`; `returns fresh: TYPE` is a result named fresh.
                    if (word == "returns" && n.text == "fresh" && !at(":") && peekWord() != null) {
                        if (fresh != null) fail(clauseStart, "a second 'returns fresh'")
                        fresh = name("a class name")
                        continue
                    }
                    expect(":")
                    (if (word == "returns") returns else ghosts) += Typed(n, nullable(name("a type")))
                }
                "throws" -> {
                    throws = true
                    // DESIGN-E 6.2: kinds on the same line; B's bare `throws` ends its line.
                    skipInlineWs()
                    if (!eof() && isNameStart(text[i]) && peekWord() !in TRUSTED_CLAUSES) {
                        do {
                            throwKinds += name("an exception kind")
                        } while (accept(","))
                    }
                }
                "creates", "consumes" -> {
                    val at = posAt(clauseStart)
                    val obligation = name("an obligation name")
                    expect("(")
                    val args = mutableListOf<Name>()
                    do {
                        args += name("a parameter or result name")
                    } while (accept(","))
                    expect(")")
                    // DESIGN-E 6.2 `if VEXPR`, in the one shape the tool reads: `if RESULT != null`.
                    var ifNotNull: Name? = null
                    if (word == "creates" && peekWord() == "if") {
                        name()
                        ifNotNull = name("a result name")
                        expect("!=")
                        val n = name("null")
                        if (n.text != "null" && n.text != "NULL") fail(i - n.text.length, "a creation condition is `RESULT != null`")
                    }
                    val use = ObligationUse(obligation, args, at, ifNotNull)
                    if (word == "creates") {
                        if (creates != null) fail(clauseStart, "a second 'creates'")
                        creates = use
                    } else {
                        if (consumes != null) fail(clauseStart, "a second 'consumes'")
                        consumes = use
                    }
                }
                "ensures" -> ensures += vexpr(indent)
                "effects" -> {
                    keyword("none")
                    effectsNone = true
                }
                "reviewed" -> review(clauseStart).let { (note, hex, at) ->
                    reviewed = note
                    sha = hex
                    reviewPos = at
                    reviewSpan = clauseStart until i
                }
            }
        }
        return TrustedDecl(
            fn, params, clock, returns, ghosts, ensures, effectsNone, reviewed, pos, fresh, throws, sha, reviewPos,
            named.takeIf { it.any { p -> p != null } } ?: emptyList(), value, creates, consumes, throwKinds,
            contract = text.substring(start, i).let { if (reviewSpan.isEmpty()) it else it.removeRange(reviewSpan.first - start, reviewSpan.last + 1 - start) },
        )
    }

    /** [n] with a `?` that follows it right away (DESIGN-E 6.2: `Token?`, `Data?`). */
    private fun nullable(n: Name): Name = if (at("?")) n.copy(text = n.text + "?").also { i++ } else n

    /** NAME { "-" NAME }, written without spaces: an assumption name such as `errors-are-fatal`. */
    private fun hyphenated(what: String): Name {
        val first = name(what)
        val sb = StringBuilder(first.text)
        while (at("-") && i + 1 < text.length && isNameStart(text[i + 1])) {
            i++
            sb.append('-').append(name().text)
        }
        return Name(sb.toString(), first.pos)
    }

    /** `trace { NAME = EVENT ... }` after the keyword (DESIGN-E 6.2), and its `derived NAME = VEXPR` lines (E3). */
    private fun trace(
        start: Int,
        pos: SPos,
    ): TraceDecl {
        expect("{")
        val events = mutableListOf<TraceEvent>()
        val derived = mutableListOf<DerivedDecl>()
        while (!accept("}")) {
            skipWs()
            val indent = indentOf(i)
            val n = name("a trace field name")
            if (n.text == "derived" && peekWord() != null) {
                val d = name("a derived value name")
                expect("=")
                derived += DerivedDecl(d, vexpr(indent))
                continue
            }
            expect("=")
            fun where(): Vexpr? = if (peekWord() == "where") name().let { vexpr(indent) } else null
            events +=
                when (peekWord()) {
                    "first", "count", "last", "hit" -> {
                        val word = name().text
                        val form = EventForm.entries.first { it.keyword == word }
                        val call = dotted("a trusted call")
                        TraceEvent(n, form, call, where())
                    }
                    "trailing" -> {
                        name()
                        keyword("failures")
                        keyword("of")
                        TraceEvent(n, EventForm.TRAILING_FAILURES, null, of = name("a count field"))
                    }
                    else -> {
                        val call = dotted("a trusted call")
                        keyword("while")
                        TraceEvent(n, EventForm.WHILE_HOLDS, call, holds = holdsPattern())
                    }
                }
        }
        return TraceDecl(events, pos, derived, text.substring(start, i))
    }

    /** `holds(NAME(ARG {, ARG}))`; an argument is a name, a constant or `_`. */
    private fun holdsPattern(): HoldsPattern {
        keyword("holds")
        expect("(")
        val obligation = name("an obligation name")
        expect("(")
        val args = mutableListOf<Name>()
        do {
            args += name("a value or '_'")
        } while (accept(","))
        expect(")")
        expect(")")
        return HoldsPattern(obligation, args)
    }

    private fun operation(pos: SPos): OperationDecl {
        val n = name("an operation name")
        expect("=")
        val selStart = i
        val selector =
            when (name("'fun' or 'compute'").text) {
                "fun" -> Selector.Fun(dotted("a Kotlin function name"))
                "compute" -> {
                    keyword("in")
                    Selector.ComputeIn(dotted("a Kotlin class name"))
                }
                else -> fail(selStart, "expected 'fun KNAME' or 'compute in KNAME'")
            }
        expect("{")
        val items = mutableListOf<OpItem>()
        while (!accept("}")) {
            skipWs()
            val start = i
            val itemPos = posAt(start)
            val indent = indentOf(start)
            when (val kw = name("an operation item").text) {
                "free" -> {
                    val names = mutableListOf(name("a free value name"))
                    val type = if (accept(":")) name("an abstract type name") else null
                    while (accept(",")) names += name("a free value name")
                    expect("=")
                    val srcStart = i
                    val source =
                        when (name("'local', 'param', 'this.' or 'call'").text) {
                            "local" -> FreeSource.Local(dotted("a Kotlin local name"))
                            "param" -> FreeSource.Param(dotted("a Kotlin parameter name"))
                            "call" -> FreeSource.Call(name("a trusted function name"))
                            "this" -> {
                                if (!at(".")) fail(i, "expected 'this.PROPERTY'")
                                i++
                                FreeSource.This(dotted("a property name"))
                            }
                            else -> fail(srcStart, "expected 'local', 'param', 'this.' or 'call'")
                        }
                    items += FreeItem(names, source, itemPos, type)
                }
                "result" -> items += ResultItem(name("a result name"), itemPos)
                "issue" -> {
                    val result = name("the result name")
                    expect("{")
                    val fields = mutableListOf<Pair<Name, Vexpr>>()
                    do {
                        val f = name("a ghost field name")
                        expect("=")
                        fields += f to vexpr(indent, stopAtSemicolon = true)
                    } while (accept(";"))
                    expect("}")
                    items += IssueItem(result, fields, null, itemPos, unit = text.substring(start, i), sources = sources())
                }
                "ends_only_if", "unbacked_only_if" -> {
                    val id = name("a property ID")
                    expect(":")
                    val kind = if (kw == "ends_only_if") Allowance.ENDS_ONLY_IF else Allowance.UNBACKED_ONLY_IF
                    val e = vexpr(indent)
                    items += AllowanceItem(kind, id, e, null, itemPos, unit = text.substring(start, endOf(e)), sources = sources())
                }
                "ensures" -> {
                    val id = name("a property ID")
                    expect(":")
                    val e = vexpr(indent)
                    items += EnsuresItem(id, e, itemPos, unit = text.substring(start, endOf(e)), sources = sources())
                }
                // DESIGN-E 6.2: `guard ID: CALL requires VEXPR`.
                "guard" -> {
                    val id = name("a property ID")
                    expect(":")
                    val call = dotted("a trusted call")
                    keyword("requires")
                    val e = vexpr(indent)
                    items += GuardItem(id, call, e, itemPos, unit = text.substring(start, endOf(e)), sources = sources())
                }
                // U7: `reviewed` marks the clause right before it (issue rule, ensures, allowance clause) as read by a person.
                "reviewed" -> {
                    val (note, sha, at) = review(start)
                    val reviewed =
                        when (val last = items.lastOrNull()) {
                            is IssueItem -> if (last.reviewed == null) last.copy(reviewed = note, sha256 = sha, reviewPos = at) else fail(start, "the issue rule is already reviewed")
                            is AllowanceItem -> if (last.reviewed == null) last.copy(reviewed = note, sha256 = sha, reviewPos = at) else fail(start, "${last.id.text} is already reviewed")
                            is EnsuresItem -> if (last.reviewed == null) last.copy(reviewed = note, sha256 = sha, reviewPos = at) else fail(start, "${last.id.text} is already reviewed")
                            is GuardItem -> if (last.reviewed == null) last.copy(reviewed = note, sha256 = sha, reviewPos = at) else fail(start, "${last.id.text} is already reviewed")
                            else -> fail(start, "'reviewed' must follow an issue rule, an ensures, a guard or an allowance clause")
                        }
                    items[items.lastIndex] = reviewed
                }
                // A source line right after its clause is read with the clause ([sources]).
                "source" -> {
                    val last = items.lastOrNull()
                    fail(start, if (last is ReviewedItem && last.reviewed != null) "a source line comes before the 'reviewed' note of its clause" else MISPLACED_SOURCE)
                }
                "requires" -> fail(start, "an operation may not have a free-form 'requires' in v0 (DESIGN-v0.md 4.2, restriction 1)")
                else -> fail(start, "unknown operation item '$kw'")
            }
        }
        return OperationDecl(n, selector, items, pos)
    }

    private fun probe(
        start: Int,
        indent: Int,
    ): ProbeDecl {
        // DESIGN-C.md 7.2: `probe NAME(...)`; `probe(...)` is the unnamed probe of v0.
        val name = if (peekWord() != null) name("a probe name") else null
        expect("(")
        val params = params()
        val requires = if (peekWord() == "requires") name().let { vexpr(indent, stopAtBodyBrace = true) } else null
        expect("{")
        val steps = mutableListOf<ProbeStep>()
        while (!accept("}")) {
            skipWs()
            val start = i
            val stepIndent = indentOf(start)
            val first = name("a probe step")
            steps += if (first.text == "assert") ProbeAssert(vexpr(stepIndent), first.pos) else call(first)
        }
        val unit = text.substring(start, i)
        val sources = topSources()
        val (note, sha, at) = topReview()
        return ProbeDecl(params, requires, steps, posAt(start), note, sha, at, unit, sources, name)
    }

    /** `PARAMS )` after the opening parenthesis: `NAME: TYPE` separated by commas. */
    private fun params(): List<Typed> {
        val params = mutableListOf<Typed>()
        if (accept(")")) return params
        do {
            val n = name("a parameter name")
            expect(":")
            params += Typed(n, name("a type"))
        } while (accept(","))
        expect(")")
        return params
    }

    /** A probe call from its first word [first]: `[OUT {, OUT} :=] OP(ARGS)`. */
    private fun call(first: Name): ProbeCall {
        // DESIGN-C.md 7.2: a call without outs, of an operation without a result.
        if (accept("(")) return ProbeCall(emptyList(), first, args(), first.pos)
        val outs = mutableListOf(first)
        while (accept(",")) outs += name("a result name")
        expect(":=")
        val op = name("an operation name")
        expect("(")
        return ProbeCall(outs, op, args(), first.pos)
    }

    // ---- cell files (DESIGN-C.md 3.2) ----

    /** A cell file: `cell ID(PARAMS) ["title"] [boundary] { steps outcomes }`, any number of them. */
    fun cells(): List<CellDecl> {
        val cells = mutableListOf<CellDecl>()
        while (true) {
            skipWs()
            if (eof()) return cells
            val start = i
            keyword("cell")
            val id = name("a cell name")
            expect("(")
            val params = params()
            skipWs()
            val title = if (at("\"")) string() else null
            val boundary = peekWord() == "boundary"
            if (boundary) name()
            expect("{")
            val steps = mutableListOf<CellStep>()
            val outcomes = mutableListOf<CellOutcome>()
            while (!accept("}")) {
                skipWs()
                val stepAt = i
                val stepIndent = indentOf(stepAt)
                val first = name("a cell step or 'outcome'")
                when {
                    first.text == "outcome" -> {
                        val n = name("an outcome name")
                        expect(":")
                        outcomes += CellOutcome(n, vexpr(stepIndent))
                    }
                    outcomes.isNotEmpty() -> fail(stepAt, "a step after an outcome: write the steps first, then the outcomes")
                    first.text == "assume" -> steps += CellAssume(vexpr(stepIndent), first.pos)
                    else -> steps += call(first)
                }
            }
            cells += CellDecl(id, params, title, steps, outcomes, posAt(start), boundary)
        }
    }

    /** The `reviewed` note after a top-level intent clause and its source lines (DESIGN-C.md 7.2), or nulls. */
    private fun topReview(): Triple<String?, String?, SPos?> {
        if (peekWord() != "reviewed") return Triple(null, null, null)
        val start = i
        name()
        return review(start)
    }

    // ---- source lines (DESIGN-C.md 5.2) ----

    /** The source lines from here, inside an operation: there every `source` word starts one. */
    private fun sources(): List<SourceLine> = buildList { while (peekWord() == "source") add(sourceLine()) }

    /**
     * The source lines from here, at the top level: `source none` or `source "..." quote` starts one, and
     * `source "File.kt"` alone is the next declaration.
     */
    private fun topSources(): List<SourceLine> =
        buildList {
            while (peekWord() == "source") {
                val save = i
                name()
                val line = peekWord() == "none" || at("\"") && string().let { peekWord() == "quote" }
                i = save
                if (!line) break
                add(sourceLine())
            }
        }

    /** `source "<anchor>" quote "<quote>" [sha256 "<hex>"]` or `source none "<reason>"`, from its `source` word. */
    private fun sourceLine(): SourceLine {
        skipWs()
        val start = i
        name()
        if (peekWord() == "none") {
            name()
            val reason = string()
            return SourceNone(reason, posAt(start), text.substring(start, i))
        }
        val anchor = string()
        keyword("quote")
        skipWs()
        val quoteAt = i
        val quote = string()
        if (quote.isBlank()) fail(quoteAt, "a quote must not be empty")
        var end = i // peekWord skips the whitespace after the line
        val sha = if (peekWord() == "sha256") name().let { string() }.also { end = i } else null
        return SourceQuote(anchor, quote, sha, posAt(start), text.substring(start, end))
    }

    /** Arguments up to the closing `)`: `VALUE` or `NAME = VALUE`, separated by top-level commas. */
    private fun args(): List<ProbeArg> {
        val out = mutableListOf<ProbeArg>()
        skipWs()
        if (accept(")")) return out
        while (true) {
            skipWs()
            val start = i
            var depth = 0
            while (!eof()) {
                val c = text[i]
                if (c == '\n') fail(start, "a probe call must fit on one line")
                if ((c == ',' || c == ')') && depth == 0) break
                if (c == '(' || c == '[') depth++
                if (c == ')' || c == ']') depth--
                i++
            }
            val raw = text.substring(start, i)
            val named = Regex("""^([A-Za-z_]\w*)\s*=(?!=)\s*""").find(raw)
            val valueStart = start + (named?.value?.length ?: 0)
            val value = text.substring(valueStart, i).trimEnd()
            if (value.isEmpty()) fail(start, "expected an argument")
            out += ProbeArg(named?.let { Name(it.groupValues[1], posAt(start)) }, Vexpr(value, posAt(valueStart)))
            if (accept(")")) return out
            expect(",")
        }
    }
}
