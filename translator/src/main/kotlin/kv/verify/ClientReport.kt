package kv.verify

import kv.emit.ClientIds
import kv.emit.Emitted
import kv.emit.MethodKind
import kv.emit.Source
import kv.emit.emitClient
import kv.emit.switchableIds
import kv.kvir.DiagnosticKind
import kv.kvir.DispositionKind
import kv.kvir.Module
import kv.kvir.Pos
import kv.kvir.While
import kv.kvir.checkWellFormed
import kv.kvir.incomplete
import kv.kvir.walk
import kv.sidecar.CLIENT_ASSUMPTIONS
import kv.sidecar.EnsuresItem
import kv.sidecar.GuardItem
import kv.sidecar.Sidecar
import kv.sidecar.SidecarException
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** One Silicon error of a client model, mapped to its property ID (DESIGN-E 6.7 item 1) and where it is. */
data class ClientFinding(
    val id: String,
    val message: String,
    /** The Kotlin position (a call, a spawn, a loop, a method) or the sidecar clause, as text. */
    val where: String,
    /** The Viper method the error is in. */
    val method: String?,
    /** The source of the error's position (DESIGN-v0.md 5.7). */
    val source: Source?,
)

/** A row of the client report: an entry, a task or a loop (DESIGN-E 6.7). */
data class ClientRow(
    val kind: String,
    val label: String,
    val kotlin: Pos?,
    val verdict: Verdict,
    val findings: List<ClientFinding>,
    val note: String? = null,
)

/** The verdict of one property ID: FAILED if any run found it ([where]: the rows), else PROVEN. */
data class PropertyRow(
    val id: String,
    val what: String,
    val failed: Boolean,
    val where: List<String>,
)

data class ClientReport(
    val module: Module,
    val sidecar: Sidecar,
    val verdict: ModuleVerdict,
    val rows: List<ClientRow>,
    /** Null if the runs proved nothing (ERROR). */
    val properties: List<PropertyRow>?,
    val error: String?,
    val seconds: Double?,
    /** The isolation runs of the full failing set (DESIGN-E 5.2): count and wall clock seconds; 0 if pass 1 verified. */
    val isolationRuns: Int = 0,
    val isolationSeconds: Double? = null,
)

/** The assumption texts of DESIGN-E 4, by ID: what it says when on, and when off. */
private val ASSUMPTION_TEXT =
    mapOf(
        "A1" to ("a java.lang.Error ends the process, so no call throws one" to "a call may throw a java.lang.Error"),
        "A2" to ("the obligation calls (KeyLock.tryLock, KeyLock.unLock) return normally" to "the obligation calls may throw (UE3)"),
        "A3" to ("a task the executor accepted runs" to "an accepted task may be dropped (shutdownNow)"),
        "A4" to ("a throwing release ends the obligation; the lease ends the lock" to "a throwing release keeps the obligation"),
        "A5" to ("a throwing acquire leaves the caller no obligation; the lease ends a lock the store took" to "a throwing acquire may have taken the lock"),
    )

private val TOOL_FACT_TEXT =
    mapOf(
        ClientIds.LD_NOLEAK to "tool fact: every thread end (the call, each task) holds no obligation",
        ClientIds.LD_OWN to "tool fact: a release consumes an obligation the thread holds",
        ClientIds.LD_TRANSFER to "tool fact: a spawn hands the task its precondition, obligations included",
        ClientIds.LD_WAIT to "tool fact: a loop holds no obligation",
        ClientIds.TERM to "tool fact: the loop measure decreases",
    )

/** The property ID of a Silicon error, by the source of its position (DESIGN-E 6.7 item 1). */
private fun idOf(
    emitted: Emitted,
    e: SiliconError,
): ClientFinding {
    val mapped = emitted.sourceAt(e.line, e.col)
    val method = emitted.methodAt(e.line)?.label
    val message = e.message.substringBefore(". ").removeSuffix(".")
    fun at(p: Pos) = "${p.file.substringAfterLast('/')}:${p.line}:${p.col}"
    return when (val s = mapped?.source) {
        is Source.Sidecar -> ClientFinding(s.id, message, "sidecar ${s.pos.file.substringAfterLast('/')}:${s.pos.line}:${s.pos.col}", method, s)
        is Source.Kotlin -> ClientFinding(s.note ?: if (e.message.startsWith("Loop might not terminate")) ClientIds.TERM else "code", message, "kotlin ${at(s.pos)}", method, s)
        is Source.Generated -> ClientFinding(s.what, message, "generated ${s.what}", method, s)
        null -> ClientFinding("unmapped", message, "Viper ${e.line}.${e.col}", method, null)
    }
}

