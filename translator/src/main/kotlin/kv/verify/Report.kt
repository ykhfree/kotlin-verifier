package kv.verify

import kv.emit.Emitted
import kv.emit.MethodKind
import kv.emit.Source
import kv.emit.emitViper
import kv.emit.kind
import kv.emit.posInRange
import kv.kvir.Action
import kv.kvir.DiagnosticKind
import kv.kvir.DispositionKind
import kv.kvir.FreeValue
import kv.kvir.Module
import kv.kvir.Pos
import kv.kvir.RacyRead
import kv.kvir.Status
import kv.kvir.checkWellFormed
import kv.kvir.incomplete
import kv.lower.bindGhosts
import kv.sidecar.Allowance
import kv.sidecar.AllowanceItem
import kv.sidecar.IssueItem
import kv.sidecar.SPos
import kv.sidecar.Sidecar
import kv.sidecar.SidecarError
import kv.sidecar.SidecarException
import java.io.File

enum class Verdict { PROVEN, FAILED, UNSUPPORTED, ERROR }

enum class ModuleVerdict { PROVEN, FAILED, INCOMPLETE, ERROR }

/**
 * One Silicon error mapped to its source (DESIGN-v0.md 5.7): [source] is the innermost range that contains the error
 * start, [at] the sidecar position inside it if the range holds a sidecar expression.
 */
data class Finding(
    val error: SiliconError,
    val source: Source?,
    val at: SPos?,
) {
    fun describe(): String {
        val first = error.message.substringBefore(". ").removeSuffix(".")
        val where =
            when (source) {
                is Source.Sidecar -> {
                    val p = at ?: source.pos
                    "sidecar ${source.id} ${p.file.substringAfterLast('/')}:${p.line}:${p.col}" + (source.note?.let { " \"$it\"" } ?: "")
                }
                is Source.Kotlin -> "kotlin ${source.pos.file.substringAfterLast('/')}:${source.pos.line}:${source.pos.col}"
                is Source.Generated -> "generated ${source.what}"
                null -> "no source range (Viper ${error.line}.${error.col})"
            }
        return "$first: $where"
    }
}

/** The verdict of one row of the report: an action, a stray action, init, a lemma or the probe. */
data class Row(
    val kind: MethodKind,
    val label: String,
    val kotlin: Pos?,
    val verdict: Verdict,
    val findings: List<Finding>,
    val note: String? = null,
)

data class Report(
    val module: Module,
    val sidecar: Sidecar,
    val verdict: ModuleVerdict,
    val rows: List<Row>,
    /** Why the run was ERROR, or null. */
    val error: String?,
    /** Silicon errors outside every method, or of an abnormal run, mapped to their sources. */
    val otherFindings: List<Finding>,
    /** Sidecar errors that stopped the emitter. */
    val sidecarErrors: List<SidecarError>,
    val warnings: List<String>,
    val seconds: Double?,
)

/**
 * Builds the report of DESIGN-v0.md 5.8 from the bound [module], the [emitted] program and Silicon's [outcome].
 * Module verdict: ERROR if the run is abnormal; else FAILED if a row failed; else INCOMPLETE if something is
 * unsupported; else PROVEN.
 */
fun buildReport(
    module: Module,
    sidecar: Sidecar,
    emitted: Emitted,
    outcome: SiliconOutcome,
    seconds: Double?,
): Report {
    val errors =
        when (outcome) {
            SiliconOutcome.Verified -> emptyList()
            is SiliconOutcome.Failed -> outcome.errors
            is SiliconOutcome.Abnormal -> outcome.errors
        }
    val findings = errors.map { e -> e to finding(emitted, e) }
    val abnormal = outcome as? SiliconOutcome.Abnormal
    val rows = mutableListOf<Row>()
    for (method in emitted.methods) {
        val mine = findings.filter { (e, _) -> emitted.methodAt(e.line) == method }.map { it.second }
        val verdict =
            when {
                abnormal != null -> Verdict.ERROR
                mine.isNotEmpty() -> Verdict.FAILED
                else -> Verdict.PROVEN
            }
        rows += Row(method.kind, method.label, method.kotlin, verdict, mine)
    }
    rows += unsupportedRows(module, emitted)
    val outside = findings.filter { (e, _) -> emitted.methodAt(e.line) == null }.map { it.second }
    val error = abnormal?.reason ?: if (outside.isNotEmpty()) "Silicon reported errors outside every method" else null
    val verdict =
        when {
            error != null -> ModuleVerdict.ERROR
            rows.any { it.verdict == Verdict.FAILED } -> ModuleVerdict.FAILED
            module.incomplete || rows.any { it.verdict == Verdict.UNSUPPORTED } -> ModuleVerdict.INCOMPLETE
            else -> ModuleVerdict.PROVEN
        }
    // An abnormal run proves nothing: every row it touched is ERROR, so no row can read PROVEN.
    val finalRows = if (error != null) rows.map { if (it.verdict == Verdict.PROVEN) it.copy(verdict = Verdict.ERROR) else it } else rows
    return Report(module, sidecar, verdict, finalRows, error, outside, emptyList(), emitted.warnings, seconds)
}

