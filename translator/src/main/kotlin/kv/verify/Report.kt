package kv.verify

import kv.emit.Emitted
import kv.emit.MethodKind
import kv.emit.RESERVE_FRESH
import kv.emit.Source
import kv.emit.emitViper
import kv.emit.kind
import kv.emit.posInRange
import kv.kvir.Action
import kv.kvir.DiagnosticKind
import kv.kvir.DispositionKind
import kv.kvir.FreeValue
import kv.kvir.Module
import kv.kvir.ModuleKind
import kv.model.TrustedModels
import kv.kvir.Pos
import kv.kvir.Reservation
import kv.kvir.Status
import kv.kvir.checkWellFormed
import kv.kvir.incomplete
import kv.lower.NOT_RESERVE
import kv.lower.bindGhosts
import kv.sidecar.Allowance
import kv.sidecar.AllowanceItem
import kv.sidecar.IssueItem
import kv.sidecar.SPos
import kv.sidecar.Sidecar
import kv.sidecar.SidecarError
import kv.sidecar.SidecarException
import kv.sidecar.intentClauses
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

enum class Verdict { PROVEN, FAILED, UNSUPPORTED, ERROR }

enum class ModuleVerdict { PROVEN, FAILED, INCOMPLETE, ERROR }

/**
 * Why a FAILED action row failed. It is diagnostic only: the module verdict and the exit code do not read it.
 * Silicon reports only the first failing postcondition per path, and the hole ensures come before the properties,
 * so an action whose errors are all at hole conjuncts gets the property check (pass 2) without those ensures.
 */
enum class SubStatus(
    val text: String,
) {
    /** A sidecar property or tool obligation fails: in pass 1, or in the property check. */
    PROPERTY("property"),

    /** Pass 1 failed only at hole conjuncts, and the property check found no error in this action. */
    INVARIANT_ONLY("invariant only"),

    /** Pass 1 failed only at hole conjuncts, and the property check did not finish normally. */
    INVARIANT_ONLY_CHECK_ERROR("invariant only; property check ERROR"),
}

/**
 * One Silicon error mapped to its source (DESIGN-v0.md 5.7): [source] is the innermost range that contains the error
 * start, [at] the sidecar position inside it if the range holds a sidecar expression.
 */
data class Finding(
    val error: SiliconError,
    val source: Source?,
    val at: SPos?,
) {
    /** The failure, where it maps to, and Silicon's reason for an unknown prover result if it gave one (Q1). */
    fun describe(): String {
        val first = error.message.substringBefore(". ").removeSuffix(".")
        val where =
            when (source) {
                is Source.Sidecar -> {
                    val p = at ?: source.pos
                    "sidecar ${source.id} ${p.file.substringAfterLast('/')}:${p.line}:${p.col}" + (source.note?.let { " \"$it\"" } ?: "")
                }
                is Source.Kotlin -> "kotlin ${source.pos.file.substringAfterLast('/')}:${source.pos.line}:${source.pos.col}" + (source.note?.let { " \"$it\"" } ?: "")
                is Source.Generated -> "generated ${source.what}"
                null -> "no source range (Viper ${error.line}.${error.col})"
            }
        val reason = error.reason?.let { if (it.startsWith("(")) " $it" else " ($it)" } ?: ""
        return "$first: $where$reason"
    }
}

/**
 * The verdict of one row of the report: an action, a stray action, init, a lemma or the probe. [findings] are the
 * pass-1 errors. [sub] says why a FAILED action failed; it is null for other rows, and for a hole-only action until
 * its property check ran. [pass2] are the property check's errors in this action.
 */
data class Row(
    val kind: MethodKind,
    val label: String,
    val kotlin: Pos?,
    val verdict: Verdict,
    val findings: List<Finding>,
    val note: String? = null,
    val sub: SubStatus? = null,
    val pass2: List<Finding> = emptyList(),
)

