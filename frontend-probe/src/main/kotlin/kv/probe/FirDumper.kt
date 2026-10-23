package kv.probe

import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirAnonymousInitializer
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.isInline
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirBooleanOperatorExpression
import org.jetbrains.kotlin.fir.expressions.FirBreakExpression
import org.jetbrains.kotlin.fir.expressions.FirCheckNotNullCall
import org.jetbrains.kotlin.fir.expressions.FirCheckedSafeCallSubject
import org.jetbrains.kotlin.fir.expressions.FirComparisonExpression
import org.jetbrains.kotlin.fir.expressions.FirElvisExpression
import org.jetbrains.kotlin.fir.expressions.impl.FirElseIfTrueCondition
import org.jetbrains.kotlin.fir.expressions.FirEqualityOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirFunctionCallOrigin
import org.jetbrains.kotlin.fir.expressions.FirFunctionTypeConversionExpression
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirLoop
import org.jetbrains.kotlin.fir.expressions.FirLoopJump
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSafeCallExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirStringConcatenationCall
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirThrowExpression
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
import org.jetbrains.kotlin.fir.expressions.FirTypeOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.FirWhileLoop
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.references.FirReference
import org.jetbrains.kotlin.fir.references.toResolvedBaseSymbol
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirBackingFieldSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirEnumEntrySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFieldSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirReceiverParameterSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirSyntheticPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirThisOwnerSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.renderReadableWithFqNames
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId

private val VOLATILE_IDS = setOf(ClassId.fromString("kotlin/jvm/Volatile"), ClassId.fromString("kotlin/concurrent/Volatile"))

/**
 * Prints the resolved FIR of one file as an indented tree, one node per line, each with `@line:col`.
 * Known node kinds print the facts a translator needs. Any other node prints as `?<FirClass>` with its
 * children, so the dump never drops a node silently.
 */
