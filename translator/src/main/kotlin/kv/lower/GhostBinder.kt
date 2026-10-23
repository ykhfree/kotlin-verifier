package kv.lower

import kv.kvir.Action
import kv.kvir.Arith
import kv.kvir.ArithOp
import kv.kvir.AssertFresh
import kv.kvir.BoolLit
import kv.kvir.Carried
import kv.kvir.EndIfNotCarried
import kv.kvir.Expr
import kv.kvir.GhostOp
import kv.kvir.IntLit
import kv.kvir.Issue
import kv.kvir.KType
import kv.kvir.Local
import kv.kvir.Module
import kv.kvir.NullLit
import kv.kvir.Pos
import kv.kvir.Status
import kv.kvir.Var
import kv.sidecar.IssueItem
import kv.sidecar.Sidecar
import kv.sidecar.SidecarError
import kv.sidecar.SidecarException
import kv.sidecar.Vexpr

/**
 * The ghost binder (DESIGN-v0.md 3.5, M6): fills the commit of every supported action with the effect-bound rules.
 * - END: every action ends the pre-state token of its key's entry if the post-state entry no longer carries it.
 * - ISSUE: an operation with an `issue` rule checks that its non-null result was never issued (set-once), then
 *   records it; the frozen fields take the sidecar values.
 * Stray actions get END only. Throws [SidecarException] for an issue value that v0 cannot read.
 */
fun bindGhosts(
    module: Module,
    sidecar: Sidecar,
): Module {
    val witness =
        sidecar.witnesses.singleOrNull()?.let { w ->
            module.classes.find { it.name == w.cls.text }?.fields?.find { it.name == w.field.text }
        }

    fun bind(
        a: Action,
        issue: List<GhostOp>,
    ): Action {
        if (a.status != Status.Supported) return a
        return a.copy(commit = listOfNotNull(witness?.let { EndIfNotCarried(it, a.pos) }) + issue)
    }
    val operations =
        module.operations.map { op ->
            val item = sidecar.operations.single { it.name.text == op.name }.items.filterIsInstance<IssueItem>().singleOrNull()
            val result = op.result
            val issue =
                if (item == null || result == null || op.action.status != Status.Supported) {
                    emptyList()
                } else {
                    val token = Var(result.local, result.pos)
                    val scope = (listOf(op.action.key) + op.action.free.filter { it.sidecarName != null }).associate { it.local.name to it.local } + (result.local.name to result.local)
                    val fields = item.fields.associate { (name, value) -> name.text to IssueValue(value, scope, op.action.key.local, result.local, result.pos).parse() }
                    listOf(AssertFresh(token, result.pos), Issue(token, fields, result.pos))
                }
            op.copy(action = bind(op.action, issue))
        }
    return module.copy(operations = operations, strayActions = module.strayActions.map { bind(it, emptyList()) })
}

/**
 * Reads one issue value into KVIR. R1 (DESIGN-v0.md 3.5) allows the action key, free values, the result, constants,
 * arithmetic and `carried(S, k, t)`; v0 reads names, integer and boolean constants, NULL, `+`, `-`, parentheses and
 * `carried`. [pos] is the Kotlin position the KVIR nodes carry: the operation result.
 */
private class IssueValue(
    private val v: Vexpr,
    private val scope: Map<String, Local>,
    private val key: Local,
    private val result: Local,
    private val pos: Pos,
) {
    private val tokens = Regex("""[A-Za-z_]\w*|\d+|\S""").findAll(v.text).toList()
    private var i = 0

    private fun fail(
        at: Int,
        message: String,
    ): Nothing = throw SidecarException(listOf(SidecarError(v.posAt(at), "issue-value", "$message; v0 reads names, integer and boolean constants, NULL, +, -, parentheses and carried(S, k, t)")))

    private fun peek() = tokens.getOrNull(i)?.value

    private fun next(): MatchResult = tokens.getOrNull(i++) ?: fail(v.text.length, "the issue value ends early")

    private fun expect(s: String) {
        val t = next()
        if (t.value != s) fail(t.range.first, "expected '$s', found '${t.value}'")
    }

    fun parse(): Expr {
        val e = sum()
        tokens.getOrNull(i)?.let { fail(it.range.first, "unexpected '${it.value}'") }
        return e
    }

    private fun sum(): Expr {
        var e = term()
        while (peek() == "+" || peek() == "-") {
            val op = if (next().value == "+") ArithOp.PLUS else ArithOp.MINUS
            e = Arith(op, e, term(), null, pos)
        }
        return e
    }

    private fun term(): Expr {
        val t = next()
        val w = t.value
        return when {
            w == "(" -> sum().also { expect(")") }
            w[0].isDigit() -> IntLit(w.toLong(), pos)
            w == "true" || w == "false" -> BoolLit(w == "true", pos)
            w == "NULL" -> NullLit((result.type as? KType.Abs)?.copy(nullable = true) ?: fail(t.range.first, "NULL needs a token result"), pos)
            w == "carried" -> {
                expect("(")
                expect("S")
                expect(",")
                val k = sum()
                expect(",")
                val token = sum()
                expect(")")
                if ((k as? Var)?.local != key) fail(t.range.first, "carried must name the action key ${key.name}")
                Carried(k, token, pos)
            }
            w[0].isLetter() || w[0] == '_' -> Var(scope[w] ?: fail(t.range.first, "'$w' is not the key, a named free value or the result"), pos)
            else -> fail(t.range.first, "unexpected '$w'")
        }
    }
}
