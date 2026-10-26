package kv.lower

import kv.frontend.FoundAction
import kv.frontend.Positions
import kv.frontend.SidecarResolver
import kv.kvir.AbsType
import kv.kvir.BoolLit
import kv.kvir.CounterVar
import kv.kvir.Diagnostic
import kv.kvir.DiagnosticKind
import kv.kvir.Expr
import kv.kvir.GhostSchema
import kv.kvir.IntLit
import kv.kvir.KType
import kv.kvir.MapVar
import kv.kvir.ModuleKind
import kv.kvir.NullLit
import kv.kvir.ObjClass
import kv.kvir.ObjField
import kv.kvir.Pos
import kv.kvir.StateVar
import kv.model.Entry
import kv.model.Role
import kv.model.TrustedModels
import kv.sidecar.Sidecar
import kv.sidecar.SidecarError
import kv.sidecar.StateKind
import kv.sidecar.TrustedDecl
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.FirVariable
import org.jetbrains.kotlin.contracts.description.EventOccurrencesRange
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirEqualityOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSafeCallExpression
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirStringConcatenationCall
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.fir.unwrapSubstitutionOverrides
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.descriptors.ClassKind
import java.util.IdentityHashMap
import org.jetbrains.kotlin.types.ConstantValueKind
import org.jetbrains.kotlin.realElement

private val VOLATILE = ClassId.fromString("kotlin/jvm/Volatile")
private val LONG_OPS = setOf("kotlin/Long", "kotlin/Int")

/** The models whose objects model operations may prepare before a construction passes them (AI-CODE.md 14.1 A1). */
private val PREPARED_MODELS = setOf("jdk.Semaphore", TrustedModels.KOTLINX_SEMAPHORE_MODEL)

/** A reason v0 cannot model something, with the Kotlin position that shows it. */
internal data class Problem(
    val reason: String,
    val pos: Pos,
)

/**
 * How a construction gets the object of a flattened field (DESIGN-v0.md 2.11 rule 5, AI-CODE.md 14.1 A1): a new
 * model object, or the problem that keeps it from being one.
 */
internal sealed interface Made {
    /**
     * [ctor] makes the object. [ops] are the model operations on it before the pass, in source order; their results
     * are unused. [local] is the `val` that holds the object between [ctor] and the pass; null for a direct argument.
     */
    data class New(
        val ctor: FirFunctionCall,
        val ops: List<FirFunctionCall>,
        val local: FirProperty?,
    ) : Made

    data class Broken(
        val problem: Problem,
    ) : Made
}

/** A sidecar `type ... = template "prefix${Long}suffix"`. */
internal data class Template(
    val type: String,
    val prefix: String,
    val suffix: String,
)

/**
 * The sidecar's module bound to the FIR of the module file: state, classes, models, trusted functions, the String
 * abstraction, and the checks that are about the whole file (flattening, cells). Valid only during the compiler run.
 */
