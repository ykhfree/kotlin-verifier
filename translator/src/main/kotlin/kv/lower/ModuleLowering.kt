package kv.lower

import kv.frontend.FoundCompute
import kv.frontend.Resolution
import kv.frontend.SidecarResolver
import kv.kvir.Diagnostic
import kv.kvir.DiagnosticKind
import kv.kvir.MapVar
import kv.kvir.Module
import kv.kvir.Operation
import kv.sidecar.Selector
import kv.sidecar.Sidecar
import kv.sidecar.SidecarException
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirRegularClass

/**
 * FIR to KVIR for the module of a resolved sidecar (DESIGN-v0.md 2.1 steps 2-7): each compute call on a state map
 * is an action of its sidecar operation or a stray action; every other node of the module file goes to the ledger.
 * [computes] are the compute calls of [file]; [resolver] holds the symbols [resolution] was made from.
 * Throws [SidecarException] if a free value's abstract type contradicts the code (DESIGN-v0.md 4.2 restriction 4).
 */
internal fun lowerModule(
    sidecar: Sidecar,
    resolver: SidecarResolver,
    resolution: Resolution,
    file: FirFile,
    computes: List<FoundCompute>,
    moduleClass: FirRegularClass,
): Module {
    val ctx = ModuleContext(sidecar, resolver, FirIndex(file), moduleClass)
    val maps = computes.associateWith { ctx.stateRead(it.call.dispatchReceiver) as? MapVar }
    // 2.2 rule 2: a compute call whose receiver is not a sidecar state map is no action; the ledger reports it.
    val notActions = computes.filter { maps[it] == null }.associate { it.call to "compute on ${it.site.receiver ?: "a value"}, which is not a sidecar state map" }
    val actionSites = computes.filter { maps[it] != null }
    actionSites.forEach { ctx.collectAbstractions(it, maps.getValue(it)!!.key.name) }
    ctx.fixAnnotations().takeIf { it.isNotEmpty() }?.let { throw SidecarException(it) }

    val lowered = mutableListOf<Pair<FoundCompute, Lowered>>()
    val operations =
        sidecar.operations.mapNotNull { decl ->
            val found = resolver.computeOf[decl.name.text] ?: return@mapNotNull null
            val name = decl.name.text
            val map = maps[found]
            if (map == null) {
                ctx.diagnostics += Diagnostic("U${found.site.pos.line}_${found.site.pos.col}", DiagnosticKind.UNSUPPORTED, "operation $name: ${notActions.getValue(found.call)}", found.site.pos)
                return@mapNotNull null
            }
            val l = ActionLowering(ctx, found, decl, "$name#${found.site.pos.line}", map).run()
            lowered += found to l
            val kotlin =
                when (val sel = decl.selector) {
                    is Selector.Fun -> "fun ${sel.target.text}"
                    is Selector.ComputeIn -> "compute in ${sel.target.text}"
                }
            Operation(name, kotlin, found.inFunction?.let { ctx.posOf(it) } ?: found.site.pos, l.action, l.cells, l.result)
        }
    val claimed = resolver.computeOf.values.toSet()
    val stray =
        actionSites.filter { it !in claimed }.map { found ->
            val l = ActionLowering(ctx, found, null, "${found.site.inFunction ?: "compute"}#${found.site.pos.line}", maps.getValue(found)!!).run()
            lowered += found to l
            l.action
        }
    for (u in resolution.unsupported) {
        val pos = u.kotlinPos ?: ctx.posOf(moduleClass)
        ctx.diagnostics += Diagnostic("U${pos.line}_${pos.col}", DiagnosticKind.UNSUPPORTED, "operation ${u.operation} (${u.at}): ${u.reason}", pos)
    }
    val ledger = Audit(ctx, lowered, notActions).run()
    return Module(
        id = sidecar.modules.single().fqn.text,
        types = ctx.types,
        state = ctx.state,
        classes = ctx.classes,
        ghost = ctx.ghost,
        operations = operations,
        strayActions = stray,
        firNodeCount = ctx.index.count,
        ledger = ledger,
        diagnostics = ctx.diagnostics,
    )
}
