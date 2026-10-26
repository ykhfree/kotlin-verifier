package kv.kvir

/**
 * The well-formedness rules of a client module (DESIGN-E 6.3): W1 positions, W4 names, W5 types and W14 ledger as for
 * the other modules (DESIGN-v0.md 1.4), and the new rules of DESIGN-E 6.3:
 * - W18: a catch has kinds (its Kotlin type mapped, rule 1);
 * - W19: a Spawn names a task of the module with an argument per task parameter, and each task has one Spawn (rule 3);
 * - W20: a While body has no obligation call and no Spawn (rule 4);
 * - W21: an obligation call names an obligation of the module with its arity (rule 5);
 * - W22: a Break is inside a While of its function, a Return inside an entry or an Inline, with a value only where
 *   one is expected.
 * Rule 2 (no cycle in the inlined helpers) is the lowering's: it reports a recursive call as unsupported.
 */
internal class ClientWellFormed(
    private val m: Module,
) {
    private val violations = mutableListOf<Violation>()
    private val types = m.types.associateBy { it.name }
    private val enums = m.enums.associateBy { it.name }
    private val classes = m.classes.associateBy { it.name }
    private val obligations = m.obligations.associateBy { it.name }
    private val tasks = m.tasks.associateBy { it.id }

    private fun report(
        rule: String,
        pos: Pos?,
        message: String,
    ) {
        violations += Violation(rule, message, pos)
    }

    fun run(): List<Violation> {
        for (e in m.entries) {
            if (e.status !is Status.Supported) continue
            unit(e.name, e.params + e.free.map { it.local } + listOfNotNull(e.result), e.body, e.result)
        }
        for (t in m.tasks) {
            if (t.status !is Status.Supported) continue
            unit(t.id, t.params + t.free.map { it.local }, t.body, null)
            (t.requires + t.holds.flatMap { h -> h.args + listOfNotNull(h.cond) }).forEach { e -> types(e) }
            t.requires.forEach { if (it.type != KType.BoolT) report("W5", it.pos, "task ${t.id} requires a non-Boolean ${it.type}") }
            t.holds.forEach { h -> obligation(h.obligation, h.args, h.pos) }
        }
        spawns()
        ledger()
        return violations
    }

    // ---- per unit ----

    private class Frame(
        val out: Local?,
        val inLoop: Boolean,
    )

    private fun unit(
        name: String,
        header: List<Local>,
        body: List<Stmt>,
        result: Local?,
    ) {
        val names = mutableMapOf<String, Pos>()
        header.forEach { l ->
            local(l)
            names.put(l.name, l.pos)?.let { report("W4", l.pos, "${l.name} is declared twice in $name") }
        }
        val scope = header.associateBy { it.name }.mapValues { (_, l) -> l to false }.toMutableMap()
        // The header's free values and parameters are not assigned; the result is the entry's out.
        block(body, scope, Frame(result, false), names, name)
    }

    private fun local(l: Local) {
        if (l.pos.line < 1) report("W1", l.pos, "local ${l.name} has no source position")
        declaredType(l.type, l.name, l.pos)
    }

    private fun declaredType(
        t: KType,
        what: String,
        pos: Pos,
    ) {
        when (t) {
            is KType.Abs -> if (t.name !in types) report("W5", pos, "$what has type ${t.name}, which is not a sidecar type")
            is KType.RefT -> if (t.cls !in classes) report("W5", pos, "$what has type ${t.cls}, which is not a module class")
            is KType.EnumT -> if (t.name !in enums) report("W5", pos, "$what has type ${t.name}, which is not a sidecar enum")
            else -> Unit
        }
    }

    /** [scope] maps a name to its local and whether it is mutable; declarations in [stmts] stay inside it. */
    private fun block(
        stmts: List<Stmt>,
        outer: MutableMap<String, Pair<Local, Boolean>>,
        frame: Frame,
        names: MutableMap<String, Pos>,
        unit: String,
    ) {
        val scope = outer.toMutableMap()

        fun declare(
            v: Local,
            mutable: Boolean,
            pos: Pos,
        ) {
            local(v)
            names.put(v.name, v.pos)?.let { if (it != v.pos || scope[v.name]?.first != v) report("W4", pos, "${v.name} is declared twice in $unit") }
            scope[v.name] = v to mutable
        }

        fun uses(e: Expr) {
            types(e)
            e.walk().filterIsInstance<Var>().forEach { x ->
                val b = scope[x.local.name]
                when {
                    b == null -> report("W4", x.pos, "${x.local.name} is not declared on this path")
                    b.first != x.local -> report("W4", x.pos, "${x.local.name} refers to another declaration of that name")
                }
            }
        }

        fun assign(
            v: Local,
            e: Expr?,
            pos: Pos,
        ) {
            val b = scope[v.name]
            when {
                b == null -> report("W4", pos, "${v.name} is assigned but not declared on this path")
                !b.second -> report("W4", pos, "${v.name} is assigned but not mutable")
                b.first != v -> report("W4", pos, "${v.name} is assigned through another declaration")
            }
            e?.let { if (!assignable(it.type, v.type)) report("W5", it.pos, "${it.type} is not assignable to ${v.name}: ${v.type}") }
        }
        for (s in stmts) {
            if (s.pos.line < 1) report("W1", s.pos, "${s::class.simpleName} has no source position")
            when (s) {
                is Let -> {
                    uses(s.e)
                    if (!assignable(s.e.type, s.v.type)) report("W5", s.e.pos, "${s.e.type} is not assignable to ${s.v.name}: ${s.v.type}")
                    declare(s.v, s.mutable, s.pos)
                }
                is Assign -> {
                    uses(s.e)
                    assign(s.v, s.e, s.pos)
                }
                is If -> {
                    uses(s.cond)
                    if (s.cond.type != KType.BoolT) report("W5", s.cond.pos, "condition has type ${s.cond.type}")
                    block(s.then, scope, frame, names, unit)
                    block(s.els, scope, frame, names, unit)
                }
                is Call -> {
                    s.args.forEach(::uses)
                    s.outs.forEach { o -> if (scope[o.name]?.first == o) assign(o, null, s.pos) else declare(o, false, s.pos) }
                    s.obligation?.let { o ->
                        o.args.forEach(::uses)
                        obligation(o.obligation, o.args, s.pos)
                    }
                    if (frame.inLoop && s.obligation != null) report("W20", s.pos, "a loop body creates or consumes an obligation (${s.fn}); the tool's loop invariant names no obligation (DESIGN-E 6.3 rule 4)")
                }
                is New -> {
                    s.init.values.forEach(::uses)
                    val cls = classes[s.cls.name]
                    if (cls == null) report("W5", s.pos, "new ${s.cls.name}: not a module class")
                    cls?.fields?.forEach { f -> s.init[f.name]?.let { if (!assignable(it.type, f.type)) report("W5", it.pos, "field ${f.name}: ${it.type} is not assignable to ${f.type}") } ?: report("W5", s.pos, "new ${s.cls.name} leaves ${f.name} without a value") }
                    declare(s.v, false, s.pos)
                }
                is Try -> {
                    block(s.body, scope, frame, names, unit)
                    s.catches.forEach { c ->
                        if (c.kinds.isEmpty()) report("W18", c.pos, "catch ${c.type} catches no kind")
                        block(c.body, scope, frame, names, unit)
                    }
                    s.fin?.let { block(it, scope, frame, names, unit) }
                }
                is Throw -> Unit
                is Spawn -> {
                    s.args.forEach(::uses)
                    if (frame.inLoop) report("W20", s.pos, "a loop body hands task ${s.task} to an executor (DESIGN-E 6.3 rule 4)")
                }
                is While -> {
                    uses(s.cond)
                    s.measure?.let(::uses)
                    if (s.cond.type != KType.BoolT) report("W5", s.cond.pos, "loop condition has type ${s.cond.type}")
                    block(s.body, scope, Frame(frame.out, true), names, unit)
                }
                is Break -> if (!frame.inLoop) report("W22", s.pos, "break outside a loop of its function")
                is Inline -> {
                    // The helper's Returns set the out; its body reads the caller's locals its arguments name.
                    block(s.body, scope, Frame(s.out, false), names, unit)
                    s.out?.let { declare(it, false, s.pos) }
                }
                is Return -> {
                    s.e?.let(::uses)
                    val out = frame.out
                    when {
                        s.e == null && out != null -> report("W22", s.pos, "return without a value where ${out.name} expects one")
                        s.e != null && out == null -> report("W22", s.pos, "return with a value where none is expected")
                        // A nullable local may be returned as non-null: Kotlin's stable smart cast proved it (DESIGN-v0.md 2.3).
                        s.e != null && out != null && !sameBase(s.e.type, out.type) -> report("W5", s.e.pos, "${s.e.type} is not assignable to ${out.name}: ${out.type}")
                    }
                }
                is Unsupported -> Unit
                else -> report("W22", s.pos, "${s::class.simpleName} is not a statement of a client module")
            }
        }
    }

    private fun types(e: Expr) {
        for (x in e.walk()) {
            if (x.pos.line < 1) report("W1", x.pos, "${x::class.simpleName} has no source position")
            when (x) {
                is NullLit -> if (!nullable(x.type)) report("W5", x.pos, "null has non-nullable type ${x.type}")
                is EnumLit -> if (enums[x.type.name]?.entries?.contains(x.entry) != true) report("W5", x.pos, "${x.type.name}.${x.entry} is no entry of a sidecar enum")
                is Cmp -> {
                    if (!sameBase(x.a.type, x.b.type)) report("W5", x.pos, "${x.a.type} is compared with ${x.b.type}")
                    if (x.op !in setOf(CmpOp.EQ, CmpOp.NE) && (x.a.type != KType.IntT || x.b.type != KType.IntT)) report("W5", x.pos, "${x.op.symbol} needs Int operands")
                }
                is Arith -> if (x.a.type != KType.IntT || x.b.type != KType.IntT) report("W5", x.pos, "arithmetic needs Int operands")
                is And, is Or, is Not -> x.children().forEach { if (it.type != KType.BoolT) report("W5", it.pos, "${x::class.simpleName} needs Bool, got ${it.type}") }
                else -> Unit
            }
        }
    }

    private fun nullable(t: KType) = (t as? KType.Abs)?.nullable ?: (t as? KType.RefT)?.nullable ?: (t as? KType.EnumT)?.nullable ?: false

    private fun sameBase(
        a: KType,
        b: KType,
    ): Boolean =
        when (a) {
            is KType.Abs -> b is KType.Abs && a.name == b.name
            is KType.RefT -> b is KType.RefT && a.cls == b.cls
            is KType.EnumT -> b is KType.EnumT && a.name == b.name
            else -> a == b
        }

    /** [from] fits [to]: the same type, or a non-null value of a nullable one. */
    private fun assignable(
        from: KType,
        to: KType,
    ): Boolean = from == to || sameBase(from, to) && (nullable(to) || !nullable(from))

    private fun obligation(
        name: String,
        args: List<Expr>,
        pos: Pos,
    ) {
        val o = obligations[name] ?: return report("W21", pos, "$name is not an obligation of the module")
        if (o.types.size != args.size) return report("W21", pos, "$name takes ${o.types.size} argument(s), got ${args.size}")
        o.types.zip(args).forEach { (t, a) -> if (!sameBase(a.type, t)) report("W21", a.pos, "argument of $name has type ${a.type}, not $t") }
    }

    // ---- module ----

    private fun spawns() {
        val all = (m.entries.flatMap { it.body } + m.tasks.flatMap { it.body }).walk().filterIsInstance<Spawn>().toList()
        for (s in all) {
            val t = tasks[s.task]
            if (t == null) {
                report("W19", s.pos, "spawn of ${s.task}, which is not a task of the module")
                continue
            }
            if (t.params.size != s.args.size) report("W19", s.pos, "spawn of ${t.id} gives ${s.args.size} argument(s) for ${t.params.size} parameter(s)")
            t.params.zip(s.args).forEach { (p, a) -> if (!assignable(a.type, p.type)) report("W19", a.pos, "argument for ${p.name}: ${a.type} is not assignable to ${p.type}") }
        }
        // An unsupported unit keeps only its Unsupported statements, so its spawns are not there to count.
        if ((m.entries.map { it.status } + m.tasks.map { it.status }).any { it !is Status.Supported }) return
        for (t in m.tasks) {
            val n = all.count { it.task == t.id }
            if (n != 1) report("W19", t.pos, "task ${t.id} has $n spawns; a task has one")
        }
    }

    /** W14: every FIR node is translated by an entry or a task, or in the ledger; not both, not twice in the ledger. */
    private fun ledger() {
        val translated = (m.entries.flatMap { it.translated } + m.tasks.flatMap { it.translated }).toSet()
        val inLedger = m.ledger.map { it.node }
        inLedger.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { report("W14", null, "FIR node $it is in the ledger more than once") }
        (inLedger.toSet() intersect translated).sorted().forEach { report("W14", null, "FIR node $it is both translated and in the ledger") }
        val missing = (0 until m.firNodeCount).filter { it !in translated && it !in inLedger }
        if (missing.isNotEmpty()) report("W14", null, "FIR nodes ${missing.take(10)}${if (missing.size > 10) " (${missing.size} in all)" else ""} are neither translated nor in the ledger")
    }
}
