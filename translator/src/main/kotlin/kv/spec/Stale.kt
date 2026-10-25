package kv.spec

import kv.sidecar.SPos
import kv.sidecar.Sidecar
import kv.sidecar.SourceNone
import kv.sidecar.SourceQuote
import kv.sidecar.intentClauses
import kv.sidecar.normalizeWhitespace

/** One finding of `kv spec stale` (DESIGN-C.md 5.3) at the source line [pos]. Only `stale-source` is an [error]. */
data class StaleFinding(
    val pos: SPos,
    val code: String,
    val error: Boolean,
    val message: String,
) {
    override fun toString(): String = "$pos: ${if (error) "error" else "warning"}[$code]: $message"
}

/** What [staleCheck] found, and how many source lines it read. */
data class StaleReport(
    val findings: List<StaleFinding>,
    val quotes: Int,
    val withoutBlockFingerprint: Int,
    val none: Int,
) {
    val errors: Int get() = findings.count { it.error }
}

/**
 * The stale rules of DESIGN-C.md 5.3 for every quoted source line of [sidecar] against the snapshot [blocks]. A quote
 * matches a block if, with every whitespace run as one space on both sides, it is a substring of the block's text.
 * 1. The quote is in its anchor block, and the block's fingerprint is the one the source line records (or the line
 *    records none): no finding.
 * 2. The quote is in its anchor block, but the block's fingerprint differs: warning `source-context-changed`.
 * 3. The quote is not in its anchor block (or the anchor is gone) but in another block: warning `source-moved`.
 * 4. The quote is in no block: error `stale-source`.
 * Lines `source none` cite no text and are not checked. The rule `uncovered-block` (C7) is not here.
 */
fun staleCheck(
    sidecar: Sidecar,
    blocks: List<Block>,
): StaleReport {
    val normal = blocks.map { it to normalizeWhitespace(it.text) }
    val findings = mutableListOf<StaleFinding>()
    var quotes = 0
    var unsigned = 0
    var none = 0
    for ((what, clause) in sidecar.intentClauses()) {
        val clauseAt = "$what (${clause.pos.line}:${clause.pos.col})"
        for (line in clause.sources) {
            if (line is SourceNone) {
                none++
                continue
            }
            val src = line as SourceQuote
            quotes++
            if (src.sha256 == null) unsigned++
            val q = normalizeWhitespace(src.quote)
            val anchored = normal.filter { (b, _) -> b.anchor == src.anchor }
            val block = anchored.firstOrNull { (_, text) -> q in text }?.first
            if (block != null) {
                if (src.sha256 != null && src.sha256 != block.sha256) {
                    findings +=
                        StaleFinding(
                            src.pos,
                            "source-context-changed",
                            false,
                            "$clauseAt: block ${src.anchor} changed (the source line records sha256 \"${src.sha256}\", the block now has sha256 \"${block.sha256}\"); the quote is still in it, so a person should read the block again",
                        )
                }
                continue
            }
            val gone = if (anchored.isEmpty()) " (no such block in the snapshot)" else ""
            val elsewhere = normal.filter { (_, text) -> q in text }.map { (b, _) -> b.anchor }.distinct()
            findings +=
                if (elsewhere.isNotEmpty()) {
                    StaleFinding(src.pos, "source-moved", false, "$clauseAt: quote \"${src.quote}\" is not in ${src.anchor}$gone but in ${elsewhere.joinToString()}: change the anchor to the block it cites")
                } else {
                    StaleFinding(src.pos, "stale-source", true, "$clauseAt: quote \"${src.quote}\" is in no block of the snapshot (anchor ${src.anchor}$gone); a person must review the clause against the new text")
                }
        }
    }
    return StaleReport(findings, quotes, unsigned, none)
}
