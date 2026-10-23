package kv.frontend

import kv.kvir.Pos
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.com.intellij.lang.LighterASTNode
import org.jetbrains.kotlin.com.intellij.openapi.util.Ref
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirFunctionConversionKind
import org.jetbrains.kotlin.fir.expressions.FirFunctionTypeConversionExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.unwrapSubstitutionOverrides
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * A `ConcurrentHashMap` call that can be one atomic action of a module (DESIGN-v0.md 2.2, DESIGN-B.md 2.3, 2.5):
 * [method] is the name of the Java member or, for [SET] and [GET_OR_PUT], of the Kotlin stdlib extension.
 */
enum class MapCall(
    val method: String,
) {
    COMPUTE("compute"),
    COMPUTE_IF_ABSENT("computeIfAbsent"),
    PUT_IF_ABSENT("putIfAbsent"),
    PUT("put"),

    /** `m[k] = v`: the stdlib extension `kotlin.collections.set` on a ConcurrentHashMap. Its body is `put(k, v)`. */
    SET("set"),
    REMOVE("remove"),

    /** The stdlib extension `getOrPut` with a `ConcurrentMap` receiver (not the `MutableMap` overload). */
    GET_OR_PUT("getOrPut"),
}

/** A [MapCall] in a source file: one candidate action (DESIGN-v0.md 2.2, DESIGN-B.md 5.3 rule 1). */
data class ActionSite(
    /** The call. Its column is where the receiver expression starts. */
    val pos: Pos,
    /** Name of the property the call is made on, e.g. `lockMap`; null if the receiver is not a property read. */
    val receiver: String?,
    /** FQN of the innermost enclosing class or object; null for a top-level call. Lambdas are transparent. */
    val inClass: String?,
    /** Name of the innermost enclosing named function; null outside functions. Lambdas are transparent. */
    val inFunction: String?,
    /** The lambda literal passed as the function argument; null if the call takes none or it is not a lambda literal. */
    val lambda: Pos?,
    val kind: MapCall = MapCall.COMPUTE,
)

/** A [ActionSite] with the FIR nodes it came from. Valid only while the compiler session runs. */
class FoundAction(
    val site: ActionSite,
    val call: FirFunctionCall,
    val lambda: FirAnonymousFunction?,
    val inClass: FirRegularClass?,
    val inFunction: FirNamedFunction?,
    /** Enclosing functions and lambdas, outermost first. Does not include [lambda]. */
    val scopes: List<FirFunction>,
) {
    val kind: MapCall get() = site.kind

    /** The map the call works on: the dispatch receiver of a member, the extension receiver of [MapCall.SET] and [MapCall.GET_OR_PUT]. */
    val receiver: FirExpression? get() = call.dispatchReceiver ?: call.extensionReceiver
}

private val CHM = ClassId.fromString("java/util/concurrent/ConcurrentHashMap")
private val CONCURRENT_MAP = ClassId.fromString("java/util/concurrent/ConcurrentMap")
private val BI_FUNCTION = ClassId.fromString("java/util/function/BiFunction")
private val FUNCTION = ClassId.fromString("java/util/function/Function")
private val STDLIB_SET = CallableId(FqName("kotlin.collections"), Name.identifier("set"))
private val STDLIB_GET_OR_PUT = CallableId(FqName("kotlin.collections"), Name.identifier("getOrPut"))

/**
 * The [MapCall] that [call] resolves to, or null. A member must be the `ConcurrentHashMap` one with the modeled
 * parameters (the callable id does not tell overloads apart, so the parameter types are compared too). `set` must have
 * a `ConcurrentHashMap` receiver, and `getOrPut` must be its `ConcurrentMap` overload (DESIGN-B.md 2.5).
 */
fun mapCallOf(call: FirFunctionCall): MapCall? {
    val sym = call.calleeReference.toResolvedCallableSymbol()?.unwrapSubstitutionOverrides() as? FirNamedFunctionSymbol ?: return null
    val params = sym.valueParameterSymbols.map { it.resolvedReturnType.lowerBoundIfFlexible().classId }
    val id = sym.callableId
    if (id.classId == CHM) {
        return when (id.callableName.asString()) {
            "compute" -> MapCall.COMPUTE.takeIf { params.size == 2 && params[1] == BI_FUNCTION }
            "computeIfAbsent" -> MapCall.COMPUTE_IF_ABSENT.takeIf { params.size == 2 && params[1] == FUNCTION }
            "putIfAbsent" -> MapCall.PUT_IF_ABSENT.takeIf { params.size == 2 }
            "put" -> MapCall.PUT.takeIf { params.size == 2 }
            "remove" -> MapCall.REMOVE.takeIf { params.size == 1 }
            else -> null
        }
    }
    return when (id) {
        STDLIB_SET -> MapCall.SET.takeIf { call.extensionReceiver?.resolvedType?.lowerBoundIfFlexible()?.classId == CHM }
        // The overload decides: the ConcurrentMap one reads, calls the function, then putIfAbsent; the MutableMap one
        // reads, calls the function, then put (stdlib 2.0.21 MapsJVM.kt:70-75, Maps.kt:380-389).
        STDLIB_GET_OR_PUT -> MapCall.GET_OR_PUT.takeIf { sym.resolvedReceiverType?.lowerBoundIfFlexible()?.classId == CONCURRENT_MAP }
        else -> null
    }
}