/**
 * The property check (pass 2): one Silicon run on the program whose [actions] have no hole ensures. [error] says why
 * the run did not finish normally, or is null.
 */
data class PropertyCheck(
    val actions: List<String>,
    val seconds: Double,
    val error: String?,
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
    /** The branch reachability check (M9, INFO only); null if it did not run. */
    val reach: Reachability? = null,
    /** The property check (pass 2); null if it did not run. */
    val propertyCheck: PropertyCheck? = null,
)

/** The rows with an action's verdict and sub-status: actions, stray actions and reserve steps (DESIGN-D.md 6.7). */
private val ACTIONS = setOf(MethodKind.ACTION, MethodKind.STRAY, MethodKind.RESERVE)

private val HOLE_ID = Regex("""MI_INV_\d+""")

/** The emitter's warning for a probe it left out; group 1 is the probe's label (`probe` or `probe NAME`). */
private val NOT_EMITTED = Regex("""^(probe(?: \w+)?) not emitted: """)

/**
 * True if the error is at a hole conjunct (its `ensures MI_INV_i(S)` line or its text) or at a fresh assert of a
 * reserve step, which rests on MI only (DESIGN-D.md 6.7 item 2).
 */
private val Finding.atHole: Boolean get() = (source as? Source.Sidecar)?.id?.let(HOLE_ID::matches) == true || (source as? Source.Kotlin)?.note == RESERVE_FRESH

private const val OUTSIDE = "Silicon reported errors outside every method"

/** The error lines Silicon printed. */
private val SiliconOutcome.errorLines: List<SiliconError>
    get() =
        when (this) {
            SiliconOutcome.Verified -> emptyList()
            is SiliconOutcome.Failed -> errors
            is SiliconOutcome.Abnormal -> errors
        }

/** The labels of the FAILED actions whose pass-1 errors are all at hole conjuncts: the actions of the property check. */
fun Report.holeOnly(): List<String> = rows.filter { it.verdict == Verdict.FAILED && it.kind in ACTIONS && it.findings.all { f -> f.atHole } }.map { it.label }

/**
 * Adds the property check (pass 2) to a pass-1 report. [emitted] is the program without the hole ensures of
 * [holeOnly], [outcome] is its Silicon outcome. Each of those actions is PROPERTY if pass 2 has an error in its
 * method, else INVARIANT_ONLY; each is INVARIANT_ONLY_CHECK_ERROR if the run was abnormal or had an error outside
 * every method. No verdict changes: each row stays FAILED, so the module stays FAILED.
 */
fun Report.withPropertyCheck(
    emitted: Emitted,
    outcome: SiliconOutcome,
    seconds: Double,
): Report {
    val actions = holeOnly()
    val errors = outcome.errorLines
    val outside = errors.any { emitted.methodAt(it.line) == null }
    val error = (outcome as? SiliconOutcome.Abnormal)?.reason ?: if (outside) OUTSIDE else null
    val checked =
        rows.map { r ->
            if (r.verdict != Verdict.FAILED || r.label !in actions) return@map r
            // A reserve step keeps its fresh asserts in pass 2; an error there is not a property (DESIGN-D.md 6.7 item 2).
            val mine = errors.filter { emitted.methodAt(it.line)?.label == r.label }.map { finding(emitted, it) }.filter { !it.atHole }
            when {
                error != null -> r.copy(sub = SubStatus.INVARIANT_ONLY_CHECK_ERROR)
                mine.isNotEmpty() -> r.copy(sub = SubStatus.PROPERTY, pass2 = mine)
                else -> r.copy(sub = SubStatus.INVARIANT_ONLY)
            }
        }
    return copy(rows = checked, propertyCheck = PropertyCheck(actions, seconds, error))
}

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
    val findings = outcome.errorLines.map { e -> e to finding(emitted, e) }
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
        val sub = SubStatus.PROPERTY.takeIf { verdict == Verdict.FAILED && method.kind in ACTIONS && mine.any { !it.atHole } }
        rows += Row(method.kind, method.label, method.kotlin, verdict, mine, sub = sub)
    }
    rows += unsupportedRows(module, emitted)
    val outside = findings.filter { (e, _) -> emitted.methodAt(e.line) == null }.map { it.second }
    val error = abnormal?.reason ?: if (outside.isNotEmpty()) OUTSIDE else null
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

