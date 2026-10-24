package kv.mutation

import kv.frontend.Frontend
import kv.frontend.FoundAction
import kv.frontend.FunDeclaration
import kv.frontend.LoweredFir
import kv.frontend.Positions
import kv.frontend.ReviewedSource
import kv.kvir.DiagnosticKind
import kv.kvir.MapVar
import kv.kvir.Pos
import kv.lower.FirIndex
import kv.lower.auditIndex
import kv.lower.modelEntries
import kv.lower.unwrap
import kv.sidecar.Selector
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.com.intellij.lang.LighterASTNode
import org.jetbrains.kotlin.com.intellij.openapi.util.Ref
import org.jetbrains.kotlin.contracts.description.LogicOperationKind
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirBooleanOperatorExpression
import org.jetbrains.kotlin.fir.expressions.FirComparisonExpression
import org.jetbrains.kotlin.fir.expressions.FirEqualityOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirOperation
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSafeCallExpression
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirWhenBranch
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.impl.FirElseIfTrueCondition
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.unwrapSubstitutionOverrides
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.text
import org.jetbrains.kotlin.types.ConstantValueKind
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

/** The nine mutation operators of MUTATION.md 3. */
enum class Operator { ROR, COR, NEG, BOOL, SDL, RET, AOR, MOP, RACY }

/** Replaces [original], the text at [start, end) of a file, with [replacement]. [start] == [end] inserts. */
data class Edit(
    val start: Int,
    val end: Int,
    val original: String,
    val replacement: String,
)

/**
 * One mutant of MUTATION.md 3: [edits] applied to [file], which they do not overlap in (RACY has two). [pos] and [end]
 * bound the mutated FIR element. [ledger] is the ledger kind of that element's node in the module ledger, the one
 * `kv lower` prints: `translated` if an action or an operation section translated it, `-` outside the audited files.
 * [facts] are what the classification rules of MUTATION.md 5 and 7 read.
 */
data class MutationSite(
    val operator: Operator,
    val scope: String,
    val file: File,
    val pos: Pos,
    val end: Pos,
    val edits: List<Edit>,
    val ledger: String,
    val facts: Map<String, String> = emptyMap(),
)

/** Sites, and the reasons a site could not be made (a tool defect: `kv mutation-sites` then fails). */
class SiteList {
    val sites = mutableListOf<MutationSite>()
    val problems = mutableListOf<String>()
}

/** A reviewed trusted fun whose body is in a library: its source declaration, found as the resolver finds it (B6). */
internal class LibraryTrusted(
    val name: String,
    val declaration: FunDeclaration,
)

private val LONG = ClassId.fromString("kotlin/Long")
private val BOOLEAN = ClassId.fromString("kotlin/Boolean")
private const val ATOMIC_LONG = "java/util/concurrent/atomic/AtomicLong"
private const val ATOMIC_BOOLEAN = "java/util/concurrent/atomic/AtomicBoolean"
private const val SEMAPHORE = "java/util/concurrent/Semaphore"

private val ROR_SWAP =
    mapOf(
        FirOperation.GT to ">=",
        FirOperation.GT_EQ to ">",
        FirOperation.LT to "<=",
        FirOperation.LT_EQ to "<",
        FirOperation.EQ to "!=",
        FirOperation.NOT_EQ to "==",
    )
private val BOUNDARY = setOf(FirOperation.GT, FirOperation.GT_EQ, FirOperation.LT, FirOperation.LT_EQ)

/**
 * The sites of MUTATION.md 2 items 1-4 in the module file of [l], and the library trusted funs whose bodies item 4
 * also covers (their files are compiled on their own: [librarySites]). Regions, in this order:
 * 1. the body of each `fun` operation, its action lambda included;
 * 2. the action lambda of each `compute in` operation;
 * 3. the module-file functions that regions 1 and 2 call directly (helpers), except operations and trusted funs;
 * 4. the body of each reviewed trusted fun of the module file.
 */
