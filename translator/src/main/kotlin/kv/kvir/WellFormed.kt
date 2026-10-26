package kv.kvir

import kv.model.TrustedModels

/** A broken well-formedness rule. [rule] is `W1`..`W17` (DESIGN-v0.md 1.4, DESIGN-B.md 5.2, DESIGN-D.md 6.5). */
data class Violation(
    val rule: String,
    val message: String,
    val pos: Pos?,
) {
    override fun toString(): String = "$rule${pos?.let { " at $it" } ?: ""}: $message"
}

/**
 * Checks the well-formedness rules W1-W14 (DESIGN-v0.md 1.4) with the changes and the rules W15-W16 of DESIGN-B.md 5.2,
 * and W17 and the W10 change of DESIGN-D.md 6.5 (reserve steps).
 * The emitter must not run on a module with a violation.
 * W2 (expressions are pure) holds by construction: no [Expr] subtype has an effect, and every effectful node is a
 * [Stmt], so the Kotlin compiler rejects an effectful node in an expression position.
 */
fun checkWellFormed(module: Module): List<Violation> = WellFormedChecker(module).run()

// ---- tree walks, shared by the checker and the printer ----

internal fun Expr.children(): List<Expr> =
    when (this) {
        is IntLit, is BoolLit, is NullLit, is Var -> emptyList()
        is FieldRead -> listOf(obj)
        is Arith -> listOf(a, b)
        is Cmp -> listOf(a, b)
        is Not -> listOf(a)
        is And -> listOf(a, b)
        is Or -> listOf(a, b)
        is TokenOf -> listOf(n)
        is Carried -> listOf(key, token)
        is Elvis -> listOf(a, b)
        is Ite -> listOf(cond, a, b)
    }

/** This expression and all its subexpressions. */
internal fun Expr.walk(): Sequence<Expr> =
    sequence {
        yield(this@walk)
        children().forEach { yieldAll(it.walk()) }
    }

/** The expressions a statement holds directly (not those of nested blocks). */
internal fun Stmt.exprs(): List<Expr> =
    when (this) {
        is Let -> listOf(e)
        is Assign -> listOf(e)
        is FieldWrite -> listOf(obj, e)
        is ModelOp -> listOfNotNull((target as? FieldTarget)?.obj) + args
        is New -> init.values.toList()
        is Nondet, is Unsupported, is RunAction, is Fresh, is Raise -> emptyList()
        is If -> listOf(cond)
        is Exit -> listOfNotNull((result as? ExitResult.Put)?.e)
        is RacyGet -> listOf(key)
        is Return -> listOfNotNull(e)
    }

/** These statements and all statements nested in them, in source order. */
internal fun List<Stmt>.walk(): Sequence<Stmt> =
    sequence {
        for (s in this@walk) {
            yield(s)
            if (s is If) {
                yieldAll(s.then.walk())
                yieldAll(s.els.walk())
            }
        }
    }

internal fun GhostOp.exprs(): List<Expr> =
    when (this) {
        is EndIfNotCarried -> emptyList()
        is Issue -> listOf(token) + fields.values
        is AssertFresh -> listOf(token)
    }

private fun KType.isNullable(): Boolean =
    when (this) {
        is KType.Abs -> nullable
        is KType.RefT -> nullable
        KType.IntT, KType.BoolT -> false
    }

/** Same type ignoring nullability. */
private fun sameBase(
    a: KType,
    b: KType,
): Boolean =
    when (a) {
        is KType.Abs -> b is KType.Abs && a.name == b.name
        is KType.RefT -> b is KType.RefT && a.cls == b.cls
        KType.IntT, KType.BoolT -> a == b
    }

private fun assignable(
    from: KType,
    to: KType,
): Boolean = sameBase(from, to) && (!from.isNullable() || to.isNullable())

