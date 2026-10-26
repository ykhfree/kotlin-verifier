package kv.frontend

import kv.kvir.Pos
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirFunctionConversionKind
import org.jetbrains.kotlin.fir.expressions.FirFunctionTypeConversionExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.unwrapSubstitutionOverrides
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

/** The class and name of the executor call that hands a task to another thread (DESIGN-E 3.3, 6.5). */
internal const val RUN_ASYNC_CLASS = "java/util/concurrent/CompletableFuture"
internal const val RUN_ASYNC = "runAsync"

/** A public function of a client module candidate and the private helpers it reaches (DESIGN-E 6.1). */
data class EntrySite(
    val pos: Pos,
    val name: String,
    /** The private member functions the entry calls, directly or through other helpers, in first-call order. */
    val helpers: List<String>,
)

/**
 * A task (DESIGN-E 3.4): a lambda literal that reaches a `CompletableFuture.runAsync` call, directly or through
 * function-typed parameters of private helpers ([via], innermost last).
 */
data class TaskSite(
    val lambda: Pos,
    /** The named function whose body holds the lambda literal. */
    val inFunction: String,
    val via: List<String>,
    /** The runAsync call. */
    val spawn: Pos,
)

/** A runAsync call whose task argument discovery cannot follow to a lambda literal. */
data class OpaqueSpawn(
    val spawn: Pos,
    val reason: String,
)

/**
 * A client module candidate (DESIGN-E 6.1, 6.10 E0): a class whose code hands a task to an executor. Its public
 * functions are the entries; their private helpers are inlined; the tasks run on other threads.
 */
data class ClientSite(
    val pos: Pos,
    val fqn: String,
    val entries: List<EntrySite>,
    val tasks: List<TaskSite>,
    val opaque: List<OpaqueSpawn>,
)

/** True if [call] is `CompletableFuture.runAsync(Runnable, ...)`, the spawn the client lowering models. */
internal fun isRunAsync(call: FirFunctionCall): Boolean {
    val sym = call.calleeReference.toResolvedCallableSymbol()?.unwrapSubstitutionOverrides() as? FirNamedFunctionSymbol ?: return false
    return sym.callableId.classId?.asString() == RUN_ASYNC_CLASS && sym.callableId.callableName.asString() == RUN_ASYNC
}

/** [e] without SAM conversions and smart casts. */
internal fun unwrapTask(e: FirExpression?): FirExpression? {
    var x = e
    while (true) {
        x =
            when (x) {
                is FirFunctionTypeConversionExpression -> if (x.kind == FirFunctionConversionKind.Sam) x.expression else return x
                is FirSmartCastExpression -> x.originalExpression
                else -> return x
            }
    }
}

/** The client module candidates of [file]: each class with a runAsync call, in source order (DESIGN-E 6.10 E0). */
@OptIn(DirectDeclarationsAccess::class)
fun findClients(file: FirFile): List<ClientSite> {
    val positions = Positions(file)
    val out = mutableListOf<ClientSite>()

    fun visit(cls: FirRegularClass) {
        val functions = cls.declarations.filterIsInstance<FirNamedFunction>()
        clientOf(cls, functions, positions)?.let { out += it }
        cls.declarations.filterIsInstance<FirRegularClass>().forEach(::visit)
    }
    file.declarations.filterIsInstance<FirRegularClass>().forEach(::visit)
    return out
}

private fun clientOf(
    cls: FirRegularClass,
    functions: List<FirNamedFunction>,
    positions: Positions,
): ClientSite? {
    val bySymbol = functions.associateBy { it.symbol }
    // Every call of a member function of this class, with the function that holds it (lambdas are transparent).
    val calls = mutableListOf<Pair<FirNamedFunction, FirFunctionCall>>()
    for (f in functions) {
        f.accept(
            object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) = element.acceptChildren(this)

                override fun visitFunctionCall(functionCall: FirFunctionCall) {
                    calls += f to functionCall
                    functionCall.acceptChildren(this)
                }
            },
        )
    }
    val spawns = calls.filter { isRunAsync(it.second) }
    if (spawns.isEmpty()) return null

    val tasks = mutableListOf<TaskSite>()
    val opaque = mutableListOf<OpaqueSpawn>()
    for ((holder, spawn) in spawns) {
        val spawnPos = positions.of(spawn.source)!!
        val seen = mutableSetOf<Pair<FirNamedFunctionSymbol, FirValueParameterSymbol>>()

        // Follows the task argument [e] of a call inside function [owner]; [via] lists the helpers it passed through.
        fun follow(
            e: FirExpression?,
            owner: FirNamedFunction,
            via: List<String>,
        ) {
            val x = unwrapTask(e)
            when {
                x is FirAnonymousFunctionExpression -> tasks += TaskSite(positions.of(x.anonymousFunction.source)!!, owner.name.asString(), via, spawnPos)
                x is FirPropertyAccessExpression && x.calleeReference.toResolvedCallableSymbol() is FirValueParameterSymbol -> {
                    val param = x.calleeReference.toResolvedCallableSymbol() as FirValueParameterSymbol
                    if (owner.valueParameters.none { it.symbol == param }) {
                        opaque += OpaqueSpawn(spawnPos, "the task is a parameter of a lambda, not of ${owner.name}")
                        return
                    }
                    if (!seen.add(owner.symbol to param)) return
                    val sites = calls.filter { (_, c) -> c.calleeReference.toResolvedCallableSymbol()?.unwrapSubstitutionOverrides() == owner.symbol }
                    if (owner.visibility != Visibilities.Private || sites.isEmpty()) {
                        opaque += OpaqueSpawn(spawnPos, "the task is parameter ${param.name} of ${owner.name}, which ${if (sites.isEmpty()) "no function of the class calls" else "is not private"}")
                        return
                    }
                    for ((caller, site) in sites) {
                        val arg = site.resolvedArgumentMapping?.entries?.find { it.value.symbol == param }?.key
                        follow(arg, caller, listOf(owner.name.asString()) + via)
                    }
                }
                else -> opaque += OpaqueSpawn(spawnPos, "the task argument is neither a lambda literal nor a function-typed parameter")
            }
        }
        follow(spawn.argumentList.arguments.firstOrNull(), holder, emptyList())
    }

    // Helpers: the private member functions an entry reaches through calls (DESIGN-E 6.4: they are inlined).
    fun helpersOf(entry: FirNamedFunction): List<String> {
        val reached = linkedSetOf<FirNamedFunctionSymbol>()
        val queue = ArrayDeque(listOf(entry))
        while (queue.isNotEmpty()) {
            val f = queue.removeFirst()
            for ((holder, c) in calls) {
                if (holder !== f) continue
                val callee = c.calleeReference.toResolvedCallableSymbol()?.unwrapSubstitutionOverrides() as? FirNamedFunctionSymbol ?: continue
                val helper = bySymbol[callee]?.takeIf { it.visibility == Visibilities.Private } ?: continue
                if (reached.add(callee)) queue += helper
            }
        }
        return reached.map { it.name.asString() }
    }
    val entries =
        functions.filter { it.visibility == Visibilities.Public }.map { f ->
            EntrySite(positions.ofDeclaration(f.source)!!, f.name.asString(), helpersOf(f))
        }
    return ClientSite(
        positions.ofDeclaration(cls.source)!!,
        cls.symbol.classId.asSingleFqName().asString(),
        entries,
        tasks.sortedWith(compareBy({ it.lambda.line }, { it.lambda.col })),
        opaque,
    )
}
