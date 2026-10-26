package kv.sidecar

import kv.kvir.GhostKind

// Syntax tree of a sidecar spec (DESIGN-v0.md 4.2). Every node keeps its sidecar position for error reports.

/** 1-based position in a sidecar file. */
data class SPos(
    val file: String,
    val line: Int,
    val col: Int,
) {
    override fun toString(): String = "$file:$line:$col"
}

/** A sidecar error. [code] is a stable identifier for tests and tools; [message] is for people. */
data class SidecarError(
    val pos: SPos,
    val code: String,
    val message: String,
) {
    override fun toString(): String = "$pos: error[$code]: $message"
}

class SidecarException(
    val errors: List<SidecarError>,
) : Exception(errors.joinToString("\n"))

/** A name as written, with its position. Dotted Kotlin names keep their dots. */
data class Name(
    val text: String,
    val pos: SPos,
)

/** Viper expression text (DESIGN-v0.md 4.1). Continuation lines keep their indentation, so offsets map back to columns. */
data class Vexpr(
    val text: String,
    val pos: SPos,
) {
    /** Sidecar position of the character at [offset] in [text]. */
    fun posAt(offset: Int): SPos {
        val newline = text.lastIndexOf('\n', offset - 1)
        if (newline < 0) return pos.copy(col = pos.col + offset)
        return pos.copy(line = pos.line + text.substring(0, offset).count { it == '\n' }, col = offset - newline)
    }
}

/** A name with a type, e.g. `returns rej: Bool` or a probe parameter. */
data class Typed(
    val name: Name,
    val type: Name,
)

sealed interface Decl {
    val pos: SPos
}

/** `module FQN [client]`; [client] marks a client module (DESIGN-E 6.1): no state, its public functions run thread-modularly. */
data class ModuleDecl(
    val fqn: Name,
    override val pos: SPos,
    val client: Boolean = false,
) : Decl

data class SourceDecl(
    val path: String,
    override val pos: SPos,
) : Decl

/** Removed by [SidecarLoader], which puts the included declarations in its place. */
data class IncludeDecl(
    val path: String,
    override val pos: SPos,
) : Decl

/**
 * `type NAME = opaque [KCLASS]` when [template] is null, else `type NAME = template "..."`. [kclass] binds an opaque
 * type to a Kotlin class (DESIGN-B.md 2.2), e.g. `java.lang.reflect.Method`; null for a String type (DESIGN-v0.md 2.13).
 */
data class TypeDecl(
    val name: Name,
    val template: String?,
    override val pos: SPos,
    val kclass: Name? = null,
) : Decl

/** `set-once ID : NAME` (DESIGN-B.md 2.4): every action keeps each mapped key's value of state map [map]. */
data class SetOnceDecl(
    val id: Name,
    val map: Name,
    override val pos: SPos,
) : Decl

sealed interface StateKind {
    data class MapOf(
        val key: Name,
        val value: Name,
    ) : StateKind

    data object Counter : StateKind
}

data class StateDecl(
    val name: Name,
    val kind: StateKind,
    val kname: Name,
    override val pos: SPos,
) : Decl

data class FieldDecl(
    val name: Name,
    val type: Name,
    val nullable: Boolean,
    val kname: Name,
)

data class ClassDecl(
    val name: Name,
    val kname: Name,
    val fields: List<FieldDecl>,
    override val pos: SPos,
) : Decl

data class UseDecl(
    val model: Name,
    override val pos: SPos,
) : Decl

/** `enum NAME = KCLASS` (DESIGN-E 6.2): a Kotlin enum class whose entries are Int constants in declaration order. */
data class EnumDecl(
    val name: Name,
    val kclass: Name,
    override val pos: SPos,
) : Decl

/** `obligation NAME(TYPE, ...)` (DESIGN-E 3.1, 6.2): a linear obligation, an abstract Viper predicate. */
data class ObligationDecl(
    val name: Name,
    val types: List<Name>,
    override val pos: SPos,
) : Decl

/**
 * `creates NAME(ARGS) [if RESULT != null]` or `consumes NAME(ARGS)` of a trusted declaration (DESIGN-E 6.2): the
 * call creates or consumes one obligation. [args] name its parameters and results; [ifNotNull] is the result whose
 * non-null value is the condition of a creation (the `if` form of DESIGN-E 6.2, restricted to this shape).
 */
