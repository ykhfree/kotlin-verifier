package kv.kvir

// KVIR: the typed, A-normal, structured IR between FIR and Viper (DESIGN-v0.md section 1).
// It holds only what the code does. Ghost updates live in Action.commit, which only the ghost binder fills.

sealed interface KType {
    /** Kotlin Long/Int as a mathematical integer. */
    data object IntT : KType

    data object BoolT : KType

    /** An abstract String type the sidecar declares, e.g. Key, Token, Token?. */
    data class Abs(
        val name: String,
        val nullable: Boolean,
    ) : KType

    /** An object class reachable from module state, e.g. LockInfo?. */
    data class RefT(
        val cls: String,
        val nullable: Boolean,
    ) : KType
}

/** A sidecar `type`: opaque when [template] is null, else a string template abstracted to its Long part. */
data class AbsType(
    val name: String,
    val template: String?,
)

enum class GhostKind { SET_ONCE, FROZEN, ENDS }

/** The sidecar `ghost record` schema. W9 and W11 read it. */
data class GhostSchema(
    val name: String,
    val fields: Map<String, GhostKind>,
)

data class Module(
    /** FQN of the module object, e.g. `...KeyLocalLock.Companion`. */
    val id: String,
    val types: List<AbsType>,
    val state: List<StateVar>,
    val classes: List<ObjClass>,
    val ghost: GhostSchema?,
    val operations: List<Operation>,
    /** compute calls that no sidecar operation names. */
    val strayActions: List<Action>,
    /** Number of FIR nodes in the module file. The frontend numbers them 0 until this count. */
    val firNodeCount: Int,
    /** One entry per FIR node outside actions (DESIGN-v0.md 2.14). */
    val ledger: List<Disposition>,
    val diagnostics: List<Diagnostic>,
)

sealed interface StateVar {
    val name: String
    val kotlin: String
    val pos: Pos
}

/** `ConcurrentHashMap<String, C>` module field. */
data class MapVar(
    override val name: String,
    override val kotlin: String,
    override val pos: Pos,
    val key: KType.Abs,
    val valueClass: String,
) : StateVar

/** `AtomicLong` module field. */
data class CounterVar(
    override val name: String,
    override val kotlin: String,
    override val pos: Pos,
    val initial: Long,
) : StateVar

data class ObjClass(
    val name: String,
    val kotlin: String,
    val fields: List<ObjField>,
    /** Constructor default values, by field name. */
    val defaults: Map<String, Expr>,
    val pos: Pos,
)

data class ObjField(
    val name: String,
    val kotlin: String,
    val type: KType,
    /** Trusted model of a flattened field, e.g. `jdk.Semaphore`; null for a plain field. */
    val model: String?,
    val volatile: Boolean,
    val pos: Pos,
)

data class Operation(
    val name: String,
    val kotlin: String,
    val pos: Pos,
    val action: Action,
    val cells: List<Cell>,
    val result: OpResult?,
)

/** The operation result: [local] is the sidecar `result` name, [e] reads cells and constants (W10). */
data class OpResult(
    val local: Local,
    val e: Expr,
    val pos: Pos,
)

data class Action(
    val id: String,
    /** Position of the compute call. */
    val pos: Pos,
    val map: MapVar,
    val key: FreeValue,
    val entry: Local,
    val free: List<FreeValue>,
    val body: List<Stmt>,
    /** Filled by the ghost binder only. */
    val commit: List<GhostOp>,
    val status: Status,
    /** Ids of the FIR nodes this action translated (W14). An inlined helper's nodes may appear in several actions. */
    val translated: Set<Int>,
)

sealed interface Status {
    data object Supported : Status

    data class Unsupported(
        val diagnostics: List<Diagnostic>,
    ) : Status
}

/** A local variable. [name] is already mangled (`<name>__<line>`, DESIGN-v0.md 5.5). */
data class Local(
    val name: String,
    val type: KType,
    val pos: Pos,
)

/** A value the action reads but did not compute: an arbitrary value of its type (DESIGN-v0.md 2.12). */
data class FreeValue(
    val local: Local,
    val origin: Origin,
    /** The sidecar `free` name; null for an unnamed free value. */
    val sidecarName: String?,
    /** The Kotlin source of the value, for reports, e.g. `completeKey` or `this.lockTimeoutMillis`. */
    val kotlin: String,
)

/** A non-escaping local `AtomicReference` or `AtomicBoolean`, represented by one local (W8). */
data class Cell(
    val local: Local,
    val init: Expr,
    val pos: Pos,
)

sealed interface Origin {
    val pos: Pos
}

/** A local or parameter declared outside the lambda. */
data class Captured(
    override val pos: Pos,
) : Origin

/** An immutable property of the receiver instance. */
data class InstanceVal(
    val property: String,
    override val pos: Pos,
) : Origin

/** compute's key argument. */
data class ActionKey(
    override val pos: Pos,
) : Origin

/** A trusted clock read. */
data class ClockRead(
    override val pos: Pos,
) : Origin

/** A non-deterministic trusted call inside the action; [pos] is the call. */
data class NondetCall(
    val model: String,
    override val pos: Pos,
) : Origin

/** Module state read outside every action. */
data class RacyRead(
    override val pos: Pos,
) : Origin

sealed interface Stmt {
    val pos: Pos
}

/** Declares [v]. A [mutable] local may later be the target of [Assign] or of an op's output. */
data class Let(
    val v: Local,
    val e: Expr,
    val mutable: Boolean,
    override val pos: Pos,
) : Stmt

