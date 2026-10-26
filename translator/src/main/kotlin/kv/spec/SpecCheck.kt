package kv.spec

import kv.emit.AssumedFact
import kv.emit.ContractOnly
import kv.emit.Emitted
import kv.emit.MethodKind
import kv.emit.Source
import kv.emit.cellMethod
import kv.emit.emitViper
import kv.frontend.Frontend
import kv.json.jsonOf
import kv.kvir.Module
import kv.kvir.checkWellFormed
import kv.lower.bindGhosts
import kv.sidecar.CellDecl
import kv.sidecar.SPos
import kv.sidecar.Sidecar
import kv.sidecar.SidecarException
import kv.sidecar.SidecarLoader
import kv.sidecar.checkCells
import kv.sidecar.checkSidecar
import kv.sidecar.intentClauses
import kv.sidecar.parseCells
import kv.verify.Silicon
import kv.verify.SiliconBudget
import kv.verify.SiliconOutcome
import kv.verify.classify
import kv.verify.finding
import kv.verify.specToReview
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** The two modes of the determinacy check (DESIGN-C.md 3.2 step 4). */
enum class Mode(
    val letter: String,
    val text: String,
) {
    A("a", "with the hole MI_INV (the clauses and the implementation's inductive invariant)"),
    B("b", "the hole is true (the sidecar clauses alone)"),
}

/** A cell's result: DETERMINED(o), OPEN, INFEASIBLE, or ERROR when a step fails. */
object CellVerdict {
    const val OPEN = "OPEN"
    const val INFEASIBLE = "INFEASIBLE"

    fun determined(outcomes: List<String>) = "DETERMINED(${outcomes.joinToString("|")})"

    fun error(why: String) = "ERROR ($why)"
}

/**
 * The cells of one [mode] (DESIGN-C.md 3.2): cell name to its verdict. [error] says why the run counts for nothing
 * (an abnormal Silicon run, or errors outside the cells: then [cells] is empty and [findings] holds those errors).
 */
data class Determinacy(
    val mode: Mode,
    val cells: Map<String, String>,
    val error: String?,
    val findings: List<String>,
    val seconds: Double?,
)

/**
 * The verdicts of [cells] from one Silicon [outcome] on the contract-only model [emitted]. A cell is INFEASIBLE if
 * its reach method fails at its `refute false` (the end is unreachable); else DETERMINED by every outcome whose
 * method verifies; else OPEN. A failure at a step (a call's precondition) makes the cell ERROR: the scenario breaks
 * the contracts before its end. An error outside the cell methods makes the whole mode ERROR.
 */
fun classifyCells(
    mode: Mode,
    cells: List<CellDecl>,
    emitted: Emitted,
    outcome: SiliconOutcome,
    seconds: Double?,
): Determinacy {
    val errors =
        when (outcome) {
            SiliconOutcome.Verified -> emptyList()
            is SiliconOutcome.Failed -> outcome.errors
            is SiliconOutcome.Abnormal -> return Determinacy(mode, emptyMap(), outcome.reason, outcome.errors.map { finding(emitted, it).describe() }, seconds)
        }
    // The lines of each cell method's last statement (its assert or refute): an error there is the cell's answer.
    val tailLines =
        emitted.ranges.filter { r -> (r.source as? Source.Sidecar)?.id?.let { it.startsWith("cell ") && (it.endsWith(" reach") || " outcome " in it) } == true }
            .flatMap { r -> (r.range.line..r.range.endLine).toList() }.toSet()
    // Per cell method: whether its last statement failed, and the first failing step.
    val failed = mutableSetOf<String>()
    val steps = mutableMapOf<String, String>()
    val outside = mutableListOf<String>()
    for (e in errors) {
        val m = emitted.methodAt(e.line)
        when {
            m == null || m.kind != MethodKind.CELL -> outside += finding(emitted, e).describe()
            e.line in tailLines -> failed += m.name
            else -> steps.putIfAbsent(m.name, ((finding(emitted, e).source as? Source.Sidecar)?.id ?: finding(emitted, e).describe()))
        }
    }
    if (outside.isNotEmpty()) return Determinacy(mode, emptyMap(), "Silicon reported errors outside the cells, so the contracts themselves fail and no cell counts", outside, seconds)
    val verdicts =
        cells.associate { c ->
            val methods = c.outcomes.map { cellMethod(c.id.text, it.name.text) } + cellMethod(c.id.text, null)
            val step = methods.firstNotNullOfOrNull { steps[it] }
            c.id.text to
                when {
                    step != null -> CellVerdict.error("a step fails: $step")
                    cellMethod(c.id.text, null) in failed -> CellVerdict.INFEASIBLE
                    else -> c.outcomes.map { it.name.text }.filter { cellMethod(c.id.text, it) !in failed }.let { if (it.isEmpty()) CellVerdict.OPEN else CellVerdict.determined(it) }
                }
        }
    return Determinacy(mode, verdicts, null, emptyList(), seconds)
}

