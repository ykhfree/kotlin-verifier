package kv.frontend

import kv.kvir.Pos
import kv.model.TrustedModels
import kv.sidecar.ClassDecl
import kv.sidecar.EnumDecl
import kv.sidecar.HoleDecl
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
import kv.sidecar.stripComments
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirResolvedImport
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirDoWhileLoop
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirWhileLoop
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.scopes.unsubstitutedScope
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirEnumEntrySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeFlexibleType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
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

private val LONG = ClassId.fromString("kotlin/Long")
private val INT = ClassId.fromString("kotlin/Int")
private val BOOLEAN = ClassId.fromString("kotlin/Boolean")

/** Packages of the JDK and the Kotlin library: a trusted fun there has no source, so its signature is reviewed. */
private val JDK_PACKAGES = listOf("java.", "javax.", "kotlin.")

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

    /** Sidecar class and field name to the trusted model of a flattened field, which the Kotlin type chooses. */
    internal val fieldModels = mutableMapOf<Pair<String, String>, String>()

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
        if (sidecar.client) {
            resolveClient()
            return Resolution(bindings, actions, unsupported) to errors
        }
        sidecar.classes.forEach(::cls)
        sidecar.states.forEach(::state)
        sidecar.trusted.forEach(::trustedFun)
        val claimed = mutableMapOf<FoundAction, String>()
        sidecar.operations.forEach { operation(it, claimed) }
        return Resolution(bindings, actions, unsupported) to errors
    }

    // ---- client modules (DESIGN-E 6.1, 6.2, 6.10 E1) ----

    /** Sidecar enum name to its Kotlin enum class and entry names in declaration order. */
    internal val enums = linkedMapOf<String, Pair<FirRegularClassSymbol, List<String>>>()

    /** Short name of each `trusted value` to its function-typed property. */
    internal val trustedValues = mutableMapOf<String, FirPropertySymbol>()

    /** Client operation name to its entry function. */
    internal val entryOf = linkedMapOf<String, FirNamedFunctionSymbol>()

    /** Client operation name to its free values, each with the parameter or the property path it reads. */
    internal val clientFree = mutableMapOf<String, MutableList<Pair<FreeItem, List<FirCallableSymbol<*>>>>>()

    /** Each `hole loop` with the loop it names (a FirWhileLoop or FirDoWhileLoop). */
    internal val loopHoles = mutableMapOf<HoleDecl, FirElement>()

    private fun resolveClient() {
        sidecar.enums.forEach(::enumDecl)
        sidecar.classes.forEach(::cls)
        sidecar.trusted.forEach { if (it.value) trustedValue(it) else trustedFun(it) }
        sidecar.operations.forEach(::entry)
        sidecar.holes.filter { it.loop != null }.forEach(::loopHole)
    }

    private fun enumDecl(e: EnumDecl) {
        val sym = lookupClass(e.kclass.text.split('.')) ?: classSymbol(ClassId.topLevel(FqName(e.kclass.text)))
        if (sym == null || sym.classKind != ClassKind.ENUM_CLASS) {
            err(e.kclass.pos, "unresolved", "no enum class '${e.kclass.text}' in the module's package or the imports of ${file.name}")
            return
        }
        enums[e.name.text] = sym to sym.declarationSymbols.filterIsInstance<FirEnumEntrySymbol>().map { it.name.asString() }
        bind("enum ${e.name.text}", e.pos, sym)
    }

    /**
     * True if the sidecar value type [type] (DESIGN-E 6.2, `?` for nullable) stands for Kotlin type [t]: Int for Int,
     * Long or a type parameter (an arbitrary value of it), Bool for Boolean, a sidecar type for String, an enum for its
     * Kotlin enum class, a class for its Kotlin class. Nullability must agree, except for a Java (flexible) type and a
     * type parameter.
     */
    private fun stands(
        type: String,
        t: ConeKotlinType,
    ): Boolean {
        val nullable = type.endsWith("?")
        val base = type.removeSuffix("?")
        val low = t.lowerBoundIfFlexible()
        if (low is ConeTypeParameterType) return base == "Int"
        if (t !is ConeFlexibleType && low.isMarkedNullable != nullable) return false
        val id = low.classId ?: return false
        return when {
            base == "Int" -> id == INT || id == LONG
            base == "Bool" -> id == BOOLEAN
            sidecar.types.any { it.name.text == base } -> id == (sidecar.types.first { it.name.text == base }.kclass?.let { ClassId.topLevel(FqName(it.text)) } ?: STRING)
            base in enums -> id == enums.getValue(base).first.classId
            base in classes -> id == classes.getValue(base).classId
            else -> false
        }
    }

    /** A client class field (DESIGN-E 6.2): its sidecar type stands for the Kotlin property type ([stands]). */
    private fun clientField(
        c: ClassDecl,
        f: FieldDecl,
        prop: FirPropertySymbol,
    ) {
        val type = f.type.text + if (f.nullable) "?" else ""
        if (!stands(type, prop.resolvedReturnType)) {
            err(f.kname.pos, "kotlin-type", "field ${f.name.text}: $type does not stand for '${f.kname.text}', a Kotlin ${typeText(prop.resolvedReturnType)}")
        }
    }

    /** One parameter or result list position: a named one must [stand][stands] for its Kotlin type, a bare one name it. */
    private fun matches(
        t: TrustedDecl,
        index: Int,
        kotlin: ConeKotlinType,
    ): Boolean {
        val named = t.named.getOrNull(index)
        return if (named != null) stands(named.type.text, kotlin) else t.params[index].text == typeText(kotlin)
    }

    /** The result type of [t] stands for [kotlin], or [t] has no result. */
    private fun resultMatches(
        t: TrustedDecl,
        kotlin: ConeKotlinType,
    ): Boolean = t.returns.isEmpty() || t.returns.size == 1 && stands(t.returns[0].type.text, kotlin)

    /**
     * A `trusted fun` of a client module: a bare parameter names its Kotlin type, as in an object module; a named one
     * must stand for it ([stands]); the result must stand for the Kotlin result type.
     */
    private fun clientTrustedFun(t: TrustedDecl) {
        val path = t.fn.text.split('.')
        val candidates = lookupMember(path, FirNamedFunctionSymbol::class).ifEmpty { fqnFunctions(path) }.flatMap(::enhanced)
        val match =
            candidates.filter { f ->
                f.valueParameterSymbols.size == t.params.size &&
                    f.valueParameterSymbols.withIndex().all { (i, p) -> matches(t, i, p.resolvedReturnType) } && resultMatches(t, f.resolvedReturnType)
            }
        when {
            match.size == 1 -> {
                trusted[t.shortName] = match.single()
                bind("trusted ${t.shortName}", t.pos, match.single())
                reviewed(t, match.single())
            }
            candidates.isEmpty() -> err(t.fn.pos, "unresolved", "no function '${t.fn.text}'")
            else -> err(t.fn.pos, "kotlin-type", "no '${t.fn.text}' takes (${t.params.joinToString { it.text }}) and returns ${t.returns.map { it.type.text }}; candidates take ${candidates.map { "${signature(it)} -> ${typeText(it.resolvedReturnType)}" }}")
        }
    }

    /**
     * A Java method as the use-site scope of its class gives it: with its types enhanced, so that its result type can
     * be read (the declared Java member computes it lazily, which the checker phase cannot). Any other function as is.
     */
    private fun enhanced(f: FirNamedFunctionSymbol): List<FirNamedFunctionSymbol> {
        if (f.origin !is FirDeclarationOrigin.Java) return listOf(f)
        val cls = f.callableId.classId?.let(::classSymbol) ?: return listOf(f)
        val out = mutableListOf<FirNamedFunctionSymbol>()
        cls.unsubstitutedScope(session, ScopeSession(), false, null).processFunctionsByName(f.name) { out += it }
        return out.filter { it.valueParameterSymbols.size == f.valueParameterSymbols.size }.ifEmpty { listOf(f) }
    }

    /**
     * `trusted value KNAME(PARAMS)` (DESIGN-E 6.2): a property of a Kotlin function type `(P1, ..., Pn) -> R`; each
     * parameter and the result must match ([matches], [resultMatches]).
     */
    private fun trustedValue(t: TrustedDecl) {
        val prop = lookupMember(t.fn.text.split('.'), FirPropertySymbol::class).firstOrNull() ?: return err(t.fn.pos, "unresolved", "no property '${t.fn.text}'")
        bind("trusted value ${t.shortName}", t.pos, prop)
        val type = prop.resolvedReturnType.lowerBoundIfFlexible()
        val fn = type.classId?.takeIf { it.packageFqName.asString() == "kotlin" && it.shortClassName.asString().startsWith("Function") }
        val args = type.typeArguments.map { it.type }
        if (fn == null || args.any { it == null }) return err(t.fn.pos, "kotlin-type", "trusted value ${t.fn.text}: '${t.fn.text}' is a ${typeText(type)}, not a Kotlin function type")
        val params = args.dropLast(1).map { it!! }
        val ok = params.size == t.params.size && params.withIndex().all { (i, p) -> matches(t, i, p) } && resultMatches(t, args.last()!!)
        if (!ok) {
            val want = "(${t.params.joinToString { it.text }}) -> ${t.returns.singleOrNull()?.type?.text ?: "any"}"
            return err(t.fn.pos, "kotlin-type", "trusted value ${t.fn.text} is declared $want, but '${t.fn.text}' is a ${typeText(type)}")
        }
        trustedValues[t.shortName] = prop
        reviewedValue(t, prop)
    }

    /**
     * DESIGN-E 2.5: a trusted value has no body, so its `reviewed` note records the fingerprint of its declaration, a
     * property or a constructor `val` parameter, from its first modifier keyword (else `val`/`var`) to its end.
     */
    private fun reviewedValue(
        t: TrustedDecl,
        prop: FirPropertySymbol,
    ) {
        if (t.reviewed == null) return // reported as unreviewed, or not required in a client draft
        val at = t.reviewPos ?: t.pos
        val id = prop.callableId ?: return err(at, "review-source", "trusted value ${t.shortName} is a local property")
        val classes = id.className?.pathSegments()?.map { it.asString() }.orEmpty()
        val path = runCatching { session.firProvider.getFirCallableContainerFile(prop) }.getOrNull()?.sourceFile?.path
        val found = path?.let { ReviewedSource.values(java.io.File(it)) }.orEmpty().filter { it.classes == classes && it.name == prop.name.asString() }
        val decl = found.singleOrNull() ?: return err(at, "review-source", "trusted value ${t.shortName}: ${found.size} source declaration(s) of ${id.asSingleFqName()}")
        fingerprint(t, "trusted value", decl.reviewedText, "the declaration at ${decl.where}")
    }

    /** A client operation: a public function of the module class (DESIGN-E 6.1), and its free values (3.6 layer 1). */
    private fun entry(op: OperationDecl) {
        val sel = op.selector as? Selector.Fun ?: return err(op.selector.target.pos, "client-item", "a client operation selects a public function: `fun CLASS.NAME` (DESIGN-E 6.1)")
        val fns = lookupMember(sel.target.text.split('.'), FirNamedFunctionSymbol::class)
        val fn =
            when (fns.size) {
                0 -> return err(sel.target.pos, "unresolved", "no function '${sel.target.text}'")
                1 -> fns.single()
                else -> return err(sel.target.pos, "ambiguous", "'${sel.target.text}' names ${fns.size} overloads")
            }
        if (fn.callableId.classId != module.symbol.classId || fn.resolvedStatus.visibility != Visibilities.Public) {
            return err(sel.target.pos, "client-entry", "fun ${sel.target.text} is not a public function of module class ${module.symbol.classId.asSingleFqName()} (DESIGN-E 6.1)")
        }
        entryOf[op.name.text] = fn
        bind("entry ${op.name.text}", op.pos, fn)
        for (item in op.free) clientFreeValue(op, item, fn)
    }

    /** A free value of a client entry: a parameter, or a path of `val` properties from `this` (an instance value). */
    private fun clientFreeValue(
        op: OperationDecl,
        item: FreeItem,
        fn: FirNamedFunctionSymbol,
    ) {
        val what = "free ${op.name.text}.${item.names.joinToString(", ") { it.text }}"
        val src = item.source
        val path: List<FirCallableSymbol<*>> =
            when (src) {
                is FreeSource.Param -> listOf(fn.valueParameterSymbols.find { it.name.asString() == src.name.text } ?: return err(src.name.pos, "unresolved", "fun ${fn.name} has no parameter '${src.name.text}'"))
                is FreeSource.This -> {
                    var owner: FirRegularClassSymbol? = module.symbol
                    src.name.text.split('.').map { seg ->
                        val prop = owner?.let { members(it, seg, FirPropertySymbol::class).firstOrNull() } ?: return err(src.name.pos, "unresolved", "no property '$seg' in ${owner?.classId?.asSingleFqName() ?: "a value without a class"} (path this.${src.name.text})")
                        if (!prop.isVal) return err(src.name.pos, "free-var", "$what: '$seg' is a var; an instance value is a path of vals (DESIGN-v0.md 2.12)")
                        owner = prop.resolvedReturnType.lowerBoundIfFlexible().classId?.let(::classSymbol)
                        prop
                    }
                }
                else -> return // a sidecar error (ClientChecks)
            }
        val t = path.last().resolvedReturnType
        val id = t.lowerBoundIfFlexible().classId
        val annotation = item.type
        val ok =
            when {
                id == STRING -> annotation != null && stands(annotation.text, t)
                annotation != null -> false
                else -> id == INT || id == LONG || id == BOOLEAN || enums.values.any { it.first.classId == id }
            }
        if (!ok) {
            val fix = if (id == STRING) "a String value names its sidecar type (: Key)" else "a free value is an Int, Long, Boolean or sidecar enum value; only a String takes ': TYPE'"
            return err(item.names[0].pos, "free-type", "$what: '${src.name.text}' is a Kotlin ${typeText(t)}; $fix")
        }
        // The same path, compared as the lowering compares it (ClientLowering: callableId per segment).
        fun ids(p: List<FirCallableSymbol<*>>) = p.map { it.callableId.toString() }
        if (duplicateFree(op, item, ids(path), clientFree[op.name.text].orEmpty().map { it.first to ids(it.second) })) return
        bind(what, item.pos, path.last(), posOf(path.last()))
        clientFree.getOrPut(op.name.text) { mutableListOf() } += item to path
    }

    /**
     * `hole loop KNAME [#N]` (DESIGN-E 6.8, 12.5): the loops of function KNAME of the module class, in source pre-order
     * (outer before inner), are its FirWhileLoop and FirDoWhileLoop nodes, those in its lambdas too. KNAME names the
     * only one, KNAME#N the N-th. Two holes may not name one loop.
     */
    private fun loopHole(h: HoleDecl) {
        val loop = h.loop!!
        val fn = lookupMember(loop.fn.text.split('.'), FirNamedFunctionSymbol::class).singleOrNull() ?: return err(loop.fn.pos, "unresolved", "no single function '${loop.fn.text}'")
        val decl = module.declarations.filterIsInstance<FirNamedFunction>().find { it.symbol == fn } ?: return err(loop.fn.pos, "unresolved", "fun ${loop.fn.text} is not a function of module class ${module.name}")
        val loops = mutableListOf<FirElement>()
        decl.accept(
            object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    if (element is FirWhileLoop || element is FirDoWhileLoop) loops += element
                    element.acceptChildren(this)
                }
            },
        )
        val what = "hole loop ${loop.label}: fun ${fn.name}"
        val at =
            when {
                loop.ordinal != null -> loops.getOrNull(loop.ordinal - 1) ?: return err(h.pos, "no-loop", "$what has ${loops.size} loop(s), so #${loop.ordinal} names none")
                loops.isEmpty() -> return err(h.pos, "no-loop", "$what has no loop")
                loops.size > 1 -> {
                    val names = loops.indices.map { "${loop.fn.text}#${it + 1}" }
                    val lines = loops.joinToString { positions.of(it.source)?.line?.toString() ?: "?" }
                    return err(h.pos, "ambiguous", "$what has ${loops.size} loops (lines $lines); name one: ${names.dropLast(1).joinToString()} or ${names.last()}")
                }
                else -> loops.single()
            }
        loopHoles.entries.find { it.value === at }?.let { (other, _) ->
            return err(h.pos, "duplicate-decl", "hole loop ${loop.label} names the loop of hole loop ${other.loop!!.label} at ${other.pos}")
        }
        loopHoles[h] = at
        bindings += Binding("hole loop ${loop.label}", h.pos, "loop of fun ${fn.callableId.asSingleFqName()}", positions.of(at.source))
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
                if (sidecar.client) clientField(c, f, prop) else fieldType(c, f, prop)
            }
        }
    }

    /**
     * The Kotlin type of field [f] (DESIGN-v0.md 4.2, FTYPE): a flattened type binds a Kotlin class of one of its
     * models, and that class chooses the model (TrustedModels.fieldModels); Long binds kotlin.Long; a sidecar type binds
     * String. A Semaphore field's chosen model must be declared with `use`, since two models share the type.
     */
    private fun fieldType(
        c: ClassDecl,
        f: FieldDecl,
        prop: FirPropertySymbol,
    ) {
        val t = prop.resolvedReturnType.lowerBoundIfFlexible()
        val models = TrustedModels.fieldModels[f.type.text].orEmpty()
        val expected = models.keys.map(ClassId::fromString).ifEmpty { listOf(if (f.type.text == "Long") LONG else STRING) }
        if (t.classId !in expected || t.isMarkedNullable != f.nullable) {
            val want = expected.joinToString(" or ") { it.asSingleFqName().asString() + if (f.nullable) "?" else "" }
            err(f.kname.pos, "kotlin-type", "field ${f.name.text}: ${f.type.text}${if (f.nullable) "?" else ""} needs a Kotlin $want, but '${f.kname.text}' is ${typeText(t)}")
            return
        }
        val model = models[t.classId!!.asString()] ?: return
        fieldModels[c.name.text to f.name.text] = model
        if (f.type.text == "Semaphore" && sidecar.uses.none { it.model.text == model }) {
            err(f.type.pos, "missing-use", "field ${f.name.text}: '${f.kname.text}' is a ${typeText(t)}, whose trusted model is $model; the sidecar needs `use $model`")
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
        if (sidecar.client) return clientTrustedFun(t)
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
     * declaration of [fn] from its first modifier keyword (else `fun`) to its end. A function of the compiled sources is read from its file; a library
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
        // DESIGN-E 2.5: a JDK or Kotlin library function has no source here; its reviewed unit is its signature.
        if (source == null && JDK_PACKAGES.any { id.packageName.asString().startsWith(it) }) {
            val text = "${id.asSingleFqName()}(${signature(fn).joinToString()}): ${typeText(fn.resolvedReturnType)}"
            return fingerprint(t, "trusted fun", text, "the library signature $text")
        }
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
        fingerprint(t, "trusted fun", decl.reviewedText, "the declaration at ${decl.where}")
    }

    /**
     * Compares the fingerprint of the reviewed unit of [t] with the one its note records (B6). The unit is [kotlin], the
     * Kotlin text that [unit] names. Under `review intent` (DESIGN-E 12.4, T-A) it is the contract text of [t] without
     * comments, a newline, then [kotlin]: a changed `throws`, `creates` or `ensures` needs a new review too.
     */
    private fun fingerprint(
        t: TrustedDecl,
        what: String,
        kotlin: String,
        unit: String,
    ) {
        val at = t.reviewPos ?: t.pos
        val now = reviewFingerprint(if (sidecar.reviewIntent) stripComments(t.contract) + "\n" + kotlin else kotlin)
        val covered = if (sidecar.reviewIntent) "the contract and $unit" else unit
        when (t.sha256) {
            null -> err(at, "missing-fingerprint", "the reviewed note of $what ${t.shortName} has no fingerprint: write sha256 \"$now\" ($covered) after it once a person has read it")
            now -> Unit
            else -> err(at, "stale-review", "$what ${t.shortName} changed after its review: the note records sha256 \"${t.sha256}\", $covered now has sha256 \"$now\"; a person must review it again")
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
                if (call != null && duplicateFree(op, item, call, freeOf[op.name.text].orEmpty().map { it.first to it.second })) return
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
        if (duplicateFree(op, item, sym, freeOf[op.name.text].orEmpty().map { it.first to it.second })) return
        bind(what, item.pos, sym)
        freeOf.getOrPut(op.name.text) { mutableListOf() } += item to sym
        freeType(item, sym)
    }

    /**
     * JG4 B1 (DESIGN-C.md 10.16): two free items of one operation may not bind one value. Each name of a clause would
     * then be the same value, and today such a sidecar resolves and fails later with no pointer to the free line.
     * [bound] holds the items of [op] bound so far with their targets. True if [item] repeats one; the error is reported.
     */
    private fun duplicateFree(
        op: OperationDecl,
        item: FreeItem,
        target: Any,
        bound: List<Pair<FreeItem, Any>>,
    ): Boolean {
        val first = bound.find { it.second == target }?.first ?: return false
        fun names(i: FreeItem) = "free ${op.name.text}.${i.names.joinToString(", ") { it.text }}"
        err(item.names[0].pos, "free-duplicate", "${names(item)} binds the value that ${names(first)} binds (${first.source.name.text}); each free item of an operation names its own value")
        return true
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