/** [e] mapped to its source through the position table of [emitted] (DESIGN-v0.md 5.7). */
internal fun finding(
    emitted: Emitted,
    e: SiliconError,
): Finding {
    val mapped = emitted.sourceAt(e.line, e.col)
    val src = mapped?.source
    val at = (src as? Source.Sidecar)?.let { s -> s.expr?.posInRange(mapped.range, e.line, e.col) ?: s.pos }
    return Finding(e, src, at)
}

/** Rows for what was not emitted: unsupported actions and operations, and each probe that calls one. */
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
    emitted?.warnings?.forEach { w ->
        NOT_EMITTED.find(w)?.let { rows += Row(MethodKind.PROBE, it.groupValues[1], null, Verdict.UNSUPPORTED, emptyList(), w) }
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
    val actions = rows.filter { it.kind in ACTIONS }
    val width = actions.maxOfOrNull { label(it).length } ?: 0
    for (r in actions) {
        line("  ${label(r).padEnd(width)}  ${r.kotlin?.let(::at) ?: "-"}   ${r.verdict}" + (r.sub?.let { " (${it.text})" } ?: ""))
        r.note?.let { line("      $it") }
        r.findings.forEach { f ->
            line("      ${f.describe()}")
            // DESIGN-B.md 5.5 rule 3: what a failed MI_OWN means, on a line the M8 script does not read as a finding.
            if ((f.source as? Source.Generated)?.what == "MI_OWN") line("        meaning: a map value is not an object this module created, or two keys share one object")
        }
        // The property check, on lines the M8 script does not read as pass-1 findings.
        r.pass2.forEach { line("        pass 2 (property check): ${it.describe()}") }
        when (r.sub) {
            SubStatus.INVARIANT_ONLY -> INVARIANT_ONLY.forEach { line("        $it") }
            SubStatus.INVARIANT_ONLY_CHECK_ERROR -> {
                line("        ${INVARIANT_ONLY.first()}")
                line("        property check ERROR: ${propertyCheck?.error}; it is not known whether a sidecar property fails")
            }
            else -> {}
        }
    }
    val others = rows.filter { it.kind !in ACTIONS }
    if (others.isNotEmpty()) line("  " + others.joinToString(" · ") { "${it.label} ${it.verdict}" })
    others.forEach { r ->
        r.note?.let { line("      ${r.label}: $it") }
        r.findings.forEach { line("      ${r.label}: ${it.describe()}") }
    }
    propertyCheck?.let { c ->
        val head = "Property check (pass 2): ${c.actions.joinToString()} without the hole ensures MI_INV_i"
        line(if (c.error == null) "$head (Silicon %.2f s)".format(c.seconds) else "$head: ERROR: ${c.error}; this run proves nothing")
    }
    if (otherFindings.isNotEmpty()) {
        line("Silicon errors:")
        otherFindings.forEach { line("  ${it.describe()}") }
    }
    line("Spec to review: ${specToReview(sidecar, module)}")
    line("Assumptions (not checked by Silicon):")
    val longs = module.diagnostics.filter { it.kind == DiagnosticKind.ASSUMPTION }
    line("  Long unbounded: " + longs.joinToString { at(it.pos) }.ifEmpty { "none" })
    line("  Free values:")
    fun freeValues(a: Action) =
        (listOf(a.key) + a.free).joinToString { fv -> "${fv.local.name} <- ${fv.kotlin} ${fv.local.pos.line}:${fv.local.pos.col} (${origin(fv, fv === a.key)})" }
    module.operations.forEach { line("    ${it.name}: ${freeValues(it.action)}") }
    module.strayActions.forEach { line("    stray ${it.id}: ${freeValues(it)}") }
    // DESIGN-D.md 6.7: each reserve step, the counter operation it runs before its action, and its reserved val.
    module.operations.mapNotNull { op -> op.reserve?.let { op.name to it } }.takeIf { it.isNotEmpty() }?.let { steps ->
        line("  Reserve steps (DESIGN-D.md 2.1, one atomic counter step before the action): " + steps.joinToString { (op, r) -> "$op: ${r.local.name} <- ${r.kotlin} ${r.pos.line}:${r.pos.col}" })
    }
    line("  Trusted models: ${sidecar.uses.joinToString { it.model.text }}")
    line("  Trusted project functions: " + sidecar.trusted.joinToString { "${it.shortName} (reviewed: ${it.reviewed})" }.ifEmpty { "none" })
    if (module.kind == ModuleKind.INSTANCE) {
        instanceAssumptions().forEach { line("  $it") }
        line("  Init: the map is empty and no object is created, as after the constructor's initializer")
    } else {
        val reserved = if (module.operations.any { it.reserve != null && it.action.status == Status.Supported }) " or reserved" else ""
        line("  Init: the ghost records are allocated by an inhale with no record issued$reserved (trusted)")
    }
    // Q5 (DESIGN-v0.md 8.3): v0 lists the racy reads without linking them to the free values that hold them.
    val racy = module.ledger.filter { it.kind == DispositionKind.RACY_READ }.distinctBy { it.what to it.pos }
    line("Racy reads (modeled as arbitrary values): " + racy.joinToString { d -> "${d.what} ${d.pos.line}:${d.pos.col}" }.ifEmpty { "none" })
    val notModeled = module.ledger.filter { it.kind == DispositionKind.NOT_MODELED }.distinctBy { it.what }
    line("Not modeled (no module-state access): " + notModeled.joinToString { "${it.what} ${it.pos.line}:${it.pos.col}" }.ifEmpty { "none" })
    val unsupported =
        rows.filter { it.verdict == Verdict.UNSUPPORTED }.map { "${it.label}${it.kotlin?.let { p -> " " + at(p) } ?: ""}" } +
            module.diagnostics.filter { it.kind == DiagnosticKind.UNSUPPORTED }.map { "${it.message} ${at(it.pos)}" } +
            module.ledger.filter { it.kind == DispositionKind.UNSUPPORTED }.distinctBy { it.what to it.pos }.map { "${it.what} ${it.pos.line}:${it.pos.col}" }
    line("Unsupported: " + unsupported.distinct().joinToString("; ").ifEmpty { "none" })
    // DESIGN-D.md 6.3: why a counter increment outside an action is not a reserve step.
    module.diagnostics.filter { it.kind == DiagnosticKind.INFO && it.message.startsWith(NOT_RESERVE) }.takeIf { it.isNotEmpty() }?.let { ds ->
        line("Counter operations outside an action: " + ds.joinToString("; ") { "${at(it.pos)}: ${it.message}" })
    }
    reach?.let { r ->
        val head = "Branch reachability (INFO, not part of any verdict; DESIGN-v0.md 5.4):"
        if (r.notChecked != null) {
            line("$head not checked: ${r.notChecked}")
        } else {
            line("$head ${r.probed} branch(es) probed, ${r.unreachable.size} unreachable")
            r.unreachable.forEach { b -> line("  INFO unreachable branch: ${b.action} ${at(b.pos)} ${b.what}") }
            r.note?.let { line("  note: $it") }
        }
    }
    warnings.forEach { line("Warning: $it") }
    line("Note: init and probe do not check inductiveness; the action methods do (DESIGN-v0.md 5.4).")
    return sb.toString()
}

