package kv.emit

import kv.kvir.And
import kv.kvir.Arith
import kv.kvir.Assign
import kv.kvir.BoolLit
import kv.kvir.Break
import kv.kvir.Call
import kv.kvir.Catch
import kv.kvir.Cmp
import kv.kvir.CmpOp
import kv.kvir.EntryFun
import kv.kvir.Elvis
import kv.kvir.EnumLit
import kv.kvir.ExcKind
import kv.kvir.Expr
import kv.kvir.FreeValue
import kv.kvir.If
import kv.kvir.Inline
import kv.kvir.IntLit
import kv.kvir.KType
import kv.kvir.Let
import kv.kvir.LibExc
import kv.kvir.Local
import kv.kvir.Module
import kv.kvir.New
import kv.kvir.Not
import kv.kvir.NullLit
import kv.kvir.Or
import kv.kvir.Pos
import kv.kvir.Return
import kv.kvir.Spawn
import kv.kvir.Status
import kv.kvir.Stmt
import kv.kvir.Task
import kv.kvir.Throw
import kv.kvir.Thrown
import kv.kvir.Try
import kv.kvir.Var
import kv.kvir.While
import kv.kvir.walk
import kv.model.ClientModels
import kv.model.ClientRole
import kv.sidecar.EnsuresItem
import kv.sidecar.EventForm
import kv.sidecar.GuardItem
import kv.sidecar.HoleDecl
import kv.sidecar.SPos
import kv.sidecar.Sidecar
import kv.sidecar.SidecarError
import kv.sidecar.SidecarException
import kv.sidecar.TraceEvent
import kv.sidecar.TrustedDecl
import kv.sidecar.Vexpr
import kv.sidecar.callTarget

/**
 * The property IDs of a client module that no sidecar clause states (DESIGN-E 6.2 item 1): the tool facts, the loop
 * hole and the tool's loop frame. A Silicon error maps to one of these, or to a guard or ensures ID of the sidecar.
 */
object ClientIds {
    const val LD_NOLEAK = "LD_noleak"
    const val LD_OWN = "LD_own"
    const val LD_TRANSFER = "LD_transfer"
    const val LD_WAIT = "LD_wait"
    const val TERM = "TERM"
    const val HOLE = "HOLE"

    /** The tool's part of a loop invariant: trace permissions, read-only trace values, the exception range. */
    const val FRAME = "loop frame"

    /** The tool facts, in report order. */
    val TOOL_FACTS = listOf(LD_NOLEAK, LD_OWN, LD_TRANSFER, LD_WAIT, TERM)
}

/**
 * The property IDs one Silicon run of [emitClient] can keep alone (`keep`): the tool facts LD_noleak and LD_wait, and
 * every guard and ensures of the sidecar. LD_own, LD_transfer, HOLE and TERM are always checked (DESIGN-E 5.2).
 */
fun switchableIds(s: Sidecar): List<String> =
    listOf(ClientIds.LD_NOLEAK, ClientIds.LD_WAIT) +
        s.operations.flatMap { op -> op.items.mapNotNull { (it as? GuardItem)?.id?.text ?: (it as? EnsuresItem)?.id?.text } }

/**
 * KVIR + sidecar of a client module -> Viper text (DESIGN-E 6.6), in the layout of spike/protocol2/target.vpr: types
 * and constants, the assumption switches, the obligation, the guard macros, the trace record and its event macros, the
 * trusted models, each task with its precondition macro, then each entry. With [keep], every switchable check
 * ([switchableIds]) but [keep] is `true`: one run of the full failing set (DESIGN-E 5.2, `isolate.py`).
 * Throws [SidecarException] for a sidecar or a module shape the emitter does not support.
 */
fun emitClient(
    module: Module,
    sidecar: Sidecar,
    keep: String? = null,
): Emitted = ClientEmitter(module, sidecar, keep).run()

/** The assumption switches (DESIGN-E 4): ID, the define, and the value the define has when the sidecar assumes it. */
private val SWITCHES =
    listOf(
        Triple("A1", "ERRORS_ARE_FATAL", true),
        Triple("A2", "LOCK_CALLS_THROW", false),
        Triple("A3", "ACCEPTED_TASKS_RUN", true),
        Triple("A4", "THROWING_UNLOCK_RELEASES", true),
        Triple("A5", "THROWING_TRYLOCK_TAKES_NOTHING", true),
    )

/** The library exception kinds after the module's own (DESIGN-E 3.5); IS_EXCEPTION is every kind before ERR. */
private val LIB_KINDS = listOf(LibExc.REE, LibExc.RTE, LibExc.CHK, LibExc.INTR, LibExc.ERR).map { it.name }
private const val ERR = "ERR"

/** Event outcomes (DESIGN-E 3.2): of a call with a nullable result, and of any other call. */
private val OUTCOMES = linkedMapOf("HIT" to 1L, "MISS" to 2L, "THREW" to 3L, "OK" to 1L)

private fun fail(
    pos: SPos,
    message: String,
): Nothing = throw SidecarException(listOf(SidecarError(pos, "emit", message)))

/** `GAVE_UP` -> `gaveUp`: the parameter that stands for a derived value in a macro or a task. */
private fun lowerCamel(name: String): String =
    name.lowercase().split('_').filter { it.isNotEmpty() }.mapIndexed { i, w -> if (i == 0) w else w.replaceFirstChar { it.uppercase() } }.joinToString("")

/** A trusted model's Viper method (DESIGN-E 3.3): a sidecar trusted declaration or a built-in client model. */
private class Model(
    val name: String,
    /** The sidecar declaration, or null for a built-in model. */
    val decl: TrustedDecl?,
    val builtIn: String?,
    /** The parameters the model passes: name and Viper type; and which of them are non-null Int-encoded values. */
    val params: List<Pair<String, String>>,
    val nonNull: List<String>,
    /** The results: name, Viper type, nullable. */
    val results: List<Triple<String, String, Boolean>>,
    /** The kinds the call may throw besides an Error (A1); empty for a call that does not throw. */
    val kinds: List<String>,
    val role: ClientRole?,
) {
    val throws get() = kinds.isNotEmpty()
    val obligationCall get() = decl?.creates != null || decl?.consumes != null
}

/** The fields an event writes: the outcome or count, and the recorded result ("Obj" or "Val") or hit flag. */
private class EventFields(
    val event: TraceEvent,
    val outcome: String?,
    val hit: String?,
    val value: String?,
    val valueType: String?,
)

/**
 * The events of one trusted call, grouped by their condition (DESIGN-E 3.2): a call updates exactly one group, the first
 * whose condition holds, in the order first, while-holds, where-groups; [Group.cond] is null for an unconditional group.
 */
private class Group(
    val form: EventForm?,
    val events: List<TraceEvent>,
    val where: VE?,
)

private enum class First { UNSET, MAYBE, SET }

/**
 * What the emitter knows at a point of a method (the trace facts of DESIGN-E 3.4): exception flags that are NONE,
 * null and non-null values, and per first-event field whether it is set and by which call (its flag and result).
 */
private data class Env(
    val none: Set<String> = emptySet(),
    val nullness: Map<String, Boolean> = emptyMap(),
    val first: Map<String, First> = emptyMap(),
    val firstBy: Map<String, Pair<String, String?>> = emptyMap(),
    /** Flags whose dispatch raises on every exception: `exc == NONE` later means the flag is NONE. */
    val raiseAll: Set<String> = emptySet(),
)

