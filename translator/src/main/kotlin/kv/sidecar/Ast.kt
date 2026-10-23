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

data class ModuleDecl(
    val fqn: Name,
    override val pos: SPos,
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

/** `type NAME = opaque` when [template] is null, else `type NAME = template "..."`. */
data class TypeDecl(
    val name: Name,
    val template: String?,
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

/** `trusted fun KSIG`: [fn] is the Kotlin name, [params] the parameter types as written. */
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
) : Decl {
    val shortName: String get() = fn.text.substringAfterLast('.')
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

    /** `this.KNAME`; [name] holds KNAME only. */
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

data class FreeItem(
    val names: List<Name>,
    val source: FreeSource,
    override val pos: SPos,
) : OpItem

data class ResultItem(
    val name: Name,
    override val pos: SPos,
) : OpItem

data class IssueItem(
    val result: Name,
    val fields: List<Pair<Name, Vexpr>>,
    val reviewed: String?,
    override val pos: SPos,
) : OpItem

data class AllowanceItem(
    val kind: Allowance,
    val id: Name,
    val expr: Vexpr,
    val reviewed: String?,
    override val pos: SPos,
) : OpItem

data class EnsuresItem(
    val id: Name,
    val expr: Vexpr,
    override val pos: SPos,
) : OpItem

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
) : Decl

/** [text] is the hole body between the triple quotes; [textPos] is the position of its first character. */
data class HoleDecl(
    val name: Name,
    val text: String,
    override val pos: SPos,
    val textPos: SPos,
) : Decl

/** A probe call argument: positional when [name] is null. */
data class ProbeArg(
    val name: Name?,
    val value: Vexpr,
)

sealed interface ProbeStep {
    val pos: SPos
}

data class ProbeCall(
    val out: Name,
    val op: Name,
    val args: List<ProbeArg>,
    override val pos: SPos,
) : ProbeStep

data class ProbeAssert(
    val expr: Vexpr,
    override val pos: SPos,
) : ProbeStep

data class ProbeDecl(
    val params: List<Typed>,
    val requires: Vexpr?,
    val steps: List<ProbeStep>,
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
}