private fun label(r: Row) =
    when (r.kind) {
        MethodKind.STRAY -> r.label
        MethodKind.RESERVE -> "step ${r.label}"
        else -> "action ${r.label}"
    }

/** What INVARIANT_ONLY means. It is not harmless: a real defect can fail this way (MUTATION.md change log, item 6). */
private val INVARIANT_ONLY =
    listOf(
        "meaning: this action does not preserve the inductive invariant (the hole MI_INV).",
        "property check: no sidecar property fails in one step from a state that satisfies the invariant.",
        "A property may still break in a later step: the code may be wrong, or the hole may be too strong for this code.",
        "Next: re-derive the hole for this code (kv hole). If no inductive invariant proves the properties, the code is wrong.",
    )

private fun origin(
    fv: FreeValue,
    key: Boolean,
): String = fv.origin.kind() + if (fv.sidecarName == null && !key && fv.origin !is Reservation) ", unnamed" else ""

/**
 * The assumptions an instance module adds (DESIGN-B.md 5.5 rule 2, appendix B, 9.4 questions 5, 6 and 8). Silicon
 * checks none of them.
 */
private fun Report.instanceAssumptions(): List<String> {
    val out = mutableListOf<String>()
    if (sidecar.setOnce.isNotEmpty()) {
        out += "Racy reads of a set-once map: a non-null get is a value the map held, so the read is null or the current value (ConcurrentHashMap.java:87-91, DESIGN-B.md 2.4)"
    }
    module.types.filter { it.kclass != null }.forEach { t ->
        out += "Key equality: == on ${t.name} is ${t.kclass!!.replace('/', '.')}.equals (${TrustedModels.keyClasses[t.kclass.replace('/', '.')] ?: "no source"})"
    }
    out += "Test sources are not audited: they may write an internal state field (DESIGN-B.md 2.1 rule 6, UB6)"
    sidecar.trusted.filter { it.fresh != null }.forEach { t ->
        out += "Trusted ${t.shortName}: a person read that every call that can throw runs before the ${t.fresh!!.text} constructor, and that the constructor and the calls reach no state or operation of this module; the tool checks direct calls only (DESIGN-B.md 3.4, 9.4 question 6)"
    }
    out += "Throws outside the model: a call the model does not give a meaning to, or an untranslated !!, may throw after a fresh call and before the action (a non-deterministic raise); elsewhere its throw is not modeled (DESIGN-B.md 9.4 question 5)"
    if (sidecar.uses.any { it.model.text == TrustedModels.GET_OR_PUT }) {
        out += "getOrPut: its inline body is the one of stdlib 2.0.21 MapsJVM.kt:70-75 (DESIGN-B.md appendix B 6, 9.4 question 8)"
    }
    return out
}