/** The findings of one Silicon outcome, or null for an abnormal run. */
private fun findings(
    emitted: Emitted,
    outcome: SiliconOutcome,
): List<ClientFinding>? =
    when (outcome) {
        SiliconOutcome.Verified -> emptyList()
        is SiliconOutcome.Failed -> outcome.errors.map { idOf(emitted, it) }
        is SiliconOutcome.Abnormal -> null
    }

/**
 * `kv verify` for a client module (DESIGN-E 6.7): emits the program to [vpr] and runs Silicon once. If that run fails,
 * the full failing set follows (DESIGN-E 5.2): one run per switchable property ID with only its checks on, and one run
 * with none, [jobs] at a time, to `<vpr>.iso.<ID>.vpr`. Silicon stops a path at its first failing check, so one run
 * alone can hide a failure behind another.
 */
fun verifyClient(
    module: Module,
    sidecar: Sidecar,
    vpr: File,
    home: File = Silicon.defaultHome(),
    budget: SiliconBudget = SiliconBudget(),
    jobs: Int = minOf(8, Runtime.getRuntime().availableProcessors()),
): ClientReport {
    val violations = checkWellFormed(module)
    if (violations.isNotEmpty()) return notEmitted(module, sidecar, "the KVIR violates W1-W22 (a tool defect): ${violations.joinToString("; ")}")
    if (module.incomplete) return notEmitted(module, sidecar, null)
    val emitted =
        try {
            emitClient(module, sidecar)
        } catch (e: SidecarException) {
            return notEmitted(module, sidecar, "the sidecar cannot be emitted: ${e.errors.joinToString("; ")}")
        }
    Silicon.check(home)
    vpr.absoluteFile.parentFile.mkdirs()
    vpr.writeText(emitted.text)
    val run = Silicon.run(vpr, home, budget)
    val outcome = classify(run, budget)
    val first = findings(emitted, outcome) ?: return errorReport(module, sidecar, emitted, (outcome as SiliconOutcome.Abnormal).reason, run.seconds)
    if (first.isEmpty()) return report(module, sidecar, emitted, first, run.seconds, 0, null)
    // The full failing set: each switchable ID alone, and none (the checks that are always on).
    val keeps = switchableIds(sidecar) + "none"
    val start = System.nanoTime()
    val pool = Executors.newFixedThreadPool(jobs.coerceAtLeast(1))
    try {
        val runs =
            keeps.map { keep ->
                pool.submit(
                    Callable {
                        val iso = emitClient(module, sidecar, keep)
                        val file = File(vpr.path.removeSuffix(".vpr") + ".iso.$keep.vpr").apply { writeText(iso.text) }
                        val r = Silicon.run(file, home, budget)
                        val o = classify(r, budget)
                        Triple(keep, findings(iso, o), (o as? SiliconOutcome.Abnormal)?.reason)
                    },
                )
            }.map { it.get() }
        val seconds = (System.nanoTime() - start) / 1e9
        runs.firstOrNull { it.second == null }?.let { (keep, _, reason) ->
            return errorReport(module, sidecar, emitted, "isolation run $keep: $reason", run.seconds)
        }
        val all = (first + runs.flatMap { it.second!! }).distinct()
        return report(module, sidecar, emitted, all, run.seconds, keeps.size, seconds)
    } finally {
        pool.shutdownNow()
    }
}

private fun notEmitted(
    module: Module,
    sidecar: Sidecar,
    reason: String?,
): ClientReport {
    val verdict = if (reason == null) ModuleVerdict.INCOMPLETE else ModuleVerdict.ERROR
    val rows =
        module.entries.map { ClientRow("entry", it.kotlin.substringAfterLast('.'), it.pos, if (it.status is kv.kvir.Status.Supported) Verdict.ERROR else Verdict.UNSUPPORTED, emptyList()) } +
            module.tasks.map { ClientRow("task", it.id, it.pos, if (it.status is kv.kvir.Status.Supported) Verdict.ERROR else Verdict.UNSUPPORTED, emptyList()) }
    return ClientReport(module, sidecar, verdict, rows, null, reason, null)
}