/** Finds every [MapCall] in [file], in source order. */
fun findActions(file: FirFile): List<FoundAction> {
    val positions = Positions(file)
    val found = mutableListOf<FoundAction>()
    val classes = ArrayDeque<FirRegularClass>()
    val scopes = ArrayDeque<FirFunction>()

    file.acceptChildren(
        object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) = element.acceptChildren(this)

            override fun visitRegularClass(regularClass: FirRegularClass) {
                classes.addLast(regularClass)
                regularClass.acceptChildren(this)
                classes.removeLast()
            }

            override fun visitNamedFunction(namedFunction: FirNamedFunction) = inScope(namedFunction)

            override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) = inScope(anonymousFunction)

            private fun inScope(function: FirFunction) {
                scopes.addLast(function)
                function.acceptChildren(this)
                scopes.removeLast()
            }

            override fun visitFunctionCall(functionCall: FirFunctionCall) {
                mapCallOf(functionCall)?.let { found += found(functionCall, it) }
                functionCall.acceptChildren(this)
            }

            private fun found(
                call: FirFunctionCall,
                kind: MapCall,
            ): FoundAction {
                val lambda = lambdaArgument(call, kind)
                val cls = classes.lastOrNull()
                val function = scopes.lastOrNull { it is FirNamedFunction } as FirNamedFunction?
                val site =
                    ActionSite(
                        pos = positions.of(call.source)!!,
                        receiver = ((call.dispatchReceiver ?: call.extensionReceiver) as? FirPropertyAccessExpression)?.calleeReference?.name?.asString(),
                        inClass = cls?.symbol?.classId?.asSingleFqName()?.asString(),
                        inFunction = function?.name?.asString(),
                        lambda = lambda?.let { positions.of(it.source) },
                        kind = kind,
                    )
                return FoundAction(site, call, lambda, cls, function, scopes.toList())
            }
        },
    )
    return found
}

/**
 * The lambda literal passed as the function argument: behind its SAM conversion for `compute` (BiFunction) and
 * `computeIfAbsent` (Function), a plain Kotlin lambda for `getOrPut`. Null for the other calls.
 */
private fun lambdaArgument(
    call: FirFunctionCall,
    kind: MapCall,
): FirAnonymousFunction? {
    val arg = call.argumentList.arguments.getOrNull(1)
    return when (kind) {
        MapCall.COMPUTE, MapCall.COMPUTE_IF_ABSENT -> {
            val conversion = arg as? FirFunctionTypeConversionExpression ?: return null
            if (conversion.kind != FirFunctionConversionKind.Sam) return null
            (conversion.expression as? FirAnonymousFunctionExpression)?.anonymousFunction
        }
        MapCall.GET_OR_PUT -> (arg as? FirAnonymousFunctionExpression)?.anonymousFunction
        else -> null
    }
}

/** Maps source offsets of one file to 1-based [Pos]. */
class Positions(
    file: FirFile,
) {
    private val path = file.sourceFile?.path ?: file.name
    private val lines = file.sourceFileLinesMapping

    fun of(source: KtSourceElement?): Pos? = source?.let { at(it, it.startOffset) }

    /** Position of a declaration: where its code starts, after the KDoc and comments its source range includes. */
    fun ofDeclaration(source: KtSourceElement?): Pos? {
        if (source == null) return null
        val children = Ref<Array<LighterASTNode?>>()
        source.treeStructure.getChildren(source.lighterASTNode, children)
        val code = children.get().orEmpty().filterNotNull().firstOrNull { it.tokenType != KtTokens.WHITE_SPACE && it.tokenType !in KtTokens.COMMENTS }
        return at(source, code?.startOffset ?: source.startOffset)
    }

    private fun at(
        source: KtSourceElement,
        offset: Int,
    ): Pos? {
        val (line, col) = lines?.getLineAndColumnByOffset(offset) ?: return null
        return Pos(path, line + 1, col + 1, (source.kind as? KtFakeSourceElementKind)?.let { it::class.simpleName })
    }
}
