package kv.lower

import kv.kvir.Disposition
import kv.kvir.DispositionKind
import kv.kvir.EntryFun
import kv.kvir.Task
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirAnonymousInitializer
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass

/**
 * The ledger of a client module (DESIGN-E 6.4, DESIGN-v0.md W14): every FIR node of the module file that no entry or
 * task translated gets one entry. A node takes the disposition of the innermost construct around it: code the lowering
 * checked has no effect (a callback that only logs), the module class and its constructor and properties (structure),
 * or the member or file declaration that holds it (not modeled).
 */
internal class ClientAudit(
    private val m: ClientLowering,
    entries: List<EntryFun>,
    tasks: List<Task>,
) {
    private val ctx = m.ctx
    private val index = ctx.index
    private val translated = (entries.flatMap { it.translated } + tasks.flatMap { it.translated }).toSet()

    fun run(): List<Disposition> =
        index.nodes.mapIndexedNotNull { id, node ->
            // A node of lowered code that no unit marked is left out, so that W14 finds it.
            if (id in translated || lowered(node)) return@mapIndexedNotNull null
            val (kind, what, at) = construct(node)
            Disposition(id, kind, what, ctx.posOf(at))
        }

    /** True if [node] is in an entry, an inlined helper or a task, outside a no-effect callback. */
    private fun lowered(node: FirElement): Boolean {
        var x: FirElement? = node
        while (x != null) {
            if (x in m.noEffect) return false
            if (x in m.lowered) return true
            x = index.parent(x)
        }
        return false
    }

    private fun construct(node: FirElement): Triple<DispositionKind, String, FirElement> {
        var x: FirElement? = node
        while (x != null) {
            m.noEffect[x]?.let { return Triple(DispositionKind.NO_EFFECT, it, x) }
            structure(x)?.let { return Triple(DispositionKind.STRUCTURE, it, x) }
            member(x)?.let { return Triple(DispositionKind.NOT_MODELED, it, x) }
            x = index.parent(x)
        }
        return Triple(DispositionKind.NOT_MODELED, "file ${index.fileOf(node).name}", node)
    }

    /** The module class, its primary constructor and its properties: the declarations the entries read paths from. */
    private fun structure(x: FirElement): String? =
        when {
            x === ctx.moduleClass -> "client module class ${ctx.moduleClass.name}"
            x is FirConstructor && index.parent(x) === ctx.moduleClass -> "constructor of ${ctx.moduleClass.name}"
            x is FirProperty && index.parent(x) === ctx.moduleClass -> "property ${x.name} of ${ctx.moduleClass.name}"
            else -> null
        }

    private fun member(x: FirElement): String? {
        val parent = index.parent(x)
        if (x !is FirDeclaration || parent !is FirRegularClass && parent !is FirFile) return null
        return when (x) {
            is FirNamedFunction -> "fun ${x.name}"
            is FirProperty -> "property ${x.name}"
            is FirRegularClass -> "class ${x.name}"
            is FirAnonymousInitializer -> "init"
            else -> x::class.simpleName.orEmpty()
        }
    }
}
