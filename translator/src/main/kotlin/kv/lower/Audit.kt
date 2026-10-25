package kv.lower

import kv.frontend.FoundAction
import kv.frontend.MapCall
import kv.kvir.CounterVar
import kv.kvir.Disposition
import kv.kvir.DispositionKind
import kv.kvir.MapVar
import kv.kvir.Pos
import kv.model.Role
import kv.model.TrustedModels
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirAnonymousInitializer
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import java.util.IdentityHashMap

/**
 * The disposition shared by the nodes of one construct; [what] and [pos] name the construct. [stateAccess] marks an
 * unsupported module-state access outside every action (see [Audit.stateAccesses]).
 */
private data class Construct(
    val kind: DispositionKind,
    val what: String,
    val pos: Pos,
    val stateAccess: Boolean = false,
)

/** Where a node is: in compute's key argument, in an action, in a helper an action inlined, or elsewhere. */
private enum class Region { KEY_ARG, ACTION, HELPER, OUTSIDE }

/**
 * The module audit (DESIGN-v0.md 2.14, DESIGN-B.md 2.1 rule 6 and 3.3): every FIR node of the audited files that no
 * action or operation section translated gets one ledger entry. A node takes the disposition of the innermost
 * construct around it: a module-state access (racy read, or unsupported for a write or any other use), a value-class
 * constructor outside its fresh function (unsupported), the source of a free value, a cell, a declaration the module
 * structure comes from, the body of a trusted function, or else the enclosing member (not modeled). Nodes of actions
 * and inlined helpers are left to the lowering, so W14 finds a node the lowering skipped.
 */
