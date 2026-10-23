package kv.frontend

import kv.kvir.Pos
import kv.sidecar.ClassDecl
import kv.sidecar.FieldDecl
import kv.sidecar.FreeItem
import kv.sidecar.FreeSource
import kv.sidecar.OperationDecl
import kv.sidecar.SPos
import kv.sidecar.Selector
import kv.sidecar.Sidecar
import kv.sidecar.SidecarError
import kv.sidecar.StateDecl
import kv.sidecar.StateKind
import kv.sidecar.TrustedDecl
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.fir.unwrapSubstitutionOverrides
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import kotlin.reflect.KClass

/** A sidecar name bound to a Kotlin declaration. [kotlinPos] is null for a library declaration. */
data class Binding(
    val sidecar: String,
    val at: SPos,
    val kotlin: String,
    val kotlinPos: Pos?,
)

/** An operation v0 does not translate. Not a sidecar error: the module verdict becomes INCOMPLETE (U2, U3). */
data class UnsupportedOperation(
    val operation: String,
    val at: SPos,
    val reason: String,
)

data class Resolution(
    val bindings: List<Binding>,
    /** Each translatable operation's action: its compute call. */
    val actions: Map<String, ComputeSite>,
    val unsupported: List<UnsupportedOperation>,
)

private val CHM = ClassId.fromString("java/util/concurrent/ConcurrentHashMap")
private val ATOMIC_LONG = ClassId.fromString("java/util/concurrent/atomic/AtomicLong")
private val STRING = ClassId.fromString("kotlin/String")

/** Kotlin classes of the built-in field types (DESIGN-v0.md 4.2, FTYPE); a sidecar type is a String. */
private val FIELD_CLASSES =
    mapOf(
        "Semaphore" to ClassId.fromString("java/util/concurrent/Semaphore"),
        "AtomicBoolean" to ClassId.fromString("java/util/concurrent/atomic/AtomicBoolean"),
        "Long" to ClassId.fromString("kotlin/Long"),
    )

/** The class [fqn] if [file] declares it, at any nesting depth. */
@OptIn(DirectDeclarationsAccess::class)
fun findClass(
    file: FirFile,
    fqn: String,
): FirRegularClass? {
    fun search(classes: List<FirRegularClass>): FirRegularClass? =
        classes.firstNotNullOfOrNull { c ->
            if (c.symbol.classId.asSingleFqName().asString() == fqn) c else search(c.declarations.filterIsInstance<FirRegularClass>())
        }
    return search(file.declarations.filterIsInstance<FirRegularClass>())
}

/**
 * Binds every Kotlin name of a checked sidecar to a FIR symbol (DESIGN-v0.md 2.1 step 1, 2.2, 4.2 rule 3).
 * Runs in the checker phase on the file that declares the module object. [computes] are that file's compute calls.
 */
