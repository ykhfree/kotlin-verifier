package kv.emit

import kv.sidecar.SPos
import kv.sidecar.SidecarError
import kv.sidecar.SidecarException
import kv.sidecar.Vexpr

// The sidecar expressions of a client module (DESIGN-E 6.2: guards, ensures, derived values, loop holes) as a small
// tree. The client emitter rewrites the client vocabulary (trace fields, holds(...), exception kinds) in this tree and
// prints it as Viper. The printer keeps the source's parentheses and adds one only where a rewritten part needs it.

/** A node of a sidecar expression. [at] is the offset of its first character in the source text, or -1. */
internal sealed interface VE {
    val at: Int

    data class Num(
        val value: Long,
        override val at: Int,
    ) : VE

    data class Id(
        val name: String,
        override val at: Int,
    ) : VE

    data class Call(
        val name: String,
        val args: List<VE>,
        override val at: Int,
    ) : VE

    data class Paren(
        val e: VE,
        override val at: Int,
    ) : VE

    data class Un(
        val op: String,
        val e: VE,
        override val at: Int,
    ) : VE

    data class Bin(
        val op: String,
        val a: VE,
        val b: VE,
        override val at: Int,
    ) : VE

    data class Cond(
        val c: VE,
        val a: VE,
        val b: VE,
        override val at: Int,
    ) : VE

    data class Dot(
        val e: VE,
        val field: String,
        override val at: Int,
    ) : VE
}

/** Binding strength of [op]: higher binds tighter (Viper's order). */
private fun prec(op: String): Int =
    when (op) {
        "<==>" -> 1
        "==>" -> 2
        "||" -> 3
        "&&" -> 4
        "==", "!=" -> 5
        "<", "<=", ">", ">=" -> 6
        "+", "-" -> 7
        else -> 8
    }

private fun prec(e: VE): Int =
    when (e) {
        is VE.Cond -> 0
        is VE.Bin -> prec(e.op)
        is VE.Un -> 9
        else -> 10
    }

/** [e] as Viper text. */
internal fun VE.print(): String =
    when (this) {
        is VE.Num -> "$value"
        is VE.Id -> name
        is VE.Call -> "$name(${args.joinToString(", ") { it.print() }})"
        is VE.Paren -> "(${e.print()})"
        is VE.Un -> op + operand(e, 9)
        // `==>` and `<==>` group to the right, the other binary operators to the left.
        is VE.Bin ->
            when (op) {
                "==>", "<==>" -> "${operand(a, prec(op) + 1)} $op ${operand(b, prec(op))}"
                // `&&` and `||` are associative: a right operand of the same operator needs no parentheses.
                "&&", "||" -> "${operand(a, prec(op))} $op ${operand(b, prec(op))}"
                else -> "${operand(a, prec(op))} $op ${operand(b, prec(op) + 1)}"
            }
        is VE.Cond -> "${operand(c, 1)} ? ${operand(a, 1)} : ${operand(b, 0)}"
        is VE.Dot -> "${operand(e, 10)}.$field"
    }

private fun operand(
    e: VE,
    min: Int,
): String = if (e !is VE.Paren && prec(e) < min) "(${e.print()})" else e.print()

/** The nodes of [e], [e] first. */
internal fun VE.walk(): Sequence<VE> =
    sequence {
        yield(this@walk)
        when (val e = this@walk) {
            is VE.Call -> e.args.forEach { yieldAll(it.walk()) }
            is VE.Paren -> yieldAll(e.e.walk())
            is VE.Un -> yieldAll(e.e.walk())
            is VE.Bin -> {
                yieldAll(e.a.walk())
                yieldAll(e.b.walk())
            }
            is VE.Cond -> {
                yieldAll(e.c.walk())
                yieldAll(e.a.walk())
                yieldAll(e.b.walk())
            }
            is VE.Dot -> yieldAll(e.e.walk())
            else -> Unit
        }
    }

