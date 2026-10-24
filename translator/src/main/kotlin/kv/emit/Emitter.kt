package kv.emit

import kv.kvir.Action
import kv.kvir.ActionKey
import kv.kvir.Captured
import kv.kvir.ClockRead
import kv.kvir.InstanceVal
import kv.kvir.NondetCall
import kv.kvir.Origin
import kv.kvir.RacyRead
import kv.kvir.StateVar
import kv.kvir.And
import kv.kvir.Arith
import kv.kvir.Assign
import kv.kvir.AssertFresh
import kv.kvir.BoolLit
import kv.kvir.Carried
import kv.kvir.Cmp
import kv.kvir.CmpOp
import kv.kvir.CounterVar
import kv.kvir.EndIfNotCarried
import kv.kvir.Exit
import kv.kvir.ExitResult
import kv.kvir.Expr
import kv.kvir.FieldRead
import kv.kvir.FieldTarget
import kv.kvir.FieldWrite
import kv.kvir.FreeValue
import kv.kvir.GhostKind
import kv.kvir.If
import kv.kvir.IntLit
import kv.kvir.Issue
import kv.kvir.KType
import kv.kvir.Let
import kv.kvir.MapVar
import kv.kvir.ModelOp
import kv.kvir.Module
import kv.kvir.ModuleKind
import kv.kvir.ActionKind
import kv.kvir.Computed
import kv.kvir.RunAction
import kv.kvir.Fresh
import kv.kvir.RacyGet
import kv.kvir.Return
import kv.kvir.Raise
import kv.kvir.Elvis
import kv.kvir.returnedValue
import kv.kvir.canThrow
import kv.kvir.Local
import kv.kvir.New
import kv.kvir.Nondet
import kv.kvir.Not
import kv.kvir.NullLit
import kv.kvir.ObjClass
import kv.kvir.Operation
import kv.kvir.Or
import kv.kvir.Pos
import kv.kvir.StateTarget
import kv.kvir.Status
import kv.kvir.Stmt
import kv.kvir.TokenOf
import kv.kvir.Unsupported
import kv.kvir.Var
import kv.kvir.exprs
import kv.kvir.walk
import kv.sidecar.Allowance
import kv.sidecar.AllowanceItem
import kv.sidecar.EnsuresItem
import kv.sidecar.IssueItem
import kv.sidecar.OperationDecl
import kv.sidecar.ProbeAssert
import kv.sidecar.ProbeCall
import kv.sidecar.PropertyDecl
import kv.sidecar.PropertyKind
import kv.sidecar.SPos
import kv.sidecar.Sidecar
import kv.sidecar.SidecarError
import kv.sidecar.SidecarException
import kv.sidecar.TrustedDecl
import kv.sidecar.Vexpr

/**
 * KVIR + sidecar -> Viper text (DESIGN-v0.md section 5), with the position table of 5.7.
 * Only supported actions are emitted. Throws [SidecarException] for a sidecar that v0 cannot emit.
 * With [reach], the program is the branch reachability clone of 5.4 item 4 (M9): the action methods keep their
 * preconditions and bodies, lose their postconditions, and start each branch with `refute false`; init, lemmas and
 * the probe are left out.
 * The action methods whose labels are in [withoutHole] have no hole ensures (`ensures MI_INV_i(S)`) and keep every
 * other ensures: the program of the property check (pass 2, [kv.verify.verify]).
 */
fun emitViper(
    module: Module,
    sidecar: Sidecar,
    reach: Boolean = false,
    withoutHole: Set<String> = emptySet(),
): Emitted = Emitter(module, sidecar, reach, withoutHole).run()

/** What kind of value a free value is, for comments and reports (DESIGN-v0.md 2.12). */
internal fun Origin.kind(): String =
    when (this) {
        is Captured -> "captured"
        is InstanceVal -> "instance val $property"
        is ActionKey -> "action key"
        is ClockRead -> "clock"
        is NondetCall -> "trusted call $model"
        is RacyRead -> "racy read"
        is Computed -> "computed outside the action"
    }

private fun fail(
    pos: SPos,
    message: String,
): Nothing = throw SidecarException(listOf(SidecarError(pos, "emit", message)))

/** Writes text and records which source each range comes from. */
private class VprWriter {
    private val sb = StringBuilder()
    var line = 1
        private set
    var col = 1
        private set
    val ranges = mutableListOf<Mapped>()

    val text: String get() = sb.toString()

    fun put(s: String) {
        for (ch in s) {
            sb.append(ch)
            if (ch == '\n') {
                line++
                col = 1
            } else {
                col++
            }
        }
    }

    /** Writes what [body] writes and maps that range to [source]. The range must not end with a line break. */
    fun mapped(
        source: Source,
        body: () -> Unit,
    ) {
        val l = line
        val c = col
        body()
        ranges += Mapped(VprRange(l, c, line, col - 1), source)
    }

    /** One line: [indent] spaces, [code] mapped to [source], then a `//` [comment]. */
    fun line(
        code: String,
        source: Source? = null,
        indent: Int = 0,
        comment: String? = null,
    ) {
        put(" ".repeat(indent))
        if (source == null) put(code) else mapped(source) { put(code) }
        if (comment != null) put("  // $comment")
        put("\n")
    }

    fun comment(
        text: String,
        indent: Int = 0,
    ) = line("// $text", indent = indent)
}

