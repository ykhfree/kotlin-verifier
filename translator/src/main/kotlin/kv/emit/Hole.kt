package kv.emit

import kv.sidecar.HoleDecl
import kv.sidecar.SidecarError
import kv.sidecar.SidecarException
import kv.sidecar.Vexpr

/**
 * One top-level conjunct of the hole `MI_INV` (DESIGN-v0.md 5.7: one `ensures` per part). [expr] is its text without
 * the comment lines in front of it. [ownComment] holds those comment lines. [name] is the comment that names the
 * conjunct in reports: its own comment, or else the nearest earlier conjunct's comment, or null if no conjunct before
 * it has a comment.
 */
data class HoleConjunct(
    val expr: Vexpr,
    val ownComment: List<String>,
    val name: String?,
)

private val QUANTIFIERS = setOf("forall", "exists", "let")

/**
 * Splits the hole on its top-level `&&`. The split keeps the meaning only if `&&` is the weakest top-level operator,
 * so a hole with a top-level `||`, `==>`, `<==>`, `?:` or an unparenthesized quantifier stays one conjunct.
 */
fun splitHole(hole: HoleDecl): List<HoleConjunct> {
    val text = hole.text
    val whole = Vexpr(text, hole.textPos)
    val cuts = mutableListOf<Int>()
    var weaker = false
    var depth = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            text.startsWith("//", i) -> {
                while (i < text.length && text[i] != '\n') i++
                continue
            }
            c == '(' || c == '[' || c == '{' -> depth++
            c == ')' || c == ']' || c == '}' -> depth--
            depth == 0 && text.startsWith("&&", i) -> {
                cuts += i
                i += 2
                continue
            }
            depth == 0 && (text.startsWith("||", i) || text.startsWith("==>", i) || c == '?') -> weaker = true
            depth == 0 && (c.isLetter() || c == '_') -> {
                val end = (i until text.length).firstOrNull { !text[it].isLetterOrDigit() && text[it] != '_' } ?: text.length
                if (text.substring(i, end) in QUANTIFIERS) weaker = true
                i = end
                continue
            }
        }
        i++
    }
    val bounds = if (weaker) listOf(0 to text.length) else (listOf(-2) + cuts).zip(cuts + text.length).map { (a, b) -> a + 2 to b }
    var previous: String? = null
    return bounds.map { (from, to) ->
        var j = from
        val comments = mutableListOf<String>()
        while (true) {
            while (j < to && text[j].isWhitespace()) j++
            if (!text.startsWith("//", j)) break
            val end = text.indexOf('\n', j).let { if (it < 0 || it > to) to else it }
            comments += text.substring(j + 2, end).trim()
            j = end
        }
        val body = text.substring(j, to).trimEnd()
        // An empty conjunct is reported at the `&&` before it.
        if (body.isEmpty()) throw SidecarException(listOf(SidecarError(whole.posAt(if (from >= 2) from - 2 else j), "hole", "the hole MI_INV has an empty conjunct")))
        val name = comments.joinToString(" ").ifEmpty { null } ?: previous
        previous = name
        HoleConjunct(Vexpr(body, whole.posAt(j)), comments, name)
    }
}
