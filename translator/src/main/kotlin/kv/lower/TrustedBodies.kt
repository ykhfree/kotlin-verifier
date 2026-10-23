package kv.lower

import kv.sidecar.SidecarError
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

/**
 * DESIGN-B.md 3.3: the machine checks of a trusted function whose body is in the audited files. A person reviews the
 * rest (3.4). Rules: 1. the body neither reads nor writes a state field; 2. it calls no sidecar operation function;
 * 3. for `returns fresh C`, every return is a constructor call of C, or a local `val` that only such a call
 * initializes. Each violation is a sidecar error at the `trusted fun` declaration. [operations] are the functions the
 * sidecar operations select.
 */
internal fun checkTrustedBodies(
    ctx: ModuleContext,
    operations: Set<FirNamedFunctionSymbol>,
): List<SidecarError> {
    val errors = mutableListOf<SidecarError>()
    for ((sym, decl) in ctx.trusted) {
        val fn = ctx.declarationOf(sym) as? FirNamedFunction ?: continue
        fun err(
            at: FirElement,
            message: String,
        ) {
            val p = ctx.posOf(at)
            errors += SidecarError(decl.pos, "trusted-body", "trusted fun ${decl.shortName}: $message at ${p.file.substringAfterLast('/')}:${p.line}:${p.col} (DESIGN-B.md 3.3)")
        }
        val returns = mutableListOf<FirReturnExpression>()
        fn.body?.accept(
            object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    when (element) {
                        is FirVariableAssignment ->
                            (element.lValue as? FirQualifiedAccessExpression)?.let { ctx.stateOf(ctx.calleeOf(it)) }?.let { err(element, "the body writes state ${it.name}") }
                        is FirPropertyAccessExpression ->
                            ctx.stateOf(ctx.calleeOf(element))?.takeIf { (ctx.index.parent(element) as? FirVariableAssignment)?.lValue !== element }?.let { err(element, "the body reads state ${it.name}") }
                        is FirFunctionCall -> if (ctx.calleeOf(element) in operations) err(element, "the body calls the operation function ${ctx.calleeOf(element)?.callableId?.callableName}")
                        is FirReturnExpression -> if (element.target.labeledElement === fn) returns += element
                        else -> Unit
                    }
                    element.acceptChildren(this)
                }
            },
        )
        val cls = decl.fresh?.text ?: continue
        for (r in returns) {
            val e = unwrap(r.result)
            val ok =
                when (e) {
                    is FirFunctionCall -> ctx.constructedClass(ctx.calleeOf(e))?.name == cls
                    is FirPropertyAccessExpression -> {
                        val local = ctx.calleeOf(e)?.let { ctx.declarationOf(it) } as? FirProperty
                        local != null && local.isLocal && local.isVal && (unwrap(local.initializer) as? FirFunctionCall)?.let { ctx.constructedClass(ctx.calleeOf(it))?.name == cls } == true
                    }
                    else -> false
                }
            if (!ok) err(r, "returns ${r.result.let(::sourceText)}, which is not a new $cls")
        }
    }
    return errors
}