internal class Audit(
    private val ctx: ModuleContext,
    lowered: List<Pair<FoundAction, Lowered>>,
    /** Map calls that are not actions (their receiver is not a state map), with the reason. */
    private val notActions: Map<FirFunctionCall, String>,
) {
    private val index = ctx.index
    private val keyArgs = IdentityHashMap<FirElement, Construct>()
    private val actionCalls: Set<FirFunctionCall> = lowered.map { it.first.call }.toSet()
    private val helpers = lowered.flatMap { it.second.helpers }.toSet()
    private val freeSources: Set<FirBasedSymbol<*>> = lowered.flatMap { it.second.freeSources }.toSet()
    private val cells: Set<FirBasedSymbol<*>> = lowered.flatMap { it.second.cellSymbols }.toSet()
    private val reach = mutableMapOf<FirNamedFunctionSymbol, String?>()

    /** Nodes an operation section translated (DESIGN-B.md 2.6), and the default-value lambdas of getOrPut actions, which belong to the section. */
    private val translated: Set<Int> = lowered.flatMap { it.second.action.translated }.toSet()
    private val sectionRoots: Set<FirElement> = lowered.mapNotNull { (found, _) -> found.lambda?.takeIf { found.kind == MapCall.GET_OR_PUT } }.toSet()

    /** The trusted functions whose bodies are in the audited files (DESIGN-B.md 3.3 rule 5), by symbol. */
    private val trustedFns = ctx.trusted.keys

    /** The classes `returns fresh` functions make: their constructors may run only in those functions (3.3 rule 4). */
    private val valueClasses = ctx.freshFunctions.values.toSet()
    private val freshFns = ctx.trusted.filterValues { it.fresh != null }.keys

    init {
        for ((found, l) in lowered) {
            val arg = found.call.argumentList.arguments.first()
            keyArgs[arg] = Construct(DispositionKind.FREE_SOURCE, "key of ${l.action.id}: ${sourceText(arg)}", ctx.posOf(arg))
        }
    }

    /**
     * The ledger nodes of the unsupported module-state accesses ([access]): writes, mutating operations, escapes and
     * calls of state-changing helpers outside every action. [run] fills it. The input check reads it ([checkInputs]).
     */
    val stateAccesses = mutableSetOf<Int>()

    fun run(): List<Disposition> =
        index.nodes.mapIndexedNotNull { id, node ->
            // A section node is translated (DESIGN-B.md 2.6); a key-argument node stays in the ledger, so that W14
            // still catches a lowering that marks one.
            val region = region(node)
            if (region == Region.ACTION || region == Region.HELPER || region == Region.OUTSIDE && id in translated) return@mapIndexedNotNull null
            val c = construct(node)
            if (c.stateAccess) stateAccesses += id
            Disposition(id, c.kind, c.what, c.pos)
        }

    /** The innermost region marker on [node]'s ancestor chain, [node] included. */
    private fun region(node: FirElement): Region {
        var x: FirElement? = node
        while (x != null) {
            when {
                x in keyArgs -> return Region.KEY_ARG
                x in sectionRoots -> return Region.OUTSIDE
                x in actionCalls -> return Region.ACTION
                x in helpers -> return Region.HELPER
            }
            x = index.parent(x)
        }
        return Region.OUTSIDE
    }

    private fun construct(node: FirElement): Construct {
        var x: FirElement? = node
        while (x != null) {
            (keyArgs[x] ?: access(x) ?: valueConstructor(x) ?: source(x) ?: cell(x) ?: structure(x) ?: trustedBody(x) ?: member(x))?.let { return it }
            x = index.parent(x)
        }
        return Construct(DispositionKind.NOT_MODELED, "file ${index.fileOf(node).name}", ctx.posOf(node))
    }

    /** DESIGN-B.md 3.3 rule 4: a constructor of a class a fresh function makes, outside such a function's body. */
    private fun valueConstructor(x: FirElement): Construct? {
        if (x !is FirFunctionCall) return null
        val cls = ctx.constructedClass(ctx.calleeOf(x))?.takeIf { it.name in valueClasses } ?: return null
        val inFresh = generateSequence(index.parent(x)) { index.parent(it) }.any { it is FirNamedFunction && it.symbol in freshFns }
        if (inFresh) return null
        return unsupported("a ${cls.name} constructor outside the body of a `returns fresh ${cls.name}` trusted function: P_once cannot count it (DESIGN-B.md 3.3 rule 4)", x)
    }

    /** DESIGN-B.md 3.3 rule 5: the body of a trusted function, which a person reviewed and the tool does not translate. */
    private fun trustedBody(x: FirElement): Construct? {
        val f = x as? FirNamedFunction ?: return null
        if (f.symbol !in trustedFns) return null
        return Construct(DispositionKind.TRUSTED, "fun ${f.name} (trusted fun ${ctx.trusted.getValue(f.symbol).shortName})", ctx.posOf(f))
    }

    private fun unsupported(
        what: String,
        x: FirElement,
    ) = Construct(DispositionKind.UNSUPPORTED, "$what: ${sourceText(x)}", ctx.posOf(x))

    /** An unsupported module-state access outside every action (see [access]). */
    private fun stateAccess(
        what: String,
        x: FirElement,
    ) = unsupported(what, x).copy(stateAccess = true)

    private fun racy(x: FirElement) = Construct(DispositionKind.RACY_READ, sourceText(x), ctx.posOf(x))

    private fun receiverOf(x: FirQualifiedAccessExpression): FirExpression? = unwrap(x.dispatchReceiver ?: x.extensionReceiver)

    /**
     * A module-state access outside every action (DESIGN-v0.md 2.12, 2.14): a racy read for a read operation of the
     * map, the counter or a field of a module object; unsupported for a write, a mutating operation, a call of a
     * module helper that changes state, a compute that is not an action, and any other use of a state property.
     */
    private fun access(x: FirElement): Construct? {
        if (x is FirVariableAssignment) {
            val sym = (x.lValue as? FirQualifiedAccessExpression)?.let(ctx::calleeOf)
            if (ctx.fieldOf(sym) != null || ctx.stateOf(sym) != null) return stateAccess("write of module state outside an action", x)
            return null
        }
        if (x !is FirQualifiedAccessExpression) return null
        // DESIGN-B.md 5.3 rule 2: a map call on another map is not modeled; its lambda is audited like the rest.
        (x as? FirFunctionCall)?.let { notActions[it] }?.let { return Construct(DispositionKind.NOT_MODELED, "$it: ${sourceText(x)}", ctx.posOf(x)) }
        // DESIGN-B.md 2.1: the state of another instance of an instance module is another module's state.
        if (x is FirPropertyAccessExpression && ctx.foreignState(x)) return stateAccess("state of another ${ctx.moduleClass.name} instance", x)
        val sym = ctx.calleeOf(x)
        val parent = index.parent(x)
        // The target of an assignment: the assignment decides.
        if (parent is FirVariableAssignment && parent.lValue === x) return null
        val usedAsReceiver = parent is FirQualifiedAccessExpression && receiverOf(parent) === x
        if (x is FirPropertyAccessExpression && (ctx.stateOf(sym) != null || ctx.fieldOf(sym)?.model != null)) {
            // The operation on this state decides; any other use lets the state escape.
            return if (usedAsReceiver) null else stateAccess("module state used outside an action", x)
        }
        val field = ctx.fieldOf(sym)
        if (x is FirPropertyAccessExpression && field != null) return racy(x)
        val receiver = receiverOf(x)
        val state = ctx.stateRead(receiver)
        val entries = ctx.entriesOf(x)
        if (state != null) {
            val read =
                when (state) {
                    is MapVar -> entries.any { it.role == Role.RACY_READ || it.role == Role.NONDET }
                    is CounterVar -> entries.any { it.model == TrustedModels.COUNTER && it.name == "get" }
                }
            return if (read) racy(x) else stateAccess("${state.kotlin} operation outside an action", x)
        }
        val flattened = (receiver as? FirPropertyAccessExpression)?.let { ctx.fieldOf(ctx.calleeOf(it)) }?.takeIf { it.model != null }
        if (flattened != null) {
            return if (entries.any { it.model == flattened.model && it.name == "get" }) racy(x) else stateAccess("${flattened.kotlin} operation outside an action", x)
        }
        val helper = (sym as? FirNamedFunctionSymbol)?.let { index.functions[it] }
        if (x is FirFunctionCall && helper != null) {
            reaches(helper)?.let { return stateAccess("call of ${helper.name}, which changes module state ($it), outside an action", x) }
        }
        return null
    }

    /** Why [f] changes module state outside its actions (directly or through a module helper); null if it does not. */
    private fun reaches(f: FirNamedFunction): String? {
        if (f.symbol in reach) return reach[f.symbol]
        reach[f.symbol] = null
        val found =
            index.subtree(f).asSequence().map { index.nodes[it] }.filter { region(it) != Region.ACTION }.firstNotNullOfOrNull { node ->
                access(node)?.takeIf { it.kind == DispositionKind.UNSUPPORTED }?.let { "${it.what} at ${it.pos.line}:${it.pos.col}" }
            }
        reach[f.symbol] = found
        return found
    }

    /** The declaration of a free value's source (DESIGN-v0.md 2.14, free-source). */
    private fun source(x: FirElement): Construct? {
        val decl = x as? FirDeclaration ?: return null
        if (decl.symbol !in freeSources) return null
        return Construct(DispositionKind.FREE_SOURCE, describe(decl), ctx.posOf(decl))
    }

    /** A cell, a read of it after the action, and the operation result that reads it (DESIGN-v0.md 2.14, cell). */
    private fun cell(x: FirElement): Construct? {
        val isCell =
            when (x) {
                is FirProperty -> x.symbol in cells
                is FirFunctionCall -> isCellGet(x)
                is FirReturnExpression -> (unwrap(x.result) as? FirFunctionCall)?.let(::isCellGet) == true
                else -> false
            }
        if (!isCell) return null
        return Construct(DispositionKind.CELL, if (x is FirDeclaration) describe(x) else sourceText(x), ctx.posOf(x))
    }

    private fun isCellGet(c: FirFunctionCall): Boolean {
        val read = unwrap(c.dispatchReceiver) as? FirPropertyAccessExpression ?: return false
        return cells.contains<FirBasedSymbol<*>?>(ctx.calleeOf(read)) && ctx.entriesOf(c).any { it.model == TrustedModels.CELL && it.name == "get" }
    }

    /** A declaration the module structure is read from: the module object, a state property, a sidecar class, its fields. */
    private fun structure(x: FirElement): Construct? {
        val what =
            when {
                x === ctx.moduleClass -> "module object ${ctx.moduleClass.name}"
                x is FirProperty && ctx.stateOf(x.symbol) != null -> "state ${ctx.stateOf(x.symbol)!!.name}: ${describe(x)}"
                x is FirRegularClass && ctx.classOf(x.symbol) != null -> "class ${ctx.classOf(x.symbol)!!.name}"
                x is FirConstructor && x.isPrimary && (index.parent(x) as? FirRegularClass)?.let { ctx.classOf(it.symbol) } != null -> "constructor of ${(index.parent(x) as FirRegularClass).name}"
                x is FirProperty && ctx.fieldOf(x.symbol) != null -> "field ${ctx.fieldOf(x.symbol)!!.name}: ${describe(x)}"
                else -> return null
            }
        return Construct(DispositionKind.STRUCTURE, what, ctx.posOf(x))
    }

    /** A member of a class or of the file: the construct of the nodes no inner construct claims (not modeled). */
    private fun member(x: FirElement): Construct? {
        val parent = index.parent(x)
        if (x !is FirDeclaration || parent !is FirRegularClass && parent !is FirFile) return null
        return Construct(DispositionKind.NOT_MODELED, describe(x), ctx.posOf(x))
    }

    private fun describe(d: FirDeclaration): String =
        when (d) {
            is FirNamedFunction -> "fun ${d.name}"
            is FirProperty -> if (d.isLocal) "${if (d.isVal) "val" else "var"} ${d.name}${d.initializer?.let { " = ${sourceText(it)}" } ?: ""}" else "property ${d.name}"
            is FirValueParameter -> "param ${d.name}"
            is FirRegularClass -> "class ${d.name}"
            is FirConstructor -> "constructor of ${(index.parent(d) as? FirRegularClass)?.name}"
            is FirAnonymousInitializer -> "init"
            else -> d::class.simpleName.orEmpty()
        }
}