@OptIn(DirectDeclarationsAccess::class)
class SidecarResolver(
    private val sidecar: Sidecar,
    private val file: FirFile,
    private val session: FirSession,
    private val computes: List<FoundCompute>,
    private val module: FirRegularClass,
) {
    private val errors = mutableListOf<SidecarError>()
    private val bindings = mutableListOf<Binding>()
    private val unsupported = mutableListOf<UnsupportedOperation>()
    private val actions = linkedMapOf<String, ComputeSite>()
    private val positions = Positions(file)
    private val outer = module.symbol.classId.outerClassId?.let(::classSymbol)
    private val pkg = file.packageDirective.packageFqName
    private val classes = mutableMapOf<String, FirRegularClassSymbol>()
    private val trusted = mutableMapOf<String, FirNamedFunctionSymbol>()

    private fun err(
        pos: SPos,
        code: String,
        message: String,
    ) {
        errors += SidecarError(pos, code, message)
    }

    fun resolve(): Pair<Resolution, List<SidecarError>> {
        val decl = sidecar.modules.single()
        bind("module ${decl.fqn.text}", decl.pos, module.symbol)
        if (module.classKind != ClassKind.OBJECT) err(decl.fqn.pos, "kotlin-kind", "module ${decl.fqn.text} must be an object or a companion object")
        sidecar.classes.forEach(::cls)
        sidecar.states.forEach(::state)
        sidecar.trusted.forEach(::trustedFun)
        val claimed = mutableMapOf<FoundCompute, String>()
        sidecar.operations.forEach { operation(it, claimed) }
        return Resolution(bindings, actions, unsupported) to errors
    }

    // ---- symbols ----

    private fun classSymbol(id: ClassId): FirRegularClassSymbol? = session.symbolProvider.getClassLikeSymbolByClassId(id) as? FirRegularClassSymbol

    private fun nested(
        cls: FirRegularClassSymbol,
        name: String,
    ) = classSymbol(cls.classId.createNestedClassId(Name.identifier(name)))

    private fun <T : FirBasedSymbol<*>> members(
        cls: FirRegularClassSymbol,
        name: String,
        kind: KClass<T>,
    ): List<T> =
        cls.declarationSymbols.filter { kind.isInstance(it) && memberName(it) == name }.map {
            @Suppress("UNCHECKED_CAST")
            it as T
        }

    private fun memberName(sym: FirBasedSymbol<*>): String? = (sym as? FirCallableSymbol<*>)?.name?.asString()

    private fun <T : FirCallableSymbol<*>> topLevel(
        name: String,
        kind: KClass<T>,
    ): List<T> =
        session.symbolProvider.getTopLevelCallableSymbols(pkg, Name.identifier(name)).filter { kind.isInstance(it) }.map {
            @Suppress("UNCHECKED_CAST")
            it as T
        }

    /** A short class name: nested in the module object, nested in its outer class, or in the module's package. */
    private fun lookupClass(path: List<String>): FirRegularClassSymbol? {
        val first =
            listOfNotNull(module.symbol, outer).firstNotNullOfOrNull { nested(it, path[0]) }
                ?: classSymbol(ClassId(pkg, Name.identifier(path[0])))
                ?: return null
        return path.drop(1).fold(first as FirRegularClassSymbol?) { c, n -> c?.let { nested(it, n) } }
    }

    /** A short member name: in the module object, its outer class, then the package; `C.m` is member m of class C. */
    private fun <T : FirCallableSymbol<*>> lookupMember(
        path: List<String>,
        kind: KClass<T>,
    ): List<T> {
        if (path.size > 1) return lookupClass(path.dropLast(1))?.let { members(it, path.last(), kind) }.orEmpty()
        return listOfNotNull(module.symbol, outer).map { members(it, path[0], kind) }.firstOrNull { it.isNotEmpty() }
            ?: topLevel(path[0], kind)
    }

    /** A function written as a fully qualified name: package, then classes, then the function. */
    private fun fqnFunctions(path: List<String>): List<FirNamedFunctionSymbol> {
        for (k in path.size - 1 downTo 1) {
            val pkgName = FqName(path.take(k).joinToString("."))
            val rest = path.drop(k)
            val found =
                if (rest.size == 1) {
                    session.symbolProvider.getTopLevelFunctionSymbols(pkgName, Name.identifier(rest[0]))
                } else {
                    classSymbol(ClassId(pkgName, FqName(rest.dropLast(1).joinToString(".")), false))
                        ?.let { members(it, rest.last(), FirNamedFunctionSymbol::class) }
                        .orEmpty()
                }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun typeText(t: ConeKotlinType): String {
        val low = t.lowerBoundIfFlexible()
        return (low.classId?.asSingleFqName()?.asString() ?: low.toString()) + if (low.isMarkedNullable) "?" else ""
    }

    private fun signature(f: FirNamedFunctionSymbol) = f.valueParameterSymbols.map { typeText(it.resolvedReturnType) }

    // ---- bindings ----

    private fun posOf(sym: FirBasedSymbol<*>): Pos? {
        val source = sym.source ?: return null
        val container =
            when {
                sym is FirValueParameterSymbol || sym is FirPropertySymbol && sym.isLocal -> file
                sym is FirClassLikeSymbol<*> -> session.firProvider.getFirClassifierContainerFileIfAny(sym)
                sym is FirCallableSymbol<*> -> runCatching { session.firProvider.getFirCallableContainerFile(sym) }.getOrNull()
                else -> null
            } ?: return null
        return Positions(container).ofDeclaration(source)
    }

    private fun describe(sym: FirBasedSymbol<*>): String =
        when (sym) {
            is FirRegularClassSymbol -> "class ${sym.classId.asSingleFqName()}"
            is FirPropertySymbol -> if (sym.isLocal) "local ${sym.name}" else "property ${sym.callableId?.asSingleFqName()}"
            is FirValueParameterSymbol -> "param ${sym.name}"
            is FirNamedFunctionSymbol -> "fun ${sym.callableId.asSingleFqName()}(${signature(sym).joinToString()})"
            else -> sym.toString()
        }

    private fun bind(
        what: String,
        at: SPos,
        sym: FirBasedSymbol<*>,
        kotlinPos: Pos? = posOf(sym),
    ) {
        bindings += Binding(what, at, describe(sym), kotlinPos)
    }

    // ---- declarations ----

    private fun cls(c: ClassDecl) {
        val sym = lookupClass(c.kname.text.split('.'))
        if (sym == null) {
            err(c.kname.pos, "unresolved", "no class '${c.kname.text}' in the module object, its outer class or package $pkg")
            return
        }
        classes[c.name.text] = sym
        bind("class ${c.name.text}", c.pos, sym)
        c.fields.forEach { f ->
            val prop = members(sym, f.kname.text, FirPropertySymbol::class).firstOrNull()
            if (prop == null) {
                err(f.kname.pos, "missing-kotlin-field", "class ${sym.classId.asSingleFqName()} has no property '${f.kname.text}'")
            } else {
                bind("field ${c.name.text}.${f.name.text}", f.name.pos, prop)
                fieldType(f, prop)
            }
        }
    }

    private fun fieldType(
        f: FieldDecl,
        prop: FirPropertySymbol,
    ) {
        val t = prop.resolvedReturnType.lowerBoundIfFlexible()
        val expected = FIELD_CLASSES[f.type.text] ?: STRING
        if (t.classId != expected || t.isMarkedNullable != f.nullable) {
            val want = expected.asSingleFqName().asString() + if (f.nullable) "?" else ""
            err(f.kname.pos, "kotlin-type", "field ${f.name.text}: ${f.type.text}${if (f.nullable) "?" else ""} needs a Kotlin $want, but '${f.kname.text}' is ${typeText(t)}")
        }
    }

    private fun state(st: StateDecl) {
        val prop = lookupMember(st.kname.text.split('.'), FirPropertySymbol::class).firstOrNull()
        if (prop == null) {
            err(st.kname.pos, "unresolved", "no property '${st.kname.text}' in the module object, its outer class or package $pkg")
            return
        }
        bind("state ${st.name.text}", st.pos, prop)
        val t = prop.resolvedReturnType.lowerBoundIfFlexible()
        val ok =
            when (val kind = st.kind) {
                StateKind.Counter -> t.classId == ATOMIC_LONG
                is StateKind.MapOf -> {
                    val args = t.typeArguments.map { it.type?.lowerBoundIfFlexible()?.classId }
                    val value = classes[kind.value.text]?.classId
                    t.classId == CHM && args.size == 2 && args[0] == STRING && (value == null || args[1] == value)
                }
            }
        if (!ok) {
            val want = if (st.kind == StateKind.Counter) "an AtomicLong" else "a ConcurrentHashMap<String, ${(st.kind as StateKind.MapOf).value.text}>"
            err(st.kname.pos, "kotlin-type", "state ${st.name.text} needs $want, but '${st.kname.text}' is ${typeText(t)}")
        }
    }

    private fun trustedFun(t: TrustedDecl) {
        val path = t.fn.text.split('.')
        val candidates = lookupMember(path, FirNamedFunctionSymbol::class).ifEmpty { fqnFunctions(path) }
        val wanted = t.params.map { it.text }
        val match = candidates.filter { signature(it) == wanted }
        when {
            match.size == 1 -> {
                trusted[t.shortName] = match.single()
                bind("trusted ${t.shortName}", t.pos, match.single())
            }
            candidates.isEmpty() -> err(t.fn.pos, "unresolved", "no function '${t.fn.text}'")
            else -> err(t.fn.pos, "unresolved", "no '${t.fn.text}' takes ($wanted); candidates take ${candidates.map(::signature)}")
        }
    }

    // ---- operations ----

    private fun operation(
        op: OperationDecl,
        claimed: MutableMap<FoundCompute, String>,
    ) {
        val sel = op.selector
        val target = sel.target
        val found: FoundCompute =
            when (sel) {
                is Selector.Fun -> {
                    val fns = lookupMember(target.text.split('.'), FirNamedFunctionSymbol::class)
                    when {
                        fns.isEmpty() -> return err(target.pos, "unresolved", "no function '${target.text}'")
                        fns.size > 1 -> return err(target.pos, "ambiguous", "'${target.text}' names ${fns.size} overloads")
                    }
                    bind("operation ${op.name.text}", op.pos, fns.single())
                    val inFun = computes.filter { it.inFunction?.symbol == fns.single() }
                    if (inFun.size != 1) {
                        // U2: v0 translates an operation with exactly one action; otherwise the operation is unsupported.
                        unsupported += UnsupportedOperation(op.name.text, target.pos, "fun ${target.text} has ${inFun.size} compute calls ${inFun.map { at(it) }}; v0 translates an operation with exactly one")
                        return
                    }
                    inFun.single()
                }
                is Selector.ComputeIn -> {
                    val cls = lookupClass(target.text.split('.')) ?: return err(target.pos, "unresolved", "no class '${target.text}'")
                    bind("operation ${op.name.text}", op.pos, cls)
                    val inCls = computes.filter { it.inClass?.symbol == cls }
                    if (inCls.size != 1) {
                        return err(target.pos, "ambiguous-selector", "compute in ${target.text} matches ${inCls.size} compute calls ${inCls.map { at(it) }}; it must match exactly one")
                    }
                    inCls.single()
                }
            }
        claimed[found]?.let { return err(target.pos, "selector-overlap", "the compute call at ${at(found)} is already the action of operation $it") }
        claimed[found] = op.name.text
        actions[op.name.text] = found.site
        bindings += Binding("action ${op.name.text}", op.pos, "compute call", found.site.pos)
        op.free.forEach { free(op, it, found) }
    }

    private fun at(c: FoundCompute) = "${c.site.pos.line}:${c.site.pos.col}"

    private fun free(
        op: OperationDecl,
        item: FreeItem,
        found: FoundCompute,
    ) {
        val what = "free ${op.name.text}.${item.names.joinToString(", ") { it.text }}"
        val src = item.source
        val name = src.name.text
        when (src) {
            is FreeSource.Local -> {
                // The innermost visible declaration is the one the Kotlin code reads.
                val local = visibleLocals(found).lastOrNull { it.name.asString() == name }
                if (local == null) err(src.name.pos, "unresolved", "no local '$name' is visible at the compute call at ${at(found)}") else bind(what, item.pos, local.symbol)
            }
            is FreeSource.Param -> {
                val param = found.scopes.asReversed().flatMap { it.valueParameters }.firstOrNull { it.name.asString() == name }
                if (param == null) err(src.name.pos, "unresolved", "no parameter '$name' encloses the compute call at ${at(found)}") else bind(what, item.pos, param.symbol)
            }
            is FreeSource.This -> {
                val cls = found.inClass?.symbol
                val prop = cls?.let { members(it, name, FirPropertySymbol::class).firstOrNull() }
                if (prop == null) err(src.name.pos, "unresolved", "class ${cls?.classId?.asSingleFqName()} has no property '$name'") else bind(what, item.pos, prop)
            }
            is FreeSource.Call -> {
                val fn = trusted[name] ?: return // the trusted function did not resolve; that error is reported
                val calls = callsIn(found.lambda, fn)
                when (calls.size) {
                    0 -> err(src.name.pos, "unresolved", "the action at ${at(found)} makes no call to $name")
                    1 -> bind(what, item.pos, fn, positions.of(calls.single().source))
                    else -> err(src.name.pos, "ambiguous", "the action at ${at(found)} calls $name ${calls.size} times")
                }
            }
        }
    }

    /** Local properties declared before [found]'s call in the blocks that enclose it, outermost first. */
    private fun visibleLocals(found: FoundCompute): List<FirProperty> {
        val outermost = found.scopes.firstOrNull() ?: return emptyList()
        val blocks = ArrayDeque<MutableList<FirProperty>>()
        var visible: List<FirProperty>? = null
        outermost.accept(
            object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    if (visible == null) element.acceptChildren(this)
                }

                override fun visitBlock(block: FirBlock) {
                    blocks.addLast(mutableListOf())
                    visitElement(block)
                    blocks.removeLast()
                }

                override fun visitProperty(property: FirProperty) {
                    visitElement(property)
                    if (property.isLocal) blocks.lastOrNull()?.add(property)
                }

                override fun visitFunctionCall(functionCall: FirFunctionCall) {
                    if (functionCall === found.call) visible = blocks.flatten() else visitElement(functionCall)
                }
            },
        )
        return visible.orEmpty()
    }

    private fun callsIn(
        lambda: FirAnonymousFunction?,
        fn: FirNamedFunctionSymbol,
    ): List<FirFunctionCall> {
        val calls = mutableListOf<FirFunctionCall>()
        lambda?.body?.accept(
            object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) = element.acceptChildren(this)

                override fun visitFunctionCall(functionCall: FirFunctionCall) {
                    if (functionCall.calleeReference.toResolvedCallableSymbol()?.unwrapSubstitutionOverrides() == fn) calls += functionCall
                    functionCall.acceptChildren(this)
                }
            },
        )
        return calls
    }
}
