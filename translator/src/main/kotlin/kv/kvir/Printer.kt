package kv.kvir

/** Column where source positions start, so that they line up (the format of DESIGN-v0.md 1.3). */
private const val POS_COLUMN = 66

/** Prints [module] as indented text, one node per line, each with its Kotlin `@line:col`. Used by golden tests. */
fun printModule(module: Module): String = KvirPrinter(module).print()

private class KvirPrinter(
    private val m: Module,
) {
    private val out = StringBuilder()

    private fun line(
        indent: Int,
        text: String,
        pos: Pos? = null,
    ) {
        val head = "  ".repeat(indent) + text
        out.append(head)
        if (pos != null) out.append(" ".repeat(maxOf(1, POS_COLUMN - head.length))).append(at(pos))
        out.append('\n')
    }

    private fun at(p: Pos): String = "@${p.line}:${p.col}" + (p.fake?.let { " fake=$it" } ?: "")

    fun print(): String {
        line(0, "module ${m.id}")
        line(0, "files ${allPositions().map { it.file.substringAfterLast('/') }.distinct().sorted().joinToString()}")
        line(0, "types")
        m.types.forEach { line(1, "${it.name} = ${it.template?.let { t -> "template \"$t\"" } ?: "opaque"}") }
        line(0, "state")
        m.state.forEach {
            when (it) {
                is MapVar -> line(1, "${it.name}: Map<${it.key.name}, ${it.valueClass}> = ${it.kotlin}", it.pos)
                is CounterVar -> line(1, "${it.name}: Counter = ${it.kotlin}, initial ${it.initial}", it.pos)
            }
        }
        m.classes.forEach { c ->
            line(0, "class ${c.name} = ${c.kotlin}", c.pos)
            c.fields.forEach { f ->
                val tags = listOfNotNull(f.model, "volatile".takeIf { f.volatile })
                val default = c.defaults[f.name]?.let { " default ${expr(it)} ${at(it.pos)}" } ?: ""
                line(1, "${f.name}: ${type(f.type)} = ${f.kotlin}${tags.joinToString("", " (", ")").takeIf { tags.isNotEmpty() } ?: ""}$default", f.pos)
            }
        }
        m.ghost?.let { g ->
            line(0, "ghost record ${g.name}")
            g.fields.forEach { (name, kind) -> line(1, "$name: ${kind.name.lowercase().replace('_', '-')}") }
        }
        m.operations.forEach(::operation)
        if (m.strayActions.isEmpty()) line(0, "stray actions: none")
        m.strayActions.forEach { action(0, "stray action", it) }
        line(0, "ledger: ${m.firNodeCount} FIR nodes, ${m.ledger.size} in the ledger, ${translatedIds().size} translated")
        m.ledger.sortedBy { it.node }.forEach { line(1, "${it.node} ${it.kind.label} ${it.what}", it.pos) }
        line(0, "diagnostics")
        m.diagnostics.forEach { line(1, "${it.id} ${it.kind} ${it.message}", it.pos) }
        return out.toString()
    }

    private fun translatedIds(): Set<Int> = (m.operations.map { it.action } + m.strayActions).flatMap { it.translated }.toSet()

    private fun allPositions(): List<Pos> =
        m.state.map { it.pos } + m.classes.map { it.pos } + m.operations.map { it.pos } + m.strayActions.map { it.pos } +
            m.ledger.map { it.pos } + m.diagnostics.map { it.pos }

    private fun operation(op: Operation) {
        line(0, "operation ${op.name} = ${op.kotlin}", op.pos)
        op.cells.forEach { line(1, "cell   ${decl(it.local)} := ${expr(it.init)}", it.pos) }
        op.result?.let { line(1, "result ${decl(it.local)} := ${expr(it.e)}", it.pos) }
        action(1, "action", op.action)
    }

    private fun action(
        indent: Int,
        label: String,
        a: Action,
    ) {
        line(indent, "$label ${a.id} = ${a.map.kotlin}.compute", a.pos)
        line(indent + 1, "key   ${decl(a.key.local)} <- ${a.key.kotlin} (${origin(a.key.origin)})", a.key.local.pos)
        line(indent + 1, "entry ${decl(a.entry)}", a.entry.pos)
        a.free.forEach { line(indent + 1, "free  ${freeValue(it)}", it.local.pos) }
        val status = a.status
        if (status is Status.Unsupported) {
            line(indent + 1, "status unsupported")
            status.diagnostics.forEach { line(indent + 2, "${it.id} ${it.message}", it.pos) }
        }
        line(indent + 1, "translates FIR nodes ${ranges(a.translated)}")
        line(indent + 1, "body")
        a.body.forEach { stmt(indent + 2, it) }
        line(indent + 1, "commit${if (a.commit.isEmpty()) " (empty)" else ""}")
        a.commit.forEach { ghost(indent + 2, a, it) }
    }

    private fun freeValue(fv: FreeValue): String {
        val name = if (fv.sidecarName == null) "${fv.local.name} (unnamed)" else fv.local.name
        return "$name: ${type(fv.local.type)} <- ${fv.kotlin} (${origin(fv.origin)})"
    }

    private fun origin(o: Origin): String =
        when (o) {
            is Captured -> "captured ${at(o.pos)}"
            is InstanceVal -> "instance val ${o.property} ${at(o.pos)}"
            is ActionKey -> "action key ${at(o.pos)}"
            is ClockRead -> "clock ${at(o.pos)}"
            is NondetCall -> "nondet ${o.model} ${at(o.pos)}"
            is RacyRead -> "racy read ${at(o.pos)}"
        }

    private fun stmt(
        indent: Int,
        s: Stmt,
    ) {
        val assumptions =
            (s.exprs().flatMap { it.walk() }.filterIsInstance<Arith>().mapNotNull { it.assumption } + listOfNotNull((s as? ModelOp)?.assumption))
                .distinct()
        val suffix = if (assumptions.isEmpty()) "" else " [ASSUME ${assumptions.joinToString()}]"
        when (s) {
            is Let -> line(indent, "${if (s.mutable) "var" else "let"} ${decl(s.v)} := ${expr(s.e)}$suffix", s.pos)
            is Assign -> line(indent, "${s.v.name} := ${expr(s.e)}$suffix", s.pos)
            is FieldWrite -> line(indent, "${expr(s.obj)}.${s.field.name} := ${expr(s.e)}$suffix", s.pos)
            is ModelOp -> {
                val target =
                    when (val t = s.target) {
                        is FieldTarget -> "${expr(t.obj)}.${t.field.name}"
                        is StateTarget -> t.v.name
                    }
                val call = "${s.op}(${(listOf(target) + s.args.map(::expr)).joinToString()})"
                line(indent, "${s.out?.let { "${it.name} := " } ?: ""}$call$suffix", s.pos)
            }
            is New -> {
                val init = s.cls.fields.filter { it.name in s.init }.joinToString { "${it.name} := ${expr(s.init.getValue(it.name))}" }
                line(indent, "new ${decl(s.v)} {$init}$suffix", s.pos)
            }
            is Nondet -> line(indent, "nondet ${s.outs.joinToString { it.name }} := ${s.model}", s.pos)
            is If -> {
                line(indent, "if ${expr(s.cond)}$suffix", s.pos)
                s.then.forEach { stmt(indent + 1, it) }
                if (s.els.isNotEmpty()) {
                    line(indent, "else")
                    s.els.forEach { stmt(indent + 1, it) }
                }
            }
            is Exit ->
                line(
                    indent,
                    when (val r = s.result) {
                        ExitResult.Keep -> "exit keep"
                        ExitResult.Remove -> "exit remove"
                        is ExitResult.Put -> "exit put(${expr(r.e)})"
                    },
                    s.pos,
                )
            is Unsupported -> line(indent, "unsupported ${s.what}: ${s.reason}", s.pos)
        }
    }

    private fun ghost(
        indent: Int,
        a: Action,
        g: GhostOp,
    ) {
        val text =
            when (g) {
                is EndIfNotCarried -> "end-if-not-carried(witness ${a.map.valueClass}.${g.witness.name})"
                is AssertFresh -> "assert-fresh(${expr(g.token)})"
                is Issue -> "issue(${expr(g.token)}) {${g.fields.entries.joinToString { (k, v) -> "$k = ${expr(v)}" }}}"
            }
        line(indent, text, g.pos)
    }

    private fun decl(l: Local): String = "${l.name}: ${type(l.type)}"

    private fun type(t: KType): String =
        when (t) {
            KType.IntT -> "Int"
            KType.BoolT -> "Bool"
            is KType.Abs -> t.name + if (t.nullable) "?" else ""
            is KType.RefT -> t.cls + if (t.nullable) "?" else ""
        }

    private fun expr(e: Expr): String =
        when (e) {
            is IntLit -> e.value.toString()
            is BoolLit -> e.value.toString()
            is NullLit -> "null"
            is Var -> e.local.name
            is FieldRead -> "${operand(e.obj)}.${e.field.name}"
            is Arith -> "${operand(e.a)} ${e.op.symbol} ${operand(e.b)}"
            is Cmp -> "${operand(e.a)} ${e.op.symbol} ${operand(e.b)}"
            is Not -> "!${operand(e.a)}"
            is And -> "${operand(e.a)} && ${operand(e.b)}"
            is Or -> "${operand(e.a)} || ${operand(e.b)}"
            is TokenOf -> "tokenOf(${expr(e.n)})"
            is Carried -> "carried(S, ${expr(e.key)}, ${expr(e.token)})"
        }

    /** Operators inside operators get parentheses, so the printed text has one reading. */
    private fun operand(e: Expr): String =
        when (e) {
            is Arith, is Cmp, is And, is Or, is Not -> "(${expr(e)})"
            else -> expr(e)
        }

    private fun ranges(ids: Set<Int>): String {
        if (ids.isEmpty()) return "none"
        val sorted = ids.sorted()
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var prev = start
        for (id in sorted.drop(1) + Int.MIN_VALUE) {
            if (id == prev + 1) {
                prev = id
                continue
            }
            parts += if (start == prev) "$start" else "$start-$prev"
            start = id
            prev = id
        }
        return parts.joinToString(",")
    }
}