/** Assigns a mutable local or a cell. */
data class Assign(
    val v: Local,
    val e: Expr,
    override val pos: Pos,
) : Stmt

data class FieldWrite(
    val obj: Expr,
    val field: ObjField,
    val e: Expr,
    override val pos: Pos,
) : Stmt

/**
 * A trusted-model operation, e.g. `jdk.AtomicBoolean.compareAndSet`. [out] receives the result: it declares a new
 * local, or assigns a mutable local that is already in scope (W4). [assumption] names the ASSUMPTION diagnostic of
 * an operation the model treats as unbounded (W13).
 */
data class ModelOp(
    val out: Local?,
    val op: String,
    val target: Target,
    val args: List<Expr>,
    val assumption: String?,
    override val pos: Pos,
) : Stmt

/** Allocates [v]; [init] gives every field its value, by field name. */
data class New(
    val v: Local,
    val cls: ObjClass,
    val init: Map<String, Expr>,
    override val pos: Pos,
) : Stmt

/** A non-deterministic trusted call. Each out is a named free value bound here, or a new local. */
data class Nondet(
    val outs: List<Local>,
    val model: String,
    override val pos: Pos,
) : Stmt

data class If(
    val cond: Expr,
    val then: List<Stmt>,
    val els: List<Stmt>,
    override val pos: Pos,
) : Stmt

data class Exit(
    val result: ExitResult,
    override val pos: Pos,
) : Stmt

data class Unsupported(
    val what: String,
    val reason: String,
    override val pos: Pos,
) : Stmt

sealed interface ExitResult {
    /** The lambda returned its value parameter. */
    data object Keep : ExitResult

    /** The lambda returned null. */
    data object Remove : ExitResult

    /** The lambda returned another object. */
    data class Put(
        val e: Expr,
    ) : ExitResult
}

sealed interface Target

/** A flattened field, e.g. `existing.permits`. */
data class FieldTarget(
    val obj: Expr,
    val field: ObjField,
) : Target

/** A module counter. */
data class StateTarget(
    val v: StateVar,
) : Target

/** A pure expression (W2: no node of this hierarchy has an effect). */
sealed interface Expr {
    val type: KType
    val pos: Pos
}

data class IntLit(
    val value: Long,
    override val pos: Pos,
) : Expr {
    override val type get() = KType.IntT
}

data class BoolLit(
    val value: Boolean,
    override val pos: Pos,
) : Expr {
    override val type get() = KType.BoolT
}

data class NullLit(
    override val type: KType,
    override val pos: Pos,
) : Expr

data class Var(
    val local: Local,
    override val pos: Pos,
) : Expr {
    override val type get() = local.type
}

data class FieldRead(
    val obj: Expr,
    val field: ObjField,
    override val pos: Pos,
) : Expr {
    override val type get() = field.type
}

enum class ArithOp(
    val symbol: String,
) { PLUS("+"), MINUS("-") }

/** `Long` arithmetic as mathematical integers; [assumption] names its ASSUMPTION diagnostic (W13). */
data class Arith(
    val op: ArithOp,
    val a: Expr,
    val b: Expr,
    val assumption: String?,
    override val pos: Pos,
) : Expr {
    override val type get() = KType.IntT
}

enum class CmpOp(
    val symbol: String,
) { EQ("=="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">=") }

data class Cmp(
    val op: CmpOp,
    val a: Expr,
    val b: Expr,
    override val pos: Pos,
) : Expr {
    override val type get() = KType.BoolT
}

data class Not(
    val a: Expr,
    override val pos: Pos,
) : Expr {
    override val type get() = KType.BoolT
}

data class And(
    val a: Expr,
    val b: Expr,
    override val pos: Pos,
) : Expr {
    override val type get() = KType.BoolT
}

data class Or(
    val a: Expr,
    val b: Expr,
    override val pos: Pos,
) : Expr {
    override val type get() = KType.BoolT
}

/** The token a string template builds from the Long [n] (DESIGN-v0.md 2.9). [type] is the template type. */
data class TokenOf(
    val n: Expr,
    override val type: KType.Abs,
    override val pos: Pos,
) : Expr

/** The entry of [key] carries [token] in its witness field. Commit vocabulary only. */
data class Carried(
    val key: Expr,
    val token: Expr,
    override val pos: Pos,
) : Expr {
    override val type get() = KType.BoolT
}

sealed interface GhostOp {
    val pos: Pos
}

/** END rule: the pre-state token of the key's entry ends if the post-state entry no longer carries it. */
data class EndIfNotCarried(
    val witness: ObjField,
    override val pos: Pos,
) : GhostOp

/** ISSUE rule: [fields] gives each frozen ghost field its value. */
data class Issue(
    val token: Expr,
    val fields: Map<String, Expr>,
    override val pos: Pos,
) : GhostOp

/** Set-once check: [token] was not issued before. */
data class AssertFresh(
    val token: Expr,
    override val pos: Pos,
) : GhostOp

enum class DispositionKind(
    val label: String,
) {
    FREE_SOURCE("free-source"),
    RACY_READ("racy-read"),
    CELL("cell"),
    NOT_MODELED("not-modeled"),
    UNSUPPORTED("unsupported"),
}

/** How the translator handled one FIR node outside the actions. */
data class Disposition(
    val node: Int,
    val kind: DispositionKind,
    val what: String,
    val pos: Pos,
)

enum class DiagnosticKind { UNSUPPORTED, ASSUMPTION, INFO }

data class Diagnostic(
    val id: String,
    val kind: DiagnosticKind,
    val message: String,
    val pos: Pos,
)