data class ObligationUse(
    val obligation: Name,
    val args: List<Name>,
    val pos: SPos,
    val ifNotNull: Name? = null,
)

/**
 * `trusted fun KSIG`: [fn] is the Kotlin name, [params] the parameter types as written. [fresh] is the class of
 * `returns fresh NAME` and [throws] the `throws` clause (DESIGN-B.md 3.2). [sha256] is the fingerprint the `reviewed`
 * note records (B6): of the Kotlin declaration from `fun` to its end. [reviewPos] is the `reviewed` keyword.
 */
data class TrustedDecl(
    val fn: Name,
    val params: List<Name>,
    val clock: Boolean,
    val returns: List<Typed>,
    val ghosts: List<Typed>,
    val ensures: List<Vexpr>,
    val effectsNone: Boolean,
    val reviewed: String?,
    override val pos: SPos,
    val fresh: Name? = null,
    val throws: Boolean = false,
    val sha256: String? = null,
    val reviewPos: SPos? = null,
    /**
     * DESIGN-E 6.2: per parameter, its model name and sidecar type when written `NAME: TYPE` (the model passes it),
     * or null when written as a bare Kotlin type (the model drops it). Empty if every parameter is bare.
     */
    val named: List<Typed?> = emptyList(),
    /** `trusted value KNAME`: a function-typed property the code invokes (DESIGN-E 6.2); false for `trusted fun`. */
    val value: Boolean = false,
    val creates: ObligationUse? = null,
    val consumes: ObligationUse? = null,
    /** `throws KIND {, KIND}` (DESIGN-E 3.5): the exception kinds; empty for B's bare `throws` or no clause. */
    val throwKinds: List<Name> = emptyList(),
) : Decl {
    val shortName: String get() = fn.text.substringAfterLast('.')

    /** The values a `free ... = call` item binds, in order: the named results, the ghost values, the throw flag. */
    val callValues: List<String> get() = (returns + ghosts).map { it.name.text } + listOfNotNull("throw flag".takeIf { throws })
}

data class GhostFieldDecl(
    val name: Name,
    val type: Name,
    val kind: GhostKind,
)

data class GhostRecordDecl(
    val name: Name,
    val index: Name,
    val fields: List<GhostFieldDecl>,
    override val pos: SPos,
) : Decl

data class WitnessDecl(
    val cls: Name,
    val field: Name,
    override val pos: SPos,
) : Decl

sealed interface Selector {
    val target: Name

    /** `fun KNAME`: the compute call inside that function. */
    data class Fun(
        override val target: Name,
    ) : Selector

    /** `compute in KNAME`: the one compute call inside that class, nested lambdas included. */
    data class ComputeIn(
        override val target: Name,
    ) : Selector
}

sealed interface FreeSource {
    val name: Name

    data class Local(
        override val name: Name,
    ) : FreeSource

    data class Param(
        override val name: Name,
    ) : FreeSource

    /** `this.KNAME`; [name] holds KNAME only, a dotted path of properties in a client module (DESIGN-E 3.6). */
    data class This(
        override val name: Name,
    ) : FreeSource

    data class Call(
        override val name: Name,
    ) : FreeSource
}

enum class Allowance(
    val keyword: String,
) { ENDS_ONLY_IF("ends_only_if"), UNBACKED_ONLY_IF("unbacked_only_if") }

sealed interface OpItem {
    val pos: SPos
}

/**
 * `free NAME [: TYPE] {, NAME} = SOURCE`. [type] is the abstract type of a String value (DESIGN-v0.md 4.2,
 * restriction 4): a sidecar `type` name such as Key or Token, written after the first name.
 */
data class FreeItem(
    val names: List<Name>,
    val source: FreeSource,
    override val pos: SPos,
    val type: Name? = null,
) : OpItem

data class ResultItem(
    val name: Name,
    override val pos: SPos,
) : OpItem

/**
 * A source line of an intent clause (DESIGN-C.md 5.2): `source "<anchor>" quote "<quote>" [sha256 "<hex>"]` or
 * `source none "<reason>"`. [text] is the line as written, from `source` through its last string.
 */