/** One sidecar clause of an action in the clause isolation runs (DESIGN-C.md 4.1): FAILED, PROVEN, UNKNOWN or ERROR. */
data class ClauseResult(
    val action: String,
    val index: Int,
    val clause: String,
    val pos: SPos?,
    val verdict: String,
    val detail: String?,
)

/**
 * Clause isolation (DESIGN-C.md 4.1): [runs] Silicon runs on the code, each with every action's tool ensures and one
 * of its sidecar clauses. [other] are errors outside the kept clauses (tool ensures, invariants, init), each once;
 * [hole] are those at a hole conjunct, so mode a rests on a hole the code does not keep. [errors] are abnormal runs.
 */
data class Isolation(
    val runs: Int,
    val clauses: List<ClauseResult>,
    val other: List<String>,
    val hole: List<String>,
    val errors: List<String>,
)

/** The clause results, other errors, hole errors and run error of one isolation run (see [Isolation]). */
data class IsolationRun(
    val clauses: List<ClauseResult>,
    val other: List<String>,
    val hole: List<String>,
    val error: String?,
)

private val HOLE_ID = Regex("""MI_INV_\d+""")

/**
 * The verdicts of the clauses that run [emitted] keeps, from its Silicon [outcome]. A clause is FAILED if an error is
 * inside it. Silicon reports only the first failure of each path, so a clause without an error is UNKNOWN if its
 * action has another error in this run (on that error's path the clause was not checked), else PROVEN. An abnormal run
 * makes each kept clause ERROR.
 */
fun isolationRun(
    emitted: Emitted,
    outcome: SiliconOutcome,
): IsolationRun {
    val kept = emitted.clauses
    if (outcome is SiliconOutcome.Abnormal) {
        return IsolationRun(kept.map { ClauseResult(it.action, it.index, it.name, it.pos, "ERROR", outcome.reason) }, outcome.errors.map { finding(emitted, it).describe() }, emptyList(), outcome.reason)
    }
    val errors = (outcome as? SiliconOutcome.Failed)?.errors.orEmpty()
    val inClause = mutableMapOf<Int, MutableList<String>>()
    val otherByMethod = mutableMapOf<String?, MutableList<String>>()
    val hole = mutableListOf<String>()
    for (e in errors) {
        val f = finding(emitted, e)
        val c = kept.indexOfFirst { it.range.contains(e.line, e.col) }
        if (c >= 0) {
            inClause.getOrPut(c) { mutableListOf() } += f.describe()
            continue
        }
        val method = emitted.methodAt(e.line)?.label
        otherByMethod.getOrPut(method) { mutableListOf() } += "${method ?: "outside every method"}: ${f.describe()}"
        if ((f.source as? Source.Sidecar)?.id?.let(HOLE_ID::matches) == true) hole += "${method ?: "?"}: ${f.describe()}"
    }
    val results =
        kept.mapIndexed { i, c ->
            val other = otherByMethod[c.action]
            when {
                inClause.containsKey(i) -> ClauseResult(c.action, c.index, c.name, c.pos, "FAILED", inClause.getValue(i).joinToString("; "))
                other != null -> ClauseResult(c.action, c.index, c.name, c.pos, "UNKNOWN", "another error of the action in this run may hide it: ${other.first()}")
                else -> ClauseResult(c.action, c.index, c.name, c.pos, "PROVEN", null)
            }
        }
    return IsolationRun(results, otherByMethod.values.flatten(), hole, null)
}