/** [e] with every node that [f] maps to a non-null value replaced by it; [f] sees a node before its children. */
internal fun VE.rewrite(f: (VE) -> VE?): VE =
    f(this) ?: when (this) {
        is VE.Call -> copy(args = args.map { it.rewrite(f) })
        is VE.Paren -> copy(e = e.rewrite(f))
        is VE.Un -> copy(e = e.rewrite(f))
        is VE.Bin -> copy(a = a.rewrite(f), b = b.rewrite(f))
        is VE.Cond -> copy(c = c.rewrite(f), a = a.rewrite(f), b = b.rewrite(f))
        is VE.Dot -> copy(e = e.rewrite(f))
        else -> this
    }

/** [e] without the parentheses around it. */
internal fun VE.unparen(): VE = if (this is VE.Paren) e.unparen() else this

/** The top-level `&&` operands of [e] (one element if [e] is no conjunction). */
internal fun VE.conjuncts(): List<VE> = (this as? VE.Bin)?.takeIf { it.op == "&&" }?.let { it.a.conjuncts() + it.b.conjuncts() } ?: listOf(this)

/** Parses sidecar expression [v]; a syntax error is a sidecar error at its position. */
internal fun parseVE(v: Vexpr): VE = VEParser(v).parseAll()

private val TOKEN = Regex("""<==>|==>|==|!=|<=|>=|&&|\|\||[A-Za-z_][A-Za-z0-9_]*|\d+|[-+*/%!<>?:(),.]""")

private class VEParser(
    private val v: Vexpr,
) {
    private val toks = mutableListOf<Pair<String, Int>>()
    private var i = 0

    init {
        var j = 0
        val t = v.text
        while (j < t.length) {
            when {
                t[j].isWhitespace() -> j++
                t.startsWith("//", j) -> while (j < t.length && t[j] != '\n') j++
                else -> {
                    val m = TOKEN.matchAt(t, j) ?: fail(j, "unexpected character '${t[j]}'")
                    toks += m.value to j
                    j = m.range.last + 1
                }
            }
        }
    }

    fun fail(
        offset: Int,
        message: String,
    ): Nothing = throw SidecarException(listOf(SidecarError(v.posAt(offset.coerceAtMost(v.text.length)), "expression", message)))

    private fun peek(): String? = toks.getOrNull(i)?.first

    private fun offset(): Int = toks.getOrNull(i)?.second ?: v.text.length

    private fun next(): Pair<String, Int> = toks.getOrNull(i++) ?: fail(v.text.length, "the expression ends too early")

    private fun expect(s: String) {
        val (t, at) = next()
        if (t != s) fail(at, "expected '$s', found '$t'")
    }

    fun parseAll(): VE {
        val e = cond()
        if (i < toks.size) fail(offset(), "unexpected '${peek()}'")
        return e
    }

    private fun cond(): VE {
        val c = impl()
        if (peek() != "?") return c
        val at = offset()
        next()
        val a = cond()
        expect(":")
        return VE.Cond(c, a, cond(), at)
    }

    private fun impl(): VE {
        val a = or()
        val op = peek()
        if (op != "==>" && op != "<==>") return a
        val at = offset()
        next()
        return VE.Bin(op, a, impl(), at)
    }

    private fun left(
        ops: Set<String>,
        sub: () -> VE,
    ): VE {
        var a = sub()
        while (peek() in ops) {
            val at = offset()
            val op = next().first
            a = VE.Bin(op, a, sub(), at)
        }
        return a
    }

    private fun or(): VE = left(setOf("||")) { and() }

    private fun and(): VE = left(setOf("&&")) { eq() }

    private fun eq(): VE = left(setOf("==", "!=")) { rel() }

    private fun rel(): VE = left(setOf("<", "<=", ">", ">=")) { add() }

    private fun add(): VE = left(setOf("+", "-")) { mul() }

    private fun mul(): VE = left(setOf("*", "/", "%")) { un() }

    private fun un(): VE {
        val op = peek()
        if (op == "!" || op == "-") {
            val at = offset()
            next()
            return VE.Un(op, un(), at)
        }
        var e = atom()
        while (peek() == ".") {
            next()
            val (f, at) = next()
            if (!f[0].isLetter() && f[0] != '_') fail(at, "expected a field name after '.'")
            e = VE.Dot(e, f, at)
        }
        return e
    }

    private fun atom(): VE {
        val (t, at) = next()
        return when {
            t == "(" -> VE.Paren(cond().also { expect(")") }, at)
            t[0].isDigit() -> VE.Num(t.toLong(), at)
            t[0].isLetter() || t[0] == '_' ->
                if (peek() == "(") {
                    next()
                    val args = mutableListOf<VE>()
                    if (peek() != ")") {
                        do {
                            args += cond()
                        } while (peek() == "," && next().let { true })
                    }
                    expect(")")
                    VE.Call(t, args, at)
                } else {
                    VE.Id(t, at)
                }
            else -> fail(at, "unexpected '$t'")
        }
    }
}

