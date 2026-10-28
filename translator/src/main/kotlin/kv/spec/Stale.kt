package kv.spec

import kv.sidecar.SPos
import kv.sidecar.Sidecar
import kv.sidecar.SourceNone
import kv.sidecar.SourceQuote
import kv.sidecar.intentClauses
import kv.sidecar.normalizeWhitespace
import kv.sidecar.spans

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
 * Lines `source none` cite no text and are not checked. Then the scope rules of C7 ([coverage]).
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
    findings += coverage(sidecar, blocks)
    return StaleReport(findings, quotes, unsigned, none)
}

/**
 * The scope rules of C7 (DESIGN-C.md 10.10). A block is cited if a quoted source line names its anchor; `source none`
 * cites no block.
 * 1. A scope that matches no block: error `empty-scope`.
 * 2. A block in a scope that no source line cites and no `exclude` names: error `uncovered-block` (once per block).
 * 3. An `exclude` of an anchor the snapshot does not have: error `stale-exclude`. Of a block whose fingerprint is not
 *    the one it records: warning `exclude-context-changed`. Of a block outside every scope: warning
 *    `exclude-out-of-scope`. Of a block a source line cites: warning `exclude-cited`.
 */
private fun coverage(
    sidecar: Sidecar,
    blocks: List<Block>,
): List<StaleFinding> {
    val findings = mutableListOf<StaleFinding>()
    val cited = sidecar.intentClauses().flatMap { (_, c) -> c.sources.filterIsInstance<SourceQuote>().map { it.anchor } }.toSet()
    val excluded = sidecar.excludes.map { it.anchor }.toSet()
    val reported = mutableSetOf<String>()
    for (scope in sidecar.scopes) {
        val inScope = blocks.filter { scope.covers(it.anchor, it.source) }
        if (inScope.isEmpty()) {
            findings += StaleFinding(scope.pos, "empty-scope", true, "scope \"${scope.target}\" matches no block of the snapshot: name a block anchor, a section (SOURCE#slug) or a source")
        }
        for (b in inScope.filter { it.anchor !in cited && it.anchor !in excluded && reported.add(it.anchor) }) {
            findings +=
                StaleFinding(
                    scope.pos,
                    "uncovered-block",
                    true,
                    "block ${b.anchor} (lines ${b.first}-${b.last} of ${b.path}) is in scope \"${scope.target}\", but no source line cites it and no exclude names it: cite it from the clause that encodes it, or exclude it with a reason",
                )
        }
    }
    for (e in sidecar.excludes) {
        val block = blocks.firstOrNull { it.anchor == e.anchor }
        if (block == null) {
            // A section or a source name, as a scope takes it, is no block: an exclude names one block.
            val many = blocks.count { spans(e.anchor, it.anchor, it.source) }
            val hint = if (many > 0) "\"${e.anchor}\" is a section or a source of $many block(s), and an exclude names one block (SOURCE#slug/N): write one exclude per block" else "a person must decide again what the new text leaves out"
            findings += StaleFinding(e.pos, "stale-exclude", true, "exclude \"${e.anchor}\" names no block of the snapshot; $hint")
            continue
        }
        if (e.blockSha256 != null && e.blockSha256 != block.sha256) {
            findings += StaleFinding(e.pos, "exclude-context-changed", false, "exclude \"${e.anchor}\": the block changed (the exclude records sha256 \"${e.blockSha256}\", the block now has sha256 \"${block.sha256}\"); a person should read it and decide again to leave it out")
        }
        if (sidecar.scopes.none { it.covers(block.anchor, block.source) }) {
            findings += StaleFinding(e.pos, "exclude-out-of-scope", false, "exclude \"${e.anchor}\": the block is in no scope of the sidecar, so the exclude has no effect")
        }
        if (e.anchor in cited) {
            findings += StaleFinding(e.pos, "exclude-cited", false, "exclude \"${e.anchor}\": a source line cites the block too; drop the exclude or the citation")
        }
    }
    return findings
}
