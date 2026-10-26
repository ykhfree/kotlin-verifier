package kv.client

import kv.kvir.Break
import kv.kvir.Call
import kv.kvir.Catch
import kv.kvir.EntryFun
import kv.kvir.If
import kv.kvir.Inline
import kv.kvir.Module
import kv.kvir.Return
import kv.kvir.Spawn
import kv.kvir.Stmt
import kv.kvir.Try
import kv.kvir.While
import kv.kvir.checkWellFormed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The client well-formedness rules (DESIGN-E 6.3, W18-W22, with W4, W5 and W14 for client nodes), each shown by one
 * edit of the well-formed core ReqShield KVIR that breaks it.
 */
class ClientWellFormedTest {
    private val core = ClientGoldenTest.core

    /** [s] with every statement list rewritten by [f], innermost first. */
    private fun rewrite(
        s: List<Stmt>,
        f: (List<Stmt>) -> List<Stmt>,
    ): List<Stmt> =
        f(
            s.map { x ->
                when (x) {
                    is If -> x.copy(then = rewrite(x.then, f), els = rewrite(x.els, f))
                    is Try -> x.copy(body = rewrite(x.body, f), catches = x.catches.map { c -> c.copy(body = rewrite(c.body, f)) }, fin = x.fin?.let { rewrite(it, f) })
                    is While -> x.copy(body = rewrite(x.body, f))
                    is Inline -> x.copy(body = rewrite(x.body, f))
                    else -> x
                }
            },
        )

    private fun entry(f: (List<Stmt>) -> List<Stmt>): Module {
        val e: EntryFun = core.entries.single()
        return core.copy(entries = listOf(e.copy(body = rewrite(e.body, f))))
    }

    private fun rules(m: Module) = checkWellFormed(m).map { it.rule }

    @Test
    fun `the core module is well formed`() = assertEquals(emptyList(), checkWellFormed(core))

    @Test
    fun `W18 a catch without kinds`() {
        val m = entry { b -> b.map { if (it is Try) it.copy(catches = it.catches.map { c: Catch -> c.copy(kinds = emptyList()) }) else it } }
        assertTrue("W18" in rules(m), checkWellFormed(m).toString())
    }

    @Test
    fun `W19 a task without its spawn, and a spawn of no task`() {
        val dropped = entry { b -> b.filter { it !is Spawn } }
        assertEquals(listOf("W19", "W19"), rules(dropped).filter { it == "W19" }, checkWellFormed(dropped).toString())
        val renamed = entry { b -> b.map { if (it is Spawn && it.task == "task_69") it.copy(task = "task_70") else it } }
        assertTrue(checkWellFormed(renamed).any { it.rule == "W19" && "task_70, which is not a task" in it.message }, checkWellFormed(renamed).toString())
        val arity = entry { b -> b.map { if (it is Spawn) it.copy(args = it.args.drop(1)) else it } }
        assertTrue(checkWellFormed(arity).any { it.rule == "W19" && "argument(s) for" in it.message }, checkWellFormed(arity).toString())
    }

    @Test
    fun `W20 a loop body with an obligation call or a spawn`() {
        val unlock = core.entries.single().body.let { b -> rewrite(b) { it }.let { all -> collectCalls(all).first { c -> c.obligation?.creates == false } } }
        val m = entry { b -> b.map { if (it is While) it.copy(body = it.body + unlock) else it } }
        assertTrue(checkWellFormed(m).any { it.rule == "W20" && "creates or consumes an obligation" in it.message }, checkWellFormed(m).toString())
        val spawn = collectSpawns(core.entries.single().body).first()
        val s = entry { b -> b.map { if (it is While) it.copy(body = it.body + spawn) else it } }
        assertTrue(checkWellFormed(s).any { it.rule == "W20" && "hands task" in it.message }, checkWellFormed(s).toString())
    }

    @Test
    fun `W21 an obligation call with an unknown obligation or the wrong arity`() {
        val m = entry { b -> b.map { if (it is Call && it.obligation != null) it.copy(obligation = it.obligation!!.copy(obligation = "Leash")) else it } }
        assertTrue(checkWellFormed(m).any { it.rule == "W21" && "Leash is not an obligation" in it.message }, checkWellFormed(m).toString())
        val arity = entry { b -> b.map { if (it is Call && it.obligation != null) it.copy(obligation = it.obligation!!.copy(args = it.obligation!!.args.dropLast(1))) else it } }
        assertTrue(checkWellFormed(arity).any { it.rule == "W21" && "takes 3 argument(s), got 2" in it.message }, checkWellFormed(arity).toString())
    }

    @Test
    fun `W22 a break outside a loop and a return without its value`() {
        val brk = entry { b -> if (b.any { it is Return }) listOf(Break(b.first().pos)) + b else b }
        assertTrue(checkWellFormed(brk).any { it.rule == "W22" && "break outside a loop" in it.message }, checkWellFormed(brk).toString())
        val ret = entry { b -> b.map { if (it is Return && it.e != null) it.copy(e = null) else it } }
        assertTrue(checkWellFormed(ret).any { it.rule == "W22" && "return without a value" in it.message }, checkWellFormed(ret).toString())
    }

    @Test
    fun `W4 a read before the declaration, and W14 a node neither translated nor in the ledger`() {
        val e = core.entries.single()
        val swapped = core.copy(entries = listOf(e.copy(body = e.body.drop(1) + e.body.first())))
        assertTrue(checkWellFormed(swapped).any { it.rule == "W4" && "currentReqShieldData__43 is not declared on this path" in it.message }, checkWellFormed(swapped).toString())
        // A node only the entry translates (an inlined helper's node may be a task's too).
        val only = (e.translated - core.tasks.flatMap { it.translated }.toSet()).max()
        val lost = core.copy(entries = listOf(e.copy(translated = e.translated - only)))
        assertTrue(checkWellFormed(lost).any { it.rule == "W14" && "neither translated nor in the ledger" in it.message }, checkWellFormed(lost).toString())
        val twice = core.copy(ledger = core.ledger + core.ledger.first())
        assertTrue(checkWellFormed(twice).any { it.rule == "W14" && "more than once" in it.message }, checkWellFormed(twice).toString())
    }

    private fun collectCalls(s: List<Stmt>): List<Call> = all(s).filterIsInstance<Call>()

    private fun collectSpawns(s: List<Stmt>): List<Spawn> = all(s).filterIsInstance<Spawn>()

    private fun all(s: List<Stmt>): List<Stmt> =
        s.flatMap { x ->
            listOf(x) +
                when (x) {
                    is If -> all(x.then) + all(x.els)
                    is Try -> all(x.body) + x.catches.flatMap { all(it.body) } + all(x.fin.orEmpty())
                    is While -> all(x.body)
                    is Inline -> all(x.body)
                    else -> emptyList()
                }
        }
}
