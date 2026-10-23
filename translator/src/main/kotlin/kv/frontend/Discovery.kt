package kv.frontend

import kv.kvir.Pos
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirFunctionConversionKind
import org.jetbrains.kotlin.fir.expressions.FirFunctionTypeConversionExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.unwrapSubstitutionOverrides
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/** A `ConcurrentHashMap.compute` call in a source file: one candidate action (DESIGN-v0.md 2.2). */
data class ComputeSite(
    /** The call. Its column is where the receiver expression starts. */
    val pos: Pos,
    /** Name of the property the call is made on, e.g. `lockMap`; null if the receiver is not a property read. */
    val receiver: String?,
    /** FQN of the innermost enclosing class or object; null for a top-level call. Lambdas are transparent. */
    val inClass: String?,
    /** Name of the innermost enclosing named function; null outside functions. Lambdas are transparent. */
    val inFunction: String?,
    /** The lambda literal passed as the remapping function; null if the argument is not a lambda literal. */
    val lambda: Pos?,
)

/** A [ComputeSite] with the FIR nodes it came from. Valid only while the compiler session runs. */
class FoundCompute(
    val site: ComputeSite,
    val call: FirFunctionCall,
    val lambda: FirAnonymousFunction?,
    val inClass: FirRegularClass?,
    val inFunction: FirNamedFunction?,
    /** Enclosing functions and lambdas, outermost first. Does not include [lambda]. */
    val scopes: List<FirFunction>,
)

private val CHM_COMPUTE = CallableId(ClassId.fromString("java/util/concurrent/ConcurrentHashMap"), Name.identifier("compute"))
private val BI_FUNCTION = ClassId.fromString("java/util/function/BiFunction")

/**
 * True if [call] resolves to `java.util.concurrent.ConcurrentHashMap.compute(K, BiFunction)`.
 * The callable id does not tell overloads apart, so the parameter types are compared too.
 */
fun isChmCompute(call: FirFunctionCall): Boolean {
    val sym = call.calleeReference.toResolvedCallableSymbol()?.unwrapSubstitutionOverrides() as? FirNamedFunctionSymbol ?: return false
    val params = sym.valueParameterSymbols
    return sym.callableId == CHM_COMPUTE &&
        params.size == 2 &&
        params[1].resolvedReturnType.lowerBoundIfFlexible().classId == BI_FUNCTION
}

/** Finds every `ConcurrentHashMap.compute` call in [file], in source order. */
fun findComputes(file: FirFile): List<FoundCompute> {
    val positions = Positions(file)
    val found = mutableListOf<FoundCompute>()
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
                if (isChmCompute(functionCall)) found += found(functionCall)
                functionCall.acceptChildren(this)
            }

            private fun found(call: FirFunctionCall): FoundCompute {
                val lambda = lambdaArgument(call)
                val cls = classes.lastOrNull()
                val function = scopes.lastOrNull { it is FirNamedFunction } as FirNamedFunction?
                val site =
                    ComputeSite(
                        pos = positions.of(call.source)!!,
                        receiver = ((call.dispatchReceiver as? FirPropertyAccessExpression)?.calleeReference?.name)?.asString(),
                        inClass = cls?.symbol?.classId?.asSingleFqName()?.asString(),
                        inFunction = function?.name?.asString(),
                        lambda = lambda?.let { positions.of(it.source) },
                    )
                return FoundCompute(site, call, lambda, cls, function, scopes.toList())
            }
        },
    )
    return found
}

/** The lambda literal passed as compute's remapping function, behind its SAM conversion to BiFunction. */
private fun lambdaArgument(call: FirFunctionCall): FirAnonymousFunction? {
    val arg = call.argumentList.arguments.getOrNull(1) as? FirFunctionTypeConversionExpression ?: return null
    if (arg.kind != FirFunctionConversionKind.Sam) return null
    return (arg.expression as? FirAnonymousFunctionExpression)?.anonymousFunction
}

/** Maps source offsets of one file to 1-based [Pos]. */
class Positions(
    file: FirFile,
) {
    private val path = file.sourceFile?.path ?: file.name
    private val lines = file.sourceFileLinesMapping

    fun of(source: KtSourceElement?): Pos? {
        if (source == null || lines == null) return null
        val (line, col) = lines.getLineAndColumnByOffset(source.startOffset)
        return Pos(path, line + 1, col + 1, (source.kind as? KtFakeSourceElementKind)?.let { it::class.simpleName })
    }
}
