package kv.lower

import kv.frontend.RUN_ASYNC
import kv.frontend.SidecarResolver
import kv.frontend.unwrapTask
import kv.kvir.And
import kv.kvir.Arith
import kv.kvir.ArithOp
import kv.kvir.Assign
import kv.kvir.BoolLit
import kv.kvir.Break
import kv.kvir.Call
import kv.kvir.Catch
import kv.kvir.Cmp
import kv.kvir.CmpOp
import kv.kvir.Diagnostic
import kv.kvir.DiagnosticKind
import kv.kvir.EntryFun
import kv.kvir.EnumDef
import kv.kvir.EnumLit
import kv.kvir.ExcKind
import kv.kvir.Expr
import kv.kvir.FreeValue
import kv.kvir.Holds
import kv.kvir.If
import kv.kvir.Inline
import kv.kvir.InstanceVal
import kv.kvir.IntLit
import kv.kvir.KType
import kv.kvir.Let
import kv.kvir.LibExc
import kv.kvir.Local
import kv.kvir.Module
import kv.kvir.ModuleKind
import kv.kvir.New
import kv.kvir.Not
import kv.kvir.NullLit
import kv.kvir.ObligationDef
import kv.kvir.ObligationOp
import kv.kvir.Or
import kv.kvir.Pos
import kv.kvir.Return
import kv.kvir.Spawn
import kv.kvir.Status
import kv.kvir.Stmt
import kv.kvir.Task
import kv.kvir.Throw
import kv.kvir.Thrown
import kv.kvir.Try
import kv.kvir.Unsupported
import kv.kvir.Var
import kv.kvir.While
import kv.kvir.walk
import kv.model.ClientModel
import kv.model.ClientModels
import kv.model.ClientRole
import kv.model.Role
import kv.sidecar.OperationDecl
import kv.sidecar.Sidecar
import kv.sidecar.TrustedDecl
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.contracts.description.LogicOperationKind
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.utils.isConst
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirBooleanOperatorExpression
import org.jetbrains.kotlin.fir.expressions.FirBreakExpression
import org.jetbrains.kotlin.fir.expressions.FirCheckNotNullCall
import org.jetbrains.kotlin.fir.expressions.FirCheckedSafeCallSubject
import org.jetbrains.kotlin.fir.expressions.FirComparisonExpression
import org.jetbrains.kotlin.fir.expressions.FirContinueExpression
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirDoWhileLoop
import org.jetbrains.kotlin.fir.expressions.FirElvisExpression
import org.jetbrains.kotlin.fir.expressions.FirEqualityOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirFunctionTypeConversionExpression
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirOperation
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSafeCallExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirThrowExpression
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.FirWhileLoop
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.impl.FirElseIfTrueCondition
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirEnumEntrySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.types.ConstantValueKind
import java.util.IdentityHashMap

/** The catch types the model maps to exception kinds (DESIGN-E 3.5); kotlin.X type aliases resolve to java.lang.X. */
private val THROWABLE = setOf("java/lang/Throwable", "kotlin/Throwable")
private val EXCEPTION = setOf("java/lang/Exception", "kotlin/Exception")
private val RUNTIME = setOf("java/lang/RuntimeException", "kotlin/RuntimeException")
private val ERROR = setOf("java/lang/Error", "kotlin/Error")
private const val REE_CLASS = "java/util/concurrent/RejectedExecutionException"
private val INTERRUPTED = setOf("java/lang/InterruptedException", "kotlin/InterruptedException")
private val NUMBER = setOf("kotlin/Int", "kotlin/Long")

/** Classes whose operators a pure argument may use (an argument the model drops, DESIGN-v0.md 2.3). */
private val PURE_OWNERS = setOf("kotlin/Int", "kotlin/Long", "kotlin/Boolean", "kotlin/String", "kotlin/Any")

/**
 * What a Kotlin variable stands for while a body is lowered: a KVIR value; a helper argument lowered where it is read,
 * in its caller's scope (it is pure and stable); an entry parameter, a value only once a modeled place reads it; or a
 * caught exception, which is no value of the model.
 */
private sealed interface Bound {
    data class Value(
        val e: Expr,
    ) : Bound

    data class Arg(
        val arg: FirExpression,
        val scope: Scope,
    ) : Bound

    data class Param(
        val sym: FirValueParameterSymbol,
    ) : Bound

    data object Exception : Bound
}

/**
 * The Kotlin names visible where a body is lowered. A helper's body gets a new scope without parent (Kotlin helpers do
 * not see their caller's locals); blocks and inline lambdas get a child scope; a task's scope [captures] the scope
 * where its lambda literal is written.
 */
private class Scope(
    val parent: Scope?,
    val captures: Scope? = null,
) {
    val vars = IdentityHashMap<FirBasedSymbol<*>, Bound>()

    fun local(sym: FirBasedSymbol<*>): Bound? = vars[sym] ?: parent?.local(sym)
}

/** An inline helper frame: the helper, the call that inlined it, the name suffix of its locals. */
private class Frame(
    val fn: FirNamedFunctionSymbol?,
    val suffix: String,
)

/**
 * FIR to KVIR for the client module of [sidecar] (DESIGN-E 6.3, 6.4): the module file [file] holds [moduleClass]; the
 * ledger covers that file (a client module has no state another file could reach).
 */
internal fun lowerClient(
    sidecar: Sidecar,
    resolver: SidecarResolver,
    file: FirFile,
    moduleClass: FirRegularClass,
): Module = ClientLowering(ModuleContext(sidecar, resolver, FirIndex(file), moduleClass), resolver, sidecar, file).run()

/**
 * FIR to KVIR for a client module (DESIGN-E 6.3, 6.4): each sidecar operation's public function is an entry, lowered
 * with its private helpers inlined; each lambda it hands to an executor is a task, with the precondition of DESIGN-E
 * 3.4; every other FIR node of the module file goes to the ledger ([ClientAudit]).
 */
internal class ClientLowering(
    val ctx: ModuleContext,
    val resolver: SidecarResolver,
    val sidecar: Sidecar,
    val file: FirFile,
) {
    val session = file.moduleData.session

    /** Trusted declarations by the callable id and parameter count of their Kotlin function or property. */
    val trustedFuns: Map<String, TrustedDecl> =
        sidecar.trusted.filter { !it.value }.mapNotNull { t -> resolver.trusted[t.shortName]?.let { idOf(it) to t } }.toMap()
    val trustedValues: Map<String, TrustedDecl> =
        sidecar.trusted.filter { it.value }.mapNotNull { t -> resolver.trustedValues[t.shortName]?.let { it.callableId.toString() to t } }.toMap()

    /** Sidecar enums by Kotlin class id. */
    val enums: Map<ClassId, EnumDef> =
        resolver.enums.entries.associate { (name, e) -> e.first.classId to EnumDef(name, e.first.classId.asSingleFqName().asString(), e.second) }

    /** The obligations, with their KVIR types. */
    val obligations = sidecar.obligations.map { o -> ObligationDef(o.name.text, o.types.map { sidecarType(it.text) }) }

    val loops: Map<FirElement, String> = resolver.loopHoles.entries.associate { (h, loop) -> loop to "${h.loop!!.fn.text}@${h.loop.line}" }
    val uses = sidecar.uses.map { it.model.text }.toSet()
    val diagnostics = mutableListOf<Diagnostic>()
    val tasks = linkedMapOf<FirAnonymousFunction, Task?>()

    /** The code the units lower: entry functions, inlined helpers and task lambdas. The ledger leaves their nodes to W14. */
    val lowered: MutableSet<FirElement> = java.util.Collections.newSetFromMap(IdentityHashMap())

    /** Callbacks the lowering checked have no effect, with what they are: the ledger holds them as no-effect (6.4 row 10). */
    val noEffect = IdentityHashMap<FirElement, String>()

    /** The exception kinds the code throws (DESIGN-E 3.5), found before any catch is lowered. */
    val thrown: List<Thrown> = thrownKinds()

    fun idOf(f: FirNamedFunctionSymbol) = "${f.callableId}/${f.valueParameterSymbols.size}"

    /** A sidecar value type as KVIR (DESIGN-E 6.2): Int, Bool, a type, an enum or a class, `?` for nullable. */
    fun sidecarType(text: String): KType {
        val nullable = text.endsWith("?")
        val base = text.removeSuffix("?")
        return when {
            base == "Int" -> KType.IntT
            base == "Bool" -> KType.BoolT
            sidecar.enums.any { it.name.text == base } -> KType.EnumT(base, nullable)
            sidecar.classes.any { it.name.text == base } -> KType.RefT(base, nullable)
            else -> KType.Abs(base, nullable)
        }
    }

    fun classSymbol(id: ClassId): FirRegularClassSymbol? = session.symbolProvider.getClassLikeSymbolByClassId(id) as? FirRegularClassSymbol

    /** [id] and its superclasses, by class id string. */
    fun supertypes(id: ClassId): Set<String> {
        val out = linkedSetOf<String>()
        val queue = ArrayDeque(listOf(id))
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!out.add(c.asString())) continue
            classSymbol(c)?.resolvedSuperTypes?.mapNotNull { it.lowerBoundIfFlexible().classId }?.let { queue += it }
        }
        return out
    }

    private fun thrownKinds(): List<Thrown> {
        val out = linkedSetOf<Thrown>()
        ctx.moduleClass.accept(
            object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    if (element is FirThrowExpression) thrownKind(element.exception)?.let { out += it }
                    element.acceptChildren(this)
                }
            },
        )
        return out.toList()
    }

    /** The kind of `throw C(E.X, ...)`; null if the exception is not a constructor call or its code is no enum entry. */
    fun thrownKind(e: FirExpression): Thrown? {
        val call = unwrap(e) as? FirFunctionCall ?: return null
        val ctor = ctx.calleeOf(call) as? FirConstructorSymbol ?: return null
        val cls = ctor.callableId.classId ?: return null
        val first = call.arguments.firstOrNull()?.let { unwrap(it) }
        val code =
            when {
                first == null -> null
                first is FirPropertyAccessExpression && ctx.calleeOf(first) is FirEnumEntrySymbol -> (ctx.calleeOf(first) as FirEnumEntrySymbol).name.asString()
                first.resolvedType.lowerBoundIfFlexible().classId?.let(::classSymbol)?.classKind == ClassKind.ENUM_CLASS -> return null
                else -> null
            }
        val supers = supertypes(cls)
        if (supers.none { it in EXCEPTION }) return null
        return Thrown(cls.shortClassName.asString(), code, supers.any { it in RUNTIME })
    }

    /** The kinds `catch (e: T)` catches, T by class id (DESIGN-E 3.5), or null for a type the model cannot map. */
    fun catchKinds(id: String): List<ExcKind>? {
        val lib = listOf(LibExc.REE, LibExc.RTE, LibExc.CHK, LibExc.INTR)
        return when {
            id in THROWABLE -> lib + LibExc.ERR + thrown
            id in EXCEPTION -> lib + thrown
            id in RUNTIME -> listOf(LibExc.REE, LibExc.RTE) + thrown.filter { it.runtime }
            id in ERROR -> listOf(LibExc.ERR)
            id == REE_CLASS -> listOf(LibExc.REE)
            id in INTERRUPTED -> listOf(LibExc.INTR)
            else -> null
        }
    }

    /** The entries of the sidecar operations, their tasks, the ledger and the diagnostics, as one module. */
    fun run(): Module {
        collectStringTypes()
        val entries =
            sidecar.operations.mapNotNull { op ->
                val fn = resolver.entryOf[op.name.text] ?: return@mapNotNull null
                val decl = ctx.index.functions[fn] ?: return@mapNotNull null
                ClientUnit(this, op.name.text, op).entry(decl, op)
            }
        val taskList = tasks.values.filterNotNull()
        val ledger = ClientAudit(this, entries, taskList).run()
        return Module(
            id = sidecar.modules.single().fqn.text,
            types = ctx.types,
            state = emptyList(),
            classes = ctx.classes,
            ghost = null,
            operations = emptyList(),
            strayActions = emptyList(),
            firNodeCount = ctx.index.count,
            ledger = ledger,
            diagnostics = ctx.diagnostics + diagnostics,
            kind = ModuleKind.CLIENT,
            file = file.sourceFile?.path ?: file.name,
            enums = enums.values.sortedBy { e -> sidecar.enums.indexOfFirst { it.name.text == e.name } },
            obligations = obligations,
            thrown = thrown,
            entries = entries,
            tasks = taskList,
        )
    }

    /**
     * The abstract types of String values (DESIGN-v0.md 2.13) in the module class: a trusted argument or result fixes
     * its slot to the sidecar type; locals, helper parameters, returns, when branches and comparisons join slots.
     */
    private fun collectStringTypes() {
        ctx.walkConstraints(listOf(ctx.moduleClass)) { c ->
            val decl = trustedOf(c) ?: return@walkConstraints
            val kotlinArgs = c.arguments
            decl.named.forEachIndexed { i, p ->
                val t = p?.type?.text?.removeSuffix("?") ?: return@forEachIndexed
                if (sidecar.types.any { it.name.text == t }) kotlinArgs.getOrNull(i)?.let { ctx.abstraction.fix(ctx.slotOf(it), t, ctx.posOf(it)) }
            }
            decl.returns.singleOrNull()?.type?.text?.removeSuffix("?")?.takeIf { t -> sidecar.types.any { it.name.text == t } }?.let { ctx.abstraction.fix(c, it, ctx.posOf(c)) }
        }
    }

    /** The trusted declaration [c] calls: a trusted fun, or the invoke of a trusted value read through a path. */
    fun trustedOf(c: FirFunctionCall): TrustedDecl? {
        val sym = ctx.calleeOf(c) as? FirNamedFunctionSymbol ?: return null
        trustedFuns[idOf(sym)]?.let { return it }
        if (!isInvoke(sym)) return null
        val receiver = unwrap(c.dispatchReceiver) as? FirPropertyAccessExpression ?: return null
        return (ctx.calleeOf(receiver) as? FirPropertySymbol)?.let { trustedValues[it.callableId.toString()] }
    }

    fun isInvoke(sym: FirNamedFunctionSymbol): Boolean =
        sym.callableId.callableName.asString() == "invoke" && sym.callableId.classId?.let { it.packageFqName.asString() == "kotlin" && it.shortClassName.asString().startsWith("Function") } == true

    /** The client model of [sym], if any. */
    fun clientModel(sym: FirCallableSymbol<*>): ClientModel? {
        val id = sym.callableId ?: return null
        val owner = id.classId?.asString() ?: id.packageName.asString().replace('.', '/')
        return ClientModels.lookup(owner, id.callableName.asString())
    }
}