/** The report of a module that was not emitted: ERROR for [reason], with the sidecar errors if any. */
fun notEmittedReport(
    module: Module,
    sidecar: Sidecar,
    reason: String,
    errors: List<SidecarError> = emptyList(),
): Report = Report(module, sidecar, ModuleVerdict.ERROR, unsupportedRows(module, null), reason, emptyList(), errors, emptyList(), null)

private fun finding(
    emitted: Emitted,
    e: SiliconError,
): Finding {
    val mapped = emitted.sourceAt(e.line, e.col)
    val src = mapped?.source
    val at = (src as? Source.Sidecar)?.let { s -> s.expr?.posInRange(mapped.range, e.line, e.col) ?: s.pos }
    return Finding(e, src, at)
}

/** Rows for what was not emitted: unsupported actions and operations, and a probe that calls one. */
private fun unsupportedRows(
    module: Module,
    emitted: Emitted?,
): List<Row> {
    val rows = mutableListOf<Row>()
    module.operations.filter { it.action.status is Status.Unsupported }.forEach {
        rows += Row(MethodKind.ACTION, it.name, it.action.pos, Verdict.UNSUPPORTED, emptyList(), (it.action.status as Status.Unsupported).diagnostics.joinToString("; ") { d -> d.message })
    }
    module.strayActions.filter { it.status is Status.Unsupported }.forEach {
        rows += Row(MethodKind.STRAY, "stray action ${it.id}", it.pos, Verdict.UNSUPPORTED, emptyList(), (it.status as Status.Unsupported).diagnostics.joinToString("; ") { d -> d.message })
    }
    if (emitted != null && emitted.warnings.any { it.startsWith("probe not emitted") }) {
        rows += Row(MethodKind.PROBE, "probe", null, Verdict.UNSUPPORTED, emptyList(), emitted.warnings.first { it.startsWith("probe not emitted") })
    }
    return rows
}

/** The report as text, in the layout of DESIGN-v0.md 5.8. */
fun Report.render(): String {
    val sb = StringBuilder()

    fun line(s: String = "") = sb.append(s).append('\n')

    fun at(p: Pos) = "${p.file.substringAfterLast('/')}:${p.line}:${p.col}"
    line("${module.id} (${sidecar.file.substringAfterLast('/')})    module: $verdict" + (seconds?.let { " (Silicon %.2f s)".format(it) } ?: ""))
    error?.let { line("  ERROR: $it; this run proves nothing") }
    sidecarErrors.forEach { line("  sidecar error: $it") }
    val actions = rows.filter { it.kind == MethodKind.ACTION || it.kind == MethodKind.STRAY }
    val width = actions.maxOfOrNull { label(it).length } ?: 0
    for (r in actions) {
        line("  ${label(r).padEnd(width)}  ${r.kotlin?.let(::at) ?: "-"}   ${r.verdict}")
        r.note?.let { line("      $it") }
        r.findings.forEach { line("      ${it.describe()}") }
    }
    val others = rows.filter { it.kind != MethodKind.ACTION && it.kind != MethodKind.STRAY }
    if (others.isNotEmpty()) line("  " + others.joinToString(" · ") { "${it.label} ${it.verdict}" })
    others.forEach { r ->
        r.note?.let { line("      ${r.label}: $it") }
        r.findings.forEach { line("      ${r.label}: ${it.describe()}") }
    }
    if (otherFindings.isNotEmpty()) {
        line("Silicon errors:")
        otherFindings.forEach { line("  ${it.describe()}") }
    }
    line("Spec to review: ${specToReview()}")
    line("Assumptions (not checked by Silicon):")
    val longs = module.diagnostics.filter { it.kind == DiagnosticKind.ASSUMPTION }
    line("  Long unbounded: " + longs.joinToString { at(it.pos) }.ifEmpty { "none" })
    line("  Free values:")
    fun freeValues(a: Action) =
        (listOf(a.key) + a.free).joinToString { fv -> "${fv.local.name} <- ${fv.kotlin} ${fv.local.pos.line}:${fv.local.pos.col} (${origin(fv, fv === a.key)})" }
    module.operations.forEach { line("    ${it.name}: ${freeValues(it.action)}") }
    module.strayActions.forEach { line("    stray ${it.id}: ${freeValues(it)}") }
    line("  Trusted models: ${sidecar.uses.joinToString { it.model.text }}")
    line("  Trusted project functions: " + sidecar.trusted.joinToString { "${it.shortName} (reviewed: ${it.reviewed})" }.ifEmpty { "none" })
    line("  Init: the ghost records are allocated by an inhale with no record issued (trusted)")
    val racy = module.ledger.filter { it.kind == DispositionKind.RACY_READ }.distinctBy { it.what to it.pos }
    val racyUses = (module.operations.map { it.name to it.action } + module.strayActions.map { "stray ${it.id}" to it }).flatMap { (n, a) -> a.free.filter { it.origin is RacyRead }.map { fv -> "${fv.origin.pos.line}:${fv.origin.pos.col}" to "$n ${fv.local.name}" } }.toMap()
    line(
        "Racy reads (modeled as arbitrary values): " +
            racy.joinToString { d -> "${d.what} ${d.pos.line}:${d.pos.col}" + (racyUses["${d.pos.line}:${d.pos.col}"]?.let { " -> $it" } ?: "") }.ifEmpty { "none" },
    )
    val notModeled = module.ledger.filter { it.kind == DispositionKind.NOT_MODELED }.distinctBy { it.what }
    line("Not modeled (no module-state access): " + notModeled.joinToString { "${it.what} ${it.pos.line}:${it.pos.col}" }.ifEmpty { "none" })
    val unsupported =
        rows.filter { it.verdict == Verdict.UNSUPPORTED }.map { "${it.label}${it.kotlin?.let { p -> " " + at(p) } ?: ""}" } +
            module.diagnostics.filter { it.kind == DiagnosticKind.UNSUPPORTED }.map { "${it.message} ${at(it.pos)}" } +
            module.ledger.filter { it.kind == DispositionKind.UNSUPPORTED }.distinctBy { it.what to it.pos }.map { "${it.what} ${it.pos.line}:${it.pos.col}" }
    line("Unsupported: " + unsupported.distinct().joinToString("; ").ifEmpty { "none" })
    warnings.forEach { line("Warning: $it") }
    line("Note: init and probe do not check inductiveness; the action methods do (DESIGN-v0.md 5.4).")
    return sb.toString()
}