/** The inputs of [specCheck] (`kv spec check`, DESIGN-C.md 7.1). Without [root], only mode b runs and no isolation. */
data class SpecCheckOptions(
    val sidecar: File,
    val snapshot: File,
    val cells: File?,
    val root: File?,
    val classpath: List<File>,
    val kotlincArgs: List<String>,
    val draft: Boolean,
    val out: File,
    val home: File,
    val budget: SiliconBudget,
    val assumed: Set<AssumedFact> = emptySet(),
    val jobs: Int = 3,
)

/** What `kv spec check` found. [errors] stop the check or make a part of it count for nothing. */
data class SpecCheckReport(
    val options: SpecCheckOptions,
    val module: String?,
    val errors: List<String>,
    val unsigned: List<String>,
    val stale: StaleReport?,
    val fieldWarnings: List<FieldWarning>,
    val modelAssumptions: List<String>,
    val cells: List<CellDecl>,
    val determinacy: List<Determinacy>,
    val isolation: Isolation?,
    val specToReview: String?,
) {
    /** Errors of every kind: sidecar, cell file, stale-source, Silicon runs that count for nothing. */
    val errorCount: Int
        get() =
            errors.size + (stale?.errors ?: 0) + determinacy.count { it.error != null } + (isolation?.errors?.size ?: 0)
}

/**
 * `kv spec check` (DESIGN-C.md 7.1, 4.1): checks a sidecar before a person sees it.
 * 1. Grammar and types: the sidecar checks; a [SpecCheckOptions.draft] is not signed yet ([checkSidecar]).
 * 2. Sources: [staleCheck] against the snapshot.
 * 3. Field fixing ([fieldFixing]).
 * 4. Determinacy of the cells on the contract-only model ([ContractOnly]): mode a (with the hole) and mode b (the hole
 *    is true) with code, mode b alone without. One Silicon run per mode. Without cells, mode b runs alone: Silicon
 *    type-checks the clauses and checks that the contracts are well-formed.
 * 5. With code: clause isolation, one Silicon run per clause index ([emitViper] onlyClause).
 * The Viper files and check.json go to [SpecCheckOptions.out], check.json also after an error. Silicon runs
 * [SpecCheckOptions.jobs] at a time.
 */
fun specCheck(o: SpecCheckOptions): SpecCheckReport =
    check(o).also {
        o.out.mkdirs()
        File(o.out, "check.json").writeText(it.json() + "\n")
    }

