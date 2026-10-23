package kv.lower

import kv.frontend.FoundCompute
import kv.kvir.Action
import kv.kvir.ActionKey
import kv.kvir.And
import kv.kvir.Arith
import kv.kvir.ArithOp
import kv.kvir.Assign
import kv.kvir.BoolLit
import kv.kvir.Captured
import kv.kvir.Cell
import kv.kvir.Cmp
import kv.kvir.CmpOp
import kv.kvir.Diagnostic
import kv.kvir.DiagnosticKind
import kv.kvir.Exit
import kv.kvir.ExitResult
import kv.kvir.Expr
import kv.kvir.FieldRead
import kv.kvir.FieldTarget
import kv.kvir.FieldWrite
import kv.kvir.FreeValue
import kv.kvir.If
import kv.kvir.InstanceVal
import kv.kvir.KType
import kv.kvir.Let
import kv.kvir.Local
import kv.kvir.MapVar
import kv.kvir.ModelOp
import kv.kvir.New
import kv.kvir.Nondet
import kv.kvir.NondetCall
import kv.kvir.Not
import kv.kvir.ObjClass
import kv.kvir.OpResult
import kv.kvir.Or
import kv.kvir.Pos
import kv.kvir.RacyRead
import kv.kvir.StateTarget
import kv.kvir.Status
import kv.kvir.Stmt
import kv.kvir.TokenOf
import kv.kvir.Unsupported
import kv.kvir.Var
import kv.model.Entry
import kv.model.Returns
import kv.model.Role
import kv.model.TrustedModels
import kv.sidecar.FreeSource
import kv.sidecar.OperationDecl
import kv.sidecar.Selector
import kv.sidecar.TrustedDecl
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.contracts.description.LogicOperationKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.utils.isInterface
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirBooleanOperatorExpression
import org.jetbrains.kotlin.fir.expressions.FirCheckNotNullCall
import org.jetbrains.kotlin.fir.expressions.FirComparisonExpression
import org.jetbrains.kotlin.fir.expressions.FirDoWhileLoop
import org.jetbrains.kotlin.fir.expressions.impl.FirElseIfTrueCondition
import org.jetbrains.kotlin.fir.expressions.FirElvisExpression
import org.jetbrains.kotlin.fir.expressions.FirEqualityOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirOperation
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSafeCallExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.expressions.FirStringConcatenationCall
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirThrowExpression
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
import org.jetbrains.kotlin.fir.expressions.FirTypeOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.FirWhileLoop
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.types.ConstantValueKind
import java.util.IdentityHashMap

/** One lowered action, with the operation parts that come from the same function. */
internal class Lowered(
    val action: Action,
    val cells: List<Cell>,
    val result: OpResult?,
    /** Declarations whose values the action reads as free values (its key variable included). */
    val freeSources: Set<FirBasedSymbol<*>>,
    /** Cells of the operation. */
    val cellSymbols: Set<FirBasedSymbol<*>>,
    /** Module-file functions the action inlined. */
    val helpers: Set<FirNamedFunction>,
)

/** How the statements of a block end: no path exits, every path exits, one branch of its last `if` exits, or other. */
private enum class Exits { NONE, ALL, SPLIT, MIXED }

private val OPERATOR_SYMBOLS = mapOf("times" to "*", "div" to "/", "rem" to "%", "inc" to "++", "dec" to "--", "unaryMinus" to "unary -", "shl" to "shl", "shr" to "shr", "ushr" to "ushr", "and" to "and", "or" to "or", "xor" to "xor")

/**
 * Lowers one `ConcurrentHashMap.compute` call to a KVIR action (DESIGN-v0.md 2.2-2.11). [op] is the sidecar
 * operation that selects it; null for a stray action. Every construct v0 does not model becomes an Unsupported
 * statement with its Kotlin position, and lowering goes on to the end of the action to collect all of them (1.5).
 * An unsupported action is never emitted (W12), so its body keeps only the Unsupported statements, in the order
 * lowering found them.
 */
