package kv.spec

import kv.sidecar.EnsuresItem
import kv.sidecar.IssueItem
import kv.sidecar.OperationDecl
import kv.sidecar.PropertyDecl
import kv.sidecar.SPos
import kv.sidecar.Sidecar
import kv.sidecar.StateKind
import kv.sidecar.Vexpr
import kv.sidecar.normalizeWhitespace

/** A field-fixing warning (DESIGN-C.md 4.1): [clause] reads the entry [fields] in its premise, and no intent clause ties them. */
data class FieldWarning(
    val clause: String,
    val pos: SPos,
    val fields: List<String>,
) {
    override fun toString(): String =
        "$pos: warning[field-fixing]: $clause reads entry field${if (fields.size > 1) "s" else ""} ${fields.joinToString()} in its premise, and no intent clause " +
            "ties ${if (fields.size > 1) "them" else "it"} to a ghost value or an issue value: the meaning lives only in the hole, so a new hole can change it (DESIGN-C.md 4.1)"
}

/**
 * The field-fixing check of DESIGN-C.md 4.1. An `ensures` whose premise (the text before its last top-level `==>`)
 * reads a field of a map entry, `S.m[...].f`, other than the witness field, needs an intent clause (an ensures or an
 * invariant, not the hole) with an equality that ties f to a ghost value (a field of the ghost record) or to the value
 * an issue rule gives (in that issuing operation's clauses). Otherwise the field's meaning is only in the hole: mode b
 * cannot decide the cells that need it (3.2), and a new hole can change it (3.5, D14). A field's own property (P2)
 * is no premise read, so it is not checked.
 */
fun fieldFixing(sidecar: Sidecar): List<FieldWarning> {
    val maps = sidecar.states.filter { it.kind is StateKind.MapOf }
    val witness = sidecar.witnesses.singleOrNull()?.field?.text
    val ghost = sidecar.ghosts.singleOrNull() ?: return emptyList()
    val fields =
        maps.associate { st ->
            val cls = sidecar.classes.single { it.name.text == (st.kind as StateKind.MapOf).value.text }
            st.name.text to cls.fields.map { it.name.text }.filter { it != witness }.toSet()
        }
    val ghostFields = ghost.fields.map { it.name.text }.toSet()

    // Ties: (field) from every equality of an intent clause between an entry field and a ghost value or an issue value.
    val tied = mutableSetOf<String>()
    fun ties(
        e: Vexpr,
        issueValues: Set<String>,
    ) {
        for ((a, b) in equalities(e.text)) {
            for ((x, y) in listOf(a to b, b to a)) {
                val read = entryFields(x, fields).toSet()
                if (read.isEmpty()) continue
                if (ghostRead(y, ghost.name.text, ghostFields) || normalizeWhitespace(y) in issueValues) tied += read
            }
        }
    }
    for (op in sidecar.operations) {
        val issueValues = op.items.filterIsInstance<IssueItem>().flatMap { it.fields.map { (_, v) -> normalizeWhitespace(v.text) } }.toSet()
        op.items.filterIsInstance<EnsuresItem>().forEach { ties(it.expr, issueValues) }
    }
    sidecar.decls.filterIsInstance<PropertyDecl>().forEach { ties(it.expr, emptySet()) }

    return sidecar.operations.flatMap { op -> op.items.filterIsInstance<EnsuresItem>().mapNotNull { e -> warning(op, e, fields, tied) } }
}

private fun warning(
    op: OperationDecl,
    e: EnsuresItem,
    fields: Map<String, Set<String>>,
    tied: Set<String>,
): FieldWarning? {
    val premise = premise(e.expr.text) ?: return null
    val untied = entryFields(premise, fields).distinct().filter { it !in tied }
    return if (untied.isEmpty()) null else FieldWarning("ensures ${e.id.text} of ${op.name.text}", e.pos, untied.sorted())
}