private fun check(o: SpecCheckOptions): SpecCheckReport {
    var report = SpecCheckReport(o, null, emptyList(), emptyList(), null, emptyList(), emptyList(), emptyList(), emptyList(), null, null)
    val sidecar =
        try {
            SidecarLoader.load(o.sidecar)
        } catch (e: SidecarException) {
            return report.copy(errors = e.errors.map { it.toString() })
        }
    report = report.copy(module = sidecar.modules.firstOrNull()?.fqn?.text)
    val errors = checkSidecar(sidecar, o.draft).map { it.toString() }
    if (errors.isNotEmpty()) return report.copy(errors = errors)
    val unsigned = if (o.draft) sidecar.intentClauses().filter { (_, c) -> c.reviewed == null }.map { it.first } else emptyList()
    val blocks =
        try {
            readSnapshot(o.snapshot)
        } catch (e: SnapshotException) {
            return report.copy(errors = listOf(e.message!!))
        }
    report = report.copy(unsigned = unsigned, stale = staleCheck(sidecar, blocks), fieldWarnings = fieldFixing(sidecar), specToReview = specToReview(sidecar, null))
    val cells =
        try {
            o.cells?.let { f -> parseCells(f.path, f.readText()) }.orEmpty()
        } catch (e: SidecarException) {
            return report.copy(errors = e.errors.map { it.toString() })
        }
    val cellErrors = checkCells(sidecar, cells).map { it.toString() }
    if (cellErrors.isNotEmpty()) return report.copy(errors = cellErrors)
    report = report.copy(cells = cells)

    val (module, assumptions) =
        if (o.root == null) {
            try {
                sidecarModule(sidecar).let { it.module to it.assumptions }
            } catch (e: SidecarException) {
                return report.copy(errors = e.errors.map { it.toString() })
            }
        } else {
            val (bound, errs) = codeModule(o)
            (bound ?: return report.copy(errors = errs)) to emptyList()
        }
    report = report.copy(modelAssumptions = assumptions, specToReview = specToReview(sidecar, module.takeIf { o.root != null }))
    o.out.mkdirs()
    val pool = Executors.newFixedThreadPool(o.jobs)
    try {
        // Without cells, mode b still runs: Silicon type-checks the clauses and checks that the contracts are well-formed.
        val modes = if (o.root == null || cells.isEmpty()) listOf(Mode.B) else listOf(Mode.A, Mode.B)
        val det =
            modes.map { mode ->
                val emitted =
                    try {
                        emitViper(module, sidecar, contract = ContractOnly(cells, mode == Mode.B, o.root != null, o.assumed))
                    } catch (e: SidecarException) {
                        return report.copy(errors = e.errors.map { it.toString() })
                    }
                val vpr = File(o.out, "contract-${mode.letter}.vpr").apply { writeText(emitted.text) }
                pool.submit(Callable { Silicon.run(vpr, o.home, o.budget).let { run -> classifyCells(mode, cells, emitted, classify(run, o.budget, refutes = true), run.seconds) } })
            }
        val iso =
            try {
                if (o.root == null) null else isolation(module, sidecar, o, pool)
            } catch (e: SidecarException) {
                return report.copy(errors = e.errors.map { it.toString() })
            }
        report = report.copy(determinacy = det.map { it.get() }, isolation = iso?.get())
    } finally {
        pool.shutdownNow()
    }
    return report
}

/** The module of the code, lowered and with the ghost rules bound; or null and the compile, sidecar or W1-W16 errors. */
private fun codeModule(o: SpecCheckOptions): Pair<Module?, List<String>> {
    val outcome = Frontend.resolve(o.sidecar, o.root!!, o.classpath, o.kotlincArgs, draft = o.draft)
    outcome.compileMessages?.let { return null to listOf("compilation failed:\n$it") }
    if (outcome.errors.isNotEmpty()) return null to outcome.errors.map { it.toString() }
    val module = outcome.module ?: return null to listOf("the module was not lowered")
    val bound =
        try {
            bindGhosts(module, outcome.sidecar!!)
        } catch (e: SidecarException) {
            return null to e.errors.map { it.toString() }
        }
    val violations = checkWellFormed(bound)
    return if (violations.isEmpty()) bound to emptyList() else null to violations.map { "the KVIR violates W1-W16 (a tool defect): $it" }
}

/**
 * Clause isolation on the code: run j keeps clause j of every action ([emitViper] onlyClause). The number of runs is
 * the largest number of sidecar clauses of one action.
 */
private fun isolation(
    module: Module,
    sidecar: Sidecar,
    o: SpecCheckOptions,
    pool: ExecutorService,
): Future<Isolation> {
    val all = emitViper(module, sidecar).clauses
    val runs = all.groupingBy { it.action }.eachCount().values.maxOrNull() ?: 0
    val futures =
        (0 until runs).map { j ->
            val emitted = emitViper(module, sidecar, onlyClause = j)
            val vpr = File(o.out, "isolate-$j.vpr").apply { writeText(emitted.text) }
            pool.submit(Callable { Silicon.run(vpr, o.home, o.budget).let { run -> isolationRun(emitted, classify(run, o.budget)) } })
        }
    return pool.submit(
        Callable {
            val done = futures.map { it.get() }
            Isolation(
                runs,
                done.flatMap { it.clauses }.sortedWith(compareBy({ c -> all.indexOfFirst { it.action == c.action } }, { it.index })),
                done.flatMap { it.other }.distinct(),
                done.flatMap { it.hole }.distinct(),
                done.mapIndexedNotNull { j, r -> r.error?.let { "isolation run $j: $it" } },
            )
        },
    )
}

