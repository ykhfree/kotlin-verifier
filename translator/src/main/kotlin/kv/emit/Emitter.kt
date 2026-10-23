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
 */
fun emitViper(
    module: Module,
    sidecar: Sidecar,
    reach: Boolean = false,
): Emitted = Emitter(module, sidecar, reach).run()

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
) {
    private val w = VprWriter()
    private val methods = mutableListOf<EmittedMethod>()
    private val warnings = mutableListOf<String>()
    private val branches = mutableListOf<BranchProbe>()
    private val anchor = s.modules.single().pos

    private val templates = m.types.filter { it.template != null }.map { it.name }.toSet()
    private val ghost = s.ghosts.singleOrNull() ?: fail(anchor, "v0 emits a module with a ghost record (DESIGN-v0.md 3.5)")
    private val issued =
        ghost.fields.filter { it.kind == GhostKind.SET_ONCE }.singleOrNull()?.name?.text
            ?: fail(ghost.pos, "v0 needs exactly one set-once field in the ghost record: the issued flag")
    private val held =
        ghost.fields.filter { it.kind == GhostKind.ENDS }.singleOrNull()?.name?.text
            ?: fail(ghost.pos, "v0 needs exactly one ends field in the ghost record: the held flag")
    private val map: MapVar = m.state.filterIsInstance<MapVar>().singleOrNull() ?: fail(anchor, "v0 emits a module with exactly one state map")
    private val cls: ObjClass =
        m.classes.singleOrNull { it.name == map.valueClass }?.takeIf { m.classes.size == 1 }
            ?: fail(anchor, "v0 emits a module with exactly one class, the value class of state ${map.name}")
    private val witnessDecl = s.witnesses.singleOrNull() ?: fail(anchor, "v0 emits a module with a 'witness' field (DESIGN-v0.md 3.5, END)")
    private val witness = cls.fields.find { it.name == witnessDecl.field.text } ?: fail(witnessDecl.pos, "witness field ${witnessDecl.field.text} is not a field of ${cls.name}")
    private val witnessSource = Source.Sidecar(witnessDecl.pos, "witness ${witnessDecl.cls.text}.${witnessDecl.field.text}")
    private val hole = splitHole(s.holes.single())
    private val invariants = s.properties.filter { it.kind == PropertyKind.INVARIANT }
    private val keyType get() = vtype(map.key)

    /**
     * The backed field: the frozen ghost field an issue rule sets to `carried(S, k, result)` (sidecar `gBacked`).
     * The allowance clauses of 5.2 name it. Null if no issue rule sets one.
     */
    private val backed: String? =
        m.operations.flatMap { op ->
            op.action.commit.filterIsInstance<Issue>().flatMap { issue -> issue.fields.filterValues { it is Carried && (it.token as? Var)?.local == op.result }.keys }
        }.distinct().let {
            if (it.size > 1) fail(ghost.pos, "issue rules set different backed fields $it to carried(S, k, result); v0 needs one")
            it.singleOrNull()
        }

    fun run(): Emitted {
        // DESIGN-B.md 8: B3 emits instance modules, the map calls other than compute and operation sections.
        val b =
            m.kind == ModuleKind.INSTANCE ||
                m.operations.any { !(it.body.size == 2 && it.body[0] is RunAction && it.body[1] is Return) } ||
                (m.operations.map { it.action } + m.strayActions).any { a -> a.kind != ActionKind.COMPUTE || a.body.walk().any { it is Fresh || it is Raise } }
        if (b) fail(anchor, "the emitter handles DESIGN-B.md modules from milestone B3 on")
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
        w.comment("Generated by kv emit (translator v0, DESIGN-v0.md section 5). Do not edit: change the Kotlin source or the sidecar.")
        w.comment("Module: ${m.id}")
        w.comment("Sidecar: ${s.file}")
        w.comment("Sources: ${s.sources.joinToString { it.path }}")
        w.comment("Assumptions are not checked by Silicon: see the 'Assumptions' part of the kv verify report.")
        if (reach) {
            w.comment("Branch reachability clone (DESIGN-v0.md 5.4 item 4): the action methods without postconditions, with")
            w.comment("`refute false` at the start of each branch. A refute that holds marks an unreachable branch (INFO only).")
        }
        w.put("\n")
    }

    private fun domains() {
        w.comment("---- Abstract types (sidecar: type) ----")
        m.types.filter { it.template == null }.forEach { t ->
            w.line("domain ${t.name} {}", Source.Sidecar(s.types.single { it.name.text == t.name }.pos, "type ${t.name}"), comment = "opaque")
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
        w.line("define NULL (-1)", Source.Generated("NULL"), comment = "null of a template type such as Token?; a template value \"...\${Long}\" is its Long n >= 0")
        w.put("\n")
    }

    private fun fields() {
        w.comment("---- Module state: ${m.id} (sidecar: state) ----")
        m.state.forEach { v ->
            val type = if (v is MapVar) "Map[$keyType, Ref]" else "Int"
            w.line("field ${v.name}: $type", Source.Kotlin(v.pos), comment = "${v.kotlin} ${at(v.pos)}")
        }
        w.line("field created: Set[Ref]", Source.Generated("created"), comment = "tool ghost: every ${cls.name} allocated in an action")
        w.put("\n")
        w.comment("---- Ghost record fields (sidecar: ghost record $g) ----")
        ghost.fields.forEach { f ->
            w.line("field ${f.name.text}: ${stype(f.type.text)}", Source.Sidecar(f.name.pos, "ghost field ${f.name.text}"), comment = f.kind.name.lowercase().replace('_', '-'))
        }
        w.put("\n")
        w.comment("---- ${cls.name}, flattened (sidecar: class ${cls.name}) ----")
        cls.fields.forEach { f ->
            w.line("field ${f.name}: ${vtype(f.type)}", Source.Kotlin(f.pos), comment = "${f.kotlin}${f.model?.let { m -> " ($m)" } ?: ""}")
        }
        w.put("\n")
    }

    private fun macros() {
        val ms = map.name
        w.line("define has(S, qk) (qk in domain(S.$ms) && S.$ms[qk] != null)", Source.Generated("has"))
        w.line("define carried(S, qk, qt) (has(S, qk) && S.$ms[qk].${witness.name} == qt)", Source.Sidecar(witnessDecl.pos, "witness ${witnessDecl.cls.text}.${witness.name}"))
        w.put("\n")
        w.comment("---- Tool-generated: permissions and ownership (DESIGN-v0.md 5.3) ----")
        val index = stype(ghost.index.text)
        w.line("define GHOST_PERMS (", Source.Generated("GHOST_PERMS"))
        w.line("forall qt: $index :: { $g(qt) }", indent = 2)
        w.line(ghost.fields.joinToString(" && ") { "acc($g(qt).${it.name.text})" }, indent = 4)
        w.line(")")
        w.put("\n")
        w.line("define MI_PERMS(S) (", Source.Generated("MI_PERMS"))
        w.line((m.state.map { "acc(S.${it.name})" } + "acc(S.created)" + "GHOST_PERMS").joinToString(" && ") + " &&", indent = 2)
        w.line("(forall qr: Ref :: { qr in S.created } qr in S.created ==>", indent = 2)
        w.line(cls.fields.joinToString(" && ") { "acc(qr.${it.name})" } + ")", indent = 5)
        w.line(")")
        w.put("\n")
        w.comment("Ownership: every map value is an allocated ${cls.name}, and no two keys share one.")
        w.line("define MI_OWN(S) (", Source.Generated("MI_OWN"))
        w.line("(forall qk: $keyType :: { S.$ms[qk] } has(S, qk) ==> S.$ms[qk] in S.created) &&", indent = 2)
        w.line("(forall qk1: $keyType, qk2: $keyType :: { S.$ms[qk1], S.$ms[qk2] }", indent = 2)
        w.line("has(S, qk1) && has(S, qk2) && qk1 != qk2 ==> S.$ms[qk1] != S.$ms[qk2])", indent = 5)
        w.line(")")
        w.put("\n")
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
        hist()
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
            w.comment("Ghost allocation: one record per ${ghost.index.text} value, none issued yet (trusted: an inhale).", 2)
            w.line("inhale GHOST_PERMS && (forall qt: ${stype(ghost.index.text)} :: { $g(qt) } !$g(qt).$issued)", Source.Generated("init inhale"), 2)
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
        w.comment("Action ${op.name}: ${map.kotlin}.compute ${at(a.pos)}, operation ${op.kotlin} ${at(op.pos)}")
        w.comment("  ${origin(a.key)}")
        a.free.forEach { w.comment("  ${origin(it)}${if (it.sidecarName == null) ", unnamed: arbitrary" else ""}") }
        method(op.name, MethodKind.ACTION, op.name, a.pos) {
            val params = (listOf("S: Ref") + (listOf(a.key) + named).map { "${it.local.name}: ${vtype(it.local.type)}" }).joinToString()
            val returns = op.result?.let { " returns (${it.name}: ${vtype(it.type)})" } ?: ""
            w.line("method ${op.name}($params)$returns")
            requires(a)
            if (!reach) {
                ensuresInvariant()
                allowances(decl, op)
                decl.items.filterIsInstance<EnsuresItem>().forEach { e ->
                    w.put("  ensures ")
                    sidecarExpr(e.expr, e.id.text)
                    w.put("\n")
                }
            }
            w.line("{")
            Body(a, op.name).run(op)
            w.put("}")
        }
    }

    private fun origin(fv: FreeValue): String = "${fv.local.name} <- ${fv.kotlin} ${at(fv.origin.pos)} (${fv.origin.kind()})"

    /** `requires`: MI, the trusted contracts of named Nondet values, the type facts of named values (5.2). */
    private fun requires(a: Action) {
        w.line("requires MI(S)", Source.Generated("MI"), 2)
        val named = a.free.filter { it.sidecarName != null }
        val namedLocals = named.map { it.local }.toSet()
        a.body.walk().filterIsInstance<Nondet>().filter { n -> n.outs.isNotEmpty() && n.outs.all { it in namedLocals } }.forEach { n ->
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

    /** The ensures every action method has: the module invariant, one clause per part (5.7). */
    private fun ensuresInvariant() {
        w.line("ensures MI_PERMS(S)", Source.Generated("MI_PERMS"), 2)
        w.line("ensures MI_OWN(S)", Source.Generated("MI_OWN"), 2)
        invariants.forEach { p -> w.line("ensures ${p.id.text}(S)", Source.Sidecar(p.expr.pos, p.id.text), 2) }
        hole.forEachIndexed { i, c -> w.line("ensures MI_INV_${i + 1}(S)", Source.Sidecar(c.expr.pos, "MI_INV_${i + 1}", c.name), 2) }
        w.line("ensures HIST", Source.Generated("HIST"), 2)
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
        w.comment("Stray action ${a.id}: ${map.kotlin}.compute ${at(a.pos)}; no sidecar operation names it, so it has the default contract.")
        w.comment("  ${origin(a.key)}")
        a.free.forEach { w.comment("  ${origin(it)}, unnamed: arbitrary") }
        method(name, MethodKind.STRAY, "stray action ${a.id}", a.pos) {
            w.line("method $name(S: Ref, ${a.key.local.name}: ${vtype(a.key.local.type)})")
            w.line("requires MI(S)", Source.Generated("MI"), 2)
            if (!reach) {
                ensuresInvariant()
                allowances(null, null)
            }
            w.line("{")
            Body(a, "stray action ${a.id}").run(null)
            w.put("}")
        }
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

        /** In the reachability clone, the `refute false` that opens branch [what] of the construct at [pos]. */
        private fun probe(
            pos: Pos,
            what: String,
            indent: Int,
        ) {
            if (!reach) return
            branches += BranchProbe(w.line, label, pos, what)
            w.line("refute false", Source.Kotlin(pos), indent, "branch reachability: $what ${at(pos)}")
        }

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

        /** A named Nondet value is a method parameter; an unnamed one is an arbitrary local with its contract inhaled. */
        private fun nondet(
            s: Nondet,
            indent: Int,
        ) {
            if (s.outs.all { it in named }) return w.comment("${s.model} ${at(s.pos)}: named free value(s) ${s.outs.joinToString { it.name }}", indent)
            s.outs.forEach {
                declare(it.name)
                w.line("var ${it.name}: ${vtype(it.type)}", Source.Kotlin(s.pos), indent, "${s.model} ${at(s.pos)}: arbitrary")
            }
            contract(s)?.let { (t, renaming) -> t.ensures.forEach { e -> contractLine("inhale", t, e, renaming, indent) } }
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
                        // A second out (exc, DESIGN-B.md 5.1) comes with the emitter of operation sections (B3).
                        val out = step.outs.singleOrNull() ?: fail(step.outs[1].pos, "the emitter takes one out per probe call; exc outs come with DESIGN-B.md 8, B3")
                        val result = op.result ?: fail(step.op.pos, "operation ${op.name} has no result to assign to ${out.text}")
                        val byName = step.args.filter { it.name != null }.associateBy { it.name!!.text }
                        val args = listOf(step.args.first().value.text) + op.action.free.filter { it.sidecarName != null }.map { byName.getValue(it.sidecarName!!).value.text }
                        w.line("var ${out.text}: ${vtype(result.type)}", indent = 2)
                        w.line("${out.text} := ${op.name}(S, ${args.joinToString()})", Source.Sidecar(step.pos, "probe call ${op.name}"), 2)
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
            is Or -> 1
            is And -> 2
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
            is Var -> e.local.name
            is FieldRead -> "${operand(e.obj, PREC_ATOM)}.${e.field.name}"
            is Arith -> "${operand(e.a, PREC_ARITH)} ${e.op.symbol} ${operand(e.b, PREC_ARITH + 1)}"
            is Cmp -> "${operand(e.a, prec(e) + 1)} ${e.op.symbol} ${operand(e.b, prec(e) + 1)}"
            is Not -> "!${operand(e.a, PREC_NOT)}"
            is And -> "${operand(e.a, 2)} && ${operand(e.b, 2)}"
            is Or -> "${operand(e.a, 1)} || ${operand(e.b, 1)}"
            // The template side condition is asserted before the statement; the token is its Long n.
            is TokenOf -> expr(e.n)
            is Carried -> "carried(S, ${expr(e.key)}, ${expr(e.token)})"
            is Elvis -> error("?: at ${e.pos}: emitted from DESIGN-B.md 8, B3 on")
        }

    private fun operand(
        e: Expr,
        min: Int,
    ): String = if (prec(e) < min) "(${expr(e)})" else expr(e)

    private companion object {
        const val PREC_EQ = 3
        const val PREC_CMP = 4
        const val PREC_ARITH = 5
        const val PREC_NOT = 6
        const val PREC_ATOM = 7
    }
}