sealed interface SourceLine {
    val pos: SPos
    val text: String
}

/** `source "<anchor>" quote "<quote>" [sha256 "<hex>"]`: [sha256] is the fingerprint of the whole block at review. */
data class SourceQuote(
    val anchor: String,
    val quote: String,
    val sha256: String?,
    override val pos: SPos,
    override val text: String,
) : SourceLine

/** `source none "<reason>"`: the clause cites no text, for [reason]. */
data class SourceNone(
    val reason: String,
    override val pos: SPos,
    override val text: String,
) : SourceLine

/**
 * An intent clause (DESIGN-C.md 2.1, layer 4): an issue rule, an `ensures`, an allowance clause, an `invariant`, a
 * `lemma` or a probe. [reviewed] is its note, [sha256] the fingerprint the note records, [reviewPos] the `reviewed`
 * keyword, [unit] the clause text from its keyword through the end of its expression or body, and [sources] its
 * source lines.
 */
sealed interface IntentClause {
    val pos: SPos
    val reviewed: String?
    val sha256: String?
    val reviewPos: SPos?
    val unit: String
    val sources: List<SourceLine>

    /**
     * The text a `reviewed` fingerprint covers (DESIGN-v0.md 8.3 "reviewed 지문", DESIGN-C.md 5.2 rule 4): the clause
     * text, then each source line on its own line. Without source lines it is the clause text alone.
     */
    val reviewedText: String get() = (listOf(unit) + sources.map { it.text }).joinToString("\n")
}

/** An intent clause inside an operation: an issue rule, an `ensures` or an allowance clause. */
sealed interface ReviewedItem :
    OpItem,
    IntentClause {
    override val pos: SPos
}

data class IssueItem(
    val result: Name,
    val fields: List<Pair<Name, Vexpr>>,
    override val reviewed: String?,
    override val pos: SPos,
    override val sha256: String? = null,
    override val reviewPos: SPos? = null,
    override val unit: String = "",
    override val sources: List<SourceLine> = emptyList(),
) : ReviewedItem

data class AllowanceItem(
    val kind: Allowance,
    val id: Name,
    val expr: Vexpr,
    override val reviewed: String?,
    override val pos: SPos,
    override val sha256: String? = null,
    override val reviewPos: SPos? = null,
    override val unit: String = "",
    override val sources: List<SourceLine> = emptyList(),
) : ReviewedItem

data class EnsuresItem(
    val id: Name,
    val expr: Vexpr,
    override val pos: SPos,
    override val reviewed: String? = null,
    override val sha256: String? = null,
    override val reviewPos: SPos? = null,
    override val unit: String = "",
    override val sources: List<SourceLine> = emptyList(),
) : ReviewedItem

/** `guard ID: CALL requires VEXPR` (DESIGN-E 6.2): [expr] must hold before each call of the trusted [call]. */
data class GuardItem(
    val id: Name,
    val call: Name,
    val expr: Vexpr,
    override val pos: SPos,
    override val reviewed: String? = null,
    override val sha256: String? = null,
    override val reviewPos: SPos? = null,
    override val unit: String = "",
    override val sources: List<SourceLine> = emptyList(),
) : ReviewedItem

data class OperationDecl(
    val name: Name,
    val selector: Selector,
    val items: List<OpItem>,
    override val pos: SPos,
) : Decl {
    val free: List<FreeItem> get() = items.filterIsInstance<FreeItem>()
    val result: ResultItem? get() = items.filterIsInstance<ResultItem>().firstOrNull()
}

enum class PropertyKind { INVARIANT, LEMMA }

data class PropertyDecl(
    val kind: PropertyKind,
    val id: Name,
    val expr: Vexpr,
    override val pos: SPos,
    override val reviewed: String? = null,
    override val sha256: String? = null,
    override val reviewPos: SPos? = null,
    override val unit: String = "",
    override val sources: List<SourceLine> = emptyList(),
) : Decl,
    IntentClause

/** `KNAME@LINE` of `hole loop` (DESIGN-E 6.2): the loop at Kotlin line [line] of function [fn]. */
data class LoopRef(
    val fn: Name,
    val line: Int,
)