/** The first line of a report on a draft (DESIGN-C.md 9.2 JC6): `kv spec check --draft` and `kv verify --draft`. */
const val DRAFT_LINE = "DRAFT: not signed; the verdict counts for nothing"

/** The report as text. With a draft, the first line says that the verdict counts for nothing (DESIGN-C.md 9.2 JC6). */
fun SpecCheckReport.render(): String {
    val sb = StringBuilder()

    fun line(s: String = "") = sb.append(s).append('\n')
    if (options.draft) line(DRAFT_LINE)
    line("kv spec check: ${options.sidecar.name}${module?.let { " (module $it)" } ?: ""}")
    if (errors.isEmpty()) line("Grammar and types: OK") else line("Errors: ${errors.size}")
    errors.forEach { line("  $it") }
    if (options.draft) line("Signatures: draft, ${unsigned.size} intent clause(s) without a 'reviewed' note" + unsigned.joinToString(prefix = ": ").takeIf { unsigned.isNotEmpty() }.orEmpty())
    stale?.let { r ->
        val counts = r.findings.groupingBy { it.code }.eachCount()
        line(
            "Sources (snapshot ${options.snapshot.path}): ${r.quotes} quoted source line(s), ${r.withoutBlockFingerprint} without a block fingerprint, ${r.none} 'source none': " +
                listOf("stale-source", "source-moved", "source-context-changed").joinToString { "${counts[it] ?: 0} $it" },
        )
        r.findings.forEach { line("  $it") }
    }
    if (stale != null) {
        line("Field fixing: ${fieldWarnings.size} warning(s)")
        fieldWarnings.forEach { line("  $it") }
    }
    if (determinacy.isNotEmpty() || isolation != null) {
        line(
            "Model: contract-only (no method bodies), " +
                when {
                    options.root == null -> "from the sidecar alone; mode b only: without code, no run proves the hole"
                    cells.isEmpty() -> "from the code"
                    else -> "from the code; mode a assumes the hole, which the isolation runs check on the code"
                },
        )
        modelAssumptions.forEach { line("  assumed type: $it") }
        if (options.assumed.isNotEmpty()) line("  assumed for a measurement, not proven (DESIGN-C.md 10.2): ${options.assumed.joinToString { it.option }}")
    }
    if (determinacy.isNotEmpty() && cells.isEmpty()) {
        val d = determinacy.single()
        line("Types: no cells; the contract-only model (mode b) " + (d.error?.let { "is ERROR: $it" } ?: "type-checks and its contracts are well-formed (Silicon %.2f s)".format(d.seconds ?: 0.0)))
        d.findings.forEach { line("  $it") }
    }
    if (determinacy.isNotEmpty() && cells.isNotEmpty()) {
        line("Cells (${options.cells?.path}): ${cells.size} cell(s)")
        determinacy.forEach { d -> line("  mode ${d.mode.letter}, ${d.mode.text}: " + (d.error?.let { "ERROR: $it" } ?: "Silicon %.2f s".format(d.seconds ?: 0.0))) }
        determinacy.filter { it.error != null }.forEach { d -> d.findings.forEach { line("    mode ${d.mode.letter}: $it") } }
        val width = maxOf(4, cells.maxOf { it.id.text.length })
        val cols = determinacy.map { d -> d to (cells.maxOf { (d.cells[it.id.text] ?: "-").length }.coerceAtLeast(6)) }
        line("  " + "cell".padEnd(width) + cols.joinToString("") { (d, w) -> "  " + "mode ${d.mode.letter}".padEnd(w) } + "  title")
        cells.forEach { c ->
            line("  " + c.id.text.padEnd(width) + cols.joinToString("") { (d, w) -> "  " + (d.cells[c.id.text] ?: "-").padEnd(w) } + "  " + c.title.orEmpty())
        }
    }
    isolation?.let { iso ->
        val n = iso.clauses.groupingBy { it.verdict }.eachCount()
        line(
            "Clause isolation (on the code; each run keeps the tool ensures, the hole and one sidecar clause per action): ${iso.runs} run(s), ${iso.clauses.size} clause(s): " +
                listOf("FAILED", "UNKNOWN", "PROVEN", "ERROR").joinToString { "${n[it] ?: 0} $it" },
        )
        iso.clauses.filter { it.verdict != "PROVEN" }.forEach { c ->
            line("  ${c.verdict} action ${c.action}: ${c.clause}${c.pos?.let { p -> " ${p.file.substringAfterLast('/')}:${p.line}:${p.col}" } ?: ""}")
            c.detail?.let { line("      $it") }
        }
        iso.errors.forEach { line("  ERROR $it") }
        if (iso.other.isNotEmpty()) line("  Errors outside the kept clauses (in some run):")
        iso.other.forEach { line("    $it") }
        line(
            "  Hole MI_INV on the code: " +
                if (iso.hole.isEmpty()) "no hole conjunct fails in any run" else "a hole conjunct fails, so mode a assumes a hole this code does not keep and counts for nothing",
        )
    }
    specToReview?.let { line("Spec to review: $it") }
    val b = determinacy.find { it.mode == Mode.B }?.cells?.values.orEmpty()
    line(
        "kv spec check: $errorCount error(s), ${fieldWarnings.size + (stale?.findings?.count { !it.error } ?: 0)} warning(s)" +
            (if (cells.isNotEmpty()) "; mode b cells: ${b.count { it.startsWith("DETERMINED") }} DETERMINED, ${b.count { it == CellVerdict.OPEN }} OPEN, ${b.count { it == CellVerdict.INFEASIBLE }} INFEASIBLE" else "") +
            (isolation?.let { "; ${it.clauses.count { c -> c.verdict == "FAILED" }} failing clause(s)" } ?: ""),
    )
    return sb.toString()
}