private fun errorReport(
    module: Module,
    sidecar: Sidecar,
    emitted: Emitted,
    reason: String,
    seconds: Double?,
): ClientReport {
    val rows = units(module, emitted).map { (kind, label, pos) -> ClientRow(kind, label, pos, Verdict.ERROR, emptyList()) }
    return ClientReport(module, sidecar, ModuleVerdict.ERROR, rows, null, reason, seconds)
}

/** The rows of the report: each entry and task method, then each loop with a hole. */
private fun units(
    module: Module,
    emitted: Emitted,
): List<Triple<String, String, Pos?>> =
    emitted.methods.filter { it.kind == MethodKind.ENTRY }.map { Triple("entry", it.label, it.kotlin) } +
        emitted.methods.filter { it.kind == MethodKind.TASK }.map { Triple("task", it.label, it.kotlin) } +
        loops(module).map { (w, _) -> Triple("loop", w.hole!!, w.pos) }

private fun loops(module: Module): List<Pair<While, String>> =
    (module.entries.map { it.body to it.kotlin.substringAfterLast('.') } + module.tasks.map { it.body to it.id })
        .flatMap { (body, unit) -> body.walk().filterIsInstance<While>().filter { it.hole != null }.map { it to unit } }

private val LOOP_IDS = setOf(ClientIds.HOLE, ClientIds.TERM, ClientIds.LD_WAIT, ClientIds.FRAME)

private fun report(
    module: Module,
    sidecar: Sidecar,
    emitted: Emitted,
    all: List<ClientFinding>,
    seconds: Double?,
    runs: Int,
    isolationSeconds: Double?,
): ClientReport {
    val whiles = loops(module).map { it.first }

    /** The loop a finding is about: a hole line of its sidecar hole, or the loop's own checks (frame, LD_wait, TERM). */
    fun loopOf(f: ClientFinding): While? {
        if (f.id !in LOOP_IDS) return null
        return when (val src = f.source) {
            is Source.Sidecar -> {
                val hole = sidecar.holes.firstOrNull { h -> h.loop != null && src.pos.file == h.textPos.file && src.pos.line in h.textPos.line..h.textPos.line + h.text.count { it == '\n' } }
                whiles.firstOrNull { w -> hole?.loop?.label == w.hole }
            }
            is Source.Kotlin -> whiles.firstOrNull { it.pos == src.pos }
            else -> null
        }
    }
    val rows =
        units(module, emitted).map { (kind, label, pos) ->
            val mine =
                if (kind == "loop") {
                    all.filter { f -> loopOf(f)?.let { it.hole == label } == true }
                } else {
                    all.filter { it.method == label && loopOf(it) == null }
                }
            val note = if (kind == "loop") "hole loop $label" else null
            ClientRow(kind, label, pos, if (mine.isEmpty()) Verdict.PROVEN else Verdict.FAILED, mine.distinctBy { it.id to it.where }, note)
        }
    val op = sidecar.operations.single()
    val ids =
        ClientIds.TOOL_FACTS.map { it to TOOL_FACT_TEXT.getValue(it) } +
            listOfNotNull((ClientIds.HOLE to "loop invariant (sidecar hole loop)").takeIf { loops(module).isNotEmpty() }) +
            op.items.filterIsInstance<GuardItem>().map { it.id.text to "guard before each call of ${it.call.text}" } +
            op.items.filterIsInstance<EnsuresItem>().map { it.id.text to "ensures of ${op.name.text}" }
    val known = ids.map { it.first }.toSet()
    val properties =
        ids.map { (id, what) ->
            val hits = all.filter { it.id == id }
            PropertyRow(id, what, hits.isNotEmpty(), hits.mapNotNull { it.method }.distinct())
        } + all.filter { it.id !in known }.groupBy { it.id }.map { (id, fs) -> PropertyRow(id, "not a property: ${fs.first().where}", true, fs.mapNotNull { it.method }.distinct()) }
    val verdict =
        when {
            all.isNotEmpty() -> ModuleVerdict.FAILED
            module.incomplete -> ModuleVerdict.INCOMPLETE
            else -> ModuleVerdict.PROVEN
        }
    return ClientReport(module, sidecar, verdict, rows, properties, null, seconds, runs, isolationSeconds)
}

