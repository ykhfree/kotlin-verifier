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

/**
 * A sidecar `type`: opaque when [template] is null, else a string template abstracted to its Long part. [kclass] is
 * the Kotlin class an opaque type stands for (DESIGN-B.md 2.2), e.g. `java/lang/reflect/Method`; null for a String type.
 */
data class AbsType(
    val name: String,
    val template: String?,
    val kclass: String? = null,
)

/** An object module (v0: a Kotlin object or companion object) or an instance module (DESIGN-B.md 2.1: a class, S = this). */
enum class ModuleKind { OBJECT, INSTANCE }

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
    val kind: ModuleKind = ModuleKind.OBJECT,
    /** The trusted `returns fresh` functions, by short name, with the class each returns (DESIGN-B.md 3.2, W15). */
    val freshFunctions: Map<String, String> = emptyMap(),
    /** The module file. Positions in other files print with their file name. */
    val file: String? = null,
)

sealed interface StateVar {
    val name: String
    val kotlin: String
    val pos: Pos
}

/** `ConcurrentHashMap<K, C>` module field. [setOnce] is the ID of its `set-once` declaration (DESIGN-B.md 2.4), if any. */
data class MapVar(
    override val name: String,
    override val kotlin: String,
    override val pos: Pos,
    val key: KType.Abs,
    val valueClass: String,
    val setOnce: String? = null,
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
    /** The sidecar `result`: its name and type. Null without one. */
    val result: Local?,
    /**
     * The operation section (DESIGN-B.md 2.6): what the operation does around [action]. [RunAction] marks where the
     * action runs, at most once per path; every path ends with one [Return] or [Raise] (W10). An object module's
     * operation is `run action; return e` (DESIGN-v0.md 2.4, 8.3).
     */
    val body: List<Stmt>,
    /** The reserve step before [action] (DESIGN-D.md 2.1, 6.2); null for an operation of one atomic action. */
    val reserve: Reserve? = null,
)

/**
 * The reserve step of an operation (DESIGN-D.md 2.1, 6.2): one atomic step outside the action and before it,
 * `counter := counter + 1; local := counter`. [local] is the reserved val: a token from a template ([template] is the
 * template's position) or a raw Long (an Int). The action reads it as the free value of origin [Reservation].
 */
data class Reserve(
    val local: Local,
    val counter: CounterVar,
    /** The `incrementAndGet` call. */
    val pos: Pos,
    val template: Pos?,
    /** The ASSUMPTION diagnostic of the unbounded increment (W13). */
    val assumption: String?,
    /** Ids of the FIR nodes the step translated: the val and, for a helper call, the helper (W14). */
    val translated: Set<Int>,
    /** The Kotlin source of the increment, for reports, e.g. `tokenSequence.incrementAndGet()`. */
    val kotlin: String,
)

/** The value an operation of the shape `run action; return e` returns; null for another shape or no value. */
val Operation.returnedValue: Expr?
    get() = body.takeIf { it.size == 2 && it[0] is RunAction }?.let { (it[1] as? Return)?.e }

/** True if a path of the operation raises: a `throws` call or a failed `!!` (DESIGN-B.md 2.7). */
val Operation.canThrow: Boolean
    get() = (body.walk() + action.body.walk()).any { it is Raise }

/** The map call of an action (DESIGN-B.md 2.3), with its method name. Only [COMPUTE] and [COMPUTE_IF_ABSENT] have a lambda body. */
enum class ActionKind(
    val method: String,
) { COMPUTE("compute"), COMPUTE_IF_ABSENT("computeIfAbsent"), PUT_IF_ABSENT("putIfAbsent"), PUT("put"), REMOVE("remove") }