/** The report as JSON (check.json): every finding, every cell of every mode, every clause. */
fun SpecCheckReport.json(): String {
    fun pos(p: SPos?) = p?.let { "${it.file}:${it.line}:${it.col}" }
    return jsonOf(
        linkedMapOf(
            "sidecar" to options.sidecar.path,
            "module" to module,
            "draft" to options.draft,
            "errors" to errors,
            "unsigned" to unsigned,
            "stale" to stale?.findings?.map { mapOf("pos" to pos(it.pos), "code" to it.code, "error" to it.error, "message" to it.message) },
            "field_fixing" to fieldWarnings.map { mapOf("clause" to it.clause, "pos" to pos(it.pos), "fields" to it.fields) },
            "model" to mapOf("from_code" to (options.root != null), "assumptions" to modelAssumptions, "assumed_facts" to options.assumed.map { it.option }),
            // The cells as the cell file has them, so that `spec.py questions` needs no cell parser (DESIGN-C.md 3.3).
            "cell_list" to
                cells.map { c ->
                    linkedMapOf("id" to c.id.text, "title" to c.title, "boundary" to c.boundary, "outcomes" to c.outcomes.map { linkedMapOf("name" to it.name.text, "expr" to it.expr.text) })
                },
            "cells" to
                determinacy.associate { d ->
                    d.mode.letter to linkedMapOf("error" to d.error, "findings" to d.findings, "millis" to d.seconds?.let { (it * 1000).toLong() }, "cells" to d.cells)
                },
            "isolation" to
                isolation?.let { iso ->
                    linkedMapOf(
                        "runs" to iso.runs,
                        "clauses" to iso.clauses.map { linkedMapOf("action" to it.action, "index" to it.index, "clause" to it.clause, "pos" to pos(it.pos), "verdict" to it.verdict, "detail" to it.detail) },
                        "other" to iso.other,
                        "hole" to iso.hole,
                        "errors" to iso.errors,
                    )
                },
            "spec_to_review" to specToReview,
        ),
    )
}
