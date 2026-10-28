package kv.sidecar

/** A note whose fingerprint [restamped] wrote: [what] names its subject, [line] is its line, [old] what it recorded. */
data class Restamp(
    val what: String,
    val line: Int,
    val old: String?,
    val new: String,
)

/**
 * Sidecar [text] with every `reviewed` note stamped with the fingerprint of its subject as it is now, and the notes
 * whose fingerprint changed. Subjects: each intent clause (its text and source lines), the vocabulary (DESIGN-E 12.3),
 * each `scope` and `exclude` (DESIGN-C.md 10.10). Trusted declarations keep theirs: their unit includes Kotlin text,
 * which only `kv resolve` reads. A note without a fingerprint gets one. Throws [SidecarException] if [text] does not
 * parse.
 *
 * This re-signs without a person. It is for copies whose edits are declared elsewhere: test fixtures and the binding
 * adaptations of the AI-CODE harness (AI-CODE.md 6.1). A person signs the original sidecar.
 */
fun restamped(
    file: String,
    text: String,
): Pair<String, List<Restamp>> {
    val s = parseSidecar(file, text)
    val lines = text.split("\n").toMutableList()
    val changed = mutableListOf<Restamp>()
    fun stamp(
        what: String,
        at: SPos,
        recorded: String?,
        reviewed: String,
    ) {
        val now = reviewFingerprint(reviewed)
        if (now == recorded) return
        val n = at.line - 1
        lines[n] = recorded?.let { lines[n].replace("sha256 \"$it\"", "sha256 \"$now\"") } ?: "${lines[n]} sha256 \"$now\""
        changed += Restamp(what, at.line, recorded, now)
    }
    for ((what, item) in s.intentClauses()) item.reviewPos?.let { stamp(what, it, item.sha256, item.reviewedText) }
    s.vocabularyReviews.forEach { v -> v.reviewPos?.let { stamp("the vocabulary", it, v.sha256, s.vocabularyText) } }
    s.scopes.forEach { d -> d.reviewPos?.let { stamp("scope \"${d.target}\"", it, d.sha256, d.unit) } }
    s.excludes.forEach { d -> d.reviewPos?.let { stamp("exclude \"${d.anchor}\"", it, d.sha256, d.unit) } }
    return lines.joinToString("\n") to changed
}