/**
 * One unit of a client module: an entry or a task (DESIGN-E 6.1, 3.4). It has its own names, translated nodes and
 * problems. Every construct the model does not cover becomes an Unsupported statement with its Kotlin position, and the
 * lowering goes on to collect all of them (DESIGN-v0.md 1.5).
 */
private class ClientUnit(
    private val m: ClientLowering,
    private val owner: String,
    private val op: OperationDecl,
    /** For a task: the unit that spawns it, which owns the values the task captures. */
    private val parent: ClientUnit? = null,
) {
    private val ctx = m.ctx
    private val problems = mutableListOf<Unsupported>()
    private val translated = mutableSetOf<Int>()
    private val names = mutableSetOf<String>()
    private var out = mutableListOf<Stmt>()
    private val frames = ArrayDeque<Frame>()
    /**
     * The conditions that hold where a statement is lowered (DESIGN-E 3.4 part 3). A task keeps only those over the
     * values it captures, which are Kotlin vals (a captured var is unsupported): no assignment between the test and the
     * spawn can change them.
     */
    private val conds = ArrayDeque<Expr>()

    /** Locals of Kotlin vals whose value never changes after their Let, with that value (DESIGN-E 3.4 parts 2, 3). */
    private val lets = mutableMapOf<Local, Expr>()

    /** The loops being lowered, innermost last, each with the frame that holds it. */
    private val loopFrames = ArrayDeque<Frame>()

    /** Entry parameters read as values, in materialization order. */
    private val params = linkedMapOf<FirValueParameterSymbol, Local>()

    /** Free values by sidecar name or by Kotlin property path. */
    private val free = linkedMapOf<String, FreeValue>()

    /** Task parameters: the captured symbol, its local, and the caller's value at the spawn. */
    private val captured = linkedMapOf<FirBasedSymbol<*>, Pair<Local, Expr>>()

    /** The key the unit's calls must use (DESIGN-E appendix B item 7): the entry's key parameter or the task's. */
    private var key: Local? = null

    // ---- bookkeeping ----

    private fun mark(e: FirElement?) {
        e?.let { ctx.index.id(it) }?.let { translated += it }
    }

    /** Marks [e] and the nodes below it, except lambdas a task or a no-effect callback owns. */
    private fun markAll(e: FirElement?) {
        e ?: return
        e.accept(
            object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    if (element in m.noEffect || element is FirAnonymousFunction && m.tasks.containsKey(element)) return
                    mark(element)
                    element.acceptChildren(this)
                }
            },
        )
    }

    /** The helpers this unit inlined. */
    private val inlined = mutableSetOf<FirNamedFunction>()

    /** Marks [root]'s subtree except no-effect callbacks and task lambdas other than [own]. */
    private fun markUnit(
        root: FirElement,
        own: FirAnonymousFunction?,
    ) {
        root.accept(
            object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    if (element in m.noEffect || element is FirAnonymousFunction && element !== own && m.tasks.containsKey(element)) return
                    mark(element)
                    element.acceptChildren(this)
                }
            },
        )
    }

    /** Marks the smart casts around an expression; the lowering reads the expression under them. */
    private fun markCasts(e: FirExpression) {
        var x: FirExpression = e
        while (x is FirSmartCastExpression) {
            mark(x)
            x = x.originalExpression
        }
    }

    private fun unsupported(
        what: String,
        reason: String,
        e: FirElement,
    ): Nothing? {
        markAll(e)
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

    private fun fresh(base: String): String {
        var name = base
        var suffix = 'b'
        while (!names.add(name)) name = base + suffix++
        return name
    }

    private val suffix get() = frames.lastOrNull()?.suffix.orEmpty()

    /** The local of Kotlin declaration [name] at [pos]: `<name>__<line>`, with the inline suffix (DESIGN-E 5.1 item 1). */
    private fun declare(
        name: String,
        type: KType,
        pos: Pos,
    ) = Local(fresh("${name}__${pos.line}$suffix"), type, pos)

    private fun temp(
        prefix: String,
        type: KType,
        pos: Pos,
    ) = Local(fresh("${prefix}_${pos.line}_${pos.col}$suffix"), type, pos)

    // ---- types ----

    private fun ktype(
        t: ConeKotlinType,
        slot: Any?,
        e: FirElement,
    ): KType? {
        val low = t.lowerBoundIfFlexible()
        val nullable = low.isMarkedNullable
        if (low is ConeTypeParameterType) return KType.IntT
        val id = low.classId ?: return unsupported("value of type $low", "the type is not part of the model", e)
        return when (id.asString()) {
            "kotlin/Long", "kotlin/Int" -> if (nullable) unsupported("nullable ${id.shortClassName}", "the model has Int values without null", e) else KType.IntT
            "kotlin/Boolean" -> if (nullable) unsupported("nullable Boolean", "the model has Boolean values without null", e) else KType.BoolT
            "kotlin/String" -> {
                val types = slot?.let { ctx.abstraction.types(it) }.orEmpty()
                if (types.size == 1) KType.Abs(types.keys.single(), nullable) else unsupported("String value", if (types.isEmpty()) "no trusted call fixes its sidecar type" else "used as ${types.keys.sorted().joinToString(" and ")}", e)
            }
            else ->
                m.enums[id]?.let { KType.EnumT(it.name, nullable) }
                    ?: ctx.classOfType(low)?.let { KType.RefT(it.name, nullable) }
                    ?: unsupported("value of type ${id.asFqNameString()}", "the type is not part of the model (DESIGN-E 6.2: Int, Bool, sidecar types, enums and classes)", e)
        }
    }

    private fun canBeNull(t: KType) = (t as? KType.Abs)?.nullable ?: (t as? KType.RefT)?.nullable ?: (t as? KType.EnumT)?.nullable ?: false

    private fun nullable(t: KType): KType =
        when (t) {
            is KType.Abs -> t.copy(nullable = true)
            is KType.RefT -> t.copy(nullable = true)
            is KType.EnumT -> t.copy(nullable = true)
            else -> t
        }

    /** The initial value of a local a branch assigns: null, 0 or false; null for a type without one. */
    private fun default(
        t: KType,
        pos: Pos,
    ): Expr? =
        when {
            t == KType.IntT -> IntLit(0, pos)
            t == KType.BoolT -> BoolLit(false, pos)
            canBeNull(t) -> NullLit(t, pos)
            // An enum's first entry: every branch overwrites it, since a value when has an else branch.
            t is KType.EnumT -> m.enums.values.find { it.name == t.name }?.entries?.firstOrNull()?.let { EnumLit(t, it, pos) }
            else -> null
        }

    // ---- units ----

    fun entry(
        decl: FirNamedFunction,
        op: OperationDecl,
    ): EntryFun {
        val scope = Scope(null)
        decl.valueParameters.forEach { scope.vars[it.symbol] = Bound.Param(it.symbol) }
        mark(decl)
        m.lowered += decl
        decl.valueParameters.forEach(::markAll)
        // DESIGN-E 3.1: the parameter of the obligation's key type is the key k of HOLDS.
        val keyType = m.obligations.firstOrNull()?.types?.firstOrNull()
        val keyParams = decl.valueParameters.filter { p -> ctx.typeOf(p).lowerBoundIfFlexible().classId?.asString() == "kotlin/String" && (ctx.abstraction.types(p.symbol).keys.singleOrNull()?.let { KType.Abs(it, false) } == keyType) }
        keyParams.singleOrNull()?.let { p -> key = Local("k", keyType!!, ctx.posOf(p)).also { names += "k"; params[p.symbol] = it } }
        val result = op.result?.let { r -> ktype(decl.returnTypeRef.coneType, decl.symbol, decl)?.let { Local(r.name.text, it, ctx.posOf(decl)) } }
        names += listOfNotNull(op.result?.name?.text)
        frames.addLast(Frame(decl.symbol, ""))
        mark(decl.body)
        out += block(decl.body?.statements.orEmpty(), scope)
        frames.removeLast()
        val ok = problems.isEmpty()
        // An unsupported unit is not emitted (W12): every node of its code counts as handled (DESIGN-v0.md 1.5).
        if (!ok) (listOf(decl) + inlined).forEach { markUnit(it, null) }
        val namedFree = op.free.flatMap { item -> item.names.map { it.text } }
        return EntryFun(
            name = owner,
            kotlin = "fun ${(op.selector).target.text}",
            pos = ctx.posOf(decl),
            params = params.values.toList(),
            free = free.values.sortedBy { fv -> namedFree.indexOf(fv.sidecarName).let { if (it < 0) Int.MAX_VALUE else it } },
            result = result,
            body = if (ok) out else problems.toList(),
            status = if (ok) Status.Supported else Status.Unsupported(problems.map { unsupportedDiagnostic(it, owner) }),
            translated = translated,
        )
    }

    // ---- statements ----

    /** Lowers [stmts] in a child scope of [scope]. */
    private fun block(
        stmts: List<FirStatement>,
        scope: Scope,
    ): List<Stmt> =
        collect {
            val inner = Scope(scope)
            stmts.forEach { statement(it, inner) }
        }

    private fun statement(
        s: FirStatement,
        scope: Scope,
    ) {
        when (s) {
            is FirProperty -> localVal(s, scope)
            is FirVariableAssignment -> assignment(s, scope)
            is FirReturnExpression -> returnStmt(s, scope)
            is FirWhenExpression -> whenStmt(s, scope) { b -> block(b.statements, scope) }
            is FirWhileLoop -> whileLoop(s, scope)
            is FirDoWhileLoop -> unsupported("do-while loop", "the client lowering translates `while` loops (DESIGN-E 6.4 row 7)", s)
            is FirBreakExpression -> breakStmt(s)
            is FirContinueExpression -> unsupported("continue", "a `continue` is not translated (DESIGN-E 6.4 row 7)", s)
            is FirTryExpression -> tryStmt(s, scope, null)
            is FirThrowExpression -> throwStmt(s, scope)
            is FirBlock ->
                if (isIncDec(s)) {
                    incDec(s, scope, valueUsed = false)
                } else {
                    mark(s)
                    out += block(s.statements, scope)
                }
            is FirExpression -> valueInto(s, scope, Sink.Discard)
            else -> unsupported(s::class.simpleName ?: "statement", "this FIR node is not translated by the client lowering", s)
        }
    }

    private fun localVal(
        p: FirProperty,
        scope: Scope,
    ) {
        mark(p)
        val init = p.initializer ?: return run { unsupported("local ${p.name} without initializer", "a local is translated with its value", p) }
        if (p.delegate != null) return run { unsupported("delegated local ${p.name}", "a delegated local is not translated", p) }
        val pos = ctx.posOf(p)
        val type = ktype(ctx.typeOf(p), p.symbol, p) ?: return run { markAll(init) }
        val v = declare(p.name.asString(), type, pos)
        val x = unwrap(init)
        when {
            // A value with no branches is the local's value; a branching value assigns a mutable local in each branch.
            simple(x) -> {
                val e = expr(init, scope, type) ?: return
                out += Let(v, e, p.isVar, pos)
                if (!p.isVar) lets[v] = e
            }
            // A Kotlin val: its producer (call, helper, constructor) declares it, and branches assign it (valueInto).
            !p.isVar -> valueInto(init, scope, Sink.Declare(v))
            else -> {
                // A var of a non-null object type starts as null in the model; Kotlin assigns it before every read.
                val w = if (type is KType.RefT) v.copy(type = nullable(type)) else v
                val d = default(w.type, pos) ?: return run { unsupported("local ${p.name}", "a var of type ${typeName(type)} gets a call's value only with an initial value to overwrite", p) }
                out += Let(w, d, true, pos)
                valueInto(init, scope, Sink.Into(w, d is NullLit))
                scope.vars[p.symbol] = Bound.Value(Var(w, pos))
                return
            }
        }
        scope.vars[p.symbol] = Bound.Value(Var(v, pos))
    }

    private fun typeName(t: KType): String =
        when (t) {
            KType.IntT -> "Int"
            KType.BoolT -> "Bool"
            is KType.Abs -> t.name
            is KType.RefT -> t.cls
            is KType.EnumT -> t.name
        }

    private fun isConstant(e: Expr) = e is IntLit || e is BoolLit || e is EnumLit || e is NullLit

    /** True if [x] lowers to a value without statements of its own branches: no when, try, elvis, scope function or call that may throw. */
    private fun simple(x: FirExpression?): Boolean =
        when (x) {
            is FirWhenExpression, is FirTryExpression, is FirElvisExpression, is FirSafeCallExpression -> false
            is FirFunctionCall -> helperOf(x) == null && m.trustedOf(x) == null && !isInvoke(x) && ctx.constructedClass(ctx.calleeOf(x)) == null && ctx.calleeOf(x)?.let(m::clientModel) == null && scopeName(x) == null
            else -> true
        }

    private fun assignment(
        a: FirVariableAssignment,
        scope: Scope,
    ) {
        mark(a)
        val lhs = a.lValue as? FirQualifiedAccessExpression ?: return run { unsupported("assignment", "the target is not a variable", a) }
        mark(lhs)
        val sym = ctx.calleeOf(lhs)
        val bound = sym?.let { scope.local(it) }
        val local = ((bound as? Bound.Value)?.e as? Var)?.local
        if (local == null || sym !is FirPropertySymbol || !sym.isLocal) return run { unsupported("assignment to ${sourceText(lhs)}", "the client lowering assigns only locals of the unit (DESIGN-E 6.4)", a) }
        val pos = ctx.posOf(a)
        if (simple(unwrap(a.rValue))) {
            val e = expr(a.rValue, scope, local.type) ?: return
            out += Assign(local, e, pos)
        } else {
            valueInto(a.rValue, scope, Sink.Into(local, false))
        }
    }

    private fun returnStmt(
        r: FirReturnExpression,
        scope: Scope,
    ) {
        mark(r)
        val target = r.target.labeledElement
        val frame = frames.last()
        val explicit = r.source?.kind !is KtFakeSourceElementKind
        when {
            target is FirNamedFunction && target.symbol == frame.fn -> {
                val unitValue = r.result.resolvedType.lowerBoundIfFlexible().classId?.asString() == "kotlin/Unit"
                if (unitValue) {
                    valueInto(r.result, scope, Sink.Discard)
                    out += Return(null, ctx.posOf(r))
                } else {
                    valueInto(r.result, scope, Sink.Ret(ctx.posOf(r)))
                }
            }
            target is FirAnonymousFunction && !explicit -> valueInto(r.result, scope, Sink.Discard)
            target is FirAnonymousFunction -> unsupported("return@${target.label?.name ?: "lambda"}", "a labeled return to a lambda is not translated (DESIGN-E 6.4 row 1)", r)
            else -> unsupported("return from ${(target as? FirNamedFunction)?.name ?: "an outer function"}", "a return that leaves more than the innermost function is not translated", r)
        }
    }

    private fun breakStmt(b: FirBreakExpression) {
        mark(b)
        val frame = loopFrames.lastOrNull()
        if (frame == null || frame !== frames.last() || b.target.labeledElement !== currentLoop.lastOrNull()) {
            unsupported("break", "a break leaves only the innermost loop of its function (DESIGN-E 6.4 row 7)", b)
            return
        }
        out += Break(ctx.posOf(b))
    }

    private val currentLoop = ArrayDeque<FirElement>()

    private fun whileLoop(
        w: FirWhileLoop,
        scope: Scope,
    ) {
        mark(w)
        val pos = ctx.posOf(w)
        var cond: Expr? = null
        val condStmts = collect { cond = expr(w.condition, scope, KType.BoolT) }
        if (condStmts.isNotEmpty()) unsupported("loop condition with an effect", "a loop condition is evaluated each iteration; it must be pure", w.condition)
        loopFrames.addLast(frames.last())
        currentLoop.addLast(w)
        mark(w.block)
        val body = block(w.block.statements, scope)
        currentLoop.removeLast()
        loopFrames.removeLast()
        val c = cond ?: return
        // DESIGN-E 6.6 fact 4: a measure `bound - v` for a condition `v < bound`; Silicon checks it decreases.
        val measure =
            (c as? Cmp)?.let { cmp ->
                when (cmp.op) {
                    CmpOp.LT -> Arith(ArithOp.MINUS, cmp.b, cmp.a, null, cmp.pos)
                    CmpOp.GT -> Arith(ArithOp.MINUS, cmp.a, cmp.b, null, cmp.pos)
                    else -> null
                }
            }
        out += While(c, body, m.loops[w], measure, pos)
    }

    private fun whenStmt(
        w: FirWhenExpression,
        scope: Scope,
        branch: (FirBlock) -> List<Stmt>,
    ) {
        mark(w)
        if (w.subjectVariable != null) {
            unsupported("when with subject", "the client lowering translates `if` and `when` without subject", w)
            return
        }
        out += chain(w, 0, scope, branch)
    }

    private fun chain(
        w: FirWhenExpression,
        i: Int,
        scope: Scope,
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
            val c = expr(b.condition, scope, KType.BoolT) ?: return@collect
            // Each conjunct holds in the then branch (DESIGN-E 3.4 part 3).
            val holds = conjuncts(c)
            conds.addAll(holds)
            val then = branch(b.result)
            repeat(holds.size) { conds.removeLast() }
            conds.addLast(Not(c, c.pos))
            val els = chain(w, i + 1, scope, branch)
            conds.removeLast()
            // An if whose branches are both empty (a log call) has no effect; an empty then branch flips the test.
            val at = if (i == 0) ctx.posOf(w) else ctx.posOf(b.condition)
            when {
                then.isNotEmpty() -> out += If(c, then, els, at)
                els.isNotEmpty() -> out += If(Not(c, c.pos), els, emptyList(), at)
            }
        }

    private fun tryStmt(
        t: FirTryExpression,
        scope: Scope,
        sink: Sink?,
    ) {
        mark(t)
        mark(t.tryBlock)
        val body = collect { lambdaLike(t.tryBlock.statements, Scope(scope), sink) }
        val catches =
            t.catches.mapNotNull { c ->
                mark(c)
                markAll(c.parameter)
                mark(c.block)
                val type = c.parameter.returnTypeRef.coneType
                val kinds = type.lowerBoundIfFlexible().classId?.asString()?.let(m::catchKinds) ?: return@mapNotNull unsupported("catch (${c.parameter.name}: ${type.lowerBoundIfFlexible().classId?.asFqNameString()})", "the model cannot tell which library exceptions this type catches; it maps Throwable, Exception, RuntimeException, Error, RejectedExecutionException and InterruptedException (DESIGN-E 3.5)", c.parameter)
                val inner = Scope(scope)
                inner.vars[c.parameter.symbol] = Bound.Exception
                Catch(type.lowerBoundIfFlexible().classId!!.shortClassName.asString(), kinds, collect { lambdaLike(c.block.statements, inner, sink) }, ctx.posOf(c.parameter))
            }
        val fin =
            t.finallyBlock?.let { f ->
                mark(f)
                val stmts = block(f.statements, scope)
                if (stmts.walk().any { it is Return || it is Break }) unsupported("return or break inside a finally", "a jump inside a finally block is not translated (DESIGN-E 6.4 row 3)", f)
                stmts
            }
        out += Try(body, catches, fin, ctx.posOf(t))
    }

    /** The statements of a try, catch or lambda body; the last one's value goes to [sink] (null: a statement). */
    private fun lambdaLike(
        stmts: List<FirStatement>,
        scope: Scope,
        sink: Sink?,
    ) {
        stmts.forEachIndexed { i, s ->
            if (i < stmts.lastIndex || sink == null) return@forEachIndexed statement(s, scope)
            val last = (s as? FirReturnExpression)?.takeIf { it.target.labeledElement is FirAnonymousFunction && it.source?.kind is KtFakeSourceElementKind }
            if (last != null) {
                mark(last)
                valueInto(last.result, scope, sink)
            } else if (s is FirExpression) {
                valueInto(s, scope, sink)
            } else {
                statement(s, scope)
            }
        }
    }

    private fun throwStmt(
        t: FirThrowExpression,
        scope: Scope,
    ) {
        mark(t)
        val pos = ctx.posOf(t)
        val x = unwrap(t.exception)
        // Rethrow of the caught exception (DESIGN-E 6.3: Throw with no kind).
        if (x is FirPropertyAccessExpression && ctx.calleeOf(x)?.let { scope.local(it) } == Bound.Exception) {
            markAll(x)
            out += Throw(null, pos)
            return
        }
        val kind = m.thrownKind(t.exception) ?: return run { unsupported("throw ${sourceText(t.exception)}", "a thrown exception is a constructor call of an Exception class whose first argument, if any, is an enum entry: the error code (DESIGN-E 3.5)", t) }
        val call = x as FirFunctionCall
        mark(call)
        call.arguments.forEach { dropped(it, scope) }
        out += Throw(kind, pos)
    }

    // ---- values ----

    /** Where a value goes: dropped, returned, assigned to a mutable local, or declared as a new local. */
    private sealed interface Sink {
        data object Discard : Sink

        data class Ret(
            val pos: Pos,
        ) : Sink

        /** Assigns [v]; [initNull] says v starts as null, so a null value needs no assignment. */
        data class Into(
            val v: Local,
            val initNull: Boolean,
        ) : Sink

        data class Declare(
            val v: Local,
        ) : Sink
    }

    private fun sinkType(sink: Sink): KType? =
        when (sink) {
            is Sink.Into -> sink.v.type
            is Sink.Declare -> sink.v.type
            else -> null
        }

    private fun emit(
        sink: Sink,
        e: Expr,
        pos: Pos,
    ) {
        when (sink) {
            Sink.Discard -> Unit
            is Sink.Ret -> out += Return(e, sink.pos)
            is Sink.Into -> {
                if (e is NullLit && sink.initNull) return
                out += Assign(sink.v, e, pos)
            }
            is Sink.Declare -> out += Let(sink.v, e, false, pos)
        }
    }

    /** The local a branching value lands in: the sink's own for a declare or an assign, else a new temp of [type]. */
    private fun outLocal(
        sink: Sink,
        type: KType,
        prefix: String,
        pos: Pos,
        assigns: Boolean = false,
    ): Local =
        when {
            sink is Sink.Declare -> sink.v
            sink is Sink.Into && assigns -> sink.v
            else -> temp(prefix, type, pos)
        }

    /** Lowers [e] for its effects and puts its value into [sink] (DESIGN-E 6.4: branches, calls, inlined helpers). */
    private fun valueInto(
        e: FirExpression,
        scope: Scope,
        sink: Sink,
    ) {
        val x = unwrap(e)!!
        markCasts(e)
        val pos = ctx.posOf(x)
        // A declared local that branches assign starts with its initial value.
        val branching = x is FirWhenExpression || x is FirTryExpression || x is FirElvisExpression || x is FirSafeCallExpression ||
            x is FirFunctionCall && ctx.calleeOf(x)?.let(m::clientModel)?.role == ClientRole.GET_OR_ELSE
        if (sink is Sink.Declare && branching) {
            val d = default(sink.v.type, pos) ?: return run { unsupported("value with branches", "a ${typeName(sink.v.type)} value with branches has no initial value for them to overwrite", x) }
            out += Let(sink.v, d, true, pos)
            return valueInto(e, scope, Sink.Into(sink.v, d is NullLit))
        }
        when {
            x is FirWhenExpression && sink !is Sink.Discard -> {
                if (x.branches.none { it.condition is FirElseIfTrueCondition }) return run { unsupported("if without else as a value", "a value if has an else branch", x) }
                whenStmt(x, scope) { b -> collect { lambdaLike(b.statements, Scope(scope), sink) } }
            }
            x is FirWhenExpression -> whenStmt(x, scope) { b -> block(b.statements, scope) }
            x is FirTryExpression -> tryStmt(x, scope, sink.takeIf { it !is Sink.Discard })
            x is FirElvisExpression -> elvis(x, scope, sink)
            x is FirSafeCallExpression -> safeCall(x, scope, sink)
            x is FirReturnExpression -> statement(x, scope)
            x is FirThrowExpression -> throwStmt(x, scope)
            x is FirBlock && isIncDec(x) -> incDec(x, scope, valueUsed = sink != Sink.Discard)?.let { emit(sink, it, pos) }
            x is FirFunctionCall -> call(x, scope, sink)
            sink is Sink.Discard -> dropped(x, scope)
            else -> expr(x, scope, sinkType(sink))?.let { emit(sink, it, pos) }
        }
    }

    /** `a ?: b`: a's value if it is not null, else b's. A left side `a?.f { ... }` whose f never returns is an if. */
    private fun elvis(
        e: FirElvisExpression,
        scope: Scope,
        sink: Sink,
    ) {
        mark(e)
        val pos = ctx.posOf(e)
        val lhs = unwrap(e.lhs)
        val nothing = lhs is FirSafeCallExpression && (lhs.selector as? FirExpression)?.resolvedType?.lowerBoundIfFlexible()?.classId?.asString() == "kotlin/Nothing"
        if (nothing) {
            mark(lhs)
            val a = expr(lhs.receiver, scope, null) ?: return
            val then = collect { selector(lhs, a, scope, Sink.Discard) }
            val els = collect { valueInto(e.rhs, scope, sink) }
            out += If(Cmp(CmpOp.NE, a, NullLit(nullable(a.type), pos), pos), then, els, pos)
            return
        }
        val type = ktype(e.resolvedType, ctx.slotOf(e), e) ?: return
        val t = temp("elvis", nullable(type), pos)
        out += Let(t, NullLit(nullable(type), pos), true, pos)
        valueInto(e.lhs, scope, Sink.Into(t, true))
        val els = collect { valueInto(e.rhs, scope, sink) }
        val then = collect { emit(sink, Var(t, pos), pos) }
        out += If(Cmp(CmpOp.NE, Var(t, pos), NullLit(nullable(type), pos), pos), then, els, pos)
    }

    /** `a?.s`: s with a as its receiver if a is not null. */
    private fun safeCall(
        e: FirSafeCallExpression,
        scope: Scope,
        sink: Sink,
    ) {
        mark(e)
        val pos = ctx.posOf(e)
        val a = expr(e.receiver, scope, null) ?: return
        if (sink !is Sink.Discard) return run { unsupported("safe call as a value", "a safe call is translated as a statement or as the left side of an elvis whose right side runs when it is null", e) }
        val then = collect { selector(e, a, scope, Sink.Discard) }
        out += if (canBeNull(a.type)) listOf(If(Cmp(CmpOp.NE, a, NullLit(nullable(a.type), pos), pos), then, emptyList(), pos)) else then
    }

    /** The selector of safe call [e] with its subject bound to [a]. */
    private fun selector(
        e: FirSafeCallExpression,
        a: Expr,
        scope: Scope,
        sink: Sink,
    ) {
        subjects[e.checkedSubjectRef.value] = a
        (e.selector as? FirExpression)?.let { valueInto(it, scope, sink) } ?: statement(e.selector, scope)
    }

    private val subjects = IdentityHashMap<FirCheckedSafeCallSubject, Expr>()

    private fun scopeName(c: FirFunctionCall): String? = ctx.calleeOf(c)?.callableId?.takeIf { it.classId == null && it.packageName.asString() == "kotlin" }?.callableName?.asString()?.takeIf { it in setOf("let", "run") }

    /** A call: a scope function, a helper, a trusted call, a client model, a logger, a constructor. */
    private fun call(
        c: FirFunctionCall,
        scope: Scope,
        sink: Sink,
    ) {
        val pos = ctx.posOf(c)
        val sym = ctx.calleeOf(c)
        val helper = helperOf(c)
        val trusted = trustedAt(c, scope)
        val model = sym?.let(m::clientModel)
        when {
            scopeName(c) != null -> scopeCall(c, scope, sink)
            trusted == null && isInvoke(c) -> unsupported("call of the function value ${sourceText(c.dispatchReceiver ?: c)}", "the function value is not a trusted value (DESIGN-E 6.2: trusted value)", c)
            helper != null -> inline(c, helper, scope, sink)
            trusted != null -> trustedCall(c, trusted, scope, sink)
            model != null -> modelCall(c, model, scope, sink)
            sym != null && ctx.entriesOf(sym).any { it.role == Role.NO_EFFECT } -> {
                val entry = ctx.entriesOf(sym).first()
                mark(c)
                if (entry.model !in m.uses) unsupported("trusted model ${entry.model}", "the call needs model ${entry.model}, which the sidecar does not `use`", c)
                markAll(c.dispatchReceiver)
                c.arguments.forEach { dropped(it, scope, "logging call") }
                if (sink != Sink.Discard) unsupported("value of ${sym.callableId?.callableName}", "a ${entry.model} call has no modeled value", c)
            }
            ctx.constructedClass(sym) != null -> {
                val cls = ctx.constructedClass(sym)!!
                if (sink == Sink.Discard) return run { unsupported("new ${cls.name} that is not used", "a constructed object is a value", c) }
                val v = outLocal(sink, KType.RefT(cls.name, false), "new", pos)
                if (!newObject(c, cls, v, scope)) return
                if (sink !is Sink.Declare) emit(sink, Var(v, pos), pos)
            }
            sink == Sink.Discard && sym?.callableId?.classId?.asString() in PURE_OWNERS -> dropped(c, scope)
            sink == Sink.Discard -> unsupported("call of ${sym?.callableId?.asSingleFqName() ?: sourceText(c)}", "the call is outside the model: no trusted declaration, helper or built-in model (DESIGN-E 6.4)", c)
            else -> expr(c, scope, sinkType(sink))?.let { emit(sink, it, pos) }
        }
    }

    private fun isInvoke(c: FirFunctionCall): Boolean = (ctx.calleeOf(c) as? FirNamedFunctionSymbol)?.let(m::isInvoke) == true

    /** The trusted declaration [c] calls; an invoke may read its trusted value through helper parameters. */
    private fun trustedAt(
        c: FirFunctionCall,
        scope: Scope,
    ): TrustedDecl? = m.trustedOf(c) ?: if (isInvoke(c)) trustedValueOf(c.dispatchReceiver, scope) else null

    private fun trustedValueOf(
        e: FirExpression?,
        scope: Scope,
    ): TrustedDecl? {
        val x = unwrap(e) as? FirPropertyAccessExpression ?: return null
        val sym = ctx.calleeOf(x)
        if (sym is FirPropertySymbol && !sym.isLocal) return m.trustedValues[sym.callableId.toString()]
        return when (val b = sym?.let { lookupLocal(it, scope) }) {
            is Bound.Arg -> trustedValueOf(b.arg, b.scope)
            else -> null
        }
    }

    /** `x.let { ... }` and `run { ... }` (EXACTLY_ONCE lambdas): the lambda in place; `it` is x. */
    private fun scopeCall(
        c: FirFunctionCall,
        scope: Scope,
        sink: Sink,
    ) {
        mark(c)
        val name = scopeName(c)!!
        val arg = c.arguments.singleOrNull() as? FirAnonymousFunctionExpression
        val lam = arg?.anonymousFunction ?: return run { unsupported("$name argument", "$name is translated with a lambda literal", c) }
        mark(arg)
        mark(lam)
        mark(lam.body)
        lam.valueParameters.forEach(::mark)
        val inner = Scope(scope)
        when (name) {
            "let" -> {
                val receiver = c.extensionReceiver ?: return
                val subject = (unwrap(receiver) as? FirCheckedSafeCallSubject)?.let { mark(receiver); subjects[it] } ?: expr(receiver, scope, null) ?: return
                lam.valueParameters.firstOrNull()?.let { inner.vars[it.symbol] = Bound.Value(subject) }
            }
            "run" -> if (c.extensionReceiver != null && unwrap(c.extensionReceiver) !is FirThisReceiverExpression) return run { unsupported("run with a receiver", "the client lowering translates `run { }` on this", c) } else markAll(c.extensionReceiver)
        }
        lambdaLike(lam.body?.statements.orEmpty(), inner, sink.takeIf { it != Sink.Discard })
    }

    /** The module-class function [c] calls, if it is one the lowering inlines (DESIGN-E JE2). */
    private fun helperOf(c: FirFunctionCall): FirNamedFunction? {
        val sym = ctx.calleeOf(c) as? FirNamedFunctionSymbol ?: return null
        val f = ctx.index.functions[sym] ?: return null
        return f.takeIf { sym.callableId.classId == ctx.moduleClass.symbol.classId && it.body != null }
    }

    /** Inlines helper [f] at [c] (DESIGN-E 6.4, JE2): its parameters bind the arguments; its returns leave the block. */
    private fun inline(
        c: FirFunctionCall,
        f: FirNamedFunction,
        scope: Scope,
        sink: Sink,
    ) {
        mark(c)
        markAll(c.dispatchReceiver)
        val pos = ctx.posOf(c)
        if (frames.any { it.fn == f.symbol }) return run { unsupported("recursive call of ${f.name}", "an inlined helper's call graph has no cycle (DESIGN-E 6.3 rule 2)", c) }
        val inner = Scope(null)
        val mapping = c.resolvedArgumentMapping.orEmpty()
        for (param in f.valueParameters) {
            val arg = mapping.entries.find { it.value.symbol == param.symbol }?.key ?: param.defaultValue
            if (arg == null) return run { unsupported("argument ${param.name}", "the call gives no value", c) }
            inner.vars[param.symbol] = bindArg(arg, scope, param) ?: return
        }
        // Several inline sites of a helper in the module: its locals carry the call line (DESIGN-E 5.1 item 1).
        val sites = ctx.index.nodes.count { it is FirFunctionCall && ctx.calleeOf(it) == f.symbol }
        val type = f.returnTypeRef.coneType.lowerBoundIfFlexible()
        val unit = type.classId?.asString().let { it == "kotlin/Unit" || it == "kotlin/Nothing" }
        val outType = if (unit) null else ktype(type, f.symbol, c) ?: return
        val result = outType?.let { outLocal(sink, it, "out", pos) }
        mark(f)
        m.lowered += f
        inlined += f
        f.valueParameters.forEach(::markAll)
        mark(f.body)
        frames.addLast(Frame(f.symbol, (frames.last().suffix + if (sites > 1) "_at${pos.line}" else "")))
        val body = block(f.body?.statements.orEmpty(), inner)
        frames.removeLast()
        out += Inline(f.name.asString(), result, body, pos)
        if (result != null && sink !is Sink.Declare) emit(sink, Var(result, pos), pos)
    }

    /**
     * What a helper parameter binds: a stable argument (a literal, a read of a parameter, a local or a val path, a
     * lambda literal) stays unevaluated and is lowered where it is read; any other argument runs here, into a temp.
     */
    private fun bindArg(
        arg: FirExpression,
        scope: Scope,
        param: FirValueParameter,
    ): Bound? {
        val x = unwrap(arg)!!
        if (stable(x)) {
            if (x !is FirAnonymousFunctionExpression) markAll(arg) else mark(arg)
            return Bound.Arg(arg, scope)
        }
        val type = ktype(ctx.typeOf(param), param.symbol, arg) ?: return null
        val t = temp("arg", type, ctx.posOf(arg))
        valueInto(arg, scope, Sink.Declare(t))
        return Bound.Value(Var(t, t.pos))
    }

    private fun stable(x: FirExpression): Boolean =
        when (x) {
            is FirLiteralExpression, is FirAnonymousFunctionExpression, is FirThisReceiverExpression, is FirResolvedQualifier -> true
            is FirSmartCastExpression -> stable(x.originalExpression)
            is FirPropertyAccessExpression -> (x.dispatchReceiver?.let { stable(unwrap(it)!!) } ?: true) && (x.extensionReceiver == null)
            is FirFunctionTypeConversionExpression -> stable(x.expression)
            else -> false
        }

    // ---- trusted calls ----

    private fun trustedCall(
        c: FirFunctionCall,
        t: TrustedDecl,
        scope: Scope,
        sink: Sink,
    ) {
        mark(c)
        val pos = ctx.posOf(c)
        val sym = ctx.calleeOf(c) as FirNamedFunctionSymbol
        // The name a reader knows: Class.member, or a top-level function's own name.
        val segments = t.fn.text.split('.')
        val name = if (segments.size >= 2 && segments[segments.size - 2].first().isUpperCase()) segments.takeLast(2).joinToString(".") else t.shortName
        // The receiver: the trusted value read for an invoke, else the object whose function is called; a stable path.
        val receiver = c.dispatchReceiver
        receiver?.let { r ->
            if (!m.isInvoke(sym) && stablePath(r, scope) == null) unsupported("receiver of ${name}", "a trusted call's receiver is a parameter or a path of vals from this", r)
            markAll(r)
        }
        val args = mutableListOf<Expr>()
        val kotlinArgs = c.arguments
        if (kotlinArgs.size != t.params.size) return run { unsupported("call of $name", "the call has ${kotlinArgs.size} argument(s); the trusted declaration ${t.params.size}", c) }
        kotlinArgs.forEachIndexed { i, a ->
            val named = t.named.getOrNull(i)
            if (named == null) {
                dropped(a, scope)
                return@forEachIndexed
            }
            val type = m.sidecarType(named.type.text)
            // A null argument where the declaration takes no null: a branch the code's own null test makes dead.
            val v = expr(a, scope, nullable(type)) ?: return@forEachIndexed
            // DESIGN-E appendix B item 7: every call uses the call's key k.
            val keyType = m.obligations.firstOrNull()?.types?.firstOrNull()
            if (keyType != null && type == keyType && !(v is Var && v.local == key)) unsupported("${named.name.text} argument of $name", "a trusted call of the unit uses its key ${key?.name ?: "k"} (DESIGN-E appendix B item 7)", a)
            args += v
        }
        if (args.size != t.named.count { it != null }) return
        val result = t.returns.singleOrNull()
        val obligationUse = t.creates ?: t.consumes
        val needOut = result != null && (sink != Sink.Discard || t.creates?.args?.any { it.text == result.name.text } == true)
        val outV = if (needOut) outLocal(sink, m.sidecarType(result.type.text), "r", pos, assigns = true) else null
        val obligation =
            obligationUse?.let { u ->
                val namedParams = t.named.filterNotNull().map { it.name.text }
                ObligationOp(
                    creates = u === t.creates,
                    obligation = u.obligation.text,
                    args = u.args.map { a -> if (a.text == result?.name?.text) Var(outV!!, pos) else args[namedParams.indexOf(a.text)] },
                    ifNotNull = u.ifNotNull?.let { outV },
                )
            }
        out += Call(listOfNotNull(outV), name, args, t.throwKinds.map { LibExc.valueOf(it.text) }, obligation, pos)
        outV?.let { v ->
            if (sink !is Sink.Declare && sink !is Sink.Into) emit(sink, Var(v, pos), pos)
        }
    }

    /** A stable reference: `this`, a val property path from it, a parameter (through helper bindings); null otherwise. */
    private fun stablePath(
        e: FirExpression,
        scope: Scope,
    ): String? {
        val x = unwrap(e)
        return when (x) {
            is FirThisReceiverExpression -> "this"
            is FirResolvedQualifier -> x.qualifierSymbol?.classId?.asString()
            is FirPropertyAccessExpression -> {
                val sym = ctx.calleeOf(x)
                when {
                    sym is FirValueParameterSymbol || sym is FirPropertySymbol && sym.isLocal ->
                        when (val b = lookup(sym, scope, x)) {
                            is Bound.Arg -> stablePath(b.arg, b.scope)
                            is Bound.Param -> "param ${b.sym.name}"
                            is Bound.Value -> (b.e as? Var)?.local?.name
                            else -> null
                        }
                    sym is FirPropertySymbol && sym.isVal -> x.dispatchReceiver?.let { stablePath(it, scope) }?.let { "$it.${sym.name}" }
                    else -> null
                }
            }
            else -> null
        }
    }

    // ---- client models ----

    private fun modelCall(
        c: FirFunctionCall,
        model: ClientModel,
        scope: Scope,
        sink: Sink,
    ) {
        mark(c)
        val pos = ctx.posOf(c)
        if (model.model !in m.uses) return run { unsupported("trusted model ${model.model}", "the call needs model ${model.model}, which the sidecar does not `use`", c) }
        when (model.role) {
            ClientRole.SPAWN -> {
                if (sink != Sink.Discard) unsupported("value of runAsync", "the future runAsync returns is used only as the receiver of whenComplete (DESIGN-E 3.3 point 2)", c)
                spawn(c, model, scope)
            }
            ClientRole.CALLBACK -> {
                val receiver = unwrap(c.dispatchReceiver) as? FirFunctionCall
                val spawnModel = receiver?.let { ctx.calleeOf(it) }?.let(m::clientModel)
                if (receiver == null || spawnModel?.role != ClientRole.SPAWN) return run { unsupported("whenComplete", "whenComplete is translated on the future of runAsync only", c) }
                if (sink != Sink.Discard) unsupported("value of whenComplete", "its future is not used", c)
                val cb = unwrapTask(c.arguments.singleOrNull()) as? FirAnonymousFunctionExpression ?: return run { unsupported("whenComplete callback", "the callback is a lambda literal", c) }
                c.arguments.single().let { if (it !== cb) mark(it) }
                effect(cb.anonymousFunction, mutableSetOf())?.let { return run { unsupported("whenComplete callback", "the callback has an effect: $it (DESIGN-E 6.4 row 10)", cb) } }
                m.noEffect[cb] = "whenComplete callback: logs only"
                markRegionHelpers(cb.anonymousFunction)
                if (spawnModel.model !in m.uses) return run { unsupported("trusted model ${spawnModel.model}", "the call needs model ${spawnModel.model}, which the sidecar does not `use`", receiver) }
                mark(receiver)
                spawn(receiver, spawnModel, scope)
            }
            ClientRole.CALL -> {
                c.arguments.forEach { dropped(it, scope) }
                markAll(c.dispatchReceiver)
                if (sink != Sink.Discard) unsupported("value of ${model.name}", "a ${model.model} call has no modeled value", c)
                out += Call(emptyList(), model.model, emptyList(), model.throws.map { LibExc.valueOf(it) }, null, pos)
            }
            ClientRole.NO_EFFECT -> {
                val receiver = unwrap(c.dispatchReceiver)
                if (receiver is FirFunctionCall) {
                    if (ctx.calleeOf(receiver)?.let(m::clientModel)?.role != ClientRole.NO_EFFECT) return run { unsupported("receiver of ${model.name}", "the receiver has an effect", receiver) }
                }
                markAll(c.dispatchReceiver)
                c.arguments.forEach { dropped(it, scope) }
            }
            ClientRole.RUN_CATCHING -> unsupported("runCatching without getOrElse", "runCatching is translated with getOrElse { } on its result (DESIGN-E 6.4 row 4)", c)
            ClientRole.GET_OR_ELSE -> getOrElse(c, scope, sink)
        }
    }

    /** `runCatching { a }.getOrElse { b }` (DESIGN-E 3.3): `try { a } catch (e: Throwable) { b }`, each value to [sink]. */
    private fun getOrElse(
        c: FirFunctionCall,
        scope: Scope,
        sink: Sink,
    ) {
        val pos = ctx.posOf(c)
        val receiver = unwrap(c.extensionReceiver) as? FirFunctionCall
        if (receiver == null || ctx.calleeOf(receiver)?.let(m::clientModel)?.role != ClientRole.RUN_CATCHING) return run { unsupported("getOrElse", "getOrElse is translated on the result of runCatching only", c) }
        mark(receiver)
        markAll(receiver.extensionReceiver)
        val tryLam = (receiver.arguments.singleOrNull() as? FirAnonymousFunctionExpression)?.also(::mark)?.anonymousFunction
        val elseLam = (c.arguments.singleOrNull() as? FirAnonymousFunctionExpression)?.also(::mark)?.anonymousFunction
        if (tryLam == null || elseLam == null) return run { unsupported("runCatching", "runCatching and getOrElse take lambda literals", c) }
        listOf(tryLam, elseLam).forEach {
            mark(it)
            mark(it.body)
            it.valueParameters.forEach(::markAll)
        }
        val s = sink.takeIf { it != Sink.Discard }
        val body = collect { lambdaLike(tryLam.body?.statements.orEmpty(), Scope(scope), s) }
        val inner = Scope(scope)
        elseLam.valueParameters.firstOrNull()?.let { inner.vars[it.symbol] = Bound.Exception }
        val handler = collect { lambdaLike(elseLam.body?.statements.orEmpty(), inner, s) }
        out += Try(body, listOf(Catch("Throwable", m.catchKinds("java/lang/Throwable")!!, handler, ctx.posOf(elseLam))), null, pos)
    }

    /** Why the lambda or function [f] has an effect on the model; null if it only logs (DESIGN-E 6.4 row 10). */
    private fun effect(
        f: FirElement,
        seen: MutableSet<FirNamedFunctionSymbol>,
    ): String? {
        var found: String? = null
        f.accept(
            object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    if (found != null) return
                    when (element) {
                        is FirFunctionCall -> {
                            val sym = ctx.calleeOf(element)
                            val helper = helperOf(element)
                            when {
                                sym != null && ctx.entriesOf(sym).any { it.role == Role.NO_EFFECT } -> Unit
                                sym != null && m.clientModel(sym)?.role == ClientRole.NO_EFFECT -> Unit
                                sym?.callableId?.classId?.asString() in PURE_OWNERS -> Unit
                                helper != null -> if (seen.add(helper.symbol)) effect(helper, seen)?.let { found = "${helper.name}: $it" }
                                else -> found = "the call ${sourceText(element)}"
                            }
                        }
                        is FirVariableAssignment -> found = "the assignment ${sourceText(element)}"
                        is FirThrowExpression -> found = "a throw"
                        else -> Unit
                    }
                    element.acceptChildren(this)
                }
            },
        )
        return found
    }

    /** The helpers a no-effect callback calls are no-effect code too (the ledger holds them so). */
    private fun markRegionHelpers(f: FirElement) {
        f.accept(
            object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    if (element is FirFunctionCall) helperOf(element)?.let { h -> if (h !in m.noEffect) m.noEffect[h] = "fun ${h.name}: called by a no-effect callback".also { markRegionHelpers(h) } }
                    element.acceptChildren(this)
                }
            },
        )
    }

    /** `runAsync(task, executor)`: the task's lambda literal, lowered as a task the first time, then a Spawn. */
    private fun spawn(
        c: FirFunctionCall,
        model: ClientModel,
        scope: Scope,
    ) {
        val pos = ctx.posOf(c)
        markAll(c.dispatchReceiver)
        val taskArg = c.arguments.firstOrNull() ?: return run { unsupported(RUN_ASYNC, "runAsync without a task", c) }
        c.arguments.drop(1).forEach { dropped(it, scope) }
        val (lambda, via) = lambdaOf(taskArg, scope, emptyList()) ?: return run { unsupported("task argument", "the task is a lambda literal, passed directly or through function-typed parameters of private helpers (DESIGN-E 6.3 rule 3)", taskArg) }
        markAll(taskArg)
        if (m.tasks.containsKey(lambda.first)) return run { unsupported("second spawn of a task", "a lambda is handed to an executor at one site", c) }
        m.tasks[lambda.first] = null
        if (loopFrames.isNotEmpty()) unsupported("spawn in a loop", "a loop body has no Spawn (DESIGN-E 6.3 rule 4)", c)
        val unit = ClientUnit(m, owner, op, this)
        val task = unit.task(lambda.first, lambda.second, via, conds.toList(), lets, free, pos)
        m.tasks[lambda.first] = task.first
        out += Spawn(task.first.id, task.second, model.model, model.throws.map { LibExc.valueOf(it) }, pos)
    }

    /** The lambda literal [e] stands for, with its scope and the helpers it passed through; null if it is no lambda literal. */
    private fun lambdaOf(
        e: FirExpression,
        scope: Scope,
        via: List<String>,
    ): Pair<Pair<FirAnonymousFunction, Scope>, List<String>>? {
        val x = unwrapTask(e)
        return when (x) {
            is FirAnonymousFunctionExpression -> (x.anonymousFunction to scope) to via
            is FirPropertyAccessExpression -> {
                val sym = ctx.calleeOf(x) ?: return null
                when (val b = lookup(sym, scope, x)) {
                    is Bound.Arg -> lambdaOf(b.arg, b.scope, listOf(frameName(sym)) + via)
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun frameName(sym: FirBasedSymbol<*>): String =
        (sym as? FirValueParameterSymbol)?.containingDeclarationSymbol?.let { (it as? FirNamedFunctionSymbol)?.name?.asString() } ?: "?"

    /**
     * Lowers task [lambda] (DESIGN-E 3.4): its captured values become parameters, bound at the spawn to the caller's
     * values; its precondition is the obligations its release calls name, its captured constants, and [conds] (the
     * conditions around the spawn) over captured values. Returns the task and the spawn's arguments.
     */
    fun task(
        lambda: FirAnonymousFunction,
        lambdaScope: Scope,
        via: List<String>,
        conds: List<Expr>,
        callerLets: Map<Local, Expr>,
        callerFree: Map<String, FreeValue>,
        spawnPos: Pos,
    ): Pair<Task, List<Expr>> {
        // The instance values are the same in the task (DESIGN-E 3.6: immutable).
        free.putAll(callerFree)
        val pos = ctx.posOf(lambda)
        val id = "task_${pos.line}"
        val scope = Scope(null, captures = lambdaScope)
        mark(lambda)
        m.lowered += lambda
        (ctx.index.parent(lambda) as? FirAnonymousFunctionExpression)?.let(::mark)
        mark(lambda.body)
        frames.addLast(Frame(null, ""))
        out += collect { lambdaLike(lambda.body?.statements.orEmpty(), scope, null) }
        frames.removeLast()
        if (problems.isNotEmpty()) (listOf(lambda) + inlined).forEach { markUnit(it, lambda) }
        val holder = ctx.enclosing(lambda) { it is FirNamedFunction } as? FirNamedFunction
        // The caller's values of the parameters, and the reverse map for the conditions.
        val callerToParam = captured.values.mapNotNull { (p, e) -> (e as? Var)?.local?.let { it to p } }.toMap()
        val requires = mutableListOf<Expr>()
        for ((p, e) in captured.values) {
            val value = if (isConstant(e)) e else (e as? Var)?.local?.let { callerLets[it] }?.takeIf(::isConstant)
            value?.let { requires += Cmp(CmpOp.EQ, Var(p, p.pos), it, spawnPos) }
        }
        for (c in conds) {
            for (conjunct in conjuncts(c)) translate(conjunct, callerToParam, callerLets)?.let { if (it !in requires) requires += it }
        }
        val holds = mutableListOf<Holds>()
        for (call in out.walk().filterIsInstance<Call>()) {
            val o = call.obligation?.takeIf { !it.creates } ?: continue
            if (o.args.any { a -> a.walk().any { it is Var && it.local !in captured.values.map { c -> c.first } && it.local != key } }) continue
            val nullableArgs = o.args.filter { canBeNull(it.type) }
            val cond = nullableArgs.map<Expr, Expr> { a -> Cmp(CmpOp.NE, a, NullLit(a.type, a.pos), a.pos) }.reduceOrNull { a, b -> And(a, b, a.pos) }
            val h = Holds(o.obligation, o.args, cond, call.pos)
            if (holds.none { it.obligation == h.obligation && it.args.map(::unpos) == h.args.map(::unpos) && it.cond?.let(::unpos) == h.cond?.let(::unpos) }) holds += h
        }
        val ok = problems.isEmpty()
        // The key first, then the captured values in Kotlin declaration order.
        val order = captured.values.sortedWith(compareBy({ it.first != key }, { it.first.pos.line }, { it.first.pos.col }))
        val task =
            Task(
                id = id,
                pos = pos,
                inFunction = holder?.name?.asString() ?: "?",
                via = via,
                spawnedBy = owner,
                params = order.map { it.first },
                free = free.values.toList(),
                requires = requires,
                holds = holds,
                body = if (ok) out else problems.toList(),
                status = if (ok) Status.Supported else Status.Unsupported(problems.map { unsupportedDiagnostic(it, id) }),
                translated = translated,
            )
        return task to order.map { it.second }
    }

    /** [e] with every position the same, to compare expressions as text. */
    private fun unpos(e: Expr): Expr {
        val p = Pos("", 0, 0)
        return when (e) {
            is Var -> Var(e.local, p)
            is NullLit -> NullLit(e.type, p)
            is IntLit -> IntLit(e.value, p)
            is BoolLit -> BoolLit(e.value, p)
            is EnumLit -> EnumLit(e.type, e.entry, p)
            is Cmp -> Cmp(e.op, unpos(e.a), unpos(e.b), p)
            is And -> And(unpos(e.a), unpos(e.b), p)
            is Or -> Or(unpos(e.a), unpos(e.b), p)
            is Not -> Not(unpos(e.a), p)
            is Arith -> Arith(e.op, unpos(e.a), unpos(e.b), e.assumption, p)
            else -> e
        }
    }

    /** The top-level conjuncts of [e]. */
    private fun conjuncts(e: Expr): List<Expr> = if (e is And) conjuncts(e.a) + conjuncts(e.b) else listOf(e)

    /**
     * [e] over the task's parameters (DESIGN-E 3.4 part 3): a caller local becomes its task parameter, an immutable
     * local with a known value its value, a free value stays; null if another local remains.
     */
    private fun translate(
        e: Expr,
        params: Map<Local, Local>,
        values: Map<Local, Expr>,
    ): Expr? {
        fun go(x: Expr): Expr? =
            when (x) {
                is Var -> params[x.local]?.let { Var(it, x.pos) } ?: values[x.local]?.let(::go) ?: x.takeIf { v -> free.values.any { it.local == v.local } || v.local == key }
                is Cmp -> go(x.a)?.let { a -> go(x.b)?.let { b -> x.copy(a = a, b = b) } }
                is Not -> go(x.a)?.let { x.copy(a = it) }
                is And -> go(x.a)?.let { a -> go(x.b)?.let { b -> x.copy(a = a, b = b) } }
                is Or -> go(x.a)?.let { a -> go(x.b)?.let { b -> x.copy(a = a, b = b) } }
                is Arith -> go(x.a)?.let { a -> go(x.b)?.let { b -> x.copy(a = a, b = b) } }
                is IntLit, is BoolLit, is NullLit, is EnumLit -> x
                else -> null
            }
        return go(e)
    }

    // ---- reads ----

    /**
     * What [sym] is bound to where [at] reads it. In a task, a symbol of the spawn's scope is captured: a value becomes
     * a task parameter (DESIGN-E 3.4); a captured Kotlin var is unsupported.
     */
    private fun lookup(
        sym: FirBasedSymbol<*>,
        scope: Scope,
        at: FirElement,
    ): Bound? {
        scope.local(sym)?.let { return it }
        var s: Scope? = scope
        while (s != null && s.captures == null) s = s.parent
        val spawn = s?.captures ?: return null
        val outer = lookupLocal(sym, spawn) ?: return null
        captured[sym]?.let { return Bound.Value(Var(it.first, it.first.pos)) }
        if ((sym as? FirPropertySymbol)?.let { it.isLocal && !it.isVal } == true) {
            unsupported("captured var ${sym.name}", "the task captures var ${sym.name}, which may change after the spawn (DESIGN-E 3.4 point 3)", at)
            return null
        }
        // A value the model does not translate (a Callable, a function value, a lambda) stays as it is: no parameter.
        val callerValue =
            when (outer) {
                is Bound.Param -> if (!modeledType(outer.sym.resolvedReturnType)) return outer else parent!!.spawnValue { parent.materialize(outer.sym, at) }
                is Bound.Value -> outer.e
                is Bound.Arg -> {
                    val x = unwrap(outer.arg)
                    if (x is FirAnonymousFunctionExpression || x != null && !modeledType(x.resolvedType)) return outer
                    parent!!.spawnValue { parent.expr(outer.arg, outer.scope, parent.argType(outer.arg, sym)) }
                }
                Bound.Exception -> return outer
            } ?: return null
        val name = (callerValue as? Var)?.local?.name ?: "${(sym as? FirCallableSymbol<*>)?.callableId?.callableName}__${ctx.posOf(at).line}"
        val p = Local(name, callerValue.type, (callerValue as? Var)?.local?.pos ?: ctx.posOf(at))
        names += name
        if (name == "k") key = p
        captured[sym] = p to callerValue
        return Bound.Value(Var(p, p.pos))
    }

    /** The spawn's value of a captured parameter or argument: a pure read, so lowering it emits no statement. */
    private fun spawnValue(f: () -> Expr?): Expr? {
        var v: Expr? = null
        val stmts = collect { v = f() }
        check(stmts.isEmpty()) { "a captured value has an effect" }
        return v
    }

    /** [sym]'s binding in [scope] or the scopes a task captures, without capturing it. */
    private fun lookupLocal(
        sym: FirBasedSymbol<*>,
        scope: Scope,
    ): Bound? {
        scope.local(sym)?.let { return it }
        var s: Scope? = scope
        while (s != null && s.captures == null) s = s.parent
        return s?.captures?.let { lookupLocal(sym, it) }
    }

    /** True if [t] is a type the model translates as a value. */
    private fun modeledType(t: ConeKotlinType): Boolean {
        val low = t.lowerBoundIfFlexible()
        val id = low.classId?.asString()
        return low is ConeTypeParameterType || id in NUMBER || id == "kotlin/Boolean" || id == "kotlin/String" || low.classId?.let { m.enums.containsKey(it) } == true || ctx.classOfType(low) != null
    }

    /** The type a `null` argument of helper parameter [sym] takes: the parameter's; null for any other argument. */
    private fun argType(
        arg: FirExpression,
        sym: FirBasedSymbol<*>,
    ): KType? {
        val lit = unwrap(arg) as? FirLiteralExpression ?: return null
        if (lit.kind != ConstantValueKind.Null || sym !is FirValueParameterSymbol) return null
        return ktype(sym.resolvedReturnType, sym, arg)
    }

    /** The local of entry parameter [sym], declared the first time a modeled place reads it. */
    private fun materialize(
        sym: FirValueParameterSymbol,
        at: FirElement,
    ): Expr? {
        params[sym]?.let { return Var(it, ctx.posOf(at)) }
        val decl = ctx.declarationOf(sym) ?: return null
        val type = ktype(sym.resolvedReturnType, sym, at) ?: return null
        val l = declare(sym.name.asString(), type, ctx.posOf(decl))
        params[sym] = l
        return Var(l, ctx.posOf(at))
    }

    /** The pure value of [e]; effects of calls go to [out] first. [expected] types a `null` literal. Null if unsupported. */
    private fun expr(
        e: FirExpression,
        scope: Scope,
        expected: KType?,
    ): Expr? {
        val x = unwrap(e)!!
        markCasts(e)
        val pos = ctx.posOf(x)
        return when (x) {
            is FirLiteralExpression -> {
                mark(x)
                when {
                    x.kind == ConstantValueKind.Null -> if (expected != null && canBeNull(expected)) NullLit(expected, pos) else unsupported("null", "a null of a type without null in the model", x)
                    x.kind == ConstantValueKind.Boolean -> BoolLit(x.value as Boolean, pos)
                    x.value is Number && x.kind != ConstantValueKind.Float && x.kind != ConstantValueKind.Double -> IntLit((x.value as Number).toLong(), pos)
                    else -> unsupported("literal ${x.value}", "the literal's type is not part of the model", x)
                }
            }
            is FirCheckedSafeCallSubject -> subjects[x]?.also { mark(x) } ?: unsupported("safe call subject", "the subject is not lowered", x)
            is FirPropertyAccessExpression -> read(x, scope)
            is FirEqualityOperatorCall -> {
                mark(x)
                val (a0, b0) = x.arguments
                val op = when (x.operation) {
                    FirOperation.EQ, FirOperation.IDENTITY -> CmpOp.EQ
                    FirOperation.NOT_EQ, FirOperation.NOT_IDENTITY -> CmpOp.NE
                    else -> return unsupported("operator ${x.operation.operator}", "not translated", x)
                }
                val a = expr(a0, scope, null)
                val b = expr(b0, scope, a?.type?.let(::nullable))
                // `null == a` gets its type from a.
                val aa = a ?: return null
                val bb = b ?: return null
                Cmp(op, aa, bb, pos)
            }
            is FirComparisonExpression -> {
                mark(x)
                mark(x.compareToCall)
                val op =
                    when (x.operation) {
                        FirOperation.LT -> CmpOp.LT
                        FirOperation.LT_EQ -> CmpOp.LE
                        FirOperation.GT -> CmpOp.GT
                        FirOperation.GT_EQ -> CmpOp.GE
                        else -> return unsupported("comparison", "not translated", x)
                    }
                val a = expr(x.compareToCall.dispatchReceiver ?: return null, scope, KType.IntT) ?: return null
                val b = expr(x.compareToCall.arguments.single(), scope, KType.IntT) ?: return null
                Cmp(op, a, b, pos)
            }
            is FirBooleanOperatorExpression -> {
                mark(x)
                val a = expr(x.leftOperand, scope, KType.BoolT) ?: return null
                var b: Expr? = null
                val rightEffects = collect { b = expr(x.rightOperand, scope, KType.BoolT) }
                if (rightEffects.isNotEmpty()) return unsupported("effect in the right side of ${x.kind.token}", "the right side of && and || runs only sometimes; it must be pure", x.rightOperand)
                val bb = b ?: return null
                if (x.kind == LogicOperationKind.AND) And(a, bb, pos) else Or(a, bb, pos)
            }
            is FirBlock -> if (isIncDec(x)) incDec(x, scope, valueUsed = true) else unsupported("block as a value", "not translated", x)
            is FirFunctionCall -> callValue(x, scope, expected)
            is FirThisReceiverExpression -> unsupported("this as a value", "the module object is not a value of the model", x)
            is FirCheckNotNullCall -> unsupported("!!", "a not-null assertion throws; the client lowering does not translate it", x)
            else -> {
                // A branching value: a mutable temp each branch assigns.
                val type = expected ?: ktype(x.resolvedType, ctx.slotOf(x), x) ?: return null
                val d = default(type, pos)
                val t = temp("value", type, pos)
                if (d != null) out += Let(t, d, true, pos)
                valueInto(x, scope, if (d != null) Sink.Into(t, d is NullLit) else Sink.Declare(t))
                Var(t, pos)
            }
        }
    }

    /** The value of call [c]: an operator, a `!`, an increment, or a call with an effect into a temp. */
    private fun callValue(
        c: FirFunctionCall,
        scope: Scope,
        expected: KType?,
    ): Expr? {
        val pos = ctx.posOf(c)
        val sym = ctx.calleeOf(c)
        val id = sym?.callableId
        val owner = id?.classId?.asString()
        val name = id?.callableName?.asString()
        when {
            owner == "kotlin/Boolean" && name == "not" -> {
                mark(c)
                return expr(c.dispatchReceiver ?: return null, scope, KType.BoolT)?.let { Not(it, pos) }
            }
            owner in NUMBER && (name == "plus" || name == "minus") -> {
                mark(c)
                val a = expr(c.dispatchReceiver ?: return null, scope, KType.IntT) ?: return null
                val b = expr(c.arguments.single(), scope, KType.IntT) ?: return null
                return Arith(if (name == "plus") ArithOp.PLUS else ArithOp.MINUS, a, b, assumption(c), pos)
            }
            owner in NUMBER && name == "toLong" -> {
                mark(c)
                return expr(c.dispatchReceiver ?: return null, scope, KType.IntT)
            }
        }
        val effectful = helperOf(c) != null || m.trustedOf(c) != null || isInvoke(c) || ctx.constructedClass(sym) != null || sym?.let(m::clientModel) != null || scopeName(c) != null
        if (!effectful) return unsupported("call of ${id?.asSingleFqName() ?: sourceText(c)}", "the call is outside the model: no trusted declaration, helper or built-in model (DESIGN-E 6.4)", c)
        val type = expected?.takeIf { true } ?: ktype(c.resolvedType, ctx.slotOf(c), c) ?: return null
        val t = temp(if (ctx.constructedClass(sym) != null) "new" else "r", type, pos)
        call(c, scope, Sink.Declare(t))
        return Var(t, pos)
    }

    /** The ASSUMPTION of Kotlin Int or Long arithmetic at [c] (DESIGN-v0.md 2.8, W13): `A<line>` or `A<line>_<col>`. */
    private fun assumption(c: FirElement): String {
        val pos = ctx.posOf(c)
        val id = "A${pos.line}_${pos.col}"
        if (m.diagnostics.none { it.id == id }) m.diagnostics += Diagnostic(id, DiagnosticKind.ASSUMPTION, "Int unbounded: ${sourceText(c)}", pos)
        return id
    }

    private fun isIncDec(b: FirBlock): Boolean = b.source?.kind is KtFakeSourceElementKind.DesugaredIncrementOrDecrement

    /** `x++`, `++x`, `x--`, `--x` on a local: `x := x ± 1`; the value of a prefix form is the new x. */
    private fun incDec(
        b: FirBlock,
        scope: Scope,
        valueUsed: Boolean,
    ): Expr? {
        markAll(b)
        val assign = b.statements.filterIsInstance<FirVariableAssignment>().singleOrNull() ?: return unsupported("increment", "not translated", b)
        val target = (assign.lValue as? FirDesugaredAssignmentValueReferenceExpression)?.expressionRef?.value?.let { unwrap(it) } as? FirPropertyAccessExpression
        val sym = target?.let { ctx.calleeOf(it) }
        val local = ((sym?.let { scope.local(it) } as? Bound.Value)?.e as? Var)?.local ?: return unsupported("increment of ${sourceText(b)}", "an increment of a local of the unit", b)
        val op = (assign.rValue as? FirFunctionCall)?.let { ctx.calleeOf(it)?.callableId?.callableName?.asString() }
        val kind = (b.source?.kind as KtFakeSourceElementKind.DesugaredIncrementOrDecrement)
        val prefix = kind::class.simpleName!!.startsWith("Prefix")
        if (valueUsed && !prefix) return unsupported("postfix increment as a value", "only a prefix increment is translated as a value", b)
        val pos = ctx.posOf(b)
        val delta = Arith(if (op == "dec") ArithOp.MINUS else ArithOp.PLUS, Var(local, pos), IntLit(1, pos), assumption(b), pos)
        out += Assign(local, delta, pos)
        return Var(local, pos)
    }

    private fun read(
        x: FirPropertyAccessExpression,
        scope: Scope,
    ): Expr? {
        val pos = ctx.posOf(x)
        val sym = ctx.calleeOf(x)
        if (sym is FirEnumEntrySymbol) {
            markAll(x)
            val e = sym.callableId.classId?.let { m.enums[it] } ?: return unsupported("enum entry ${sym.name}", "the enum class is no sidecar enum", x)
            return EnumLit(KType.EnumT(e.name, false), sym.name.asString(), pos)
        }
        if (sym is FirValueParameterSymbol || sym is FirPropertySymbol && sym.isLocal) {
            mark(x)
            return when (val b = lookup(sym, scope, x)) {
                is Bound.Value -> b.e.let { if (it is Var) Var(it.local, pos) else it }
                is Bound.Arg -> expr(b.arg, b.scope, argType(b.arg, sym))
                is Bound.Param -> materialize(b.sym, x)
                Bound.Exception -> unsupported("exception value ${sym.name}", "a caught exception is no value of the model", x)
                null -> unsupported("read of ${sourceText(x)}", "the declaration was not translated", x)
            }
        }
        if (sym is FirPropertySymbol && sym.resolvedStatus.isConst) {
            markAll(x)
            val init = sym.resolvedInitializer as? FirLiteralExpression ?: return unsupported("const ${sym.name}", "its value is not a literal", x)
            return when (val v = init.value) {
                is Boolean -> BoolLit(v, pos)
                is Number -> IntLit(v.toLong(), pos)
                else -> unsupported("const ${sym.name}", "the const's type is not part of the model", x)
            }
        }
        // A property of a caught exception: the exception is no value of the model.
        (unwrap(x.dispatchReceiver) as? FirPropertyAccessExpression)?.let { r ->
            if (ctx.calleeOf(r)?.let { lookupLocal(it, scope) } == Bound.Exception) return unsupported("exception value ${sourceText(x)}", "a caught exception and its properties are no values of the model", x)
        }
        // An instance value: a path of vals from this (DESIGN-v0.md 2.12, DESIGN-E 3.6).
        if (sym is FirPropertySymbol && thisPath(x) != null) {
            markAll(x)
            val path = thisPath(x)!!
            val named = m.resolver.clientFree[op.name.text]?.find { (_, syms) -> syms.map { it.callableId.toString() } == path.map { it.callableId.toString() } }
            val key = named?.first?.names?.first()?.text ?: path.joinToString(".") { it.name.asString() }
            free[key]?.let { return Var(it.local, pos) }
            val type = ktype(sym.resolvedReturnType, sym, x) ?: return null
            val local = if (named != null) Local(key, type, pos) else declare(path.joinToString("_") { it.name.asString() }, type, pos)
            names += local.name
            free[key] = FreeValue(local, InstanceVal(path.joinToString(".") { it.name.asString() }, pos), named?.first?.names?.first()?.text, "this.${path.joinToString(".") { it.name.asString() }}")
            return Var(local, pos)
        }
        return unsupported("read of ${sourceText(x)}", "a property outside the model: a local, a parameter, an enum entry, a const, or a path of vals from this (DESIGN-E 3.6)", x)
    }

    /** The path of val properties from this that [x] reads, or null. */
    private fun thisPath(x: FirExpression): List<FirPropertySymbol>? {
        val e = unwrap(x)
        if (e is FirThisReceiverExpression) return emptyList()
        if (e !is FirPropertyAccessExpression) return null
        val sym = ctx.calleeOf(e) as? FirPropertySymbol ?: return null
        if (!sym.isVal || sym.isLocal) return null
        val receiver = e.dispatchReceiver ?: return null
        return thisPath(receiver)?.plus(sym)
    }

    /**
     * An argument the model drops (DESIGN-v0.md 2.3): it must be pure (reads, literals, templates, Int and Boolean
     * operators); its value is not translated. [what] names the call for the message.
     */
    private fun dropped(
        e: FirExpression,
        scope: Scope,
        what: String = "trusted call",
    ) {
        markAll(e)
        var effect: FirElement? = null
        e.accept(
            object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    val pure =
                        when (element) {
                            is FirFunctionCall -> ctx.calleeOf(element)?.callableId?.classId?.asString() in PURE_OWNERS || ctx.calleeOf(element)?.let { m.clientModel(it)?.role == ClientRole.NO_EFFECT } == true
                            is FirAnonymousFunctionExpression, is FirVariableAssignment, is FirThrowExpression, is FirCheckNotNullCall -> false
                            else -> true
                        }
                    if (!pure && effect == null) effect = element
                    element.acceptChildren(this)
                }
            },
        )
        effect?.let { unsupported("call in an argument of a $what", "an argument the model drops must be pure ($what, DESIGN-v0.md 2.3)", it) }
    }

    /** `C(args)` of sidecar class [cls] into [v] (DESIGN-E 6.4 row 12): each field gets the argument of the same name. */
    private fun newObject(
        c: FirFunctionCall,
        cls: kv.kvir.ObjClass,
        v: Local,
        scope: Scope,
    ): Boolean {
        mark(c)
        val pos = ctx.posOf(c)
        val byName = c.resolvedArgumentMapping.orEmpty().entries.associate { (a, p) -> p.name.asString() to a }
        val init = linkedMapOf<String, Expr>()
        for (f in cls.fields) {
            val a = byName[f.kotlin]
            if (a == null) {
                unsupported("new ${cls.name}", "the constructor gives no argument named ${f.kotlin} for field ${f.name}", c)
                return false
            }
            init[f.name] = expr(a, scope, f.type) ?: return false
        }
        byName.filterKeys { k -> cls.fields.none { it.kotlin == k } }.values.forEach { dropped(it, scope, "constructor") }
        out += New(v, cls, init, pos)
        return true
    }
}
