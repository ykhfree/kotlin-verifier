package kv.client

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirBooleanOperatorExpression
import org.jetbrains.kotlin.fir.expressions.FirBreakExpression
import org.jetbrains.kotlin.fir.expressions.FirCheckNotNullCall
import org.jetbrains.kotlin.fir.expressions.FirCheckedSafeCallSubject
import org.jetbrains.kotlin.fir.expressions.FirComparisonExpression
import org.jetbrains.kotlin.fir.expressions.FirDoWhileLoop
import org.jetbrains.kotlin.fir.expressions.FirElvisExpression
import org.jetbrains.kotlin.fir.expressions.FirEqualityOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirFunctionTypeConversionExpression
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSafeCallExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirThrowExpression
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.FirWhileLoop
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.impl.FirElseIfTrueCondition
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible

/**
 * A one-line rendering of the FIR nodes the client lowering reads (DESIGN-E 6.4), for the E0 shape fixtures: calls
 * with their callee, lambdas with their inline status and invocation kind, returns with their target, try with its
 * catch types, loops, break, throw, when, safe calls, elvis, locals and assignments. Smart casts are transparent.
 */
object FirShape {
    fun of(f: FirNamedFunction): String = "fun ${f.name}: ${f.body?.let(::render) ?: "-"}"

    private fun list(es: List<FirElement>) = es.joinToString("; ") { render(it) }

    fun render(e: FirElement): String =
        when (e) {
            is FirBlock -> "{${list(e.statements)}}"
            is FirProperty -> "${if (e.isVal) "val" else "var"} ${e.name}${e.initializer?.let { " = ${render(it)}" } ?: ""}"
            is FirVariableAssignment -> "${(e.lValue as? FirPropertyAccessExpression)?.calleeReference?.name ?: "?"} := ${render(e.rValue)}"
            is FirSmartCastExpression -> render(e.originalExpression)
            is FirReturnExpression -> {
                val target = e.target.labeledElement
                val to = if (target is FirNamedFunction) "fun ${target.name}" else "lambda"
                "return->$to(${render(e.result)})"
            }
            is FirElvisExpression -> "elvis(${render(e.lhs)}, ${render(e.rhs)})"
            is FirSafeCallExpression -> "safe(${render(e.receiver)}, ${render(e.selector)})"
            is FirCheckedSafeCallSubject -> "subject"
            is FirCheckNotNullCall -> "notnull(${render(e.arguments.single())})"
            is FirFunctionTypeConversionExpression -> "sam(${render(e.expression)})"
            is FirAnonymousFunctionExpression -> render(e.anonymousFunction)
            is FirAnonymousFunction -> "λ[${e.inlineStatus},${e.invocationKind}]${e.body?.let(::render) ?: "{}"}"
            is FirTryExpression -> "try${render(e.tryBlock)}" + e.catches.joinToString("") { c -> " catch(${c.parameter.returnTypeRef.coneType.lowerBoundIfFlexible().classId?.shortClassName})${render(c.block)}" } + (e.finallyBlock?.let { " finally${render(it)}" } ?: "")
            is FirWhileLoop -> "while(${render(e.condition)})${render(e.block)}"
            is FirDoWhileLoop -> "do${render(e.block)}while(${render(e.condition)})"
            is FirBreakExpression -> "break"
            is FirThrowExpression -> "throw(${render(e.exception)})"
            is FirWhenExpression -> "when[${if (e.usedAsExpression) "value" else "stmt"}](" + e.branches.joinToString(" | ") { b -> "${if (b.condition is FirElseIfTrueCondition) "else" else render(b.condition)} -> ${render(b.result)}" } + ")"
            is FirEqualityOperatorCall -> "${e.operation.operator}(${list(e.arguments)})"
            is FirComparisonExpression -> "${e.operation.operator}(${render(e.compareToCall.dispatchReceiver!!)}, ${render(e.compareToCall.arguments.single())})"
            is FirBooleanOperatorExpression -> "${e.kind.token}(${render(e.leftOperand)}, ${render(e.rightOperand)})"
            is FirFunctionCall -> {
                val id = e.calleeReference.toResolvedCallableSymbol()?.callableId
                val name = id?.let { (it.className?.shortName()?.asString()?.let { c -> "$c." } ?: "") + it.callableName } ?: e.calleeReference.name.asString()
                val receiver = (e.dispatchReceiver ?: e.extensionReceiver)?.takeUnless { it is FirThisReceiverExpression || it is FirResolvedQualifier }
                "${receiver?.let { "${render(it)}." } ?: ""}$name(${list(e.arguments)})"
            }
            is FirPropertyAccessExpression -> {
                val receiver = (e.dispatchReceiver ?: e.extensionReceiver)?.takeUnless { it is FirThisReceiverExpression || it is FirResolvedQualifier }
                "${receiver?.let { "${render(it)}." } ?: ""}${e.calleeReference.name}"
            }
            is FirThisReceiverExpression -> "this"
            is FirLiteralExpression -> "${e.value}"
            is FirExpression -> e::class.simpleName!!.removePrefix("Fir").removeSuffix("Impl")
            else -> e::class.simpleName!!.removePrefix("Fir").removeSuffix("Impl")
        }
}