/**
 * The allowance clauses and issue rules a person must review (DESIGN-v0.md 3.6), and the defaults stray actions get.
 * Under `review intent` (DESIGN-C.md 10.1), every intent clause instead of the allowance clauses and issue rules.
 * Without a [module] (no code), there are no stray actions.
 */
fun specToReview(
    sidecar: Sidecar,
    module: Module?,
): String {
    fun clauses(kind: Allowance) =
        sidecar.operations.flatMap { op -> op.items.filterIsInstance<AllowanceItem>().filter { it.kind == kind }.map { "${op.name.text} ${it.id.text}" } }
    val strays = module?.strayActions.orEmpty()
    val intent =
        if (sidecar.reviewIntent) {
            listOf(sidecar.intentClauses().takeIf { it.isNotEmpty() }?.let { cs -> "intent clauses (review intent): ${cs.joinToString { it.first }}" })
        } else {
            listOf(
                clauses(Allowance.ENDS_ONLY_IF).takeIf { it.isNotEmpty() }?.let { "ends_only_if ${it.joinToString()}" },
                clauses(Allowance.UNBACKED_ONLY_IF).takeIf { it.isNotEmpty() }?.let { "unbacked_only_if ${it.joinToString()}" },
                sidecar.operations.filter { op -> op.items.any { it is IssueItem } }.takeIf { it.isNotEmpty() }?.let { ops -> "issue ${ops.joinToString { it.name.text }}" },
            )
        }
    val others =
        listOf(
            strays.takeIf { it.isNotEmpty() && module?.kind == ModuleKind.OBJECT }?.let { s -> "default ends_only_if false for stray ${s.joinToString { it.id }}" },
            strays.takeIf { it.isNotEmpty() && module?.kind == ModuleKind.INSTANCE }?.let { s ->
                "default contract MI${sidecar.setOnce.joinToString("") { " and ${it.id.text}" }} for stray ${s.joinToString { it.id }}"
            },
            // DESIGN-B.md 5.5 rule 1: the set-once declarations and the fresh and throws claims of trusted functions.
            sidecar.setOnce.takeIf { it.isNotEmpty() }?.let { d -> "set-once ${d.joinToString { "${it.id.text}: ${it.map.text}" }}" },
            sidecar.trusted.filter { it.fresh != null || it.throws }.takeIf { it.isNotEmpty() }?.let { ts ->
                ts.joinToString("; ") { t -> "trusted ${t.shortName}: " + listOfNotNull(t.fresh?.let { "returns fresh ${it.text}" }, "throws".takeIf { t.throws }, "effects none".takeIf { t.effectsNone }).joinToString(", ") }
            },
        )
    return (intent + others).filterNotNull().joinToString("; ").ifEmpty { "none" }
}

