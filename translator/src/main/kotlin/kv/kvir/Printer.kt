package kv.kvir

/** Column where source positions start, so that they line up (the format of DESIGN-v0.md 1.3). */
private const val POS_COLUMN = 66

/** Prints [module] as indented text, one node per line, each with its Kotlin `@line:col`. Used by golden tests. */
fun printModule(module: Module): String = KvirPrinter(module).print()

/** Prints operation [name] of [module] and the ASSUMPTION diagnostics its action names. Used by golden tests. */
fun printOperation(
    module: Module,
    name: String,
): String = KvirPrinter(module).printOperation(module.operations.single { it.name == name })

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

    /** `@line:col`; a position outside the module file names its file (DESIGN-B.md 2.1 rule 6: several audited files). */
    private fun at(p: Pos): String {
        val file = if (m.file != null && p.file != m.file) "${p.file.substringAfterLast('/')}:" else ""
        return "@$file${p.line}:${p.col}" + (p.fake?.let { " fake=$it" } ?: "")
    }

    fun print(): String {
        if (m.kind == ModuleKind.CLIENT) return printClient()
        line(0, "module ${m.id}${if (m.kind == ModuleKind.INSTANCE) " (instance: S is one object of the class)" else ""}")
        line(0, "files ${allPositions().map { it.file.substringAfterLast('/') }.distinct().sorted().joinToString()}")
        line(0, "types")
        m.types.forEach { line(1, "${it.name} = ${it.template?.let { t -> "template \"$t\"" } ?: "opaque"}${it.kclass?.let { k -> " ${k.replace('/', '.')}" } ?: ""}") }
        line(0, "state")
        m.state.forEach {
            when (it) {
                is MapVar -> line(1, "${it.name}: Map<${it.key.name}, ${it.valueClass}> = ${it.kotlin}${it.setOnce?.let { id -> " (set-once $id)" } ?: ""}", it.pos)
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
        return ledgerAndDiagnostics()
    }

    /** The ledger (consecutive nodes of one construct on one line) and the diagnostics. */
    private fun ledgerAndDiagnostics(): String {
        line(0, "ledger: ${m.firNodeCount} FIR nodes, ${m.ledger.size} in the ledger, ${translatedIds().size} translated")
        // Consecutive nodes of one construct share a line: `first-last kind what`.
        val runs = mutableListOf<MutableList<Disposition>>()
        for (d in m.ledger.sortedBy { it.node }) {
            val run = runs.lastOrNull()
            val prev = run?.last()
            if (prev != null && prev.node + 1 == d.node && prev.kind == d.kind && prev.what == d.what && prev.pos == d.pos) run.add(d) else runs += mutableListOf(d)
        }
        runs.forEach { r ->
            val nodes = if (r.size == 1) "${r[0].node}" else "${r[0].node}-${r.last().node}"
            line(1, "$nodes ${r[0].kind.label} ${r[0].what}", r[0].pos)
        }
        line(0, "diagnostics")
        m.diagnostics.forEach { line(1, "${it.id} ${it.kind} ${it.message}", it.pos) }
        return out.toString()
    }

    fun printOperation(op: Operation): String {
        operation(op)
        val ids = ((op.body.walk() + op.action.body.walk()).flatMap { s -> s.exprs().flatMap { it.walk() }.filterIsInstance<Arith>().mapNotNull { it.assumption } + listOfNotNull((s as? ModelOp)?.assumption) } + listOfNotNull(op.reserve?.assumption)).toSet()
        val used = m.diagnostics.filter { it.id in ids }
        if (used.isNotEmpty()) line(0, "assumptions")
        used.forEach { line(1, "${it.id} ${it.kind} ${it.message}", it.pos) }
        return out.toString()
    }

    private fun translatedIds(): Set<Int> =
        (
            (m.operations.map { it.action } + m.strayActions).flatMap { it.translated } + m.operations.flatMap { it.reserve?.translated.orEmpty() } +
                m.entries.flatMap { it.translated } + m.tasks.flatMap { it.translated }
        ).toSet()

    // ---- client modules (DESIGN-E 6.3) ----

    /** A client module: its vocabulary, each entry and each task with its precondition, then the ledger. */
    private fun printClient(): String {
        line(0, "client module ${m.id}")
        line(0, "files ${(m.entries.map { it.pos } + m.tasks.map { it.pos } + m.ledger.map { it.pos } + m.diagnostics.map { it.pos }).map { it.file.substringAfterLast('/') }.distinct().sorted().joinToString()}")
        line(0, "types")
        m.types.forEach { line(1, "${it.name} = ${it.template?.let { t -> "template \"$t\"" } ?: "opaque"}${it.kclass?.let { k -> " ${k.replace('/', '.')}" } ?: ""}") }
        line(0, "enums")
        m.enums.forEach { line(1, "${it.name} = ${it.kotlin} {${it.entries.joinToString()}}") }
        m.classes.forEach { c ->
            line(0, "class ${c.name} = ${c.kotlin}")
            c.fields.forEach { f -> line(1, "${f.name}: ${type(f.type)} = ${f.kotlin}") }
        }
        m.obligations.forEach { o -> line(0, "obligation ${o.name}(${o.types.joinToString { type(it) }})") }
        line(0, "thrown ${m.thrown.joinToString { it.label }.ifEmpty { "none" }}")
        for (e in m.entries) {
            line(0, "entry ${e.name} = ${e.kotlin}", e.pos)
            e.params.forEach { line(1, "param ${decl(it)}", it.pos) }
            e.free.forEach { line(1, "free  ${freeValue(it)}", it.local.pos) }
            e.result?.let { line(1, "result ${decl(it)}") }
            unitTail(e.status, e.translated, e.body)
        }
        for (t in m.tasks) {
            line(0, "task ${t.id} = lambda in ${t.inFunction}${if (t.via.isEmpty()) "" else " via ${t.via.joinToString()}"}, spawned by ${t.spawnedBy}", t.pos)
            t.params.forEach { line(1, "param ${decl(it)}", it.pos) }
            t.free.forEach { line(1, "free  ${freeValue(it)}", it.local.pos) }
            t.requires.forEach { line(1, "requires ${expr(it)}", it.pos) }
            t.holds.forEach { h -> line(1, "holds ${h.cond?.let { "${operand(it)} ==> " } ?: ""}${h.obligation}(${h.args.joinToString { expr(it) }})", h.pos) }
            unitTail(t.status, t.translated, t.body)
        }
        return ledgerAndDiagnostics()
    }

    private fun unitTail(
        status: Status,
        translated: Set<Int>,
        body: List<Stmt>,
    ) {
        if (status is Status.Unsupported) {
            line(1, "status unsupported")
            status.diagnostics.forEach { line(2, "${it.id} ${it.message}", it.pos) }
        }
        line(1, "translates FIR nodes ${ranges(translated)}")
        line(1, "body")
        body.forEach { stmt(2, it) }
    }

    private fun kinds(ks: List<ExcKind>) = ks.joinToString { it.label }

    private fun allPositions(): List<Pos> =
        m.state.map { it.pos } + m.classes.map { it.pos } + m.operations.map { it.pos } + m.strayActions.map { it.pos } +
            m.ledger.map { it.pos } + m.diagnostics.map { it.pos }

    /**
     * An object module's operation (`run action; return e`) prints as in DESIGN-v0.md 1.3: the result line holds `e`.
     * An instance module's operation prints its section (DESIGN-B.md 2.6) under `body`, before the action.
     */
    private fun operation(op: Operation) {
        line(0, "operation ${op.name} = ${op.kotlin}", op.pos)
        op.reserve?.let { r ->
            // DESIGN-D.md 6.2: the reserve step runs before the action, outside it.
            val value = if (r.template != null) "tokenOf(${r.counter.name}.incrementAndGet())" else "${r.counter.name}.incrementAndGet()"
            line(1, "reserve ${decl(r.local)} := $value${r.assumption?.let { " [ASSUME $it]" } ?: ""}", r.pos)
            line(2, "translates FIR nodes ${ranges(r.translated)}")
        }
        op.cells.forEach { line(1, "cell   ${decl(it.local)} := ${expr(it.init)}", it.pos) }
        val returned = op.returnedValue
        val result = op.result
        if (m.kind == ModuleKind.OBJECT) {
            if (result != null && returned != null) line(1, "result ${decl(result)} := ${expr(returned)}", returned.pos)
        } else {
            result?.let { line(1, "result ${decl(it)}") }
            if (op.canThrow) line(1, "throws")
            line(1, "body")
            running = op.action
            op.body.forEach { stmt(2, it) }
            running = null
        }
        action(1, "action", op.action)
    }

    /** The action a RunAction in the section being printed runs. */
    private var running: Action? = null

    private fun method(k: ActionKind): String =
        when (k) {
            ActionKind.COMPUTE -> "compute"
            ActionKind.COMPUTE_IF_ABSENT -> "computeIfAbsent"
            ActionKind.PUT_IF_ABSENT -> "putIfAbsent"
            ActionKind.PUT -> "put"
            ActionKind.REMOVE -> "remove"
        }

    private fun action(
        indent: Int,
        label: String,
        a: Action,
    ) {
        line(indent, "$label ${a.id} = ${a.map.kotlin}.${method(a.kind)}", a.pos)
        line(indent + 1, "key   ${decl(a.key.local)} <- ${a.key.kotlin} (${origin(a.key.origin)})", a.key.local.pos)
        line(indent + 1, "entry ${decl(a.entry)}", a.entry.pos)
        a.value?.let { line(indent + 1, "value ${expr(it)}", it.pos) }
        a.out?.let { line(indent + 1, "out   ${decl(it)}", it.pos) }
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
        // A reserved val has no sidecar name, but it is the exact value of the reserve step (DESIGN-D.md 6.2).
        val name = if (fv.sidecarName == null && fv.origin !is Reservation) "${fv.local.name} (unnamed)" else fv.local.name
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
            is Computed -> "computed ${at(o.pos)}"
            is Reservation -> "reserved ${at(o.pos)}"
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
            is RunAction -> line(indent, "run action ${running?.id ?: "?"}", s.pos)
            is Fresh -> line(indent, "fresh ${decl(s.v)} := ${s.fn}", s.pos)
            is RacyGet -> {
                val call = if (s.contains) "containsKey" else "get"
                line(indent, "racy ${decl(s.v)} := ${s.map.kotlin}.$call(${expr(s.key)})${if (s.contract) " [set-once contract]" else ""}", s.pos)
            }
            is Return -> line(indent, "return${s.e?.let { " ${expr(it)}" } ?: ""}", s.pos)
            is Raise -> line(indent, "raise", s.pos)
            is Call -> {
                val outs = if (s.outs.isEmpty()) "" else "${s.outs.joinToString { decl(it) }} := "
                val throws = if (s.throws.isEmpty()) "" else " throws ${kinds(s.throws)}"
                val obligation =
                    s.obligation?.let { o ->
                        "; ${if (o.creates) "creates" else "consumes"} ${o.obligation}(${o.args.joinToString { expr(it) }})${o.ifNotNull?.let { " if ${it.name} != null" } ?: ""}"
                    } ?: ""
                line(indent, "call $outs${s.fn}(${s.args.joinToString { expr(it) }})$throws$obligation$suffix", s.pos)
            }
            is Try -> {
                line(indent, "try", s.pos)
                s.body.forEach { stmt(indent + 1, it) }
                s.catches.forEach { c ->
                    line(indent, "catch ${c.type}: ${kinds(c.kinds)}", c.pos)
                    c.body.forEach { stmt(indent + 1, it) }
                }
                s.fin?.let { f ->
                    line(indent, "finally")
                    f.forEach { stmt(indent + 1, it) }
                }
            }
            is Throw -> line(indent, s.kind?.let { "throw ${it.label}" } ?: "rethrow", s.pos)
            is Spawn -> line(indent, "spawn ${s.task}(${s.args.joinToString { expr(it) }}) by ${s.model}${if (s.throws.isEmpty()) "" else " throws ${kinds(s.throws)}"}", s.pos)
            is While -> {
                line(indent, "while ${expr(s.cond)}${s.hole?.let { " [hole $it]" } ?: ""}${s.measure?.let { " [measure ${expr(it)}]" } ?: ""}$suffix", s.pos)
                s.body.forEach { stmt(indent + 1, it) }
            }
            is Break -> line(indent, "break", s.pos)
            is Inline -> {
                line(indent, "inline ${s.fn}${s.out?.let { " -> ${decl(it)}" } ?: ""}", s.pos)
                s.body.forEach { stmt(indent + 1, it) }
            }
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
            is KType.EnumT -> t.name + if (t.nullable) "?" else ""
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
            is Elvis -> "${operand(e.a)} ?: ${operand(e.b)}"
            is Ite -> "if ${operand(e.cond)} then ${operand(e.a)} else ${operand(e.b)}"
            is EnumLit -> "${e.type.name}.${e.entry}"
        }

    /** Operators inside operators get parentheses, so the printed text has one reading. */
    private fun operand(e: Expr): String =
        when (e) {
            is Arith, is Cmp, is And, is Or, is Not, is Elvis, is Ite -> "(${expr(e)})"
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