internal fun moduleSites(
    l: LoweredFir,
    root: File,
): Pair<SiteList, List<LibraryTrusted>> {
    val index = auditIndex(l.resolver, l.file, l.sources)
    val ledger = l.module.ledger.associateBy { it.node }
    val fields = l.resolver.fields.entries.associate { (k, v) -> v to k.second }
    val states = l.resolver.states.entries.associate { (k, v) -> v to k }
    val maps = l.module.state.filterIsInstance<MapVar>().associateBy { it.name }
    val assumptions = l.module.diagnostics.filter { it.kind == DiagnosticKind.ASSUMPTION }.map { it.pos }.toSet()
    val finder =
        SiteFinder(
            l.file,
            index,
            l.sidecar.uses.map { it.model.text }.toSet(),
            ledgerOf = { e -> index.id(e)?.let { ledger[it]?.kind?.label ?: "translated" } ?: "-" },
            assumptions = assumptions,
            fieldOf = { fields[it] },
            stateOf = { e -> (unwrap(e) as? FirPropertyAccessExpression)?.let(::callee)?.let { states[it] }?.let { maps[it] } },
        )
    val trusted = l.sidecar.trusted.filter { it.reviewed != null }.mapNotNull { t -> l.resolver.trusted[t.shortName]?.let { t.shortName to it } }
    val trustedSymbols = trusted.map { it.second }.toSet()
    val called = mutableListOf<FirNamedFunctionSymbol>()
    for (op in l.sidecar.operations) {
        val found = l.resolver.computeOf[op.name.text] ?: continue
        val regionRoot =
            when (op.selector) {
                is Selector.Fun -> found.inFunction?.body
                is Selector.ComputeIn -> found.lambda?.body
            } ?: continue
        val function = if (op.selector is Selector.Fun) found.inFunction else null
        called += finder.region(regionRoot, "operation ${op.name.text}", found, function)
    }
    val helpers = called.distinct().filter { it in index.functions && it !in l.resolver.operationFunctions && it !in trustedSymbols }
    helpers.forEach { sym -> index.functions.getValue(sym).body?.let { finder.region(it, "helper ${sym.name}") } }
    val library = mutableListOf<LibraryTrusted>()
    for ((name, sym) in trusted) {
        val fn = index.functions[sym]
        if (fn != null) {
            fn.body?.let { finder.region(it, "trusted $name") }
        } else if (sym.source == null) {
            val id = sym.callableId
            val classes = id.className?.pathSegments()?.map { it.asString() }.orEmpty()
            val decl = ReviewedSource.library(root, id.packageName.asString(), classes, id.callableName.asString(), sym.valueParameterSymbols.size).singleOrNull()
            if (decl == null) finder.list.problems += "trusted fun $name: no single source declaration under $root" else library += LibraryTrusted(name, decl)
        }
    }
    return finder.list to library
}

/**
 * The sites of the library trusted fun [target] (MUTATION.md 2 item 4): its file is compiled alone with [classpath],
 * and the sites are found in the FIR of its body. [uses] are the trusted models the sidecar uses (MOP). No ledger
 * covers a library file, so every site has ledger kind `-`.
 */
internal fun librarySites(
    target: LibraryTrusted,
    uses: Set<String>,
    classpath: List<File>,
    kotlincArgs: List<String>,
): SiteList {
    var list: SiteList? = null
    val result =
        Frontend.run(listOf(target.declaration.file), classpath, kotlincArgs, onFile = { file ->
            val fn = functions(file).find { it.name.asString() == target.name && it.source?.startOffset == target.declaration.start }
            val finder = SiteFinder(file, FirIndex(file), uses, ledgerOf = { "-" })
            if (fn?.body == null) finder.list.problems += "trusted fun ${target.name}: no FIR function at ${target.declaration.where}" else finder.region(fn.body!!, "trusted ${target.name}")
            list = finder.list
        })
    return list?.takeIf { result.compiled } ?: SiteList().apply { problems += "trusted fun ${target.name}: ${target.declaration.file} does not compile:\n${result.messages}" }
}

private fun functions(file: FirFile): List<FirNamedFunction> {
    val out = mutableListOf<FirNamedFunction>()
    file.accept(
        object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (element is FirNamedFunction) out += element
                element.acceptChildren(this)
            }
        },
    )
    return out
}

