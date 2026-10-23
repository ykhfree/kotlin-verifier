package kv.sidecar

// The M7 hole flow (DESIGN-v0.md 7 M7, 8.3 "M7 edit surface"). An LLM edits only the text of `hole MI_INV """..."""`
// in the sidecar file that holds it. The tool writes the skeleton and checks an edit; it calls no LLM.

/**
 * Statements and constructs that would let the hole assume what the module must prove. The list is the
 * checker's of spike/e3/e3_harness.py; text after `//` on a line is a comment and is not searched.
 */
private val FORBIDDEN = Regex("""\b(assume|inhale|exhale|refute|import|method|function|predicate|axiom)\b|\bdomain\s+\w+\s*\{|\bfield\s+\w+\s*:""")

/** The hole text of a skeleton: `MI_INV` is `true`, so the module cannot be proven until the hole is written. */
const val SKELETON_HOLE = "\n  true\n"

/** The offsets of the hole text (between the triple quotes) in [text], the content of sidecar [file]. */
private fun holeSpan(
    file: String,
    text: String,
): IntRange {
    val holes = parseSidecar(file, text).holes
    if (holes.size != 1) {
        val at = holes.getOrNull(1)?.pos ?: SPos(file, 1, 1)
        throw SidecarException(listOf(SidecarError(at, "hole-edit", "the file holds ${holes.size} 'hole MI_INV' declarations; the hole flow needs the file that holds the one hole")))
    }
    val hole = holes.single()
    val start = offsetOf(text, hole.textPos.line, hole.textPos.col)
    return start until start + hole.text.length
}

private fun offsetOf(
    text: String,
    line: Int,
    col: Int,
): Int {
    var offset = 0
    repeat(line - 1) { offset = text.indexOf('\n', offset) + 1 }
    return offset + col - 1
}

private fun posOf(
    file: String,
    text: String,
    offset: Int,
): SPos {
    val before = text.substring(0, offset)
    return SPos(file, before.count { it == '\n' } + 1, offset - (before.lastIndexOf('\n') + 1) + 1)
}

/** [text] (sidecar [file]) with its hole replaced by [SKELETON_HOLE]. Throws [SidecarException]. */
fun holeSkeleton(
    file: String,
    text: String,
): String {
    val span = holeSpan(file, text)
    return text.substring(0, span.first) + SKELETON_HOLE + text.substring(span.last + 1)
}

/**
 * Checks an edit of the hole (spike e3 rules): [after] must equal [before] byte for byte outside the hole text, and
 * the hole of [after] must hold no forbidden word outside comments. Returns the reasons to reject the edit, each at
 * its position in [afterFile]; an empty list accepts it. Throws [SidecarException] if [before] has no single hole.
 */
fun checkHoleEdit(
    beforeFile: String,
    before: String,
    afterFile: String,
    after: String,
): List<SidecarError> {
    val old = holeSpan(beforeFile, before)
    val new =
        try {
            holeSpan(afterFile, after)
        } catch (e: SidecarException) {
            return e.errors
        }
    val oldHead = before.substring(0, old.first)
    val newHead = after.substring(0, new.first)
    val oldTail = before.substring(old.last + 1)
    val newTail = after.substring(new.last + 1)
    val outside =
        when {
            oldHead != newHead -> firstDifference(oldHead, newHead)
            oldTail != newTail -> new.last + 1 + firstDifference(oldTail, newTail)
            else -> null
        }
    if (outside != null) {
        return listOf(SidecarError(posOf(afterFile, after, outside), "hole-edit", "the text outside the hole MI_INV was changed; an edit may change only the hole text"))
    }
    // Comments become spaces, so a match offset is a position in the hole.
    val hole = after.substring(new).replace(Regex("//[^\n]*")) { " ".repeat(it.value.length) }
    return FORBIDDEN.findAll(hole).map { m ->
        SidecarError(posOf(afterFile, after, new.first + m.range.first), "hole-forbidden", "the hole uses '${m.value.trim()}'; a hole may not assume, inhale, exhale or refute, nor declare methods, functions, predicates, axioms, domains, fields or imports")
    }.toList()
}

/** The first offset where [a] and [b] differ; the shorter length if one is a prefix of the other. */
private fun firstDifference(
    a: String,
    b: String,
): Int = a.indices.firstOrNull { it >= b.length || a[it] != b[it] } ?: a.length