/** The sidecar position of node [e] of expression [v]. */
internal fun Vexpr.posOf(e: VE): SPos = posAt(e.at.coerceIn(0, text.length))

/**
 * The value of [e] when the names in [known] have the given values (Int constants; Bool as 0 and 1), or null if it
 * depends on another name. A conditional with a known condition is the node of its chosen branch, so [choose] returns
 * that node: the derived value `PHASE` at a spawn is the enum entry it names (DESIGN-E 3.4).
 */
internal fun choose(
    e: VE,
    known: Map<String, Long>,
): VE? =
    when (val x = e.unparen()) {
        is VE.Cond -> constValue(x.c, known)?.let { choose(if (it != 0L) x.a else x.b, known) }
        else -> x.takeIf { constValue(it, known) != null }
    }

internal fun constValue(
    e: VE,
    known: Map<String, Long>,
): Long? =
    when (e) {
        is VE.Num -> e.value
        is VE.Id ->
            when (e.name) {
                "true" -> 1L
                "false" -> 0L
                else -> known[e.name]
            }
        is VE.Paren -> constValue(e.e, known)
        is VE.Un ->
            constValue(e.e, known)?.let {
                when (e.op) {
                    "!" -> if (it == 0L) 1L else 0L
                    else -> -it
                }
            }
        is VE.Cond -> constValue(e.c, known)?.let { constValue(if (it != 0L) e.a else e.b, known) }
        is VE.Bin -> {
            val a = constValue(e.a, known)
            val b = constValue(e.b, known)
            fun bool(x: Boolean) = if (x) 1L else 0L
            when (e.op) {
                // A short circuit decides with one known side.
                "&&" -> if (a == 0L || b == 0L) 0L else if (a != null && b != null) 1L else null
                "||" -> if (a == 1L || b == 1L) 1L else if (a != null && b != null) 0L else null
                "==>" -> if (a == 0L || b == 1L) 1L else if (a != null && b != null) 0L else null
                else ->
                    if (a == null || b == null) {
                        null
                    } else {
                        when (e.op) {
                            "==" -> bool(a == b)
                            "!=" -> bool(a != b)
                            "<" -> bool(a < b)
                            "<=" -> bool(a <= b)
                            ">" -> bool(a > b)
                            ">=" -> bool(a >= b)
                            "+" -> a + b
                            "-" -> a - b
                            "*" -> a * b
                            else -> null
                        }
                    }
            }
        }
        else -> null
    }

/**
 * True if every occurrence of name [name] in [e] is positive: under an even number of negations, never on the left of
 * `==>`, never inside a comparison or a condition. Replacing a positive Boolean by `false` can only make [e] stronger.
 */
internal fun positiveOnly(
    e: VE,
    name: String,
): Boolean {
    fun go(
        x: VE,
        pos: Boolean,
    ): Boolean =
        when (x) {
            is VE.Id -> x.name != name || pos
            is VE.Paren -> go(x.e, pos)
            is VE.Un -> if (x.op == "!") go(x.e, !pos) else x.walk().none { it is VE.Id && it.name == name }
            is VE.Bin ->
                when (x.op) {
                    "&&", "||" -> go(x.a, pos) && go(x.b, pos)
                    "==>" -> go(x.a, !pos) && go(x.b, pos)
                    else -> x.walk().none { it is VE.Id && it.name == name }
                }
            else -> x.walk().none { it is VE.Id && it.name == name }
        }
    return go(e, true)
}