data class Action(
    val id: String,
    /** Position of the map call. */
    val pos: Pos,
    val map: MapVar,
    val key: FreeValue,
    /** The key's current value at the start of the action: compute's value parameter, else `cur_<line>_<col>`. */
    val entry: Local,
    val free: List<FreeValue>,
    /** The lambda body of [ActionKind.COMPUTE] and [ActionKind.COMPUTE_IF_ABSENT]; empty for the other kinds. */
    val body: List<Stmt>,
    /** Filled by the ghost binder only. */
    val commit: List<GhostOp>,
    val status: Status,
    /** Ids of the FIR nodes this action translated (W14). An inlined helper's nodes may appear in several actions. */
    val translated: Set<Int>,
    val kind: ActionKind = ActionKind.COMPUTE,
    /** The value [ActionKind.PUT_IF_ABSENT] and [ActionKind.PUT] map the key to. */
    val value: Expr? = null,
    /**
     * The local that receives the call's result in the operation section; null if the result is unused. compute:
     * the new value; computeIfAbsent: the current or computed value; putIfAbsent, put, remove: [entry].
     */
    val out: Local? = null,
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

/** A value computed outside every action by code the model does not translate, e.g. a call (DESIGN-B.md 2.6 rule 5). */
data class Computed(
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

/**
 * The reserved val of the operation's reserve step (DESIGN-D.md 6.2); [pos] is the increment. The action method takes
 * it as a parameter, named or not: its precondition reads it.
 */
data class Reservation(
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

/** Runs the operation's action (DESIGN-B.md 5.2); the action's `out`, if any, holds the call's result after it. */
data class RunAction(
    override val pos: Pos,
) : Stmt

/**
 * A call of the trusted `returns fresh` function [fn] that returned normally: [v] is a new object of [cls]. The tool
 * records it in `S.created` with `gFor := k` (DESIGN-B.md 3.2, 4.1).
 */
data class Fresh(
    val v: Local,
    val cls: ObjClass,
    val fn: String,
    override val pos: Pos,
) : Stmt

/**
 * A read of [map] outside every action, before the action (DESIGN-B.md 2.4): `get` gives [v] (an object or null),
 * `containsKey` ([contains]) gives a Bool. With [contract] (only for a `set-once` map, W16) the read value is null or
 * the map's current value, and a true containsKey means the key is mapped; without it, [v] is arbitrary.
 */
data class RacyGet(
    val v: Local,
    val map: MapVar,
    val key: Expr,
    val contains: Boolean,
    val contract: Boolean,
    override val pos: Pos,
) : Stmt

/** Ends the operation with [e] as its result; [e] is null for an operation without a value. */
data class Return(
    val e: Expr?,
    override val pos: Pos,
) : Stmt

/**
 * Ends the operation, or the action, with an exception (`exc := true`): a `throws` call threw or `!!` met null
 * (DESIGN-B.md 2.7). In an action, the map is not written.
 */
data class Raise(
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

/** `a ?: b`: [a] if it is not null, else [b] (both pure). Its type is [b]'s. */
data class Elvis(
    val a: Expr,
    val b: Expr,
    override val pos: Pos,
) : Expr {
    override val type get() = b.type
}

/** `if (cond) a else b` as a value (DESIGN-D.md 6.2, 7): an operation result. [type] is the type of both values. */
data class Ite(
    val cond: Expr,
    val a: Expr,
    val b: Expr,
    override val type: KType,
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

    /** A declaration the tool reads the module structure from: module object, state, sidecar class, field, defaults. */
    STRUCTURE("structure"),
    NOT_MODELED("not-modeled"),
    UNSUPPORTED("unsupported"),

    /** The body of a reviewed trusted function: read by a person, checked by the tool, not translated (DESIGN-B.md 3.3 rule 5). */
    TRUSTED("trusted"),
}

/** How the translator handled one FIR node outside the actions. */
data class Disposition(
    val node: Int,
    val kind: DispositionKind,
    val what: String,
    val pos: Pos,
)

enum class DiagnosticKind { UNSUPPORTED, ASSUMPTION, INFO }

/**
 * True if the module verdict cannot be PROVEN (W12, U3): an action is unsupported, or the ledger or a module
 * diagnostic reports something unsupported. The verdict is then INCOMPLETE unless Silicon finds a failure.
 */
val Module.incomplete: Boolean
    get() =
        (operations.map { it.action } + strayActions).any { it.status is Status.Unsupported } ||
            ledger.any { it.kind == DispositionKind.UNSUPPORTED } ||
            diagnostics.any { it.kind == DiagnosticKind.UNSUPPORTED }

data class Diagnostic(
    val id: String,
    val kind: DiagnosticKind,
    val message: String,
    val pos: Pos,
)