// Direct declaration lists keep source order; the probe only reads them after resolution.
@OptIn(DirectDeclarationsAccess::class)
class FirDumper(
    private val file: FirFile,
) {
    private val out = StringBuilder()
    private val lines = file.sourceFileLinesMapping
    private var depth = 0

    private val childVisitor =
        object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) = node(element)
        }

    fun dump(): String {
        emit("file ${file.sourceFile?.path} package=${file.packageDirective.packageFqName}", null)
        nested { file.declarations.forEach(::declaration) }
        return out.toString()
    }

    // ---- output ----

    private fun emit(
        text: String,
        at: FirElement?,
    ) {
        repeat(depth) { out.append("  ") }
        out.append(text)
        if (at != null) out.append(' ').append(pos(at))
        out.append('\n')
    }

    private inline fun nested(block: () -> Unit) {
        depth++
        block()
        depth--
    }

    /** 1-based `@line:col` of the node's start, plus the fake source kind for compiler-made nodes. */
    private fun pos(e: FirElement): String = pos(e.source)

    private fun pos(src: KtSourceElement?): String {
        if (src == null) return "@?"
        val (line, col) = lines?.getLineAndColumnByOffset(src.startOffset) ?: return "@offset=${src.startOffset}"
        val fake = (src.kind as? KtFakeSourceElementKind)?.let { " fake=${it::class.simpleName}" } ?: ""
        return "@${line + 1}:${col + 1}$fake"
    }

    private fun ConeKotlinType.r(): String = renderReadableWithFqNames()

    private fun FirTypeRef.r(): String = coneTypeOrNull?.r() ?: "<unresolved ${this::class.simpleName}>"

    private fun FirExpression.t(): String = resolvedType.r()

    private fun tags(vararg tags: String?): String = tags.filterNotNull().takeIf { it.isNotEmpty() }?.joinToString(" ", " [", "]") ?: ""

    private fun callableTags(sym: FirCallableSymbol<*>): Array<String?> =
        arrayOf(
            "SUSPEND".takeIf { sym.isSuspend },
            "inline".takeIf { sym.isInline },
            "override".takeIf { sym.isOverride },
            sym.visibility.name,
            "origin=${sym.origin}",
        )

    // ---- declarations ----

    private fun declaration(d: FirDeclaration) {
        when (d) {
            is FirRegularClass -> {
                emit("class ${d.symbol.classId} kind=${d.classKind}${tags(d.status.visibility.name)} supertypes=[${d.superTypeRefs.joinToString { it.r() }}]", d)
                nested { d.declarations.forEach(::declaration) }
            }
            is FirFunction -> function(d)
            is FirProperty -> property(d)
            is FirAnonymousInitializer -> {
                emit("init", d)
                nested { d.body?.statements?.forEach(::node) }
            }
            else -> emit("decl ${d::class.simpleName}", d)
        }
    }

    private fun function(f: FirFunction) {
        // Default accessors, implicit constructors and other generated functions have no code of their own.
        if (f.source?.kind is KtFakeSourceElementKind) return
        val head =
            when (f) {
                is FirConstructor -> "constructor ${f.symbol.callableId}"
                is FirPropertyAccessor -> "${if (f.isGetter) "getter" else "setter"} ${f.propertySymbol.callableId}"
                is FirNamedFunction -> "fun ${f.symbol.callableId}"
                else -> "function ${f::class.simpleName}"
            }
        val params = f.valueParameters.joinToString { "${it.name}: ${it.returnTypeRef.r()}" }
        emit("$head($params): ${f.returnTypeRef.r()}${tags(*callableTags(f.symbol))}", f)
        nested {
            f.valueParameters.forEach { p ->
                p.defaultValue?.let { node(it, "default ${p.name}") }
            }
            f.body?.statements?.forEach(::node)
        }
    }

    private fun property(p: FirProperty) {
        val sym = p.symbol
        val kind = if (p.isLocal) "local ${if (p.isVar) "var" else "val"} ${p.name}" else "property ${if (p.isVar) "var" else "val"} ${sym.callableId}"
        val onProperty = sym.resolvedAnnotationClassIds.takeIf { it.isNotEmpty() }?.let { "annotations(property)=$it" }
        val onField = sym.backingFieldSymbol?.resolvedAnnotationClassIds?.takeIf { it.isNotEmpty() }?.let { "annotations(field)=$it" }
        emit("$kind: ${p.returnTypeRef.r()}${tags(volatileTag(sym), onProperty, onField)}", p)
        nested {
            p.initializer?.let { node(it, "init") }
            p.getter?.let(::function)
            p.setter?.let(::function)
        }
    }

    private fun volatileTag(sym: FirPropertySymbol): String? {
        val ids = sym.resolvedAnnotationClassIds + sym.backingFieldSymbol?.resolvedAnnotationClassIds.orEmpty()
        return "VOLATILE".takeIf { ids.any { it in VOLATILE_IDS } }
    }

    // ---- statements and expressions ----

    private fun node(e: FirElement) = node(e, "")

    private fun node(
        e: FirElement,
        role: String,
    ) {
        val p = if (role.isEmpty()) "" else "$role: "
        when (e) {
            is FirProperty -> property(e)
            is FirBlock -> {
                emit("${p}block", e)
                nested { e.statements.forEach(::node) }
            }
            is FirVariableAssignment -> {
                val target = e.lValue
                val desc = (target as? FirQualifiedAccessExpression)?.let { variable(it.calleeReference) } ?: "?${target::class.simpleName}"
                emit("${p}WRITE $desc", e)
                nested {
                    if (target is FirQualifiedAccessExpression) receivers(target)
                    node(e.rValue, "value")
                }
            }
            is FirFunctionCall -> call(e, p)
            is FirComparisonExpression -> {
                emit("${p}compare ${e.operation.operator} : ${e.t()}", e)
                nested { call(e.compareToCall, "") }
            }
            is FirEqualityOperatorCall -> {
                emit("${p}equality ${e.operation.operator} : ${e.t()}", e)
                nested { e.argumentList.arguments.forEach(::node) }
            }
            is FirBooleanOperatorExpression -> {
                emit("${p}boolean ${e.kind} : ${e.t()}", e)
                nested {
                    node(e.leftOperand, "left")
                    node(e.rightOperand, "right")
                }
            }
            is FirSmartCastExpression -> {
                emit("${p}smartcast ${e.originalExpression.t()} -> ${e.smartcastType.r()} stable=${e.isStable}", e)
                nested { node(e.originalExpression) }
            }
            is FirThisReceiverExpression -> {
                val ref = e.calleeReference
                val label = ref.labelName?.let { "@$it" } ?: ""
                emit("${p}this$label${if (e.isImplicit) " (implicit)" else ""} bound=${boundTo(ref.boundSymbol)} : ${e.t()}", e)
            }
            is FirPropertyAccessExpression -> {
                emit("${p}READ ${variable(e.calleeReference)} : ${e.t()}", e)
                nested { receivers(e) }
            }
            is FirResolvedQualifier -> emit("${p}qualifier ${e.qualifierSymbol?.classId ?: e.packageFqName} : ${e.t()}", e)
            is FirLiteralExpression -> emit("${p}const ${e.kind} ${e.value} : ${e.t()}", e)
            is FirStringConcatenationCall -> {
                emit("${p}string-template : ${e.t()}", e)
                nested { e.argumentList.arguments.forEach { node(it, "part") } }
            }
            is FirWhenExpression -> {
                emit("${p}when exhaustive=${e.exhaustivenessStatus?.let { it::class.simpleName }} usedAsExpression=${e.usedAsExpression} : ${e.t()}", e)
                nested {
                    e.subjectVariable?.let { node(it, "subject") }
                    e.branches.forEach { b ->
                        emit("branch", b)
                        nested {
                            if (b.condition is FirElseIfTrueCondition) emit("cond: else", null) else node(b.condition, "cond")
                            node(b.result, "then")
                        }
                    }
                }
            }
            is FirReturnExpression -> {
                val target = e.target.labeledElement
                val targetDesc =
                    if (target is FirAnonymousFunction) {
                        "lambda@${target.label?.name}{${pos(target).removePrefix("@")}}"
                    } else {
                        "fun ${target.symbol.callableId}"
                    }
                emit("${p}return label=${e.target.labelName} -> $targetDesc", e)
                nested { node(e.result) }
            }
            is FirAnonymousFunctionExpression -> lambda(e.anonymousFunction, p)
            is FirAnonymousFunction -> lambda(e, p)
            is FirFunctionTypeConversionExpression -> {
                emit("${p}function-conversion kind=${e.kind::class.simpleName} : ${e.t()}", e)
                nested { node(e.expression) }
            }
            is FirSafeCallExpression -> {
                emit("${p}safe-call : ${e.t()}", e)
                nested {
                    node(e.receiver, "receiver")
                    node(e.selector, "selector")
                }
            }
            is FirCheckedSafeCallSubject -> emit("${p}safe-call-subject (non-null receiver) : ${e.t()}", e)
            is FirElvisExpression -> {
                emit("${p}elvis : ${e.t()}", e)
                nested {
                    node(e.lhs, "lhs")
                    node(e.rhs, "rhs")
                }
            }
            is FirWhileLoop -> {
                emit("${p}while label=${e.label?.name}", e)
                nested {
                    node(e.condition, "cond")
                    node(e.block, "body")
                }
            }
            is FirTryExpression -> {
                emit("${p}try : ${e.t()}", e)
                nested {
                    node(e.tryBlock, "try")
                    e.catches.forEach { c ->
                        emit("catch ${c.parameter.name}: ${c.parameter.returnTypeRef.r()}", c)
                        nested { node(c.block) }
                    }
                    e.finallyBlock?.let { node(it, "finally") }
                }
            }
            is FirThrowExpression -> {
                emit("${p}throw", e)
                nested { node(e.exception) }
            }
            is FirTypeOperatorCall -> {
                emit("${p}type-op ${e.operation.operator} ${e.conversionTypeRef.r()} : ${e.t()}", e)
                nested { e.argumentList.arguments.forEach(::node) }
            }
            is FirCheckNotNullCall -> {
                emit("${p}not-null-assert : ${e.t()}", e)
                nested { e.argumentList.arguments.forEach(::node) }
            }
            is FirLoopJump -> {
                val loop: FirLoop = e.target.labeledElement
                emit("${p}${if (e is FirBreakExpression) "break" else "continue"} -> loop ${pos(loop)}", e)
            }
            else -> {
                emit("$p?${e::class.simpleName}${(e as? FirExpression)?.let { " : ${it.t()}" } ?: ""}", e)
                nested { e.acceptChildren(childVisitor) }
            }
        }
    }

    private fun lambda(
        f: FirAnonymousFunction,
        p: String,
    ) {
        val params = f.valueParameters.joinToString { "${it.name}: ${it.returnTypeRef.r()}" }
        val receiver = f.receiverParameter?.typeRef?.r()?.let { " receiver=$it" } ?: ""
        emit(
            "${p}lambda label=${f.label?.name} ($params)$receiver: ${f.returnTypeRef.r()} " +
                "inlineStatus=${f.inlineStatus} invocationKind=${f.invocationKind} type=${f.typeRef.r()}",
            f,
        )
        nested { f.body?.statements?.forEach(::node) }
    }

    private fun call(
        c: FirFunctionCall,
        p: String,
    ) {
        val sym = c.calleeReference.toResolvedCallableSymbol()
        // Overloads share a callable id, so the head also carries the parameter types.
        val signature = (sym as? FirFunctionSymbol<*>)?.valueParameterSymbols?.joinToString(", ", "(", ")") { it.resolvedReturnType.r() } ?: ""
        val head =
            when (sym) {
                null -> "UNRESOLVED-CALL ${c.calleeReference::class.simpleName} ${c.calleeReference.name}"
                is FirConstructorSymbol -> "NEW ${sym.callableId.classId}$signature"
                else -> "CALL ${sym.callableId}$signature"
            }
        val typeArgs =
            c.typeArguments
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ", "typeArgs=<", ">") { (it as? FirTypeProjectionWithVariance)?.typeRef?.r() ?: it.toString() }
        val syntax = "syntax=${c.origin}".takeIf { c.origin != FirFunctionCallOrigin.Regular }
        val symTags = sym?.let(::callableTags) ?: emptyArray()
        emit("$p$head${tags(*symTags, syntax, typeArgs)} : ${c.t()}", c)
        nested {
            receivers(c)
            val mapping = c.resolvedArgumentMapping
            if (mapping != null) {
                mapping.forEach { (arg, param) -> node(arg, "arg ${param.name}: ${param.returnTypeRef.r()}") }
            } else {
                c.argumentList.arguments.forEach { node(it, "arg(unmapped)") }
            }
        }
    }

    /** Prints receivers once each, and marks which one the source wrote explicitly. */
    private fun receivers(a: FirQualifiedAccessExpression) {
        val explicit = a.explicitReceiver
        a.dispatchReceiver?.let { node(it, if (it === explicit) "dispatch-receiver(explicit)" else "dispatch-receiver") }
        a.extensionReceiver?.let { node(it, if (it === explicit) "extension-receiver(explicit)" else "extension-receiver") }
        if (explicit != null && explicit !== a.dispatchReceiver && explicit !== a.extensionReceiver) node(explicit, "explicit-receiver")
    }

    /** Declaration site of a local or parameter, because names alone can repeat within one function. */
    private fun declaredAt(sym: FirBasedSymbol<*>): String = "{${pos(sym.source).removePrefix("@")}}"

    /** Names what `this` refers to: a class, or the receiver parameter of a lambda or extension. */
    private fun boundTo(sym: FirThisOwnerSymbol<*>?): String =
        when (sym) {
            is FirClassSymbol<*> -> "class ${sym.classId}"
            is FirReceiverParameterSymbol ->
                when (val owner = sym.containingDeclarationSymbol) {
                    is FirAnonymousFunctionSymbol -> "receiver of lambda{${pos(owner.source).removePrefix("@")}}"
                    is FirCallableSymbol<*> -> "receiver of ${owner.callableId}"
                    else -> "receiver of $owner"
                }
            else -> "$sym"
        }

    private fun variable(ref: FirReference): String =
        when (val sym = ref.toResolvedBaseSymbol()) {
            null -> "UNRESOLVED ${ref::class.simpleName}"
            is FirSyntheticPropertySymbol -> "synthetic-property ${sym.callableId} getter=${sym.getterId}${tags(*callableTags(sym))}"
            is FirPropertySymbol ->
                if (sym.isLocal) {
                    "local ${sym.name}${declaredAt(sym)}"
                } else {
                    "property ${if (sym.isVar) "var" else "val"} ${sym.callableId}${tags(volatileTag(sym), *callableTags(sym))}"
                }
            is FirValueParameterSymbol -> "param ${sym.name}${declaredAt(sym)}"
            is FirBackingFieldSymbol -> "backing-field ${sym.propertySymbol.callableId}${tags(volatileTag(sym.propertySymbol))}"
            is FirEnumEntrySymbol -> "enum-entry ${sym.callableId}"
            is FirFieldSymbol -> "java-field ${sym.callableId}${tags(*callableTags(sym))}"
            else -> "${sym::class.simpleName} $sym"
        }
}
