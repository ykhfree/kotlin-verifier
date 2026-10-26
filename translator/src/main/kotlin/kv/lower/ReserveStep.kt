package kv.lower

import kv.frontend.FoundAction
import kv.frontend.MapCall
import kv.kvir.CounterVar
import kv.kvir.Diagnostic
import kv.kvir.DiagnosticKind
import kv.kvir.KType
import kv.kvir.ModuleKind
import kv.model.Role
import kv.model.TrustedModels
import kv.sidecar.OperationDecl
import kv.sidecar.Selector
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirArgumentList
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirDoWhileLoop
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirStringConcatenationCall
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.FirWhileLoop
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol

/** The start of the reason why a counter increment is not a reserve step (DESIGN-D.md 6.3), in reports. */
const val NOT_RESERVE = "not a reserve step"

/**
 * A reserve step that DESIGN-D.md 6.3 rules 1-5 accept: [decl] is `val x = E` in the operation body, before the
 * action call; [increment] is the `C.incrementAndGet()` call of E, in the body of [helper] for E = `helper()`;
 * [template] is the token template of type [templateType] around the increment, null for a raw Long.
 */
internal class ReserveSite(
    val decl: FirProperty,
    val increment: FirFunctionCall,
    val counter: CounterVar,
    val template: FirStringConcatenationCall?,
    val templateType: String?,
    val helper: FirNamedFunction?,
) {
    /** The type of the reserved val: the template token, or a Long (an Int). */
    val type: KType get() = templateType?.let { KType.Abs(it, false) } ?: KType.IntT
}

/** The value E of rule 3 without the helper form: the increment, or a token template whose Long part is the increment. */
private class ReserveValue(
    val increment: FirFunctionCall,
    val counter: CounterVar,
    val template: FirStringConcatenationCall?,
    val templateType: String?,
)

/**
 * DESIGN-D.md 6.3: the reserve steps of the operations [ops] (each with its action call and the map it works on), by
 * operation name. Every counter increment outside an action in an operation function is a candidate: a direct
 * `C.incrementAndGet()` on a sidecar counter, or a call of a module helper whose body returns one or a token template
 * around one. A candidate that breaks a rule stays what it was (an unsupported module-state access); the reason goes
 * to an INFO diagnostic and to [ModuleContext.notReserve], by the candidate's position. Instance modules have no reserve
 * steps (DESIGN-D.md JD8) and get no reasons.
 */
internal fun findReserves(
    ctx: ModuleContext,
    ops: List<Pair<OperationDecl, FoundAction>>,
): Map<String, ReserveSite> {
    if (ctx.kind != ModuleKind.OBJECT) return emptyMap()
    val finder = ReserveFinder(ctx)
    val accepted = linkedMapOf<String, ReserveSite>()
    var counter: CounterVar? = null
    for ((decl, found) in ops) {
        val sites = finder.candidates(decl, found)
        if (sites.size > 1) {
            val at = sites.joinToString { (c, _) -> where(ctx.posOf(c)) }
            sites.forEach { (c, _) -> finder.reject(c, 5, "operation ${decl.name.text} has ${sites.size} reserve steps ($at); v1 allows one") }
            continue
        }
        val (c, site) = sites.singleOrNull() ?: continue
        if (counter != null && site.counter != counter) {
            finder.reject(c, 5, "the step increments ${site.counter.kotlin}, but an earlier reserve step of the module increments ${counter.kotlin}; v1 allows one counter")
            continue
        }
        counter = site.counter
        accepted[decl.name.text] = site
        // A template fixes the abstract type of the reserved val (DESIGN-v0.md 2.13 rule 2), before any action is lowered.
        site.templateType?.let { ctx.abstraction.fix(site.decl.symbol, it, ctx.posOf(site.template!!)) }
    }
    return accepted
}