private class WellFormedChecker(
    private val m: Module,
) {
    private val out = mutableListOf<Violation>()
    private val classes = m.classes.associateBy { it.name }
    private val types = m.types.associateBy { it.name }

    /** The local that [s] declares or writes: a declaration's variable or an op's out. */
    private fun outsOf(s: Stmt): List<Local> =
        when (s) {
            is Let -> listOf(s.v)
            is Assign -> listOf(s.v)
            is ModelOp -> listOfNotNull(s.out)
            is New -> listOf(s.v)
            is Nondet -> s.outs
            is Fresh -> listOf(s.v)
            is RacyGet -> listOf(s.v)
            else -> emptyList()
        }

    private fun report(
        rule: String,
        pos: Pos?,
        message: String,
    ) {
        out += Violation(rule, message, pos)
    }

    fun run(): List<Violation> {
        checkPositions()
        checkTypes()
        checkFreeValueNames()
        checkOperations()
        checkLedger()
        checkFresh()
        checkRacyGets()
        checkReserves()
        for (op in m.operations) checkAction(op.action, op)
        for (a in m.strayActions) checkAction(a, null)
        return out
    }

    private fun checkAction(
        a: Action,
        op: Operation?,
    ) {
        if (a.status is Status.Supported) checkExits(a)
        checkNames(a, op)
        checkModuleAccess(a, op)
        checkFlattenedFields(a)
        if (op != null) checkCells(a, op)
        checkCommit(a, op)
        checkUnsupportedStatus(a)
        checkAssumptions(a, op)
    }

    // ---- W1: every node has a real position ----

    private fun checkPositions() {
        fun pos(
            what: String,
            p: Pos,
        ) {
            if (p.file.isBlank() || p.line < 1 || p.col < 1) report("W1", p, "$what has no source position")
        }

        fun expr(e: Expr) = e.walk().forEach { pos("expression ${it::class.simpleName}", it.pos) }

        fun local(l: Local) = pos("local ${l.name}", l.pos)

        fun stmts(block: List<Stmt>) =
            block.walk().forEach { s ->
                pos("statement ${s::class.simpleName}", s.pos)
                s.exprs().forEach(::expr)
                outsOf(s).forEach(::local)
            }

        fun action(a: Action) {
            pos("action ${a.id}", a.pos)
            (listOf(a.key) + a.free).forEach {
                local(it.local)
                pos("origin of ${it.local.name}", it.origin.pos)
            }
            local(a.entry)
            a.value?.let(::expr)
            a.out?.let(::local)
            stmts(a.body)
            a.commit.forEach { g ->
                pos("ghost op ${g::class.simpleName}", g.pos)
                g.exprs().forEach(::expr)
            }
            (a.status as? Status.Unsupported)?.diagnostics?.forEach { pos("diagnostic ${it.id}", it.pos) }
        }

        m.state.forEach { pos("state ${it.name}", it.pos) }
        m.classes.forEach { c ->
            pos("class ${c.name}", c.pos)
            c.fields.forEach { pos("field ${c.name}.${it.name}", it.pos) }
            c.defaults.values.forEach(::expr)
        }
        m.operations.forEach { op ->
            pos("operation ${op.name}", op.pos)
            op.cells.forEach {
                pos("cell ${it.local.name}", it.pos)
                local(it.local)
                expr(it.init)
            }
            op.result?.let(::local)
            stmts(op.body)
            action(op.action)
            op.reserve?.let {
                pos("reserve step of ${op.name}", it.pos)
                local(it.local)
            }
        }
        m.strayActions.forEach(::action)
        m.ledger.forEach { pos("ledger entry for node ${it.node}", it.pos) }
        m.diagnostics.forEach { pos("diagnostic ${it.id}", it.pos) }
    }

    // ---- W3: no early exits; every path of a supported action ends with exactly one Exit ----

    private fun checkExits(a: Action) {
        if (a.kind == ActionKind.COMPUTE || a.kind == ActionKind.COMPUTE_IF_ABSENT) {
            tailBlock(a.body, a.pos)
        } else if (a.body.isNotEmpty()) {
            report("W3", a.pos, "a ${a.kind.name.lowercase()} action has no lambda body, but ${a.id} has statements")
        }
    }

    /** A block that ends the action: it ends with an Exit or with an If whose branches are tail blocks. */
    private fun tailBlock(
        block: List<Stmt>,
        at: Pos,
    ) {
        val last = block.lastOrNull()
        if (last == null) {
            report("W3", at, "a path ends without exit")
            return
        }
        block.dropLast(1).forEach(::notTail)
        when (last) {
            // DESIGN-B.md 5.2 W3: a path that raises ends without writing the map.
            is Exit, is Raise -> Unit
            is If -> {
                tailBlock(last.then, last.pos)
                tailBlock(last.els, last.pos)
            }
            else -> report("W3", last.pos, "a path ends after this ${last::class.simpleName} without exit")
        }
    }

    /** A statement that other statements follow: neither it nor anything nested in it may exit or raise. */
    private fun notTail(s: Stmt) {
        when (s) {
            is Exit -> report("W3", s.pos, "exit is followed by more statements on its path")
            is Raise -> report("W3", s.pos, "raise is followed by more statements on its path")
            is If -> (s.then + s.els).forEach(::notTail)
            else -> Unit
        }
    }

    // ---- W4: every Var refers to a declaration earlier on its path; names are unique in an action ----

    private enum class Kind { HEADER, FREE, CELL, LOCAL }

    private class Binding(
        val local: Local,
        val mutable: Boolean,
        val kind: Kind,
        val origin: Origin? = null,
    )

    private fun checkNames(
        a: Action,
        op: Operation?,
    ) {
        val declared = mutableSetOf<String>()

        fun unique(
            name: String,
            pos: Pos,
        ) {
            if (!declared.add(name)) report("W4", pos, "$name is declared twice in action ${a.id}")
        }

        val header = mutableMapOf<String, Binding>()

        fun headerDecl(
            b: Binding,
            pos: Pos,
        ) {
            unique(b.local.name, pos)
            header[b.local.name] = b
        }

        headerDecl(Binding(a.key.local, false, Kind.HEADER, a.key.origin), a.key.local.pos)
        headerDecl(Binding(a.entry, false, Kind.HEADER), a.entry.pos)
        a.free.forEach { headerDecl(Binding(it.local, false, Kind.FREE, it.origin), it.local.pos) }
        // Two cells with one name break W8, not W4.
        op?.cells?.distinctBy { it.local.name }?.forEach { headerDecl(Binding(it.local, true, Kind.CELL), it.pos) }
        block(a.body, header, ::unique)
        if (op == null) return
        // The operation section sees the header without the entry; RunAction declares the action's out.
        val section = header.filterKeys { it != a.entry.name }
        block(op.body, section, ::unique) { scope ->
            val o = a.out ?: return@block
            if (o == a.entry) {
                scope[o.name] = header.getValue(o.name)
            } else {
                unique(o.name, o.pos)
                scope[o.name] = Binding(o, false, Kind.LOCAL)
            }
        }
    }

    /**
     * Checks [stmts] with the names of [outer] in scope; [unique] checks a new name against the whole action and
     * its operation section. [run] puts the names a RunAction declares in scope.
     */
    private fun block(
        stmts: List<Stmt>,
        outer: Map<String, Binding>,
        unique: (String, Pos) -> Unit,
        run: ((MutableMap<String, Binding>) -> Unit)? = null,
    ) {
        val scope = outer.toMutableMap()

        fun decl(b: Binding) {
            unique(b.local.name, b.local.pos)
            scope[b.local.name] = b
        }

        /** An op output assigns a mutable local already in scope, or declares a new one. */
        fun output(
            v: Local,
            pos: Pos,
        ) {
            val b = scope[v.name]
            when {
                b == null -> decl(Binding(v, false, Kind.LOCAL))
                !b.mutable -> report("W4", pos, "${v.name} is not mutable but receives an op result")
                b.local != v -> report("W4", pos, "${v.name} receives a value of another declaration")
            }
        }

        for (s in stmts) {
            s.exprs().forEach { e -> uses(e, scope) }
            when (s) {
                is Let -> decl(Binding(s.v, s.mutable, Kind.LOCAL))
                is Assign -> {
                    val b = scope[s.v.name]
                    when {
                        b == null -> report("W4", s.pos, "${s.v.name} is assigned but not declared on this path")
                        !b.mutable -> report("W4", s.pos, "${s.v.name} is assigned but not mutable")
                        b.local != s.v -> report("W4", s.pos, "${s.v.name} is assigned through another declaration")
                    }
                }
                is ModelOp -> s.out?.let { output(it, s.pos) }
                is New -> decl(Binding(s.v, false, Kind.LOCAL))
                is Fresh -> decl(Binding(s.v, false, Kind.LOCAL))
                is RacyGet -> decl(Binding(s.v, false, Kind.LOCAL))
                is RunAction -> run?.invoke(scope)
                is Nondet ->
                    s.outs.forEach { o ->
                        val b = scope[o.name]
                        // A named non-deterministic free value is bound at its call.
                        val bindsFree = b?.kind == Kind.FREE && (b.origin as? NondetCall)?.pos == s.pos && b.local == o
                        if (b == null) decl(Binding(o, false, Kind.LOCAL)) else if (!bindsFree) report("W4", s.pos, "${o.name} is already declared")
                    }
                is If -> {
                    block(s.then, scope, unique, run)
                    block(s.els, scope, unique, run)
                }
                is FieldWrite, is Exit, is Unsupported, is Return, is Raise -> Unit
            }
        }
    }

    private fun uses(
        e: Expr,
        scope: Map<String, Binding>,
    ) {
        e.walk().filterIsInstance<Var>().forEach { v ->
            val b = scope[v.local.name]
            when {
                b == null -> report("W4", v.pos, "${v.local.name} is not declared on this path")
                b.local != v.local -> report("W4", v.pos, "${v.local.name} refers to another declaration of that name")
            }
        }
    }

    // ---- W5: types ----

    private fun checkTypes() {
        fun declared(
            t: KType,
            what: String,
            pos: Pos,
        ) {
            when (t) {
                is KType.Abs -> if (t.name !in types) report("W5", pos, "$what has type ${t.name}, which is not a sidecar type")
                is KType.RefT -> if (t.cls !in classes) report("W5", pos, "$what has type ${t.cls}, which is not a module class")
                KType.IntT, KType.BoolT -> Unit
            }
        }

        fun local(l: Local) = declared(l.type, l.name, l.pos)

        fun expr(e: Expr) =
            e.walk().forEach { x ->
                if (x is NullLit) {
                    declared(x.type, "null", x.pos)
                    if (!x.type.isNullable()) report("W5", x.pos, "null has non-nullable type ${x.type}")
                }
                if (x is TokenOf) declared(x.type, "token", x.pos)
                exprType(x)
            }

        fun assign(
            from: Expr,
            to: KType,
            what: String,
        ) {
            expr(from)
            if (!assignable(from.type, to)) report("W5", from.pos, "$what: ${from.type} is not assignable to $to")
        }

        m.state.filterIsInstance<MapVar>().forEach { v ->
            declared(v.key, "key of ${v.name}", v.pos)
            if (v.valueClass !in classes) report("W5", v.pos, "map ${v.name} holds ${v.valueClass}, which is not a module class")
        }
        m.classes.forEach { c ->
            c.fields.forEach { declared(it.type, "${c.name}.${it.name}", it.pos) }
            c.defaults.forEach { (name, e) ->
                val f = c.fields.find { it.name == name }
                if (f == null) report("W5", e.pos, "default for unknown field ${c.name}.$name") else assign(e, f.type, "default of ${c.name}.$name")
            }
        }

        val mapValue = { a: Action -> KType.RefT(a.map.valueClass, true) }

        /** The statements of an action body or an operation section; [a] is the action they belong to. */
        fun stmts(
            block: List<Stmt>,
            a: Action,
        ) {
            block.walk().forEach { s ->
                when (s) {
                    is Let -> {
                        local(s.v)
                        assign(s.e, s.v.type, "let ${s.v.name}")
                    }
                    is Assign -> assign(s.e, s.v.type, "assignment to ${s.v.name}")
                    is FieldWrite -> {
                        expr(s.obj)
                        fieldOf(s.obj, s.field)
                        assign(s.e, s.field.type, "write of ${s.field.name}")
                    }
                    is ModelOp -> {
                        s.out?.let(::local)
                        (s.target as? FieldTarget)?.let {
                            expr(it.obj)
                            fieldOf(it.obj, it.field)
                        }
                        s.args.forEach(::expr)
                    }
                    is New -> {
                        local(s.v)
                        if (s.v.type != KType.RefT(s.cls.name, false)) report("W5", s.pos, "new ${s.v.name} must have type ${s.cls.name}")
                        val missing = s.cls.fields.map { it.name } - s.init.keys
                        if (missing.isNotEmpty()) report("W5", s.pos, "new ${s.cls.name} leaves $missing without a value")
                        s.init.forEach { (name, e) ->
                            val f = s.cls.fields.find { it.name == name }
                            if (f == null) report("W5", e.pos, "new ${s.cls.name} sets unknown field $name") else assign(e, f.type, "field $name")
                        }
                    }
                    is Nondet -> s.outs.forEach(::local)
                    is If -> {
                        expr(s.cond)
                        if (s.cond.type != KType.BoolT) report("W5", s.cond.pos, "condition has type ${s.cond.type}")
                    }
                    is Exit -> (s.result as? ExitResult.Put)?.let { assign(it.e, mapValue(a), "put") }
                    is Fresh -> {
                        local(s.v)
                        if (s.v.type != KType.RefT(s.cls.name, false)) report("W5", s.pos, "fresh ${s.v.name} must have type ${s.cls.name}")
                    }
                    is RacyGet -> {
                        local(s.v)
                        assign(s.key, s.map.key, "key of the racy read ${s.v.name}")
                        val want = if (s.contains) KType.BoolT else KType.RefT(s.map.valueClass, true)
                        if (s.v.type != want) report("W5", s.pos, "racy read ${s.v.name} must have type $want")
                    }
                    is Return -> Unit // checked against the operation result
                    is Unsupported, is RunAction, is Raise -> Unit
                }
            }
        }

        fun action(a: Action) {
            (listOf(a.key) + a.free).forEach { local(it.local) }
            local(a.entry)
            if (a.entry.type != mapValue(a)) report("W5", a.entry.pos, "entry ${a.entry.name} must have type ${a.map.valueClass}?")
            if (a.key.local.type != a.map.key) report("W5", a.key.local.pos, "key ${a.key.local.name} must have type ${a.map.key.name}")
            a.out?.let {
                local(it)
                if (it.type != mapValue(a)) report("W5", it.pos, "out ${it.name} must have type ${a.map.valueClass}?")
            }
            a.value?.let { assign(it, mapValue(a), "value of ${a.id}") }
            val needsValue = a.kind == ActionKind.PUT || a.kind == ActionKind.PUT_IF_ABSENT
            if (a.status is Status.Supported && needsValue != (a.value != null)) report("W5", a.pos, "a ${a.kind.name.lowercase()} action ${if (needsValue) "needs" else "takes no"} value")
            stmts(a.body, a)
            a.commit.forEach { g -> g.exprs().forEach(::expr) }
        }

        m.operations.forEach { op ->
            op.cells.forEach {
                local(it.local)
                assign(it.init, it.local.type, "cell ${it.local.name}")
            }
            op.result?.let(::local)
            stmts(op.body, op.action)
            op.body.walk().filterIsInstance<Return>().forEach { r ->
                val e = r.e
                val result = op.result
                when {
                    e != null && result != null -> assign(e, result.type, "result of ${op.name}")
                    e != null -> report("W5", r.pos, "operation ${op.name} has no result, but returns a value")
                    result != null -> report("W5", r.pos, "operation ${op.name} returns no value for its result ${result.name}")
                }
            }
            action(op.action)
        }
        m.strayActions.forEach(::action)
    }

    /** Checks the operand types of [e] itself; subexpressions are checked on their own. */
    private fun exprType(e: Expr) {
        fun need(
            x: Expr,
            t: KType,
        ) {
            if (x.type != t) report("W5", x.pos, "${e::class.simpleName} needs $t, got ${x.type}")
        }
        when (e) {
            is FieldRead -> fieldOf(e.obj, e.field)
            is Arith -> {
                need(e.a, KType.IntT)
                need(e.b, KType.IntT)
            }
            is Cmp ->
                if (e.op == CmpOp.EQ || e.op == CmpOp.NE) {
                    if (!sameBase(e.a.type, e.b.type)) report("W5", e.pos, "${e.a.type} is compared with ${e.b.type}")
                } else {
                    need(e.a, KType.IntT)
                    need(e.b, KType.IntT)
                }
            is Not -> need(e.a, KType.BoolT)
            is And -> {
                need(e.a, KType.BoolT)
                need(e.b, KType.BoolT)
            }
            is Or -> {
                need(e.a, KType.BoolT)
                need(e.b, KType.BoolT)
            }
            is TokenOf -> {
                need(e.n, KType.IntT)
                if (types[e.type.name]?.template == null || e.type.nullable) report("W5", e.pos, "token type ${e.type} is not a non-null template type")
            }
            is Carried -> {
                val key = e.key.type
                val token = e.token.type
                if (key !is KType.Abs || types[key.name]?.template != null) report("W5", e.key.pos, "carried needs an opaque key, got $key")
                if (token !is KType.Abs || types[token.name]?.template == null) report("W5", e.token.pos, "carried needs a template token, got $token")
            }
            is Elvis -> if (!sameBase(e.a.type, e.b.type) || e.a.type !is KType.RefT) report("W5", e.pos, "?: needs two objects of one class, got ${e.a.type} and ${e.b.type}")
            is Ite -> {
                need(e.cond, KType.BoolT)
                if (!assignable(e.a.type, e.type) || !assignable(e.b.type, e.type)) report("W5", e.pos, "if-else of type ${e.type} has values of type ${e.a.type} and ${e.b.type}")
            }
            is IntLit, is BoolLit, is NullLit, is Var -> Unit
        }
    }

    private fun fieldOf(
        obj: Expr,
        field: ObjField,
    ) {
        val t = obj.type
        if (t !is KType.RefT || classes[t.cls]?.fields?.contains(field) != true) {
            report("W5", obj.pos, "${field.name} is not a field of $t")
        }
    }

    // ---- W6: inside an action, module objects are reached only through the entry, new objects and counters ----

    private fun checkModuleAccess(
        a: Action,
        op: Operation?,
    ) {
        if (a.map !in m.state) report("W6", a.pos, "action ${a.id} maps ${a.map.name}, which is not module state")
        // DESIGN-B.md 5.2 W6: a fresh object is created by the action too.
        val owned = setOf(a.entry.name) + a.body.walk().mapNotNull { (it as? New)?.v?.name ?: (it as? Fresh)?.v?.name }
        // M5 (DESIGN-B.md 7.2): an action may map an object it got from outside if no field of its class is modeled,
        // so that nothing reaches into it; MI_OWN decides whether that object may be mapped.
        fun opaque(e: Expr) = (e.type as? KType.RefT)?.let { t -> classes[t.cls]?.fields?.isEmpty() } == true

        fun ownedObj(
            e: Expr,
            what: String,
            alsoOpaque: Boolean = false,
        ) {
            val ok = e is Var && (e.local.name in owned || alsoOpaque && opaque(e))
            if (!ok) report("W6", e.pos, "$what an object that is neither the entry nor created by the action")
        }
        a.body.walk().forEach { s ->
            when (s) {
                is FieldWrite -> ownedObj(s.obj, "writes ${s.field.name} of")
                is ModelOp ->
                    when (val t = s.target) {
                        is FieldTarget -> ownedObj(t.obj, "applies ${s.op} to")
                        is StateTarget ->
                            if (t.v !is CounterVar || t.v !in m.state) report("W6", s.pos, "${s.op} on ${t.v.name}: only module counters are op targets")
                    }
                is Exit -> (s.result as? ExitResult.Put)?.let { ownedObj(it.e, "puts", alsoOpaque = true) }
                else -> Unit
            }
            s.exprs().flatMap { it.walk() }.forEach { x ->
                if (x is FieldRead) ownedObj(x.obj, "reads ${x.field.name} of")
                if (x is Carried) report("W6", x.pos, "carried reads the map; it may appear only in the commit")
            }
        }
        // The operation section works outside every action: it does not reach into module objects.
        op?.body?.walk()?.forEach { s ->
            val reaches = s is FieldWrite || s is ModelOp && s.target is FieldTarget || s.exprs().any { e -> e.walk().any { it is FieldRead || it is Carried } }
            if (reaches) report("W6", s.pos, "the operation section of ${op.name} reaches into a module object outside its action")
        }
    }

    // ---- W7: flattened fields only as model-op targets, plain fields only as reads and writes ----

    private fun checkFlattenedFields(a: Action) {
        a.body.walk().forEach { s ->
            when (s) {
                is FieldWrite -> if (s.field.model != null) report("W7", s.pos, "flattened field ${s.field.name} is written directly")
                is ModelOp ->
                    when (val t = s.target) {
                        is FieldTarget ->
                            when {
                                t.field.model == null -> report("W7", s.pos, "${s.op} targets plain field ${t.field.name}")
                                !s.op.startsWith(t.field.model + ".") -> report("W7", s.pos, "${s.op} targets a ${t.field.model} field")
                            }
                        is StateTarget -> if (!s.op.startsWith(TrustedModels.COUNTER + ".")) report("W7", s.pos, "${s.op} targets counter ${t.v.name}")
                    }
                else -> Unit
            }
            s.exprs().flatMap { it.walk() }.filterIsInstance<FieldRead>().forEach { r ->
                if (r.field.model != null) report("W7", r.pos, "flattened field ${r.field.name} is read directly")
            }
        }
    }

    // ---- W8: a cell is one local, written only by Assign ----

    private fun checkCells(
        a: Action,
        op: Operation,
    ) {
        op.cells.groupBy { it.local.name }.filterValues { it.size > 1 }.forEach { (name, cells) ->
            report("W8", cells[1].pos, "cell $name is represented by ${cells.size} cells")
        }
        val names = op.cells.map { it.local.name }.toSet()
        (a.body.walk() + op.body.walk()).forEach { s ->
            val outs = if (s is ModelOp || s is Nondet || s is Fresh || s is RacyGet) outsOf(s) else emptyList()
            outs.filter { it.name in names }.forEach { report("W8", s.pos, "cell ${it.name} is written by ${s::class.simpleName}, not by Assign") }
        }
    }

    // ---- W9: free values have origins; sidecar names do not clash with tool names ----

    private fun checkFreeValueNames() {
        val taken =
            buildMap {
                Reserved.names.forEach { put(it, "a reserved name") }
                Reserved.tool.forEach { put(it, "a tool name") }
                m.state.forEach { put(it.name, "a state field") }
                m.classes.forEach { c -> c.fields.forEach { put(it.name, "a field of ${c.name}") } }
                m.ghost?.let { g ->
                    put(g.name, "the ghost record")
                    g.fields.keys.forEach { put(it, "a ghost field") }
                }
            }
        (m.operations.map { it.action } + m.strayActions).forEach { a ->
            (listOf(a.key) + a.free).forEach { fv ->
                val name = fv.sidecarName ?: return@forEach
                taken[name]?.let { report("W9", fv.local.pos, "free value $name clashes with $it") }
                if (fv.local.name != name) report("W9", fv.local.pos, "free value $name is emitted as ${fv.local.name}")
            }
        }
    }

    // ---- W10: one action per operation, run at most once per path; each path ends with Return or Raise; results ----

    private fun checkOperations() {
        val owners = mutableMapOf<String, String>()

        fun own(
            a: Action,
            owner: String,
        ) {
            val previous = owners.put(a.id, owner)
            if (previous != null) report("W10", a.pos, "action ${a.id} belongs to both $previous and $owner")
        }
        m.operations.forEach { own(it.action, "operation ${it.name}") }
        m.strayActions.forEach { own(it, "the stray actions") }

        m.operations.forEach { op ->
            val a = op.action
            if (op.body.walk().none { it is RunAction }) report("W10", op.pos, "operation ${op.name} never runs its action ${a.id}")
            if (m.kind == ModuleKind.OBJECT && !(op.body.size == 2 && op.body[0] is RunAction && op.body[1] is Return)) {
                report("W10", op.pos, "an object module's operation ${op.name} is `run action; return` (DESIGN-v0.md 2.4)")
            }
            section(op, op.body, ran = false)
            (a.body.walk().filter { it is RunAction || it is Return }).forEach { report("W10", it.pos, "${it::class.simpleName} inside action ${a.id}: it belongs to the operation section") }
            results(op)
        }
        m.strayActions.flatMap { it.body.walk().filter { s -> s is RunAction || s is Return }.toList() }.forEach { report("W10", it.pos, "${it::class.simpleName} inside a stray action") }
    }

    /**
     * A tail block of [op]'s section: it ends with Return, Raise or an If whose branches are tail blocks, and runs the
     * action at most once on each path ([ran]: the action ran before the block). Returns nothing; reports W10.
     */
    private fun section(
        op: Operation,
        block: List<Stmt>,
        ran: Boolean,
    ) {
        var done = ran
        val last = block.lastOrNull() ?: return report("W10", op.pos, "a path of operation ${op.name} ends without return or raise")
        for (s in block.dropLast(1)) done = inner(op, s, done)
        when (last) {
            is Return, is Raise -> Unit
            is If -> {
                section(op, last.then, done)
                section(op, last.els, done)
            }
            else -> {
                inner(op, last, done)
                report("W10", last.pos, "a path of operation ${op.name} ends after this ${last::class.simpleName} without return or raise")
            }
        }
    }

    /** A statement of [op]'s section that more statements follow; returns whether a path through it ran the action. */
    private fun inner(
        op: Operation,
        s: Stmt,
        ran: Boolean,
    ): Boolean =
        when (s) {
            is RunAction -> {
                if (ran) report("W10", s.pos, "operation ${op.name} runs its action twice on one path")
                true
            }
            is Return, is Raise -> {
                report("W10", s.pos, "${s::class.simpleName.orEmpty().lowercase()} is followed by more statements on its path")
                ran
            }
            is Exit -> {
                report("W10", s.pos, "exit outside the action of operation ${op.name}")
                ran
            }
            is If -> {
                var t = ran
                var e = ran
                s.then.forEach { t = inner(op, it, t) }
                s.els.forEach { e = inner(op, it, e) }
                t || e
            }
            else -> ran
        }

    /**
     * The values an operation returns (DESIGN-B.md 2.6 rule 2): section locals, the action's out, racy read values,
     * cells and constants; never a field, `carried`, the key or a named free value. An object module's operation
     * returns only cells, constants and its reserved val (DESIGN-v0.md W10, DESIGN-D.md 6.5).
     */
    private fun results(op: Operation) {
        val cells = op.cells.map { it.local }.toSet() + listOfNotNull(op.reserve?.local)
        val inputs = (listOf(op.action.key) + op.action.free.filter { it.sidecarName != null }).map { it.local }.toSet()
        op.body.walk().filterIsInstance<Return>().forEach { r ->
            r.e?.walk()?.forEach { x ->
                val ok =
                    when (x) {
                        is Var -> if (m.kind == ModuleKind.OBJECT) x.local in cells else x.local !in inputs
                        is FieldRead, is Carried -> false
                        else -> true
                    }
                if (!ok) {
                    val allowed = if (m.kind == ModuleKind.OBJECT) "only cells, constants and the reserved val" else "section locals, the action out, racy reads, cells and constants"
                    report("W10", x.pos, "result of ${op.name} reads ${x::class.simpleName} ${(x as? Var)?.local?.name ?: ""}; $allowed")
                }
            }
        }
    }

    // ---- W11: ghost ops read only the key, free values, the result and carried ----

    private fun checkCommit(
        a: Action,
        op: Operation?,
    ) {
        val result = op?.result
        val readable = (listOf(a.key) + a.free).map { it.local }.toSet() + listOfNotNull(result)

        fun value(e: Expr) =
            e.walk().forEach { x ->
                val ok =
                    when (x) {
                        is Var -> x.local in readable
                        is FieldRead, is TokenOf -> false
                        else -> true
                    }
                if (!ok) report("W11", x.pos, "ghost op reads ${x::class.simpleName} ${(x as? Var)?.local?.name ?: ""}")
            }

        fun token(
            e: Expr,
            what: String,
        ) {
            if (result == null || (e as? Var)?.local != result) report("W11", e.pos, "$what must be the operation result")
        }

        val witnessClass = classes[a.map.valueClass]
        a.commit.forEach { g ->
            when (g) {
                is EndIfNotCarried -> {
                    val t = g.witness.type
                    val ok = witnessClass?.fields?.contains(g.witness) == true && g.witness.model == null && t is KType.Abs && types[t.name]?.template != null
                    if (!ok) report("W11", g.pos, "witness ${g.witness.name} must be a plain token field of ${a.map.valueClass}")
                }
                is Issue -> {
                    token(g.token, "issued token")
                    val frozen = m.ghost?.fields?.filterValues { it == GhostKind.FROZEN }?.keys.orEmpty()
                    if (g.fields.keys != frozen) report("W11", g.pos, "issue sets ${g.fields.keys}; it must set exactly the frozen fields $frozen")
                    g.fields.values.forEach(::value)
                }
                is AssertFresh -> token(g.token, "fresh token")
            }
        }
    }

    // ---- W12: an action with an Unsupported node is unsupported ----

    private fun checkUnsupportedStatus(a: Action) {
        val node = a.body.walk().filterIsInstance<Unsupported>().firstOrNull()
        val status = a.status
        if (node != null && status !is Status.Unsupported) report("W12", node.pos, "action ${a.id} holds unsupported ${node.what} but is not unsupported")
        if (status is Status.Unsupported && status.diagnostics.none { it.kind == DiagnosticKind.UNSUPPORTED }) {
            report("W12", a.pos, "unsupported action ${a.id} has no UNSUPPORTED diagnostic")
        }
    }

    // ---- W13: unbounded Long operations carry an ASSUMPTION diagnostic ----

    private fun checkAssumptions(
        a: Action,
        op: Operation?,
    ) {
        val ids = m.diagnostics.filter { it.kind == DiagnosticKind.ASSUMPTION }.map { it.id }.toSet()

        fun assumption(
            id: String?,
            pos: Pos,
            what: String,
        ) {
            when {
                id == null -> report("W13", pos, "$what has no ASSUMPTION diagnostic")
                id !in ids -> report("W13", pos, "$what names $id, which is not an ASSUMPTION diagnostic")
            }
        }

        // Ghost values (the commit) are mathematical integers by definition, so W13 covers only Kotlin code.
        val exprs = a.body.walk().flatMap { it.exprs() } + op?.cells?.map { it.init }.orEmpty() + op?.body?.walk()?.flatMap { it.exprs() }.orEmpty() + listOfNotNull(a.value)
        exprs.flatMap { it.walk() }.filterIsInstance<Arith>().forEach { assumption(it.assumption, it.pos, "Long ${it.op.symbol}") }
        a.body.walk().filterIsInstance<ModelOp>().filter { it.op in TrustedModels.unboundedLongOps }.forEach {
            assumption(it.assumption, it.pos, it.op)
        }
        op?.reserve?.let { assumption(it.assumption, it.pos, "the increment of the reserve step of ${op.name}") }
    }

    // ---- W15: a Fresh is a call of a `returns fresh` function; the class it returns has no other construction ----

    private fun checkFresh() {
        val all = (m.operations.flatMap { it.body.walk().toList() + it.action.body.walk() } + m.strayActions.flatMap { it.body.walk().toList() })
        all.filterIsInstance<Fresh>().forEach { f ->
            val cls = m.freshFunctions[f.fn]
            when {
                cls == null -> report("W15", f.pos, "fresh ${f.v.name} comes from ${f.fn}, which is not a `returns fresh` trusted function")
                cls != f.cls.name -> report("W15", f.pos, "fresh ${f.v.name} is a ${f.cls.name}, but ${f.fn} returns fresh ${cls}s")
            }
        }
        val values = m.freshFunctions.values.toSet()
        all.filterIsInstance<New>().filter { it.cls.name in values }.forEach {
            report("W15", it.pos, "new ${it.v.name}: ${it.cls.name} objects come only from a `returns fresh` function (DESIGN-B.md 3.3 rule 4)")
        }
    }

    // ---- W16: a racy read is in an operation section, of a state map; its contract needs the map to be set-once ----

    private fun checkRacyGets() {
        val inActions = (m.operations.map { it.action } + m.strayActions).flatMap { it.body.walk().filterIsInstance<RacyGet>().toList() }
        inActions.forEach { report("W16", it.pos, "racy read ${it.v.name} inside an action: an action reads its map only through its entry") }
        for (op in m.operations) racyGets(op.body, ranBefore = false)
    }

    /** [ranBefore]: the action ran earlier on the path. A read after the action is arbitrary (DESIGN-B.md 2.4 rule 3). */
    private fun racyGets(
        block: List<Stmt>,
        ranBefore: Boolean,
    ): Boolean {
        var ran = ranBefore
        for (s in block) {
            when (s) {
                is RunAction -> ran = true
                is If -> ran = racyGets(s.then, ran) or racyGets(s.els, ran)
                is RacyGet -> {
                    if (s.map !in m.state) report("W16", s.pos, "racy read ${s.v.name} reads ${s.map.name}, which is not module state")
                    if (s.contract && s.map.setOnce == null) report("W16", s.pos, "racy read ${s.v.name} has the set-once contract, but map ${s.map.name} is not set-once")
                    if (s.contract && ran) report("W16", s.pos, "racy read ${s.v.name} has the set-once contract, but it follows the action on its path")
                }
                else -> Unit
            }
        }
        return ran
    }

    // ---- W17: a reserve step (DESIGN-D.md 6.3, 6.5) ----

    /**
     * An operation has at most one reserve step (one [Operation.reserve]); it is a `fun` operation of an object module
     * whose action is a compute. The step increments a module counter, and every step of the module the same one. Its
     * reserved val is the one free value of the action with origin [Reservation]: an Int, or a non-null template token.
     */
    private fun checkReserves() {
        val counters = linkedSetOf<String>()
        for (op in m.operations) {
            val reserved = op.action.free.filter { it.origin is Reservation }
            val r = op.reserve
            if (r == null) {
                reserved.forEach { report("W17", it.local.pos, "${it.local.name} of ${op.name} is a reserved val, but the operation has no reserve step") }
                continue
            }
            if (m.kind != ModuleKind.OBJECT || op.action.kind != ActionKind.COMPUTE || !op.kotlin.startsWith("fun ")) {
                report("W17", r.pos, "reserve step of ${op.name}: v1 has reserve steps only in `fun` operations of an object module whose action is a compute")
            }
            if (r.counter !in m.state) report("W17", r.pos, "reserve step of ${op.name} increments ${r.counter.name}, which is not module state")
            counters += r.counter.name
            if (reserved.map { it.local } != listOf(r.local)) report("W17", r.pos, "the reserved val ${r.local.name} of ${op.name} must be the one free value of origin reservation of its action")
            val t = r.local.type
            val ok = if (r.template == null) t == KType.IntT else t is KType.Abs && !t.nullable && types[t.name]?.template != null
            if (!ok) report("W17", r.pos, "the reserved val ${r.local.name} has type $t: a raw Long is an Int, a template a non-null template token")
        }
        m.strayActions.flatMap { it.free }.filter { it.origin is Reservation }.forEach { report("W17", it.local.pos, "stray action value ${it.local.name} is a reserved val: only an operation has a reserve step") }
        if (counters.size > 1) report("W17", m.operations.firstNotNullOf { it.reserve }.pos, "the reserve steps use the counters $counters; v1 allows one counter for all of them")
    }

    // ---- W14: every FIR node of the module file has exactly one disposition ----

    private fun checkLedger() {
        val ledger = m.ledger.groupBy { it.node }
        val translated = ((m.operations.map { it.action } + m.strayActions).flatMap { it.translated } + m.operations.flatMap { it.reserve?.translated.orEmpty() }).toSet()
        ledger.filterValues { it.size > 1 }.forEach { (node, entries) ->
            report("W14", entries[1].pos, "FIR node $node has ${entries.size} ledger entries")
        }
        (ledger.keys + translated).filter { it !in 0 until m.firNodeCount }.forEach {
            report("W14", null, "FIR node $it is out of range 0..${m.firNodeCount - 1}")
        }
        (ledger.keys intersect translated).forEach {
            report("W14", ledger.getValue(it).first().pos, "FIR node $it is both translated and in the ledger")
        }
        val missing = (0 until m.firNodeCount).filter { it !in ledger && it !in translated }
        if (missing.isNotEmpty()) report("W14", null, "${missing.size} FIR node(s) have no disposition: $missing")
    }
}