/** Top-level tokens: operators, brackets, separators and everything between them. */
private val TOKEN = Regex("""<==>|==>|==|!=|&&|\|\||::|[()\[\]{},?:]""")

/** The text before the last `==>` at bracket depth 0, or null if there is none. */
internal fun premise(text: String): String? {
    var depth = 0
    var last = -1
    for (m in TOKEN.findAll(text)) {
        when (m.value) {
            "(", "[", "{" -> depth++
            ")", "]", "}" -> depth--
            "==>" -> if (depth == 0) last = m.range.first
        }
    }
    return if (last < 0) null else text.substring(0, last)
}

/**
 * The two operands of every `==` in [text]: each runs from the `==` to the nearest operator weaker than `==`
 * (`&&`, `||`, `==>`, `<==>`, `?`, `:`, `::`, `,`), trigger brace or unmatched bracket at the same depth.
 */
internal fun equalities(text: String): List<Pair<String, String>> {
    val tokens = TOKEN.findAll(text).toList()
    val out = mutableListOf<Pair<String, String>>()
    // In Viper a brace only opens or closes a trigger, so it ends an operand.
    val stops = setOf("<==>", "==>", "&&", "||", "::", ",", "?", ":", "{", "}")
    for ((n, t) in tokens.withIndex()) {
        if (t.value != "==") continue
        var depth = 0
        var from = 0
        for (j in n - 1 downTo 0) {
            val v = tokens[j].value
            if (v == ")" || v == "]") depth++
            if (v == "(" || v == "[") {
                if (depth == 0) {
                    from = tokens[j].range.last + 1
                    break
                }
                depth--
            }
            if (depth == 0 && v in stops) {
                from = tokens[j].range.last + 1
                break
            }
        }
        depth = 0
        var to = text.length
        for (j in n + 1 until tokens.size) {
            val v = tokens[j].value
            if (v == "(" || v == "[") depth++
            if (v == ")" || v == "]") {
                if (depth == 0) {
                    to = tokens[j].range.first
                    break
                }
                depth--
            }
            if (depth == 0 && (v in stops || v == "==" || v == "!=")) {
                to = tokens[j].range.first
                break
            }
        }
        out += text.substring(from, t.range.first).trim() to text.substring(t.range.last + 1, to).trim()
    }
    return out
}

/** The entry fields [text] reads: `S.<map>[...].<field>` with a field of the map's value class in [fields]. */
internal fun entryFields(
    text: String,
    fields: Map<String, Set<String>>,
): List<String> {
    val out = mutableListOf<String>()
    for ((map, names) in fields) {
        var i = text.indexOf("S.$map[")
        while (i >= 0) {
            var j = i + "S.$map[".length
            var depth = 1
            while (j < text.length && depth > 0) {
                if (text[j] == '[') depth++
                if (text[j] == ']') depth--
                j++
            }
            Regex("""^\.(\w+)""").find(text.substring(j))?.groupValues?.get(1)?.takeIf { it in names }?.let { out += it }
            i = text.indexOf("S.$map[", j)
        }
    }
    return out
}

/** True if [text] reads a field of ghost record [ghost]: `ghost(...).f` with f in [ghostFields]. */
private fun ghostRead(
    text: String,
    ghost: String,
    ghostFields: Set<String>,
): Boolean {
    var i = Regex("""(?<![\w.])$ghost\(""").find(text)?.range?.first ?: return false
    while (i >= 0) {
        var j = text.indexOf('(', i) + 1
        var depth = 1
        while (j < text.length && depth > 0) {
            if (text[j] == '(') depth++
            if (text[j] == ')') depth--
            j++
        }
        if (Regex("""^\.(\w+)""").find(text.substring(j))?.groupValues?.get(1) in ghostFields) return true
        i = Regex("""(?<![\w.])$ghost\(""").find(text, j)?.range?.first ?: -1
    }
    return false
}