/**
 * [text] is the hole body between the triple quotes; [textPos] is the position of its first character. [loop] is
 * set for `hole loop KNAME@LINE` (DESIGN-E 6.8): the invariant of that loop; [name] is then `loop`.
 */
data class HoleDecl(
    val name: Name,
    val text: String,
    override val pos: SPos,
    val textPos: SPos,
    val loop: LoopRef? = null,
) : Decl

/** The form of a trace event (DESIGN-E 3.2, 6.2). */
enum class EventForm(
    val keyword: String,
) { FIRST("first"), WHILE_HOLDS("while holds"), COUNT("count"), LAST("last"), TRAILING_FAILURES("trailing failures of") }

/** `holds(NAME(PATTERN))`: an obligation with argument patterns; `_` matches any value. */
data class HoldsPattern(
    val obligation: Name,
    val args: List<Name>,
)

/**
 * One field of the trace record (DESIGN-E 3.2): `NAME = first CALL`, `CALL while holds(...)`, `count CALL`, `last
 * CALL` (each but `while holds` with an optional `where VEXPR`), or `trailing failures of NAME`.
 */
data class TraceEvent(
    val name: Name,
    val form: EventForm,
    val call: Name?,
    val where: Vexpr? = null,
    val holds: HoldsPattern? = null,
    val of: Name? = null,
)

/** `trace { NAME = event ... }` (DESIGN-E 3.2, 6.2): the event record of one thread run. */
data class TraceDecl(
    val events: List<TraceEvent>,
    override val pos: SPos,
) : Decl

/**
 * `assume ID: NAME` (DESIGN-E 4, 6.2): turns on a named assumption of a built-in model. It is signed like an intent
 * clause (DESIGN-E 2.5): source lines, then a `reviewed` note.
 */
data class AssumeDecl(
    val id: Name,
    val name: Name,
    override val pos: SPos,
    override val reviewed: String? = null,
    override val sha256: String? = null,
    override val reviewPos: SPos? = null,
    override val unit: String = "",
    override val sources: List<SourceLine> = emptyList(),
) : Decl,
    IntentClause

/** A probe call argument: positional when [name] is null. */
data class ProbeArg(
    val name: Name?,
    val value: Vexpr,
)

sealed interface ProbeStep {
    val pos: SPos
}

/**
 * `[OUT {, OUT} :=] OP(ARGS)`: one out is the result; a second one is `exc` of an operation that can throw
 * (DESIGN-B.md 5.1). No out calls an operation without a result (DESIGN-C.md 7.2). A scenario cell uses the same step.
 */
data class ProbeCall(
    val outs: List<Name>,
    val op: Name,
    val args: List<ProbeArg>,
    override val pos: SPos,
) : ProbeStep,
    CellStep

data class ProbeAssert(
    val expr: Vexpr,
    override val pos: SPos,
) : ProbeStep

/**
 * `probe [NAME](PARAMS) [requires E] { steps }` (DESIGN-C.md 7.2). [name] is null for the unnamed probe of v0; a
 * sidecar may have many named probes and one unnamed one.
 */
data class ProbeDecl(
    val params: List<Typed>,
    val requires: Vexpr?,
    val steps: List<ProbeStep>,
    override val pos: SPos,
    override val reviewed: String? = null,
    override val sha256: String? = null,
    override val reviewPos: SPos? = null,
    override val unit: String = "",
    override val sources: List<SourceLine> = emptyList(),
    val name: Name? = null,
) : Decl,
    IntentClause {
    /** The probe's name in reports: `probe`, or `probe NAME`. */
    val label: String get() = name?.let { "probe ${it.text}" } ?: "probe"

    /** Its Viper method: `probe`, or `probe_NAME` (as a lemma's is `lemma_ID`). */
    val method: String get() = name?.let { "probe_${it.text}" } ?: "probe"
}

/** A step of a scenario cell: a probe call ([ProbeCall]) or an [CellAssume]. */
sealed interface CellStep {
    val pos: SPos
}

/** `assume VEXPR`: the scenario goes on only where [expr] holds, e.g. "the first tryLock returned a backed token". */
data class CellAssume(
    val expr: Vexpr,
    override val pos: SPos,
) : CellStep