internal class ActionLowering(
    private val ctx: ModuleContext,
    private val found: FoundCompute,
    private val op: OperationDecl?,
    private val id: String,
    private val map: MapVar,
) {
    private val call = found.call
    private val lambda = found.lambda
    private val problems = mutableListOf<Unsupported>()
    private val translated = mutableSetOf<Int>()
    private val names = mutableSetOf<String>()
    private val nameOwners = mutableMapOf<String, FirBasedSymbol<*>>()
    private val locals = IdentityHashMap<FirBasedSymbol<*>, Local>()
    private val free = LinkedHashMap<Any, FreeValue>()
    private val freeSources = mutableSetOf<FirBasedSymbol<*>>()
    private val cells = LinkedHashMap<FirBasedSymbol<*>, Cell>()
    private val helpers = mutableSetOf<FirNamedFunction>()
    private val inlining = ArrayDeque<FirNamedFunctionSymbol>()
    private val reportedSlots = mutableSetOf<Any>()
    private var out = mutableListOf<Stmt>()
    private val at = ctx.posOf(call)

    /** Sidecar names of this operation's free values, by the Kotlin declaration or call they name. */
    private val named: List<Pair<kv.sidecar.FreeItem, Any>> = op?.let { ctx.resolver.freeOf[it.name.text] }.orEmpty()

    // ---- bookkeeping ----

    private fun mark(e: FirElement?) {
        e?.let { ctx.index.id(it) }?.let { translated += it }
    }

    private fun markAll(e: FirElement?) {
        e?.let { translated += ctx.index.subtree(it) }
    }

    /**
     * Reports [e] as an unsupported construct (DESIGN-v0.md 1.5) and marks its nodes as handled by the action, unless
     * [e] is outside the action ([inAction] false). Returns null, the value of an unsupported expression.
     */
    private fun unsupported(
        what: String,
        reason: String,
        e: FirElement,
        inAction: Boolean = true,
    ): Nothing? {
        if (inAction) markAll(e)
        val pos = ctx.posOf(e)
        if (problems.none { it.what == what && it.pos == pos }) problems += Unsupported(what, reason, pos)
        return null
    }

    private fun collect(f: () -> Unit): List<Stmt> {
        val saved = out
        out = mutableListOf()
        f()
        return out.also { out = saved }
    }

    /** A fresh action-unique name: [base], or [base] with suffix b, c, ... when an inlined helper repeats it. */
    private fun fresh(base: String): String {
        var name = base
        var suffix = 'b'
        while (!names.add(name)) name = base + suffix++
        return name
    }

    /**
     * The local for Kotlin declaration [sym]: `<name>__<line>`, or `<name>__<line>_<col>` if another declaration has
     * that name. Reads of [sym] then read the local, unless it is a cell ([variable] false), which is read by `get`.
     */
    private fun declare(
        sym: FirBasedSymbol<*>,
        name: String,
        type: KType,
        pos: Pos,
        variable: Boolean = true,
    ): Local {
        var base = "${name}__${pos.line}"
        if (nameOwners[base]?.let { it != sym } == true) base += "_${pos.col}"
        nameOwners.putIfAbsent(base, sym)
        return Local(fresh(base), type, pos).also { if (variable) locals[sym] = it }
    }

    private fun temp(
        prefix: String,
        type: KType,
        pos: Pos,
    ) = Local(fresh("${prefix}_${pos.line}_${pos.col}"), type, pos)

    // ---- types ----

    /** The KVIR type of Kotlin type [t]; [slot] is the abstraction slot of a String value. Null if unsupported. */
    private fun ktype(
        t: ConeKotlinType,
        slot: Any?,
        e: FirElement,
        inAction: Boolean = true,
    ): KType? {
        val low = t.lowerBoundIfFlexible()
        val nullable = low.isMarkedNullable
        return when (val id = low.classId?.asString()) {
            "kotlin/Long", "kotlin/Int", "kotlin/Boolean" ->
                when {
                    nullable -> unsupported("nullable ${id.removePrefix("kotlin/")}", "v0 models Long, Int and Boolean values without null", e, inAction)
                    id == "kotlin/Boolean" -> KType.BoolT
                    else -> KType.IntT
                }
            "kotlin/String" -> abstraction(slot, nullable, e, inAction)
            else -> ctx.classOfType(low)?.let { KType.RefT(it.name, nullable) } ?: unsupported("value of type ${low.classId?.asFqNameString() ?: low}", "the type is not part of the model", e, inAction)
        }
    }

    /** The abstract type of a String value in [slot]'s class (DESIGN-v0.md 2.13 rule 3). */
    private fun abstraction(
        slot: Any?,
        nullable: Boolean,
        e: FirElement,
        inAction: Boolean = true,
    ): KType? {
        val types = slot?.let { ctx.abstraction.types(it) }.orEmpty()
        if (types.size == 1) return KType.Abs(types.keys.single(), nullable)
        val root = slot?.let { ctx.abstraction.find(it) }
        if (root != null && !reportedSlots.add(root)) return null
        if (types.isEmpty()) return unsupported("String value", "String value with unknown abstraction: no compute key, template or sidecar field fixes it as Key or Token", e, inAction)
        val where = types.entries.joinToString { (t, p) -> "$t at ${p.line}:${p.col}" }
        return unsupported("String abstraction", "String used as both ${types.keys.sorted().joinToString(" and ")} ($where)", e, inAction)
    }

    // ---- the action ----

    fun run(): Lowered {
        mark(call)
        markAll(call.dispatchReceiver)
        val keyArg = call.arguments[0]
        val key = keyValue(keyArg)
        names += "k"
        val local = found.scopes.find { it is FirNamedFunction && it.visibility == Visibilities.Local }
        if (local != null) {
            // Decision 5: a compute inside a local function is out of v0 scope; it is reported, never dropped.
            // Reported at the call without marking it: its key argument belongs to the ledger.
            unsupported("compute in local function ${(local as FirNamedFunction).name}", "v0 does not translate a compute call inside a local function", call, inAction = false)
        }
        var entry = Local("entry__${at.line}", KType.RefT(map.valueClass, true), at)
        val body =
            if (lambda == null) {
                unsupported("remapping function", "compute's second argument is not a lambda literal; v0 reads the action from a lambda (DESIGN-v0.md 2.2)", call.arguments[1])
                emptyList()
            } else {
                mark(call.arguments[1])
                mark(lambda)
                ctx.index.parent(lambda)?.let(::mark)
                mark(lambda.body)
                val (keyParam, entryParam) = lambda.valueParameters
                mark(keyParam)
                mark(entryParam)
                locals[keyParam.symbol] = key.local
                entry = declare(entryParam.symbol, entryParam.name.asString().takeUnless { it.startsWith("<") } ?: "entry", KType.RefT(map.valueClass, true), ctx.posOf(entryParam))
                seq(lambda.body?.statements.orEmpty(), resultLast = true)
            }
        val result = operationResult(operationScope())
        namedFreeValues()
        if (problems.isNotEmpty()) {
            markAll(lambda)
            helpers.forEach(::markAll)
        }
        val diagnostics = problems.map { Diagnostic("U${it.pos.line}_${it.pos.col}", DiagnosticKind.UNSUPPORTED, "UNSUPPORTED ${it.what} in action $id at ${where(it.pos)}: ${it.reason}", it.pos) }
        val freeValues = free.values.sortedBy { fv -> named.indexOfFirst { (item, _) -> fv.sidecarName in item.names.map { it.text } }.let { if (it < 0) Int.MAX_VALUE else it } }
        val action =
            Action(
                id = id,
                pos = at,
                map = map,
                key = key,
                entry = entry,
                free = freeValues,
                body = if (problems.isEmpty()) body else problems.toList(),
                commit = emptyList(),
                status = if (problems.isEmpty()) Status.Supported else Status.Unsupported(diagnostics),
                translated = translated,
            )
        return Lowered(action, cells.values.toList(), result, freeSources, cells.keys, helpers)
    }

    /** compute's key argument: the free value `k` (DESIGN-v0.md 2.12, ActionKey). */
    private fun keyValue(keyArg: FirExpression): FreeValue {
        val read = unwrap(keyArg) as? FirPropertyAccessExpression
        val sym = read?.let { ctx.calleeOf(it) }
        val decl = sym?.let { ctx.declarationOf(it) }
        val immutable = sym is FirValueParameterSymbol || sym is FirPropertySymbol && sym.isLocal && (decl as? FirProperty)?.isVal == true
        val pos = if (immutable && decl != null) ctx.posOf(decl) else ctx.posOf(keyArg)
        // The key argument is computed outside the action: the ledger holds its nodes, so they are not marked here.
        abstraction(ctx.slotOf(keyArg), false, keyArg, inAction = false)
        val local = Local("k", map.key, pos)
        if (immutable) {
            locals[sym] = local
            freeSources += sym
        }
        return FreeValue(local, ActionKey(ctx.posOf(keyArg)), null, sourceText(keyArg))
    }

    // ---- statements ----

    /**
     * Lowers a statement list. If [resultLast], the last statement is the lambda's result (DESIGN-v0.md 2.4 rule 2).
     * Early exit normalization (2.4 rule 4): after an `if` whose one branch always exits, the rest of the list moves
     * into the other branch, so every Exit ends its block (W3).
     */
    private fun seq(
        stmts: List<FirStatement>,
        resultLast: Boolean,
    ): List<Stmt> {
        val done = mutableListOf<Stmt>()
        for ((i, s) in stmts.withIndex()) {
            val last = i == stmts.lastIndex
            val lowered = collect { if (last && resultLast) result(s) else statement(s) }
            if (last) {
                done += lowered
                break
            }
            when (exits(lowered)) {
                Exits.NONE -> done += lowered
                Exits.SPLIT -> {
                    val rest = seq(stmts.subList(i + 1, stmts.size), resultLast)
                    val branch = lowered.last() as If
                    done += lowered.dropLast(1) + if (exits(branch.then) == Exits.NONE) branch.copy(then = branch.then + rest) else branch.copy(els = branch.els + rest)
                    return done
                }
                Exits.ALL -> {
                    stmts.drop(i + 1).forEach { unsupported("unreachable code", "statements after an exit are never run", it) }
                    return done + lowered
                }
                Exits.MIXED -> {
                    unsupported("partial exit", "only some paths of this statement leave the lambda; v0 normalizes only an if whose one branch exits (DESIGN-v0.md 2.4 rule 5)", s)
                    // The rest is lowered only to report what else it holds.
                    seq(stmts.subList(i + 1, stmts.size), resultLast)
                    return done
                }
            }
        }
        return done
    }

    private fun exits(stmts: List<Stmt>): Exits {
        if (stmts.isEmpty()) return Exits.NONE
        if (stmts.dropLast(1).any(::hasExit)) return Exits.MIXED
        return when (val last = stmts.last()) {
            is Exit -> Exits.ALL
            is If -> {
                val t = exits(last.then)
                val e = exits(last.els)
                when {
                    t == e && (t == Exits.NONE || t == Exits.ALL) -> t
                    t == Exits.ALL && e == Exits.NONE || t == Exits.NONE && e == Exits.ALL -> Exits.SPLIT
                    else -> Exits.MIXED
                }
            }
            else -> Exits.NONE
        }
    }

    private fun hasExit(s: Stmt): Boolean = s is Exit || s is If && (s.then.any(::hasExit) || s.els.any(::hasExit))

    private fun statement(s: FirStatement) {
        when (s) {
            is FirProperty -> localVal(s)
            is FirVariableAssignment -> assignment(s)
            is FirReturnExpression -> returnFrom(s)
            is FirWhenExpression -> whenChain(s) { seq(it.statements, resultLast = false) }
            is FirBlock -> {
                mark(s)
                out += seq(s.statements, resultLast = false)
            }
            is FirWhileLoop, is FirDoWhileLoop -> unsupported("loop", "loops inside an action are not modeled in v0", s)
            is FirTryExpression -> unsupported("try expression", "exception paths inside an action are not modeled in v0", s)
            is FirThrowExpression -> unsupported("throw", "exception paths inside an action are not modeled in v0", s)
            is FirFunctionCall -> call(s, valueUsed = false)
            is FirExpression -> expr(s)
            else -> unsupported(s::class.simpleName ?: "statement", "this FIR node is not translated in v0", s)
        }
    }

    /**
     * The lambda's result at [s] (DESIGN-v0.md 2.4): each branch of a `when` and the last statement of a block.
     * [exitAt] places the Exit of a plain value: an explicit `return@compute` there, else the value itself.
     */
    private fun result(
        s: FirStatement,
        exitAt: Pos? = null,
    ) {
        when {
            s is FirReturnExpression -> returnFrom(s)
            s is FirWhenExpression -> whenChain(s) { seq(it.statements, resultLast = true) }
            s is FirBlock -> {
                mark(s)
                out += seq(s.statements, resultLast = true)
            }
            s is FirExpression -> exit(s, exitAt ?: ctx.posOf(s))
            else -> statement(s)
        }
    }

    private fun returnFrom(r: FirReturnExpression) {
        mark(r)
        when (val target = r.target.labeledElement) {
            // The compiler wraps the last statement in a fake return; a written `return@compute` places the exit.
            lambda -> result(r.result, ctx.posOf(r).takeIf { r.source?.kind !is KtFakeSourceElementKind })
            is FirNamedFunction ->
                if (target.symbol in inlining) {
                    unsupported("early return in helper ${target.name}", "an inlined helper may return only at its end", r)
                } else {
                    unsupported("return from ${target.name}", "a return out of the action", r)
                }
            else -> unsupported("return", "a return out of the action", r)
        }
    }

    /** `Exit` for the value [e] (DESIGN-v0.md 2.4 rule 1): null removes, the value parameter keeps, an object puts. */
    private fun exit(
        e: FirExpression,
        pos: Pos,
    ) {
        val value = stripSmartCasts(e) ?: return
        val entryParam = lambda?.valueParameters?.getOrNull(1)?.symbol
        when {
            value is FirLiteralExpression && value.kind == ConstantValueKind.Null -> {
                mark(value)
                out += Exit(ExitResult.Remove, pos)
            }
            value is FirPropertyAccessExpression && ctx.calleeOf(value) == entryParam -> {
                mark(value)
                out += Exit(ExitResult.Keep, pos)
            }
            else -> {
                val v = expr(e, KType.RefT(map.valueClass, true)) ?: return
                out += Exit(ExitResult.Put(v), pos)
            }
        }
    }

    /** [e] without its smart casts, each marked; null if one is unstable. */
    private fun stripSmartCasts(e: FirExpression): FirExpression? {
        var x = e
        while (x is FirSmartCastExpression) {
            if (!x.isStable) return unsupported("unstable smart cast", "the compiler does not prove this smart cast stable (DESIGN-v0.md 2.3)", x)
            mark(x)
            x = x.originalExpression
        }
        return x
    }

    /** An `if` chain from a `when` without subject (DESIGN-v0.md 2.3). [branch] lowers a branch body. */
    private fun whenChain(
        w: FirWhenExpression,
        branch: (FirBlock) -> List<Stmt>,
    ) {
        mark(w)
        if (w.subjectVariable != null) {
            unsupported("when with subject", "v0 translates `if` and `when` without subject", w)
            return
        }
        out += chain(w, 0, branch)
    }

    private fun chain(
        w: FirWhenExpression,
        i: Int,
        branch: (FirBlock) -> List<Stmt>,
    ): List<Stmt> =
        collect {
            val b = w.branches.getOrNull(i) ?: return@collect
            mark(b.result)
            if (b.condition is FirElseIfTrueCondition) {
                mark(b.condition)
                out += branch(b.result)
                return@collect
            }
            if (b.hasGuard) {
                unsupported("when guard", "guards are not translated in v0", b.condition)
                return@collect
            }
            val c = expr(b.condition) ?: return@collect
            val then = branch(b.result)
            val els = chain(w, i + 1, branch)
            out += If(c, then, els, if (i == 0) ctx.posOf(w) else ctx.posOf(b.condition))
        }

    private fun localVal(p: FirProperty) {
        mark(p)
        val init = p.initializer
        if (init == null || p.delegate != null) {
            unsupported("local ${p.name} without initializer", "v0 translates a local declared with its value", p)
            return
        }
        val pos = ctx.posOf(p)
        val cls = (init as? FirFunctionCall)?.let { ctx.constructedClass(ctx.calleeOf(it)) }
        if (cls != null && p.isVal) {
            newObject(init, cls) { type -> declare(p.symbol, p.name.asString(), type, pos) }
            return
        }
        val type = ktype(ctx.typeOf(p), p.symbol, p) ?: return
        if (type is KType.RefT) {
            unsupported("local alias ${p.name}", "a second name for a module object is not modeled; use the entry or the new object directly", p)
            return
        }
        val v = expr(init, type) ?: return
        out += Let(declare(p.symbol, p.name.asString(), type, pos), v, p.isVar, pos)
    }

    private fun assignment(a: FirVariableAssignment) {
        mark(a)
        val lhs = a.lValue as? FirQualifiedAccessExpression ?: return run { unsupported("assignment", "the target is not a variable or property", a) }
        mark(lhs)
        val sym = ctx.calleeOf(lhs)
        val local = sym?.let { locals[it] }
        val field = ctx.fieldOf(sym)
        when {
            local != null && sym == lambda?.valueParameters?.getOrNull(1)?.symbol -> unsupported("assignment to the entry", "the lambda parameter is not a variable", a)
            local != null -> expr(a.rValue, local.type)?.let { out += Assign(local, it, ctx.posOf(a)) }
            sym is FirValueParameterSymbol || sym is FirPropertySymbol && sym.isLocal ->
                unsupported("assignment to captured var ${nameOf(sym)}", "the lambda assigns a local declared outside it; v0 supports only cells (DESIGN-v0.md 2.5 rule 6)", a)
            field != null && field.model == null -> {
                val obj = obj(lhs.dispatchReceiver) ?: return
                val v = expr(a.rValue, field.type) ?: return
                out += FieldWrite(obj, field, v, ctx.posOf(a))
            }
            else -> unsupported("write of ${describe(sym)}", "a write of state outside the model (DESIGN-v0.md 2.11 rule 4)", a)
        }
    }

    // ---- expressions ----

    /** The pure value of [e]; effects go to [out] first. [expected] types a `null` literal. Null if unsupported. */
    private fun expr(
        e: FirExpression,
        expected: KType? = null,
    ): Expr? {
        val pos = ctx.posOf(e)
        return when (e) {
            is FirSmartCastExpression -> stripSmartCasts(e)?.let { expr(it, expected) }
            is FirLiteralExpression -> {
                mark(e)
                literal(e, expected)
            }
            is FirFunctionCall -> call(e, valueUsed = true)
            is FirPropertyAccessExpression -> read(e)
            is FirComparisonExpression -> {
                mark(e)
                mark(e.compareToCall)
                val c = e.compareToCall
                val a = expr(c.explicitReceiver ?: c.dispatchReceiver ?: return unsupported("comparison", "no left operand", e)) ?: return null
                val b = expr(c.arguments.single()) ?: return null
                val op = COMPARISONS[e.operation] ?: return unsupported("comparison ${e.operation.operator}", "not translated in v0", e)
                if (a.type != KType.IntT || b.type != KType.IntT) return unsupported("comparison of ${a.type} and ${b.type}", "v0 compares only Long and Int values", e)
                Cmp(op, a, b, pos)
            }
            is FirEqualityOperatorCall -> equality(e)
            is FirBooleanOperatorExpression -> logic(e)
            is FirStringConcatenationCall -> template(e)
            is FirSafeCallExpression -> unsupported("safe call ?.", "not translated inside an action in v0", e)
            is FirElvisExpression -> unsupported("elvis ?:", "not translated inside an action in v0", e)
            is FirCheckNotNullCall -> unsupported("!!", "not translated inside an action in v0", e)
            is FirTypeOperatorCall -> unsupported("${e.operation.operator} check", "is/as are not translated inside an action in v0", e)
            is FirAnonymousFunctionExpression -> unsupported("nested lambda", "lambdas inside an action are not translated in v0", e)
            is FirWhenExpression -> unsupported("if/when used as a value", "v0 translates an if or when as a statement or as the lambda result", e)
            is FirTryExpression -> unsupported("try expression", "exception paths inside an action are not modeled in v0", e)
            else -> unsupported(e::class.simpleName ?: "expression", "this FIR node is not translated in v0", e)
        }
    }

    private fun literal(
        e: FirLiteralExpression,
        expected: KType?,
    ): Expr? {
        val pos = ctx.posOf(e)
        return when (e.kind) {
            ConstantValueKind.Null ->
                if (expected != null) ctx.literal(e, expected, pos) ?: unsupported("null", "null where a $expected is expected", e) else unsupported("null", "null with no Token? or object type here", e)
            ConstantValueKind.Boolean -> ctx.literal(e, KType.BoolT, pos)
            ConstantValueKind.String -> unsupported("string literal", "a String constant is neither a Key nor a template Token", e)
            else -> ctx.literal(e, KType.IntT, pos) ?: unsupported("literal ${e.kind}", "not translated in v0", e)
        }
    }

    /** A read of a variable or property (DESIGN-v0.md 2.3, 2.11, 2.12). */
    private fun read(e: FirPropertyAccessExpression): Expr? {
        mark(e)
        val pos = ctx.posOf(e)
        val sym = ctx.calleeOf(e)
        locals[sym]?.let { return Var(it, pos) }
        // A local of the action whose declaration was unsupported: that is reported already.
        if (sym != null && ctx.declarationOf(sym)?.let(::insideAction) == true) return null
        val field = ctx.fieldOf(sym)
        return when {
            sym is FirValueParameterSymbol || sym is FirPropertySymbol && sym.isLocal -> captured(sym, e)
            field != null && field.model != null -> unsupported("read of flattened field ${field.kotlin}", "only model operations on it are modeled (W7)", e)
            field != null -> obj(e.dispatchReceiver)?.let { FieldRead(it, field, pos) }
            ctx.stateOf(sym) != null -> unsupported("module state ${ctx.stateOf(sym)!!.kotlin}", "inside an action the map is used only by its compute and the counter only by its operations", e)
            sym is FirPropertySymbol && instanceVal(e, sym) -> {
                markAll(e.dispatchReceiver)
                freeValue(sym, e) { local -> InstanceVal(sym.name.asString(), local.pos) }
            }
            else -> unsupported("read of ${describe(sym)}", "a property outside the model; only immutable properties of the receiver instance are free values (DESIGN-v0.md 2.12)", e)
        }
    }

    /** True if [decl] is in the lambda or in a helper being inlined. */
    private fun insideAction(decl: FirElement): Boolean {
        var x: FirElement? = decl
        while (x != null) {
            if (x === lambda || x is FirNamedFunction && x.symbol in inlining) return true
            x = ctx.index.parent(x)
        }
        return false
    }

    /** True if [e] reads an immutable property of the instance whose method holds the action, through `this`. */
    private fun instanceVal(
        e: FirPropertyAccessExpression,
        sym: FirPropertySymbol,
    ): Boolean {
        val receiver = e.dispatchReceiver as? FirThisReceiverExpression ?: return false
        val bound = receiver.calleeReference.boundSymbol as? FirClassSymbol<*> ?: return false
        return sym.isVal && bound.classKind == ClassKind.CLASS && bound != ctx.moduleClass.symbol
    }

    /** A local or parameter declared outside the lambda: a free value (a cell is read only through set and get). */
    private fun captured(
        sym: FirBasedSymbol<*>,
        e: FirPropertyAccessExpression,
    ): Expr? {
        return freeValue(sym, e) { local ->
            val decl = ctx.declarationOf(sym)
            val init = (decl as? FirProperty)?.initializer?.let { unwrap(it) } as? FirQualifiedAccessExpression
            val racy = init?.takeIf { ctx.stateRead(it.dispatchReceiver ?: it.extensionReceiver) != null && ctx.entriesOf(it).any { m -> m.role == Role.RACY_READ || m.role == Role.NONDET } }
            if (racy != null) RacyRead(ctx.posOf(racy)) else Captured(local.pos)
        }
    }

    /** The free value of [sym], made on first read: the sidecar name if the operation names it (W9), else `<name>__<line>`. */
    private fun freeValue(
        sym: FirBasedSymbol<*>,
        e: FirQualifiedAccessExpression,
        origin: (Local) -> kv.kvir.Origin,
    ): Expr? {
        free[sym]?.let { return Var(it.local, ctx.posOf(e)) }
        val type = ktype((sym as FirCallableSymbol<*>).resolvedReturnType, sym, e) ?: return null
        if (type is KType.RefT) return unsupported("module object from outside the action", "the action reads a ${type.cls} it did not get from compute or create (DESIGN-v0.md 2.12 rule 5)", e)
        val name = nameOf(sym)
        val member = sym is FirPropertySymbol && !sym.isLocal
        // A local or parameter is placed at its declaration; a property of the instance at its first read.
        val pos = ctx.declarationOf(sym)?.takeUnless { member }?.let { ctx.posOf(it) } ?: ctx.posOf(e)
        val sidecarName = named.find { (item, target) -> target == sym && item.source !is FreeSource.Call }?.first?.names?.single()?.text
        val local = Local(sidecarName?.also { names += it } ?: fresh("${name}__${pos.line}"), type, pos)
        free[sym] = FreeValue(local, origin(local), sidecarName, if (member) "this.$name" else name)
        freeSources += sym
        return Var(local, ctx.posOf(e))
    }

    /** An object-valued operand of a field access: the entry or an object the action created (W6). */
    private fun obj(e: FirExpression?): Expr? {
        e ?: return null
        val v = expr(e) ?: return null
        if (v !is Var || v.type !is KType.RefT) return unsupported("access through ${sourceText(e)}", "only the entry and objects the action creates are accessed (W6)", e)
        return v
    }

    private fun equality(e: FirEqualityOperatorCall): Expr? {
        mark(e)
        val (left, right) = e.arguments
        val op =
            when (e.operation) {
                FirOperation.EQ, FirOperation.IDENTITY -> CmpOp.EQ
                FirOperation.NOT_EQ, FirOperation.NOT_IDENTITY -> CmpOp.NE
                else -> return unsupported("equality ${e.operation.operator}", "not translated in v0", e)
            }
        val nullLeft = unwrap(left).let { it is FirLiteralExpression && it.kind == ConstantValueKind.Null }
        val b0 = if (nullLeft) expr(right) ?: return null else null
        val a = expr(left, b0?.type?.let(::nullable)) ?: return null
        val b = b0 ?: expr(right, nullable(a.type)) ?: return null
        val sameType =
            when (val t = a.type) {
                is KType.Abs -> b.type is KType.Abs && (b.type as KType.Abs).name == t.name
                is KType.RefT -> b.type is KType.RefT && (b.type as KType.RefT).cls == t.cls
                else -> a.type == b.type
            }
        if (!sameType) return unsupported("== between ${a.type} and ${b.type}", "the operands have different abstract types", e)
        val cls = (a.type as? KType.RefT)?.cls
        if (cls != null && e.operation in setOf(FirOperation.EQ, FirOperation.NOT_EQ) && hasEquals(cls)) {
            return unsupported("== on $cls", "$cls declares equals; v0 compares objects by identity only (DESIGN-v0.md 2.3)", e)
        }
        return Cmp(op, a, b, ctx.posOf(e))
    }

    private fun nullable(t: KType): KType? =
        when (t) {
            is KType.Abs -> t.copy(nullable = true)
            is KType.RefT -> t.copy(nullable = true)
            else -> null
        }

    /** True if sidecar class [cls] declares or generates `equals`. */
    private fun hasEquals(cls: String): Boolean {
        val decl = ctx.index.nodes.filterIsInstance<FirRegularClass>().find { c -> ctx.classOf(c.symbol)?.name == cls } ?: return false
        return decl.status.isData || ctx.index.nodes.filterIsInstance<FirNamedFunction>().any { it.name.asString() == "equals" && ctx.index.parent(it) === decl }
    }

    /** `&&` and `||`: a pure right side stays an And/Or; one with effects is computed first (DESIGN-v0.md 2.6). */
    private fun logic(e: FirBooleanOperatorExpression): Expr? {
        mark(e)
        val pos = ctx.posOf(e)
        val a = expr(e.leftOperand) ?: return null
        var b: Expr? = null
        val effects = collect { b = expr(e.rightOperand) }
        val right = b ?: return null
        val and = e.kind == LogicOperationKind.AND
        if (effects.isEmpty()) return if (and) And(a, right, pos) else Or(a, right, pos)
        val cond = temp("cond", KType.BoolT, pos)
        out += Let(cond, BoolLit(!and, pos), true, pos)
        out += If(if (and) a else Not(a, pos), assignInto(cond, effects, right), emptyList(), pos)
        return Var(cond, pos)
    }

    /** [stmts] then `target := value`; a model operation that computes [value] writes [target] directly. */
    private fun assignInto(
        target: Local,
        stmts: List<Stmt>,
        value: Expr,
    ): List<Stmt> {
        val last = stmts.lastOrNull()
        if (last is ModelOp && value is Var && last.out == value.local) {
            names -= value.local.name
            return stmts.dropLast(1) + last.copy(out = target)
        }
        return stmts + Assign(target, value, value.pos)
    }

    /** A string template: `TokenOf(n)` if it matches a sidecar template type (DESIGN-v0.md 2.9). */
    private fun template(e: FirStringConcatenationCall): Expr? {
        mark(e)
        val match = ctx.templateOf(e)
        if (match == null) {
            val shape = e.arguments.joinToString(" + ") { (it as? FirLiteralExpression)?.let { lit -> "\"${lit.value}\"" } ?: "<${it.resolvedType.lowerBoundIfFlexible().classId?.shortClassName}>" }
            val wanted = ctx.templates.joinToString { "\"${it.prefix}\${Long}${it.suffix}\"" }.ifEmpty { "none declared" }
            return unsupported("string template", "parts $shape match no sidecar template type ($wanted)", e)
        }
        val (template, long) = match
        e.arguments.forEach { if (it !== long) mark(it) }
        val n = expr(long) ?: return null
        return TokenOf(n, KType.Abs(template.type, false), ctx.posOf(e))
    }

    // ---- calls ----

    /** A call (DESIGN-v0.md 2.3): Kotlin operator, constructor, trusted model, trusted function or module helper. */
    private fun call(
        c: FirFunctionCall,
        valueUsed: Boolean,
    ): Expr? {
        mark(c)
        val pos = ctx.posOf(c)
        val sym = ctx.calleeOf(c) ?: return unsupported("unresolved call", "the call does not resolve", c)
        val id = sym.callableId
        val owner = id?.classId?.asString()
        val name = id?.callableName?.asString()
        if (owner in setOf("kotlin/Long", "kotlin/Int")) return arithmetic(c, owner!!.removePrefix("kotlin/"), name!!)
        if (owner == "kotlin/Boolean" && name == "not") return expr(c.dispatchReceiver ?: c.explicitReceiver!!)?.let { Not(it, pos) }
        ctx.constructedClass(sym)?.let { cls -> return newObject(c, cls) { type -> temp("new", type, pos) } }
        val entries = ctx.entriesOf(sym)
        if (entries.isNotEmpty()) return modelCall(c, entries, valueUsed)
        (sym as? FirNamedFunctionSymbol)?.let { f -> ctx.trusted[f]?.let { return trustedCall(c, it, valueUsed) } }
        (sym as? FirNamedFunctionSymbol)?.let { f -> ctx.index.functions[f]?.let { return inline(c, it, valueUsed) } }
        return unsupported("unknown call ${describe(sym)}", "neither a trusted model, a trusted function nor a helper of the module file (DESIGN-v0.md 2.3)", c)
    }

    private fun arithmetic(
        c: FirFunctionCall,
        type: String,
        name: String,
    ): Expr? {
        val op =
            when (name) {
                "plus" -> ArithOp.PLUS
                "minus" -> ArithOp.MINUS
                else -> return unsupported("$type ${OPERATOR_SYMBOLS[name] ?: name}", "v0 models only + and - on $type (DESIGN-v0.md 2.3)", c)
            }
        val a = expr(c.dispatchReceiver ?: c.explicitReceiver ?: return null) ?: return null
        val b = expr(c.arguments.single()) ?: return null
        return Arith(op, a, b, ctx.assumption(c), ctx.posOf(c))
    }

    /** `New` for a sidecar class constructor (DESIGN-v0.md 2.11 rule 6); [local] declares the object's local. */
    private fun newObject(
        c: FirFunctionCall,
        cls: ObjClass,
        local: (KType) -> Local,
    ): Expr? {
        mark(c)
        val values = linkedMapOf<String, Expr>()
        var ok = true
        // Arguments in source order: Kotlin evaluates them so. An argument for no sidecar field is evaluated for its effects.
        for ((arg, param) in c.resolvedArgumentMapping.orEmpty()) {
            val field = cls.fields.find { it.kotlin == param.name.asString() }
            val v =
                when {
                    field == null -> expr(arg)
                    field.model != null -> flattenedInit(arg, field)
                    else -> expr(arg, field.type)
                }
            if (v == null) ok = false else if (field != null) values[field.name] = v
        }
        for (f in cls.fields.filter { it.name !in values }) {
            val default = cls.defaults[f.name]
            val problem = ctx.flattenProblem(f)
            if (default != null && problem == null) {
                values[f.name] = default
                continue
            }
            ok = false
            if (default == null) {
                unsupported("constructor default of ${f.kotlin}", "${cls.name}(...) gives ${f.kotlin} no argument and its default is not a constant", c)
            } else {
                unsupported("flattened field ${f.kotlin}", "${problem!!.reason} at ${where(problem.pos)}", c)
            }
        }
        if (!ok) return null
        val v = local(KType.RefT(cls.name, false))
        out += New(v, cls, values, ctx.posOf(c))
        return Var(v, ctx.posOf(c))
    }

    /** The initial value of flattened [field]: the argument of a new model object, e.g. `Semaphore(1)` is 1. */
    private fun flattenedInit(
        arg: FirExpression,
        field: kv.kvir.ObjField,
    ): Expr? {
        ctx.flattenProblem(field)?.let { return unsupported("flattened field ${field.kotlin}", "${it.reason} at ${where(it.pos)}", arg) }
        val ctor = arg as? FirFunctionCall
        if (ctor == null || ctx.entriesOf(ctor).none { it.model == field.model && it.role == Role.CTOR }) {
            return unsupported("flattened field ${field.kotlin}", "the argument is not a new ${field.model} object", arg)
        }
        mark(ctor)
        return expr(ctor.arguments.single(), field.type)
    }

    private fun useCheck(
        entry: Entry,
        c: FirFunctionCall,
        inAction: Boolean = true,
    ): Boolean {
        if (entry.model in ctx.uses) return true
        unsupported("trusted model ${entry.model}", "the call needs model ${entry.model}, which the sidecar does not `use`", c, inAction)
        return false
    }

    /** A call of a built-in model (DESIGN-v0.md 4.3). */
    private fun modelCall(
        c: FirFunctionCall,
        entries: List<Entry>,
        valueUsed: Boolean,
    ): Expr? {
        val pos = ctx.posOf(c)
        val first = entries.first()
        return when (first.role) {
            Role.ACTION -> unsupported("nested compute", "an action does not access its map except through its entry (DESIGN-v0.md 4.3)", c)
            Role.RACY_READ -> unsupported("map read ${first.name} inside an action", "inside an action the map is read only through compute's entry (DESIGN-v0.md 4.3)", c)
            Role.CTOR -> unsupported("new ${first.owner.substringAfterLast('/')}", "a ${first.model} object is modeled only as a flattened field of a new object or as a cell", c)
            Role.NONDET, Role.CLOCK -> {
                if (!useCheck(first, c)) return null
                if (first.role == Role.NONDET && ctx.stateRead(c.dispatchReceiver) == null) return unsupported("${first.name} on ${sourceText(c)}", "not the module map", c)
                markAll(c.dispatchReceiver)
                val n = temp("n", KType.IntT, pos)
                out += Nondet(listOf(n), first.model, pos)
                Var(n, pos)
            }
            Role.OPERATION_BODY -> unsupported("${first.model} inside an action", "a Mono.fromCallable lambda is the body of an operation function, not a value inside an action (DESIGN-v0.md 8.3)", c)
            Role.NO_EFFECT -> {
                if (!useCheck(first, c)) return null
                markAll(c.dispatchReceiver)
                c.arguments.forEach(::pureArgument)
                if (valueUsed) unsupported("value of ${first.name}", "a ${first.model} call has no modeled value", c) else null
            }
            Role.OP -> operation(c, entries)
        }
    }

    /** An argument of a call with no effect: it must be pure; its value is not translated (DESIGN-v0.md 2.3, 2.13 rule 3). */
    private fun pureArgument(e: FirExpression) {
        markAll(e)
        var effect: FirElement? = null
        e.accept(
            object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    // Pure: reads, literals, templates, Kotlin Long/Int/Boolean operators, model reads (`get`, mappingCount).
                    val pure =
                        when (element) {
                            is FirFunctionCall -> {
                                val sym = ctx.calleeOf(element)
                                sym?.callableId?.classId?.asString() in setOf("kotlin/Long", "kotlin/Int", "kotlin/Boolean") ||
                                    sym?.let { ctx.entriesOf(it) }.orEmpty().any { it.name == "get" || it.role == Role.NONDET }
                            }
                            is FirAnonymousFunctionExpression, is FirVariableAssignment -> false
                            else -> true
                        }
                    if (!pure && effect == null) effect = element
                    element.acceptChildren(this)
                }
            },
        )
        effect?.let { unsupported("call in a log argument", "an argument of a call with no effect must be pure", it) }
    }

    /** A model operation on a flattened field, the module counter or a cell. */
    private fun operation(
        c: FirFunctionCall,
        entries: List<Entry>,
    ): Expr? {
        val pos = ctx.posOf(c)
        val receiver = c.dispatchReceiver?.let { stripSmartCasts(it) } ?: return null
        val read = receiver as? FirPropertyAccessExpression ?: return unsupported("${entries.first().name} on ${sourceText(receiver)}", "the receiver is not a flattened field, the module counter or a cell", c)
        val sym = ctx.calleeOf(read)
        val field = ctx.fieldOf(sym)
        val state = ctx.stateOf(sym)
        val isLocal = sym is FirPropertySymbol && sym.isLocal && sym !in locals
        val entry =
            entries.find {
                when {
                    field?.model != null -> it.model == field.model
                    state != null -> it.model == TrustedModels.COUNTER
                    isLocal -> it.model == TrustedModels.CELL
                    else -> false
                }
            } ?: return unsupported("${entries.first().name} on ${sourceText(receiver)}", "the receiver is not a flattened field, the module counter or a cell", c)
        if (!useCheck(entry, c)) return null
        if (isLocal) return cellOperation(c, read, sym, entry)
        mark(read)
        val target =
            if (field != null) {
                ctx.flattenProblem(field)?.let { return unsupported("flattened field ${field.kotlin}", "${it.reason} at ${where(it.pos)}", c) }
                FieldTarget(obj(read.dispatchReceiver) ?: return null, field)
            } else {
                markAll(read.dispatchReceiver)
                StateTarget(state!!)
            }
        val args = c.arguments.map { expr(it) ?: return null }
        val result =
            when (entry.returns) {
                Returns.BOOL -> temp("cond", KType.BoolT, pos)
                Returns.LONG -> temp("n", KType.IntT, pos)
                else -> null
            }
        out += ModelOp(result, entry.op, target, args, if (entry.unbounded) ctx.assumption(c) else null, pos)
        return result?.let { Var(it, pos) }
    }

    /** A cell `set` or `get` (DESIGN-v0.md 2.5): an Assign to the cell's local, or a read of it. */
    private fun cellOperation(
        c: FirFunctionCall,
        read: FirPropertyAccessExpression,
        sym: FirBasedSymbol<*>,
        entry: Entry,
    ): Expr? {
        mark(read)
        val cell = cell(sym, read) ?: return null
        val pos = ctx.posOf(c)
        return when (entry.name) {
            "set" -> {
                val v = expr(c.arguments.single(), cell.local.type) ?: return null
                out += Assign(cell.local, v, pos)
                null
            }
            else -> Var(cell.local, pos)
        }
    }

    /** The cell for local [sym], checked on first use (DESIGN-v0.md 2.5). */
    private fun cell(
        sym: FirBasedSymbol<*>,
        use: FirElement,
        inAction: Boolean = true,
    ): Cell? {
        cells[sym]?.let { return it }
        if (op == null) return unsupported("cell in a stray action", "cells belong to an operation; no sidecar operation names this action", use, inAction)
        ctx.cellProblem(sym, found)?.let { return unsupported("cell escapes", "${it.reason} at ${where(it.pos)}", use, inAction) }
        val decl = ctx.declarationOf(sym) as FirProperty
        val init = decl.initializer as FirFunctionCall
        val elementType = ctx.typeOf(decl).lowerBoundIfFlexible().typeArguments.firstOrNull()?.type
        // AtomicReference<T> holds a T; AtomicBoolean holds a Boolean.
        val type = if (elementType != null) ktype(elementType, sym, use, inAction) ?: return null else KType.BoolT
        val lit = init.arguments.single() as FirLiteralExpression
        val initValue = ctx.literal(lit, type, ctx.posOf(lit)) ?: return unsupported("cell initial value", "the initial value does not fit the cell type $type", use, inAction)
        val pos = ctx.posOf(decl)
        val local = declare(sym, decl.name.asString(), type, pos, variable = false)
        return Cell(local, initValue, pos).also { cells[sym] = it }
    }

    /** A trusted project function (DESIGN-v0.md 4.2): a clock read or a non-deterministic call bound by its contract. */
    private fun trustedCall(
        c: FirFunctionCall,
        t: TrustedDecl,
        valueUsed: Boolean,
    ): Expr? {
        val pos = ctx.posOf(c)
        val model = describe(ctx.calleeOf(c))
        markAll(c.dispatchReceiver)
        c.arguments.forEach { expr(it) ?: return null }
        if (t.clock) {
            val n = temp(t.shortName, KType.IntT, pos)
            out += Nondet(listOf(n), model, pos)
            return Var(n, pos)
        }
        if (!t.effectsNone) return unsupported("call to $model", "a trusted function without 'effects none' may change state the model does not see", c)
        // Named by a sidecar `free a, b = call f`: the values are free values (method parameters); else fresh locals.
        val bound = named.find { (item, target) -> item.source is FreeSource.Call && target === c }?.first?.names?.map { it.text }
        val outs =
            (t.returns + t.ghosts).mapIndexed { i, v ->
                val type =
                    when (val n = v.type.text) {
                        "Bool" -> KType.BoolT
                        "Int" -> KType.IntT
                        else -> KType.Abs(n, false)
                    }
                if (bound == null) return@mapIndexed temp(v.name.text, type, pos)
                names += bound[i]
                Local(bound[i], type, pos).also { free[bound[i]] = FreeValue(it, NondetCall(model, pos), bound[i], "$model(...)") }
            }
        out += Nondet(outs, model, pos)
        if (t.returns.isEmpty()) return if (valueUsed) unsupported("value of $model", "the trusted function declares no result", c) else null
        return Var(outs.first(), pos)
    }

    /** Inlines a helper of the module file (DESIGN-v0.md 2.3): its parameters become locals, its result the value. */
    private fun inline(
        c: FirFunctionCall,
        fn: FirNamedFunction,
        valueUsed: Boolean,
    ): Expr? {
        val pos = ctx.posOf(c)
        if (fn.symbol in inlining) return unsupported("recursive helper ${fn.name}", "a helper that calls itself, directly or not, cannot be inlined (DESIGN-v0.md 2.3)", c)
        if (fn.visibility == Visibilities.Local) return unsupported("call to local function ${fn.name}", "v0 inlines only member and top-level helpers", c)
        val receiver = c.dispatchReceiver
        if (receiver != null && receiver !is FirThisReceiverExpression && receiver !is FirResolvedQualifier) {
            return unsupported("helper call on ${sourceText(receiver)}", "v0 inlines helpers of this object or class only", c)
        }
        markAll(receiver)
        helpers += fn
        mark(fn)
        val mapping = c.resolvedArgumentMapping.orEmpty()
        for (param in fn.valueParameters) {
            mark(param)
            val arg = mapping.entries.find { it.value === param }?.key ?: param.defaultValue ?: return unsupported("argument ${param.name}", "no argument and no default", c)
            val type = ktype(ctx.typeOf(param), param.symbol, param) ?: return null
            val v = expr(arg, type) ?: return null
            val p = ctx.posOf(param)
            out += Let(declare(param.symbol, param.name.asString(), type, p), v, false, p)
        }
        val body = fn.body ?: return unsupported("helper ${fn.name} without body", "nothing to inline", c)
        mark(body)
        inlining.addLast(fn.symbol)
        try {
            body.statements.dropLast(1).forEach(::statement)
            val last = body.statements.lastOrNull()
            if (last is FirReturnExpression && last.target.labeledElement === fn) {
                mark(last)
                val type = ktype(fn.returnTypeRef.coneType, fn.symbol, fn) ?: return null
                return expr(last.result, type)
            }
            last?.let(::statement)
            return if (valueUsed) unsupported("value of helper ${fn.name}", "the helper returns no value at its end", c) else null
        } finally {
            inlining.removeLast()
        }
    }

    // ---- the operation ----

    /**
     * Where a `fun` operation holds its action (DESIGN-v0.md 8.3, model reactor.Mono.fromCallable): the function, if
     * the compute call is directly in it; or the lambda of `Mono.fromCallable`, if that call is the whole function
     * body (an expression body or a single return) and its lambda holds the compute call directly. Every other shape
     * is UNSUPPORTED: any other lambda around the compute call, a second or nested fromCallable, a fromCallable
     * without the compute call, and a chained call on the Mono. Null for an operation selected by `compute in`, for
     * a stray action, and after a report.
     */
    private fun operationScope(): FirFunction? {
        val fn = found.inFunction
        if (op?.selector !is Selector.Fun || fn == null) return null

        // The shape is outside the action: its nodes stay in the ledger.
        fun fail(
            what: String,
            reason: String,
            e: FirElement,
        ): FirFunction? = unsupported(what, reason, e, inAction = false)

        val shape = "v0 reads a fun operation from its body, or from the lambda of the one Mono.fromCallable that is its whole body (DESIGN-v0.md 8.3)"
        // fromCallable calls inside the action are reported by the action lowering.
        val action = ctx.index.subtree(call).toSet()
        val bodies =
            ctx.index.subtree(fn).filter { it !in action }.map { ctx.index.nodes[it] }.filterIsInstance<FirFunctionCall>()
                .filter { c -> ctx.entriesOf(c).any { it.role == Role.OPERATION_BODY } }
        val lambdas = found.scopes.drop(found.scopes.indexOf(fn) + 1)
        if (lambdas.isEmpty()) {
            val other = bodies.firstOrNull() ?: return fn
            return fail("Mono.fromCallable without the action", "its lambda holds no compute call; the action of ${fn.name} is outside it (DESIGN-v0.md 8.3)", other)
        }
        for ((i, lambda) in lambdas.withIndex()) {
            val holder = callOf(lambda)
            val isBody = holder != null && bodies.any { it === holder }
            if (i == 0 && isBody) continue
            val what =
                when {
                    isBody -> "nested Mono.fromCallable"
                    holder != null -> "compute inside the lambda of ${describe(ctx.calleeOf(holder))}"
                    else -> "compute inside a lambda"
                }
            return fail(what, shape, holder ?: lambda)
        }
        val fc = callOf(lambdas.first())!!
        bodies.firstOrNull { it !== fc }?.let { return fail("second Mono.fromCallable", "an operation function holds one Mono.fromCallable, whose lambda is the operation body (DESIGN-v0.md 8.3)", it) }
        val whole = (fn.body?.statements?.singleOrNull() as? FirReturnExpression)?.takeIf { it.target.labeledElement === fn }?.result?.let(::unwrap)
        if (whole !== fc) {
            val chained = ctx.index.subtree(fn).map { ctx.index.nodes[it] }.filterIsInstance<FirQualifiedAccessExpression>().firstOrNull { unwrap(it.explicitReceiver) === fc }
            if (chained != null) {
                return fail(
                    "chained call ${ctx.calleeOf(chained)?.name ?: sourceText(chained)} on Mono.fromCallable",
                    "the model covers a Mono.fromCallable that is the whole body of ${fn.name}; an operator on the Mono changes when and how often the lambda runs (DESIGN-v0.md 8.3)",
                    chained.calleeReference,
                )
            }
            return fail("Mono.fromCallable that is not the whole body", "v0 accepts Mono.fromCallable only as the whole body of ${fn.name}: an expression body or a single return (DESIGN-v0.md 8.3)", fc)
        }
        if (!useCheck(ctx.entriesOf(fc).first { it.role == Role.OPERATION_BODY }, fc, inAction = false)) return null
        return lambdas.first()
    }

    /** The call that takes [lambda] as an argument, or null. */
    private fun callOf(lambda: FirFunction): FirFunctionCall? {
        var x = ctx.index.parent(lambda)
        while (x != null && x !is FirFunctionCall && x !is FirFunction) x = ctx.index.parent(x)
        return x as? FirFunctionCall
    }

    /**
     * The operation result (W10): the one final `return` of a cell `get` or a constant in [scope], which is the
     * operation function or its Mono.fromCallable lambda (whose last expression is a return; DESIGN-v0.md 8.3).
     */
    private fun operationResult(scope: FirFunction?): OpResult? {
        val item = op?.result ?: return null
        val fn = found.inFunction

        // The result is computed outside the action: its nodes stay in the ledger.
        fun fail(
            reason: String,
            e: FirElement,
        ): Nothing? = unsupported("operation result", reason, e, inAction = false)

        if (op.selector !is Selector.Fun || fn == null) return fail("an operation selected by 'compute in' has no function result", call)
        // An operation shape v0 does not read is reported by operationScope.
        scope ?: return null
        val name = if (scope === fn) fn.name.asString() else "the Mono.fromCallable lambda of ${fn.name}"
        val returns = mutableListOf<FirReturnExpression>()
        scope.body?.accept(
            object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    if (element is FirReturnExpression && element.target.labeledElement === scope) returns += element
                    element.acceptChildren(this)
                }
            },
        )
        val r = returns.singleOrNull() ?: return fail("v0 reads the result from exactly one return; $name has ${returns.size}", scope)
        if (scope.body?.statements?.lastOrNull() !== r) return fail("the return is not the last statement of $name", r)
        val e = unwrap(r.result)!!
        val pos = ctx.posOf(e)
        val v: Expr =
            when {
                e is FirFunctionCall && ctx.entriesOf(e).any { it.model == TrustedModels.CELL && it.name == "get" } -> {
                    val read = unwrap(e.dispatchReceiver) as? FirPropertyAccessExpression ?: return fail("not a cell", e)
                    val sym = ctx.calleeOf(read) ?: return fail("not a cell", e)
                    Var((cells[sym] ?: cell(sym, read, inAction = false) ?: return null).local, pos)
                }
                e is FirLiteralExpression -> ctx.literal(e, ktype(e.resolvedType, null, e, inAction = false) ?: return null, pos) ?: return fail("constant ${e.value}", e)
                else -> return fail("the result reads only cells and constants (W10); ${sourceText(e)} is neither", e)
            }
        return OpResult(Local(item.name.text, v.type, pos), v, pos)
    }

    /** Named free values the action does not read are parameters too (DESIGN-v0.md 2.12 rule 3). */
    private fun namedFreeValues() {
        for ((item, target) in named) {
            val sym = target as? FirBasedSymbol<*> ?: continue
            if (sym in free || sym in locals) continue
            val decl = ctx.declarationOf(sym) ?: continue
            // The declaration is outside the action and its nodes are in the ledger: a type problem is reported
            // at it without marking it translated (W14).
            val type = ktype((sym as FirCallableSymbol<*>).resolvedReturnType, sym, decl, inAction = false) ?: continue
            val name = item.names.single().text
            names += name
            val local = Local(name, type, ctx.posOf(decl))
            val origin = if (item.source is FreeSource.This) InstanceVal(sym.callableId?.callableName?.asString() ?: name, local.pos) else Captured(local.pos)
            free[sym] = FreeValue(local, origin, name, if (item.source is FreeSource.This) "this.${item.source.name.text}" else item.source.name.text)
            freeSources += sym
        }
    }

    private fun where(p: Pos) = "${p.file.substringAfterLast('/')}:${p.line}:${p.col}"

    private fun nameOf(sym: FirBasedSymbol<*>): String = (sym as? FirCallableSymbol<*>)?.name?.asString() ?: "value"

    private fun describe(sym: FirCallableSymbol<*>?): String {
        val id = sym?.callableId ?: return "?"
        return listOfNotNull(id.className?.shortName()?.asString(), id.callableName.asString()).joinToString(".")
    }

    private companion object {
        val COMPARISONS = mapOf(FirOperation.LT to CmpOp.LT, FirOperation.LT_EQ to CmpOp.LE, FirOperation.GT to CmpOp.GT, FirOperation.GT_EQ to CmpOp.GE)
    }
}
