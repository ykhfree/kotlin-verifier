package kv.lower

import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirBackingField
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.FirReceiverParameter
import org.jetbrains.kotlin.fir.declarations.FirTypeParameter
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.text
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Numbers the FIR nodes of the module file for the ledger check W14 (DESIGN-v0.md 1.4, 2.14).
 *
 * A FIR node is a statement or declaration (expressions are statements) reached from the file's declarations.
 * Not nodes: type references, references, argument lists and when-branches (parts of their parent), annotations,
 * type and receiver parameters (no behavior), and the accessors and backing fields the compiler generates for a
 * property. Nodes are numbered in pre-order; a node reached twice keeps its first number.
 */
class FirIndex(
    val file: FirFile,
) {
    private val ids = IdentityHashMap<FirElement, Int>()
    private val parents = IdentityHashMap<FirElement, FirElement>()

    /** The nodes in pre-order: `nodes[id]` has number `id`. */
    val nodes = mutableListOf<FirElement>()

    /** The named functions declared in the file, local ones included. */
    val functions = mutableMapOf<FirNamedFunctionSymbol, FirNamedFunction>()

    init {
        val seen = Collections.newSetFromMap(IdentityHashMap<FirElement, Boolean>())
        file.acceptChildren(
            object : FirVisitorVoid() {
                private val stack = ArrayDeque<FirElement>(listOf(file))

                override fun visitElement(element: FirElement) {
                    if (!isPart(element) || !seen.add(element)) return
                    stack.lastOrNull()?.let { parents[element] = it }
                    if (isNode(element)) {
                        ids[element] = nodes.size
                        nodes += element
                    }
                    if (element is FirNamedFunction) functions[element.symbol] = element
                    stack.addLast(element)
                    element.acceptChildren(this)
                    stack.removeLast()
                }
            },
        )
    }

    /** False for the subtrees that hold no nodes. */
    private fun isPart(e: FirElement): Boolean =
        e !is FirAnnotation && e !is FirTypeParameter && e !is FirReceiverParameter && e !is FirBackingField &&
            !(e is FirPropertyAccessor && e.source?.kind is KtFakeSourceElementKind.DefaultAccessor)

    private fun isNode(e: FirElement): Boolean = e is FirStatement || e is FirDeclaration

    val count: Int get() = nodes.size

    fun id(e: FirElement): Int? = ids[e]

    fun parent(e: FirElement): FirElement? = parents[e]

    /** The numbers of [e] and of every node below it. */
    fun subtree(e: FirElement): List<Int> {
        val out = mutableListOf<Int>()
        e.accept(
            object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    if (!isPart(element)) return
                    ids[element]?.let { out += it }
                    element.acceptChildren(this)
                }
            },
        )
        return out
    }
}

/** The first line of [e]'s source text, at most [max] characters; for ledger entries and reports. */
fun sourceText(
    e: FirElement,
    max: Int = 60,
): String {
    val text = e.source?.text?.toString()?.lineSequence()?.first()?.trim() ?: return e::class.simpleName.orEmpty()
    return if (text.length <= max) text else text.take(max - 3) + "..."
}