internal class ModuleContext(
    val sidecar: Sidecar,
    val resolver: SidecarResolver,
    val index: FirIndex,
    val moduleClass: FirRegularClass,
) {
    private val positions = IdentityHashMap<FirFile, Positions>().apply { index.all.forEach { put(it, Positions(it)) } }
    val diagnostics = mutableListOf<Diagnostic>()
    val abstraction = Abstraction()

    /** Why a counter increment at a position is not a reserve step (DESIGN-D.md 6.3), for the input check ([findReserves]). */
    val notReserve = mutableMapOf<Pos, String>()

    /** DESIGN-B.md 2.1: a class module is an instance module; an object or companion object is an object module. */
    val kind: ModuleKind = if (moduleClass.classKind == ClassKind.CLASS) ModuleKind.INSTANCE else ModuleKind.OBJECT

    val types = sidecar.types.map { AbsType(it.name.text, it.template, it.kclass?.text?.let { k -> ClassId.topLevel(FqName(k)).asString() }) }

    /** The `returns fresh` trusted functions by short name, with their sidecar class (DESIGN-B.md 3.2). */
    val freshFunctions: Map<String, String> = sidecar.trusted.mapNotNull { t -> t.fresh?.let { t.shortName to it.text } }.toMap()
    val templates =
        sidecar.types.mapNotNull { t ->
            val text = t.template ?: return@mapNotNull null
            val at = text.indexOf("\${Long}")
            if (at < 0) null else Template(t.name.text, text.substring(0, at), text.substring(at + "\${Long}".length))
        }
    val uses = sidecar.uses.map { it.model.text }.toSet()
    val trusted: Map<FirNamedFunctionSymbol, TrustedDecl> =
        sidecar.trusted.mapNotNull { t -> resolver.trusted[t.shortName]?.let { it to t } }.toMap()
    val ghost =
        sidecar.ghosts.firstOrNull()?.let { g -> GhostSchema(g.name.text, g.fields.associateTo(linkedMapOf()) { it.name.text to it.kind }) }

    /** Declarations of the module file by symbol. */
    private val declarations: Map<FirBasedSymbol<*>, FirDeclaration> =
        index.nodes.filterIsInstance<FirDeclaration>().associateBy { it.symbol }

    fun declarationOf(sym: FirBasedSymbol<*>): FirDeclaration? = declarations[sym]

    // ---- positions and FIR queries ----

    /**
     * The Kotlin position of [e]. A declaration is placed where its code starts (after its KDoc), without the fake
     * kind of a property declared in a constructor; an expression is placed without its smart casts.
     */
    fun posOf(e: FirElement): Pos {
        val positions = positions.getValue(index.fileOf(e))
        val p =
            if (e is FirDeclaration) {
                positions.ofDeclaration(e.source?.realElement())
            } else {
                positions.of(unwrap(e)?.source)
            }
        return p ?: this.positions.getValue(index.file).ofDeclaration(moduleClass.source?.realElement())!!
    }

    fun calleeOf(e: FirQualifiedAccessExpression): FirCallableSymbol<*>? = e.calleeReference.toResolvedCallableSymbol()?.unwrapSubstitutionOverrides()

    /** The model entries of [sym], matched by owner, name and parameter types. */
    fun entriesOf(sym: FirCallableSymbol<*>): List<Entry> = modelEntries(sym)

    fun entriesOf(e: FirQualifiedAccessExpression): List<Entry> = calleeOf(e)?.let(::entriesOf).orEmpty()

    // ---- state ----

    /** State variables in Kotlin declaration order. */
    val state: List<StateVar>
    private val stateBySymbol = mutableMapOf<FirPropertySymbol, StateVar>()

    fun stateOf(sym: FirBasedSymbol<*>?): StateVar? = stateBySymbol[sym]

    /**
     * The state variable [e] reads, if [e] is a read of a module state property of this module. In an instance module
     * that is a read through `this` of the module class (DESIGN-B.md 2.1 rule 3); see [foreignState].
     */
    fun stateRead(e: FirExpression?): StateVar? = (unwrap(e) as? FirPropertyAccessExpression)?.takeUnless { foreignState(it) }?.let { stateOf(calleeOf(it)) }

    /** True if [e] reads a state property of an instance module through another receiver than `this`: another module. */
    fun foreignState(e: FirPropertyAccessExpression): Boolean {
        if (kind != ModuleKind.INSTANCE || stateOf(calleeOf(e)) == null) return false
        val receiver = unwrap(e.dispatchReceiver) as? FirThisReceiverExpression ?: return true
        return receiver.calleeReference.boundSymbol != moduleClass.symbol
    }

    // ---- classes ----

    val classes: List<ObjClass>
    private val classBySymbol = mutableMapOf<FirRegularClassSymbol, ObjClass>()
    private val fieldBySymbol = mutableMapOf<FirPropertySymbol, ObjField>()

    fun classOf(sym: FirBasedSymbol<*>?): ObjClass? = classBySymbol[sym]

    fun fieldOf(sym: FirBasedSymbol<*>?): ObjField? = fieldBySymbol[sym]

    fun classOfType(t: ConeKotlinType): ObjClass? = t.lowerBoundIfFlexible().classId?.let { id -> classBySymbol.entries.find { it.key.classId == id }?.value }

    /** The opaque sidecar type bound to [t]'s class (DESIGN-B.md 2.2), e.g. MethodKey for java.lang.reflect.Method. */
    fun absOfType(t: ConeKotlinType): KType.Abs? {
        val low = t.lowerBoundIfFlexible()
        val id = low.classId?.asString() ?: return null
        return types.find { it.kclass == id }?.let { KType.Abs(it.name, low.isMarkedNullable) }
    }

    /** The sidecar class [sym] constructs, if [sym] is a constructor of one. */
    fun constructedClass(sym: FirCallableSymbol<*>?): ObjClass? = (sym as? FirConstructorSymbol)?.let { s -> classBySymbol.entries.find { it.key.classId == s.callableId.classId }?.value }

    private val flattenProblems = mutableMapOf<ObjField, List<Problem>>()

    /** The maximum permits of each valid kotlinx Semaphore field: the `permits` all its constructions give. */
    private val semaphoreMaxima = mutableMapOf<ObjField, Long>()

    fun semaphoreMax(field: ObjField): Long? = semaphoreMaxima[field]

    /** Every reason why [field] cannot be folded into its owner (DESIGN-v0.md 2.11 rule 5); empty for a plain or valid field. */
    fun flattenProblems(field: ObjField): List<Problem> = flattenProblems[field].orEmpty()

    /** The `val`s that hold a new model object until a construction passes it (A1), with the field and the object. */
    private val madeLocals = mutableMapOf<FirPropertySymbol, Pair<ObjField, Made.New>>()

    fun madeLocal(sym: FirBasedSymbol<*>): Pair<ObjField, Made.New>? = madeLocals[sym]

    init {
        state =
            sidecar.states.mapNotNull { st ->
                val sym = resolver.states[st.name.text] ?: return@mapNotNull null
                val decl = declarations[sym] as? FirProperty
                val pos = decl?.let { posOf(it) } ?: posOf(moduleClass)
                val v =
                    when (val kind = st.kind) {
                        is StateKind.MapOf -> {
                            if (decl == null || !isEmptyMap(decl.initializer)) {
                                unsupportedModule("state ${st.name.text}: v0 needs an empty ConcurrentHashMap() initializer", pos)
                            }
                            val setOnce = sidecar.setOnce.find { it.map.text == st.name.text }?.id?.text
                            MapVar(st.name.text, sym.name.asString(), pos, KType.Abs(kind.key.text, false), kind.value.text, setOnce)
                        }
                        StateKind.Counter -> {
                            val initial = decl?.let { counterInitial(it.initializer) }
                            if (initial == null) unsupportedModule("state ${st.name.text}: v0 needs an AtomicLong() or AtomicLong(constant) initializer", pos)
                            CounterVar(st.name.text, sym.name.asString(), pos, initial ?: 0)
                        }
                    }
                stateBySymbol[sym] = v
                v
            }.sortedWith(compareBy({ it.pos.line }, { it.pos.col }))
        classes =
            sidecar.classes.mapNotNull { c ->
                val sym = resolver.classes[c.name.text] ?: return@mapNotNull null
                val fields =
                    c.fields.mapNotNull { f ->
                        val prop = resolver.fields[c.name.text to f.name.text] ?: return@mapNotNull null
                        val model = resolver.fieldModels[c.name.text to f.name.text]
                        val type =
                            when (f.type.text) {
                                "Semaphore", "Long" -> KType.IntT
                                "AtomicBoolean" -> KType.BoolT
                                // DESIGN-E 6.2: a client class field is Int or Bool (an object module's FTYPE has neither).
                                "Int" -> KType.IntT
                                "Bool" -> KType.BoolT
                                else -> KType.Abs(f.type.text, f.nullable)
                            }
                        val volatile = prop.backingFieldSymbol?.resolvedAnnotationClassIds?.contains(VOLATILE) == true
                        val field = ObjField(f.name.text, f.kname.text, type, model, volatile, declarations[prop]?.let { posOf(it) } ?: posOf(moduleClass))
                        fieldBySymbol[prop] = field
                        if (type is KType.Abs) abstraction.fix(prop, type.name, field.pos)
                        field
                    }
                val decl = declarations[sym] as? FirRegularClass
                val cls = ObjClass(c.name.text, c.kname.text, fields, defaults(decl, fields), decl?.let { posOf(it) } ?: posOf(moduleClass))
                classBySymbol[sym] = cls
                cls
            }
        checkFlattening()
    }

    private fun unsupportedModule(
        message: String,
        pos: Pos,
    ) {
        diagnostics += Diagnostic("U${pos.line}_${pos.col}", DiagnosticKind.UNSUPPORTED, message, pos)
    }

    private fun isEmptyMap(e: FirExpression?): Boolean {
        val call = e as? FirFunctionCall ?: return false
        val sym = calleeOf(call) as? FirConstructorSymbol ?: return false
        return sym.callableId.classId?.asString() == "java/util/concurrent/ConcurrentHashMap" && call.arguments.isEmpty()
    }

    private fun counterInitial(e: FirExpression?): Long? {
        val call = e as? FirFunctionCall ?: return null
        val entry = entriesOf(call).find { it.model == TrustedModels.COUNTER && it.role == Role.CTOR } ?: return null
        if (entry.params!!.isEmpty()) return 0
        return ((call.arguments.single() as? FirLiteralExpression)?.value as? Number)?.toLong()
    }

    /** Constructor defaults of the fields (DESIGN-v0.md 2.11 rule 6). A default that is not a constant is left out. */
    private fun defaults(
        decl: FirRegularClass?,
        fields: List<ObjField>,
    ): Map<String, Expr> {
        val ctor = index.nodes.filterIsInstance<FirConstructor>().find { it.isPrimary && index.parent(it) === decl } ?: return emptyMap()
        val out = linkedMapOf<String, Expr>()
        for (p in ctor.valueParameters) {
            val field = fields.find { it.kotlin == p.name.asString() } ?: continue
            val default = p.defaultValue ?: continue
            constant(default, field)?.let { out[field.name] = it }
        }
        return out
    }

    /**
     * The constant value of [e] as the initial value of [field]: a literal, or for a flattened field the literal
     * argument of the model constructor (`AtomicBoolean(false)` is `false`). The position is that of [e].
     */
    fun constant(
        e: FirExpression,
        field: ObjField,
    ): Expr? {
        val pos = posOf(e)
        val lit =
            if (field.model != null) {
                val call = e as? FirFunctionCall ?: return null
                if (entriesOf(call).none { it.model == field.model && it.role == Role.CTOR }) return null
                if (field.model == TrustedModels.KOTLINX_SEMAPHORE_MODEL) return kotlinxPermits(call)?.let { IntLit(it.first, pos) }
                call.arguments.singleOrNull() as? FirLiteralExpression
            } else {
                e as? FirLiteralExpression
            } ?: return null
        return literal(lit, field.type, pos)
    }

    /** [lit] as a KVIR literal of type [type], or null if it does not fit. */
    fun literal(
        lit: FirLiteralExpression,
        type: KType,
        pos: Pos,
    ): Expr? =
        when {
            lit.kind == ConstantValueKind.Null -> if (type is KType.Abs && type.nullable || type is KType.RefT && type.nullable) NullLit(type, pos) else null
            lit.kind == ConstantValueKind.Boolean && type == KType.BoolT -> BoolLit(lit.value as Boolean, pos)
            lit.value is Number && lit.kind != ConstantValueKind.Float && lit.kind != ConstantValueKind.Double && type == KType.IntT -> IntLit((lit.value as Number).toLong(), pos)
            else -> null
        }

    /**
     * The available count and the maximum of a kotlinx `Semaphore(permits, acquiredPermits = 0)` call (TrustedModels):
     * Int literals with 1 <= permits and 0 <= acquiredPermits <= permits. Null otherwise, since the factory may throw.
     */
    fun kotlinxPermits(call: FirFunctionCall): Pair<Long, Long>? {
        val args = call.resolvedArgumentMapping.orEmpty().entries.associate { (arg, param) -> param.name.asString() to arg }

        fun int(name: String): Long? = ((args[name] as? FirLiteralExpression)?.value as? Number)?.toLong()
        val permits = int("permits") ?: return null
        val acquired = if ("acquiredPermits" in args) int("acquiredPermits") ?: return null else 0L
        return if (permits >= 1 && acquired in 0..permits) permits - acquired to permits else null
    }

    /**
     * DESIGN-v0.md 2.11 rule 5: a flattened field is a `val`, every construction passes a new model object (or uses a
     * default that does), and every read is the receiver of a model operation. The whole module file is checked, and
     * every read and construction that breaks the rule is a problem of its own (AI-CODE.md 13, fix 3).
     */
    private fun checkFlattening() {
        for ((sym, field) in fieldBySymbol) {
            val model = field.model ?: continue
            val notVal = Problem("${field.kotlin} is a var; a flattened field must be a val", field.pos).takeIf { !sym.isVal }
            val uses =
                reads(sym).mapNotNull { read ->
                    // An operation is a call such as `release()` or a property such as kotlinx `availablePermits`.
                    val op = index.parent(read) as? FirQualifiedAccessExpression
                    val ok = op != null && op.dispatchReceiver === read && entriesOf(op).any { it.model == model && it.role == Role.OP }
                    if (ok) null else Problem("${field.kotlin} is used other than as the receiver of a $model operation", posOf(read))
                }
            val made =
                constructions(field).mapNotNull { (call, arg) ->
                    when (val m = madeObject(arg, call, field)) {
                        is Made.Broken -> m.problem
                        is Made.New -> {
                            m.local?.let { madeLocals[it.symbol] = field to m }
                            null
                        }
                    }
                }
            // The maximum needs constructions that all pass a new kotlinx Semaphore.
            val problems = (listOfNotNull(notVal) + uses + made).ifEmpty { if (model == TrustedModels.KOTLINX_SEMAPHORE_MODEL) semaphoreMaximum(field) else emptyList() }
            if (problems.isNotEmpty()) flattenProblems[field] = problems
        }
    }

    /**
     * The maximum of kotlinx Semaphore field [field], whose constructions all pass a new kotlinx Semaphore: each gives
     * literal permits (see [kotlinxPermits]), all the same. Records it, or returns the problems.
     */
    private fun semaphoreMaximum(field: ObjField): List<Problem> {
        val made = constructions(field).map { (call, arg) -> (madeObject(arg, call, field) as Made.New).ctor }
        val notLiteral = made.filter { kotlinxPermits(it) == null }
        if (notLiteral.isNotEmpty()) {
            return notLiteral.map { Problem("kotlinx Semaphore(...) of ${field.kotlin} needs Int literals with 1 <= permits and 0 <= acquiredPermits <= permits", posOf(it)) }
        }
        val maxima = made.map { kotlinxPermits(it)!!.second }
        if (maxima.isEmpty()) return listOf(Problem("no construction of ${field.kotlin} in the module file gives its maximum permits", field.pos))
        made.firstOrNull { kotlinxPermits(it)!!.second != maxima[0] }?.let {
            return listOf(Problem("the constructions of ${field.kotlin} give different permits (${maxima.distinct().joinToString()}); the field needs one maximum", posOf(it)))
        }
        semaphoreMaxima[field] = maxima[0]
        return emptyList()
    }

    private fun isCtor(
        e: FirExpression?,
        model: String,
    ): Boolean = e is FirFunctionCall && entriesOf(e).any { it.model == model && it.role == Role.CTOR }

    /**
     * How [arg], the argument of construction [call] for flattened [field], makes its object (DESIGN-v0.md 2.11 rule
     * 5). A model constructor call is a new object. For a Semaphore model, AI-CODE.md 14.1 A1 also accepts an object
     * that model operations change before the pass: `Semaphore(1).apply { tryAcquire() }`, `Semaphore(1).also {
     * it.tryAcquire() }`, and a local `val s = Semaphore(1)` whose other uses are statements `s.op()` before the
     * statement that passes it. The object is new, only model operations use it, the construction is its only
     * pass, and nothing reads it after the pass; so its operations may run on the new entry's field right after
     * the construction. Each operation must not throw: kotlinx `release` (a proof obligation) is excluded.
     */
    fun madeObject(
        arg: FirExpression?,
        call: FirFunctionCall,
        field: ObjField,
    ): Made {
        val model = field.model!!
        if (isCtor(arg, model)) return Made.New(arg as FirFunctionCall, emptyList(), null)
        val broken = Made.Broken(Problem("a construction of ${field.kotlin} does not pass a new $model object", posOf(arg ?: call)))
        if (model !in PREPARED_MODELS) return broken
        val x = unwrap(arg)
        val scope = (x as? FirFunctionCall)?.let { calleeOf(it)?.callableId }?.takeIf { it.classId == null && it.packageName.asString() == "kotlin" }?.callableName?.asString()
        return when {
            x is FirFunctionCall && (scope == "apply" || scope == "also") && isCtor(unwrap(x.extensionReceiver), model) -> scopeObject(x, scope, model)
            x is FirPropertyAccessExpression && (calleeOf(x) as? FirPropertySymbol)?.isLocal == true -> localObject(x, call, model)
            else -> broken
        }
    }

    /** `Semaphore(1).apply { ... }` or `.also { ... }`: each statement of the lambda is a model operation on its receiver. */
    private fun scopeObject(
        c: FirFunctionCall,
        scope: String,
        model: String,
    ): Made {
        val lam = (c.arguments.singleOrNull() as? FirAnonymousFunctionExpression)?.anonymousFunction
        if (lam == null || lam.invocationKind != EventOccurrencesRange.EXACTLY_ONCE) {
            return Made.Broken(Problem("the $scope argument is not a lambda literal that runs exactly once", posOf(c)))
        }
        val subject: FirBasedSymbol<*>? = if (scope == "apply") lam.receiverParameter?.symbol else lam.valueParameters.firstOrNull()?.symbol
        val ops = lam.body?.statements.orEmpty()
        for (s in ops) {
            val op = s as? FirFunctionCall
            val receiver = unwrap(op?.dispatchReceiver)
            val onSubject =
                when (receiver) {
                    is FirThisReceiverExpression -> receiver.calleeReference.boundSymbol == subject
                    is FirPropertyAccessExpression -> calleeOf(receiver) == subject
                    else -> false
                }
            if (op == null || !onSubject || !preparing(op, model)) {
                return Made.Broken(Problem("the lambda of $scope does something other than a ${model} operation on its receiver whose result is unused", posOf(s)))
            }
        }
        return Made.New(unwrap(c.extensionReceiver) as FirFunctionCall, ops.map { it as FirFunctionCall }, null)
    }

    /**
     * A local `val s = Semaphore(...)` that [call] passes: every other read of s is the receiver of a statement
     * `s.op()` of the block that declares s, after the declaration and before the statement that holds the pass, in
     * the same function or lambda.
     */
    private fun localObject(
        pass: FirPropertyAccessExpression,
        call: FirFunctionCall,
        model: String,
    ): Made {
        val sym = calleeOf(pass) as FirPropertySymbol
        val name = sym.name.asString()
        val decl = declarations[sym] as? FirProperty

        fun broken(
            reason: String,
            at: FirElement,
        ) = Made.Broken(Problem("$name $reason", posOf(at)))
        if (decl == null || !decl.isVal) return broken("is not a val", decl ?: pass)
        val init = unwrap(decl.initializer)
        if (!isCtor(init, model)) return broken("is not made by a new $model object", decl)
        val block = index.parent(decl) as? FirBlock ?: return broken("is not declared in a block", decl)
        val function = enclosing(decl) { it is FirFunction }
        if (enclosing(pass) { it is FirFunction } !== function) return broken("is passed inside another function or lambda than the one that makes it", call)
        val statements = block.statements

        // The index of the statement of the block that holds [e]; -1 if [e] is not in the block.
        fun statementOf(e: FirElement): Int {
            var x: FirElement = e
            while (index.parent(x) !== block) x = index.parent(x) ?: return -1
            return statements.indexOf(x)
        }
        val passAt = statementOf(pass)
        if (passAt < 0) return broken("is passed outside the block that declares it", call)
        val ops = mutableListOf<FirFunctionCall>()
        // Every read, the right side of an assignment included ([reads] leaves out every read under an assignment).
        val others = index.nodes.filterIsInstance<FirPropertyAccessExpression>().filter { calleeOf(it) == sym && it !== pass }
        for (read in others) {
            val owner = enclosing(read) { it is FirFunctionCall } as FirFunctionCall?
            val op = owner?.takeIf { it.dispatchReceiver === read && entriesOf(it).any { e -> e.model == model && e.role == Role.OP } }
            when {
                owner != null && constructedClass(calleeOf(owner)) != null && owner.arguments.any { unwrap(it) === read } -> return broken("is passed to more than one constructor", read)
                statementOf(read) > passAt -> return broken("is used after the statement that passes it", read)
                op == null -> return broken("is used other than as the receiver of a $model operation or as one constructor argument", read)
                op !in statements -> return broken("is the receiver of an operation whose result is used or that is not a statement of the block that declares it", op)
                !preparing(op, model) -> return broken("gets a $model operation that may throw before the pass", op)
                else -> ops += op
            }
        }
        return Made.New(init as FirFunctionCall, ops.sortedBy { statements.indexOf(it) }, decl)
    }

    /** True if [op] is a model operation of [model] that cannot throw: kotlinx `release` has a proof obligation. */
    private fun preparing(
        op: FirFunctionCall,
        model: String,
    ): Boolean = entriesOf(op).any { it.model == model && it.role == Role.OP && !(model == TrustedModels.KOTLINX_SEMAPHORE_MODEL && it.name == "release") }

    /** The reads of property [sym] in the module file. The target of an assignment is not a read; its value is. */
    private fun reads(sym: FirPropertySymbol): List<FirPropertyAccessExpression> =
        index.nodes.filterIsInstance<FirPropertyAccessExpression>().filter { calleeOf(it) == sym && (index.parent(it) as? FirVariableAssignment)?.lValue !== it }

    /** Each constructor call of [field]'s class in the module file, with the argument for [field] (or its default). */
    private fun constructions(field: ObjField): List<Pair<FirFunctionCall, FirExpression?>> =
        index.nodes.filterIsInstance<FirFunctionCall>().mapNotNull { call ->
            if (constructedClass(calleeOf(call))?.fields?.contains(field) != true) return@mapNotNull null
            val ctor = calleeOf(call) as FirConstructorSymbol
            val arg = call.resolvedArgumentMapping?.entries?.find { it.value.name.asString() == field.kotlin }?.key
            call to (arg ?: ctor.valueParameterSymbols.find { it.name.asString() == field.kotlin }?.let { p -> (declarations[p] as? FirValueParameter)?.defaultValue })
        }

    // ---- cells (DESIGN-v0.md 2.5) ----

    /**
     * Why local [sym] is not a cell of the action [found], or null if it is one: a local `val` initialized with
     * `AtomicReference(c)` or `AtomicBoolean(c)` for a constant c, whose every use is the receiver of `set` or `get`,
     * whose `set` calls are inside the action, and that no lambda captures except the action's. The function or
     * lambda that declares the cell (a Mono.fromCallable lambda, DESIGN-v0.md 8.3) reads it directly.
     */
    fun cellProblem(
        sym: FirBasedSymbol<*>,
        found: FoundAction,
    ): Problem? {
        val decl = declarations[sym] as? FirProperty ?: return Problem("${sym} is not a local of the module file", posOf(found.call))
        val name = decl.name.asString()
        val init = decl.initializer as? FirFunctionCall
        if (!decl.isVal || init == null || entriesOf(init).none { it.model == TrustedModels.CELL && it.role == Role.CTOR }) {
            return Problem("$name is not a val initialized with AtomicReference(...) or AtomicBoolean(...)", posOf(decl))
        }
        if (init.arguments.singleOrNull() !is FirLiteralExpression) return Problem("the initial value of cell $name is not a constant", posOf(init))
        val function = enclosing(decl) { it is FirFunction }
        for (read in reads(sym as? FirPropertySymbol ?: return null)) {
            val call = index.parent(read) as? FirFunctionCall
            val op = call?.takeIf { it.dispatchReceiver === read }?.let { c -> entriesOf(c).find { it.model == TrustedModels.CELL && it.role == Role.OP } }
            if (op == null) return Problem("cell $name escapes: it is used other than as the receiver of set or get", posOf(read))
            val lambda = enclosing(read) { it is FirAnonymousFunction || it === function }
            if (lambda is FirAnonymousFunction && lambda !== found.lambda && lambda !== function) return Problem("cell $name escapes: another lambda captures it", posOf(read))
            if (op.name == "set" && lambda !== found.lambda) return Problem("cell $name is set outside the action", posOf(call))
        }
        return null
    }

    /** The nearest proper ancestor of [e] that [p] accepts. */
    fun enclosing(
        e: FirElement,
        p: (FirElement) -> Boolean,
    ): FirElement? {
        var x = index.parent(e)
        while (x != null && !p(x)) x = index.parent(x)
        return x
    }

    // ---- Long arithmetic (DESIGN-v0.md 2.8) ----

    /** Call sites the models treat as unbounded Long arithmetic, by position. */
    private val unboundedSites: List<Pos> =
        index.nodes.filterIsInstance<FirFunctionCall>().filter { isUnbounded(it) }.map { posOf(it) }

    private fun isUnbounded(call: FirFunctionCall): Boolean {
        val sym = calleeOf(call) ?: return false
        val id = sym.callableId ?: return false
        return id.classId?.asString() in LONG_OPS && id.callableName.asString() in setOf("plus", "minus") ||
            entriesOf(sym).any { it.unbounded }
    }

    /** The ASSUMPTION diagnostic of [call]: `A<line>`, or `A<line>_<col>` if another site shares the line. */
    fun assumption(call: FirFunctionCall): String {
        val pos = posOf(call)
        val id = if (unboundedSites.count { it.line == pos.line } > 1) "A${pos.line}_${pos.col}" else "A${pos.line}"
        if (diagnostics.none { it.id == id }) {
            val type = (calleeOf(call)?.resolvedReturnType?.lowerBoundIfFlexible()?.classId?.shortClassName?.asString()) ?: "Long"
            diagnostics += Diagnostic(id, DiagnosticKind.ASSUMPTION, "$type unbounded: ${sourceText(call)}", pos)
        }
        return id
    }

    // ---- String abstraction (DESIGN-v0.md 2.13) ----

    /** The slot of [e]'s String value: a variable, a function (its result), a cell, or [e] itself. Null for `null`. */
    fun slotOf(e: FirExpression): Any? {
        val x = unwrap(e)
        if (x is FirLiteralExpression && x.kind == ConstantValueKind.Null) return null
        if (x is FirPropertyAccessExpression) return calleeOf(x) ?: x
        if (x is FirFunctionCall) {
            val sym = calleeOf(x)
            if (sym is FirNamedFunctionSymbol && sym in index.functions) return sym
            val receiver = unwrap(x.dispatchReceiver)
            if (receiver is FirPropertyAccessExpression && entriesOf(x).any { it.model == TrustedModels.CELL && it.name == "get" }) return calleeOf(receiver)
        }
        return x
    }

    /**
     * Collects the constraints of the action [found] and the helpers it calls: assignment, comparison, helper argument
     * and parameter, cell `set` value and cell, constructor argument and field; fixed points are the map key type for
     * compute's key and the template type for a matching template (2.13 rules 1-2). Field types are fixed in init.
     */
    fun collectAbstractions(
        found: FoundAction,
        keyType: String,
    ) {
        val keyArg = found.call.arguments.firstOrNull() ?: return
        abstraction.fix(slotOf(keyArg), keyType, posOf(keyArg))
        found.lambda?.valueParameters?.firstOrNull()?.let { abstraction.union(it.symbol, slotOf(keyArg)) }
        walkConstraints(listOfNotNull(found.lambda))
    }

    /**
     * Collects the String abstraction constraints (2.13) of [start] and of the module-file helpers they call; [onCall]
     * sees every call first (a client module fixes the slots of trusted calls there, DESIGN-E 6.4).
     */
    fun walkConstraints(
        start: List<FirElement>,
        onCall: (FirFunctionCall) -> Unit = {},
    ) {
        val visited = mutableSetOf<FirNamedFunctionSymbol>()
        val roots = ArrayDeque<FirElement>(start)
        while (roots.isNotEmpty()) {
            roots.removeFirst().accept(
                object : FirVisitorVoid() {
                    override fun visitElement(element: FirElement) {
                        constraint(element)
                        element.acceptChildren(this)
                    }

                    private fun constraint(e: FirElement) {
                        when (e) {
                            is FirProperty -> e.initializer?.let { abstraction.union(e.symbol, slotOf(it)) }
                            is FirVariableAssignment -> (e.lValue as? FirQualifiedAccessExpression)?.let { abstraction.union(calleeOf(it), slotOf(e.rValue)) }
                            is FirEqualityOperatorCall -> e.arguments.takeIf { it.size == 2 }?.let { (a, b) -> abstraction.union(slotOf(a), slotOf(b)) }
                            is FirReturnExpression -> (e.target.labeledElement as? FirNamedFunction)?.let { abstraction.union(it.symbol, slotOf(e.result)) }
                            is FirStringConcatenationCall -> templateOf(e)?.let { abstraction.fix(e, it.first.type, posOf(e)) }
                            // A String value of an if or when is each branch's value; that of `a?.s` is s's (AI-CODE.md 14.1 A2, A4).
                            is FirWhenExpression ->
                                if (isString(e)) e.branches.forEach { b -> (b.result.statements.lastOrNull() as? FirExpression)?.let { abstraction.union(slotOf(e), slotOf(it)) } }
                            is FirSafeCallExpression -> if (isString(e)) (e.selector as? FirExpression)?.let { abstraction.union(slotOf(e), slotOf(it)) }
                            is FirFunctionCall -> call(e)
                            else -> Unit
                        }
                    }

                    private fun call(c: FirFunctionCall) {
                        onCall(c)
                        val sym = calleeOf(c)
                        val mapping = c.resolvedArgumentMapping.orEmpty()
                        val helper = (sym as? FirNamedFunctionSymbol)?.let { index.functions[it] }
                        when {
                            helper != null -> {
                                mapping.forEach { (arg, param) -> abstraction.union(param.symbol, slotOf(arg)) }
                                if (visited.add(helper.symbol)) roots += helper
                            }
                            constructedClass(sym) != null -> {
                                val cls = constructedClass(sym)!!
                                mapping.forEach { (arg, param) ->
                                    val field = cls.fields.find { it.kotlin == param.name.asString() } ?: return@forEach
                                    abstraction.union(resolver.fields[cls.name to field.name], slotOf(arg))
                                }
                            }
                            entriesOf(c).any { it.model == TrustedModels.CELL && it.name == "set" } -> {
                                val receiver = unwrap(c.dispatchReceiver) as? FirPropertyAccessExpression ?: return
                                abstraction.union(calleeOf(receiver), slotOf(c.arguments.single()))
                            }
                        }
                    }
                },
            )
        }
    }

    /**
     * Adds the abstract type of each annotated free value (DESIGN-v0.md 4.2 restriction 4) to the String abstraction
     * as a fixed point. Call it after the code constraints are collected: a type the code fixes otherwise is a
     * sidecar error. Returns the errors.
     */
    fun fixAnnotations(): List<SidecarError> {
        val errors = mutableListOf<SidecarError>()
        for (op in sidecar.operations) {
            for ((item, target) in resolver.freeOf[op.name.text].orEmpty()) {
                val type = item.type ?: continue
                val sym = target as? FirBasedSymbol<*> ?: continue
                val others = abstraction.types(sym).filterKeys { it != type.text }
                if (others.isEmpty()) {
                    abstraction.fix(sym, type.text, Pos(type.pos.file, type.pos.line, type.pos.col))
                } else {
                    val uses = others.entries.joinToString { (t, p) -> "$t at ${p.file.substringAfterLast('/')}:${p.line}:${p.col}" }
                    errors += SidecarError(type.pos, "free-type", "free ${item.names[0].text}: ${type.text} contradicts the code, which uses '${item.source.name.text}' as $uses (DESIGN-v0.md 2.13)")
                }
            }
        }
        return errors
    }

    private fun isString(e: FirExpression): Boolean = e.resolvedType.lowerBoundIfFlexible().classId?.asString() == "kotlin/String"

    /** The template type [e] builds, with the Long part; null if no sidecar template matches its parts. */
    fun templateOf(e: FirStringConcatenationCall): Pair<Template, FirExpression>? {
        val parts = e.arguments
        return templates.firstNotNullOfOrNull { t ->
            // The constant parts; null stands for the Long part.
            val want =
                buildList {
                    if (t.prefix.isNotEmpty()) add(t.prefix)
                    add(null)
                    if (t.suffix.isNotEmpty()) add(t.suffix)
                }
            if (parts.size != want.size) return@firstNotNullOfOrNull null
            var long: FirExpression? = null
            for ((part, text) in parts.zip(want)) {
                if (text == null) {
                    if (part.resolvedType.lowerBoundIfFlexible().classId?.asString() !in LONG_OPS) return@firstNotNullOfOrNull null
                    long = part
                } else if ((part as? FirLiteralExpression)?.value != text) {
                    return@firstNotNullOfOrNull null
                }
            }
            long?.let { t to it }
        }
    }

    /** The Kotlin type of [v] (a variable declaration). */
    fun typeOf(v: FirVariable): ConeKotlinType = v.returnTypeRef.coneType
}

/** The trusted model entries of [sym], matched by owner, name and parameter types. */
internal fun modelEntries(sym: FirCallableSymbol<*>): List<Entry> {
    val id = sym.callableId ?: return emptyList()
    // A top-level function (owner: its package) matches only a factory, kotlinx `Semaphore(...)`; getOrPut is a MapCall.
    val owner = id.classId?.asString() ?: id.packageName.asString().replace('.', '/')
    // K2 names a constructor after its class; the table names every constructor `<init>`.
    val name = if (sym is FirConstructorSymbol) "<init>" else id.callableName.asString()
    val params = (sym as? FirFunctionSymbol<*>)?.valueParameterSymbols?.map { it.resolvedReturnType.lowerBoundIfFlexible().classId?.asString() }.orEmpty()
    return TrustedModels.lookup(owner, name, params).filter { id.classId != null || it.role == Role.CTOR }
}

/** [e] without its smart casts. */
internal fun unwrap(e: FirElement?): FirElement? {
    var x = e
    while (x is FirSmartCastExpression) x = x.originalExpression
    return x
}

internal fun unwrap(e: FirExpression?): FirExpression? = unwrap(e as FirElement?) as FirExpression?