private class Emitter(
    private val m: Module,
    private val s: Sidecar,
    private val reach: Boolean,
    private val withoutHole: Set<String>,
) {
    private val w = VprWriter()
    private val methods = mutableListOf<EmittedMethod>()
    private val warnings = mutableListOf<String>()
    private val branches = mutableListOf<BranchProbe>()
    private val anchor = s.modules.single().pos

    private val templates = m.types.filter { it.template != null }.map { it.name }.toSet()

    /**
     * An instance module (DESIGN-B.md 5.4): no ghost record, witness or HIST; `set-once` lines, fresh-object
     * bookkeeping, `exc` and the operation section. An object module keeps the v0 template (DESIGN-B.md 9.4 question 1).
     */
    private val b = m.kind == ModuleKind.INSTANCE

    // Lazy, so that an instance module never needs the v0 ghost declarations; [run] forces them in declaration order.
    private val ghost by lazy { s.ghosts.singleOrNull() ?: fail(anchor, "v0 emits a module with a ghost record (DESIGN-v0.md 3.5)") }
    private val issued by lazy {
        ghost.fields.filter { it.kind == GhostKind.SET_ONCE }.singleOrNull()?.name?.text
            ?: fail(ghost.pos, "v0 needs exactly one set-once field in the ghost record: the issued flag")
    }
    private val held by lazy {
        ghost.fields.filter { it.kind == GhostKind.ENDS }.singleOrNull()?.name?.text
            ?: fail(ghost.pos, "v0 needs exactly one ends field in the ghost record: the held flag")
    }
    private val map: MapVar by lazy { m.state.filterIsInstance<MapVar>().singleOrNull() ?: fail(anchor, "the emitter emits a module with exactly one state map") }
    private val cls: ObjClass by lazy {
        m.classes.singleOrNull { it.name == map.valueClass }?.takeIf { m.classes.size == 1 }
            ?: fail(anchor, "the emitter emits a module with exactly one class, the value class of state ${map.name}")
    }
    private val witnessDecl by lazy { s.witnesses.singleOrNull() ?: fail(anchor, "v0 emits a module with a 'witness' field (DESIGN-v0.md 3.5, END)") }
    private val witness by lazy { cls.fields.find { it.name == witnessDecl.field.text } ?: fail(witnessDecl.pos, "witness field ${witnessDecl.field.text} is not a field of ${cls.name}") }
    private val witnessSource by lazy { Source.Sidecar(witnessDecl.pos, "witness ${witnessDecl.cls.text}.${witnessDecl.field.text}") }
    private val hole by lazy { splitHole(s.holes.single()) }
    private val invariants = s.properties.filter { it.kind == PropertyKind.INVARIANT }

    /** Local names written as another name while one action method is emitted: the action's out as the result. */
    private var alias: Map<String, String> = emptyMap()
    private val keyType get() = vtype(map.key)

    /** The `set-once` declarations of the state map (DESIGN-B.md 2.4): one ensures line each, in every action. */
    private val setOnce by lazy { s.setOnce.filter { it.map.text == map.name } }

    /** True if a `returns fresh` function makes objects of [cls]: they carry the tool ghost `gFor` (DESIGN-B.md 4.1). */
    private val fresh by lazy { cls.name in m.freshFunctions.values }

    /**
     * The backed field: the frozen ghost field an issue rule sets to `carried(S, k, result)` (sidecar `gBacked`).
     * The allowance clauses of 5.2 name it. Null if no issue rule sets one.
     */
    private val backed: String? by lazy {
        m.operations.flatMap { op ->
            op.action.commit.filterIsInstance<Issue>().flatMap { issue -> issue.fields.filterValues { it is Carried && (it.token as? Var)?.local == op.result }.keys }
        }.distinct().let {
            if (it.size > 1) fail(ghost.pos, "issue rules set different backed fields $it to carried(S, k, result); v0 needs one")
            it.singleOrNull()
        }
    }

    fun run(): Emitted {
        if (b) {
            listOf(map, cls, hole)
        } else {
            listOf(ghost, issued, held, map, cls, witnessDecl, witness, witnessSource, hole, backed)
            // DESIGN-B.md 9.4 question 1: an object module keeps the v0 rules, so it has v0 actions and operations only.
            val v0 =
                m.operations.all { it.body.size == 2 && it.body[0] is RunAction && it.body[1] is Return } &&
                    (m.operations.map { it.action } + m.strayActions).all { a -> a.kind == ActionKind.COMPUTE && a.body.walk().none { it is Fresh || it is Raise } }
            if (!v0) fail(anchor, "an object module is emitted with the v0 rules: compute actions without fresh or throwing calls, and `run action; return` operations")
        }
        header()
        domains()
        fields()
        macros()
        if (!reach) init()
        m.operations.filter { it.action.status == Status.Supported }.forEach { op ->
            action(op.action, op, s.operations.single { it.name.text == op.name })
        }
        m.strayActions.filter { it.status == Status.Supported }.forEach(::stray)
        if (!reach) {
            s.properties.filter { it.kind == PropertyKind.LEMMA }.forEach(::lemma)
            s.probes.forEach(::probe)
        }
        return Emitted(w.text, w.ranges.toList(), methods.toList(), warnings.toList(), branches.toList())
    }

    // ---- types and names ----

    /** The Viper type of a sidecar type name (ghost fields, probe parameters, trusted results). */
    private fun stype(name: String): String = if (name in templates) "Int" else name

    private fun vtype(t: KType): String =
        when (t) {
            KType.IntT -> "Int"
            KType.BoolT -> "Bool"
            is KType.Abs -> stype(t.name)
            is KType.RefT -> "Ref"
        }

    private fun at(pos: Pos) = "@${pos.line}:${pos.col}"

    private val g get() = ghost.name.text

    // ---- 5.1 items 1-5 ----

    private fun header() {
        val design = if (b) "DESIGN-B.md 5.4, an instance module" else "translator v0, DESIGN-v0.md section 5"
        w.comment("Generated by kv emit ($design). Do not edit: change the Kotlin source or the sidecar.")
        w.comment("Module: ${m.id}")
        w.comment("Sidecar: ${s.file}")
        w.comment("Sources: ${s.sources.joinToString { it.path }}")
        w.comment("Assumptions are not checked by Silicon: see the 'Assumptions' part of the kv verify report.")
        if (reach) {
            w.comment("Branch reachability clone (DESIGN-v0.md 5.4 item 4): the action methods without postconditions, with")
            w.comment("`refute false` at the start of each branch. A refute that holds marks an unreachable branch (INFO only).")
        }
        if (withoutHole.isNotEmpty()) {
            w.comment("Property check (pass 2): ${withoutHole.sorted().joinToString()} without the hole ensures MI_INV_i; every other ensures stays.")
        }
        w.put("\n")
    }

    private fun domains() {
        w.comment("---- Abstract types (sidecar: type) ----")
        m.types.filter { it.template == null }.forEach { t ->
            w.line("domain ${t.name} {}", Source.Sidecar(s.types.single { it.name.text == t.name }.pos, "type ${t.name}"), comment = "opaque${t.kclass?.let { k -> " $k: == is its equals" } ?: ""}")
        }
        if (b) {
            if (templates.isNotEmpty()) nullDefine()
            w.put("\n")
            return
        }
        val index = stype(ghost.index.text)
        val gen = Source.Sidecar(ghost.pos, "ghost record $g")
        w.comment("Ghost record per ${ghost.index.text} value (sidecar: ghost record $g).")
        w.line("domain ${g.replaceFirstChar { it.uppercase() }}Rec {", gen)
        w.line("function $g(t: $index): Ref", gen, 2)
        w.line("function ${g}Of(r: Ref): $index", gen, 2)
        w.line("axiom ${g}_injective { forall t: $index :: { $g(t) } ${g}Of($g(t)) == t }", gen, 2)
        w.line("}")
        w.put("\n")
        nullDefine()
        w.put("\n")
    }

    private fun nullDefine() =
        w.line("define NULL (-1)", Source.Generated("NULL"), comment = "null of a template type such as Token?; a template value \"...\${Long}\" is its Long n >= 0")

    /** The fields an object of [cls] carries: its flattened fields and, for a class a fresh function makes, `gFor`. */
    private val objectFields: List<String> get() = cls.fields.map { it.name } + listOfNotNull("gFor".takeIf { fresh })

    private fun fields() {
        w.comment("---- Module state: ${m.id} (sidecar: state) ----")
        m.state.forEach { v ->
            val type = if (v is MapVar) "Map[$keyType, Ref]" else "Int"
            w.line("field ${v.name}: $type", Source.Kotlin(v.pos), comment = "${v.kotlin} ${at(v.pos)}")
        }
        val made = if (fresh) "a trusted fresh function returned" else "allocated in an action"
        w.line("field created: Set[Ref]", Source.Generated("created"), comment = "tool ghost: every ${cls.name} $made")
        w.put("\n")
        if (!b) {
            w.comment("---- Ghost record fields (sidecar: ghost record $g) ----")
            ghost.fields.forEach { f ->
                w.line("field ${f.name.text}: ${stype(f.type.text)}", Source.Sidecar(f.name.pos, "ghost field ${f.name.text}"), comment = f.kind.name.lowercase().replace('_', '-'))
            }
            w.put("\n")
        }
        w.comment("---- ${cls.name}, flattened (sidecar: class ${cls.name}) ----")
        cls.fields.forEach { f ->
            w.line("field ${f.name}: ${vtype(f.type)}", Source.Kotlin(f.pos), comment = "${f.kotlin}${f.model?.let { m -> " ($m)" } ?: ""}")
        }
        if (fresh) w.line("field gFor: $keyType", Source.Generated("gFor"), comment = "tool ghost: the action key of the operation that created the object")
        w.put("\n")
    }

    private fun macros() {
        val ms = map.name
        w.line("define has(S, qk) (qk in domain(S.$ms) && S.$ms[qk] != null)", Source.Generated("has"))
        if (!b) w.line("define carried(S, qk, qt) (has(S, qk) && S.$ms[qk].${witness.name} == qt)", Source.Sidecar(witnessDecl.pos, "witness ${witnessDecl.cls.text}.${witness.name}"))
        w.put("\n")
        w.comment("---- Tool-generated: permissions and ownership (DESIGN-v0.md 5.3) ----")
        if (!b) {
            val index = stype(ghost.index.text)
            w.line("define GHOST_PERMS (", Source.Generated("GHOST_PERMS"))
            w.line("forall qt: $index :: { $g(qt) }", indent = 2)
            w.line(ghost.fields.joinToString(" && ") { "acc($g(qt).${it.name.text})" }, indent = 4)
            w.line(")")
            w.put("\n")
        }
        w.line("define MI_PERMS(S) (", Source.Generated("MI_PERMS"))
        val perms = m.state.map { "acc(S.${it.name})" } + "acc(S.created)" + listOfNotNull("GHOST_PERMS".takeUnless { b })
        if (objectFields.isEmpty()) {
            w.line(perms.joinToString(" && "), indent = 2)
        } else {
            w.line(perms.joinToString(" && ") + " &&", indent = 2)
            w.line("(forall qr: Ref :: { qr in S.created } qr in S.created ==>", indent = 2)
            w.line(objectFields.joinToString(" && ") { "acc(qr.$it)" } + ")", indent = 5)
        }
        w.line(")")
        w.put("\n")
        w.comment("Ownership: every map value is an allocated ${cls.name}, and no two keys share one.")
        w.line("define MI_OWN(S) (", Source.Generated("MI_OWN"))
        w.line("(forall qk: $keyType :: { S.$ms[qk] } has(S, qk) ==> S.$ms[qk] in S.created) &&", indent = 2)
        w.line("(forall qk1: $keyType, qk2: $keyType :: { S.$ms[qk1], S.$ms[qk2] }", indent = 2)
        w.line("has(S, qk1) && has(S, qk2) && qk1 != qk2 ==> S.$ms[qk1] != S.$ms[qk2])", indent = 5)
        w.line(")")
        w.put("\n")
        setOnce.forEach { d ->
            val src = Source.Sidecar(d.pos, d.id.text)
            w.comment("---- Sidecar: set-once ${d.id.text}: ${d.map.text}. A mapped key keeps its value (every action, DESIGN-B.md 2.4) ----")
            w.line("define ${d.id.text}(S) (", src)
            // Two trigger sets: Silicon needs the domain one for a later `k in domain(S.m)` check (DESIGN-B.md 6.1 note 3).
            w.line("forall qk: $keyType :: { qk in domain(S.$ms) } { S.$ms[qk] }", src, 2)
            w.line("old(has(S, qk)) ==> has(S, qk) && S.$ms[qk] == old(S.$ms[qk])", src, 4)
            w.line(")")
            w.put("\n")
        }
        invariants.forEach { p ->
            w.comment("---- Sidecar: invariant ${p.id.text} ----")
            w.line("define ${p.id.text}(S) (")
            w.put("  ")
            sidecarExpr(p.expr, p.id.text)
            w.put("\n)\n\n")
        }
        w.comment("@HOLE-BEGIN MI_INV (sidecar hole, split on top-level &&; one ensures per part)")
        hole.forEachIndexed { i, c ->
            c.ownComment.forEach { w.comment(it) }
            w.put("define MI_INV_${i + 1}(S) (")
            sidecarExpr(c.expr, "MI_INV_${i + 1}", c.name)
            w.put(")\n")
        }
        w.comment("@HOLE-END MI_INV")
        w.line("define MI_INV(S) (${hole.indices.joinToString(" && ") { "MI_INV_${it + 1}(S)" }})", Source.Generated("MI_INV"))
        w.put("\n")
        w.line("define MI(S) (${(listOf("MI_PERMS(S)", "MI_OWN(S)") + invariants.map { "${it.id.text}(S)" } + "MI_INV(S)").joinToString(" && ")})", Source.Generated("MI"))
        w.put("\n")
        if (!b) hist()
    }

    /** HIST (DESIGN-v0.md 5.3 item 4): from the ghost field kinds, in declaration order. */
    private fun hist() {
        w.comment("---- Tool-generated from the ghost field kinds, in declaration order: append-only history ----")
        w.line("define HIST (", Source.Generated("HIST"))
        w.line("forall qt: ${stype(ghost.index.text)} :: { $g(qt).$issued } old($g(qt).$issued) ==>", indent = 2)
        val parts =
            ghost.fields.map { f ->
                val x = "$g(qt).${f.name.text}"
                when (f.kind) {
                    GhostKind.SET_ONCE -> x
                    GhostKind.FROZEN -> "$x == old($x)"
                    GhostKind.ENDS -> "(!old($x) ==> !$x)"
                }
            }
        w.line(parts.joinToString(" && "), indent = 4)
        w.line(")")
        w.put("\n")
    }

    /** Writes [e]'s text as it is in the sidecar, mapped so that a column inside it maps back. */
    private fun sidecarExpr(
        e: Vexpr,
        id: String,
        note: String? = null,
    ) = w.mapped(Source.Sidecar(e.pos, id, note, e)) { w.put(e.text) }

    /** Records the method that [body] writes. */
    private fun method(
        name: String,
        kind: MethodKind,
        label: String,
        kotlin: Pos?,
        body: () -> Unit,
    ) {
        val start = w.line
        w.mapped(Source.Generated("method $name"), body)
        methods += EmittedMethod(name, kind, label, kotlin, VprRange(start, 1, w.line - 1, Int.MAX_VALUE))
        w.put("\n\n")
    }

    // ---- 5.4 init ----

    private fun init() {
        w.comment("Empty module, as after class initialization of ${m.id}.")
        method("init", MethodKind.INIT, "init", null) {
            w.line("method init() returns (S: Ref)")
            w.line("ensures MI(S)", Source.Generated("MI"), 2)
            m.state.forEach { w.line("ensures S.${it.name} == ${initial(it)}", Source.Kotlin(it.pos), 2) }
            w.line("{")
            w.line("S := new(${(m.state.map { it.name } + "created").joinToString()})", indent = 2)
            m.state.forEach { w.line("S.${it.name} := ${initial(it)}", Source.Kotlin(it.pos), 2, "${it.kotlin} ${at(it.pos)}") }
            w.line("S.created := Set[Ref]()", indent = 2)
            if (!b) {
                w.comment("Ghost allocation: one record per ${ghost.index.text} value, none issued yet (trusted: an inhale).", 2)
                w.line("inhale GHOST_PERMS && (forall qt: ${stype(ghost.index.text)} :: { $g(qt) } !$g(qt).$issued)", Source.Generated("init inhale"), 2)
            }
            w.put("}")
        }
    }

    private fun initial(v: StateVar): String =
        when (v) {
            is MapVar -> "Map[$keyType, Ref]()"
            is CounterVar -> "${v.initial}"
        }

    // ---- 5.2 action methods ----

    private fun action(
        a: Action,
        op: Operation,
        decl: OperationDecl,
    ) {
        val named = a.free.filter { it.sidecarName != null }
        val exc = b && raises(op)
        w.comment("Action ${op.name}: ${map.kotlin}.${a.kind.method} ${at(a.pos)}, operation ${op.kotlin} ${at(op.pos)}")
        w.comment("  ${origin(a.key)}")
        a.free.forEach { w.comment("  ${origin(it)}${if (it.sidecarName == null) ", unnamed: arbitrary" else ""}") }
        method(op.name, MethodKind.ACTION, op.name, a.pos) {
            val params = (listOf("S: Ref") + (listOf(a.key) + named).map { "${it.local.name}: ${vtype(it.local.type)}" }).joinToString()
            val outs = listOfNotNull(op.result?.let { "${it.name}: ${vtype(it.type)}" }, "exc: Bool".takeIf { exc })
            val returns = if (outs.isEmpty()) "" else " returns (${outs.joinToString()})"
            w.line("method ${op.name}($params)$returns")
            requires(a, op)
            if (!reach) {
                ensuresInvariant(op.name)
                if (!b) allowances(decl, op)
                decl.items.filterIsInstance<EnsuresItem>().forEach { e ->
                    w.put("  ensures ")
                    sidecarExpr(e.expr, e.id.text)
                    w.put("\n")
                }
            }
            w.line("{")
            if (b) SectionBody(a, op, exc, op.name).run() else Body(a, op.name).run(op)
            w.put("}")
        }
    }

    /**
     * True if the method of [op] has the result `exc` (DESIGN-B.md 5.4): a path raises, or the sidecar names `exc` in
     * an ensures of the operation or in a probe call of it, so that a mutant without a throwing call still has it.
     */
    private fun raises(op: Operation): Boolean {
        val decl = s.operations.single { it.name.text == op.name }
        val ensures = decl.items.filterIsInstance<EnsuresItem>().any { EXC.containsMatchIn(it.expr.text) }
        val probed = s.probes.flatMap { it.steps }.filterIsInstance<ProbeCall>().any { it.op.text == op.name && it.outs.size == 2 }
        return op.canThrow || ensures || probed
    }

    private fun origin(fv: FreeValue): String = "${fv.local.name} <- ${fv.kotlin} ${at(fv.origin.pos)} (${fv.origin.kind()})"

    /** `requires`: MI, the trusted contracts of named Nondet values, the type facts of named values (5.2). */
    private fun requires(
        a: Action,
        op: Operation?,
    ) {
        w.line("requires MI(S)", Source.Generated("MI"), 2)
        val named = a.free.filter { it.sidecarName != null }
        val namedLocals = named.map { it.local }.toSet()
        (op?.body.orEmpty().walk() + a.body.walk()).filterIsInstance<Nondet>().filter { n -> n.outs.isNotEmpty() && n.outs.all { it in namedLocals } }.forEach { n ->
            contract(n)?.let { (t, renaming) -> t.ensures.forEach { e -> contractLine("requires", t, e, renaming, 2) } }
        }
        named.filter { (it.local.type as? KType.Abs)?.let { t -> !t.nullable && t.name in templates } == true }.forEach {
            w.line("requires ${it.local.name} != NULL", Source.Kotlin(it.local.pos), 2, "type fact: ${it.kotlin} is a non-null ${(it.local.type as KType.Abs).name}")
        }
    }

    /** The trusted function of a Nondet and the renaming of its result names to the Nondet outs; null without a contract. */
    private fun contract(n: Nondet): Pair<TrustedDecl, Map<String, String>>? {
        val candidates = s.trusted.filter { it.fn.text == n.model || it.fn.text.endsWith(".${n.model}") }
        if (candidates.size > 1) fail(candidates[1].pos, "${candidates.size} trusted functions match the call ${n.model}")
        val t = candidates.singleOrNull()?.takeIf { it.ensures.isNotEmpty() } ?: return null
        val names = (t.returns + t.ghosts).map { it.name.text }
        return t to names.zip(n.outs.map { it.name }).toMap()
    }

    private fun contractLine(
        keyword: String,
        t: TrustedDecl,
        e: Vexpr,
        renaming: Map<String, String>,
        indent: Int,
    ) {
        val renamed = rename(e.text, renaming)
        w.put(" ".repeat(indent) + "$keyword ")
        w.mapped(Source.Sidecar(e.pos, "trusted ${t.shortName}", expr = e.takeIf { renamed == e.text })) { w.put(renamed) }
        w.put("  // trusted contract of ${t.shortName}, reviewed in the sidecar\n")
    }

    /** Renames the identifiers of [text] that are keys of [renaming]; a field name after `.` stays. */
    private fun rename(
        text: String,
        renaming: Map<String, String>,
    ): String = Regex("""(?<![.\w])[A-Za-z_]\w*""").replace(text) { renaming[it.value] ?: it.value }

    /**
     * The ensures every action method has: the module invariant, one clause per part (5.7). The method [label] has no
     * hole ensures if it is in [withoutHole].
     */
    private fun ensuresInvariant(label: String) {
        w.line("ensures MI_PERMS(S)", Source.Generated("MI_PERMS"), 2)
        w.line("ensures MI_OWN(S)", Source.Generated("MI_OWN"), 2)
        // DESIGN-B.md 5.4 rule 1 (JB1): each set-once line right after MI_OWN.
        setOnce.forEach { d -> w.line("ensures ${d.id.text}(S)", Source.Sidecar(d.pos, d.id.text), 2) }
        invariants.forEach { p -> w.line("ensures ${p.id.text}(S)", Source.Sidecar(p.expr.pos, p.id.text), 2) }
        if (label in withoutHole) {
            w.comment("property check (pass 2): the hole ensures MI_INV_i are left out", 2)
        } else {
            hole.forEachIndexed { i, c -> w.line("ensures MI_INV_${i + 1}(S)", Source.Sidecar(c.expr.pos, "MI_INV_${i + 1}", c.name), 2) }
        }
        if (!b) w.line("ensures HIST", Source.Generated("HIST"), 2)
    }

    /**
     * The allowance clauses of DESIGN-v0.md 3.5 R2, compiled as in 5.2: `ends_only_if` for every action and
     * `unbacked_only_if` for an operation that issues. A clause the sidecar does not write is `false`.
     */
    private fun allowances(
        decl: OperationDecl?,
        op: Operation?,
    ) {
        val items = decl?.items?.filterIsInstance<AllowanceItem>().orEmpty()
        val ends = items.filter { it.kind == Allowance.ENDS_ONLY_IF }
        val endsHead = "forall qt: ${stype(ghost.index.text)} :: { $g(qt).$held }\n    " +
            (listOf(issued) + listOfNotNull(backed) + held).joinToString(" && ") { "old($g(qt).$it)" } + " && !$g(qt).$held ==>\n      ("
        if (ends.isEmpty()) {
            w.comment("ends_only_if (default false): this action may end no acquisition.", 2)
            w.line("ensures ${endsHead}false)", Source.Generated("ends_only_if default false"), 2)
        }
        ends.forEach { allowance(it, endsHead) }
        val issues = op?.action?.commit?.any { it is Issue } == true
        val unbacked = items.filter { it.kind == Allowance.UNBACKED_ONLY_IF }
        if (!issues) {
            unbacked.firstOrNull()?.let { fail(it.pos, "unbacked_only_if ${it.id.text}: operation ${op?.name} has no issue rule") }
            return
        }
        if (backed == null) {
            unbacked.firstOrNull()?.let { fail(it.pos, "unbacked_only_if ${it.id.text} needs a frozen ghost field that the issue rule sets to carried(S, k, ${op!!.result!!.name})") }
            return
        }
        val r = op!!.result!!.name
        val unbackedHead = "$r != NULL && !$g($r).$backed ==> ("
        if (unbacked.isEmpty()) {
            w.comment("unbacked_only_if (default false): this operation issues no unbacked token.", 2)
            w.line("ensures ${unbackedHead}false)", Source.Generated("unbacked_only_if default false"), 2)
        }
        unbacked.forEach { allowance(it, unbackedHead) }
    }

    /** One sidecar allowance clause: the whole ensures maps to the clause; its condition maps column by column. */
    private fun allowance(
        item: AllowanceItem,
        head: String,
    ) {
        w.comment("${item.kind.keyword} ${item.id.text} (sidecar, reviewed)", 2)
        w.put("  ensures ")
        w.mapped(Source.Sidecar(item.pos, "${item.kind.keyword} ${item.id.text}")) {
            w.put(head)
            sidecarExpr(item.expr, "${item.kind.keyword} ${item.id.text}")
            w.put(")")
        }
        w.put("\n")
    }

    /** A compute call that no sidecar operation names: a method with the default contract (DESIGN-v0.md 3.5 R2). */
    private fun stray(a: Action) {
        val name = "stray_" + a.id.replace(Regex("""\W"""), "_")
        val label = "stray action ${a.id}"
        w.comment("Stray action ${a.id}: ${map.kotlin}.${a.kind.method} ${at(a.pos)}; no sidecar operation names it, so it has the default contract.")
        w.comment("  ${origin(a.key)}")
        a.free.forEach { w.comment("  ${origin(it)}, unnamed: arbitrary") }
        method(name, MethodKind.STRAY, label, a.pos) {
            w.line("method $name(S: Ref, ${a.key.local.name}: ${vtype(a.key.local.type)})")
            w.line("requires MI(S)", Source.Generated("MI"), 2)
            if (!reach) {
                // DESIGN-B.md 5.4 rule 5: MI and every set-once line, and no allowance clause without a ghost record.
                ensuresInvariant(label)
                if (!b) allowances(null, null)
            }
            w.line("{")
            if (b) SectionBody(a, null, false, label).run() else Body(a, label).run(null)
            w.put("}")
        }
    }

    /** In the reachability clone, the `refute false` that opens branch [what] of the construct at [pos] of action [label]. */
    private fun branchProbe(
        label: String,
        pos: Pos,
        what: String,
        indent: Int,
    ) {
        if (!reach) return
        branches += BranchProbe(w.line, label, pos, what)
        w.line("refute false", Source.Kotlin(pos), indent, "branch reachability: $what ${at(pos)}")
    }

    /** The body of one action method: the template of DESIGN-v0.md 5.2 around the lowered statements. */
    private inner class Body(
        private val a: Action,
        /** The action's name in reports: the operation name, or `stray action <id>`. */
        private val label: String,
    ) {
        private val declared = mutableSetOf<String>()
        private val named = a.free.filter { it.sidecarName != null }.map { it.local }.toSet()

        /** The locals the action's code reads: a model operation whose out is not read is no branch (M9). */
        private val read = a.body.walk().flatMap { it.exprs() }.flatMap { it.walk() }.filterIsInstance<Var>().map { it.local }.toSet()

        private fun probe(
            pos: Pos,
            what: String,
            indent: Int,
        ) = branchProbe(label, pos, what, indent)

        fun run(op: Operation?) {
            val e = a.entry.name
            declare(e)
            w.line("var $e: Ref := has(S, ${a.key.local.name}) ? S.${map.name}[${a.key.local.name}] : null", Source.Kotlin(a.entry.pos), 2, "entry ${at(a.entry.pos)}")
            if (a.commit.any { it is EndIfNotCarried }) {
                w.line("var pre_tok: ${vtype(witness.type)} := $e != null ? $e.${witness.name} : NULL", witnessSource, 2, "ghost snapshot for END")
            }
            op?.cells?.forEach { c ->
                declare(c.local.name)
                w.line("var ${c.local.name}: ${vtype(c.local.type)} := ${expr(c.init)}", Source.Kotlin(c.pos), 2, "cell ${at(c.pos)}")
            }
            a.free.filter { it.sidecarName == null }.forEach { fv ->
                declare(fv.local.name)
                w.line("var ${fv.local.name}: ${vtype(fv.local.type)}", Source.Kotlin(fv.local.pos), 2, "unnamed free value: arbitrary")
            }
            stmts(a.body, 2)
            op?.returnedValue?.let { e -> w.line("${op.result!!.name} := ${expr(e)}", Source.Kotlin(e.pos), 2, "result ${at(e.pos)}") }
            commit(op)
        }

        /** The commit point (DESIGN-v0.md 5.2): END, then the set-once check and ISSUE. */
        private fun commit(op: Operation?) {
            if (a.commit.isEmpty()) return
            w.comment("---- commit point: effect-bound ghost rules ----", 2)
            a.commit.filterIsInstance<EndIfNotCarried>().forEach {
                w.comment("END (sidecar: witness ${witnessDecl.cls.text}.${it.witness.name}): the pre-state token of the key is no longer carried.", 2)
                w.line("if (pre_tok != NULL && !carried(S, ${a.key.local.name}, pre_tok)) {", witnessSource, 2)
                w.line("$g(pre_tok).$held := false", witnessSource, 4)
                w.line("}", indent = 2)
            }
            val issue = a.commit.filterIsInstance<Issue>().singleOrNull() ?: return
            val item = s.operations.single { it.name.text == op!!.name }.items.filterIsInstance<IssueItem>().single()
            val src = Source.Sidecar(item.pos, "issue ${op!!.name}")
            val t = expr(issue.token)
            w.comment("ISSUE (sidecar: operation ${op.name} issues its non-null result).", 2)
            w.line("if ($t != NULL) {", src, 2)
            a.commit.filterIsInstance<AssertFresh>().forEach {
                w.line("assert !$g(${expr(it.token)}).$issued", Source.Sidecar(item.pos, "issue ${op.name}: set-once $issued"), 4, "set-once $issued: a new acquisition gets a fresh token")
            }
            ghost.fields.forEach { f ->
                val value =
                    when (f.kind) {
                        GhostKind.SET_ONCE, GhostKind.ENDS -> "true"
                        GhostKind.FROZEN -> expr(issue.fields.getValue(f.name.text))
                    }
                w.line("$g($t).${f.name.text} := $value", src, 4)
            }
            w.line("}", indent = 2)
        }

        private fun declare(name: String) {
            check(declared.add(name)) { "local $name is declared twice in action ${a.id}" }
        }

        private fun stmts(
            list: List<Stmt>,
            indent: Int,
        ) = list.forEach { stmt(it, indent) }

        private fun stmt(
            s: Stmt,
            indent: Int,
        ) {
            val src = Source.Kotlin(s.pos)
            // A template token is its Long n: the template side condition n >= 0 keeps it apart from NULL (5.2).
            s.exprs().flatMap { it.walk() }.filterIsInstance<TokenOf>().forEach {
                w.line("assert 0 <= ${operand(it.n, PREC_CMP + 1)}", Source.Kotlin(it.pos), indent, "token template ${at(it.pos)}: n is not the null sentinel")
            }
            when (s) {
                is Let -> {
                    declare(s.v.name)
                    w.line("var ${s.v.name}: ${vtype(s.v.type)} := ${expr(s.e)}", src, indent)
                }
                is Assign -> w.line("${s.v.name} := ${expr(s.e)}", src, indent)
                is FieldWrite -> w.line("${operand(s.obj, PREC_ATOM)}.${s.field.name} := ${expr(s.e)}", src, indent)
                is ModelOp -> modelOp(s, indent)
                is New -> {
                    declare(s.v.name)
                    w.line("var ${s.v.name}: Ref := new(${s.cls.fields.joinToString { it.name }})", src, indent, "${s.cls.name}(...) ${at(s.pos)}")
                    w.line("S.created := S.created union Set(${s.v.name})", src, indent)
                    s.cls.fields.forEach { f -> w.line("${s.v.name}.${f.name} := ${expr(s.init.getValue(f.name))}", src, indent) }
                }
                is Nondet -> nondet(s, indent)
                is If -> {
                    w.line("if (${expr(s.cond)}) {", src, indent)
                    probe(s.pos, "if-then", indent + 2)
                    stmts(s.then, indent + 2)
                    // An if without else has no else branch to probe; its fall-through is the code after it.
                    if (s.els.isNotEmpty()) {
                        w.line("} else {", indent = indent)
                        probe(s.pos, "if-else", indent + 2)
                        stmts(s.els, indent + 2)
                    }
                    w.line("}", indent = indent)
                }
                is Exit ->
                    when (val r = s.result) {
                        ExitResult.Keep -> w.comment("exit ${at(s.pos)}: mapping unchanged", indent)
                        ExitResult.Remove -> w.line("S.${map.name} := S.${map.name}[${a.key.local.name} := null]", src, indent, "exit ${at(s.pos)}: remove")
                        is ExitResult.Put -> w.line("S.${map.name} := S.${map.name}[${a.key.local.name} := ${expr(r.e)}]", src, indent, "exit ${at(s.pos)}: put")
                    }
                is Unsupported -> error("unsupported statement in supported action ${a.id}")
                is RunAction, is Fresh, is RacyGet, is Return, is Raise -> error("B statement ${s::class.simpleName} at ${s.pos}: emitted from DESIGN-B.md 8, B3 on")
            }
        }

        /** A trusted-model operation: the rows of the KVIR -> Viper table of DESIGN-v0.md 5.2. */
        private fun modelOp(
            s: ModelOp,
            indent: Int,
        ) {
            val src = Source.Kotlin(s.pos)
            val target =
                when (val t = s.target) {
                    is FieldTarget -> "${operand(t.obj, PREC_ATOM)}.${t.field.name}"
                    is StateTarget -> "S.${t.v.name}"
                }

            fun out(value: String) {
                val o = s.out ?: return
                if (o.name in declared) {
                    w.line("${o.name} := $value", src, indent, s.op)
                } else {
                    declare(o.name)
                    w.line("var ${o.name}: ${vtype(o.type)} := $value", src, indent, s.op)
                }
            }

            /**
             * `out := guard` and `if (out) { update }`; without an out, `if (guard) { update }`. In the reachability
             * clone, an operation whose result the code reads is a branch: it succeeds or fails.
             */
            fun guarded(
                guard: String,
                update: String,
            ) {
                val o = s.out
                out(guard)
                if (!reach || o == null || o !in read) return w.line("if (${o?.name ?: guard}) { $update }", src, indent)
                val name = s.op.substringAfterLast('.')
                w.line("if (${o.name}) {", src, indent)
                probe(s.pos, "$name succeeds", indent + 2)
                w.line(update, src, indent + 2)
                w.line("} else {", indent = indent)
                probe(s.pos, "$name fails", indent + 2)
                w.line("}", indent = indent)
            }
            when (s.op) {
                "jdk.AtomicBoolean.compareAndSet" -> guarded("$target == ${expr(s.args[0])}", "$target := ${expr(s.args[1])}")
                "jdk.AtomicBoolean.set" -> w.line("$target := ${expr(s.args[0])}", src, indent, s.op)
                "jdk.AtomicBoolean.get" -> out(target)
                "jdk.Semaphore.tryAcquire" -> guarded("$target > 0", "$target := $target - 1")
                "jdk.Semaphore.release" -> w.line("$target := $target + 1", src, indent, s.op)
                "jdk.AtomicLong.incrementAndGet" -> {
                    w.line("$target := $target + 1", src, indent, s.op)
                    out(target)
                }
                "jdk.AtomicLong.get" -> out(target)
                else -> error("the emitter has no Viper form for model operation ${s.op} at ${s.pos}")
            }
        }

        private fun nondet(
            s: Nondet,
            indent: Int,
        ) = nondet(s, indent, named, ::declare)
    }

    /** A named Nondet value is a method parameter; an unnamed one is an arbitrary local with its contract inhaled. */
    private fun nondet(
        s: Nondet,
        indent: Int,
        named: Set<Local>,
        declare: (String) -> Unit,
    ) {
        if (s.outs.all { it in named }) return w.comment("${s.model} ${at(s.pos)}: named free value(s) ${s.outs.joinToString { it.name }}", indent)
        s.outs.forEach {
            declare(it.name)
            w.line("var ${it.name}: ${vtype(it.type)}", Source.Kotlin(s.pos), indent, "${s.model} ${at(s.pos)}: arbitrary")
        }
        contract(s)?.let { (t, renaming) -> t.ensures.forEach { e -> contractLine("inhale", t, e, renaming, indent) } }
    }

    /**
     * The body of an instance module's action method (DESIGN-B.md 5.4): `exc := false`, the action's unnamed free
     * values, then the operation section with the action written out at its RunAction (the rows of 2.3). [op] is null
     * for a stray action, whose body is the action alone. A raise sets `exc`; inside the action it skips the map
     * write, and the section code after the action runs only if `!exc` (Viper has no early exit).
     */
    private inner class SectionBody(
        private val a: Action,
        private val op: Operation?,
        private val exc: Boolean,
        private val label: String,
    ) {
        private val declared = mutableSetOf<String>()
        private val named = a.free.filter { it.sidecarName != null }.map { it.local }.toSet()
        private val res = op?.result?.name

        /** compute and computeIfAbsent have their own out local; for the other kinds the out is the current value `cur`. */
        private val ownOut = a.out?.takeIf { it != a.entry }

        /**
         * DESIGN-B.md 9.4 question 3: an action value the operation returns directly (`return out`) is the result
         * variable, written at each end of the action; no out local.
         */
        private val outIsResult = res != null && ownOut != null && op!!.body.walk().any { it is Return && (it.e as? Var)?.local == ownOut }

        /** The variable that receives the action's value at its ends: the result, an out local, or none. */
        private val outName: String? = if (outIsResult) res else ownOut?.name

        private val actionRaises = a.body.walk().any { it is Raise }

        private fun declare(name: String) {
            check(declared.add(name)) { "local $name is declared twice in $label" }
        }

        fun run() {
            alias = if (outIsResult) mapOf(ownOut!!.name to res!!) else emptyMap()
            if (exc) w.line("exc := false", Source.Generated("exc"), 2)
            op?.cells?.forEach { c ->
                declare(c.local.name)
                w.line("var ${c.local.name}: ${vtype(c.local.type)} := ${expr(c.init)}", Source.Kotlin(c.pos), 2, "cell ${at(c.pos)}")
            }
            a.free.filter { it.sidecarName == null }.forEach { fv ->
                declare(fv.local.name)
                w.line("var ${fv.local.name}: ${vtype(fv.local.type)}", Source.Kotlin(fv.local.pos), 2, "unnamed free value: arbitrary")
                // DESIGN-B.md 5.3 rule 5: a Ref free value of a non-null Kotlin type is not null.
                if ((fv.local.type as? KType.RefT)?.nullable == false) {
                    w.line("inhale ${fv.local.name} != null", Source.Kotlin(fv.local.pos), 2, "type fact: ${fv.kotlin} is not nullable")
                }
            }
            if (op == null) runAction(2) else stmts(op.body, 2)
            alias = emptyMap()
        }

        /** True if [s] writes Viper code: a return of the value the result already holds, and a named Nondet, do not. */
        private fun emits(s: Stmt): Boolean =
            when (s) {
                is Return -> s.e != null && res != null && !(outIsResult && (s.e as? Var)?.local == ownOut)
                is Nondet -> !s.outs.all { it in named }
                else -> true
            }

        private fun stmts(
            list: List<Stmt>,
            indent: Int,
        ) {
            for ((i, s) in list.withIndex()) {
                if (s !is RunAction) {
                    stmt(s, indent)
                    continue
                }
                runAction(indent)
                val rest = list.drop(i + 1)
                if (actionRaises && rest.any(::emits)) afterRaisingAction(rest, indent) else stmts(rest, indent)
                return
            }
        }

        /**
         * The section code after an action that can raise runs only if it did not: `if (!exc && c) { ... }` for a
         * single `if` without an else that writes code (DESIGN-B.md 7.2 R1: `!!` on the action's value), else
         * `if (!exc) { ... }`.
         */
        private fun afterRaisingAction(
            rest: List<Stmt>,
            indent: Int,
        ) {
            val single = rest.singleOrNull() as? If
            if (single != null && single.els.none(::emits)) {
                w.line("if (!exc && ${operand(single.cond, PREC_AND)}) {", Source.Kotlin(single.pos), indent)
                branchProbe(label, single.pos, "if-then", indent + 2)
                stmts(single.then, indent + 2)
            } else {
                w.line("if (!exc) {", Source.Generated("exc"), indent, "the action did not raise")
                stmts(rest, indent + 2)
            }
            w.line("}", indent = indent)
        }

        private fun stmt(
            s: Stmt,
            indent: Int,
        ) {
            val src = Source.Kotlin(s.pos)
            when (s) {
                is Let -> {
                    declare(s.v.name)
                    w.line("var ${s.v.name}: ${vtype(s.v.type)} := ${expr(s.e)}", src, indent)
                }
                is Assign -> w.line("${s.v.name} := ${expr(s.e)}", src, indent)
                is FieldWrite -> w.line("${operand(s.obj, PREC_ATOM)}.${s.field.name} := ${expr(s.e)}", src, indent)
                is Nondet -> nondet(s, indent, named, ::declare)
                is If -> {
                    w.line("if (${expr(s.cond)}) {", src, indent)
                    branchProbe(label, s.pos, "if-then", indent + 2)
                    stmts(s.then, indent + 2)
                    if (s.els.any(::emits)) {
                        w.line("} else {", indent = indent)
                        branchProbe(label, s.pos, "if-else", indent + 2)
                        stmts(s.els, indent + 2)
                    }
                    w.line("}", indent = indent)
                }
                is Exit -> exit(s, indent)
                is Fresh -> {
                    // DESIGN-B.md 3.2, 4.1: a new object, recorded in S.created with the action key in gFor.
                    declare(s.v.name)
                    w.line("var ${s.v.name}: Ref := new(${objectFields.joinToString()})", src, indent, "${s.fn}(...) ${at(s.pos)}: a fresh object")
                    w.line("S.created := S.created union Set(${s.v.name})", src, indent)
                    w.line("${s.v.name}.gFor := ${a.key.local.name}", src, indent)
                }
                is RacyGet -> racyGet(s, indent)
                is Return -> if (emits(s)) w.line("$res := ${expr(s.e!!)}", src, indent, "result ${at(s.pos)}")
                is Raise -> if (exc) w.line("exc := true", src, indent, "raise ${at(s.pos)}") else w.comment("raise ${at(s.pos)}: the stray action ends", indent)
                is New -> fail(anchor, "an instance module creates objects only with a `returns fresh` function (DESIGN-B.md 3.3 rule 4); new ${s.cls.name} at ${at(s.pos)}")
                is ModelOp -> fail(anchor, "the instance-module emitter has no Viper form for model operation ${s.op} at ${at(s.pos)}")
                is RunAction, is Unsupported -> error("${s::class.simpleName} at ${s.pos} in ${a.id}")
            }
        }

        /** DESIGN-B.md 2.4: a racy get of a set-once map is null or the current value; containsKey true means mapped. */
        private fun racyGet(
            s: RacyGet,
            indent: Int,
        ) {
            declare(s.v.name)
            val read = if (s.contains) "containsKey" else "get"
            w.line("var ${s.v.name}: ${vtype(s.v.type)}", Source.Kotlin(s.pos), indent, "${s.map.kotlin}.$read ${at(s.pos)}: racy read")
            if (!s.contract) return
            val k = expr(s.key)
            val fact = if (s.contains) "${s.v.name} ==> has(S, $k)" else "${s.v.name} == null || (has(S, $k) && S.${s.map.name}[$k] == ${s.v.name})"
            w.line("inhale $fact", Source.Kotlin(s.pos), indent, "set-once ${s.map.setOnce}: the read is null or the current value")
        }

        /** The action at its RunAction (DESIGN-B.md 2.3 Viper table). */
        private fun runAction(indent: Int) {
            val src = Source.Kotlin(a.pos)
            val k = a.key.local.name
            val ms = map.name
            val e = a.entry.name

            fun current() {
                declare(e)
                w.line("var $e: Ref := has(S, $k) ? S.$ms[$k] : null", Source.Kotlin(a.entry.pos), indent, "${a.kind.method} ${at(a.pos)}: the current value of $k")
            }
            if (outName != null && outName != res) {
                declare(outName)
                w.line("var $outName: Ref", src, indent, "the value of ${a.kind.method} ${at(a.pos)}")
            }
            when (a.kind) {
                ActionKind.COMPUTE -> {
                    current()
                    stmts(a.body, indent)
                }
                ActionKind.COMPUTE_IF_ABSENT -> {
                    current()
                    w.line("if ($e == null) {", src, indent)
                    stmts(a.body, indent + 2)
                    if (outName != null) {
                        w.line("} else {", indent = indent)
                        w.line("$outName := $e", src, indent + 2, "present: the function is not invoked")
                    }
                    w.line("}", indent = indent)
                }
                ActionKind.PUT_IF_ABSENT -> {
                    current()
                    w.line("if ($e == null) { S.$ms := S.$ms[$k := ${expr(a.value!!)}] }", src, indent, "putIfAbsent ${at(a.pos)}")
                }
                ActionKind.PUT, ActionKind.REMOVE -> {
                    // The current value is the call's result: written only if the code reads it.
                    if (a.out != null) current()
                    val v = if (a.kind == ActionKind.PUT) expr(a.value!!) else "null"
                    w.line("S.$ms := S.$ms[$k := $v]", src, indent, "${a.kind.method} ${at(a.pos)}")
                }
            }
        }

        /** An exit of a compute or computeIfAbsent lambda: the map write, then the action's value into [outName]. */
        private fun exit(
            s: Exit,
            indent: Int,
        ) {
            val src = Source.Kotlin(s.pos)
            val k = a.key.local.name
            val ms = map.name
            val value =
                when (val r = s.result) {
                    // computeIfAbsent: a null result maps nothing and is the call's value; compute: the value parameter stays.
                    ExitResult.Keep -> {
                        w.comment("exit ${at(s.pos)}: mapping unchanged", indent)
                        if (a.kind == ActionKind.COMPUTE_IF_ABSENT) "null" else a.entry.name
                    }
                    ExitResult.Remove -> {
                        w.line("S.$ms := S.$ms[$k := null]", src, indent, "exit ${at(s.pos)}: remove")
                        "null"
                    }
                    is ExitResult.Put -> {
                        w.line("S.$ms := S.$ms[$k := ${expr(r.e)}]", src, indent, "exit ${at(s.pos)}: put")
                        expr(r.e)
                    }
                }
            outName?.let { w.line("$it := $value", src, indent) }
        }
    }

    // ---- 5.4 lemma and probe ----

    private fun lemma(p: PropertyDecl) {
        w.comment("Sidecar: lemma ${p.id.text}, proven from MI.")
        method("lemma_${p.id.text}", MethodKind.LEMMA, "lemma ${p.id.text}", null) {
            w.line("method lemma_${p.id.text}(S: Ref)")
            w.line("requires MI(S)", Source.Generated("MI"), 2)
            w.line("ensures MI(S)", Source.Generated("MI"), 2)
            w.put("  ensures ")
            sidecarExpr(p.expr, p.id.text)
            w.put("\n{}")
        }
    }

    private fun probe(p: kv.sidecar.ProbeDecl) {
        val ops = m.operations.filter { it.action.status == Status.Supported }.associateBy { it.name }
        val missing = p.steps.filterIsInstance<ProbeCall>().map { it.op.text }.filter { it !in ops }.distinct()
        if (missing.isNotEmpty()) {
            warnings += "probe not emitted: it calls ${missing.joinToString()}, which ${if (missing.size == 1) "is" else "are"} not emitted"
            return
        }
        p.steps.filterIsInstance<ProbeCall>().map { it.op.text }.distinct().forEach { name ->
            val decl = s.operations.single { it.name.text == name }
            if (decl.items.filterIsInstance<EnsuresItem>().none { it.id.text.startsWith("progress_") }) {
                warnings += "probe uses operation $name, which has no progress_* ensures: the probe may be vacuous (DESIGN-v0.md 5.4)"
            }
        }
        w.comment("Sidecar: probe scenario. MI is satisfiable from init, and the actions are reachable and not inert.")
        w.comment("init and probe do not check inductiveness; the action methods do.")
        method("probe", MethodKind.PROBE, "probe", null) {
            w.line("method probe(${p.params.joinToString { "${it.name.text}: ${stype(it.type.text)}" }})", Source.Sidecar(p.pos, "probe"))
            p.requires?.let {
                w.put("  requires ")
                sidecarExpr(it, "probe requires")
                w.put("\n")
            }
            w.line("{")
            w.line("var S: Ref", indent = 2)
            w.line("S := init()", Source.Sidecar(p.pos, "probe"), 2)
            p.steps.forEach { step ->
                when (step) {
                    is ProbeCall -> {
                        val op = ops.getValue(step.op.text)
                        // The method's results in order: the sidecar result, then `exc` (DESIGN-B.md 5.1, instance modules).
                        val types = listOfNotNull(op.result?.let { vtype(it.type) }, "Bool".takeIf { b && raises(op) })
                        if (step.outs.size != types.size) {
                            val want = listOfNotNull(op.result?.let { "the result" }, "exc".takeIf { types.size > (if (op.result != null) 1 else 0) })
                            fail(step.pos, "operation ${op.name} returns ${want.joinToString(" and ").ifEmpty { "nothing" }}; the probe call names ${step.outs.size} out(s)")
                        }
                        val byName = step.args.filter { it.name != null }.associateBy { it.name!!.text }
                        val args = listOf(step.args.first().value.text) + op.action.free.filter { it.sidecarName != null }.map { byName.getValue(it.sidecarName!!).value.text }
                        step.outs.zip(types).forEach { (out, type) -> w.line("var ${out.text}: $type", indent = 2) }
                        w.line("${step.outs.joinToString { it.text }} := ${op.name}(S, ${args.joinToString()})", Source.Sidecar(step.pos, "probe call ${op.name}"), 2)
                    }
                    is ProbeAssert -> {
                        w.put("  assert ")
                        sidecarExpr(step.expr, "probe assert")
                        w.put("\n")
                    }
                }
            }
            w.put("}")
        }
    }

    // ---- expressions ----

    private fun prec(e: Expr): Int =
        when (e) {
            is Elvis -> 0
            is Or -> 1
            is And -> PREC_AND
            is Cmp -> if (e.op == CmpOp.EQ || e.op == CmpOp.NE) PREC_EQ else PREC_CMP
            is Arith -> PREC_ARITH
            is Not -> PREC_NOT
            is TokenOf -> prec(e.n)
            else -> PREC_ATOM
        }

    /** [e] in Viper syntax, with parentheses only where Viper's precedence needs them. */
    private fun expr(e: Expr): String =
        when (e) {
            is IntLit -> if (e.value < 0) "(${e.value})" else "${e.value}"
            is BoolLit -> "${e.value}"
            is NullLit -> if (e.type is KType.Abs) "NULL" else "null"
            is Var -> alias[e.local.name] ?: e.local.name
            is FieldRead -> "${operand(e.obj, PREC_ATOM)}.${e.field.name}"
            is Arith -> "${operand(e.a, PREC_ARITH)} ${e.op.symbol} ${operand(e.b, PREC_ARITH + 1)}"
            is Cmp -> "${operand(e.a, prec(e) + 1)} ${e.op.symbol} ${operand(e.b, prec(e) + 1)}"
            is Not -> "!${operand(e.a, PREC_NOT)}"
            is And -> "${operand(e.a, 2)} && ${operand(e.b, 2)}"
            is Or -> "${operand(e.a, 1)} || ${operand(e.b, 1)}"
            // The template side condition is asserted before the statement; the token is its Long n.
            is TokenOf -> expr(e.n)
            is Carried -> "carried(S, ${expr(e.key)}, ${expr(e.token)})"
            // `a ?: b` of the operation section (DESIGN-B.md 2.6), as mutants.py writes it: `a != null ? a : b`.
            is Elvis -> "${operand(e.a, PREC_EQ + 1)} != null ? ${operand(e.a, 1)} : ${operand(e.b, 0)}"
        }

    private fun operand(
        e: Expr,
        min: Int,
    ): String = if (prec(e) < min) "(${expr(e)})" else expr(e)

    private companion object {
        /** The name `exc` in a sidecar expression (DESIGN-B.md 5.1). */
        val EXC = Regex("""(?<![.\w])exc(?!\w)""")

        const val PREC_AND = 2
        const val PREC_EQ = 3
        const val PREC_CMP = 4
        const val PREC_ARITH = 5
        const val PREC_NOT = 6
        const val PREC_ATOM = 7
    }
}