private fun label(r: Row) = if (r.kind == MethodKind.STRAY) r.label else "action ${r.label}"

private fun origin(
    fv: FreeValue,
    key: Boolean,
): String = fv.origin.kind() + if (fv.sidecarName == null && !key) ", unnamed" else ""

/** The allowance clauses and issue rules a person must review (DESIGN-v0.md 3.6), and the defaults stray actions get. */
private fun Report.specToReview(): String {
    fun clauses(kind: Allowance) =
        sidecar.operations.flatMap { op -> op.items.filterIsInstance<AllowanceItem>().filter { it.kind == kind }.map { "${op.name.text} ${it.id.text}" } }
    val parts =
        listOfNotNull(
            clauses(Allowance.ENDS_ONLY_IF).takeIf { it.isNotEmpty() }?.let { "ends_only_if ${it.joinToString()}" },
            clauses(Allowance.UNBACKED_ONLY_IF).takeIf { it.isNotEmpty() }?.let { "unbacked_only_if ${it.joinToString()}" },
            sidecar.operations.filter { op -> op.items.any { it is IssueItem } }.takeIf { it.isNotEmpty() }?.let { ops -> "issue ${ops.joinToString { it.name.text }}" },
            module.strayActions.takeIf { it.isNotEmpty() }?.let { s -> "default ends_only_if false for stray ${s.joinToString { it.id }}" },
        )
    return parts.joinToString("; ").ifEmpty { "none" }
}

/**
 * Binds the ghost rules, checks W1-W14, emits the program to [vpr], runs Silicon once and reports (DESIGN-v0.md
 * 5.6-5.8). A sidecar the emitter rejects, or a W1-W14 violation (a tool defect), is ERROR without a Silicon run.
 */
fun verify(
    module: Module,
    sidecar: Sidecar,
    vpr: File,
    home: File = Silicon.defaultHome(),
    budget: SiliconBudget = SiliconBudget(),
): Report {
    val (bound, emitted) =
        try {
            val bound = bindGhosts(module, sidecar)
            val violations = checkWellFormed(bound)
            if (violations.isNotEmpty()) return notEmittedReport(bound, sidecar, "the KVIR violates W1-W14 (a tool defect): ${violations.joinToString("; ")}")
            bound to emitViper(bound, sidecar)
        } catch (e: SidecarException) {
            return notEmittedReport(module, sidecar, "the sidecar cannot be emitted", e.errors)
        }
    vpr.absoluteFile.parentFile.mkdirs()
    vpr.writeText(emitted.text)
    val run = Silicon.run(vpr, home, budget)
    return buildReport(bound, sidecar, emitted, classify(run, budget), run.seconds)
}

/** The row of [label]. */
fun Report.row(label: String): Row = rows.single { it.label == label }
