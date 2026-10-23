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
     * [itemIndent], and stops before an unmatched closing bracket, before `;` if [stopAtSemicolon], and before a
     * `{` that ends its line if [stopAtBodyBrace]. Comments become spaces, so columns stay the same.
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
                if (next >= text.length || restOfLineBlank(next) && !commentLine(next) || indentOf(next) <= itemIndent) break
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
            "module" -> ModuleDecl(dotted("a module FQN"), pos)
            "source" -> SourceDecl(string(), pos)
            "include" -> IncludeDecl(string(), pos)
            "type" -> {
                val n = name("a type name")
                expect("=")
                when (val k = name("'opaque' or 'template'").text) {
                    // DESIGN-B.md 5.1: a Kotlin class on the same line binds the opaque type to it.
                    "opaque" -> {
                        skipInlineWs()
                        TypeDecl(n, null, pos, if (!eof() && isNameStart(text[i])) dotted("a Kotlin class name") else null)
                    }
                    "template" -> TypeDecl(n, string(), pos)
                    else -> fail(i - k.length, "expected 'opaque' or 'template'")
                }
            }
            "set" -> {
                if (!at("-once")) fail(start, "unknown declaration 'set'")
                i += "-once".length
                val id = name("a property ID")
                expect(":")
                SetOnceDecl(id, name("a state name"), pos)
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
                ClassDecl(n, kname, fields, pos)
            }
            "use" -> UseDecl(dotted("a trusted model name"), pos)
            "trusted" -> trusted(pos)
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
                GhostRecordDecl(n, index, fields, pos)
            }
            "witness" -> {
                val target = dotted("CLASS.FIELD")
                val parts = target.text.split('.')
                if (parts.size != 2) fail(start, "witness needs CLASS.FIELD")
                WitnessDecl(Name(parts[0], target.pos), Name(parts[1], target.pos.copy(col = target.pos.col + parts[0].length + 1)), pos)
            }
            "operation" -> operation(pos)
            "invariant", "lemma" -> {
                val id = name("a property ID")
                expect(":")
                PropertyDecl(if (kw == "invariant") PropertyKind.INVARIANT else PropertyKind.LEMMA, id, vexpr(indent), pos)
            }
            "hole" -> {
                val n = name("MI_INV")
                if (n.text != "MI_INV") fail(i - n.text.length, "the only hole in v0 is MI_INV")
                val (body, bodyPos) = tripleString()
                HoleDecl(n, body, pos, bodyPos)
            }
            "probe" -> probe(pos, indent)
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

    private fun trusted(pos: SPos): TrustedDecl {
        keyword("fun")
        val fn = dotted("a Kotlin function name")
        expect("(")
        val params = mutableListOf<Name>()
        if (!accept(")")) {
            do {
                val t = dotted("a parameter type")
                params += if (accept("?")) t.copy(text = t.text + "?") else t
            } while (accept(","))
            expect(")")
        }
        val clock = accept("=").also { if (it) keyword("clock") }
        val returns = mutableListOf<Typed>()
        val ghosts = mutableListOf<Typed>()
        val ensures = mutableListOf<Vexpr>()
        var effectsNone = false
        var reviewed: String? = null
        var fresh: Name? = null
        var throws = false
        while (true) {
            val word = peekWord() ?: break
            if (word !in setOf("returns", "ghost", "ensures", "effects", "reviewed", "throws")) break
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
                    (if (word == "returns") returns else ghosts) += Typed(n, name("a type"))
                }
                "throws" -> throws = true
                "ensures" -> ensures += vexpr(indent)
                "effects" -> {
                    keyword("none")
                    effectsNone = true
                }
                "reviewed" -> reviewed = string()
            }
        }
        return TrustedDecl(fn, params, clock, returns, ghosts, ensures, effectsNone, reviewed, pos, fresh, throws)
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
                                FreeSource.This(name("a property name"))
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
                    items += IssueItem(result, fields, null, itemPos)
                }
                "ends_only_if", "unbacked_only_if" -> {
                    val id = name("a property ID")
                    expect(":")
                    val kind = if (kw == "ends_only_if") Allowance.ENDS_ONLY_IF else Allowance.UNBACKED_ONLY_IF
                    items += AllowanceItem(kind, id, vexpr(indent), null, itemPos)
                }
                "ensures" -> {
                    val id = name("a property ID")
                    expect(":")
                    items += EnsuresItem(id, vexpr(indent), itemPos)
                }
                // U7: `reviewed` marks the issue rule or allowance clause right before it as read by a person.
                "reviewed" -> {
                    val note = string()
                    val reviewed =
                        when (val last = items.lastOrNull()) {
                            is IssueItem -> if (last.reviewed == null) last.copy(reviewed = note) else fail(start, "the issue rule is already reviewed")
                            is AllowanceItem -> if (last.reviewed == null) last.copy(reviewed = note) else fail(start, "${last.id.text} is already reviewed")
                            else -> fail(start, "'reviewed' must follow an issue rule or an allowance clause")
                        }
                    items[items.lastIndex] = reviewed
                }
                "requires" -> fail(start, "an operation may not have a free-form 'requires' in v0 (DESIGN-v0.md 4.2, restriction 1)")
                else -> fail(start, "unknown operation item '$kw'")
            }
        }
        return OperationDecl(n, selector, items, pos)
    }

    private fun probe(
        pos: SPos,
        indent: Int,
    ): ProbeDecl {
        expect("(")
        val params = mutableListOf<Typed>()
        if (!accept(")")) {
            do {
                val n = name("a parameter name")
                expect(":")
                params += Typed(n, name("a type"))
            } while (accept(","))
            expect(")")
        }
        val requires = if (peekWord() == "requires") name().let { vexpr(indent, stopAtBodyBrace = true) } else null
        expect("{")
        val steps = mutableListOf<ProbeStep>()
        while (!accept("}")) {
            skipWs()
            val start = i
            val stepIndent = indentOf(start)
            val first = name("a probe step")
            if (first.text == "assert") {
                steps += ProbeAssert(vexpr(stepIndent), first.pos)
            } else {
                val outs = mutableListOf(first)
                while (accept(",")) outs += name("a result name")
                expect(":=")
                val op = name("an operation name")
                expect("(")
                steps += ProbeCall(outs, op, args(), first.pos)
            }
        }
        return ProbeDecl(params, requires, steps, pos)
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
