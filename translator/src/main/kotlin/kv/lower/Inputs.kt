package kv.lower

import kv.kvir.Disposition
import kv.kvir.Return
import kv.kvir.RunAction
import kv.kvir.Status
import kv.kvir.Unsupported
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol

/**
 * DESIGN-v0.md 5.8 for the inputs of an action (AI-CODE.md 13, fix 1). The rule for a module also holds for one
 * action: an unmodeled part may break what the proof relies on. So if the value of an input ([Lowered.inputs]) flows
 * through one of [accesses], the ledger's unsupported module-state accesses ([Audit.stateAccesses]), the action is
 * unsupported and is not emitted. Each such input gets an Unsupported statement that names the constructs and their
 * positions; a construct is named once per action. Returns [l] unchanged if no input flows through one.
 *
 * A value-class constructor outside its fresh function (DESIGN-B.md 3.3 rule 4) is unsupported too, but it changes
 * no module state: it only makes an object that P_once does not count, which the action reads as an arbitrary
 * object of an opaque class (DESIGN-B.md 7.2, M5). So it makes the module INCOMPLETE, not the action unsupported.
 */
internal fun checkInputs(
    ctx: ModuleContext,
    l: Lowered,
    accesses: List<Disposition>,
): Lowered {
    val unsupported = accesses.associateBy { it.node }
    if (unsupported.isEmpty()) return l
    val origins = Origins(ctx)
    val named = mutableSetOf<Pair<String, kv.kvir.Pos>>()
    val problems =
        l.inputs.mapNotNull { (name, from) ->
            val constructs = origins.of(from).mapNotNull { unsupported[it] }.filter { named.add(it.what to it.pos) }
            if (constructs.isEmpty()) return@mapNotNull null
            // DESIGN-D.md 6.3: a counter increment that is not a reserve step says which rule it breaks.
            val list = constructs.joinToString("; ") { "${it.what} at ${where(it.pos)}" + (ctx.notReserve[it.pos]?.let { r -> ", $r" } ?: "") }
            Unsupported("input $name", "its value comes from code outside the action that the model does not translate ($list); an unmodeled part may break what the proof relies on (DESIGN-v0.md 5.8)", ctx.posOf(from))
        }
    if (problems.isEmpty()) return l
    val a = l.action
    val before = a.status as? Status.Unsupported
    // An unsupported action's body is its Unsupported statements (W12).
    val body = if (before != null) a.body + problems else problems
    val diagnostics = before?.diagnostics.orEmpty() + problems.map { unsupportedDiagnostic(it, a.id) }
    val action = a.copy(body = body, status = Status.Unsupported(diagnostics), value = null, out = null)
    // As for any unsupported action, the section keeps the plain shape and the result is dropped (W12).
    return Lowered(action, l.cells, null, listOf(RunAction(a.pos), Return(null, a.pos)), l.freeSources, l.cellSymbols, l.helpers, l.inputs, l.reserve)
}

/**
 * The origin of a value: the FIR nodes its computation may run. A declaration's origin is the declaration (with its
 * initializer) and every assignment to it. A read of a local, a parameter or a property adds the origin of that
 * declaration; a call of a module-file helper adds the helper's body. A trusted function adds nothing: its contract
 * gives its value, and a person reviewed its body (DESIGN-B.md 3.3 rule 5).
 */
private class Origins(
    private val ctx: ModuleContext,
) {
    private val index = ctx.index

    private val assignments: Map<FirBasedSymbol<*>?, List<FirVariableAssignment>> =
        index.nodes.filterIsInstance<FirVariableAssignment>().groupBy { (it.lValue as? FirQualifiedAccessExpression)?.let(ctx::calleeOf) }

    /** The node numbers of the origin of the value that [from] gives. */
    fun of(from: FirElement): Set<Int> {
        val seen = linkedSetOf<Int>()
        val followed = mutableSetOf<FirBasedSymbol<*>>()
        val queue = ArrayDeque(listOf(from))
        while (queue.isNotEmpty()) {
            for (id in index.subtree(queue.removeFirst())) {
                if (!seen.add(id)) continue
                val access = index.nodes[id] as? FirQualifiedAccessExpression ?: continue
                val sym = ctx.calleeOf(access) ?: continue
                if (!followed.add(sym)) continue
                val helper = (sym as? FirNamedFunctionSymbol)?.let { index.functions[it] }
                if (helper != null) {
                    if (helper.symbol !in ctx.trusted) queue += helper
                    continue
                }
                val decl = ctx.declarationOf(sym)?.takeIf { it is FirProperty || it is FirValueParameter } ?: continue
                queue += decl
                queue += assignments[sym].orEmpty()
            }
        }
        return seen
    }
}
