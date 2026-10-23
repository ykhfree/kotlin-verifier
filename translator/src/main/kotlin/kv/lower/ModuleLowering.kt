package kv.lower

import kv.frontend.FoundAction
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
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.declarations.FirRegularClass

/**
 * FIR to KVIR for the module of a resolved sidecar (DESIGN-v0.md 2.1 steps 2-7, DESIGN-B.md 5.3): each map call on a
 * state map is an action of its sidecar operation or a stray action; every other node of the audited files goes to
 * the ledger. The audited files are the module file [file], and every file of [sources] if a state field is internal
 * (DESIGN-B.md 2.1 rule 6). [computes] are the map action calls of [file]; [resolver] holds the symbols [resolution]
 * was made from. Throws [SidecarException] if a free value's abstract type contradicts the code (DESIGN-v0.md 4.2
 * restriction 4) or a trusted function body breaks DESIGN-B.md 3.3.
 */
internal fun lowerModule(
    sidecar: Sidecar,
    resolver: SidecarResolver,
    resolution: Resolution,
    file: FirFile,
    sources: List<FirFile>,
    computes: List<FoundAction>,
    moduleClass: FirRegularClass,
): Module {
    val internal = resolver.states.values.any { it.resolvedStatus.visibility == Visibilities.Internal }
    val others = if (internal) sources.filter { it !== file }.sortedBy { it.sourceFile?.path ?: it.name } else emptyList()
    val ctx = ModuleContext(sidecar, resolver, FirIndex(file, others), moduleClass)
    checkTrustedBodies(ctx, resolver.operationFunctions).takeIf { it.isNotEmpty() }?.let { throw SidecarException(it) }
    val maps = computes.associateWith { ctx.stateRead(it.receiver) as? MapVar }
    // DESIGN-B.md 5.3 rule 2: a map call whose receiver is not a sidecar state map is no action; the ledger has it.
    val notActions = computes.filter { maps[it] == null }.associate { it.call to "${it.kind.method} on ${it.site.receiver ?: "a value"}, which is not a sidecar state map" }
    val actionSites = computes.filter { maps[it] != null }
    actionSites.forEach { ctx.collectAbstractions(it, maps.getValue(it)!!.key.name) }
    ctx.fixAnnotations().takeIf { it.isNotEmpty() }?.let { throw SidecarException(it) }

    val lowered = mutableListOf<Pair<FoundAction, Lowered>>()
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
            Operation(name, kotlin, found.inFunction?.let { ctx.posOf(it) } ?: found.site.pos, l.action, l.cells, l.result, l.body)
        }
    val claimed = resolver.computeOf.values.toSet()
    val stray =
        actionSites.filter { it !in claimed }.map { found ->
            val l = ActionLowering(ctx, found, null, "${found.site.inFunction ?: found.kind.method}#${found.site.pos.line}", maps.getValue(found)!!).run()
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
        kind = ctx.kind,
        freshFunctions = ctx.freshFunctions,
        file = file.sourceFile?.path ?: file.name,
    )
}