/** The report as text (DESIGN-E 6.7). The `Properties:` rows are what the client mutant script reads. */
fun ClientReport.render(): String {
    val sb = StringBuilder()

    fun line(s: String = "") = sb.append(s).append('\n')

    fun at(p: Pos) = "${p.file.substringAfterLast('/')}:${p.line}:${p.col}"
    val timing =
        listOfNotNull(seconds?.let { "Silicon %.2f s".format(it) }, isolationSeconds?.let { "full failing set: $isolationRuns runs, %.1f s".format(it) })
    line("${module.id} (${sidecar.file.substringAfterLast('/')})    module: $verdict" + (if (timing.isEmpty()) "" else " (${timing.joinToString("; ")})"))
    error?.let { line("  ERROR: $it; this run proves nothing") }
    val width = rows.maxOfOrNull { "${it.kind} ${it.label}".length } ?: 0
    for (r in rows) {
        line("  ${"${r.kind} ${r.label}".padEnd(width)}  ${r.kotlin?.let(::at) ?: "-"}   ${r.verdict}" + (r.note?.let { "   ($it)" } ?: ""))
        r.findings.forEach { line("      ${it.id}: ${it.message}: ${it.where}") }
    }
    properties?.let { ps ->
        line("Properties (each property's full failing set, DESIGN-E 5.2):")
        val w = ps.maxOf { it.id.length }
        ps.forEach { p -> line("  ${p.id.padEnd(w)}  ${if (p.failed) "FAILED" else "PROVEN"}  ${p.what}" + (if (p.failed && p.where.isNotEmpty()) " (in ${p.where.joinToString()})" else "")) }
    }
    line("Tool facts: ${ClientIds.TOOL_FACTS.joinToString()} (from the obligation, its creates and consumes calls, the spawns and the loops)")
    line("Spec to review: ${specToReview(sidecar, module)}")
    line("Assumptions (not checked by Silicon):")
    val assumed = sidecar.assumes.map { it.id.text }.toSet()
    CLIENT_ASSUMPTIONS.forEach { (id, name) ->
        val (on, off) = ASSUMPTION_TEXT.getValue(id)
        line("  $id $name: ${if (id in assumed) "on (assume $id): $on" else "off: $off"}")
    }
    val longs = module.diagnostics.filter { it.kind == DiagnosticKind.ASSUMPTION }
    line("  Long unbounded: " + longs.joinToString { at(it.pos) }.ifEmpty { "none" })
    line("  Free values: " + module.entries.flatMap { e -> e.free.map { "${it.local.name} <- ${it.kotlin}" } }.joinToString().ifEmpty { "none" })
    line("  Trusted models: ${sidecar.uses.joinToString { it.model.text }}")
    line("  Trusted project functions: " + sidecar.trusted.joinToString { "${it.fn.text} (reviewed: ${it.reviewed})" }.ifEmpty { "none" })
    line("  Tasks: a task's precondition is what its spawn site gives it (DESIGN-E 3.4); the spawn's exhale checks it")
    val noEffect = module.ledger.filter { it.kind == DispositionKind.NO_EFFECT }.distinctBy { it.what }
    line("No effect (checked by the lowering): " + noEffect.joinToString { "${it.what} ${it.pos.line}:${it.pos.col}" }.ifEmpty { "none" })
    val notModeled = module.ledger.filter { it.kind == DispositionKind.NOT_MODELED }.distinctBy { it.what }
    line("Not modeled: " + notModeled.joinToString { "${it.what} ${it.pos.line}:${it.pos.col}" }.ifEmpty { "none" })
    val unsupported =
        rows.filter { it.verdict == Verdict.UNSUPPORTED }.map { "${it.kind} ${it.label}" } +
            module.diagnostics.filter { it.kind == DiagnosticKind.UNSUPPORTED }.map { "${it.message} ${at(it.pos)}" } +
            module.ledger.filter { it.kind == DispositionKind.UNSUPPORTED }.map { "${it.what} ${it.pos.line}:${it.pos.col}" }
    line("Unsupported: " + unsupported.distinct().joinToString("; ").ifEmpty { "none" })
    return sb.toString()
}