private class ReserveFinder(
    private val ctx: ModuleContext,
) {
    private val index = ctx.index

    /** The FIR parent of [e], past an argument list. */
    private fun parentOf(e: FirElement): FirElement? = index.parent(e)?.let { if (it is FirArgumentList) index.parent(it) else it }

    /** Records why the candidate [c] is not a reserve step: rule [rule] of DESIGN-D.md 6.3. */
    fun reject(
        c: FirFunctionCall,
        rule: Int,
        why: String,
    ) {
        val pos = ctx.posOf(c)
        val reason = "$NOT_RESERVE (DESIGN-D 6.3 rule $rule): $why"
        if (ctx.notReserve.putIfAbsent(pos, reason) == null) {
            ctx.diagnostics += Diagnostic("R${pos.line}_${pos.col}", DiagnosticKind.INFO, "$reason: ${sourceText(c)}", pos)
        }
    }

    /** The counter that [c] increments if it is `C.incrementAndGet()` on a sidecar counter; else null. */
    private fun counterIncrement(c: FirFunctionCall): CounterVar? {
        if (ctx.entriesOf(c).none { it.model == TrustedModels.COUNTER && it.name == "incrementAndGet" }) return null
        return ctx.stateRead(c.dispatchReceiver) as? CounterVar
    }

    /** E of rule 3 for [e]: the increment itself, or a token template whose Long part is the increment. */
    private fun value(e: FirExpression?): ReserveValue? =
        when (e) {
            is FirFunctionCall -> counterIncrement(e)?.let { ReserveValue(e, it, null, null) }
            is FirStringConcatenationCall -> {
                val (t, long) = ctx.templateOf(e) ?: return null
                val c = unwrap(long) as? FirFunctionCall
                c?.let(::counterIncrement)?.let { ReserveValue(c, it, e, t.type) }
            }
            else -> null
        }

    /** The value of helper [fn] if its body is `return E` for an E of [value] and it has no parameters; else null. */
    private fun helperValue(fn: FirNamedFunction): ReserveValue? {
        if (fn.valueParameters.isNotEmpty() || fn.visibility == Visibilities.Local || fn.symbol in ctx.trusted) return null
        val r = fn.body?.statements?.singleOrNull() as? FirReturnExpression ?: return null
        if (r.target.labeledElement !== fn) return null
        return value(unwrap(r.result))
    }

    /**
     * The candidates of operation [op] that pass rules 1-4, each with its site; the others are rejected here. The
     * candidates are the calls in the operation function outside the action call.
     */
    fun candidates(
        op: OperationDecl,
        found: FoundAction,
    ): List<Pair<FirFunctionCall, ReserveSite>> {
        val fn = found.inFunction ?: return emptyList()
        val action = index.subtree(found.call).toSet()
        val out = mutableListOf<Pair<FirFunctionCall, ReserveSite>>()
        for (id in index.subtree(fn)) {
            if (id in action) continue
            val c = index.nodes[id] as? FirFunctionCall ?: continue
            val direct = counterIncrement(c) != null
            val helper = if (direct) null else (ctx.calleeOf(c) as? FirNamedFunctionSymbol)?.let { index.functions[it] }?.takeIf { helperValue(it) != null }
            if (!direct && helper == null) continue
            check(op, found, fn, c, helper)?.let { out += c to it }
        }
        return out
    }

    /**
     * Rules 1-4 for the candidate [c]: a direct increment, or a call of [helper]; null after a rejection. E (rule 3) is
     * the helper call, or the increment or the token template whose Long part it is.
     */
    private fun check(
        op: OperationDecl,
        found: FoundAction,
        fn: FirNamedFunction,
        c: FirFunctionCall,
        helper: FirNamedFunction?,
    ): ReserveSite? {
        val e: FirExpression = if (helper == null) (parentOf(c) as? FirStringConcatenationCall)?.takeIf { unwrap(ctx.templateOf(it)?.second) === c } ?: c else c
        val value = if (helper == null) value(e)!! else helperValue(helper)!!
        if (op.selector !is Selector.Fun) return rejected(c, 1, "operation ${op.name.text} is selected by `compute in`; only a `fun` operation has a reserve step")
        if (found.kind != MapCall.COMPUTE) return rejected(c, 1, "the action of ${op.name.text} is a ${found.kind.method}; v1 has reserve steps only before a compute")
        val decl = (index.parent(e) as? FirProperty)?.takeIf { it.isLocal && unwrap(it.initializer) === e }
        if (decl == null) {
            val owner = generateSequence(parentOf(e)) { parentOf(it) }.takeWhile { it !is FirBlock && it !is FirVariableAssignment }.firstOrNull { it is FirProperty }
            return if (owner != null) {
                rejected(c, 3, "the value of val ${(owner as FirProperty).name} is neither the increment, a token template around it, nor a call of a helper that returns one of them")
            } else {
                rejected(c, 2, "the increment is not the value of a local val")
            }
        }
        if (!decl.isVal) return rejected(c, 2, "${decl.name} is a var")
        val scope = scopeOf(found, fn) ?: return rejected(c, 2, "the action call of ${op.name.text} is not directly in the operation function or in its Mono.fromCallable lambda")
        val block = scope.body ?: return rejected(c, 2, "the operation function has no body")
        if (index.parent(decl) !== block) return rejected(c, 2, "val ${decl.name} is inside ${inside(decl, block)}")
        val at = block.statements.indexOf(decl)
        val actionAt = statementOf(found.call, block)
        if (actionAt < 0 || at > actionAt) return rejected(c, 2, "val ${decl.name} is not before the action call")
        readOutside(decl, found, scope, block)?.let { return rejected(c, 4, "${decl.name} is read at ${where(ctx.posOf(it))}, outside the action, the operation result and log calls") }
        return ReserveSite(decl, value.increment, value.counter, value.template, value.templateType, helper)
    }

    /** [reject] as the result of [check]. */
    private fun rejected(
        c: FirFunctionCall,
        rule: Int,
        why: String,
    ): ReserveSite? {
        reject(c, rule, why)
        return null
    }

    /**
     * The function or lambda whose body holds the action call directly (DESIGN-v0.md 8.3): the operation function, or
     * the one Mono.fromCallable lambda around the call. Null for another shape.
     */
    private fun scopeOf(
        found: FoundAction,
        fn: FirNamedFunction,
    ): FirFunction? {
        val lambdas = found.scopes.drop(found.scopes.indexOf(fn) + 1)
        if (lambdas.isEmpty()) return fn
        val lambda = lambdas.singleOrNull() as? FirAnonymousFunction ?: return null
        var x = index.parent(lambda)
        while (x != null && x !is FirFunctionCall) x = index.parent(x)
        val holder = x as? FirFunctionCall ?: return null
        return lambda.takeIf { ctx.entriesOf(holder).any { it.role == Role.OPERATION_BODY } }
    }

    /** The index of the statement of [block] that holds [e]; -1 if [block] does not hold it. */
    private fun statementOf(
        e: FirElement,
        block: FirBlock,
    ): Int {
        var x: FirElement = e
        while (index.parent(x) !== block) x = index.parent(x) ?: return -1
        return block.statements.indexOf(x)
    }

    /** What holds [decl] inside [block]: the innermost if, when, loop, try or lambda around it, with its position. */
    private fun inside(
        decl: FirProperty,
        block: FirBlock,
    ): String {
        var x = index.parent(decl)
        while (x != null && x !== block) {
            val what =
                when (x) {
                    is FirWhenExpression -> "an if or when"
                    is FirWhileLoop, is FirDoWhileLoop -> "a loop"
                    is FirTryExpression -> "a try"
                    is FirAnonymousFunction -> "a lambda"
                    is FirNamedFunction -> "a local function"
                    else -> null
                }
            if (what != null) return "$what at ${where(ctx.posOf(x))}"
            x = index.parent(x)
        }
        return "a block that is not the operation body"
    }

    /**
     * The first read of [decl] outside the action lambda, the operation result (the last statement of [scope], a
     * return) and the arguments of a no-effect trusted call (slf4j); null if there is none (rule 4).
     */
    private fun readOutside(
        decl: FirProperty,
        found: FoundAction,
        scope: FirFunction,
        block: FirBlock,
    ): FirPropertyAccessExpression? {
        val lambda = found.lambda?.let { index.subtree(it).toSet() }.orEmpty()
        val result = (block.statements.lastOrNull() as? FirReturnExpression)?.takeIf { it.target.labeledElement === scope }?.let { index.subtree(it.result).toSet() }.orEmpty()
        val reads = index.nodes.filterIsInstance<FirPropertyAccessExpression>().filter { ctx.calleeOf(it) == decl.symbol }
        return reads.firstOrNull { r ->
            val id = index.id(r)
            id !in lambda && id !in result && !logArgument(r)
        }
    }

    /** True if [r] is in an argument of a call of a trusted model with no effect (slf4j), not in its receiver. */
    private fun logArgument(r: FirElement): Boolean {
        var x: FirElement = r
        while (true) {
            val p = parentOf(x) ?: return false
            if (p is FirFunctionCall && p.arguments.any { it === x } && ctx.entriesOf(p).any { it.role == Role.NO_EFFECT }) return true
            if (p is FirBlock || p is FirFunction) return false
            x = p
        }
    }
}