/**
 * Binds the ghost rules, checks W1-W14, emits the program to [vpr], runs Silicon once and reports (DESIGN-v0.md
 * 5.6-5.8). A sidecar the emitter rejects, or a W1-W14 violation (a tool defect), is ERROR without a Silicon run.
 * Next to it, the branch reachability clone (5.4 item 4, M9) goes to `<vpr>.reach.vpr` and runs at the same time
 * as a second Silicon process; its result is INFO in the report and changes no verdict.
 * If some FAILED actions failed only at hole conjuncts ([holeOnly]), the property check (pass 2) emits the program
 * without their hole ensures to `<vpr>.pass2.vpr` and runs Silicon on it once ([withPropertyCheck]). Its budget is
 * [pass2Budget]; only a test sets it, to force a pass-2 timeout.
 */
fun verify(
    module: Module,
    sidecar: Sidecar,
    vpr: File,
    home: File = Silicon.defaultHome(),
    budget: SiliconBudget = SiliconBudget(),
    pass2Budget: SiliconBudget = budget,
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
    Silicon.check(home)
    vpr.absoluteFile.parentFile.mkdirs()
    vpr.writeText(emitted.text)
    val clone = emitViper(bound, sidecar, reach = true)
    val cloneFile = File(vpr.path.removeSuffix(".vpr") + ".reach.vpr").apply { writeText(clone.text) }
    val pool = Executors.newSingleThreadExecutor()
    try {
        val cloneRun = pool.submit(Callable { Silicon.run(cloneFile, home, budget) })
        val run = Silicon.run(vpr, home, budget)
        val report = buildReport(bound, sidecar, emitted, classify(run, budget), run.seconds)
        val actions = report.holeOnly()
        val pass2File = File(vpr.path.removeSuffix(".vpr") + ".pass2.vpr")
        val checked =
            if (actions.isEmpty()) {
                // No property check: a pass-2 program of an earlier run at the same path would mislead.
                pass2File.delete()
                report
            } else {
                val pass2 = emitViper(bound, sidecar, withoutHole = actions.toSet())
                pass2File.writeText(pass2.text)
                val run2 = Silicon.run(pass2File, home, pass2Budget)
                report.withPropertyCheck(pass2, classify(run2, pass2Budget), run2.seconds)
            }
        return checked.copy(reach = reachability(clone, cloneRun.get(), budget))
    } finally {
        pool.shutdownNow()
    }
}

/** The row of [label]. */
fun Report.row(label: String): Row = rows.single { it.label == label }