private class ClientEmitter(
    private val m: Module,
    private val s: Sidecar,
    private val keep: String?,
) {
    private var w = VprWriter()
    private val methods = mutableListOf<EmittedMethod>()
    private val anchor = s.modules.single().pos
    private val op = s.operations.singleOrNull() ?: fail(anchor, "the client emitter emits a module with one operation (DESIGN-E 6.1)")
    private val entry: EntryFun = m.entries.singleOrNull() ?: fail(anchor, "the client emitter emits a module with one entry")
    private val assumed = s.assumes.map { it.id.text }.toSet()

    // ---- types ----

    private val enumNames = m.enums.associateBy { it.name }
    private val classNames = m.classes.map { it.name }.toSet()

    /** The abstract types that are Int with NULL: those used nullable (DESIGN-v0.md 2.9); the others are domains. */
    private val intTypes: Set<String> =
        (
            s.trusted.flatMap { t -> t.returns.map { it.type.text } + t.named.filterNotNull().map { it.type.text } }.filter { it.endsWith("?") }.map { it.removeSuffix("?") } +
                (m.entries.flatMap { it.body } + m.tasks.flatMap { it.body }).walk().flatMap { st -> locals(st) }.mapNotNull { (it.type as? KType.Abs)?.takeIf { t -> t.nullable }?.name } +
                m.tasks.flatMap { it.params }.mapNotNull { (it.type as? KType.Abs)?.takeIf { t -> t.nullable }?.name }
        ).filter { n -> m.types.any { it.name == n } }.toSet()

    private fun locals(st: Stmt): List<Local> =
        when (st) {
            is Let -> listOf(st.v)
            is Call -> st.outs
            is New -> listOf(st.v)
            is Inline -> listOfNotNull(st.out)
            else -> emptyList()
        }

    private fun vt(t: KType): String =
        when (t) {
            KType.IntT -> "Int"
            KType.BoolT -> "Bool"
            is KType.Abs -> if (t.name in intTypes) "Int" else t.name
            is KType.RefT -> "Ref"
            is KType.EnumT -> "Int"
        }

    /** The Viper type of sidecar type text, e.g. `Token?`, `LockType`, `Data`, `Bool`. */
    private fun vt(text: String): String {
        val base = text.removeSuffix("?")
        return when {
            base in enumNames -> "Int"
            base in classNames -> "Ref"
            base == "Bool" -> "Bool"
            base == "Int" -> "Int"
            m.types.any { it.name == base } -> if (base in intTypes) "Int" else base
            else -> "Int"
        }
    }

    private fun nullOf(type: String) = if (type == "Ref") "null" else "NULL"

    /** The default value of a local of type [t], which a cancelled return leaves in its target (`res := null`). */
    private fun default(t: KType): String =
        when (t) {
            KType.BoolT -> "false"
            KType.IntT, is KType.EnumT -> "0"
            is KType.RefT -> "null"
            is KType.Abs -> if (t.name in intTypes) "NULL" else fail(anchor, "no default value of domain type ${t.name}")
        }

    // ---- exception kinds ----

    /**
     * The kinds the code throws (in source order), then those only the sidecar names (`exc == C(CODE)`): a clause may
     * name a kind the code never throws, and then fails where it requires it.
     */
    private val thrownNames: List<String> by lazy {
        val named =
            (op.items.filterIsInstance<GuardItem>().map { it.expr } + op.items.filterIsInstance<EnsuresItem>().map { it.expr } + s.holes.map { Vexpr(it.text, it.textPos) })
                .flatMap { v -> parseVE(v).walk().filterIsInstance<VE.Call>().filter { c -> c.args.size == 1 && c.args[0] is VE.Id && c.name[0].isUpperCase() && c.name != obligation.name } }
                .map { "${it.name}_${(it.args[0] as VE.Id).name}" }
        (m.thrown.map(::kindName) + named).distinct()
    }
    private val kinds by lazy { thrownNames + LIB_KINDS }

    private fun kindName(k: ExcKind): String =
        when (k) {
            is LibExc -> k.name
            is Thrown -> k.cls + (k.code?.let { "_$it" } ?: "")
        }

    private fun order(ks: Collection<String>) = kinds.filter { it in ks }

    // ---- the obligation ----

    private val obligation = m.obligations.singleOrNull() ?: fail(anchor, "the client emitter emits a module with one obligation")
    private val creator: TrustedDecl =
        s.trusted.singleOrNull { it.creates?.obligation?.text == obligation.name } ?: fail(anchor, "one trusted call must create ${obligation.name}")
    private val predParams = creator.creates!!.args.map { it.text }
    private val predTypes = obligation.types.map(::vt)
    private val noObligations = "NO_${obligation.name.uppercase()}S"

    /** The holds shape of the sidecar: the argument positions it names (the others are `_`). One shape (HOLDS). */
    private val holdsShape: List<Int>? by lazy {
        val shapes =
            (holdsPatterns() + s.traces.flatMap { it.events }.mapNotNull { e -> e.holds?.args?.map { it.text } })
                .map { args -> args.indices.filter { args[it] != "_" } }.distinct()
        if (shapes.size > 1) fail(anchor, "the client emitter supports one holds(...) shape; the sidecar has ${shapes.size}")
        shapes.singleOrNull()
    }

    private fun holdsPatterns(): List<List<String>> =
        (op.items.filterIsInstance<GuardItem>().map { it.expr } + op.items.filterIsInstance<EnsuresItem>().map { it.expr } +
            s.traces.flatMap { t -> t.events.mapNotNull { it.where } + t.derived.map { it.expr } })
            .flatMap { v -> parseVE(v).walk().filterIsInstance<VE.Call>().filter { it.name == "holds" }.map { holdsArgs(it, v) } }

    private fun holdsArgs(
        c: VE.Call,
        v: Vexpr,
    ): List<String> {
        val inner = c.args.singleOrNull() as? VE.Call
        if (inner == null || inner.name != obligation.name || inner.args.size != predParams.size) fail(v.posOf(c), "holds takes one ${obligation.name}(...) with ${predParams.size} arguments")
        return inner.args.map { (it as? VE.Id)?.name ?: it.print() }
    }

    // ---- trusted models ----

    private val models: List<Model> =
        s.decls.mapNotNull { d ->
            when (d) {
                is TrustedDecl -> model(d)
                is kv.sidecar.UseDecl -> ClientModels.entries.firstOrNull { it.model == d.model.text && it.role in setOf(ClientRole.SPAWN, ClientRole.CALL) }?.let(::builtIn)
                else -> null
            }
        }

    private fun model(t: TrustedDecl): Model {
        val parts = t.fn.text.split('.')
        // A trusted value is named by its property; a member fun by its class and name; a top-level fun by its name.
        val name = if (t.value || parts.size < 2 || !parts[parts.size - 2][0].isUpperCase()) parts.last() else "${parts[parts.size - 2]}_${parts.last()}"
        val params = t.named.filterNotNull().map { it.name.text to vt(it.type.text) }
        val nonNull = t.named.filterNotNull().filter { !it.type.text.endsWith("?") && vt(it.type.text) == "Int" && it.type.text in intTypes }.map { it.name.text }
        val results = t.returns.map { Triple(it.name.text, vt(it.type.text), it.type.text.endsWith("?")) }
        return Model(name, t, null, params, nonNull, results, t.throwKinds.map { it.text }, null)
    }

    private fun builtIn(c: kv.model.ClientModel): Model {
        val name = c.model.removePrefix("jdk.").replace('.', '_')
        val results = if (c.role == ClientRole.SPAWN) listOf(Triple("dropped", "Bool", false)) else emptyList()
        return Model(name, null, c.model, emptyList(), emptyList(), results, c.throws, c.role)
    }

    /** The model of call target [ref] (a KVIR Call.fn or Spawn.model). */
    private fun modelOf(
        ref: String,
        pos: Pos,
    ): Model {
        val target = s.callTarget(ref)
        return models.firstOrNull { it.decl != null && it.decl === target || it.builtIn != null && it.builtIn == target }
            ?: fail(anchor, "no trusted model for the call of $ref at ${pos.line}:${pos.col}")
    }

    private fun modelRef(m: Model): Any = m.decl ?: m.builtIn!!

    /** False for a call of the tool's arbitrary-value model (ClientModels.ARBITRARY), which has no Viper method. */
    private fun modeled(c: Call) = c.fn != ClientModels.ARBITRARY

    // ---- trace ----

    private val trace = s.traces.singleOrNull()
    private val events = trace?.events.orEmpty()
    private val derived = trace?.derived.orEmpty().associate { it.name.text to (it to parseVE(it.expr)) }

    private fun eventModel(e: TraceEvent): Model? {
        val call = e.call ?: return null
        val target = s.callTarget(call.text) ?: fail(call.pos, "trace field ${e.name.text}: no trusted call ${call.text}")
        return models.firstOrNull { modelRef(it) == target } ?: fail(call.pos, "trace field ${e.name.text}: ${call.text} has no model")
    }

    /** The count event a trailing-failures event follows. */
    private fun countOf(e: TraceEvent): TraceEvent = events.first { it.name.text == e.of!!.text }

    private val fields: List<EventFields> =
        events.map { e ->
            val model = if (e.form == EventForm.TRAILING_FAILURES) eventModel(countOf(e)) else eventModel(e)
            val result = model?.results?.firstOrNull()?.takeIf { model.role != ClientRole.SPAWN }
            val suffix = if (result?.second == "Ref") "Obj" else "Val"
            val n = e.name.text
            when (e.form) {
                EventForm.FIRST, EventForm.WHILE_HOLDS, EventForm.LAST -> EventFields(e, n, null, result?.let { n + suffix }, result?.second)
                EventForm.HIT -> EventFields(e, null, n + "Hit", result?.let { n + suffix }, result?.second)
                EventForm.COUNT, EventForm.TRAILING_FAILURES -> EventFields(e, n, null, null, null)
            }
        }

    private val fieldDecls: List<Pair<String, String>> =
        fields.flatMap { f -> listOfNotNull(f.outcome?.let { it to "Int" }, f.hit?.let { it to "Bool" }, f.value?.let { it to f.valueType!! }) }

    /** The events of [model], with trailing-failures events next to their count. */
    private fun eventsOf(model: Model): List<TraceEvent> =
        events.filter { e -> (if (e.form == EventForm.TRAILING_FAILURES) eventModel(countOf(e)) else eventModel(e)) === model }

    /** The groups of [model]'s events (see [Group]), in chain order. */
    private fun groups(model: Model): List<Group> {
        val evs = eventsOf(model).filter { it.form != EventForm.TRAILING_FAILURES }
        val firsts = evs.filter { it.form == EventForm.FIRST }.map { Group(EventForm.FIRST, listOf(it), it.where?.let(::parseVE)) }
        val holds = evs.filter { it.form == EventForm.WHILE_HOLDS }.map { Group(EventForm.WHILE_HOLDS, listOf(it), null) }
        val others = evs.filter { it.form !in setOf(EventForm.FIRST, EventForm.WHILE_HOLDS) }.groupBy { it.where?.text?.replace(Regex("\\s+"), " ") }
        val rest =
            others.map { (_, es) ->
                val trailing = events.filter { t -> t.form == EventForm.TRAILING_FAILURES && es.any { it.name.text == t.of!!.text } }
                Group(null, es + trailing, es.first().where?.let(::parseVE))
            }
        return firsts + holds + rest
    }

    /** The models with event rules, in the order the code first calls them (entry first, then tasks). */
    private val eventModels: List<Model> by lazy {
        val order = (m.entries.flatMap { it.body } + m.tasks.flatMap { it.body }).walk().filterIsInstance<Call>().filter(::modeled).map { modelOf(it.fn, it.pos) }.distinct().toList()
        models.filter { md -> md.role != ClientRole.SPAWN && eventsOf(md).isNotEmpty() }.sortedBy { md -> order.indexOf(md).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    private fun evMacro(model: Model) = "EV_${model.name}"

    // ---- names of the trace vocabulary ----

    private val fieldByName = fields.associateBy { it.event.name.text }

    /** A derived value that reads only first-event fields: the path of the call, which a task inherits (DESIGN-E 3.4). */
    private fun pathValue(name: String): Boolean =
        derived[name]!!.second.walk().filterIsInstance<VE.Id>().all { id -> fieldByName[id.name]?.event?.form?.let { it == EventForm.FIRST } ?: true }

    private val freeNames = op.free.flatMap { f -> f.names.map { it.text } }

    // ---- output helpers ----

    private fun line(
        code: String,
        indent: Int,
        source: Source? = null,
        comment: String? = null,
    ) = w.line(code, source, indent, comment)

    private fun on(id: String) = keep == null || keep == id

    private fun check(
        id: String,
        e: String,
    ) = if (on(id)) e else "true"

    // ---- program ----

    /** Task id -> the derived path values (name -> value text) the spawn site fixes; filled by a first pass. */
    private val spawnFacts = mutableMapOf<String, Map<String, String>>()

    fun run(): Emitted {
        if (m.entries.any { it.status !is Status.Supported } || m.tasks.any { it.status !is Status.Supported }) fail(anchor, "the module has unsupported parts")
        // First pass: the entry alone, for the facts its spawns fix (the task methods come before it).
        val saved = w
        w = VprWriter()
        entryMethod(entry)
        methods.clear()
        w = saved
        header()
        constants()
        obligationDecls()
        guardMacros()
        classFields()
        traceDecls()
        models.forEach(::modelMethod)
        m.tasks.forEach(::taskMethod)
        entryMethod(entry)
        return Emitted(w.text, w.ranges.toList(), methods.toList(), emptyList())
    }

    private fun header() {
        w.comment("Generated by kv emit (DESIGN-E 6.6, a client module). Do not edit: change the Kotlin source or the sidecar.")
        w.comment("Module: ${m.id}")
        w.comment("Sidecar: ${s.file}")
        w.comment("Sources: ${s.sources.joinToString { it.path }}")
        w.comment("Assumptions are not checked by Silicon: see the 'Assumptions' part of the kv verify report.")
        keep?.let { w.comment("Isolation run: every switchable check but $it is true (the full failing set, DESIGN-E 5.2).") }
        w.put("\n")
    }

    private fun constants() {
        m.types.filter { it.template == null && it.name !in intTypes }.forEach { t ->
            line("domain ${t.name} {}", 0, Source.Sidecar(s.types.single { it.name.text == t.name }.pos, "type ${t.name}"))
        }
        val created = creator.returns.firstOrNull { it.name.text == creator.creates!!.ifNotNull?.text }
        if (created != null) {
            // A5 off: the token of a lock the store took although the acquiring call threw (DESIGN-E 4 item 3).
            val type = created.type.text.removeSuffix("?")
            val args = predParams.withIndex().filter { it.value != created.name.text }.map { (i, p) -> "$p: ${predTypes[i]}" }
            w.put("\n")
            w.comment("A5 off: the ${type.lowercase()} of a ${obligation.name} the store took although the call threw. The caller never sees it.")
            line("domain Lost$type {", 0, Source.Generated("A5"))
            line("function lost$type(${args.joinToString()}): ${vt(created.type.text)}", 2)
            line("}", 0)
        }
        w.put("\n")
        if (intTypes.isNotEmpty()) {
            line("define NULL (-1)", 0, Source.Generated("NULL"), "null of ${intTypes.sorted().joinToString { "$it?" }}")
            w.put("\n")
        }
        m.enums.forEach { e ->
            w.comment("${e.name} (${e.kotlin})")
            e.entries.forEachIndexed { i, entry -> line("define $entry $i", 0, Source.Generated("enum ${e.name}")) }
            w.put("\n")
        }
        w.comment("Exception kinds (DESIGN-E 3.5); NONE = no exception")
        line("define NONE 0", 0)
        kinds.forEachIndexed { i, k -> line("define $k ${i + 1}", 0, Source.Generated("exception kinds")) }
        line("define IS_EXCEPTION(e) (${kinds.first()} <= e && e <= ${LibExc.INTR.name})", 0, Source.Generated("exception kinds"), "catch (e: Exception)")
        w.put("\n")
        if (trace != null) {
            w.comment("Event outcomes in the trace record")
            OUTCOMES.forEach { (n, v) -> line("define $n $v", 0) }
            w.put("\n")
        }
        w.comment("---- Assumptions of the trusted models (DESIGN-E 4); 'assume' lines of the sidecar ----")
        SWITCHES.forEach { (id, define, whenAssumed) ->
            val value = if (id in assumed) whenAssumed else !whenAssumed
            line("define $define $value", 0, Source.Generated("assumption $id"), "$id ${if (id in assumed) "assumed" else "not assumed"}")
        }
        line("define MAY_ERR (!ERRORS_ARE_FATAL)", 0)
        w.put("\n")
    }

    private fun obligationDecls() {
        val d = s.obligations.single()
        w.comment("---- Obligation (DESIGN-E 3.1) ----")
        line("predicate ${obligation.name}(${predParams.zip(predTypes).joinToString { (p, t) -> "$p: $t" }})", 0, Source.Sidecar(d.pos, "obligation ${obligation.name}"))
        val q = predParams.map { "q$it" }
        val bound = q.zip(predTypes).joinToString { (p, t) -> "$p: $t" }
        val trigger = "[${obligation.name}(${q.joinToString()})]"
        line("define $noObligations [true, forperm $bound $trigger :: false]", 0, Source.Generated(ClientIds.LD_NOLEAK), "LD_noleak: the thread holds no obligation")
        holdsShape?.let { shape ->
            val args = shape.map { predParams[it] }
            val test = shape.joinToString(" && ") { "${q[it]} == ${predParams[it]}" }
            line("define HOLDS(${args.joinToString()}) (!(forperm $bound $trigger :: !($test)))", 0, Source.Generated("HOLDS"))
        }
        w.put("\n")
    }

    // ---- sidecar vocabulary -> Viper ----

    /**
     * Where a sidecar expression is used: an entry or task method body or contract ([Ctx.Unit]), a guard macro body
     * ([Ctx.Macro]), a derived macro body ([Ctx.Derived]), or a loop hole ([Ctx.Unit] with [Ctx.Unit.loop]).
     */
    private sealed interface Ctx {
        data class Unit(
            val task: Task?,
            val hole: HoleNames? = null,
        ) : Ctx

        data object Macro : Ctx

        data object Derived : Ctx
    }

    /** The loop hole's names (DESIGN-E E3): Kotlin locals by name, `returned`, `result`, `broke`. */
    private class HoleNames(
        val locals: Map<String, String>,
        val returned: String?,
        val result: String?,
        val broke: String?,
    )

    /** The args a derived value's macro takes after `tr`: the free values it reads, in operation order. */
    private fun derivedArgs(name: String) = freeNames.filter { f -> derived[name]!!.second.walk().any { it is VE.Id && it.name == f } }

    private fun translate(
        v: Vexpr,
        ctx: Ctx,
    ): VE {
        val e = parseVE(v)
        return translate(e, v, ctx)
    }

    private fun translate(
        e: VE,
        v: Vexpr,
        ctx: Ctx,
    ): VE {
        val unit = ctx as? Ctx.Unit
        val task = unit?.task

        fun tr(f: String): VE = VE.Dot(VE.Id("tr", -1), f, -1)

        fun bad(
            x: VE,
            message: String,
        ): Nothing = fail(v.posOf(x), message)
        return e.rewrite { x ->
            when (x) {
                is VE.Dot -> {
                    val base = x.e as? VE.Id
                    val f = base?.let { fieldByName[it.name] }
                    when {
                        f == null -> null
                        x.field == "obj" || x.field == "val" -> tr(f.value ?: bad(x, "trace field ${f.event.name.text} records no result"))
                        x.field == "hit" -> tr(f.hit ?: bad(x, "trace field ${f.event.name.text} is no hit field"))
                        else -> bad(x, "a trace field has .obj, .val or .hit, not .${x.field}")
                    }
                }
                is VE.Call ->
                    when {
                        x.name == "holds" -> {
                            val args = holdsArgs(x, v)
                            VE.Call("HOLDS", holdsShape!!.map { VE.Id(args[it], -1) }, x.at)
                        }
                        x.args.size == 1 && x.args[0] is VE.Id && "${x.name}_${(x.args[0] as VE.Id).name}" in thrownNames -> VE.Id("${x.name}_${(x.args[0] as VE.Id).name}", x.at)
                        else -> bad(x, "unknown function '${x.name}' in a sidecar expression")
                    }
                is VE.Id -> {
                    val n = x.name
                    val f = fieldByName[n]
                    val hole = unit?.hole
                    when {
                        hole != null && n in hole.locals -> VE.Id(hole.locals.getValue(n), x.at)
                        hole != null && n == "returned" -> VE.Id(hole.returned ?: bad(x, "no return leaves this loop"), x.at)
                        hole != null && n == "result" -> VE.Id(hole.result ?: bad(x, "no return with a value leaves this loop"), x.at)
                        hole != null && n == "broke" -> VE.Id(hole.broke ?: bad(x, "this loop has no break"), x.at)
                        f != null -> {
                            if (f.outcome == null) bad(x, "trace field $n is a hit field: write $n.hit or $n.obj")
                            if (ctx == Ctx.Macro) bad(x, "a guard reads no trace field directly; use a derived value")
                            tr(f.outcome)
                        }
                        n in derived ->
                            when {
                                ctx == Ctx.Macro -> VE.Id(lowerCamel(n), x.at)
                                ctx == Ctx.Derived -> bad(x, "a derived value does not use another")
                                task == null -> VE.Call(n, listOf(VE.Id("tr", -1)) + derivedArgs(n).map { VE.Id(it, -1) }, x.at)
                                pathValue(n) -> VE.Id(lowerCamel(n), x.at)
                                // A task makes no wait of its own; a run value is false there (it must be positive only).
                                else -> VE.Id("false", x.at)
                            }
                        n in freeNames || n == "k" || n == "exc" || n == "NONE" || n == "null" || n == "true" || n == "false" || n == "_" -> null
                        n == op.result?.name?.text && task == null -> null
                        n in OUTCOMES || n in kinds || enumNames.values.any { n in it.entries } -> null
                        else -> bad(x, "unknown name '$n' in a sidecar expression")
                    }
                }
                else -> null
            }
        }
    }

    // ---- guard macros ----

    private val guards = op.items.filterIsInstance<GuardItem>()

    /** The parameters of guard [g]'s macro: k, the path values, the free values, the run values it reads. */
    private fun guardParams(g: GuardItem): List<String> {
        val ids = parseVE(g.expr).walk().filterIsInstance<VE.Id>().map { it.name }.toSet()
        val ds = derived.keys.filter { it in ids }
        return listOfNotNull("k".takeIf { it in ids }) + ds.filter(::pathValue) + freeNames.filter { it in ids } + ds.filterNot(::pathValue)
    }

    private fun guardMacros() {
        if (guards.isEmpty()) return
        guards.forEach { g ->
            w.comment("${g.id.text}: before each call of ${g.call.text} (sidecar guard)")
            val params = guardParams(g).map { if (it in derived) lowerCamel(it) else it }
            line("define GUARD_${g.id.text}(${params.joinToString()}) (${translate(g.expr, Ctx.Macro).print()})", 0, Source.Sidecar(g.pos, g.id.text))
        }
        w.put("\n")
    }

    private fun guardArgs(
        g: GuardItem,
        task: Task?,
    ): List<String> =
        guardParams(g).map { p ->
            when {
                p !in derived -> p
                task == null -> "$p(${(listOf("tr") + derivedArgs(p)).joinToString()})"
                pathValue(p) -> lowerCamel(p)
                positiveOnly(parseVE(g.expr), p) -> "false"
                else -> fail(g.pos, "guard ${g.id.text} reads $p in a task, where only a positive run value can be false")
            }
        }

    // ---- class fields and trace ----

    private fun classFields() {
        m.classes.forEach { c ->
            w.comment("---- ${c.name} (sidecar: class ${c.name}) ----")
            c.fields.forEach { f -> line("field ${f.name}: ${vt(f.type)}", 0, Source.Kotlin(f.pos)) }
            w.put("\n")
        }
    }

    private fun traceDecls() {
        if (trace == null) return
        w.comment("---- Trace record (DESIGN-E 3.2): one per thread run ----")
        fieldDecls.forEach { (n, t) -> line("field $n: $t", 0, Source.Sidecar(trace.pos, "trace")) }
        w.put("\n")
        line("define TR(tr) (${fieldDecls.joinToString(" && ") { "acc(tr.${it.first})" }})", 0, Source.Generated("TR"))
        w.put("\n")
        line("define TR_NEW(tr) {", 0, Source.Generated("TR"))
        line("tr := new(*)", 2)
        fieldDecls.forEach { (n, t) -> line("tr.$n := ${when (t) { "Int" -> "0"; "Bool" -> "false"; else -> "null" }}", 2) }
        line("}", 0)
        w.put("\n")
        trace.derived.forEach { d ->
            val params = listOf("tr") + derivedArgs(d.name.text)
            val body = translate(d.expr, Ctx.Derived)
            line("define ${d.name.text}(${params.joinToString()}) (${body.print()})", 0, Source.Sidecar(d.name.pos, "derived ${d.name.text}"))
        }
        if (trace.derived.isNotEmpty()) w.put("\n")
        w.comment("Event rules (DESIGN-E 3.2): after each call of a trusted model, the first group whose condition holds")
        eventModels.forEach(::eventMacro)
        w.put("\n")
    }

    /** The parameters of [model]'s event macro: tr, the call parameters, `e` and the results its events read. */
    private fun eventParams(model: Model): List<String> {
        val gs = groups(model)
        val used = mutableSetOf<String>()
        gs.forEach { g ->
            g.where?.walk()?.filterIsInstance<VE.Id>()?.forEach { used += it.name }
            if (g.form == EventForm.WHILE_HOLDS) g.events.forEach { e -> e.holds!!.args.forEach { used += it.text } }
        }
        val needE = gs.any { g -> g.events.any { it.form != EventForm.COUNT } }
        val needResult = gs.any { g -> g.events.any { e -> fieldByName[e.name.text]?.let { it.value != null || it.hit != null } == true } }
        return listOf("tr") + model.params.map { it.first }.filter { it in used } + listOfNotNull("e".takeIf { needE }) +
            model.results.take(1).map { it.first }.filter { needResult }
    }

    private fun outcome(
        model: Model,
        e: String,
        d: String?,
    ): String {
        val r = model.results.firstOrNull()
        return if (r != null && r.third) "(($e) != NONE ? THREW : (($d) != null ? HIT : MISS))" else "(($e) == NONE ? OK : THREW)"
    }

    private fun eventMacro(model: Model) {
        val params = eventParams(model)
        val e = "e"
        val d = model.results.firstOrNull()?.first
        line("define ${evMacro(model)}(${params.joinToString()}) {", 0, Source.Generated("event rules"))
        val gs = groups(model)
        val (always, chain) = gs.partition { it.form == null && it.where == null }
        always.forEach { g -> groupBody(g, model, e, d, 2) }
        chain.forEachIndexed { i, g ->
            val cond = groupCond(g, model, chain.take(i))
            val head =
                when {
                    i == 0 -> "if ($cond) {"
                    cond == null -> "} else {"
                    else -> "} elseif ($cond) {"
                }
            line(head, 2)
            groupBody(g, model, e, d, 4)
        }
        if (chain.isNotEmpty()) line("}", 2)
        line("}", 0)
    }

    /** The condition of group [g] in its chain after [before], or null for `else` (it is the rest of the chain). */
    private fun groupCond(
        g: Group,
        model: Model,
        before: List<Group>,
    ): String? {
        fun firstCond(gr: Group) = "tr.${gr.events.single().name.text} == 0"
        return when (g.form) {
            EventForm.FIRST -> firstCond(g)
            EventForm.WHILE_HOLDS -> {
                val p = g.events.single().holds!!.args.map { it.text }
                "HOLDS(${holdsShape!!.joinToString { p[it] }})"
            }
            else -> {
                val where = g.where!!
                if (complement(where, before, model)) return null
                translateWhere(where, model)
            }
        }
    }

    /**
     * True if [where] is the rest of the chain after [before]: the negations of all earlier conditions (`!first &&
     * !holds(...)`), or the last value of an enum the earlier groups test with `==`.
     */
    private fun complement(
        where: VE,
        before: List<Group>,
        model: Model,
    ): Boolean {
        if (before.isEmpty()) return false
        val cs = where.conjuncts().map { it.unparen() }
        val earlier =
            before.map { g ->
                when (g.form) {
                    EventForm.FIRST -> g.events.single().name.text
                    EventForm.WHILE_HOLDS -> "holds:" + g.events.single().holds!!.args.joinToString { it.text }
                    else -> g.where!!.print()
                }
            }
        val negated =
            cs.map { c ->
                val n = (c as? VE.Un)?.takeIf { it.op == "!" }?.e?.unparen() ?: return@map null
                when {
                    n is VE.Id -> n.name
                    n is VE.Call && n.name == "holds" -> "holds:" + ((n.args.single() as VE.Call).args.joinToString { it.print() })
                    else -> n.print()
                }
            }
        if (negated.none { it == null } && negated.toSet() == earlier.toSet()) return true
        // `p == E` over every entry of p's enum: the last group is the remaining entry.
        val tests = (before.map { it.where } + where).map { (it?.unparen() as? VE.Bin)?.takeIf { b -> b.op == "==" } }
        if (tests.any { it == null }) return false
        val param = tests.map { (it!!.a as? VE.Id)?.name }.distinct().singleOrNull() ?: return false
        val values = tests.map { (it!!.b as? VE.Id)?.name }
        val enum = model.decl?.named?.filterNotNull()?.find { it.name.text == param }?.type?.text?.let { enumNames[it] } ?: return false
        return values.toSet() == enum.entries.toSet() && values.size == enum.entries.size
    }

    private fun translateWhere(
        where: VE,
        model: Model,
    ): String =
        where.rewrite { x ->
            when {
                x is VE.Id && fieldByName[x.name]?.event?.form == EventForm.FIRST -> VE.Paren(VE.Bin("==", VE.Dot(VE.Id("tr", -1), x.name, -1), VE.Num(0, -1), -1), -1)
                x is VE.Call && x.name == "holds" -> {
                    val args = (x.args.single() as VE.Call).args.map { it.print() }
                    VE.Call("HOLDS", holdsShape!!.map { VE.Id(args[it], -1) }, -1)
                }
                else -> null
            }
        }.print()

    private fun groupBody(
        g: Group,
        model: Model,
        e: String,
        d: String?,
        indent: Int,
    ) {
        val counts = g.events.filter { it.form == EventForm.COUNT }
        val trailing = g.events.filter { it.form == EventForm.TRAILING_FAILURES }
        val hits = g.events.filter { it.form == EventForm.HIT }
        val outcomes = g.events.filter { it.form in setOf(EventForm.FIRST, EventForm.WHILE_HOLDS, EventForm.LAST) }
        counts.forEach { c -> line("tr.${c.name.text} := tr.${c.name.text} + 1", indent) }
        outcomes.forEach { o ->
            val f = fieldByName.getValue(o.name.text)
            line("tr.${f.outcome} := ${outcome(model, e, d)}", indent)
            f.value?.let { line("tr.$it := $d", indent) }
        }
        if (trailing.isNotEmpty() || hits.isNotEmpty()) {
            line("if (($e) == NONE) {", indent)
            trailing.forEach { t -> line("tr.${t.name.text} := 0", indent + 2) }
            hits.forEach { h ->
                val f = fieldByName.getValue(h.name.text)
                line("if (($d) != null) {", indent + 2)
                line("tr.${f.hit} := true", indent + 4)
                f.value?.let { line("tr.$it := $d", indent + 4) }
                line("}", indent + 2)
            }
            if (trailing.isNotEmpty()) {
                line("} else {", indent)
                trailing.forEach { t -> line("tr.${t.name.text} := tr.${t.name.text} + 1", indent + 2) }
            }
            line("}", indent)
        }
    }

    // ---- trusted model methods ----

    private fun modelMethod(model: Model) {
        val pos = model.decl?.pos ?: anchor
        val label = model.decl?.fn?.text ?: model.builtIn!!
        method(model.name, MethodKind.MODEL, label, null) {
            val src = if (model.decl != null) Source.Sidecar(pos, "trusted ${model.decl.fn.text}") else Source.Generated("model ${model.builtIn}")
            val results = (if (model.throws) listOf("exc: Int") else emptyList()) + model.results.map { (n, t, _) -> "$n: $t" }
            line("method ${model.name}(${model.params.joinToString { (n, t) -> "$n: $t" }}) returns (${results.joinToString()})", 0, src)
            val d = model.decl
            val consumes = d?.consumes
            if (consumes != null || model.nonNull.isNotEmpty()) {
                val pre = model.nonNull.map { "$it != NULL" } + listOfNotNull(consumes?.let { "${it.obligation.text}(${it.args.joinToString { a -> a.text }})" })
                line("requires ${pre.joinToString(" && ")}", 2, src, consumes?.let { "LD_own: only the holder releases" })
            }
            if (model.throws) {
                val lock = model.obligationCall
                val ks = order(model.kinds).map { k -> if (lock) "(LOCK_CALLS_THROW && exc == $k)" else "exc == $k" }
                line("ensures ${(listOf("exc == NONE") + ks + "(MAY_ERR && exc == ERR)").joinToString(" || ")}", 2, src)
                model.results.filter { it.third }.forEach { (n, t, _) -> line("ensures exc != NONE ==> $n == ${nullOf(t)}", 2, src) }
            }
            d?.creates?.let { c ->
                val args = c.args.joinToString { it.text }
                val r = c.ifNotNull?.text
                val type = d.returns.first { it.name.text == r }.type.text.removeSuffix("?")
                line("ensures ${r?.let { "$it != NULL ==> " } ?: ""}${c.obligation.text}($args)", 2, src)
                if (model.throws && r != null) {
                    val lost = c.args.map { a -> if (a.text == r) "lost$type(${c.args.filter { it.text != r }.joinToString { it.text }})" else a.text }
                    line("ensures exc != NONE && !THROWING_TRYLOCK_TAKES_NOTHING ==> ${c.obligation.text}(${lost.joinToString()})", 2, src, "A5")
                }
            }
            consumes?.let { c ->
                if (model.throws) line("ensures exc != NONE && !THROWING_UNLOCK_RELEASES ==> ${c.obligation.text}(${c.args.joinToString { it.text }})", 2, src, "A4")
            }
            if (model.role == ClientRole.SPAWN) line("ensures dropped ==> exc == NONE && !ACCEPTED_TASKS_RUN", 2, src, "A3")
        }
        w.put("\n")
    }

    private fun method(
        name: String,
        kind: MethodKind,
        label: String,
        kotlin: Pos?,
        body: () -> Unit,
    ) {
        val start = w.line
        body()
        methods += EmittedMethod(name, kind, label, kotlin, VprRange(start, 1, w.line - 1, Int.MAX_VALUE))
    }

    // ---- units: tasks and entries ----

    /** Per task: does it have a trace record, which ensures apply, which derived path values it takes. */
    private fun taskEvents(t: Task): Set<String> {
        val calls = t.body.walk().filterIsInstance<Call>().filter(::modeled).map { modelOf(it.fn, it.pos) }.toSet()
        val written = mutableSetOf<String>()
        calls.forEach { c -> eventsOf(c).forEach { e -> fieldByName[e.name.text]?.let { f -> written += listOfNotNull(f.outcome, f.hit, f.value) } } }
        t.body.walk().filterIsInstance<Spawn>().forEach { sp -> eventsOf(modelOf(sp.model, sp.pos)).forEach { written += it.name.text } }
        return written
    }

    /** The ensures of the operation that apply to task [t]: no `exc` or result, and only trace fields the task writes. */
    private fun taskEnsures(t: Task): List<EnsuresItem> {
        val written = taskEvents(t)
        return op.items.filterIsInstance<EnsuresItem>().filter { en ->
            val e = parseVE(en.expr)
            val ids = e.walk().filterIsInstance<VE.Id>().map { it.name }.toSet()
            val reads = e.walk().mapNotNull { x -> (x as? VE.Id)?.name?.let { fieldByName[it] } }.flatMap { listOfNotNull(it.outcome, it.hit, it.value) }.toSet() +
                e.walk().mapNotNull { x -> ((x as? VE.Dot)?.e as? VE.Id)?.name?.let { fieldByName[it] } }.flatMap { listOfNotNull(it.outcome, it.hit, it.value) }
            "exc" !in ids && op.result?.name?.text !in ids && reads.isNotEmpty() && written.containsAll(reads) && ids.none { it in derived } &&
                ids.filter { it in freeNames }.all { f -> t.free.any { it.sidecarName == f || it.local.name == f } }
        }
    }

    private fun usesPhase(t: Task): Boolean {
        val calls = t.body.walk().filterIsInstance<Call>().filter(::modeled).map { modelRef(modelOf(it.fn, it.pos)) }.toSet()
        return guards.filter { s.callTarget(it.call.text) in calls }.any { g -> guardParams(g).any { it in derived && pathValue(it) } }
    }

    private fun preName(t: Task) = "${t.id.uppercase()}_PRE"

    /** The task's parameters in order: the key, the free values, the path values, the other captured values. */
    private fun taskParams(t: Task): List<Pair<String, String>> {
        val key = t.params.filter { it.name == "k" }
        val paths = if (usesPhase(t)) derived.keys.filter(::pathValue).map { lowerCamel(it) to "Int" } else emptyList()
        return key.map { it.name to vt(it.type) } + t.free.map { it.local.name to vt(it.local.type) } + paths + t.params.filter { it.name != "k" }.map { it.name to vt(it.type) }
    }

    private class Pre(
        val params: List<String>,
        val conjuncts: List<String>,
    )

    private fun precondition(t: Task): Pre {
        fun isConst(e: Expr) = e is Cmp && e.op == CmpOp.EQ && e.a is Var && (e.b is EnumLit || e.b is IntLit || e.b is BoolLit || e.b is NullLit)
        val consts = t.requires.filter(::isConst)
        val fixed = consts.map { ((it as Cmp).a as Var).local.name }.toSet()
        val ranges =
            (t.free.map { it.local } + t.params).filter { it.type is KType.EnumT && it.name !in fixed }.map { l ->
                val e = enumNames.getValue((l.type as KType.EnumT).name)
                "(" + e.entries.joinToString(" || ") { "${l.name} == $it" } + ")"
            }
        val phases = spawnFacts[t.id].orEmpty().map { (n, v) -> "$n == $v" }
        val others = t.requires.filterNot(::isConst).map { c -> operand(c, PREC_AND) }
        val holds = t.holds.map { h -> "(" + (h.cond?.let { "${expr(it)} ==> " } ?: "") + "${h.obligation}(${h.args.joinToString { expr(it) }}))" }
        val conjuncts = ranges + consts.map { expr(it) } + phases + others + holds
        val used = conjuncts.joinToString(" ")
        val params = taskParams(t).map { it.first }.filter { p -> Regex("(?<![\\w.])${Regex.escape(p)}(?!\\w)").containsMatchIn(used) }
        return Pre(params, conjuncts)
    }

    private fun taskMethod(t: Task) {
        val pre = precondition(t)
        w.comment("Task ${t.id}: the lambda at ${t.pos.line}:${t.pos.col} in ${t.inFunction}${if (t.via.isEmpty()) "" else " via ${t.via.joinToString()}"} (DESIGN-E 3.4)")
        line("define ${preName(t)}(${pre.params.joinToString()}) (", 0, Source.Kotlin(t.pos, "task precondition"))
        line(pre.conjuncts.ifEmpty { listOf("true") }.joinToString(" && ") + ")", 2)
        w.put("\n")
        val hasTr = taskEvents(t).isNotEmpty()
        method(t.id, MethodKind.TASK, t.id, t.pos) {
            val results = listOf("exc: Int") + listOfNotNull("tr: Ref".takeIf { hasTr })
            line("method ${t.id}(${taskParams(t).joinToString { (n, ty) -> "$n: $ty" }}) returns (${results.joinToString()})", 0, Source.Kotlin(t.pos))
            line("requires ${preName(t)}(${pre.params.joinToString()})", 2, Source.Kotlin(t.pos, "task precondition"))
            if (hasTr) line("ensures TR(tr)", 2, Source.Generated("TR"))
            line("ensures ${check(ClientIds.LD_NOLEAK, noObligations)}", 2, Source.Kotlin(t.pos, ClientIds.LD_NOLEAK), ClientIds.LD_NOLEAK)
            taskEnsures(t).forEach { en -> line("ensures ${check(en.id.text, translate(en.expr, Ctx.Unit(t)).print())}", 2, Source.Sidecar(en.pos, en.id.text), en.id.text) }
            line("{", 0)
            line("exc := NONE", 2)
            if (hasTr) line("TR_NEW(tr)", 2)
            UnitEmitter(t, null, t.body, hasTr).run()
            line("}", 0)
        }
        w.put("\n")
    }

    private fun entryMethod(e: EntryFun) {
        val name = e.kotlin.substringAfterLast('.')
        method(name, MethodKind.ENTRY, name, e.pos) {
            val params = e.params.map { it.name to vt(it.type) } + e.free.map { it.local.name to vt(it.local.type) }
            val results = listOf("exc: Int") + listOfNotNull(e.result?.let { "${it.name}: ${vt(it.type)}" }) + listOfNotNull("tr: Ref".takeIf { trace != null })
            line("method $name(${params.joinToString { (n, t) -> "$n: $t" }}) returns (${results.joinToString()})", 0, Source.Kotlin(e.pos))
            (e.params + e.free.map { it.local }).filter { it.type is KType.EnumT }.forEach { l ->
                val en = enumNames.getValue((l.type as KType.EnumT).name)
                line("requires ${en.entries.joinToString(" || ") { "${l.name} == $it" }}", 2, Source.Kotlin(l.pos, "enum range"))
            }
            if (trace != null) line("ensures TR(tr)", 2, Source.Generated("TR"))
            line("ensures ${check(ClientIds.LD_NOLEAK, noObligations)}", 2, Source.Kotlin(e.pos, ClientIds.LD_NOLEAK), ClientIds.LD_NOLEAK)
            op.items.filterIsInstance<EnsuresItem>().forEach { en ->
                line("ensures ${check(en.id.text, ensuresText(en))}", 2, Source.Sidecar(en.pos, en.id.text), en.id.text)
            }
            line("{", 0)
            line("exc := NONE", 2)
            e.result?.let { line("${it.name} := ${default(it.type)}", 2) }
            if (trace != null) line("TR_NEW(tr)", 2)
            UnitEmitter(null, e, e.body, trace != null).run()
            line("}", 0)
        }
    }

    /** An entry ensures in Viper: a read of a class field gets its read permission first (self-framing, DESIGN-E 5.1). */
    private fun ensuresText(en: EnsuresItem): String {
        val e = translate(en.expr, Ctx.Unit(null))
        val fields = m.classes.flatMap { c -> c.fields.map { it.name } }.toSet()

        fun frame(x: VE): VE =
            when {
                x is VE.Bin && (x.op == "&&" || x.op == "==>" || x.op == "||") -> x.copy(a = frame(x.a), b = frame(x.b))
                x is VE.Paren -> x.copy(e = frame(x.e))
                else -> {
                    val reads = x.walk().filterIsInstance<VE.Dot>().filter { it.field in fields && it.e is VE.Id && (it.e as VE.Id).name != "tr" }.map { it.print() }.distinct().toList()
                    reads.foldRight(x) { r, acc -> VE.Bin("&&", VE.Call("acc", listOf(VE.Id(r, -1), VE.Id("wildcard", -1)), -1), acc, -1) }
                }
            }
        return frame(e).print()
    }

    // ---- KVIR expressions ----

    /** Local names written as another name: aliases (one Viper variable for a value and the helper out it flows to). */
    private var alias: Map<String, String> = emptyMap()

    private fun root(name: String): String = alias[name]?.let(::root) ?: name

    private fun prec(e: Expr): Int =
        when (e) {
            is Elvis -> 0
            is Or -> 3
            is And -> PREC_AND
            is Cmp -> if (e.op == CmpOp.EQ || e.op == CmpOp.NE) 5 else 6
            is Arith -> 7
            is Not -> 9
            else -> 10
        }

    private fun expr(e: Expr): String =
        when (e) {
            is IntLit -> if (e.value < 0) "(${e.value})" else "${e.value}"
            is BoolLit -> "${e.value}"
            is NullLit -> if (e.type is KType.RefT) "null" else "NULL"
            is Var -> root(e.local.name)
            is EnumLit -> e.entry
            is Arith -> "${operand(e.a, 7)} ${e.op.symbol} ${operand(e.b, 8)}"
            is Cmp -> "${operand(e.a, prec(e) + 1)} ${e.op.symbol} ${operand(e.b, prec(e) + 1)}"
            is Not -> "!${operand(e.a, 9)}"
            is And -> "${operand(e.a, PREC_AND)} && ${operand(e.b, PREC_AND)}"
            is Or -> "${operand(e.a, 3)} || ${operand(e.b, 3)}"
            // `a ?: b` (a pure): Viper's conditional, the weakest operator.
            is Elvis -> "${operand(e.a, 6)} != ${if (e.a.type is KType.RefT) "null" else "NULL"} ? ${operand(e.a, 1)} : ${operand(e.b, 0)}"
            else -> fail(anchor, "expression ${e::class.simpleName} at ${e.pos.line}:${e.pos.col} is not emitted in a client module")
        }

    private fun operand(
        e: Expr,
        min: Int,
    ): String = if (prec(e) < min) "(${expr(e)})" else expr(e)

    /** [e] folded: `x != null` of a non-null x is true, `null != null` false, and `&&`, `||`, `!` of constants. */
    private fun fold(e: Expr): Expr =
        when (e) {
            is Cmp -> {
                val a = fold(e.a)
                val b = fold(e.b)
                val nullCmp = e.op == CmpOp.EQ || e.op == CmpOp.NE
                fun nonNull(x: Expr) = x !is NullLit && !nullable(x.type)
                when {
                    nullCmp && a is NullLit && b is NullLit -> BoolLit(e.op == CmpOp.EQ, e.pos)
                    nullCmp && (b is NullLit && nonNull(a) || a is NullLit && nonNull(b)) -> BoolLit(e.op == CmpOp.NE, e.pos)
                    else -> e.copy(a = a, b = b)
                }
            }
            is And -> {
                val a = fold(e.a)
                val b = fold(e.b)
                when {
                    a is BoolLit -> if (a.value) b else a
                    b is BoolLit -> if (b.value) a else b
                    else -> e.copy(a = a, b = b)
                }
            }
            is Or -> {
                val a = fold(e.a)
                val b = fold(e.b)
                when {
                    a is BoolLit -> if (a.value) a else b
                    b is BoolLit -> if (b.value) b else a
                    else -> e.copy(a = a, b = b)
                }
            }
            is Not -> fold(e.a).let { if (it is BoolLit) BoolLit(!it.value, e.pos) else e.copy(a = it) }
            else -> e
        }

    private fun nullable(t: KType) = (t as? KType.Abs)?.nullable ?: (t as? KType.RefT)?.nullable ?: (t as? KType.EnumT)?.nullable ?: false

    /** [stmts] with every If condition folded and the branches of a constant condition chosen (CE6). */
    private fun foldBlock(stmts: List<Stmt>): List<Stmt> =
        stmts.flatMap { st ->
            when (st) {
                is If -> {
                    val c = fold(st.cond)
                    if (c is BoolLit) foldBlock(if (c.value) st.then else st.els) else listOf(st.copy(cond = c, then = foldBlock(st.then), els = foldBlock(st.els)))
                }
                is Try -> listOf(st.copy(body = foldBlock(st.body), catches = st.catches.map { it.copy(body = foldBlock(it.body)) }, fin = st.fin?.let(::foldBlock)))
                is While -> listOf(st.copy(body = foldBlock(st.body)))
                is Inline -> listOf(st.copy(body = foldBlock(st.body)))
                else -> listOf(st)
            }
        }

    /** How many Inline nodes of each helper the module has: a helper with several gets the call line in its names. */
    private val sites: Map<String, Int> = (m.entries.flatMap { it.body } + m.tasks.flatMap { it.body }).walk().filterIsInstance<Inline>().groupingBy { it.fn }.eachCount()

    // ---- one method body ----

    /**
     * Emits the body of task [task] or entry [entryFun]: the structured KVIR in the exception-flag form of DESIGN-E 3.5.
     * A call that may throw returns its kind in a flag `e_...`; the code after a statement that may raise runs under
     * `if (exc == NONE ...)`; a return or break that is not the last step of its function or loop sets a flag.
     */
    private inner class UnitEmitter(
        val task: Task?,
        val entryFun: EntryFun?,
        rawBody: List<Stmt>,
        val hasTr: Boolean,
    ) {
        val body = foldBlock(rawBody)
        val names = mutableSetOf<String>()
        val declared = mutableSetOf<String>()

        /** Parent links: statement -> (its block, the statement that holds the block, how). */
        val parent = java.util.IdentityHashMap<Stmt, Triple<List<Stmt>, Stmt?, String>>()

        /** The function a Return leaves (an Inline, or null for the unit) and the loop a Break leaves. */
        val retTarget = java.util.IdentityHashMap<Return, Inline?>()
        val brkLoop = java.util.IdentityHashMap<Break, While>()
        val retFlag = java.util.IdentityHashMap<Any, String>()
        val brkFlag = java.util.IdentityHashMap<While, String>()
        val unitKey = Any()

        /** The helper outs a call or new object declares (A2), so the helper does not declare them first. */
        val valueOuts = mutableSetOf<String>()

        init {
            fun index(
                stmts: List<Stmt>,
                holder: Stmt?,
                how: String,
                fn: Inline?,
                loop: While?,
            ) {
                for (st in stmts) {
                    parent[st] = Triple(stmts, holder, how)
                    when (st) {
                        is If -> {
                            index(st.then, st, "then", fn, loop)
                            index(st.els, st, "else", fn, loop)
                        }
                        is Try -> {
                            index(st.body, st, "try", fn, loop)
                            st.catches.forEach { index(it.body, st, "catch", fn, loop) }
                            st.fin?.let { index(it, st, "finally", fn, loop) }
                        }
                        is While -> index(st.body, st, "while", fn, st)
                        is Inline -> index(st.body, st, "inline", st, null)
                        is Return -> retTarget[st] = fn
                        is Break -> brkLoop[st] = loop ?: fail(anchor, "break outside a loop at ${st.pos.line}")
                        else -> Unit
                    }
                }
            }
            index(body, null, "unit", null, null)
            parent.keys.filterIsInstance<Return>().filter { !tail(it) }.sortedBy { it.pos.line * 10000 + it.pos.col }.forEach { r ->
                val t = retTarget[r]
                val key: Any = t ?: unitKey
                if (key !in retFlag) retFlag[key] = fresh("ret_" + posName(t?.pos ?: (task?.pos ?: entryFun!!.pos)))
            }
            parent.keys.filterIsInstance<Break>().forEach { b ->
                val l = brkLoop.getValue(b)
                if (l !in brkFlag) brkFlag[l] = fresh("brk_" + posName(l.pos))
            }
            alias = aliases()
        }

        fun posName(p: Pos) = "${p.line}_${p.col}"

        fun fresh(base: String): String {
            var n = base
            var c = 'b'
            while (!names.add(n)) n = base + c++
            return n
        }

        /** The name suffix of the helpers inlined around [st] at several sites: `_at<line>` each (DESIGN-E 5.1 item 1). */
        fun suffix(st: Stmt): String {
            val chain = mutableListOf<Inline>()
            var cur: Stmt? = parent[st]?.second
            while (cur != null) {
                if (cur is Inline) chain += cur
                cur = parent[cur]?.second
            }
            return chain.reversed().filter { (sites[it.fn] ?: 0) > 1 }.joinToString("") { "_at${it.pos.line}" }
        }

        // ---- returns, tail positions and aliases ----

        /** True if the then-branch [stmts] ends with a jump, so G2 moves the statements after its If into the else. */
        fun endsWithJump(stmts: List<Stmt>) = stmts.lastOrNull().let { it is Return || it is Break || it is Throw }

        /**
         * True if nothing runs after [r] before control leaves its function: [r] is last in its block, and so is each
         * statement around it up to the function (statements after an If whose then-branch ends with a jump move into its
         * else, G2); no loop and no finally lies in between.
         */
        fun tail(r: Return): Boolean {
            val target = retTarget[r]
            var cur: Stmt = r
            var skipFollowers = false
            while (true) {
                val (block, holder, how) = parent[cur] ?: return true
                if (!skipFollowers && block.last() !== cur) return false
                skipFollowers = false
                when {
                    holder == null -> return target == null
                    holder === target -> return true
                    holder is While -> return false
                    holder is Try && holder.fin != null && how != "finally" -> return false
                    // G2: the statements after an If whose then-branch ends with a jump run in its else.
                    holder is If && how == "then" && holder.els.isEmpty() && endsWithJump(holder.then) -> {
                        skipFollowers = true
                        cur = holder
                    }
                    else -> cur = holder
                }
            }
        }

        /**
         * One Viper variable for a value and the place it flows to: a helper's out that the next statement returns
         * (A1), and a call result or new object that its helper returns right away into a not yet declared out (A2).
         */
        fun aliases(): Map<String, String> {
            val a = mutableMapOf<String, String>()
            // The statement before each tail return of a local, with the out the return goes to.
            val pairs =
                parent.keys.mapNotNull { st ->
                    val block = parent.getValue(st).first
                    val next = block.getOrNull(block.indexOfFirst { it === st } + 1) as? Return ?: return@mapNotNull null
                    val v = (next.e as? Var)?.local?.name ?: return@mapNotNull null
                    if (!tail(next)) return@mapNotNull null
                    val target = retTarget[next]
                    val out = target?.out?.name ?: entryFun?.result?.name ?: return@mapNotNull null
                    Triple(st, v, target to out)
                }
            // A1: a helper out that the next statement returns is the out it returns to.
            for ((st, v, t) in pairs) if (st is Inline && st.out?.name == v) a[v] = t.second
            // A2: a call result or new object that its helper returns right away is the helper's out, if not forwarded.
            for ((st, v, t) in pairs) {
                val fn = t.first ?: continue
                val defines = st is Call && st.outs.singleOrNull()?.name == v || st is New && st.v.name == v
                if (defines && firstOf(fn, st) && fn.out!!.name !in a) {
                    a[v] = fn.out.name
                    valueOuts += fn.out.name
                }
            }
            return a
        }

        /** True if [st] is the first statement of [fn]'s body, or of a Try that is its first statement. */
        fun firstOf(
            fn: Inline,
            st: Stmt,
        ): Boolean = fn.body.firstOrNull() === st || (fn.body.firstOrNull() as? Try)?.body?.firstOrNull() === st

        // ---- raised kinds and pending jumps ----

        val raisesMemo = java.util.IdentityHashMap<Any, Set<String>>()

        /** The exception kinds that can leave [st] (A1 Errors included: MAY_ERR is a switch, not a code fact). */
        fun raises(st: Stmt): Set<String> =
            raisesMemo.getOrPut(st) {
                when (st) {
                    is Call -> if (!modeled(st)) emptySet() else modelOf(st.fn, st.pos).let { md -> if (md.throws) (md.kinds + ERR).toSet() else emptySet() }
                    is Spawn -> (st.throws.map(::kindName) + ERR).toSet()
                    is Throw -> st.kind?.let { setOf(kindName(it)) } ?: caughtKinds(st)
                    is If -> raises(st.then) + raises(st.els)
                    is While -> raises(st.body)
                    is Inline -> raises(st.body)
                    is Try -> {
                        var left = raises(st.body)
                        val fromCatches = mutableSetOf<String>()
                        st.catches.forEach { c ->
                            val k = left intersect kindsOf(c)
                            left = left - k
                            if (k.isNotEmpty()) fromCatches += raises(c.body)
                        }
                        left + fromCatches + (st.fin?.let { raises(it) } ?: emptySet())
                    }
                    else -> emptySet()
                }
            }

        fun raises(stmts: List<Stmt>): Set<String> = stmts.flatMap { raises(it) }.toSet()

        fun kindsOf(c: Catch) = c.kinds.map(::kindName).toSet()

        /** The kinds a rethrow `throw` (no kind) in a catch rethrows: those its catch takes. */
        fun caughtKinds(t: Throw): Set<String> {
            var cur: Stmt = t
            while (true) {
                val (_, holder, how) = parent[cur] ?: return kinds.toSet()
                if (holder is Try && how == "catch") {
                    val c = holder.catches.first { c -> c.body.walk().any { it === t } }
                    return raises(holder.body) intersect kindsOf(c)
                }
                cur = holder ?: return kinds.toSet()
            }
        }

        /** The flags that [st] may set and that are still pending after it (returns and breaks that leave [st]). */
        fun jumps(st: Stmt): List<String> {
            val inside = listOf(st).walk().toList()
            val out = mutableListOf<String>()
            inside.filterIsInstance<Return>().filter { !tail(it) }.forEach { r ->
                val t = retTarget[r]
                if (t == null || inside.none { it === t }) out += retFlag.getValue(t ?: unitKey)
            }
            inside.filterIsInstance<Break>().forEach { b ->
                val l = brkLoop.getValue(b)
                if (inside.none { it === l }) out += brkFlag.getValue(l)
            }
            return out.distinct()
        }

        /** The flags declared in the block of [stmts]: those of returns to [owner] and of loops in [stmts], first use first. */
        fun flagsIn(
            stmts: List<Stmt>,
            owner: Any,
        ): List<String> {
            val all = stmts.walk().toList()
            val out = mutableListOf<Pair<Pos, String>>()
            all.filterIsInstance<Return>().filter { !tail(it) && (retTarget[it] ?: unitKey) === owner }.forEach { out += it.pos to retFlag.getValue(owner) }
            all.filterIsInstance<Break>().forEach { b ->
                val l = brkLoop.getValue(b)
                if (stmts.any { it === l }) out += b.pos to brkFlag.getValue(l)
            }
            return out.sortedBy { it.first.line * 10000 + it.first.col }.map { it.second }.distinct()
        }

        // ---- emission state ----

        inner class Frame(
            val indent: Int,
            /** The innermost function a return leaves: the Inline (or null for the unit), its out's Viper name and type. */
            val fn: Inline?,
            val out: String?,
            val outType: KType?,
            /** The try whose catches a direct call of this block dispatches to (flag mode). */
            val tryCtx: Try?,
            /** Inside the finally of these tries: a raise there cancels the returns pending through them. */
            val fins: List<Try>,
            /** The flag of the exception the enclosing catch caught (for a rethrow), or `exc` saved in exc mode. */
            val caught: String?,
            /** True inside a try body in exc mode: a dispatch there can be undone by a catch, so no raiseAll fact. */
            val excMode: Boolean,
            var env: Env,
        ) {
            fun copy(
                indent: Int = this.indent,
                fn: Inline? = this.fn,
                out: String? = this.out,
                outType: KType? = this.outType,
                tryCtx: Try? = this.tryCtx,
                fins: List<Try> = this.fins,
                caught: String? = this.caught,
                excMode: Boolean = this.excMode,
                env: Env = this.env,
            ) = Frame(indent, fn, out, outType, tryCtx, fins, caught, excMode, env)
        }

        fun run() {
            body.walk().flatMap { locals(it) }.forEach { names += it.name }
            val result = entryFun?.result
            entryFun?.params?.forEach { names += it.name; declared += it.name }
            entryFun?.free?.forEach { names += it.local.name; declared += it.local.name }
            task?.params?.forEach { names += it.name; declared += it.name }
            task?.free?.forEach { names += it.local.name; declared += it.local.name }
            result?.let { names += it.name; declared += it.name }
            val env = Env(first = fields.filter { it.event.form == EventForm.FIRST }.associate { it.event.name.text to First.UNSET })
            val f = Frame(2, null, result?.name, result?.type, null, emptyList(), null, false, env)
            block(body, f, unitKey)
        }

        fun block(
            stmts: List<Stmt>,
            f: Frame,
            owner: Any,
        ) = seq(stmts, 0, f, owner)

        /** Emits [stmts] from [i]: each statement, then the rest under the guard of what it may raise or leave (G1). */
        fun seq(
            stmts: List<Stmt>,
            i: Int,
            f: Frame,
            owner: Any,
        ) {
            if (i >= stmts.size) return
            val st = stmts[i]
            flagsIn(listOf(st), owner).filter { it !in declared }.forEach { declareFlag(it, f) }
            val rest = stmts.drop(i + 1)
            // G2: `if (c) { ...; return }` followed by more: the rest is the else-branch.
            if (st is If && st.els.isEmpty() && endsWithJump(st.then) && rest.isNotEmpty()) {
                emitIf(st, f, owner) { g -> seq(stmts, i + 1, g, owner) }
                return
            }
            // A call, spawn or throw right in a try body (flag mode): the rest of the body is its success branch.
            if (f.tryCtx != null && (st is Call || st is Spawn || st is Throw) && f.tryCtx.body.any { it === st }) {
                stmt(st, f, owner, if (rest.all(::emitsNothing)) null else { g -> seq(stmts, i + 1, g, owner) })
                return
            }
            stmt(st, f, owner, null)
            if (rest.all(::emitsNothing)) return
            val raised = raises(st).isNotEmpty()
            val left = jumps(st)
            if (!raised && left.isEmpty()) return seq(stmts, i + 1, f, owner)
            flagsIn(rest, owner).filter { it !in declared }.forEach { declareFlag(it, f) }
            val guard = listOfNotNull("exc == NONE".takeIf { raised }) + left.map { "!$it" }
            line("if (${guard.joinToString(" && ")}) {", f.indent, Source.Kotlin(rest.first().pos))
            val env = if (raised) f.env.copy(none = f.env.none + f.env.raiseAll) else f.env
            val g = f.copy(indent = f.indent + 2, env = env)
            seq(stmts, i + 1, g, owner)
            mergeFirst(f, g)
            line("}", f.indent)
        }

        /** True if [st] writes no Viper: a last return of the value its out already holds. */
        fun emitsNothing(st: Stmt): Boolean {
            if (st !is Return || !tail(st)) return false
            val target = retTarget[st]
            val out = if (target == null) entryFun?.result?.name else target.out?.let { root(it.name) }
            return st.e == null || st.e.let { it is Var && root(it.local.name) == out }
        }

        fun declareFlag(
            flag: String,
            f: Frame,
        ) {
            declared += flag
            line("var $flag: Bool := false", f.indent, null, "a pending ${if (flag.startsWith("ret_")) "return" else "break"}")
        }

        /** After a nested block: first-event fields set inside it are set or maybe set after it. */
        fun mergeFirst(
            outer: Frame,
            inner: Frame,
        ) {
            val m = outer.env.first.toMutableMap()
            inner.env.first.forEach { (k, v) ->
                val was = m[k] ?: First.UNSET
                m[k] = if (was == First.UNSET && v != First.UNSET) First.MAYBE else was
            }
            outer.env = outer.env.copy(first = m)
        }

        fun ind(f: Frame) = f.indent

        /** Emits [st]; [success] emits what runs after it in its try body (a call's success branch), or is null. */
        fun stmt(
            st: Stmt,
            f: Frame,
            owner: Any,
            success: ((Frame) -> Unit)?,
        ) {
            when (st) {
                is Let -> {
                    declared += st.v.name
                    names += st.v.name
                    line("var ${st.v.name}: ${vt(st.v.type)} := ${expr(st.e)}", f.indent, Source.Kotlin(st.pos))
                }
                is kv.kvir.Assign -> line("${root(st.v.name)} := ${expr(st.e)}", f.indent, Source.Kotlin(st.pos))
                is If -> emitIf(st, f, owner, null)
                is Call -> call(st, f, success)
                is Spawn -> spawn(st, f, success)
                is Throw -> throwStmt(st, f, success)
                is New -> newObject(st, f)
                is Inline -> inline(st, f)
                is Return -> ret(st, f)
                is Break -> line("${brkFlag.getValue(brkLoop.getValue(st))} := true", f.indent, Source.Kotlin(st.pos), "break")
                is Try -> tryStmt(st, f, owner)
                is While -> loop(st, f, owner)
                else -> fail(anchor, "${st::class.simpleName} at ${st.pos.line}:${st.pos.col} is not emitted in a client module")
            }
        }

        fun emitIf(
            st: If,
            f: Frame,
            owner: Any,
            elseRest: ((Frame) -> Unit)?,
        ) {
            line("if (${expr(st.cond)}) {", f.indent, Source.Kotlin(st.pos))
            val g = f.copy(indent = f.indent + 2, env = facts(f.env, st.cond, true))
            block(st.then, g, owner)
            mergeFirst(f, g)
            if (st.els.isNotEmpty() || elseRest != null) {
                line("} else {", f.indent)
                val h = f.copy(indent = f.indent + 2, env = facts(f.env, st.cond, false))
                block(st.els, h, owner)
                elseRest?.invoke(h)
                mergeFirst(f, h)
            }
            line("}", f.indent)
        }

        /** [env] with what [c] says when it is [value]: nullness of locals (DESIGN-E 3.4 trace facts). */
        fun facts(
            env: Env,
            c: Expr,
            value: Boolean,
        ): Env =
            when {
                c is Not -> facts(env, c.a, !value)
                c is And && value -> facts(facts(env, c.a, true), c.b, true)
                c is Or && !value -> facts(facts(env, c.a, false), c.b, false)
                c is Cmp && (c.op == CmpOp.EQ || c.op == CmpOp.NE) && c.a is Var && c.b is NullLit ->
                    env.copy(nullness = env.nullness + (root((c.a as Var).local.name) to ((c.op == CmpOp.EQ) == value)))
                else -> env
            }

        // ---- calls and dispatch ----

        fun call(
            c: Call,
            f: Frame,
            success: ((Frame) -> Unit)?,
        ) {
            if (!modeled(c)) {
                // An arbitrary value: a Viper local that is declared and never assigned.
                val o = c.outs.single()
                val r = root(o.name)
                declared += r
                names += r
                line("var $r: ${vt(o.type)}", f.indent, Source.Kotlin(c.pos), "an arbitrary value: a racy read, or a null the model's type has not")
                return
            }
            val md = modelOf(c.fn, c.pos)
            val sfx = suffix(c)
            val flag = if (md.throws) fresh("e_${posName(c.pos)}$sfx") else null
            flag?.let {
                declared += it
                line("var $it: Int", f.indent)
            }
            val outs =
                md.results.mapIndexed { i, (rn, rt, _) ->
                    val o = c.outs.getOrNull(i)
                    if (o != null) {
                        val r = root(o.name)
                        if (r !in declared) {
                            declared += r
                            names += r
                            line("var $r: ${vt(o.type)}", f.indent)
                        }
                        r
                    } else {
                        fresh("${rn}_${posName(c.pos)}$sfx").also {
                            declared += it
                            line("var $it: $rt", f.indent)
                        }
                    }
                }
            guards.filter { s.callTarget(it.call.text) === modelRef(md) }.forEach { g ->
                val args = guardArgs(g, task)
                line("assert ${check(g.id.text, "GUARD_${g.id.text}(${args.joinToString()})")}", f.indent, Source.Kotlin(c.pos, g.id.text), g.id.text)
            }
            val lhs = listOfNotNull(flag) + outs
            val source = if (md.decl?.consumes != null) Source.Kotlin(c.pos, ClientIds.LD_OWN) else Source.Kotlin(c.pos)
            line("${if (lhs.isEmpty()) "" else lhs.joinToString() + " := "}${md.name}(${c.args.joinToString { expr(it) }})", f.indent, source, "${c.fn} @${c.pos.line}")
            if (hasTr && md in eventModels) {
                val ps = eventParams(md)
                val byName = md.params.map { it.first }.zip(c.args.map { expr(it) }).toMap() + listOfNotNull(flag?.let { "e" to it }) + md.results.map { it.first }.zip(outs).toMap()
                line("${evMacro(md)}(${ps.joinToString { if (it == "tr") "tr" else byName.getValue(it) }})", f.indent)
                recordFirst(md, flag, outs.firstOrNull(), f)
            }
            if (flag != null) dispatch(flag, raises(c), f, success, c)
        }

        /** A first-event call: the field is set by this call if it was unset, else it stays as it was. */
        fun recordFirst(
            md: Model,
            flag: String?,
            out: String?,
            f: Frame,
        ) {
            val firsts = eventsOf(md).filter { it.form == EventForm.FIRST }.map { it.name.text }
            if (firsts.isEmpty()) return
            val first = f.env.first.toMutableMap()
            val by = f.env.firstBy.toMutableMap()
            firsts.forEach { n ->
                when (first[n]) {
                    First.UNSET -> {
                        first[n] = First.SET
                        by[n] = (flag ?: "") to out
                    }
                    First.MAYBE -> {
                        first[n] = First.SET
                        by.remove(n)
                    }
                    else -> Unit
                }
            }
            f.env = f.env.copy(first = first, firstBy = by)
        }

        /** The test of catch [c] for the kinds [k] of flag [e] when [remaining] are still possible. */
        fun test(
            c: Catch,
            k: Set<String>,
            remaining: Set<String>,
            e: String,
        ): String? =
            when {
                k == remaining -> null
                c.type == "Exception" -> "IS_EXCEPTION($e)"
                else -> order(k).joinToString(" || ") { "$e == $it" }
            }

        /** The catches a direct statement of the current try body dispatches to (flag mode), with what they take. */
        fun handlers(
            f: Frame,
            st: Stmt,
        ): List<Catch> = f.tryCtx?.takeIf { t -> t.body.any { it === st } }?.catches.orEmpty()

        /**
         * The flag dispatch of DESIGN-E 3.5 after a call, spawn or throw [st] with flag [e]: the success branch, a branch
         * per catch that takes some kind, and the propagation `exc := e` of the kinds no catch takes.
         */
        fun dispatch(
            e: String,
            possible: Set<String>,
            f: Frame,
            success: ((Frame) -> Unit)?,
            st: Stmt,
        ) {
            var remaining = order(possible).toSet()
            val branches = mutableListOf<Pair<String?, (Frame) -> Unit>>()
            val swallowed = mutableListOf<String>()
            var raiseAll = true
            for (c in handlers(f, st)) {
                val k = remaining intersect kindsOf(c)
                if (k.isEmpty()) continue
                val t = test(c, k, remaining, e)
                remaining = remaining - k
                if (c.body.isEmpty()) {
                    raiseAll = false
                    t?.let { swallowed += it }
                } else {
                    if (c.body.lastOrNull() !is Throw) raiseAll = false
                    branches += t to { g -> catchBody(c, g.copy(caught = e)) }
                }
            }
            if (remaining.isNotEmpty()) {
                val cond = if (swallowed.isEmpty()) null else swallowed.joinToString(" && ", transform = ::negate)
                branches += cond to { g -> raise(e, g, st.pos) }
            }
            if (success != null) {
                val noneEnv = f.env.copy(none = f.env.none + e)
                line("if ($e == NONE) {", f.indent, Source.Kotlin(st.pos))
                val g = f.copy(indent = f.indent + 2, env = noneEnv)
                success(g)
                mergeFirst(f, g)
                branches.forEachIndexed { i, (cond, body) ->
                    line(if (cond == null && i == branches.lastIndex) "} else {" else "} elseif (${cond ?: "$e != NONE"}) {", f.indent)
                    val h = f.copy(indent = f.indent + 2)
                    body(h)
                    mergeFirst(f, h)
                }
                line("}", f.indent)
            } else if (branches.size == 1) {
                val (cond, body) = branches.single()
                line("if ($e != NONE${cond?.let { " && $it" } ?: ""}) {", f.indent, Source.Kotlin(st.pos))
                val h = f.copy(indent = f.indent + 2)
                body(h)
                mergeFirst(f, h)
                line("}", f.indent)
            } else if (branches.isNotEmpty()) {
                line("if ($e != NONE) {", f.indent, Source.Kotlin(st.pos))
                branches.forEachIndexed { i, (cond, body) ->
                    val head =
                        when {
                            i == 0 -> "if (${cond!!}) {"
                            cond == null -> "} else {"
                            else -> "} elseif ($cond) {"
                        }
                    line(head, f.indent + 2)
                    val h = f.copy(indent = f.indent + 4)
                    body(h)
                    mergeFirst(f, h)
                }
                line("}", f.indent + 2)
                line("}", f.indent)
            }
            if (raiseAll && !f.excMode && success == null) f.env = f.env.copy(raiseAll = f.env.raiseAll + e)
        }

        /** The negation of catch test [t]: `!IS_EXCEPTION(e)`, `e != REE`, or `!(...)` for a disjunction. */
        fun negate(t: String): String =
            when {
                t.startsWith("IS_EXCEPTION(") -> "!$t"
                " || " !in t && t.count { it == '=' } == 2 && " == " in t -> t.replace(" == ", " != ")
                else -> "!($t)"
            }

        /** `exc := value`; in a finally, a raise cancels the returns pending through it (DESIGN-E 3.5 item 2). */
        fun raise(
            value: String,
            f: Frame,
            pos: Pos,
        ) {
            line("exc := $value", f.indent, Source.Kotlin(pos))
            for (t in f.fins) {
                val inside = (t.body + t.catches.flatMap { it.body }).walk().filterIsInstance<Return>().filter { !tail(it) }
                inside.map { retTarget[it] ?: unitKey }.distinct().forEach { key ->
                    line("${retFlag.getValue(key)} := false", f.indent)
                    val target = key as? Inline
                    val out = if (target == null) entryFun?.result?.name else root(target.out?.name ?: return@forEach)
                    val type = target?.out?.type ?: entryFun?.result?.type
                    if (out != null && type != null) line("$out := ${default(type)}", f.indent)
                }
            }
        }

        fun catchBody(
            c: Catch,
            f: Frame,
        ) = block(c.body, f.copy(tryCtx = null), Any())

        fun throwStmt(
            t: Throw,
            f: Frame,
            success: ((Frame) -> Unit)?,
        ) {
            val value = t.kind?.let(::kindName) ?: f.caught ?: fail(anchor, "a rethrow outside a catch at ${t.pos.line}")
            val hs = handlers(f, t)
            val kind = t.kind?.let(::kindName)
            val c = if (kind != null) hs.firstOrNull { kind in kindsOf(it) } else null
            if (c != null) catchBody(c, f.copy(caught = kind)) else raise(value, f, t.pos)
        }

        // ---- spawn ----

        fun spawn(
            sp: Spawn,
            f: Frame,
            success: ((Frame) -> Unit)?,
        ) {
            val md = modelOf(sp.model, sp.pos)
            val t = m.tasks.single { it.id == sp.task }
            val sfx = suffix(sp)
            val e = fresh("e_${posName(sp.pos)}$sfx")
            val dropped = fresh("dropped_${posName(sp.pos)}$sfx")
            declared += listOf(e, dropped)
            line("var $e: Int", f.indent)
            line("var $dropped: Bool", f.indent)
            line("$e, $dropped := ${md.name}()", f.indent, Source.Kotlin(sp.pos), "${sp.model} @${sp.pos.line}")
            // The path values the spawn site fixes go into the task precondition (first pass, DESIGN-E 3.4).
            val known = knownValues(f.env)
            val paths = derived.keys.filter(::pathValue).mapNotNull { n -> choose(derived.getValue(n).second, known)?.let { lowerCamel(n) to it.print() } }.toMap()
            if (usesPhase(t)) spawnFacts[t.id] = paths
            val pre = precondition(t)
            val byParam = taskParams(t).map { it.first }.zip(taskArgs(t, sp)).toMap()
            val counts = eventsOf(md).filter { it.form == EventForm.COUNT }
            val ok: (Frame) -> Unit = { g ->
                if (hasTr) counts.forEach { c -> line("tr.${c.name.text} := tr.${c.name.text} + 1", g.indent) }
                line("if (!$dropped) {", g.indent)
                line("exhale ${preName(t)}(${pre.params.joinToString { byParam.getValue(it) }})", g.indent + 2, Source.Kotlin(sp.pos, ClientIds.LD_TRANSFER), "spawn ${t.id}")
                line("}", g.indent)
                success?.invoke(g)
            }
            dispatch(e, raises(sp), f, ok, sp)
        }

        /** The arguments of [t]'s parameters ([taskParams]) at spawn [sp]. */
        fun taskArgs(
            t: Task,
            sp: Spawn,
        ): List<String> {
            val args = t.params.map { it.name }.zip(sp.args.map { expr(it) }).toMap()
            return taskParams(t).map { (p, _) ->
                when {
                    p in args -> args.getValue(p)
                    t.free.any { it.local.name == p } -> p
                    else -> derived.keys.first { lowerCamel(it) == p }.let { d -> if (task == null) "$d(${(listOf("tr") + derivedArgs(d)).joinToString()})" else p }
                }
            }
        }

        /** The values the facts give the first-event fields and the constants (for [choose]). */
        fun knownValues(env: Env): Map<String, Long> {
            val known = mutableMapOf<String, Long>()
            known += OUTCOMES
            m.enums.forEach { e -> e.entries.forEachIndexed { i, n -> known[n] = i.toLong() } }
            env.first.forEach { (n, st) ->
                if (st != First.SET) return@forEach
                val (flag, out) = env.firstBy[n] ?: return@forEach
                if (flag.isNotEmpty() && flag !in env.none) return@forEach
                val f = fieldByName.getValue(n)
                val nullableResult = f.valueType == "Ref"
                known[n] =
                    if (!nullableResult) {
                        OUTCOMES.getValue("OK")
                    } else {
                        when (out?.let { env.nullness[it] }) {
                            true -> OUTCOMES.getValue("MISS")
                            false -> OUTCOMES.getValue("HIT")
                            null -> return@forEach
                        }
                    }
            }
            return known
        }

        // ---- other statements ----

        fun newObject(
            n: New,
            f: Frame,
        ) {
            val r = root(n.v.name)
            val name = if (r !in declared) r else n.v.name
            declared += name
            names += name
            line("var $name: Ref := new(${n.cls.fields.joinToString { it.name }})", f.indent, Source.Kotlin(n.pos))
            n.cls.fields.forEach { fld -> n.init[fld.name]?.let { line("$name.${fld.name} := ${expr(it)}", f.indent) } }
            if (name != r) line("$r := $name", f.indent)
        }

        fun inline(
            i: Inline,
            f: Frame,
        ) {
            val out = i.out
            val r = out?.let { root(it.name) }
            w.comment("${i.fn} @${i.pos.line}:${i.pos.col} (inlined)", f.indent)
            if (r != null && r !in declared && out.name !in valueOuts) {
                declared += r
                names += r
                line("var $r: ${vt(out.type)}", f.indent)
            }
            val g = f.copy(fn = i, out = r, outType = out?.type, tryCtx = null)
            block(i.body, g, i)
            f.env = g.env
        }

        fun ret(
            r: Return,
            f: Frame,
        ) {
            val target = retTarget[r]
            val out = if (target == null) entryFun?.result?.name else target.out?.let { root(it.name) }
            r.e?.let { v ->
                val value = expr(v)
                if (value != out) line("$out := $value", f.indent, Source.Kotlin(r.pos), "return")
            }
            if (!tail(r)) line("${retFlag.getValue(target ?: unitKey)} := true", f.indent, Source.Kotlin(r.pos))
        }

        fun tryStmt(
            t: Try,
            f: Frame,
            owner: Any,
        ) {
            // Flag mode: every statement the catches could take is right in the body. Else exc mode (one dispatch at the end).
            val caught = t.catches.flatMap { kindsOf(it) }.toSet()
            val excMode = t.body.any { st -> st !is Call && st !is Spawn && st !is Throw && (raises(st) intersect caught).isNotEmpty() }
            w.comment("try @${t.pos.line}", f.indent)
            if (!excMode) {
                val g = f.copy(tryCtx = t.takeIf { it.catches.isNotEmpty() })
                block(t.body, g, owner)
                f.env = g.env
            } else {
                val g = f.copy(tryCtx = null, excMode = true)
                block(t.body, g, owner)
                f.env = g.env
                excDispatch(t, f)
            }
            t.fin?.let { fin ->
                if (fin.dropLast(1).any { raises(it).isNotEmpty() || jumps(it).isNotEmpty() }) {
                    fail(anchor, "a finally block whose statements need guards (${t.pos.line}:${t.pos.col}) is not emitted yet")
                }
                w.comment("finally", f.indent)
                val g = f.copy(fins = f.fins + t, tryCtx = null)
                block(fin, g, owner)
                f.env = g.env
            }
        }

        /** Exc mode: after the body, the catches test `exc` (DESIGN-E 3.5); a catch clears it and runs. */
        fun excDispatch(
            t: Try,
            f: Frame,
        ) {
            var remaining = raises(t.body).let { order(it).toSet() }
            val branches = mutableListOf<Pair<String?, Catch>>()
            for (c in t.catches) {
                val k = remaining intersect kindsOf(c)
                if (k.isEmpty()) continue
                branches += test(c, k, remaining, "exc") to c
                remaining = remaining - k
            }
            branches.forEachIndexed { i, (cond, c) ->
                val head = (if (i == 0) "if (" else "} elseif (") + "exc != NONE" + (cond?.let { " && $it" } ?: "") + ") {"
                line(head, f.indent, Source.Kotlin(c.pos))
                val rethrows = c.body.walk().any { it is Throw && it.kind == null }
                val saved = if (rethrows) fresh("caught_${posName(c.pos)}").also { line("var $it: Int := exc", f.indent + 2) } else null
                line("exc := NONE", f.indent + 2)
                val g = f.copy(indent = f.indent + 2, caught = saved)
                catchBody(c, g)
                mergeFirst(f, g)
            }
            if (branches.isNotEmpty()) line("}", f.indent)
        }

        // ---- loops ----

        fun loop(
            l: While,
            f: Frame,
            owner: Any,
        ) {
            val raised = raises(l.body)
            val rets = l.body.walk().filterIsInstance<Return>().filter { !tail(it) }.map { retFlag.getValue(retTarget[it] ?: unitKey) }.distinct()
            val brk = brkFlag[l]
            val cond = listOfNotNull("exc == NONE".takeIf { raised.isNotEmpty() }) + rets.map { "!$it" } + listOfNotNull(brk?.let { "!$it" }) + expr(l.cond)
            line("while (${cond.joinToString(" && ")})", f.indent, Source.Kotlin(l.pos))
            loopFrame(l, f, raised)
            line("{", f.indent)
            val assigned = l.body.walk().flatMap { st -> (locals(st) + listOfNotNull((st as? kv.kvir.Assign)?.v)).map { root(it.name) } }.toSet()
            val env = f.env.copy(nullness = f.env.nullness - assigned)
            val g = f.copy(indent = f.indent + 2, env = env)
            block(l.body, g, owner)
            line("}", f.indent)
            f.env = f.env.copy(nullness = f.env.nullness - assigned)
            mergeFirst(f, g)
        }

        /** The loop invariants: the tool frame (DESIGN-E 6.6, 6.8), the hole lines, and the measure. */
        fun loopFrame(
            l: While,
            f: Frame,
            raised: Set<String>,
        ) {
            val i = f.indent + 2
            val frame = Source.Kotlin(l.pos, ClientIds.FRAME)
            if (hasTr) {
                val calls = l.body.walk().filterIsInstance<Call>().filter(::modeled).map { modelOf(it.fn, it.pos) }.filter { it in eventModels }.distinct()
                val written = mutableListOf<String>()
                val readOnly = mutableListOf<String>()
                calls.forEach { md ->
                    groups(md).forEach { g ->
                        when {
                            g.form == EventForm.FIRST -> {
                                val n = g.events.single().name.text
                                if (f.env.first[n] == First.SET) readOnly += n else written += fieldsOf(g)
                            }
                            g.form == EventForm.WHILE_HOLDS -> Unit
                            g.where?.conjuncts()?.any { c -> c.unparen().let { it is VE.Call && it.name == "holds" } } == true -> Unit
                            else -> written += fieldsOf(g)
                        }
                    }
                }
                val known = knownValues(f.env)
                readOnly.distinct().forEach { n ->
                    val value = known[n]?.let { v -> OUTCOMES.entries.first { it.value == v && (fieldByName.getValue(n).valueType == "Ref") == (it.key != "OK") }.key }
                    line("invariant acc(tr.$n, 1/2) && tr.$n ${if (value != null) "== $value" else "!= 0"}", i, frame, "read only: the first call came before the loop")
                }
                if (written.isNotEmpty()) line("invariant ${written.distinct().joinToString(" && ") { "acc(tr.$it)" }}", i, frame, "the trace fields the loop writes")
            }
            line("invariant ${check(ClientIds.LD_WAIT, noObligations)}", i, Source.Kotlin(l.pos, ClientIds.LD_WAIT), ClientIds.LD_WAIT)
            if (raised.isNotEmpty()) {
                val ks = order(raised).map { if (it == ERR) "(MAY_ERR && exc == ERR)" else "exc == $it" }
                line("invariant ${(listOf("exc == NONE") + ks).joinToString(" || ")}", i, frame, "the exceptions the loop body raises")
            }
            l.hole?.let { h -> holeLines(h, l, f, i) }
            l.measure?.let { line("decreases ${expr(it)}", i, Source.Kotlin(l.pos, ClientIds.TERM), ClientIds.TERM) }
        }

        fun fieldsOf(g: Group): List<String> =
            g.events.flatMap { e ->
                val fl = fieldByName.getValue(e.name.text)
                when (e.form) {
                    EventForm.COUNT -> listOf(fl.outcome!!)
                    EventForm.TRAILING_FAILURES -> listOf(fl.outcome!!)
                    EventForm.HIT -> listOfNotNull(fl.hit, fl.value)
                    else -> listOfNotNull(fl.outcome, fl.value)
                }
            }.let { fs ->
                // In the order the macro writes them: counts, trailing failures, then hit fields.
                val counts = g.events.filter { it.form == EventForm.COUNT }.map { it.name.text }
                val trailing = g.events.filter { it.form == EventForm.TRAILING_FAILURES }.map { it.name.text }
                (counts + trailing + fs).distinct().filter { it in fs }
            }

        /** The hole of loop [l] (DESIGN-E 6.8): one invariant per group of lines that ends with a top-level `&&`. */
        fun holeLines(
            name: String,
            l: While,
            f: Frame,
            indent: Int,
        ) {
            val decl: HoleDecl = s.holes.firstOrNull { it.loop?.label == name } ?: fail(anchor, "no hole loop $name in the sidecar")
            val fnInline = f.fn
            val scope = (fnInline?.body ?: body).walk().flatMap { locals(it) }.toList() +
                (if (fnInline == null) entryFun?.params.orEmpty() + task?.params.orEmpty() else emptyList())
            val byKotlin = scope.groupBy { it.name.substringBefore("__") }.filterValues { ls -> ls.map { it.name }.distinct().size == 1 }.mapValues { root(it.value.first().name) } +
                scope.associate { it.name to root(it.name) }
            val rets = l.body.walk().filterIsInstance<Return>().filter { !tail(it) }
            val targets = rets.map { retTarget[it] ?: unitKey }.distinct()
            val returned = targets.singleOrNull()?.let { retFlag.getValue(it) }
            val result = targets.singleOrNull()?.let { t -> if (t === unitKey) entryFun?.result?.name else (t as Inline).out?.let { root(it.name) } }
            val names = HoleNames(byKotlin, returned, result, brkFlag[l])
            for ((text, pos) in holeGroups(decl)) {
                val v = Vexpr(text, pos)
                val e = translate(parseVE(v).unparen(), v, Ctx.Unit(task, names))
                line("invariant ${e.print()}", indent, Source.Sidecar(pos, ClientIds.HOLE), "hole")
            }
        }
    }

    /** The hole text in groups: split at each top-level `&&` that ends a line (DESIGN-E E3). */
    private fun holeGroups(h: HoleDecl): List<Pair<String, SPos>> {
        val text = h.text
        val whole = Vexpr(text, h.textPos)
        val cuts = mutableListOf<Int>()
        var depth = 0
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("//", i) -> {
                    while (i < text.length && text[i] != '\n') i++
                    continue
                }
                text[i] == '(' -> depth++
                text[i] == ')' -> depth--
                depth == 0 && text.startsWith("&&", i) && text.substring(i + 2).substringBefore('\n').let { it.isBlank() || it.trim().startsWith("//") } -> cuts += i
            }
            i++
        }
        val bounds = (listOf(-2) + cuts).zip(cuts + text.length).map { (a, b) -> a + 2 to b }
        return bounds.mapNotNull { (a, b) ->
            val part = text.substring(a, b)
            val lead = part.indexOfFirst { !it.isWhitespace() }
            if (lead < 0) null else part.substring(lead).trimEnd() to whole.posAt(a + lead)
        }
    }

    private companion object {
        const val PREC_AND = 4
    }
}
