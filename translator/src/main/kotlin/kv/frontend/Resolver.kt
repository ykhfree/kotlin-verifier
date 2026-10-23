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
import kv.sidecar.reviewFingerprint
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirResolvedImport
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
    /** The selected Kotlin function. */
    val kotlinPos: Pos?,
)

data class Resolution(
    val bindings: List<Binding>,
    /** Each translatable operation's action: its compute call. */
    val actions: Map<String, ActionSite>,
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
    private val computes: List<FoundAction>,
    private val module: FirRegularClass,
    /** The req-shield checkout, where the source of a library trusted fun is found (B6); null if unknown. */
    private val reviewRoot: java.io.File? = null,
) {
    private val errors = mutableListOf<SidecarError>()
    private val bindings = mutableListOf<Binding>()
    private val unsupported = mutableListOf<UnsupportedOperation>()
    private val actions = linkedMapOf<String, ActionSite>()
    private val positions = Positions(file)
    private val outer = module.symbol.classId.outerClassId?.let(::classSymbol)
    private val pkg = file.packageDirective.packageFqName
    // The symbols behind the bindings, for the lowering. Valid only while the compiler session runs.
    internal val classes = mutableMapOf<String, FirRegularClassSymbol>()
    internal val trusted = mutableMapOf<String, FirNamedFunctionSymbol>()
    internal val states = mutableMapOf<String, FirPropertySymbol>()

    /** Sidecar class and field name to the Kotlin property. */
    internal val fields = mutableMapOf<Pair<String, String>, FirPropertySymbol>()

    /** Operation name to its action call. */
    internal val computeOf = mutableMapOf<String, FoundAction>()

    /** The functions `fun` operations select, translatable or not (DESIGN-B.md 3.3 rule 2). */
    internal val operationFunctions = mutableSetOf<FirNamedFunctionSymbol>()

    /** Operation name to the Kotlin declaration each `free` item names: a local, parameter, property or call. */
    internal val freeOf = mutableMapOf<String, MutableList<Pair<FreeItem, Any>>>()

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
        // DESIGN-B.md 2.1: a class is an instance module (each instance is one module).
        if (module.classKind != ClassKind.OBJECT && module.classKind != ClassKind.CLASS) {
            err(decl.fqn.pos, "kotlin-kind", "module ${decl.fqn.text} must be an object, a companion object or a class")
        }
        sidecar.classes.forEach(::cls)
        sidecar.states.forEach(::state)
        sidecar.trusted.forEach(::trustedFun)
        val claimed = mutableMapOf<FoundAction, String>()
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

    /**
     * A short class name: nested in the module object, nested in its outer class, in the module's package, or
     * imported by the module file (DESIGN-B.md 2.2 rule 6).
     */
    private fun lookupClass(path: List<String>): FirRegularClassSymbol? {
        val first =
            listOfNotNull(module.symbol, outer).firstNotNullOfOrNull { nested(it, path[0]) }
                ?: classSymbol(ClassId(pkg, Name.identifier(path[0])))
                ?: imported(path[0])
                ?: return null
        return path.drop(1).fold(first as FirRegularClassSymbol?) { c, n -> c?.let { nested(it, n) } }
    }

    /** The class an explicit (not `*`) import of the module file names [name], under its alias if it has one. */
    private fun imported(name: String): FirRegularClassSymbol? =
        file.imports.filterIsInstance<FirResolvedImport>().filter { !it.isAllUnder }.firstNotNullOfOrNull { import ->
            val simple = import.importedName ?: return@firstNotNullOfOrNull null
            if ((import.aliasName ?: simple).asString() != name) return@firstNotNullOfOrNull null
            classSymbol(import.resolvedParentClassId?.createNestedClassId(simple) ?: ClassId(import.packageFqName, simple))
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
            err(c.kname.pos, "unresolved", "no class '${c.kname.text}' in the module object, its outer class, package $pkg or the imports of ${file.name}")
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
                fields[c.name.text to f.name.text] = prop
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
        states[st.name.text] = prop
        val t = prop.resolvedReturnType.lowerBoundIfFlexible()
        // The key class: the Kotlin class an opaque type binds (DESIGN-B.md 2.2), else String (DESIGN-v0.md 2.13).
        val keyClass = (st.kind as? StateKind.MapOf)?.let { kind -> sidecar.types.find { it.name.text == kind.key.text }?.kclass?.let { ClassId.topLevel(FqName(it.text)) } } ?: STRING
        val ok =
            when (val kind = st.kind) {
                StateKind.Counter -> t.classId == ATOMIC_LONG
                is StateKind.MapOf -> {
                    val args = t.typeArguments.map { it.type?.lowerBoundIfFlexible()?.classId }
                    val value = classes[kind.value.text]?.classId
                    t.classId == CHM && args.size == 2 && args[0] == keyClass && (value == null || args[1] == value)
                }
            }
        if (!ok) {
            val want = if (st.kind == StateKind.Counter) "an AtomicLong" else "a ConcurrentHashMap<${keyClass.asSingleFqName().asString().removePrefix("kotlin.")}, ${(st.kind as StateKind.MapOf).value.text}>"
            err(st.kname.pos, "kotlin-type", "state ${st.name.text} needs $want, but '${st.kname.text}' is ${typeText(t)}")
        }
        // DESIGN-B.md 2.1 rule 6: the audit covers the module file for a private field and every source file for an
        // internal one; code outside the sources could reach any other field.
        val visibility = prop.resolvedStatus.visibility
        if (visibility != Visibilities.Private && visibility != Visibilities.Internal) {
            err(st.kname.pos, "state-visibility", "state ${st.name.text}: '${st.kname.text}' is ${visibility.name}; a state field must be private (the module file is audited) or internal (every source file is audited)")
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
                freshType(t, match.single())
                reviewed(t, match.single())
            }
            candidates.isEmpty() -> err(t.fn.pos, "unresolved", "no function '${t.fn.text}'")
            else -> err(t.fn.pos, "unresolved", "no '${t.fn.text}' takes ($wanted); candidates take ${candidates.map(::signature)}")
        }
    }

    /**
     * B6 (DESIGN-v0.md 8.3 "reviewed 지문"): the `reviewed` note of [t] records the fingerprint of the Kotlin
     * declaration of [fn] from `fun` to its end. A function of the compiled sources is read from its file; a library
     * function from its source under [reviewRoot] ([ReviewedSource.library]). A missing fingerprint, a stale one
     * (`stale-review`) and a source that cannot be found are errors; the messages carry the fingerprint of the text now.
     */
    private fun reviewed(
        t: TrustedDecl,
        fn: FirNamedFunctionSymbol,
    ) {
        if (t.reviewed == null) return // reported as unreviewed
        val at = t.reviewPos ?: t.pos
        val id = fn.callableId
        val classes = id.className?.pathSegments()?.map { it.asString() }.orEmpty()
        val source = fn.source
        val found =
            if (source != null) {
                val path = runCatching { session.firProvider.getFirCallableContainerFile(fn) }.getOrNull()?.sourceFile?.path
                val all = path?.let { ReviewedSource.declarations(java.io.File(it)) }.orEmpty()
                listOfNotNull(all.find { it.start == source.startOffset })
            } else if (reviewRoot != null) {
                ReviewedSource.library(reviewRoot, id.packageName.asString(), classes, id.callableName.asString(), fn.valueParameterSymbols.size)
            } else {
                return err(at, "review-source", "trusted fun ${t.shortName} is a library function, and no req-shield root is given to find its source")
            }
        val decl =
            found.singleOrNull() ?: return err(
                at,
                "review-source",
                "trusted fun ${t.shortName}: ${if (found.isEmpty()) "no" else "${found.size}"} source declaration(s) of ${id.asSingleFqName()} with ${fn.valueParameterSymbols.size} parameter(s)" +
                    (reviewRoot?.let { " under $it" } ?: ""),
            )
        val now = reviewFingerprint(decl.reviewedText)
        when (t.sha256) {
            null -> err(at, "missing-fingerprint", "the reviewed note of trusted fun ${t.shortName} has no fingerprint: write sha256 \"$now\" (${decl.where}) after it once a person has read the function")
            now -> Unit
            else -> err(at, "stale-review", "trusted fun ${t.shortName} changed after its review: the note records sha256 \"${t.sha256}\", the declaration at ${decl.where} now has sha256 \"$now\"; a person must review it again")
        }
    }

    /** `returns fresh C`: the Kotlin function returns C's class; type arguments are ignored (DESIGN-B.md 2.2 rule 5). */
    private fun freshType(
        t: TrustedDecl,
        fn: FirNamedFunctionSymbol,
    ) {
        val cls = t.fresh ?: return
        val want = classes[cls.text] ?: return // the class did not resolve; that error is reported
        val got = fn.resolvedReturnType.lowerBoundIfFlexible()
        if (got.classId != want.classId || got.isMarkedNullable) {
            err(cls.pos, "kotlin-type", "trusted fun ${t.shortName} returns fresh ${cls.text}, a ${want.classId.asSingleFqName()}, but ${t.fn.text} returns ${typeText(got)}")
        }
    }

    // ---- operations ----

    private fun operation(
        op: OperationDecl,
        claimed: MutableMap<FoundAction, String>,
    ) {
        val sel = op.selector
        val target = sel.target
        val found: FoundAction =
            when (sel) {
                is Selector.Fun -> {
                    val fns = lookupMember(target.text.split('.'), FirNamedFunctionSymbol::class)
                    when {
                        fns.isEmpty() -> return err(target.pos, "unresolved", "no function '${target.text}'")
                        fns.size > 1 -> return err(target.pos, "ambiguous", "'${target.text}' names ${fns.size} overloads")
                    }
                    val fn = fns.single()
                    operationFunctions += fn
                    bind("operation ${op.name.text}", op.pos, fn)
                    val fnFile = runCatching { session.firProvider.getFirCallableContainerFile(fn) }.getOrNull()
                    if (fnFile != file) {
                        // Decision 6: v0 translates only operations whose function is in the module's file.
                        val where = fnFile?.sourceFile?.name ?: "a library"
                        unsupported += UnsupportedOperation(op.name.text, target.pos, "fun ${target.text} is declared in $where, outside the module file ${file.name}; v0 translates operations of the module file only", posOf(fn))
                        return
                    }
                    val inFun = computes.filter { it.inFunction?.symbol == fn }
                    if (inFun.size != 1) {
                        // U2: an operation has exactly one action call; otherwise the operation is unsupported.
                        unsupported += UnsupportedOperation(op.name.text, target.pos, "fun ${target.text} has ${inFun.size} action calls ${inFun.map { at(it) }}; an operation has exactly one (DESIGN-v0.md 8.3 U2)", posOf(fn))
                        return
                    }
                    inFun.single()
                }
                is Selector.ComputeIn -> {
                    val cls = lookupClass(target.text.split('.')) ?: return err(target.pos, "unresolved", "no class '${target.text}'")
                    bind("operation ${op.name.text}", op.pos, cls)
                    val inCls = computes.filter { it.inClass?.symbol == cls }
                    if (inCls.size != 1) {
                        return err(target.pos, "ambiguous-selector", "compute in ${target.text} matches ${inCls.size} action calls ${inCls.map { at(it) }}; it must match exactly one")
                    }
                    inCls.single()
                }
            }
        claimed[found]?.let { return err(target.pos, "selector-overlap", "the compute call at ${at(found)} is already the action of operation $it") }
        claimed[found] = op.name.text
        actions[op.name.text] = found.site
        computeOf[op.name.text] = found
        bindings += Binding("action ${op.name.text}", op.pos, "${found.kind.method} call", found.site.pos)
        op.free.forEach { free(op, it, found) }
    }

    private fun at(c: FoundAction) = "${c.site.pos.line}:${c.site.pos.col}"

    private fun free(
        op: OperationDecl,
        item: FreeItem,
        found: FoundAction,
    ) {
        val what = "free ${op.name.text}.${item.names.joinToString(", ") { it.text }}"
        val src = item.source
        val name = src.name.text
        when (src) {
            is FreeSource.Local -> {
                // The innermost visible declaration is the one the Kotlin code reads.
                val local = visibleLocals(found).lastOrNull { it.name.asString() == name }
                if (local == null) err(src.name.pos, "unresolved", "no local '$name' is visible at the compute call at ${at(found)}") else freeBinding(op, item, local.symbol, what)
            }
            is FreeSource.Param -> {
                val param = found.scopes.asReversed().flatMap { it.valueParameters }.firstOrNull { it.name.asString() == name }
                if (param == null) err(src.name.pos, "unresolved", "no parameter '$name' encloses the compute call at ${at(found)}") else freeBinding(op, item, param.symbol, what)
            }
            is FreeSource.This -> {
                val cls = found.inClass?.symbol
                val prop = cls?.let { members(it, name, FirPropertySymbol::class).firstOrNull() }
                if (prop == null) err(src.name.pos, "unresolved", "class ${cls?.classId?.asSingleFqName()} has no property '$name'") else freeBinding(op, item, prop, what)
            }
            is FreeSource.Call -> {
                val fn = trusted[name] ?: return // the trusted function did not resolve; that error is reported
                // DESIGN-B.md 5.3 rule 6: the names bind the first call of the operation; without a call they are
                // parameters the code does not use (INFO, reported by the lowering).
                val call = callsIn(operationScope(op, found), fn).firstOrNull()
                bind(what, item.pos, fn, call?.let { positions.of(it.source) } ?: posOf(fn))
                if (call != null) freeOf.getOrPut(op.name.text) { mutableListOf() } += item to call
            }
        }
    }

    private fun freeBinding(
        op: OperationDecl,
        item: FreeItem,
        sym: FirCallableSymbol<*>,
        what: String,
    ) {
        bind(what, item.pos, sym)
        freeOf.getOrPut(op.name.text) { mutableListOf() } += item to sym
        freeType(item, sym)
    }

    /**
     * DESIGN-v0.md 4.2 restriction 4: a free value whose Kotlin type is String or String? names its abstract type,
     * and no other free value does. The lowering adds the type to the 2.13 inference as a fixed point.
     */
    private fun freeType(
        item: FreeItem,
        sym: FirCallableSymbol<*>,
    ) {
        val t = sym.resolvedReturnType.lowerBoundIfFlexible()
        val name = item.names[0]
        val annotation = item.type
        val kotlin = "'${item.source.name.text}' is a Kotlin ${typeText(t)}"
        if (t.classId == STRING && annotation == null) {
            val fix = sidecar.types.joinToString(" or ") { ": ${it.name.text}" }.ifEmpty { "a sidecar type" }
            err(name.pos, "free-type", "free ${name.text}: $kotlin, so it needs an abstract type; add $fix to free ${name.text}")
        } else if (t.classId != STRING && annotation != null) {
            err(annotation.pos, "free-type", "free ${name.text}: $kotlin, not a String; only a String free value takes an abstract type, so remove ': ${annotation.text}'")
        }
    }

    /** Local properties declared before [found]'s call in the blocks that enclose it, outermost first. */
    private fun visibleLocals(found: FoundAction): List<FirProperty> {
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

    /** Where an operation's calls are: the function of a `fun` operation, else the action's lambda. */
    private fun operationScope(
        op: OperationDecl,
        found: FoundAction,
    ): FirElement? = if (op.selector is Selector.Fun) found.inFunction else found.lambda

    /** The calls of [fn] in [scope], in source order. */
    private fun callsIn(
        scope: FirElement?,
        fn: FirNamedFunctionSymbol,
    ): List<FirFunctionCall> {
        val calls = mutableListOf<FirFunctionCall>()
        scope?.accept(
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