/** `outcome NAME: VEXPR`: one result a person can pick for the cell. */
data class CellOutcome(
    val name: Name,
    val expr: Vexpr,
)

/**
 * A scenario cell of a cell file (DESIGN-C.md 3.2): `cell ID(PARAMS) ["title"] [boundary] { steps; outcomes }`. The
 * steps run from the empty module; `kv spec check` asks Silicon which outcome the contracts decide at the end.
 * [boundary] marks a cell whose clock reading equals a lease: DESIGN-C.md 3.3 rule 5 always asks it.
 */
data class CellDecl(
    val id: Name,
    val params: List<Typed>,
    val title: String?,
    val steps: List<CellStep>,
    val outcomes: List<CellOutcome>,
    val pos: SPos,
    val boundary: Boolean = false,
)

/**
 * `review intent` (DESIGN-C.md 10 UC4): every intent clause of the sidecar needs a `reviewed` note and a source
 * line. Without it, only issue rules and allowance clauses need a note (U7).
 */
data class ReviewIntentDecl(
    override val pos: SPos,
) : Decl

/** A parsed sidecar, includes resolved. [file] is the file the user named. */
data class Sidecar(
    val file: String,
    val decls: List<Decl>,
) {
    val modules get() = decls.filterIsInstance<ModuleDecl>()
    val sources get() = decls.filterIsInstance<SourceDecl>()
    val types get() = decls.filterIsInstance<TypeDecl>()
    val setOnce get() = decls.filterIsInstance<SetOnceDecl>()
    val states get() = decls.filterIsInstance<StateDecl>()
    val classes get() = decls.filterIsInstance<ClassDecl>()
    val uses get() = decls.filterIsInstance<UseDecl>()
    val trusted get() = decls.filterIsInstance<TrustedDecl>()
    val ghosts get() = decls.filterIsInstance<GhostRecordDecl>()
    val witnesses get() = decls.filterIsInstance<WitnessDecl>()
    val operations get() = decls.filterIsInstance<OperationDecl>()
    val properties get() = decls.filterIsInstance<PropertyDecl>()
    val holes get() = decls.filterIsInstance<HoleDecl>()
    val probes get() = decls.filterIsInstance<ProbeDecl>()
    val enums get() = decls.filterIsInstance<EnumDecl>()
    val obligations get() = decls.filterIsInstance<ObligationDecl>()
    val traces get() = decls.filterIsInstance<TraceDecl>()
    val assumes get() = decls.filterIsInstance<AssumeDecl>()

    /** True for a client module (DESIGN-E 6.1). */
    val client get() = modules.firstOrNull()?.client == true

    /** True if the sidecar has the line `review intent` (DESIGN-C.md 10 UC4). */
    val reviewIntent get() = decls.any { it is ReviewIntentDecl }
}

/**
 * Every intent clause of [this] in file order, with the name messages give it: `the issue rule of tryLock`,
 * `ensures progress_live of tryLock`, `ends_only_if P3a of sweep`, `invariant P2`, `lemma P3c`, `the probe` or `probe NAME`.
 */
fun Sidecar.intentClauses(): List<Pair<String, IntentClause>> =
    decls.flatMap { d ->
        when (d) {
            is OperationDecl -> d.items.filterIsInstance<ReviewedItem>().map { describe(it, d) to it }
            is PropertyDecl -> listOf("${d.kind.name.lowercase()} ${d.id.text}" to d)
            is ProbeDecl -> listOf((if (d.name == null) "the probe" else d.label) to d)
            is AssumeDecl -> listOf("assume ${d.id.text}" to d)
            else -> emptyList()
        }
    }

/** The name messages give [item] of operation [op]. */
fun describe(
    item: ReviewedItem,
    op: OperationDecl,
): String =
    when (item) {
        is IssueItem -> "the issue rule of ${op.name.text}"
        is AllowanceItem -> "${item.kind.keyword} ${item.id.text} of ${op.name.text}"
        is EnsuresItem -> "ensures ${item.id.text} of ${op.name.text}"
        is GuardItem -> "guard ${item.id.text} of ${op.name.text}"
    }