private fun callee(e: FirQualifiedAccessExpression): FirCallableSymbol<*>? = e.calleeReference.toResolvedCallableSymbol()?.unwrapSubstitutionOverrides()

private fun isReal(e: FirElement?): Boolean = e?.source != null && e.source?.kind !is KtFakeSourceElementKind

/**
 * Finds the sites of the operators of MUTATION.md 3 in regions of [file]. [index] gives parents and node numbers;
 * [ledgerOf] the ledger kind of an element; [uses] the trusted models the sidecar uses (MOP); [assumptions] the
 * positions of the module's ASSUMPTION diagnostics (AOR); [fieldOf] the sidecar field a property symbol binds;
 * [stateOf] the state map a receiver expression reads.
 */
internal class SiteFinder(
    file: FirFile,
    private val index: FirIndex,
    private val uses: Set<String>,
    private val ledgerOf: (FirElement) -> String,
    private val assumptions: Set<Pos> = emptySet(),
    private val fieldOf: (FirBasedSymbol<*>?) -> String? = { null },
    private val stateOf: (FirExpression?) -> MapVar? = { null },
) {
    val list = SiteList()
    private val positions = Positions(file)
    private val path = File(file.sourceFile?.path ?: file.name)
    private val text: String = file.sourceFile?.getContentsAsStream()?.reader()?.use { it.readText() }.orEmpty()
    private var racyCount = 0

    /**
     * Adds the sites in [root]'s subtree, which belongs to [scope]. With the operation's [action], RET mutates the
     * action lambda's results and RACY reads the action's map before the action call; with the operation [function],
     * RET also mutates the function's results. Returns the functions that the region calls, in call order.
     */
    fun region(
        root: FirElement,
        scope: String,
        action: FoundAction? = null,
        function: FirNamedFunction? = null,
    ): List<FirNamedFunctionSymbol> {
        val elements = mutableListOf<FirElement>()
        root.accept(
            object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    elements += element
                    element.acceptChildren(this)
                }
            },
        )
        val lambdaValues = Collections.newSetFromMap(IdentityHashMap<FirElement, Boolean>())
        elements.filterIsInstance<FirAnonymousFunction>().forEach { lambdaValues += values(it) }
        val before = list.sites.size
        for (e in elements) {
            if (!isReal(e)) continue
            when (e) {
                is FirComparisonExpression -> ror(e, scope, e.operation, listOfNotNull(e.compareToCall.explicitReceiver, e.compareToCall.arguments.firstOrNull()))
                is FirEqualityOperatorCall -> if (e.operation in ROR_SWAP) ror(e, scope, e.operation, e.arguments)
                is FirBooleanOperatorExpression -> cor(e, scope)
                is FirWhenExpression ->
                    if (e.subjectVariable == null) {
                        e.branches.map { it.condition }.filter { it !is FirElseIfTrueCondition && isReal(it) }.forEach { c ->
                            neg(c, scope)
                            if (action != null) racy(c, scope, action)
                        }
                    }
                is FirLiteralExpression -> if (e.kind == ConstantValueKind.Boolean) bool(e, scope)
                is FirFunctionCall -> {
                    aor(e, scope)
                    mop(e, scope)
                }
                else -> Unit
            }
            if (e is FirBlock) e.statements.filter { deletable(it) && it !in lambdaValues }.forEach { sdl(it, e, scope, action?.lambda) }
        }
        action?.lambda?.let { lambda -> lambdaResults(lambda, elements, scope) }
        function?.let { fn -> functionResults(fn, elements, scope) }
        // Sites in source order.
        val added = list.sites.subList(before, list.sites.size)
        val sorted = added.sortedWith(compareBy({ it.edits.first().start }, { it.operator.ordinal }))
        added.clear()
        list.sites += sorted
        return elements.filterIsInstance<FirFunctionCall>().mapNotNull { callee(it) as? FirNamedFunctionSymbol }
    }

    /** The expressions [lambda]'s value comes from: its last statement, through blocks and if/when branches. A written `return` is not one. */
    private fun values(lambda: FirAnonymousFunction): List<FirElement> {
        val out = mutableListOf<FirElement>()

        fun visit(s: FirStatement?) {
            when (s) {
                null -> Unit
                is FirReturnExpression -> if (s.target.labeledElement === lambda && !isReal(s)) visit(s.result)
                is FirWhenExpression -> s.branches.forEach { visit(it.result) }
                is FirBlock -> visit(s.statements.lastOrNull())
                is FirExpression -> out += s
                else -> Unit
            }
        }
        visit(lambda.body?.statements?.lastOrNull())
        return out
    }

    /** SDL (MUTATION.md 3): an expression statement that is a call or an assignment. */
    private fun deletable(s: FirStatement): Boolean = isReal(s) && (s is FirFunctionCall || s is FirVariableAssignment || s is FirSafeCallExpression)

    // ---- the operators ----

    private fun ror(
        e: FirExpression,
        scope: String,
        op: FirOperation,
        operands: List<FirExpression>,
    ) {
        val (start, end) = operatorToken(e) ?: return problem(e, "no operator token")
        val read = operands.mapNotNull { (unwrap(it) as? FirPropertyAccessExpression)?.let(::callee)?.let(fieldOf) }
        add(Operator.ROR, scope, e, listOf(edit(start, end, ROR_SWAP.getValue(op))), mapOf("op" to op.operator, "fields" to read.joinToString(","), "boundary" to "${op in BOUNDARY}"))
    }

    private fun cor(
        e: FirBooleanOperatorExpression,
        scope: String,
    ) {
        val (start, end) = operatorToken(e) ?: return problem(e, "no operator token")
        add(Operator.COR, scope, e, listOf(edit(start, end, if (e.kind == LogicOperationKind.AND) "||" else "&&")))
    }

    private fun neg(
        c: FirExpression,
        scope: String,
    ) = add(Operator.NEG, scope, c, listOf(edit(c, "!(${textOf(c)})")))

    private fun bool(
        e: FirLiteralExpression,
        scope: String,
    ) = add(Operator.BOOL, scope, e, listOf(edit(e, if (e.value == true) "false" else "true")))

    /** SDL: deletes [s] from [block]; a branch without braces gets an empty block instead, so it keeps its place. */
    private fun sdl(
        s: FirStatement,
        block: FirBlock,
        scope: String,
        lambda: FirAnonymousFunction?,
    ) {
        val bare = !isReal(block) && index.parent(block) is FirWhenBranch
        val facts = mutableMapOf<String, String>()
        when (s) {
            is FirFunctionCall -> facts["callee"] = calleeName(s)
            is FirSafeCallExpression -> (s.selector as? FirFunctionCall)?.let { facts["callee"] = calleeName(it) }
            is FirVariableAssignment -> {
                (s.lValue as? FirQualifiedAccessExpression)?.let { lv -> facts["field"] = fieldOf(callee(lv)) ?: callee(lv)?.callableId?.callableName?.asString().orEmpty() }
                facts["value"] = textOf(s.rValue)
            }
            else -> Unit
        }
        if (lambda != null && within(s, lambda)) facts["removalFollows"] = "${removalFollows(s, lambda)}"
        add(Operator.SDL, scope, s, listOf(edit(s, if (bare) "{}" else "")), facts)
    }

    /** RET on the action lambda: each written `return@...` value and each result value; null becomes the value parameter. */
    private fun lambdaResults(
        lambda: FirAnonymousFunction,
        elements: List<FirElement>,
        scope: String,
    ) {
        val written = elements.filterIsInstance<FirReturnExpression>().filter { isReal(it) && it.target.labeledElement === lambda }.map { it.result }
        // compute's lambda has the value parameter second; computeIfAbsent's has none.
        val valueParam = lambda.valueParameters.getOrNull(1)?.name?.asString()?.takeIf { it != "_" }
        for (v in written + values(lambda)) {
            val x = unwrap(v as? FirExpression) ?: continue
            if (!isReal(x)) continue
            val isNull = x is FirLiteralExpression && x.kind == ConstantValueKind.Null
            val replacement = if (isNull) valueParam ?: continue else "null"
            add(Operator.RET, scope, x, listOf(edit(x, replacement)), mapOf("of" to "lambda"))
        }
    }

    /** RET on the operation function's results: a constant of its return type (`null` if nullable, `true` and `false` if Boolean). */
    private fun functionResults(
        fn: FirNamedFunction,
        elements: List<FirElement>,
        scope: String,
    ) {
        val type = fn.returnTypeRef.coneType
        val constants = listOfNotNull("null".takeIf { type.isMarkedNullable }) + if (type.lowerBoundIfFlexible().classId == BOOLEAN) listOf("true", "false") else emptyList()
        for (r in elements.filterIsInstance<FirReturnExpression>().filter { it.target.labeledElement === fn }) {
            val x = unwrap(r.result) ?: continue
            if (!isReal(x)) continue
            constants.filter { it != textOf(x) }.forEach { add(Operator.RET, scope, x, listOf(edit(x, it)), mapOf("of" to "function")) }
        }
    }

    /** AOR: Long `+` and `-` with an ASSUMPTION diagnostic: the other operator, and the left operand alone. */
    private fun aor(
        call: FirFunctionCall,
        scope: String,
    ) {
        val sym = callee(call) ?: return
        val name = sym.callableId?.callableName?.asString()
        if (sym.callableId?.classId != LONG || name !in setOf("plus", "minus") || positions.of(call.source) !in assumptions) return
        val (start, end) = operatorToken(call) ?: return problem(call, "no operator token")
        val left = call.explicitReceiver ?: return problem(call, "no left operand")
        add(Operator.AOR, scope, call, listOf(edit(start, end, if (name == "plus") "-" else "+")))
        add(Operator.AOR, scope, call, listOf(edit(call, textOf(left))))
    }

    /** MOP: `incrementAndGet` to `get`, the two arguments of `compareAndSet` swapped, `tryAcquire()` to `true`. */
    private fun mop(
        call: FirFunctionCall,
        scope: String,
    ) {
        val sym = callee(call) ?: return
        val entries = modelEntries(sym).filter { it.model in uses }
        if (entries.isEmpty()) return
        val owner = entries.first().owner
        val args = call.arguments
        when {
            owner == ATOMIC_LONG && entries.first().name == "incrementAndGet" -> {
                val ref = call.calleeReference.source ?: return problem(call, "no callee source")
                add(Operator.MOP, scope, call, listOf(edit(ref.startOffset, ref.endOffset, "get")))
            }
            owner == ATOMIC_BOOLEAN && entries.first().name == "compareAndSet" && args.size == 2 ->
                add(Operator.MOP, scope, call, listOf(edit(args[0].source!!.startOffset, args[1].source!!.endOffset, "${textOf(args[1])}, ${textOf(args[0])}")))
            owner == SEMAPHORE && entries.first().name == "tryAcquire" && args.isEmpty() -> add(Operator.MOP, scope, call, listOf(edit(call, "true")))
        }
    }

    /**
     * RACY: `val racy_N = <map>.containsKey(<key>)` right before the action call, and the condition [c] becomes
     * `racy_N && c`. The action call must start its own line in a block.
     */
    private fun racy(
        c: FirExpression,
        scope: String,
        action: FoundAction,
    ) {
        val call = action.call
        val start = call.source?.startOffset ?: return problem(call, "no source")
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        val indent = text.substring(lineStart, start)
        val parent = index.parent(call)
        val statement = parent is FirBlock || parent is FirReturnExpression && !isReal(parent) && index.parent(parent) is FirBlock
        if (indent.isNotBlank() || !statement) return problem(call, "the action call does not start a statement line, so RACY cannot read the map before it")
        val receiver = action.receiver ?: return problem(call, "no map receiver")
        val key = call.arguments.firstOrNull() ?: return problem(call, "no key argument")
        val n = ++racyCount
        val cond = textOf(c)
        val wrapped = if (c is FirBooleanOperatorExpression && c.kind == LogicOperationKind.OR) "($cond)" else cond
        val map = stateOf(receiver)
        add(
            Operator.RACY,
            scope,
            c,
            listOf(edit(c, "racy_$n && $wrapped"), Edit(start, start, "", "val racy_$n = ${textOf(receiver)}.containsKey(${textOf(key)})\n$indent")),
            mapOf("map" to (map?.name ?: ""), "setOnce" to (map?.setOnce ?: "")),
        )
    }

    // ---- MUTATION.md 7.7: is the entry removed right after the statement? ----

    private fun within(
        e: FirElement,
        ancestor: FirElement,
    ): Boolean {
        var x: FirElement? = e
        while (x != null && x !== ancestor) x = index.parent(x)
        return x === ancestor
    }

    /**
     * True if, after [s], the action lambda's path reaches a result before any other exit, and that result is null
     * (the entry is removed). Climbs from [s]'s block to the enclosing blocks; false if a later statement holds an
     * exit in a branch, or no result follows.
     */
    private fun removalFollows(
        s: FirStatement,
        lambda: FirAnonymousFunction,
    ): Boolean {
        val values = values(lambda).toSet()
        var cur: FirElement = s
        while (true) {
            val block = index.parent(cur) as? FirBlock ?: return false
            for (f in block.statements.dropWhile { it !== cur }.drop(1)) {
                val exit = (f as? FirReturnExpression)?.takeIf { it.target.labeledElement === lambda }?.result ?: f.takeIf { it in values }
                if (exit != null) return unwrap(exit as? FirExpression).let { it is FirLiteralExpression && it.kind == ConstantValueKind.Null }
                if (holdsExit(f, lambda, values)) return false
            }
            if (block === lambda.body) return false
            // The statement of the enclosing block that holds [block].
            var x: FirElement = block
            while (index.parent(x) !is FirBlock) x = index.parent(x)?.takeIf { it !== lambda } ?: return false
            cur = x
        }
    }

    private fun holdsExit(
        f: FirElement,
        lambda: FirAnonymousFunction,
        values: Set<FirElement>,
    ): Boolean {
        var found = false
        f.accept(
            object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    if (element in values || element is FirReturnExpression && element.target.labeledElement === lambda) found = true
                    element.acceptChildren(this)
                }
            },
        )
        return found
    }

    // ---- text and positions ----

    private fun calleeName(call: FirFunctionCall): String = callee(call)?.callableId?.let { "${it.classId?.asString() ?: it.packageName.asString()}.${it.callableName}" }.orEmpty()

    /** The text of [e]'s source; it must equal the file text at the same offsets. */
    private fun textOf(e: FirElement): String {
        val s = e.source!!
        val t = text.substring(s.startOffset, s.endOffset)
        if (t != s.text?.toString()) list.problems += "$path:${s.startOffset}: the file text differs from the compiler's text"
        return t
    }

    private fun edit(
        e: FirElement,
        replacement: String,
    ): Edit = e.source!!.let { edit(it.startOffset, it.endOffset, replacement).also { textOf(e) } }

    private fun edit(
        start: Int,
        end: Int,
        replacement: String,
    ): Edit = Edit(start, end, text.substring(start, end), replacement)

    /** The operator token of a binary expression: the light-tree OPERATION_REFERENCE child of its source. */
    private fun operatorToken(e: FirElement): Pair<Int, Int>? {
        val source = e.source ?: return null
        val children = Ref<Array<LighterASTNode?>>()
        source.treeStructure.getChildren(source.lighterASTNode, children)
        val op = children.get().orEmpty().filterNotNull().firstOrNull { it.tokenType == KtNodeTypes.OPERATION_REFERENCE } ?: return null
        return op.startOffset to op.endOffset
    }

    private fun add(
        operator: Operator,
        scope: String,
        e: FirElement,
        edits: List<Edit>,
        facts: Map<String, String> = emptyMap(),
    ) {
        val s = e.source!!
        val pos = positions.ofOffset(s.startOffset) ?: return problem(e, "no position")
        val end = positions.ofOffset(s.endOffset) ?: return problem(e, "no position")
        list.sites += MutationSite(operator, scope, path, pos, end, edits, ledgerOf(unwrap(e)!!), facts)
    }

    private fun problem(
        e: FirElement,
        what: String,
    ) {
        list.problems += "${positions.of(e.source) ?: path}: $what"
    }
}
